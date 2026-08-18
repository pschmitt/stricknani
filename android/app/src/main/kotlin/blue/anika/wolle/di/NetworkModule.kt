package blue.anika.wolle.di

import blue.anika.wolle.BuildConfig
import blue.anika.wolle.data.api.AuthInterceptor
import blue.anika.wolle.data.api.CategoriesApi
import blue.anika.wolle.data.api.DynamicBaseUrlInterceptor
import blue.anika.wolle.data.api.MetaApi
import blue.anika.wolle.data.api.ProjectsApi
import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.YarnsApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit

/**
 * Unauthenticated, static-baseurl client used only by
 * [blue.anika.wolle.data.onboarding.OnboardingValidator] - deliberately bypasses
 * [DynamicBaseUrlInterceptor]/[AuthInterceptor], which read the currently *saved* connection rather
 * than the one being validated. Same pattern as syncwich's `ValidationClient`.
 */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class ValidationClient

/**
 * Client used for media (image) loading only: [AuthInterceptor] but deliberately NOT
 * [DynamicBaseUrlInterceptor]. Stricknani's media URLs (`/media/...`) are already relative paths
 * the app resolves against the configured server base URL itself before handing them to Coil -
 * running them back through the rewrite interceptor would double-prefix the path on
 * subpath-hosted instances. Mirrors nyetbox's `@DownloadClient`.
 */
@Qualifier @Retention(AnnotationRetention.BINARY) annotation class MediaClient

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

    /** The app's main HTTP client: every request is rewritten to the configured server and carries the stored PAT. */
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

    @Provides
    @Singleton
    @MediaClient
    fun provideMediaClient(authInterceptor: AuthInterceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor())
            .build()

    @Provides
    @Singleton
    fun provideRetrofit(okHttpClient: OkHttpClient, json: Json): Retrofit =
        Retrofit.Builder()
            // Placeholder - DynamicBaseUrlInterceptor rewrites every request to the configured
            // Stricknani server at request time, so this host is never actually contacted.
            .baseUrl("http://stricknani.invalid/")
            .client(okHttpClient)
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .build()

    @Provides @Singleton fun provideMetaApi(retrofit: Retrofit): MetaApi = retrofit.create(MetaApi::class.java)

    @Provides
    @Singleton
    fun provideCategoriesApi(retrofit: Retrofit): CategoriesApi = retrofit.create(CategoriesApi::class.java)

    @Provides
    @Singleton
    fun provideYarnsApi(retrofit: Retrofit): YarnsApi = retrofit.create(YarnsApi::class.java)

    @Provides
    @Singleton
    fun provideProjectsApi(retrofit: Retrofit): ProjectsApi = retrofit.create(ProjectsApi::class.java)

    @Provides @Singleton fun provideSyncApi(retrofit: Retrofit): SyncApi = retrofit.create(SyncApi::class.java)
}
