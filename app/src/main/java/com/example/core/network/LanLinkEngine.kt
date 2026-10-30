package com.example.core.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.webkit.MimeTypeMap
import com.example.core.protocol.NetworkPacket
import com.example.core.security.CryptoIdentityManager
import com.example.core.security.PathValidator
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
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
import java.security.cert.X509Certificate
import java.util.Properties
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
    val isWritable: Boolean = true
)

data class RemoteCommandItem(
    val key: String,
    val name: String,
    val command: String
)

/**
 * Stores dynamic SFTP credentials negotiated over TLS when a real KDE Connect peer responds with
 * `kdeconnect.sftp` (`ip`, `port`, `user`, `password`, `multiPaths` / `path`).
 */
data class SftpSessionCredentials(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    val rootPath: String
)

/**
 * Active encrypted TLS session with a real KDE Connect peer on the LAN.
 */
class ActiveTlsPeerSession(
    val deviceId: String,
    val ipAddress: String,
    val tcpPort: Int,
    val sslSocket: SSLSocket,
    val peerCertificate: X509Certificate
) {
    private val writer = OutputStreamWriter(sslSocket.outputStream, Charsets.UTF_8)
    private val writeLock = Any()

    fun sendPacketLine(serializedPacket: String) {
        synchronized(writeLock) {
            writer.write(serializedPacket)
            writer.flush()
        }
    }

    fun close() {
        runCatching { sslSocket.close() }
    }
}

/**
 * Authentic KDE Connect Protocol v8 Network Engine:
 * 1. Acquires Android `WifiManager.MulticastLock` so real Wi-Fi UDP discovery packets on 1716 are received.
 * 2. Registers & discovers `_kdeconnect._udp.` via Android `NsdManager` (mDNS / Bonjour / Avahi).
 * 3. Broadcasts & listens on UDP port 1716 (`255.255.255.255` + interface subnet broadcast addresses).
 * 4. Performs the authentic KDE Connect TCP -> Cleartext Identity -> Inverted Mutual TLS (`SSLSocket`)
 *    upgrade and keeps persistent bidirectional encrypted links alive with every connected peer.
 * 5. Connects to real KDE Connect SFTP servers (`kdeconnect.sftp` + `JSch ChannelSftp`) AND supports
 *    direct peer-to-peer `kdeconnect.vfs.*` + `kdeconnect.space.*` between two Android/ApexConnect devices.
 */
