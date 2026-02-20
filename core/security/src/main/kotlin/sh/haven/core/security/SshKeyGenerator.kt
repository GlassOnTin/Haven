package sh.haven.core.security

import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Base64

/**
 * Generates SSH keypairs using BouncyCastle (Ed25519) or JCA (RSA, ECDSA).
 * Private keys are returned as raw bytes — caller is responsible for encryption.
 */
object SshKeyGenerator {

    enum class KeyType(val displayName: String, val sshName: String) {
        ED25519("Ed25519", "ssh-ed25519"),
        RSA_4096("RSA 4096", "ssh-rsa"),
        ECDSA_384("ECDSA P-384", "ecdsa-sha2-nistp384"),
    }

    data class GeneratedKey(
        val type: KeyType,
        val privateKeyBytes: ByteArray,
        val publicKeyOpenSsh: String,
        val fingerprintSha256: String,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GeneratedKey) return false
            return type == other.type &&
                    privateKeyBytes.contentEquals(other.privateKeyBytes) &&
                    publicKeyOpenSsh == other.publicKeyOpenSsh
        }

        override fun hashCode(): Int = privateKeyBytes.contentHashCode()
    }

    fun generate(type: KeyType, comment: String = ""): GeneratedKey {
        return when (type) {
            KeyType.ED25519 -> generateEd25519(comment)
            KeyType.RSA_4096 -> generateRsa(comment)
            KeyType.ECDSA_384 -> generateEcdsa(comment)
        }
    }

    private fun generateEd25519(comment: String): GeneratedKey {
        val generator = Ed25519KeyPairGenerator()
        generator.init(Ed25519KeyGenerationParameters(SecureRandom()))
        val keyPair = generator.generateKeyPair()

        val privateKey = keyPair.private as Ed25519PrivateKeyParameters
        val publicKey = keyPair.public as Ed25519PublicKeyParameters

        val pubKeyBlob = encodeEd25519PublicKey(publicKey.encoded)
        val pubKeyB64 = Base64.getEncoder().encodeToString(pubKeyBlob)
        val pubLine = "ssh-ed25519 $pubKeyB64${if (comment.isNotEmpty()) " $comment" else ""}"

        val fingerprint = fingerprintSha256(pubKeyBlob)

        return GeneratedKey(
            type = KeyType.ED25519,
            privateKeyBytes = privateKey.encoded,
            publicKeyOpenSsh = pubLine,
            fingerprintSha256 = fingerprint,
        )
    }

    private fun generateRsa(comment: String): GeneratedKey {
        val kpg = KeyPairGenerator.getInstance("RSA")
        kpg.initialize(4096, SecureRandom())
        val keyPair: KeyPair = kpg.generateKeyPair()

        val rsaPub = keyPair.public as RSAPublicKey
        val pubKeyBlob = encodeRsaPublicKey(rsaPub)
        val pubKeyB64 = Base64.getEncoder().encodeToString(pubKeyBlob)
        val pubLine = "ssh-rsa $pubKeyB64${if (comment.isNotEmpty()) " $comment" else ""}"

        val fingerprint = fingerprintSha256(pubKeyBlob)

        return GeneratedKey(
            type = KeyType.RSA_4096,
            privateKeyBytes = keyPair.private.encoded,
            publicKeyOpenSsh = pubLine,
            fingerprintSha256 = fingerprint,
        )
    }

    private fun generateEcdsa(comment: String): GeneratedKey {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(384, SecureRandom())
        val keyPair: KeyPair = kpg.generateKeyPair()

        val ecPub = keyPair.public as ECPublicKey
        val pubKeyBlob = encodeEcdsaPublicKey(ecPub)
        val pubKeyB64 = Base64.getEncoder().encodeToString(pubKeyBlob)
        val pubLine = "ecdsa-sha2-nistp384 $pubKeyB64${if (comment.isNotEmpty()) " $comment" else ""}"

        val fingerprint = fingerprintSha256(pubKeyBlob)

        return GeneratedKey(
            type = KeyType.ECDSA_384,
            privateKeyBytes = keyPair.private.encoded,
            publicKeyOpenSsh = pubLine,
            fingerprintSha256 = fingerprint,
        )
    }

    // SSH wire encoding helpers

    private fun encodeEd25519PublicKey(rawPublicKey: ByteArray): ByteArray {
        val keyType = "ssh-ed25519".toByteArray()
        val buf = ByteArray(4 + keyType.size + 4 + rawPublicKey.size)
        var offset = 0
        offset = writeBytes(buf, offset, keyType)
        offset = writeBytes(buf, offset, rawPublicKey)
        return buf
    }

    private fun encodeRsaPublicKey(key: RSAPublicKey): ByteArray {
        val keyType = "ssh-rsa".toByteArray()
        val e = key.publicExponent.toByteArray()
        val n = key.modulus.toByteArray()
        val buf = ByteArray(4 + keyType.size + 4 + e.size + 4 + n.size)
        var offset = 0
        offset = writeBytes(buf, offset, keyType)
        offset = writeBytes(buf, offset, e)
        writeBytes(buf, offset, n)
        return buf
    }

    private fun encodeEcdsaPublicKey(key: ECPublicKey): ByteArray {
        val keyType = "ecdsa-sha2-nistp384".toByteArray()
        val curveName = "nistp384".toByteArray()
        val point = key.w
        val x = point.affineX.toByteArray()
        val y = point.affineY.toByteArray()
        // Uncompressed point: 0x04 || x || y (padded to 48 bytes each)
        val xPad = x.padStartTo(48)
        val yPad = y.padStartTo(48)
        val pointBytes = ByteArray(1 + 48 + 48)
        pointBytes[0] = 0x04
        xPad.copyInto(pointBytes, 1)
        yPad.copyInto(pointBytes, 49)

        val buf = ByteArray(4 + keyType.size + 4 + curveName.size + 4 + pointBytes.size)
        var offset = 0
        offset = writeBytes(buf, offset, keyType)
        offset = writeBytes(buf, offset, curveName)
        writeBytes(buf, offset, pointBytes)
        return buf
    }

    private fun writeBytes(buf: ByteArray, offset: Int, data: ByteArray): Int {
        buf[offset] = (data.size shr 24 and 0xFF).toByte()
        buf[offset + 1] = (data.size shr 16 and 0xFF).toByte()
        buf[offset + 2] = (data.size shr 8 and 0xFF).toByte()
        buf[offset + 3] = (data.size and 0xFF).toByte()
        data.copyInto(buf, offset + 4)
        return offset + 4 + data.size
    }

    private fun ByteArray.padStartTo(length: Int): ByteArray {
        if (this.size >= length) return this.takeLast(length).toByteArray()
        val padded = ByteArray(length)
        this.copyInto(padded, length - this.size)
        return padded
    }

    internal fun fingerprintSha256(publicKeyBlob: ByteArray): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(publicKeyBlob)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }
}
