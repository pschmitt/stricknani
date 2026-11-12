package blue.anika.wolle.di

import blue.anika.wolle.BuildConfig
import blue.anika.wolle.data.account.AccountDeletionApi
import blue.anika.wolle.data.account.AccountDeletionGateway
import blue.anika.wolle.data.account.AccountDeletionRepository
import blue.anika.wolle.data.account.AccountGateway
import blue.anika.wolle.data.account.AccountRepository
import blue.anika.wolle.data.account.AccountV2Api
import blue.anika.wolle.data.api.AdminApi
import blue.anika.wolle.data.api.AuthInterceptor
import blue.anika.wolle.data.api.CategoriesApi
import blue.anika.wolle.data.api.ClientHeaderInterceptor
import blue.anika.wolle.data.api.DynamicBaseUrlInterceptor
import blue.anika.wolle.data.api.HistoryApi
import blue.anika.wolle.data.api.MetaApi
import blue.anika.wolle.data.api.ProjectsApi
import blue.anika.wolle.data.api.SessionExpiryInterceptor
import blue.anika.wolle.data.api.SyncApi
import blue.anika.wolle.data.api.YarnsApi
import blue.anika.wolle.data.api.clientHeaderValue
import blue.anika.wolle.data.api.v2.ActivityV2Api
import blue.anika.wolle.data.api.v2.ArchiveV2Api
import blue.anika.wolle.data.api.v2.CategoriesV2Api
import blue.anika.wolle.data.api.v2.ImportsV2Api
import blue.anika.wolle.data.api.v2.MetaV2Api
import blue.anika.wolle.data.api.v2.PhotoEditV2Api
import blue.anika.wolle.data.api.v2.ProjectsV2Api
import blue.anika.wolle.data.api.v2.SharingV2Api
import blue.anika.wolle.data.api.v2.SyncV2Api
import blue.anika.wolle.data.api.v2.TrashV2Api
import blue.anika.wolle.data.api.v2.YarnsV2Api
import blue.anika.wolle.data.backups.BackupsApi
import blue.anika.wolle.data.backups.BackupsGateway
import blue.anika.wolle.data.backups.BackupsRepository
import blue.anika.wolle.data.backups.HealthApi
import blue.anika.wolle.data.backups.RestartProbe
import blue.anika.wolle.data.backups.RetrofitRestartProbe
import blue.anika.wolle.data.errorreporting.ErrorReportingApi
import blue.anika.wolle.data.export.DataExportApi
import blue.anika.wolle.data.export.DataRestoreApi
import blue.anika.wolle.data.media.CoilMediaCacheEvictor
import blue.anika.wolle.data.media.MediaCacheEvictor
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.TimeUnit
import javax.inject.Qualifier
import javax.inject.Singleton
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
 * running them back through the rewrite interceptor would double-prefix the path on subpath-hosted
 * instances. Mirrors nyetbox's `@DownloadClient`.
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

    /**
     * The app's main HTTP client: every request is rewritten to the configured server and carries
     * the stored PAT.
     */
    @Provides
    @Singleton
    fun provideOkHttpClient(
        dynamicBaseUrlInterceptor: DynamicBaseUrlInterceptor,
        authInterceptor: AuthInterceptor,
        sessionExpiryInterceptor: SessionExpiryInterceptor,
    ): OkHttpClient =
        OkHttpClient.Builder()
            // Rewrites scheme/host/path to the configured instance - added first so auth/logging
            // see the real request.
            .addInterceptor(dynamicBaseUrlInterceptor)
            .addInterceptor(authInterceptor)
            // Names this app build on the server's admin audit log.
            .addInterceptor(
                ClientHeaderInterceptor(
                    clientHeaderValue(
                        BuildConfig.VERSION_NAME,
                        BuildConfig.VERSION_CODE,
                        BuildConfig.DEBUG,
                    )
                )
            )
            // After auth, so it sees which token a request carried.
            .addInterceptor(sessionExpiryInterceptor)
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

    @Provides
    @Singleton
    fun provideMetaApi(retrofit: Retrofit): MetaApi = retrofit.create(MetaApi::class.java)

    @Provides
    @Singleton
    fun provideAdminApi(retrofit: Retrofit): AdminApi = retrofit.create(AdminApi::class.java)

    @Provides
    @Singleton
    fun provideBackupsApi(retrofit: Retrofit): BackupsApi = retrofit.create(BackupsApi::class.java)

    @Provides
    @Singleton
    fun provideHealthApi(retrofit: Retrofit): HealthApi = retrofit.create(HealthApi::class.java)

    @Provides fun provideBackupsGateway(repository: BackupsRepository): BackupsGateway = repository

    @Provides fun provideRestartProbe(probe: RetrofitRestartProbe): RestartProbe = probe

    @Provides
    @Singleton
    fun provideHistoryApi(retrofit: Retrofit): HistoryApi = retrofit.create(HistoryApi::class.java)

    @Provides
    @Singleton
    fun provideCategoriesApi(retrofit: Retrofit): CategoriesApi =
        retrofit.create(CategoriesApi::class.java)

    @Provides
    @Singleton
    fun provideYarnsApi(retrofit: Retrofit): YarnsApi = retrofit.create(YarnsApi::class.java)

    @Provides
    @Singleton
    fun provideProjectsApi(retrofit: Retrofit): ProjectsApi =
        retrofit.create(ProjectsApi::class.java)

    @Provides
    @Singleton
    fun provideImportsV2Api(retrofit: Retrofit): ImportsV2Api =
        retrofit.create(ImportsV2Api::class.java)

    @Provides
    @Singleton
    fun provideSyncApi(retrofit: Retrofit): SyncApi = retrofit.create(SyncApi::class.java)

    @Provides
    @Singleton
    fun provideSyncV2Api(retrofit: Retrofit): SyncV2Api = retrofit.create(SyncV2Api::class.java)

    // SNA-87: v2 clients. Repositories pick v1 or v2 through ApiCapabilities
    // (docs/v2-migration.md).
    @Provides
    @Singleton
    fun provideMetaV2Api(retrofit: Retrofit): MetaV2Api = retrofit.create(MetaV2Api::class.java)

    @Provides
    @Singleton
    fun provideAccountV2Api(retrofit: Retrofit): AccountV2Api =
        retrofit.create(AccountV2Api::class.java)

    @Provides
    @Singleton
    fun provideDataExportApi(retrofit: Retrofit, okHttpClient: OkHttpClient): DataExportApi {
        // The server builds the zip while streaming it: a long silence before the first byte and
        // between large files is normal, and the whole download has no overall time limit.
        val exportClient =
            okHttpClient
                .newBuilder()
                .readTimeout(120, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        return retrofit.newBuilder().client(exportClient).build().create(DataExportApi::class.java)
    }

    @Provides
    @Singleton
    fun provideDataRestoreApi(retrofit: Retrofit, okHttpClient: OkHttpClient): DataRestoreApi {
        // A large archive uploads slowly and the server answers only after it has processed every
        // file: no overall time limit, generous silence limits in both directions.
        val restoreClient =
            okHttpClient
                .newBuilder()
                .writeTimeout(120, TimeUnit.SECONDS)
                .readTimeout(600, TimeUnit.SECONDS)
                .callTimeout(0, TimeUnit.SECONDS)
                .build()
        return retrofit
            .newBuilder()
            .client(restoreClient)
            .build()
            .create(DataRestoreApi::class.java)
    }

    @Provides
    @Singleton
    fun provideErrorReportingApi(retrofit: Retrofit): ErrorReportingApi =
        retrofit.create(ErrorReportingApi::class.java)

    @Provides
    @Singleton
    fun provideAccountGateway(repository: AccountRepository): AccountGateway = repository

    @Provides
    @Singleton
    fun provideAccountDeletionApi(retrofit: Retrofit): AccountDeletionApi =
        retrofit.create(AccountDeletionApi::class.java)

    @Provides
    @Singleton
    fun provideAccountDeletionGateway(
        repository: AccountDeletionRepository
    ): AccountDeletionGateway = repository

    @Provides
    @Singleton
    fun provideProjectsV2Api(retrofit: Retrofit): ProjectsV2Api =
        retrofit.create(ProjectsV2Api::class.java)

    @Provides
    @Singleton
    fun provideArchiveV2Api(retrofit: Retrofit): ArchiveV2Api =
        retrofit.create(ArchiveV2Api::class.java)

    @Provides
    @Singleton
    fun providePhotoEditV2Api(retrofit: Retrofit): PhotoEditV2Api =
        retrofit.create(PhotoEditV2Api::class.java)

    @Provides
    @Singleton
    fun provideMediaCacheEvictor(impl: CoilMediaCacheEvictor): MediaCacheEvictor = impl

    @Provides
    @Singleton
    fun provideSharingV2Api(retrofit: Retrofit): SharingV2Api =
        retrofit.create(SharingV2Api::class.java)

    @Provides
    @Singleton
    fun provideActivityV2Api(retrofit: Retrofit): ActivityV2Api =
        retrofit.create(ActivityV2Api::class.java)

    @Provides
    @Singleton
    fun provideYarnsV2Api(retrofit: Retrofit): YarnsV2Api = retrofit.create(YarnsV2Api::class.java)

    @Provides
    @Singleton
    fun provideCategoriesV2Api(retrofit: Retrofit): CategoriesV2Api =
        retrofit.create(CategoriesV2Api::class.java)

    @Provides
    @Singleton
    fun provideTrashV2Api(retrofit: Retrofit): TrashV2Api = retrofit.create(TrashV2Api::class.java)
}
