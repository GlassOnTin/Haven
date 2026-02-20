package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "known_hosts")
data class KnownHost(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val hostname: String,
    val port: Int,
    val keyType: String,
    val publicKeyBase64: String,
    val fingerprint: String,
    val firstSeen: Long = System.currentTimeMillis(),
)
