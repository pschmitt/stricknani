package blue.anika.wolle.di

import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.passkey.QueuedWork
import blue.anika.wolle.data.uploads.PendingUploadStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

@Module
@InstallIn(SingletonComponent::class)
object QueuedWorkModule {
    @Provides
    fun provideQueuedWork(dao: PendingMutationDao, uploads: PendingUploadStore): QueuedWork =
        QueuedWork {
            dao.countAll() > 0 || uploads.hasPending()
        }
}
