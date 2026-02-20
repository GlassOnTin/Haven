package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.ConnectionLog

@Dao
interface ConnectionLogDao {

    @Insert
    suspend fun insert(log: ConnectionLog)

    @Query(
        "SELECT * FROM connection_logs WHERE profileId = :profileId " +
        "ORDER BY timestamp DESC LIMIT :limit"
    )
    fun observeForProfile(profileId: String, limit: Int = 50): Flow<List<ConnectionLog>>

    @Query("DELETE FROM connection_logs WHERE timestamp < :olderThan")
    suspend fun deleteOlderThan(olderThan: Long)
}
