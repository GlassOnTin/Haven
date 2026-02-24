package sh.haven.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.entities.SshKey

@Dao
interface SshKeyDao {

    @Query("SELECT * FROM ssh_keys ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SshKey>>

    @Query("SELECT * FROM ssh_keys ORDER BY createdAt DESC")
    suspend fun getAll(): List<SshKey>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: String): SshKey?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(key: SshKey)

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteById(id: String)
}
