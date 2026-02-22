package sh.haven.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.KnownHost
import sh.haven.core.data.db.entities.SshKey

@Database(
    entities = [
        ConnectionProfile::class,
        KnownHost::class,
        ConnectionLog::class,
        SshKey::class,
    ],
    version = 2,
    exportSchema = true,
)
abstract class HavenDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun connectionLogDao(): ConnectionLogDao
    abstract fun sshKeyDao(): SshKeyDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `ssh_keys` (
                        `id` TEXT NOT NULL,
                        `label` TEXT NOT NULL,
                        `keyType` TEXT NOT NULL,
                        `privateKeyBytes` BLOB NOT NULL,
                        `publicKeyOpenSsh` TEXT NOT NULL,
                        `fingerprintSha256` TEXT NOT NULL,
                        `createdAt` INTEGER NOT NULL,
                        PRIMARY KEY(`id`)
                    )
                """.trimIndent())
            }
        }
    }
}
