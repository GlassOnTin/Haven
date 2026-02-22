package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "ssh_keys")
data class SshKey(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val keyType: String,
    val privateKeyBytes: ByteArray,
    val publicKeyOpenSsh: String,
    val fingerprintSha256: String,
    val createdAt: Long = System.currentTimeMillis(),
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SshKey) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()
}
