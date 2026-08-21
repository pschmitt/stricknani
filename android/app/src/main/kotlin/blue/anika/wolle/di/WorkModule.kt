package blue.anika.wolle.di

import android.content.Context
import androidx.work.WorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object WorkModule {

    // WorkManager's default ContentProvider auto-init is disabled in the manifest (Hilt needs to
    // supply HiltWorkerFactory via StricknaniApp's Configuration.Provider first), so it has to be
    // provided here explicitly rather than relying on WorkManager.getInstance() being
    // auto-initialized.
    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)
}
