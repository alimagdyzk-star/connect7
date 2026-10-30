package com.example.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.webkit.MimeTypeMap
import com.example.core.diagnostics.DiagnosticLogger
import com.example.core.diagnostics.LogCategory
import com.example.core.protocol.NetworkPacket
import com.example.core.security.CryptoIdentityManager
import com.example.core.security.PathValidator
import com.example.core.security.SafSharedFoldersManager
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Properties
import java.util.UUID
import java.util.Vector
import java.util.concurrent.ConcurrentHashMap
import javax.net.ssl.SSLSocket

data class RemoteFileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val lastModifiedEpochMs: Long,
    val mimeType: String,
    val isReadable: Boolean = true,
    val isWritable: Boolean = true,
    val sftpRealPath: String? = null
)

data class RemoteCommandItem(
    val key: String,
    val name: String,
    val command: String
)

/**
 * Stores dynamic SFTP credentials negotiated over TLS (`kdeconnect.sftp`) OR configured manually
 * for OpenSSH / SFTP desktop workstations.
 */
data class SftpSessionCredentials(
    val primaryHost: String,
    val fallbackHost: String? = null,
    val port: Int,
    val username: String,
    val password: String,
    val rootPath: String,
    val multiPaths: List<String> = emptyList(),
    val pathNames: List<String> = emptyList(),
    val isManualSsh: Boolean = false
)

/**
 * Active encrypted TLS session with a real KDE Connect / ApexConnect peer on the LAN.
 */
class ActiveTlsPeerSession(
    val deviceId: String,
    val ipAddress: String,
    val tcpPort: Int,
    val sslSocket: SSLSocket,
    val peerCertificate: X509Certificate,
    val establishedAtMs: Long = System.currentTimeMillis()
) {
    private val writer = OutputStreamWriter(sslSocket.outputStream, Charsets.UTF_8)
    private val writeLock = Any()

    fun sendPacketLine(serializedPacket: String) {
        synchronized(writeLock) {
            writer.write(serializedPacket)
            writer.flush()
        }
    }

    fun isHealthy(): Boolean =
        !sslSocket.isClosed && sslSocket.isConnected && !sslSocket.isOutputShutdown

    fun close() {
        runCatching { sslSocket.close() }
    }
}

/**
 * Authentic KDE Connect LAN Discovery, Mutual TLS Transport, SFTP Client, and Peer-to-Peer VFS Engine.
 */
