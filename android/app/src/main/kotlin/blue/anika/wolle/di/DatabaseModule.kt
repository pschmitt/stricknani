package blue.anika.wolle.di

import android.content.Context
import androidx.room.Room
import blue.anika.wolle.data.db.AppDatabase
import blue.anika.wolle.data.db.dao.CategoryDao
import blue.anika.wolle.data.db.dao.ProjectDao
import blue.anika.wolle.data.db.dao.SyncStateDao
import blue.anika.wolle.data.db.dao.YarnDao
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
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, "stricknani.db")
            // Every table here is a fully rebuildable server-side cache, not user data - a schema
            // bump just wipes and resyncs rather than needing a hand-written Migration.
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()

    @Provides fun provideProjectDao(database: AppDatabase): ProjectDao = database.projectDao()

    @Provides fun provideYarnDao(database: AppDatabase): YarnDao = database.yarnDao()

    @Provides fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

    @Provides
    fun provideSyncStateDao(database: AppDatabase): SyncStateDao = database.syncStateDao()
}
