package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(tableName = "connection_profiles")
data class ConnectionProfile(
    @PrimaryKey
    val id: String = UUID.randomUUID().toString(),
    val label: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authType: AuthType = AuthType.PASSWORD,
    val keyId: String? = null,
    val colorTag: Int = 0,
    val lastConnected: Long? = null,
    val sortOrder: Int = 0,
    val connectionType: String = "SSH",
    val destinationHash: String? = null,
    val reticulumHost: String = "127.0.0.1",
    val reticulumPort: Int = 37428,
) {
    enum class AuthType {
        PASSWORD,
        KEY,
    }

    val isSsh: Boolean get() = connectionType == "SSH"
    val isReticulum: Boolean get() = connectionType == "RETICULUM"
}
