package sh.haven.core.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SshKeyGeneratorTest {

    @Test
    fun `generate Ed25519 produces valid public key line`() {
        val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519)
        assertTrue(
            "Public key should start with ssh-ed25519",
            key.publicKeyOpenSsh.startsWith("ssh-ed25519 ")
        )
    }

    @Test
    fun `generate Ed25519 produces 32-byte private key`() {
        val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519)
        assertEquals(
            "Ed25519 private key should be 32 bytes",
            32, key.privateKeyBytes.size
        )
    }

    @Test
    fun `generate Ed25519 with comment appends it`() {
        val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519, "user@haven")
        assertTrue(
            "Public key line should end with comment",
            key.publicKeyOpenSsh.endsWith(" user@haven")
        )
    }

    @Test
    fun `generate Ed25519 produces SHA256 fingerprint`() {
        val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519)
        assertTrue(
            "Fingerprint should start with SHA256:",
            key.fingerprintSha256.startsWith("SHA256:")
        )
    }

    @Test
    fun `generate Ed25519 produces unique keys each time`() {
        val key1 = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519)
        val key2 = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ED25519)
        assertNotEquals(
            "Two generated keys should differ",
            key1.publicKeyOpenSsh, key2.publicKeyOpenSsh
        )
    }

    @Test
    fun `generate RSA produces valid public key line`() {
        val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.RSA_4096)
        assertTrue(
            "Public key should start with ssh-rsa",
            key.publicKeyOpenSsh.startsWith("ssh-rsa ")
        )
    }

    @Test
    fun `generate RSA private key is non-empty`() {
        val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.RSA_4096)
        assertTrue(
            "RSA private key should be substantial (>1000 bytes)",
            key.privateKeyBytes.size > 1000
        )
    }

    @Test
    fun `generate ECDSA produces valid public key line`() {
        val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ECDSA_384)
        assertTrue(
            "Public key should start with ecdsa-sha2-nistp384",
            key.publicKeyOpenSsh.startsWith("ecdsa-sha2-nistp384 ")
        )
    }

    @Test
    fun `generate ECDSA produces SHA256 fingerprint`() {
        val key = SshKeyGenerator.generate(SshKeyGenerator.KeyType.ECDSA_384)
        assertTrue(
            "Fingerprint should start with SHA256:",
            key.fingerprintSha256.startsWith("SHA256:")
        )
    }

    @Test
    fun `all key types set correct type field`() {
        SshKeyGenerator.KeyType.entries.forEach { type ->
            val key = SshKeyGenerator.generate(type)
            assertEquals("Generated key type should match requested type", type, key.type)
        }
    }

    @Test
    fun `fingerprint is deterministic for same key blob`() {
        val blob = "deterministic-test-data".toByteArray()
        val fp1 = SshKeyGenerator.fingerprintSha256(blob)
        val fp2 = SshKeyGenerator.fingerprintSha256(blob)
        assertEquals("Same input should produce same fingerprint", fp1, fp2)
    }

    @Test
    fun `fingerprint differs for different key blobs`() {
        val fp1 = SshKeyGenerator.fingerprintSha256("key-a".toByteArray())
        val fp2 = SshKeyGenerator.fingerprintSha256("key-b".toByteArray())
        assertNotEquals("Different inputs should produce different fingerprints", fp1, fp2)
    }
}
