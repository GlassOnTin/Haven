package sh.haven.core.data.db

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): HavenDatabase {
        return Room.databaseBuilder(
            context,
            HavenDatabase::class.java,
            "haven.db",
        ).build()
    }

    @Provides
    fun provideConnectionDao(db: HavenDatabase): ConnectionDao = db.connectionDao()

    @Provides
    fun provideKnownHostDao(db: HavenDatabase): KnownHostDao = db.knownHostDao()

    @Provides
    fun provideConnectionLogDao(db: HavenDatabase): ConnectionLogDao = db.connectionLogDao()
}