class LanLinkEngine(
    private val context: Context,
    private val identityManager: CryptoIdentityManager,
    val safFoldersManager: SafSharedFoldersManager,
    private val logger: DiagnosticLogger,
    private val onPeerConnectedOrUpdated: suspend (
        deviceId: String,
        deviceName: String,
        deviceType: String,
        ipAddress: String,
        tcpPort: Int,
        protocolVersion: Int,
        certificateBytes: ByteArray?,
        incomingCapabilities: List<String>,
        outgoingCapabilities: List<String>
    ) -> Unit,
    private val onPeerDisconnected: suspend (deviceId: String) -> Unit,
    private val onPacketReceived: suspend (senderDeviceId: String, packet: NetworkPacket, payloadBytes: ByteArray?) -> Unit
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mdnsResolveMutex = Mutex()
    private val tcpReadyDeferred = CompletableDeferred<Int>()

    @Volatile
    var activeTcpPort: Int = DEFAULT_KDE_PORT
        private set

    @Volatile
    var activeUdpPort: Int = 0
        private set

    private var tcpServerSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    private val activeSessions = ConcurrentHashMap<String, ActiveTlsPeerSession>()
    private val connectingPeers = ConcurrentHashMap.newKeySet<String>()
    private val sftpCredentialsByDevice = ConcurrentHashMap<String, SftpSessionCredentials>()
    private val pendingVfsResponses = ConcurrentHashMap<String, CompletableDeferred<NetworkPacket>>()

    val authorizedVaultRoot: File by lazy {
        File(context.filesDir, "shared_export").apply {
            if (!exists()) mkdirs()
            File(this, "Documents").mkdirs()
            File(this, "Pictures").mkdirs()
            File(this, "Downloads").mkdirs()
        }
    }

    val persistentAttachmentDir: File by lazy {
        File(context.filesDir, "space_attachments").apply {
            if (!exists()) mkdirs()
        }
    }

    @Volatile
    private var isStopping: Boolean = false

    val isMulticastLockHeld: Boolean
        get() = multicastLock?.isHeld == true

    fun start() {
        isStopping = false
        acquireWifiMulticastLock()
        engineScope.launch {
            startTcpListenerRange()
            registerMdnsService()
            discoverMdnsPeers()
        }
        engineScope.launch {
            startUdpDiscoveryListener()
        }
    }

    suspend fun awaitReady(timeoutMs: Long = 2000L) {
        runCatching { withTimeout(timeoutMs) { tcpReadyDeferred.await() } }
    }

    fun stop() {
        isStopping = true
        val sessions = activeSessions.values.toList()
        activeSessions.clear()
        connectingPeers.clear()
        sessions.forEach { it.close() }
        runCatching { tcpServerSocket?.close() }
        runCatching { udpSocket?.close() }
        stopMdns()
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
        logger.info(LogCategory.SYSTEM, "LanLinkEngine stopped")
    }

    fun isSessionActive(deviceId: String): Boolean =
        activeSessions[deviceId]?.isHealthy() == true

    fun hasSftpCredentials(deviceId: String): Boolean =
        sftpCredentialsByDevice.containsKey(deviceId)

    fun configureManualSftpCredentials(
        deviceId: String,
        host: String,
        port: Int,
        username: String,
        password: String,
        rootPath: String = "/"
    ) {
        sftpCredentialsByDevice[deviceId] = SftpSessionCredentials(
            primaryHost = host.trim(),
            port = port.coerceIn(1, 65535),
            username = username.trim(),
            password = password,
            rootPath = rootPath.trim().ifBlank { "/" },
            isManualSsh = true
        )
        logger.info(
            LogCategory.FILES,
            "Configured manual SFTP/SSH credentials for peer $deviceId -> ${host.trim()}:$port (user=${username.trim()}, root=$rootPath)"
        )
    }

    fun getLocalLanIpAddresses(): List<String> {
        return runCatching {
            val result = mutableListOf<String>()
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
            while (interfaces.hasMoreElements()) {
                val nif = interfaces.nextElement()
                if (!nif.isUp || nif.isLoopback) continue
                val addrs = nif.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        addr.hostAddress?.let { result.add(it) }
                    }
                }
            }
            result
        }.getOrDefault(emptyList())
    }

    private fun acquireWifiMulticastLock() {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val lock = wifi?.createMulticastLock("ApexConnectKdeDiscoveryLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            multicastLock = lock
            logger.info(
                LogCategory.DISCOVERY,
                "WifiManager.MulticastLock acquired=${lock?.isHeld == true}"
            )
        }.onFailure { err ->
            logger.warn(LogCategory.DISCOVERY, "Failed to acquire MulticastLock: ${err.message}")
        }
    }

    // =========================================================================
    // 1. mDNS / NSD (`_kdeconnect._udp.`) DISCOVERY & SEQUENTIAL RESOLUTION
    // =========================================================================

    private fun registerMdnsService() {
        runCatching {
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = identityManager.localDeviceId
                serviceType = "_kdeconnect._udp."
                port = activeTcpPort
            }
            val regListener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) {
                    logger.info(LogCategory.DISCOVERY, "mDNS registered '${info.serviceName}' on port $activeTcpPort")
                }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {
                    logger.warn(LogCategory.DISCOVERY, "mDNS registration failed (code=$errorCode)")
                }
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
            nsdRegistrationListener = regListener
            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)
        }.onFailure { err ->
            logger.warn(LogCategory.DISCOVERY, "mDNS registerService error: ${err.message}")
        }
    }

    private fun discoverMdnsPeers() {
        runCatching {
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val discListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) {
                    logger.info(LogCategory.DISCOVERY, "mDNS discovery started for $regType")
                }
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceName == identityManager.localDeviceId) return
                    engineScope.launch {
                        // Serialize NsdManager.resolveService calls to avoid FAILURE_ALREADY_ACTIVE (code 3)
                        mdnsResolveMutex.withLock {
                            val resolvedDone = CompletableDeferred<Unit>()
                            runCatching {
                                nsd.resolveService(service, object : NsdManager.ResolveListener {
                                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                                        logger.warn(
                                            LogCategory.DISCOVERY,
                                            "mDNS resolve failed for '${serviceInfo.serviceName}' (code=$errorCode)"
                                        )
                                        resolvedDone.complete(Unit)
                                    }

                                    override fun onServiceResolved(resolved: NsdServiceInfo) {
                                        val host = resolved.host?.hostAddress
                                        logger.info(
                                            LogCategory.DISCOVERY,
                                            "mDNS resolved peer '${resolved.serviceName}' -> $host:${resolved.port}"
                                        )
                                        if (!host.isNullOrBlank() && !host.contains('%')) {
                                            engineScope.launch {
                                                broadcastUdpIdentity(unicastHost = host)
                                            }
                                        }
                                        resolvedDone.complete(Unit)
                                    }
                                })
                            }.onFailure { resolvedDone.complete(Unit) }
                            runCatching { withTimeout(2500L) { resolvedDone.await() } }
                        }
                    }
                }
                override fun onServiceLost(service: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                    logger.warn(LogCategory.DISCOVERY, "mDNS startDiscovery failed (code=$errorCode)")
                }
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            nsdDiscoveryListener = discListener
            nsd.discoverServices("_kdeconnect._udp.", NsdManager.PROTOCOL_DNS_SD, discListener)
        }.onFailure { err ->
            logger.warn(LogCategory.DISCOVERY, "mDNS discoverServices error: ${err.message}")
        }
    }

    private fun stopMdns() {
        val nsd = runCatching { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull() ?: return
        nsdDiscoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        nsdRegistrationListener?.let { runCatching { nsd.unregisterService(it) } }
    }

    // =========================================================================
    // 2. TCP SERVER (PORTS 1716..1764) & INVERTED TLS CLIENT UPGRADE
    // =========================================================================

    private suspend fun startTcpListenerRange() = withContext(Dispatchers.IO) {
        var boundServer: ServerSocket? = null
        for (port in DEFAULT_KDE_PORT..MAX_KDE_PORT) {
            boundServer = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
            }.getOrNull()
            if (boundServer != null) break
        }
        if (boundServer == null) {
            boundServer = ServerSocket(0).apply { reuseAddress = true }
        }
        tcpServerSocket = boundServer
        activeTcpPort = boundServer.localPort
        if (!tcpReadyDeferred.isCompleted) tcpReadyDeferred.complete(activeTcpPort)

        logger.info(
            LogCategory.DISCOVERY,
            "TCP listener bound on 0.0.0.0:$activeTcpPort (LAN IPs: ${getLocalLanIpAddresses()})"
        )

        engineScope.launch {
            while (isActive && !boundServer.isClosed) {
                val client = runCatching { boundServer.accept() }.getOrNull() ?: break
                engineScope.launch {
                    handleIncomingPeerTcpConnection(client)
                }
            }
        }
    }

    /**
     * Triggered when a remote KDE Connect device connects to OUR TCP listener after receiving our
     * UDP broadcast. Per KDE Connect protocol:
     * 1. Remote TCP client sends cleartext `kdeconnect.identity` line first.
     * 2. We upgrade the socket to TLS in **TLS CLIENT mode** (`useClientMode = true`).
     */
    private suspend fun handleIncomingPeerTcpConnection(rawSocket: Socket) = withContext(Dispatchers.IO) {
        val remoteIp = rawSocket.inetAddress?.hostAddress ?: "unknown"
        var peerIdForCleanup = ""
        runCatching {
            rawSocket.soTimeout = 10_000
            rawSocket.keepAlive = true
            val identityLine = readLineUnbuffered(rawSocket.getInputStream()) ?: run {
                logger.warn(LogCategory.TLS, "Incoming TCP from $remoteIp closed before sending cleartext identity")
                rawSocket.close()
                return@withContext
            }
            val identityPacket = NetworkPacket.parse(identityLine)
            if (identityPacket.type != NetworkPacket.TYPE_IDENTITY) {
                logger.warn(LogCategory.TLS, "Incoming TCP from $remoteIp sent unexpected packet: ${identityPacket.type}")
                rawSocket.close()
                return@withContext
            }

            val peerDeviceId = identityPacket.getString("deviceId")
            val peerName = identityPacket.getString("deviceName", "Unknown")
            if (peerDeviceId.isBlank() || peerDeviceId == identityManager.localDeviceId) {
                rawSocket.close()
                return@withContext
            }
            peerIdForCleanup = peerDeviceId

            // Prevent duplicate UDP echo storms from tearing down a healthy session established moments ago
            val existing = activeSessions[peerDeviceId]
            if (existing != null && existing.isHealthy() &&
                (System.currentTimeMillis() - existing.establishedAtMs) < 2500L
            ) {
                logger.info(
                    LogCategory.TLS,
                    "Ignoring duplicate incoming TCP from '$peerName' ($remoteIp) — healthy TLS link already active"
                )
                rawSocket.close()
                return@withContext
            }

            logger.logPacket("RECV-TCP-CLEAR", "$peerName@$remoteIp", identityPacket, LogCategory.DISCOVERY)

            // Inverted TLS: we accepted TCP connection -> we act as TLS Client (`useClientMode = true`)
            val sslSocket = identityManager.convertToTlsSocket(rawSocket, useClientMode = true)
            val peerCert = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw SecurityException("Peer did not present X.509 certificate during TLS handshake.")

            logger.info(
                LogCategory.TLS,
                "Incoming TLS handshake succeeded (role=TLS_CLIENT, proto=${sslSocket.session.protocol}, cipher=${sslSocket.session.cipherSuite}) with '$peerName' ($remoteIp)"
            )

            registerAndListenOnTlsSession(
                sslSocket = sslSocket,
                peerCert = peerCert,
                identityPacket = identityPacket,
                remoteIp = remoteIp
            )
        }.onFailure { err ->
            logger.error(
                LogCategory.TLS,
                "Incoming TCP/TLS connection from $remoteIp failed",
                throwable = err
            )
            runCatching { rawSocket.close() }
        }
        if (peerIdForCleanup.isNotBlank()) {
            connectingPeers.remove(peerIdForCleanup)
        }
    }

    private fun readLineUnbuffered(input: InputStream, maxBytes: Int = 16_384): String? {
        val baos = ByteArrayOutputStream(512)
        var count = 0
        while (count < maxBytes) {
            val b = input.read()
            if (b == -1) {
                return if (baos.size() == 0) null else baos.toString(Charsets.UTF_8.name())
            }
            if (b == '\n'.code) {
                return baos.toString(Charsets.UTF_8.name()).trimEnd('\r')
            }
            baos.write(b)
            count++
        }
        return baos.toString(Charsets.UTF_8.name()).trimEnd('\r')
    }

    // =========================================================================
    // 3. UDP 1716 LISTENER & OUTGOING TCP -> INVERTED TLS SERVER UPGRADE
    // =========================================================================

    private suspend fun startUdpDiscoveryListener() = withContext(Dispatchers.IO) {
        var socket: DatagramSocket? = null
        // Retry binding UDP 1716 up to 3 times before falling back to an ephemeral port
        for (attempt in 1..3) {
            socket = runCatching {
                DatagramSocket(null).apply {
                    reuseAddress = true
                    broadcast = true
                    bind(InetSocketAddress(DEFAULT_KDE_PORT))
                }
            }.getOrNull()
            if (socket != null) break
            delay(120)
        }
        if (socket == null) {
            socket = runCatching { DatagramSocket(0).apply { broadcast = true } }.getOrNull()
        }
        if (socket == null) {
            logger.error(LogCategory.DISCOVERY, "Failed to bind any UDP discovery socket!")
            return@withContext
        }

        udpSocket = socket
        activeUdpPort = socket.localPort
        logger.info(
            LogCategory.DISCOVERY,
            "UDP discovery listener bound on port $activeUdpPort (standard=1716)"
        )

        val buffer = ByteArray(16_384)
        while (isActive && !socket.isClosed) {
            val datagram = DatagramPacket(buffer, buffer.size)
            val received = runCatching { socket.receive(datagram) }.isSuccess
            if (!received) break
            val remoteIp = datagram.address?.hostAddress ?: continue
            val raw = String(datagram.data, 0, datagram.length, Charsets.UTF_8)
            val packet = runCatching { NetworkPacket.parse(raw) }.getOrElse { err ->
                logger.warn(LogCategory.DISCOVERY, "Malformed UDP packet from $remoteIp: ${err.message}")
                continue
            }
            if (packet.type == NetworkPacket.TYPE_IDENTITY) {
                val peerId = packet.getString("deviceId")
                val peerName = packet.getString("deviceName", "Unknown")
                if (peerId.isNotBlank() && peerId != identityManager.localDeviceId) {
                    val peerTcpPort = packet.getInt("tcpPort", DEFAULT_KDE_PORT)
                    logger.logPacket("RECV-UDP", "$peerName@$remoteIp:$peerTcpPort", packet, LogCategory.DISCOVERY)

                    if (!isSessionActive(peerId) && connectingPeers.add(peerId)) {
                        engineScope.launch {
                            try {
                                initiateOutgoingConnectionToPeer(remoteIp, peerTcpPort, packet)
                            } finally {
                                connectingPeers.remove(peerId)
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Broadcasts KDE Connect `kdeconnect.identity` over UDP 1716 to:
     * - `255.255.255.255`
     * - Every active Wi-Fi / LAN IPv4 subnet broadcast address (e.g., `192.168.1.255`)
     * - Or a direct unicast IP[:port] entered by the user.
     */
    suspend fun broadcastUdpIdentity(unicastHost: String? = null): Boolean = withContext(Dispatchers.IO) {
        awaitReady(1500L)
        val packet = NetworkPacket.createIdentityPacket(
            deviceId = identityManager.localDeviceId,
            deviceName = identityManager.localDeviceName,
            deviceType = "phone",
            tcpPort = activeTcpPort
        )
        val bytes = packet.serialize().toByteArray(Charsets.UTF_8)

        runCatching {
            val (parsedHost, parsedPort) = parseHostAndOptionalPort(unicastHost)
            val targets = linkedSetOf<Pair<InetAddress, Int>>()
            if (!parsedHost.isNullOrBlank()) {
                targets.add(InetAddress.getByName(parsedHost) to DEFAULT_KDE_PORT)
                if (parsedPort != DEFAULT_KDE_PORT) {
                    targets.add(InetAddress.getByName(parsedHost) to parsedPort)
                }
            } else {
                targets.add(InetAddress.getByName("255.255.255.255") to DEFAULT_KDE_PORT)
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces != null && interfaces.hasMoreElements()) {
                    val nif = interfaces.nextElement()
                    if (!nif.isUp || nif.isLoopback) continue
                    for (ifAddr in nif.interfaceAddresses) {
                        ifAddr.broadcast?.let { targets.add(it to DEFAULT_KDE_PORT) }
                    }
                }
            }

            // Send a 2-datagram burst (spaced 260ms apart) so Wi-Fi DTIM power-saving routers don't drop single UDP broadcasts
            DatagramSocket().use { sender ->
                sender.broadcast = true
                repeat(2) { wave ->
                    for ((address, targetPort) in targets) {
                        runCatching {
                            val dgram = DatagramPacket(bytes, bytes.size, address, targetPort)
                            sender.send(dgram)
                        }.onFailure { sendErr ->
                            if (wave == 0) {
                                logger.warn(
                                    LogCategory.DISCOVERY,
                                    "UDP send failed to ${address.hostAddress}:$targetPort — ${sendErr.message}"
                                )
                            }
                        }
                    }
                    if (wave == 0) delay(240)
                }
            }

            logger.info(
                LogCategory.DISCOVERY,
                "Sent UDP identity burst (myTcpPort=$activeTcpPort) to: " +
                    targets.joinToString { "${it.first.hostAddress}:${it.second}" }
            )

            // If user entered a direct IP[:port], give the peer 900ms to connect back via standard UDP->TCP
            // so we receive its full `kdeconnect.identity`, then fall back to direct TCP probe if needed!
            if (!parsedHost.isNullOrBlank()) {
                engineScope.launch {
                    delay(900)
                    val alreadyConnected = activeSessions.values.any {
                        it.ipAddress == parsedHost && it.isHealthy()
                    }
                    if (!alreadyConnected) {
                        logger.info(
                            LogCategory.DISCOVERY,
                            "No UDP callback from $parsedHost after 900ms — attempting direct TCP/TLS probe on $parsedHost:$parsedPort"
                        )
                        initiateDirectTcpProbe(parsedHost, parsedPort)
                    }
                }
            }
            true
        }.onFailure { err ->
            logger.error(LogCategory.DISCOVERY, "broadcastUdpIdentity failed", err)
        }.getOrDefault(false)
    }

    private fun parseHostAndOptionalPort(raw: String?): Pair<String?, Int> {
        if (raw.isNullOrBlank()) return null to DEFAULT_KDE_PORT
        val trimmed = raw.trim()
        val colonIdx = trimmed.lastIndexOf(':')
        if (colonIdx > 0 && trimmed.count { it == ':' } == 1) {
            val hostPart = trimmed.substring(0, colonIdx).trim()
            val portPart = trimmed.substring(colonIdx + 1).trim().toIntOrNull()
            if (portPart != null && portPart in 1..65535) {
                return hostPart to portPart
            }
        }
        return trimmed to DEFAULT_KDE_PORT
    }

    /**
     * Outgoing TCP connection to `remoteIp:peerTcpPort`:
     * 1. Send OUR `kdeconnect.identity` in cleartext first.
     * 2. Upgrade socket to TLS in **TLS SERVER mode** (`useClientMode = false`, mutual auth).
     */
    suspend fun initiateOutgoingConnectionToPeer(
        remoteIp: String,
        peerTcpPort: Int,
        udpIdentityPacket: NetworkPacket?
    ): Boolean = withContext(Dispatchers.IO) {
        val rawSocket = Socket()
        runCatching {
            logger.info(LogCategory.TLS, "Opening outgoing TCP connection to $remoteIp:$peerTcpPort…")
            rawSocket.connect(InetSocketAddress(remoteIp, peerTcpPort), 5000)
            rawSocket.soTimeout = 10_000
            rawSocket.keepAlive = true

            val ourIdentity = NetworkPacket.createIdentityPacket(
                deviceId = identityManager.localDeviceId,
                deviceName = identityManager.localDeviceName,
                deviceType = "phone",
                tcpPort = activeTcpPort
            )
            val clearWriter = OutputStreamWriter(rawSocket.getOutputStream(), Charsets.UTF_8)
            clearWriter.write(ourIdentity.serialize())
            clearWriter.flush()

            // Inverted TLS: we initiated TCP -> we act as TLS Server (`useClientMode = false`)
            val sslSocket = identityManager.convertToTlsSocket(rawSocket, useClientMode = false)
            val peerCert = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw SecurityException("Remote peer did not present client X.509 certificate.")

            logger.info(
                LogCategory.TLS,
                "Outgoing TLS handshake succeeded (role=TLS_SERVER, proto=${sslSocket.session.protocol}, cipher=${sslSocket.session.cipherSuite}) with $remoteIp:$peerTcpPort"
            )

            val effectiveIdentity = udpIdentityPacket ?: run {
                val cn = extractCommonName(peerCert.subjectX500Principal.name)
                    ?: "kde_peer_${remoteIp.replace('.', '_')}"
                val fallbackCaps = JSONArray(
                    listOf(
                        NetworkPacket.TYPE_PAIR,
                        NetworkPacket.TYPE_PING,
                        NetworkPacket.TYPE_SHARE_REQUEST,
                        NetworkPacket.TYPE_CLIPBOARD,
                        NetworkPacket.TYPE_MOUSEPAD_REQUEST,
                        NetworkPacket.TYPE_MPRIS,
                        NetworkPacket.TYPE_MPRIS_REQUEST,
                        NetworkPacket.TYPE_BATTERY,
                        NetworkPacket.TYPE_BATTERY_REQUEST,
                        NetworkPacket.TYPE_FIND_MY_PHONE,
                        NetworkPacket.TYPE_RUNCOMMAND,
                        NetworkPacket.TYPE_RUNCOMMAND_REQUEST
                    )
                )
                NetworkPacket(
                    type = NetworkPacket.TYPE_IDENTITY,
                    body = JSONObject().apply {
                        put("deviceId", cn)
                        put("deviceName", "Device ($remoteIp)")
                        put("deviceType", "desktop")
                        put("protocolVersion", NetworkPacket.PROTOCOL_VERSION)
                        put("tcpPort", peerTcpPort)
                        put("incomingCapabilities", fallbackCaps)
                        put("outgoingCapabilities", fallbackCaps)
                    }
                )
            }

            registerAndListenOnTlsSession(
                sslSocket = sslSocket,
                peerCert = peerCert,
                identityPacket = effectiveIdentity,
                remoteIp = remoteIp
            )
            true
        }.onFailure { err ->
            logger.error(
                LogCategory.TLS,
                "Outgoing TCP/TLS connection to $remoteIp:$peerTcpPort failed",
                throwable = err
            )
            runCatching { rawSocket.close() }
        }.getOrDefault(false)
    }

    private suspend fun initiateDirectTcpProbe(host: String, port: Int) {
        initiateOutgoingConnectionToPeer(host, port, udpIdentityPacket = null)
    }

    private suspend fun registerAndListenOnTlsSession(
        sslSocket: SSLSocket,
        peerCert: X509Certificate,
        identityPacket: NetworkPacket,
        remoteIp: String
    ) {
        val peerId = identityPacket.getString("deviceId").ifBlank {
            extractCommonName(peerCert.subjectX500Principal.name) ?: return
        }
        val peerName = identityPacket.getString("deviceName", "KDE Connect Device")
        val peerType = identityPacket.getString("deviceType", "desktop")
        val peerPort = identityPacket.getInt("tcpPort", DEFAULT_KDE_PORT)
        val peerProto = identityPacket.getInt("protocolVersion", NetworkPacket.PROTOCOL_VERSION)
        val inCaps = identityPacket.getStringList("incomingCapabilities")
        val outCaps = identityPacket.getStringList("outgoingCapabilities")

        // Replace any stale session for this deviceId
        activeSessions.remove(peerId)?.close()
        val session = ActiveTlsPeerSession(
            deviceId = peerId,
            ipAddress = remoteIp,
            tcpPort = peerPort,
            sslSocket = sslSocket,
            peerCertificate = peerCert
        )
        activeSessions[peerId] = session

        logger.info(
            LogCategory.TLS,
            "Session registered for '$peerName' (id=$peerId, ip=$remoteIp, proto=v$peerProto)",
            details = "peerIncomingCaps=$inCaps\npeerOutgoingCaps=$outCaps"
        )

        onPeerConnectedOrUpdated(
            peerId,
            peerName,
            peerType,
            remoteIp,
            peerPort,
            peerProto,
            peerCert.encoded,
            inCaps,
            outCaps
        )

        // Dedicated coroutine reading newline-delimited encrypted JSON packets for the life of the TLS session
        engineScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(sslSocket.inputStream, Charsets.UTF_8))
                while (isActive && !sslSocket.isClosed) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    val packet = runCatching { NetworkPacket.parse(line) }.getOrElse { parseErr ->
                        logger.warn(LogCategory.TLS, "Ignored invalid JSON packet from $peerName: ${parseErr.message}")
                        continue
                    }

                    logger.logPacket(
                        direction = "RECV-TLS",
                        peerLabel = "$peerName@$remoteIp",
                        packet = packet,
                        category = categorizePacket(packet.type)
                    )

                    // Pull binary payload over TLS if peer attached `payloadTransferInfo.port`,
                    // except `TYPE_VFS_READ_RESPONSE` which streams directly to disk in `downloadViaVfsTls`.
                    var payloadBytes: ByteArray? = null
                    if (packet.hasPayload() && packet.type != NetworkPacket.TYPE_VFS_READ_RESPONSE) {
                        val port = packet.payloadTransferInfo?.optInt("port", -1) ?: -1
                        if (port > 0) {
                            payloadBytes = pullEncryptedPayloadFromPeer(remoteIp, port)
                        }
                    }

                    handleEncryptedSessionPacket(session, peerName, packet, payloadBytes)
                }
            } catch (e: Exception) {
                if (!isStopping) {
                    logger.warn(LogCategory.TLS, "TLS reader loop ended for '$peerName' ($remoteIp): ${e.message}")
                }
            } finally {
                if (activeSessions[peerId] === session) {
                    activeSessions.remove(peerId)
                    if (!isStopping) {
                        runCatching { onPeerDisconnected(peerId) }
                    }
                }
                session.close()
            }
        }
    }

    private suspend fun handleEncryptedSessionPacket(
        session: ActiveTlsPeerSession,
        peerName: String,
        packet: NetworkPacket,
        payloadBytes: ByteArray?
    ) {
        when (packet.type) {
            NetworkPacket.TYPE_SFTP -> {
                if (packet.body.has("errorMessage")) {
                    logger.error(
                        LogCategory.FILES,
                        "Remote peer '$peerName' reported SFTP error: ${packet.getString("errorMessage")}"
                    )
                } else {
                    val rawPacketIp = packet.getString("ip", "")
                    val port = packet.getInt("port", 22)
                    val user = packet.getString("user", "kdeconnect")
                    val password = packet.getString("password", "")
                    val multiPaths = packet.getStringList("multiPaths")
                    val pathNames = packet.getStringList("pathNames")
                    val singlePath = packet.getString("path")

                    // Prefer session.ipAddress (proven working IPv4 socket) over link-local IPv6 strings like fe80::%wlan0
                    val cleanFallback = rawPacketIp.takeIf { it.isNotBlank() && !it.contains('%') && it != session.ipAddress }
                    val chosenRoot = when {
                        singlePath.isNotBlank() -> singlePath
                        multiPaths.size == 1 -> multiPaths.first()
                        else -> "/"
                    }
                    if (password.isNotBlank() && port > 0) {
                        sftpCredentialsByDevice[session.deviceId] = SftpSessionCredentials(
                            primaryHost = session.ipAddress,
                            fallbackHost = cleanFallback,
                            port = port,
                            username = user,
                            password = password,
                            rootPath = chosenRoot,
                            multiPaths = multiPaths,
                            pathNames = pathNames,
                            isManualSsh = false
                        )
                        logger.info(
                            LogCategory.FILES,
                            "Captured KDE Connect SFTP credentials from '$peerName' (${session.ipAddress}:$port, root=$chosenRoot, multiPaths=$multiPaths)"
                        )
                    }
                }
                onPacketReceived(session.deviceId, packet, payloadBytes)
            }

            NetworkPacket.TYPE_SFTP_REQUEST -> {
                logger.info(
                    LogCategory.FILES,
                    "Peer '$peerName' sent kdeconnect.sftp.request (Desktop attempting to mount phone via SFTP)"
                )
                onPacketReceived(session.deviceId, packet, payloadBytes)
            }

            NetworkPacket.TYPE_VFS_LIST_RESPONSE,
            NetworkPacket.TYPE_VFS_READ_RESPONSE,
            NetworkPacket.TYPE_VFS_WRITE_RESPONSE -> {
                val reqId = packet.getString("requestId")
                pendingVfsResponses.remove(reqId)?.complete(packet)
            }

            NetworkPacket.TYPE_VFS_LIST_REQUEST,
            NetworkPacket.TYPE_VFS_READ_REQUEST,
            NetworkPacket.TYPE_VFS_WRITE_REQUEST -> {
                val reply = serveLocalVfsRequest(packet, payloadBytes)
                if (reply != null) {
                    logger.logPacket("SEND-TLS", peerName, reply, LogCategory.FILES)
                    runCatching { session.sendPacketLine(reply.serialize()) }
                }
            }

            else -> {
                onPacketReceived(session.deviceId, packet, payloadBytes)
            }
        }
    }

    // =========================================================================
    // 4. SENDING PACKETS & ENCRYPTED PAYLOAD SOCKETS (FIREWALL PORTS 1739..1764)
    // =========================================================================

    /**
     * Low-latency non-blocking write used for 60fps Magic Trackpad cursor movements.
     */
    fun sendFastPacketIfConnected(deviceId: String, packet: NetworkPacket): Boolean {
        val session = activeSessions[deviceId] ?: return false
        if (!session.isHealthy()) return false
        return runCatching {
            session.sendPacketLine(packet.serialize())
            true
        }.getOrDefault(false)
    }

    private fun openPayloadServerSocket(): ServerSocket {
        for (port in MIN_PAYLOAD_PORT..MAX_KDE_PORT) {
            val bound = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", port))
                }
            }.getOrNull()
            if (bound != null) return bound
        }
        return ServerSocket(0).apply { reuseAddress = true }
    }

    suspend fun sendPacketToConnectedPeer(
        deviceId: String,
        packet: NetworkPacket,
        payloadBytes: ByteArray? = null,
        onProgress: (suspend (transferred: Long, total: Long) -> Unit)? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val session = activeSessions[deviceId]
            ?: return@withContext Result.failure(
                IllegalStateException("Device is not currently connected on the local network.")
            )

        runCatching {
            var payloadDoneDeferred: CompletableDeferred<Result<Unit>>? = null
            val outboundPacket = if (payloadBytes != null) {
                val payloadServer = openPayloadServerSocket()
                val transferPort = payloadServer.localPort
                val done = CompletableDeferred<Result<Unit>>()
                payloadDoneDeferred = done

                logger.info(
                    LogCategory.FILES,
                    "Opened TLS payload server on port $transferPort for ${packet.type} (${payloadBytes.size} bytes)"
                )

                engineScope.launch {
                    val serveRes = runCatching {
                        payloadServer.use { srv ->
                            srv.soTimeout = 20_000
                            val rawClient = srv.accept()
                            identityManager.convertToTlsSocket(
                                rawSocket = rawClient,
                                useClientMode = false,
                                requireClientCert = false
                            ).use { tlsOut ->
                                val out = tlsOut.outputStream
                                val chunkSize = 16 * 1024
                                var offset = 0
                                val total = payloadBytes.size.toLong()
                                while (offset < payloadBytes.size && isActive) {
                                    val count = minOf(chunkSize, payloadBytes.size - offset)
                                    out.write(payloadBytes, offset, count)
                                    offset += count
                                    onProgress?.invoke(offset.toLong(), total)
                                }
                                out.flush()
                            }
                        }
                    }
                    serveRes.onFailure { err ->
                        logger.error(LogCategory.FILES, "Payload transfer server failed on port $transferPort", err)
                    }
                    done.complete(serveRes)
                }

                packet.copy(
                    payloadSize = payloadBytes.size.toLong(),
                    payloadTransferInfo = JSONObject().put("port", transferPort)
                )
            } else {
                packet
            }

            logger.logPacket(
                direction = "SEND-TLS",
                peerLabel = "${session.deviceId}@${session.ipAddress}",
                packet = outboundPacket,
                category = categorizePacket(outboundPacket.type)
            )
            session.sendPacketLine(outboundPacket.serialize())

            // Wait for payload socket transfer completion when sending files/images
            if (payloadDoneDeferred != null) {
                withTimeout(30_000L) {
                    payloadDoneDeferred.await().getOrThrow()
                }
            }
        }.onFailure { err ->
            logger.error(LogCategory.TLS, "Failed sending ${packet.type} to $deviceId", err)
        }
    }

    private suspend fun pullEncryptedPayloadFromPeer(remoteIp: String, port: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = Socket()
                raw.connect(InetSocketAddress(remoteIp, port), 8000)
                raw.soTimeout = 20_000
                identityManager.convertToTlsSocket(
                    rawSocket = raw,
                    useClientMode = true,
                    requireClientCert = false
                ).use { tlsSock ->
                    tlsSock.inputStream.readBytes()
                }
            }.onSuccess { bytes ->
                logger.info(LogCategory.FILES, "Pulled ${bytes.size} encrypted payload bytes from $remoteIp:$port")
            }.getOrElse { tlsErr ->
                logger.warn(
                    LogCategory.FILES,
                    "TLS payload pull from $remoteIp:$port failed (${tlsErr.message}) — trying raw TCP fallback"
                )
                runCatching {
                    Socket().use { raw ->
                        raw.connect(InetSocketAddress(remoteIp, port), 6000)
                        raw.soTimeout = 15_000
                        raw.getInputStream().readBytes()
                    }
                }.getOrNull()
            }
        }

    // =========================================================================
    // 5. REAL REMOTE FILE BROWSING: APEXCONNECT VFS + KDE CONNECT SFTP + SSH/SFTP
    // =========================================================================

    suspend fun requestSftpAndBrowseDirectory(
        deviceId: String,
        requestedPath: String,
        supportsKdeSftp: Boolean,
        supportsApexVfs: Boolean
    ): Result<Pair<String, List<RemoteFileEntry>>> = withContext(Dispatchers.IO) {
        val pathCheck = PathValidator.validateRemotePath(requestedPath)
        if (pathCheck is PathValidator.ValidationResult.Denied) {
            return@withContext Result.failure(SecurityException(pathCheck.reason))
        }
        val normalizedPath = (pathCheck as PathValidator.ValidationResult.Valid).normalizedRelativePath

        logger.info(
            LogCategory.FILES,
            "Browse requested for peer=$deviceId path='$normalizedPath' (apexVfs=$supportsApexVfs, kdeSftp=$supportsKdeSftp, cachedSftp=${sftpCredentialsByDevice.containsKey(deviceId)})"
        )

        // 1. If manual SSH/SFTP or previously negotiated SFTP credentials already exist for a non-VFS peer, use them!
        val existingCreds = sftpCredentialsByDevice[deviceId]
        if (!supportsApexVfs && existingCreds != null) {
            return@withContext browseViaRealSftp(existingCreds, normalizedPath)
        }

        // 2. Peer advertises ApexConnect VFS directly over our encrypted TLS channel
        if (supportsApexVfs) {
            return@withContext requestVfsDirectoryOverTls(deviceId, normalizedPath)
        }

        // 3. If peer connected via direct TCP probe without UDP capabilities, probe VFS quickly (1.5s) first!
        val quickVfsAttempt = requestVfsDirectoryOverTls(deviceId, normalizedPath, timeoutMs = 1500L)
        if (quickVfsAttempt.isSuccess) {
            logger.info(LogCategory.FILES, "Peer $deviceId responded natively to ApexConnect VFS over TLS!")
            return@withContext quickVfsAttempt
        }

        // 4. Genuine KDE Connect peer advertising `kdeconnect.sftp` (e.g. KDE Connect Android)
        if (supportsKdeSftp) {
            var creds = sftpCredentialsByDevice[deviceId]
            if (creds == null) {
                sendPacketToConnectedPeer(
                    deviceId = deviceId,
                    packet = NetworkPacket(
                        type = NetworkPacket.TYPE_SFTP_REQUEST,
                        body = JSONObject().put("startBrowsing", true)
                    )
                )
                repeat(16) {
                    delay(250)
                    creds = sftpCredentialsByDevice[deviceId]
                    if (creds != null) return@repeat
                }
            }

            val activeCreds = creds ?: return@withContext Result.failure(
                UnsupportedOperationException(
                    "Remote device did not start an SFTP server. On KDE Connect Android, enable 'Filesystem expose' and add a storage location; on a Windows/Linux/macOS PC, tap 'Connect via SFTP / SSH' below."
                )
            )
            return@withContext browseViaRealSftp(activeCreds, normalizedPath)
        }

        Result.failure(
            UnsupportedOperationException(
                "Desktop KDE Connect does not bundle a built-in SFTP server (it only mounts phones). Tap 'Connect via SFTP / SSH' below to browse this computer's folders over SSH/SFTP (Port 22), or tap 'Upload' to send files instantly via KDE Share."
            )
        )
    }

    private fun openJschSftpChannel(creds: SftpSessionCredentials): Pair<Session, ChannelSftp> {
        val jsch = JSch()
        val hostsToTry = listOfNotNull(creds.primaryHost, creds.fallbackHost).distinct()
        var lastError: Throwable? = null

        for (host in hostsToTry) {
            try {
                val session = jsch.getSession(creds.username, host, creds.port).apply {
                    setPassword(creds.password)
                    setConfig(
                        Properties().apply {
                            put("StrictHostKeyChecking", "no")
                            put("PreferredAuthentications", "password,keyboard-interactive")
                            // Enable ssh-rsa so KDE Connect Android's Apache MINA SSHD server negotiates cleanly
                            val defaultHostKeys = JSch.getConfig("server_host_key") ?: ""
                            if (!defaultHostKeys.contains("ssh-rsa")) {
                                put("server_host_key", "$defaultHostKeys,ssh-rsa,rsa-sha2-256,rsa-sha2-512")
                            }
                            val defaultPubkey = JSch.getConfig("PubkeyAcceptedAlgorithms") ?: ""
                            if (!defaultPubkey.contains("ssh-rsa")) {
                                put("PubkeyAcceptedAlgorithms", "$defaultPubkey,ssh-rsa,rsa-sha2-256,rsa-sha2-512")
                            }
                        }
                    )
                    connect(8000)
                }
                val channel = (session.openChannel("sftp") as ChannelSftp).apply {
                    connect(8000)
                }
                return session to channel
            } catch (t: Throwable) {
                lastError = t
                logger.warn(LogCategory.FILES, "JSch SFTP connect failed to $host:${creds.port} — ${t.message}")
            }
        }
        throw (lastError ?: IllegalStateException("Unable to establish SFTP session."))
    }

    private suspend fun browseViaRealSftp(
        creds: SftpSessionCredentials,
        relativePath: String
    ): Result<Pair<String, List<RemoteFileEntry>>> = withContext(Dispatchers.IO) {
        runCatching {
            var session: Session? = null
            var channel: ChannelSftp? = null
            try {
                val (s, ch) = openJschSftpChannel(creds)
                session = s
                channel = ch

                val candidatePaths = buildList {
                    if (relativePath == "/") {
                        add(creds.rootPath)
                        if (creds.rootPath != "/") add("/")
                        creds.multiPaths.forEach { add(it) }
                    } else {
                        if (creds.rootPath == "/") {
                            add(relativePath)
                        } else {
                            add(creds.rootPath.trimEnd('/') + relativePath)
                            add(relativePath)
                        }
                    }
                }.distinct()

                var resolvedSftpDir: String? = null
                var rawEntries: Vector<ChannelSftp.LsEntry>? = null
                var lastLsErr: Throwable? = null

                for (candidate in candidatePaths) {
                    try {
                        @Suppress("UNCHECKED_CAST")
                        val ls = ch.ls(candidate) as Vector<ChannelSftp.LsEntry>
                        resolvedSftpDir = candidate
                        rawEntries = ls
                        break
                    } catch (e: Throwable) {
                        lastLsErr = e
                    }
                }

                val entriesVec = rawEntries ?: throw (lastLsErr ?: IllegalStateException("Unable to list $relativePath"))
                val baseSftpDir = resolvedSftpDir ?: "/"

                val items = entriesVec
                    .filter { it.filename != "." && it.filename != ".." }
                    .map { ls ->
                        val isDir = ls.attrs.isDir || ls.attrs.isLink
                        val childRel = if (relativePath == "/") "/${ls.filename}" else "$relativePath/${ls.filename}"
                        val realChild = if (baseSftpDir == "/") "/${ls.filename}" else "${baseSftpDir.trimEnd('/')}/${ls.filename}"
                        RemoteFileEntry(
                            name = ls.filename,
                            relativePath = childRel,
                            isDirectory = isDir,
                            sizeBytes = if (isDir) 0L else ls.attrs.size,
                            lastModifiedEpochMs = ls.attrs.mTime.toLong() * 1000L,
                            mimeType = if (isDir) "inode/directory" else guessMimeTypeByName(ls.filename),
                            sftpRealPath = realChild
                        )
                    }
                    .sortedWith(compareByDescending<RemoteFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })

                logger.info(
                    LogCategory.FILES,
                    "SFTP listed '$baseSftpDir' -> ${items.size} entries"
                )
                relativePath to items
            } finally {
                runCatching { channel?.disconnect() }
                runCatching { session?.disconnect() }
            }
        }.onFailure { err ->
            logger.error(LogCategory.FILES, "browseViaRealSftp failed for path '$relativePath'", err)
        }
    }

    suspend fun uploadViaRealSftpIfConfigured(
        deviceId: String,
        targetRemoteDir: String,
        safeFileName: String,
        payloadBytes: ByteArray,
        onProgress: suspend (transferred: Long, total: Long) -> Unit
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val creds = sftpCredentialsByDevice[deviceId] ?: return@withContext Result.success(false)
        runCatching {
            var session: Session? = null
            var channel: ChannelSftp? = null
            try {
                val (s, ch) = openJschSftpChannel(creds)
                session = s
                channel = ch
                val remoteDir = if (targetRemoteDir == "/") {
                    creds.rootPath
                } else if (creds.rootPath == "/") {
                    targetRemoteDir
                } else {
                    creds.rootPath.trimEnd('/') + targetRemoteDir
                }
                val destPath = "${remoteDir.trimEnd('/')}/$safeFileName"
                ch.put(destPath).use { out ->
                    val chunk = 16 * 1024
                    var offset = 0
                    val total = payloadBytes.size.toLong()
                    while (offset < payloadBytes.size) {
                        val len = minOf(chunk, payloadBytes.size - offset)
                        out.write(payloadBytes, offset, len)
                        offset += len
                        onProgress(offset.toLong(), total)
                    }
                    out.flush()
                }
                logger.info(LogCategory.FILES, "Uploaded '$safeFileName' via SFTP to '$destPath'")
                true
            } finally {
                runCatching { channel?.disconnect() }
                runCatching { session?.disconnect() }
            }
        }.onFailure { err ->
            logger.warn(LogCategory.FILES, "Direct SFTP upload failed (${err.message}) — falling back to KDE SharePlugin")
        }
    }

    suspend fun downloadRemoteFile(
        deviceId: String,
        entry: RemoteFileEntry,
        supportsApexVfs: Boolean,
        destinationOutput: OutputStream,
        onProgress: suspend (transferred: Long, total: Long) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val pathCheck = PathValidator.validateRemotePath(entry.relativePath)
        if (pathCheck is PathValidator.ValidationResult.Denied) {
            return@withContext Result.failure(SecurityException(pathCheck.reason))
        }
        val cleanPath = (pathCheck as PathValidator.ValidationResult.Valid).normalizedRelativePath

        if (supportsApexVfs || !sftpCredentialsByDevice.containsKey(deviceId)) {
            val vfsRes = downloadViaVfsTls(deviceId, cleanPath, destinationOutput, onProgress)
            if (vfsRes.isSuccess || supportsApexVfs) return@withContext vfsRes
        }

        val creds = sftpCredentialsByDevice[deviceId]
            ?: return@withContext Result.failure(IllegalStateException("No active SFTP session credentials for device."))

        runCatching {
            var session: Session? = null
            var channel: ChannelSftp? = null
            try {
                val (s, ch) = openJschSftpChannel(creds)
                session = s
                channel = ch

                val fullRemotePath = entry.sftpRealPath ?: if (creds.rootPath == "/") {
                    cleanPath
                } else {
                    creds.rootPath.trimEnd('/') + cleanPath
                }
                val totalSize = runCatching { ch.lstat(fullRemotePath).size }.getOrDefault(entry.sizeBytes)
                val digest = MessageDigest.getInstance("SHA-256")

                ch.get(fullRemotePath).use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var transferred = 0L
                    var read = input.read(buffer)
                    while (read >= 0) {
                        if (!coroutineContext.isActive) {
                            throw kotlinx.coroutines.CancellationException("Transfer cancelled")
                        }
                        if (read > 0) {
                            destinationOutput.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            transferred += read
                            onProgress(transferred, totalSize.coerceAtLeast(transferred))
                        }
                        read = input.read(buffer)
                    }
                    destinationOutput.flush()
                }
                val shaHex = CryptoIdentityManager.bytesToHex(digest.digest())
                logger.info(LogCategory.FILES, "Downloaded '$fullRemotePath' via SFTP (sha256=$shaHex)")
                shaHex
            } finally {
                runCatching { channel?.disconnect() }
                runCatching { session?.disconnect() }
            }
        }.onFailure { err ->
            logger.error(LogCategory.FILES, "SFTP download failed for '${entry.relativePath}'", err)
        }
    }

    private suspend fun requestVfsDirectoryOverTls(
        deviceId: String,
        cleanPath: String,
        timeoutMs: Long = 8000L
    ): Result<Pair<String, List<RemoteFileEntry>>> = withContext(Dispatchers.IO) {
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<NetworkPacket>()
        pendingVfsResponses[reqId] = deferred

        val pkt = NetworkPacket(
            type = NetworkPacket.TYPE_VFS_LIST_REQUEST,
            body = JSONObject().apply {
                put("requestId", reqId)
                put("path", cleanPath)
            }
        )
        val sent = sendPacketToConnectedPeer(deviceId, pkt)
        if (sent.isFailure) {
            pendingVfsResponses.remove(reqId)
            return@withContext Result.failure(sent.exceptionOrNull()!!)
        }

        val resp = runCatching {
            withTimeout(timeoutMs) { deferred.await() }
        }.getOrElse {
            pendingVfsResponses.remove(reqId)
            return@withContext Result.failure(IllegalStateException("Timed out waiting for remote folder response."))
        }

        if (!resp.getBoolean("success", false)) {
            return@withContext Result.failure(SecurityException(resp.getString("error", "Access denied.")))
        }

        val entriesArray = resp.body.optJSONArray("entries")
        val list = buildList {
            if (entriesArray != null) {
                for (i in 0 until entriesArray.length()) {
                    val obj = entriesArray.optJSONObject(i) ?: continue
                    add(
                        RemoteFileEntry(
                            name = obj.optString("name"),
                            relativePath = obj.optString("path"),
                            isDirectory = obj.optBoolean("isDirectory"),
                            sizeBytes = obj.optLong("size"),
                            lastModifiedEpochMs = obj.optLong("lastModified"),
                            mimeType = obj.optString("mimeType", "application/octet-stream")
                        )
                    )
                }
            }
        }
        Result.success(resp.getString("path", cleanPath) to list)
    }

    private suspend fun downloadViaVfsTls(
        deviceId: String,
        cleanPath: String,
        destinationOutput: OutputStream,
        onProgress: suspend (transferred: Long, total: Long) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val session = activeSessions[deviceId]
            ?: return@withContext Result.failure(IllegalStateException("Peer disconnected."))
        val reqId = UUID.randomUUID().toString()
        val deferred = CompletableDeferred<NetworkPacket>()
        pendingVfsResponses[reqId] = deferred

        sendPacketToConnectedPeer(
            deviceId = deviceId,
            packet = NetworkPacket(
                type = NetworkPacket.TYPE_VFS_READ_REQUEST,
                body = JSONObject().put("requestId", reqId).put("path", cleanPath)
            )
        ).onFailure {
            pendingVfsResponses.remove(reqId)
            return@withContext Result.failure(it)
        }

        val resp = runCatching { withTimeout(8000) { deferred.await() } }.getOrElse {
            pendingVfsResponses.remove(reqId)
            return@withContext Result.failure(it)
        }
        if (!resp.getBoolean("success", false)) {
            return@withContext Result.failure(SecurityException(resp.getString("error", "Read rejected.")))
        }

        val port = resp.payloadTransferInfo?.optInt("port", -1) ?: -1
        val total = resp.payloadSize ?: resp.getLong("size", 0L)
        val expectedSha = resp.getString("sha256", "")
        if (port <= 0) return@withContext Result.failure(IllegalStateException("Missing payload port."))

        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            val raw = Socket()
            raw.connect(InetSocketAddress(session.ipAddress, port), 8000)
            raw.soTimeout = 15_000
            identityManager.convertToTlsSocket(
                rawSocket = raw,
                useClientMode = true,
                requireClientCert = false
            ).use { tlsSock ->
                val input = tlsSock.inputStream
                val buf = ByteArray(16 * 1024)
                var transferred = 0L
                var read = input.read(buf)
                while (read >= 0) {
                    if (!coroutineContext.isActive) throw kotlinx.coroutines.CancellationException()
                    if (read > 0) {
                        destinationOutput.write(buf, 0, read)
                        digest.update(buf, 0, read)
                        transferred += read
                        onProgress(transferred, total.coerceAtLeast(transferred))
                    }
                    read = input.read(buf)
                }
                destinationOutput.flush()
            }
            val actualSha = CryptoIdentityManager.bytesToHex(digest.digest())
            if (expectedSha.isNotBlank() && !expectedSha.equals(actualSha, ignoreCase = true)) {
                throw SecurityException("SHA-256 verification failed.")
            }
            logger.info(LogCategory.FILES, "Downloaded '$cleanPath' via TLS VFS (sha256=$actualSha)")
            actualSha
        }.onFailure { err ->
            logger.error(LogCategory.FILES, "TLS VFS download failed for '$cleanPath'", err)
        }
    }

    /**
     * Serves both:
     * 1. Real user-shared phone folders granted via Android SAF (`safFoldersManager`)
     * 2. Built-in `authorizedVaultRoot` (`Documents`, `Pictures`, `Downloads`)
     */
    private fun serveLocalVfsRequest(packet: NetworkPacket, payload: ByteArray?): NetworkPacket? {
        val reqId = packet.getString("requestId")
        return when (packet.type) {
            NetworkPacket.TYPE_VFS_LIST_REQUEST -> {
                val path = packet.getString("path", "/")
                val pathCheck = PathValidator.validateRemotePath(path)
                if (pathCheck is PathValidator.ValidationResult.Denied) {
                    return NetworkPacket(
                        type = NetworkPacket.TYPE_VFS_LIST_RESPONSE,
                        body = JSONObject().put("requestId", reqId).put("success", false).put("error", pathCheck.reason)
                    )
                }
                val normPath = (pathCheck as PathValidator.ValidationResult.Valid).normalizedRelativePath

                // Check if path targets a user-shared SAF phone folder!
                val safMatch = safFoldersManager.findTreeForPath(normPath)
                if (safMatch != null) {
                    val (tree, subSegs) = safMatch
                    return safFoldersManager.listSafDirectory(tree, subSegs, normPath).fold(
                        onSuccess = { nodes ->
                            val arr = JSONArray()
                            nodes.forEach { n ->
                                arr.put(
                                    JSONObject().apply {
                                        put("name", n.name)
                                        put("path", n.relativePath)
                                        put("isDirectory", n.isDirectory)
                                        put("size", n.sizeBytes)
                                        put("lastModified", n.lastModifiedEpochMs)
                                        put("mimeType", n.mimeType)
                                    }
                                )
                            }
                            NetworkPacket(
                                type = NetworkPacket.TYPE_VFS_LIST_RESPONSE,
                                body = JSONObject().apply {
                                    put("requestId", reqId)
                                    put("success", true)
                                    put("path", normPath)
                                    put("entries", arr)
                                }
                            )
                        },
                        onFailure = { err ->
                            NetworkPacket(
                                type = NetworkPacket.TYPE_VFS_LIST_RESPONSE,
                                body = JSONObject().put("requestId", reqId).put("success", false)
                                    .put("error", err.message ?: "SAF folder unavailable")
                            )
                        }
                    )
                }

                when (val res = PathValidator.resolveWithinRoot(authorizedVaultRoot, normPath)) {
                    is PathValidator.ValidationResult.Denied -> NetworkPacket(
                        type = NetworkPacket.TYPE_VFS_LIST_RESPONSE,
                        body = JSONObject().put("requestId", reqId).put("success", false).put("error", res.reason)
                    )
                    is PathValidator.ValidationResult.Valid -> {
                        val dir = res.resolvedFile ?: authorizedVaultRoot
                        val arr = JSONArray()

                        // At root `/`, also list every SAF phone folder shared by this user!
                        if (res.normalizedRelativePath == "/") {
                            safFoldersManager.sharedTrees.value.forEach { tree ->
                                arr.put(
                                    JSONObject().apply {
                                        put("name", tree.alias)
                                        put("path", "/${tree.alias}")
                                        put("isDirectory", true)
                                        put("size", 0L)
                                        put("lastModified", System.currentTimeMillis())
                                        put("mimeType", "inode/directory")
                                    }
                                )
                            }
                        }

                        dir.listFiles()
                            ?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
                            ?.forEach { f ->
                                val rel = if (res.normalizedRelativePath == "/") "/${f.name}" else "${res.normalizedRelativePath}/${f.name}"
                                arr.put(
                                    JSONObject().apply {
                                        put("name", f.name)
                                        put("path", rel)
                                        put("isDirectory", f.isDirectory)
                                        put("size", if (f.isDirectory) 0L else f.length())
                                        put("lastModified", f.lastModified())
                                        put("mimeType", if (f.isDirectory) "inode/directory" else guessMimeTypeByName(f.name))
                                    }
                                )
                            }
                        NetworkPacket(
                            type = NetworkPacket.TYPE_VFS_LIST_RESPONSE,
                            body = JSONObject().apply {
                                put("requestId", reqId)
                                put("success", true)
                                put("path", res.normalizedRelativePath)
                                put("entries", arr)
                            }
                        )
                    }
                }
            }

            NetworkPacket.TYPE_VFS_READ_REQUEST -> {
                val path = packet.getString("path", "/")
                val pathCheck = PathValidator.validateRemotePath(path)
                if (pathCheck is PathValidator.ValidationResult.Denied) {
                    return NetworkPacket(
                        type = NetworkPacket.TYPE_VFS_READ_RESPONSE,
                        body = JSONObject().put("requestId", reqId).put("success", false).put("error", pathCheck.reason)
                    )
                }
                val normPath = (pathCheck as PathValidator.ValidationResult.Valid).normalizedRelativePath

                val safMatch = safFoldersManager.findTreeForPath(normPath)
                if (safMatch != null) {
                    val (tree, subSegs) = safMatch
                    return safFoldersManager.openSafFileForRead(tree, subSegs).fold(
                        onSuccess = { (name, size, shaAndOpener) ->
                            val (sha, streamOpener) = shaAndOpener
                            val payloadServer = openPayloadServerSocket()
                            val port = payloadServer.localPort
                            engineScope.launch {
                                runCatching {
                                    payloadServer.use { srv ->
                                        srv.soTimeout = 15_000
                                        val rawSock = srv.accept()
                                        identityManager.convertToTlsSocket(
                                            rawSocket = rawSock,
                                            useClientMode = false,
                                            requireClientCert = false
                                        ).use { tls ->
                                            streamOpener()?.buffered()?.use { it.copyTo(tls.outputStream) }
                                            tls.outputStream.flush()
                                        }
                                    }
                                }
                            }
                            NetworkPacket(
                                type = NetworkPacket.TYPE_VFS_READ_RESPONSE,
                                body = JSONObject().apply {
                                    put("requestId", reqId)
                                    put("success", true)
                                    put("name", name)
                                    put("size", size)
                                    put("sha256", sha)
                                },
                                payloadSize = size,
                                payloadTransferInfo = JSONObject().put("port", port)
                            )
                        },
                        onFailure = { err ->
                            NetworkPacket(
                                type = NetworkPacket.TYPE_VFS_READ_RESPONSE,
                                body = JSONObject().put("requestId", reqId).put("success", false)
                                    .put("error", err.message ?: "Cannot read SAF file")
                            )
                        }
                    )
                }

                when (val res = PathValidator.resolveWithinRoot(authorizedVaultRoot, normPath)) {
                    is PathValidator.ValidationResult.Denied -> NetworkPacket(
                        type = NetworkPacket.TYPE_VFS_READ_RESPONSE,
                        body = JSONObject().put("requestId", reqId).put("success", false).put("error", res.reason)
                    )
                    is PathValidator.ValidationResult.Valid -> {
                        val file = res.resolvedFile
                        if (file == null || !file.exists() || file.isDirectory) {
                            NetworkPacket(
                                type = NetworkPacket.TYPE_VFS_READ_RESPONSE,
                                body = JSONObject().put("requestId", reqId).put("success", false).put("error", "File not found.")
                            )
                        } else {
                            val sha = CryptoIdentityManager.sha256File(file)
                            val payloadServer = openPayloadServerSocket()
                            val port = payloadServer.localPort
                            engineScope.launch {
                                runCatching {
                                    payloadServer.use { srv ->
                                        srv.soTimeout = 15_000
                                        val rawSock = srv.accept()
                                        identityManager.convertToTlsSocket(
                                            rawSocket = rawSock,
                                            useClientMode = false,
                                            requireClientCert = false
                                        ).use { tls ->
                                            file.inputStream().buffered().use { it.copyTo(tls.outputStream) }
                                            tls.outputStream.flush()
                                        }
                                    }
                                }
                            }
                            NetworkPacket(
                                type = NetworkPacket.TYPE_VFS_READ_RESPONSE,
                                body = JSONObject().apply {
                                    put("requestId", reqId)
                                    put("success", true)
                                    put("name", file.name)
                                    put("size", file.length())
                                    put("sha256", sha)
                                },
                                payloadSize = file.length(),
                                payloadTransferInfo = JSONObject().put("port", port)
                            )
                        }
                    }
                }
            }

            NetworkPacket.TYPE_VFS_WRITE_REQUEST -> {
                val dir = packet.getString("directory", "/")
                val safeName = PathValidator.sanitizeFileName(packet.getString("filename", "upload.bin"))
                if (payload == null) {
                    return NetworkPacket(
                        type = NetworkPacket.TYPE_VFS_WRITE_RESPONSE,
                        body = JSONObject().put("requestId", reqId).put("success", false).put("error", "Empty payload.")
                    )
                }

                val dirCheck = PathValidator.validateRemotePath(dir)
                if (dirCheck is PathValidator.ValidationResult.Valid) {
                    val safMatch = safFoldersManager.findTreeForPath(dirCheck.normalizedRelativePath)
                    if (safMatch != null) {
                        val (tree, subSegs) = safMatch
                        return safFoldersManager.writeSafFile(tree, subSegs, safeName, payload).fold(
                            onSuccess = { sha ->
                                NetworkPacket(
                                    type = NetworkPacket.TYPE_VFS_WRITE_RESPONSE,
                                    body = JSONObject().put("requestId", reqId).put("success", true).put("sha256", sha)
                                )
                            },
                            onFailure = { err ->
                                NetworkPacket(
                                    type = NetworkPacket.TYPE_VFS_WRITE_RESPONSE,
                                    body = JSONObject().put("requestId", reqId).put("success", false)
                                        .put("error", err.message ?: "SAF write failed")
                                )
                            }
                        )
                    }
                }

                val target = if (dir == "/") "/Downloads/$safeName" else "${dir.trimEnd('/')}/$safeName"
                when (val res = PathValidator.resolveWithinRoot(authorizedVaultRoot, target)) {
                    is PathValidator.ValidationResult.Denied -> NetworkPacket(
                        type = NetworkPacket.TYPE_VFS_WRITE_RESPONSE,
                        body = JSONObject().put("requestId", reqId).put("success", false).put("error", res.reason)
                    )
                    is PathValidator.ValidationResult.Valid -> {
                        val dest = res.resolvedFile!!
                        dest.parentFile?.mkdirs()
                        dest.writeBytes(payload)
                        NetworkPacket(
                            type = NetworkPacket.TYPE_VFS_WRITE_RESPONSE,
                            body = JSONObject().put("requestId", reqId).put("success", true)
                                .put("sha256", CryptoIdentityManager.sha256File(dest))
                        )
                    }
                }
            }

            else -> null
        }
    }

    private fun categorizePacket(type: String): LogCategory = when {
        type == NetworkPacket.TYPE_IDENTITY -> LogCategory.DISCOVERY
        type == NetworkPacket.TYPE_PAIR -> LogCategory.PAIRING
        type.startsWith("kdeconnect.sftp") || type.startsWith("kdeconnect.vfs") || type == NetworkPacket.TYPE_SHARE_REQUEST -> LogCategory.FILES
        type.startsWith("kdeconnect.space") -> LogCategory.SPACE
        else -> LogCategory.PLUGINS
    }

    private fun extractCommonName(dn: String): String? {
        return dn.split(',')
            .map { it.trim() }
            .firstOrNull { it.startsWith("CN=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim()
    }

    private fun guessMimeTypeByName(fileName: String): String {
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: when (ext) {
            "md", "txt", "log", "json", "sh", "kt", "py" -> "text/plain"
            "pdf" -> "application/pdf"
            "png" -> "image/png"
            "jpg", "jpeg" -> "image/jpeg"
            else -> "application/octet-stream"
        }
    }

    companion object {
        const val DEFAULT_KDE_PORT = 1716
        const val MIN_PAYLOAD_PORT = 1739
        const val MAX_KDE_PORT = 1764
    }
}
