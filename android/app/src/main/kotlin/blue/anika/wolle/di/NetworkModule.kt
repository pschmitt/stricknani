package blue.anika.wolle.di

import blue.anika.wolle.BuildConfig
import blue.anika.wolle.data.api.AuthInterceptor
import blue.anika.wolle.data.api.DynamicBaseUrlInterceptor
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor

/**
 * Unauthenticated, static-baseurl client used only by
 * [blue.anika.wolle.data.onboarding.OnboardingValidator] - deliberately bypasses
 * [DynamicBaseUrlInterceptor]/[AuthInterceptor], which read the currently *saved* connection
 * rather than the one being validated. Same pattern as syncwich's `ValidationClient`.
 */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ValidationClient

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level =
                if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC
                else HttpLoggingInterceptor.Level.NONE
        }

    /**
     * The app's main HTTP client: every request is rewritten to the configured server and
     * carries the stored PAT. Nothing injects this yet (SNA-7 wires up Retrofit/Coil against it)
     * - declaring it now, since a Hilt `@Provides` binding is only ever instantiated once
     * something actually requests it.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
        authInterceptor: AuthInterceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            // Rewrites scheme/host/path to the configured instance - added first so auth/logging
            // see the real request.
            .addInterceptor(dynamicBaseUrlInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor())
            .build()

    @Provides
    @Singleton
    @ValidationClient
    fun provideValidationClient(): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(loggingInterceptor()).build()
}
