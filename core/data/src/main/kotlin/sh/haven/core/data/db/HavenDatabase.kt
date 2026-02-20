package sh.haven.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import sh.haven.core.data.db.entities.ConnectionLog
import sh.haven.core.data.db.entities.ConnectionProfile
import sh.haven.core.data.db.entities.KnownHost

@Database(
    entities = [
        ConnectionProfile::class,
        KnownHost::class,
        ConnectionLog::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class HavenDatabase : RoomDatabase() {
    abstract fun connectionDao(): ConnectionDao
    abstract fun knownHostDao(): KnownHostDao
    abstract fun connectionLogDao(): ConnectionLogDao
}
