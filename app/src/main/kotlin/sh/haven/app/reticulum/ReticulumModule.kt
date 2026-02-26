package sh.haven.app.reticulum

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import sh.haven.core.reticulum.ReticulumBridge
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class ReticulumModule {

    @Binds
    @Singleton
    abstract fun bindReticulumBridge(impl: ChaquopyReticulumBridge): ReticulumBridge
}
