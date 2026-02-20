package sh.haven.core.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "connection_logs",
    foreignKeys = [
        ForeignKey(
            entity = ConnectionProfile::class,
            parentColumns = ["id"],
            childColumns = ["profileId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("profileId")],
)
data class ConnectionLog(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val profileId: String,
    val timestamp: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val status: Status = Status.CONNECTED,
) {
    enum class Status {
        CONNECTED,
        DISCONNECTED,
        FAILED,
        TIMEOUT,
    }
}
