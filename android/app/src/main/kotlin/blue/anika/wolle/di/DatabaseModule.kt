package blue.anika.wolle.di

import android.content.Context
import androidx.room.Room
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.MIGRATION_3_4
import blue.anika.wolle.data.db.MIGRATION_4_5
import blue.anika.wolle.data.db.MIGRATION_5_6
import blue.anika.wolle.data.db.MIGRATION_6_7
import blue.anika.wolle.data.db.dao.CategoryDao
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.ProjectDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.dao.YarnDao
import blue.anika.wolle.data.impersonation.OfflineWriteGuard
import blue.anika.wolle.data.impersonation.OnlineOnlyPendingMutationDao
import blue.anika.wolle.data.impersonation.SessionMode
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.ui.common.NetworkStatus
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
    // SettingsRepository is only here for its side effect: it decides, once, whether this process
    // is an impersonation, and an impersonation gets its own database file (never the
    // administrator's cache and queue of offline writes).
    fun provideAppDatabase(
        @ApplicationContext context: Context,
        @Suppress("UNUSED_PARAMETER") settings: SettingsRepository,
    ): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, SessionMode.databaseName)
            // 3 -> 4 keeps queued offline writes. Older versions only held a rebuildable server
            // cache (plus a few queued writes of long-gone builds), so they still wipe and resync.
            .addMigrations(MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun provideProjectDao(database: AppDatabase): ProjectDao = database.projectDao()

    @Provides fun provideYarnDao(database: AppDatabase): YarnDao = database.yarnDao()

    @Provides fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

    @Provides fun provideSyncStateDao(database: AppDatabase): SyncStateDao = database.syncStateDao()

    @Provides
    fun providePendingMutationDao(database: AppDatabase): PendingMutationDao {
        val dao = database.pendingMutationDao()
        // Impersonation sessions are online-only: nothing may wait in the queue without a
        // connection (see OfflineWriteGuard).
        if (!SessionMode.impersonating) return dao
        val guard = OfflineWriteGuard(true) { NetworkStatus.hasValidatedInternet }
        return OnlineOnlyPendingMutationDao(dao, guard)
    }
}
