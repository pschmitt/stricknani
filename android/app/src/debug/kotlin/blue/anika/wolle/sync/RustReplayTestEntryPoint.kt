package blue.anika.wolle.sync

import blue.anika.wolle.data.api.ProjectsApi
import blue.anika.wolle.data.api.YarnsApi
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.dao.PendingMutationDao
import blue.anika.wolle.data.db.dao.ProjectDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.dao.YarnDao
import blue.anika.wolle.data.repository.ProjectRepository
import blue.anika.wolle.data.repository.YarnRepository
import blue.anika.wolle.data.settings.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Dependencies exposed only to debug-build instrumentation replay tests. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface RustReplayTestEntryPoint {
    fun appDatabase(): AppDatabase

    fun projectRepository(): ProjectRepository

    fun yarnRepository(): YarnRepository

    fun settingsRepository(): SettingsRepository

    fun pendingMutationDao(): PendingMutationDao

    fun syncStateDao(): SyncStateDao

    fun projectDao(): ProjectDao

    fun yarnDao(): YarnDao

    fun projectsApi(): ProjectsApi

    fun yarnsApi(): YarnsApi
}