class LanLinkEngine(
    private val context: Context,
    private val identityManager: CryptoIdentityManager,
    private val onPeerConnectedOrUpdated: suspend (
        deviceId: String,
        deviceName: String,
        deviceType: String,
        ipAddress: String,
        tcpPort: Int,
        certificateBytes: ByteArray?,
        incomingCapabilities: List<String>,
        outgoingCapabilities: List<String>
    ) -> Unit,
    private val onPeerDisconnected: suspend (deviceId: String) -> Unit,
    private val onPacketReceived: suspend (senderDeviceId: String, packet: NetworkPacket, payloadBytes: ByteArray?) -> Unit
) {
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var activeTcpPort: Int = DEFAULT_KDE_PORT
        private set

    private var tcpServerSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var nsdDiscoveryListener: NsdManager.DiscoveryListener? = null
    private var nsdRegistrationListener: NsdManager.RegistrationListener? = null

    private val activeSessions = ConcurrentHashMap<String, ActiveTlsPeerSession>()
    private val sftpCredentialsByDevice = ConcurrentHashMap<String, SftpSessionCredentials>()
    private val pendingVfsResponses = ConcurrentHashMap<String, kotlinx.coroutines.CompletableDeferred<NetworkPacket>>()

    /**
     * Authorized root directory on this Android device exported when a paired peer browses OUR files
     * over `kdeconnect.vfs.*` (contains real subdirectories inside app storage, never exposing private DB/keys).
     */
    val authorizedVaultRoot: File by lazy {
        File(context.filesDir, "shared_export").apply {
            if (!exists()) {
                mkdirs()
                File(this, "Documents").mkdirs()
                File(this, "Pictures").mkdirs()
                File(this, "Downloads").mkdirs()
            }
        }
    }

    val persistentAttachmentDir: File by lazy {
        File(context.filesDir, "space_attachments").apply {
            if (!exists()) mkdirs()
        }
    }

    @Volatile
    private var isStopping: Boolean = false

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

    fun stop() {
        isStopping = true
        val sessions = activeSessions.values.toList()
        activeSessions.clear()
        sessions.forEach { it.close() }
        runCatching { tcpServerSocket?.close() }
        runCatching { udpSocket?.close() }
        stopMdns()
        runCatching {
            if (multicastLock?.isHeld == true) multicastLock?.release()
        }
    }

    fun isSessionActive(deviceId: String): Boolean =
        activeSessions[deviceId]?.sslSocket?.isClosed == false

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
        }
    }

    // =========================================================================
    // 1. mDNS / NSD (`_kdeconnect._udp.`) DISCOVERY & REGISTRATION
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
                override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) = Unit
            }
            nsdRegistrationListener = regListener
            nsd.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, regListener)
        }
    }

    private fun discoverMdnsPeers() {
        runCatching {
            val nsd = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
            val discListener = object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(regType: String) = Unit
                override fun onServiceFound(service: NsdServiceInfo) {
                    if (service.serviceName == identityManager.localDeviceId) return
                    nsd.resolveService(service, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                        override fun onServiceResolved(resolved: NsdServiceInfo) {
                            val host = resolved.host?.hostAddress ?: return
                            engineScope.launch {
                                broadcastUdpIdentity(unicastHost = host)
                            }
                        }
                    })
                }
                override fun onServiceLost(service: NsdServiceInfo) = Unit
                override fun onDiscoveryStopped(serviceType: String) = Unit
                override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
                override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            }
            nsdDiscoveryListener = discListener
            nsd.discoverServices("_kdeconnect._udp.", NsdManager.PROTOCOL_DNS_SD, discListener)
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
        for (port in DEFAULT_KDE_PORT..1764) {
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
        runCatching {
            rawSocket.soTimeout = 10_000
            // CRITICAL: Read byte-by-byte until '\n' without BufferedReader so zero TLS handshake bytes are pre-consumed!
            val identityLine = readLineUnbuffered(rawSocket.getInputStream()) ?: run {
                rawSocket.close()
                return@withContext
            }
            val identityPacket = NetworkPacket.parse(identityLine)
            if (identityPacket.type != NetworkPacket.TYPE_IDENTITY) {
                rawSocket.close()
                return@withContext
            }

            val peerDeviceId = identityPacket.getString("deviceId")
            if (peerDeviceId.isBlank() || peerDeviceId == identityManager.localDeviceId) {
                rawSocket.close()
                return@withContext
            }

            // Inverted TLS: we accepted TCP connection -> we act as TLS Client (`useClientMode = true`)
            val sslSocket = identityManager.convertToTlsSocket(rawSocket, useClientMode = true)
            val peerCert = sslSocket.session.peerCertificates.firstOrNull() as? X509Certificate
                ?: throw SecurityException("Peer did not present X.509 certificate during TLS handshake.")

            registerAndListenOnTlsSession(
                sslSocket = sslSocket,
                peerCert = peerCert,
                identityPacket = identityPacket,
                remoteIp = rawSocket.inetAddress.hostAddress ?: ""
            )
        }.onFailure {
            runCatching { rawSocket.close() }
        }
    }

    private fun readLineUnbuffered(input: java.io.InputStream, maxBytes: Int = 16_384): String? {
        val baos = java.io.ByteArrayOutputStream(512)
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
        val socket = runCatching {
            DatagramSocket(null).apply {
                reuseAddress = true
                broadcast = true
                bind(InetSocketAddress(DEFAULT_KDE_PORT))
            }
        }.getOrElse {
            runCatching { DatagramSocket(0).apply { broadcast = true } }.getOrNull()
        } ?: return@withContext

        udpSocket = socket
        val buffer = ByteArray(8192)
        while (isActive && !socket.isClosed) {
            val datagram = DatagramPacket(buffer, buffer.size)
            val received = runCatching { socket.receive(datagram) }.isSuccess
            if (!received) break
            val raw = String(datagram.data, 0, datagram.length, Charsets.UTF_8)
            val packet = runCatching { NetworkPacket.parse(raw) }.getOrNull() ?: continue
            if (packet.type == NetworkPacket.TYPE_IDENTITY) {
                val peerId = packet.getString("deviceId")
                val remoteIp = datagram.address.hostAddress ?: continue
                if (peerId.isNotBlank() && peerId != identityManager.localDeviceId) {
                    val peerTcpPort = packet.getInt("tcpPort", DEFAULT_KDE_PORT)
                    if (!isSessionActive(peerId)) {
                        engineScope.launch {
                            initiateOutgoingConnectionToPeer(remoteIp, peerTcpPort, packet)
                        }
                    }
                }
            }
        }
    }

    /**
     * Broadcasts genuine KDE Connect v8 `kdeconnect.identity` over UDP 1716 to:
     * - `255.255.255.255`
     * - Every active Wi-Fi / LAN interface broadcast address (e.g., `192.168.1.255`)
     * - Optional direct unicast IP entered by the user
     */
    suspend fun broadcastUdpIdentity(unicastHost: String? = null): Boolean = withContext(Dispatchers.IO) {
        val packet = NetworkPacket.createIdentityPacket(
            deviceId = identityManager.localDeviceId,
            deviceName = identityManager.localDeviceName,
            deviceType = "phone",
            tcpPort = activeTcpPort
        )
        val bytes = packet.serialize().toByteArray(Charsets.UTF_8)

        runCatching {
            val (parsedHost, parsedPort) = parseHostAndOptionalPort(unicastHost)
            DatagramSocket().use { sender ->
                sender.broadcast = true
                val targets = linkedSetOf<Pair<InetAddress, Int>>()
                if (!parsedHost.isNullOrBlank()) {
                    targets.add(InetAddress.getByName(parsedHost) to parsedPort)
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
                for ((address, targetPort) in targets) {
                    runCatching {
                        val dgram = DatagramPacket(bytes, bytes.size, address, targetPort)
                        sender.send(dgram)
                    }
                }
            }
            // If user entered a direct IP[:port], allow 350ms for UDP callback, then probe TCP directly if needed
            if (!parsedHost.isNullOrBlank()) {
                engineScope.launch {
                    delay(350)
                    val alreadyConnected = activeSessions.values.any { it.ipAddress == parsedHost }
                    if (!alreadyConnected) {
                        initiateDirectTcpProbe(parsedHost, parsedPort)
                    }
                }
            }
            true
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
     * When we discover a peer via UDP `kdeconnect.identity` (or direct IP probe):
     * 1. Open TCP connection to `remoteIp:peerTcpPort`.
     * 2. Send OUR `kdeconnect.identity` in cleartext first.
     * 3. Upgrade socket to TLS in **TLS SERVER mode** (`useClientMode = false`, mutual auth).
     */
    suspend fun initiateOutgoingConnectionToPeer(
        remoteIp: String,
        peerTcpPort: Int,
        udpIdentityPacket: NetworkPacket?
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val rawSocket = Socket()
            rawSocket.connect(InetSocketAddress(remoteIp, peerTcpPort), 4500)
            rawSocket.soTimeout = 10_000

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

            val effectiveIdentity = udpIdentityPacket ?: run {
                val cn = extractCommonName(peerCert.subjectX500Principal.name)
                    ?: "kde_peer_${remoteIp.replace('.', '_')}"
                val stdCaps = JSONArray(
                    listOf(
                        NetworkPacket.TYPE_PAIR,
                        NetworkPacket.TYPE_PING,
                        NetworkPacket.TYPE_SHARE_REQUEST,
                        NetworkPacket.TYPE_CLIPBOARD,
                        NetworkPacket.TYPE_MOUSEPAD_REQUEST,
                        NetworkPacket.TYPE_MPRIS,
                        NetworkPacket.TYPE_MPRIS_REQUEST,
                        NetworkPacket.TYPE_SFTP,
                        NetworkPacket.TYPE_SFTP_REQUEST,
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
                        put("deviceName", "KDE Connect ($remoteIp)")
                        put("deviceType", "desktop")
                        put("protocolVersion", NetworkPacket.PROTOCOL_VERSION)
                        put("tcpPort", peerTcpPort)
                        put("incomingCapabilities", stdCaps)
                        put("outgoingCapabilities", stdCaps)
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

        onPeerConnectedOrUpdated(
            peerId,
            peerName,
            peerType,
            remoteIp,
            peerPort,
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
                    val packet = runCatching { NetworkPacket.parse(line) }.getOrNull() ?: continue

                    // Pull binary payload over TLS if peer attached `payloadTransferInfo.port`,
                    // except `TYPE_VFS_READ_RESPONSE` which streams directly to disk in `downloadViaVfsTls`.
                    var payloadBytes: ByteArray? = null
                    if (packet.hasPayload() && packet.type != NetworkPacket.TYPE_VFS_READ_RESPONSE) {
                        val port = packet.payloadTransferInfo?.optInt("port", -1) ?: -1
                        if (port > 0) {
                            payloadBytes = pullEncryptedPayloadFromPeer(remoteIp, port)
                        }
                    }

                    handleEncryptedSessionPacket(session, packet, payloadBytes)
                }
            } catch (_: Exception) {
                // Socket closed or reset by peer
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
        packet: NetworkPacket,
        payloadBytes: ByteArray?
    ) {
        when (packet.type) {
            // Capture real KDE Connect SFTP server credentials when peer starts its SFTP subsystem
            NetworkPacket.TYPE_SFTP -> {
                val ip = packet.getString("ip", session.ipAddress)
                val port = packet.getInt("port", 22)
                val user = packet.getString("user", "kdeconnect")
                val password = packet.getString("password", "")
                val multiPaths = packet.getStringList("multiPaths")
                val rootPath = packet.getString("path").ifBlank { multiPaths.firstOrNull() ?: "/" }
                if (password.isNotBlank() && port > 0) {
                    sftpCredentialsByDevice[session.deviceId] = SftpSessionCredentials(
                        host = ip,
                        port = port,
                        username = user,
                        password = password,
                        rootPath = rootPath
                    )
                }
                onPacketReceived(session.deviceId, packet, payloadBytes)
            }

            // Satisfy awaiting VFS coroutines when browsing another ApexConnect Android peer
            NetworkPacket.TYPE_VFS_LIST_RESPONSE,
            NetworkPacket.TYPE_VFS_READ_RESPONSE,
            NetworkPacket.TYPE_VFS_WRITE_RESPONSE -> {
                val reqId = packet.getString("requestId")
                pendingVfsResponses.remove(reqId)?.complete(packet)
            }

            // Serve OUR authorized files when another paired ApexConnect peer browses this phone
            NetworkPacket.TYPE_VFS_LIST_REQUEST,
            NetworkPacket.TYPE_VFS_READ_REQUEST,
            NetworkPacket.TYPE_VFS_WRITE_REQUEST -> {
                val reply = serveLocalVfsRequest(packet, payloadBytes)
                if (reply != null) {
                    runCatching { session.sendPacketLine(reply.serialize()) }
                }
            }

            else -> {
                onPacketReceived(session.deviceId, packet, payloadBytes)
            }
        }
    }

    // =========================================================================
    // 4. SENDING PACKETS & ENCRYPTED PAYLOAD SOCKETS OVER ACTIVE TLS LINK
    // =========================================================================

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
            val outboundPacket = if (payloadBytes != null) {
                val payloadServer = ServerSocket(0).apply { reuseAddress = true }
                val transferPort = payloadServer.localPort

                engineScope.launch {
                    runCatching {
                        payloadServer.use { srv ->
                            srv.soTimeout = 15_000
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
                }

                packet.copy(
                    payloadSize = payloadBytes.size.toLong(),
                    payloadTransferInfo = JSONObject().put("port", transferPort)
                )
            } else {
                packet
            }

            session.sendPacketLine(outboundPacket.serialize())
        }
    }

    private suspend fun pullEncryptedPayloadFromPeer(remoteIp: String, port: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            runCatching {
                val raw = Socket()
                raw.connect(InetSocketAddress(remoteIp, port), 8000)
                raw.soTimeout = 15_000
                identityManager.convertToTlsSocket(
                    rawSocket = raw,
                    useClientMode = true,
                    requireClientCert = false
                ).use { tlsSock ->
                    tlsSock.inputStream.readBytes()
                }
            }.getOrElse {
                // Fallback to raw TCP stream if peer's payload socket is non-TLS
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
    // 5. REAL REMOTE FILE BROWSING: KDE CONNECT SFTP (JSch) + P2P VFS
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

        // Path A: Peer speaks ApexConnect VFS directly over our encrypted TLS channel
        if (supportsApexVfs) {
            return@withContext requestVfsDirectoryOverTls(deviceId, normalizedPath)
        }

        // Path B: Genuine KDE Connect peer advertising `kdeconnect.sftp`
        if (supportsKdeSftp) {
            var creds = sftpCredentialsByDevice[deviceId]
            if (creds == null) {
                // Ask remote KDE Connect peer to spin up its SFTP server (`{"startBrowsing": true}`)
                sendPacketToConnectedPeer(
                    deviceId = deviceId,
                    packet = NetworkPacket(
                        type = NetworkPacket.TYPE_SFTP_REQUEST,
                        body = JSONObject().put("startBrowsing", true)
                    )
                )
                // Wait up to 3.5s for peer's `kdeconnect.sftp` credentials packet
                repeat(14) {
                    delay(250)
                    creds = sftpCredentialsByDevice[deviceId]
                    if (creds != null) return@repeat
                }
            }

            val activeCreds = creds ?: return@withContext Result.failure(
                UnsupportedOperationException(
                    "Sent kdeconnect.sftp.request, but remote device did not expose an SFTP server (note: desktop KDE Connect mounts phones as SFTP clients; Android KDE Connect hosts the SFTP server)."
                )
            )
            return@withContext browseViaRealSftp(activeCreds, normalizedPath)
        }

        Result.failure(
            UnsupportedOperationException(
                "This connected device does not advertise SFTP (`kdeconnect.sftp`) or VFS (`kdeconnect.vfs.list.request`) directory browsing capabilities."
            )
        )
    }

    private suspend fun browseViaRealSftp(
        creds: SftpSessionCredentials,
        relativePath: String
    ): Result<Pair<String, List<RemoteFileEntry>>> = withContext(Dispatchers.IO) {
        runCatching {
            var session: Session? = null
            var channel: ChannelSftp? = null
            try {
                val jsch = JSch()
                session = jsch.getSession(creds.username, creds.host, creds.port).apply {
                    setPassword(creds.password)
                    setConfig(Properties().apply { put("StrictHostKeyChecking", "no") })
                    connect(8000)
                }
                channel = (session.openChannel("sftp") as ChannelSftp).apply {
                    connect(8000)
                }

                val targetFull = if (relativePath == "/") {
                    creds.rootPath
                } else {
                    creds.rootPath.trimEnd('/') + relativePath
                }

                @Suppress("UNCHECKED_CAST")
                val rawEntries = channel.ls(targetFull) as Vector<ChannelSftp.LsEntry>
                val items = rawEntries
                    .filter { it.filename != "." && it.filename != ".." }
                    .map { ls ->
                        val isDir = ls.attrs.isDir
                        val childRel = if (relativePath == "/") "/${ls.filename}" else "$relativePath/${ls.filename}"
                        RemoteFileEntry(
                            name = ls.filename,
                            relativePath = childRel,
                            isDirectory = isDir,
                            sizeBytes = if (isDir) 0L else ls.attrs.size,
                            lastModifiedEpochMs = ls.attrs.mTime.toLong() * 1000L,
                            mimeType = if (isDir) "inode/directory" else guessMimeTypeByName(ls.filename)
                        )
                    }
                    .sortedWith(compareByDescending<RemoteFileEntry> { it.isDirectory }.thenBy { it.name.lowercase() })

                relativePath to items
            } finally {
                runCatching { channel?.disconnect() }
                runCatching { session?.disconnect() }
            }
        }
    }

    suspend fun downloadRemoteFile(
        deviceId: String,
        remoteRelativePath: String,
        supportsApexVfs: Boolean,
        destinationOutput: OutputStream,
        onProgress: suspend (transferred: Long, total: Long) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        val pathCheck = PathValidator.validateRemotePath(remoteRelativePath)
        if (pathCheck is PathValidator.ValidationResult.Denied) {
            return@withContext Result.failure(SecurityException(pathCheck.reason))
        }
        val cleanPath = (pathCheck as PathValidator.ValidationResult.Valid).normalizedRelativePath

        if (supportsApexVfs) {
            return@withContext downloadViaVfsTls(deviceId, cleanPath, destinationOutput, onProgress)
        }

        val creds = sftpCredentialsByDevice[deviceId]
            ?: return@withContext Result.failure(IllegalStateException("No active SFTP session credentials for device."))

        runCatching {
            var session: Session? = null
            var channel: ChannelSftp? = null
            try {
                val jsch = JSch()
                session = jsch.getSession(creds.username, creds.host, creds.port).apply {
                    setPassword(creds.password)
                    setConfig(Properties().apply { put("StrictHostKeyChecking", "no") })
                    connect(8000)
                }
                channel = (session.openChannel("sftp") as ChannelSftp).apply { connect(8000) }

                val fullRemotePath = creds.rootPath.trimEnd('/') + cleanPath
                val totalSize = runCatching { channel.lstat(fullRemotePath).size }.getOrDefault(0L)
                val digest = java.security.MessageDigest.getInstance("SHA-256")

                channel.get(fullRemotePath).use { input ->
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
                CryptoIdentityManager.bytesToHex(digest.digest())
            } finally {
                runCatching { channel?.disconnect() }
                runCatching { session?.disconnect() }
            }
        }
    }

    private suspend fun requestVfsDirectoryOverTls(
        deviceId: String,
        cleanPath: String
    ): Result<Pair<String, List<RemoteFileEntry>>> = withContext(Dispatchers.IO) {
        val reqId = java.util.UUID.randomUUID().toString()
        val deferred = kotlinx.coroutines.CompletableDeferred<NetworkPacket>()
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
            kotlinx.coroutines.withTimeout(8000) { deferred.await() }
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
        val reqId = java.util.UUID.randomUUID().toString()
        val deferred = kotlinx.coroutines.CompletableDeferred<NetworkPacket>()
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

        val resp = runCatching { kotlinx.coroutines.withTimeout(8000) { deferred.await() } }.getOrElse {
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
            val digest = java.security.MessageDigest.getInstance("SHA-256")
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
            actualSha
        }
    }

    private fun serveLocalVfsRequest(packet: NetworkPacket, payload: ByteArray?): NetworkPacket? {
        val reqId = packet.getString("requestId")
        return when (packet.type) {
            NetworkPacket.TYPE_VFS_LIST_REQUEST -> {
                val path = packet.getString("path", "/")
                when (val res = PathValidator.resolveWithinRoot(authorizedVaultRoot, path)) {
                    is PathValidator.ValidationResult.Denied -> NetworkPacket(
                        type = NetworkPacket.TYPE_VFS_LIST_RESPONSE,
                        body = JSONObject().put("requestId", reqId).put("success", false).put("error", res.reason)
                    )
                    is PathValidator.ValidationResult.Valid -> {
                        val dir = res.resolvedFile ?: authorizedVaultRoot
                        val arr = JSONArray()
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
                when (val res = PathValidator.resolveWithinRoot(authorizedVaultRoot, path)) {
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
                            val payloadServer = ServerSocket(0).apply { reuseAddress = true }
                            val port = payloadServer.localPort
                            engineScope.launch {
                                runCatching {
                                    payloadServer.use { srv ->
                                        srv.soTimeout = 12_000
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
                val target = if (dir == "/") "/$safeName" else "$dir/$safeName"
                when (val res = PathValidator.resolveWithinRoot(authorizedVaultRoot, target)) {
                    is PathValidator.ValidationResult.Denied -> NetworkPacket(
                        type = NetworkPacket.TYPE_VFS_WRITE_RESPONSE,
                        body = JSONObject().put("requestId", reqId).put("success", false).put("error", res.reason)
                    )
                    is PathValidator.ValidationResult.Valid -> {
                        val dest = res.resolvedFile!!
                        dest.parentFile?.mkdirs()
                        if (payload != null) {
                            dest.writeBytes(payload)
                            NetworkPacket(
                                type = NetworkPacket.TYPE_VFS_WRITE_RESPONSE,
                                body = JSONObject().put("requestId", reqId).put("success", true)
                                    .put("sha256", CryptoIdentityManager.sha256File(dest))
                            )
                        } else {
                            NetworkPacket(
                                type = NetworkPacket.TYPE_VFS_WRITE_RESPONSE,
                                body = JSONObject().put("requestId", reqId).put("success", false).put("error", "Empty payload.")
                            )
                        }
                    }
                }
            }

            else -> null
        }
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
    }
}
