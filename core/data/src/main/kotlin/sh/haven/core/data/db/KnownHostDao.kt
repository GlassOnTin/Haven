package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.KnownHost

@Dao
interface KnownHostDao {

    @Query("SELECT * FROM known_hosts ORDER BY hostname ASC")
    fun observeAll(): Flow<List<KnownHost>>

    @Query("SELECT * FROM known_hosts WHERE hostname = :hostname AND port = :port LIMIT 1")
    suspend fun findByHostPort(hostname: String, port: Int): KnownHost?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(knownHost: KnownHost)

    @Delete
    suspend fun delete(knownHost: KnownHost)

    @Query("DELETE FROM known_hosts WHERE hostname = :hostname AND port = :port")
    suspend fun deleteByHostPort(hostname: String, port: Int)
}
