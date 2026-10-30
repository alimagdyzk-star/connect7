package com.example.core.security

import android.content.Context
import android.os.Build
import org.bouncycastle.asn1.x500.X500NameBuilder
import org.bouncycastle.asn1.x500.style.BCStyle
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Date
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Authentic KDE Connect `SslHelper` & Cryptographic Identity Manager.
 *
 * Generates a persistent RSA-2048 keypair and a self-signed X.509v3 certificate (`CN=<deviceId>, O=KDE, OU=KDE Connect`)
 * compatible with genuine KDE Connect desktop/mobile apps (Linux Plasma, Windows, macOS, Android),
 * upgrades raw TCP sockets to mutual-authenticated TLS (`SSLSocket`), and computes exact SHA-256
 * pairing verification codes from peer X.509 certificates.
 */
class CryptoIdentityManager(
    private val context: Context,
    private val profilePrefix: String = ""
) {

    private val prefs = context.getSharedPreferences("${profilePrefix}apexconnect_identity_prefs", Context.MODE_PRIVATE)
    private val bcProvider = BouncyCastleProvider()

    val localDeviceId: String by lazy {
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            existing
        } else {
            val generated = UUID.randomUUID().toString().replace("-", "")
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            generated
        }
    }

    var localDeviceName: String
        get() {
            val saved = prefs.getString(KEY_DEVICE_NAME, null)
            if (!saved.isNullOrBlank()) return saved
            val model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}".trim()
            return model.ifBlank { "Android Phone" }
        }
        set(value) {
            prefs.edit().putString(KEY_DEVICE_NAME, value.trim().ifBlank { "Android Phone" }).apply()
        }

    private val keyAndCertificate: Triple<PublicKey, PrivateKey, X509Certificate> by lazy {
        ensureKeyAndCertificate()
    }

    val localCertificate: X509Certificate
        get() = keyAndCertificate.third

    val publicKeyBytes: ByteArray
        get() = localCertificate.encoded

    val localFingerprintFormatted: String by lazy {
        formatSha256Hex(sha256(localCertificate.encoded))
    }

    /**
     * Upgrades an existing connected TCP `Socket` to an encrypted `SSLSocket` using our X.509
     * KDE Connect identity certificate.
     *
     * Note on KDE Connect's inverted TLS role convention:
     * - When WE opened the TCP socket to a discovered peer (we are TCP client), KDE Connect expects
     *   us to act as TLS SERVER (`useClientMode = false`, `needClientAuth = true`).
     * - When a peer connected to OUR TCP port 1716 (we are TCP server), KDE Connect expects us to
     *   act as TLS CLIENT (`useClientMode = true`).
     */
    fun convertToTlsSocket(
        rawSocket: Socket,
        useClientMode: Boolean,
        requireClientCert: Boolean = true
    ): SSLSocket {
        val sslContext = createSslContext()
        val hostStr = rawSocket.inetAddress?.hostAddress ?: "127.0.0.1"
        val sslSocket = sslContext.socketFactory.createSocket(
            rawSocket,
            hostStr,
            rawSocket.port,
            true
        ) as SSLSocket

        sslSocket.useClientMode = useClientMode
        val supportedProtocols = sslSocket.supportedProtocols.filter {
            it == "TLSv1.3" || it == "TLSv1.2"
        }
        if (supportedProtocols.isNotEmpty()) {
            sslSocket.enabledProtocols = supportedProtocols.toTypedArray()
        }

        if (!useClientMode) {
            sslSocket.wantClientAuth = true
            sslSocket.needClientAuth = requireClientCert
        }

        sslSocket.soTimeout = 12_000
        sslSocket.startHandshake()
        sslSocket.soTimeout = 0 // Persistent keep-alive link after handshake completes
        return sslSocket
    }

    fun createTlsSocketFactory(): javax.net.ssl.SSLSocketFactory = createSslContext().socketFactory

    fun createTlsServerSocketFactory(): javax.net.ssl.SSLServerSocketFactory =
        createSslContext().serverSocketFactory

    private fun createSslContext(): SSLContext {
        val (_, privKey, cert) = keyAndCertificate
        val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setKeyEntry("kdeconnect", privKey, charArrayOf(), arrayOf(cert))
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply {
            init(keyStore, charArrayOf())
        }

        // KDE Connect TOFU / Pinning TrustManager: accepts X.509 self-signed peer certs during TLS
        // handshake so we can inspect & verify the peer's exact X.509 certificate bytes for pairing
        // or match against the pinned certificate stored in Room (`PairedDeviceEntity`).
        val trustManager = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

        return SSLContext.getInstance("TLS").apply {
            init(kmf.keyManagers, arrayOf(trustManager), SecureRandom())
        }
    }

    /**
     * Computes the exact KDE Connect 8-character hex pairing verification code from the two peers'
     * public keys/X.509 certificates sorted lexicographically (`SslHelper.getVerificationKey`).
     */
    fun computePairingVerificationCode(remotePublicKeyOrCert: ByteArray): String {
        val a = localCertificate.publicKey.encoded
        val b = try {
            val peerCert = parseCertificate(remotePublicKeyOrCert)
            peerCert.publicKey.encoded
        } catch (_: Exception) {
            remotePublicKeyOrCert
        }

        val digest = MessageDigest.getInstance("SHA-256")
        if (compareByteArraysLexicographically(a, b) >= 0) {
            digest.update(a)
            digest.update(b)
        } else {
            digest.update(b)
            digest.update(a)
        }
        val hash = digest.digest()
        return bytesToHex(hash).take(8).uppercase().chunked(4).joinToString(" ")
    }

    private fun compareByteArraysLexicographically(a: ByteArray, b: ByteArray): Int {
        val minLen = minOf(a.size, b.size)
        for (i in 0 until minLen) {
            val ua = a[i].toInt() and 0xFF
            val ub = b[i].toInt() and 0xFF
            if (ua != ub) return ua - ub
        }
        return a.size - b.size
    }

    fun parseCertificate(derBytes: ByteArray): X509Certificate {
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(derBytes)) as X509Certificate
    }

    private fun ensureKeyAndCertificate(): Triple<PublicKey, PrivateKey, X509Certificate> {
        val pubFile = File(context.filesDir, "${profilePrefix}kdeconnect_rsa.pub")
        val privFile = File(context.filesDir, "${profilePrefix}kdeconnect_rsa.key")
        val certFile = File(context.filesDir, "${profilePrefix}kdeconnect_x509.der")

        if (pubFile.exists() && privFile.exists() && certFile.exists()) {
            return try {
                val kf = KeyFactory.getInstance("RSA")
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                val cert = parseCertificate(certFile.readBytes())
                Triple(pub, priv, cert)
            } catch (_: Exception) {
                generateAndStoreCertificate(pubFile, privFile, certFile)
            }
        }
        return generateAndStoreCertificate(pubFile, privFile, certFile)
    }

    private fun generateAndStoreCertificate(
        pubFile: File,
        privFile: File,
        certFile: File
    ): Triple<PublicKey, PrivateKey, X509Certificate> {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(2048, SecureRandom())
        val pair: KeyPair = keyGen.generateKeyPair()

        val nameBuilder = X500NameBuilder(BCStyle.INSTANCE).apply {
            addRDN(BCStyle.CN, localDeviceId)
            addRDN(BCStyle.OU, "KDE Connect")
            addRDN(BCStyle.O, "KDE")
        }
        val subjectAndIssuer = nameBuilder.build()
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 86_400_000L * 365L)
        val notAfter = Date(now + 86_400_000L * 3650L) // 10-year validity matching KDE Connect

        val certBuilder = JcaX509v3CertificateBuilder(
            subjectAndIssuer,
            BigInteger.valueOf(now),
            notBefore,
            notAfter,
            subjectAndIssuer,
            pair.public
        )
        val signer = JcaContentSignerBuilder("SHA256WithRSAEncryption")
            .setProvider(bcProvider)
            .build(pair.private)

        val holder = certBuilder.build(signer)
        val cert: X509Certificate = JcaX509CertificateConverter()
            .setProvider(bcProvider)
            .getCertificate(holder)

        pubFile.writeBytes(pair.public.encoded)
        privFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(cert.encoded)
        return Triple(pair.public, pair.private, cert)
    }

    companion object {
        private const val KEY_DEVICE_ID = "local_device_id"
        private const val KEY_DEVICE_NAME = "local_device_name"

        fun sha256(bytes: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(bytes)

        fun sha256File(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered().use { input ->
                val buffer = ByteArray(8192)
                var read = input.read(buffer)
                while (read > 0) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            return bytesToHex(digest.digest())
        }

        fun bytesToHex(bytes: ByteArray): String =
            bytes.joinToString("") { "%02x".format(it) }

        fun formatSha256Hex(bytes: ByteArray): String =
            bytes.take(12).joinToString(":") { "%02X".format(it) }
    }
}
