package com.example.core.security

import android.content.Context
import android.os.Build
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.UUID

/**
 * Manages the persistent KDE Connect node identity (`deviceId`, `deviceName`) and RSA-2048 keypair
 * used to compute deterministic SHA-256 pairing verification codes between two devices, matching
 * `SslHelper.getVerificationKey(certA, certB)` in KDE Connect.
 */
class CryptoIdentityManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("apexconnect_identity_prefs", Context.MODE_PRIVATE)

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

    val publicKeyBytes: ByteArray by lazy {
        ensureKeyPair().first.encoded
    }

    val localFingerprintFormatted: String by lazy {
        formatSha256Hex(sha256(publicKeyBytes))
    }

    /**
     * Computes the symmetric KDE Connect 8-character hex pairing verification code from two
     * public keys/certificates sorted lexicographically so both peers display the exact same string.
     */
    fun computePairingVerificationCode(remotePublicKeyOrId: ByteArray): String {
        val a = publicKeyBytes
        val b = remotePublicKeyOrId
        val aHex = bytesToHex(a)
        val bHex = bytesToHex(b)

        val digest = MessageDigest.getInstance("SHA-256")
        if (aHex >= bHex) {
            digest.update(a)
            digest.update(b)
        } else {
            digest.update(b)
            digest.update(a)
        }
        val hash = digest.digest()
        return bytesToHex(hash).take(8).uppercase().chunked(4).joinToString(" - ")
    }

    private fun ensureKeyPair(): Pair<PublicKey, PrivateKey> {
        val pubFile = File(context.filesDir, "identity_rsa.pub")
        val privFile = File(context.filesDir, "identity_rsa.key")

        if (pubFile.exists() && privFile.exists()) {
            return try {
                val kf = KeyFactory.getInstance("RSA")
                val pub = kf.generatePublic(X509EncodedKeySpec(pubFile.readBytes()))
                val priv = kf.generatePrivate(PKCS8EncodedKeySpec(privFile.readBytes()))
                pub to priv
            } catch (_: Exception) {
                generateAndStoreKeyPair(pubFile, privFile)
            }
        }
        return generateAndStoreKeyPair(pubFile, privFile)
    }

    private fun generateAndStoreKeyPair(pubFile: File, privFile: File): Pair<PublicKey, PrivateKey> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048)
        val pair = generator.generateKeyPair()
        pubFile.writeBytes(pair.public.encoded)
        privFile.writeBytes(pair.private.encoded)
        return pair.public to pair.private
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
