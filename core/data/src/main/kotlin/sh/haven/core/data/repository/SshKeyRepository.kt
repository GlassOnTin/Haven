package sh.haven.core.data.repository

import kotlinx.coroutines.flow.Flow
import sh.haven.core.data.db.SshKeyDao
import sh.haven.core.data.db.entities.SshKey
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SshKeyRepository @Inject constructor(
    private val sshKeyDao: SshKeyDao,
) {
    fun observeAll(): Flow<List<SshKey>> = sshKeyDao.observeAll()

    suspend fun getById(id: String): SshKey? = sshKeyDao.getById(id)

    suspend fun save(key: SshKey) = sshKeyDao.upsert(key)

    suspend fun delete(id: String) = sshKeyDao.deleteById(id)
}
