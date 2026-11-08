package blue.anika.wolle.di

import android.os.Build
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Named

/** Qualifier of the injectable SDK int that picks the transfer path (tests inject any level). */
const val SDK_INT_NAME = "sdkInt"

@Module
@InstallIn(SingletonComponent::class)
object TransferModule {
    @Provides @Named(SDK_INT_NAME) fun provideSdkInt(): Int = Build.VERSION.SDK_INT
}
