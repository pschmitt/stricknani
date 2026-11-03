package blue.anika.wolle.di

import blue.anika.wolle.data.oidc.HttpOidcLoginApi
import blue.anika.wolle.data.oidc.OidcAccountApi
import blue.anika.wolle.data.oidc.OidcLoginApi
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class OidcModule {
    @Binds abstract fun bindOidcLoginApi(impl: HttpOidcLoginApi): OidcLoginApi

    companion object {
        @Provides
        @Singleton
        fun provideOidcAccountApi(retrofit: Retrofit): OidcAccountApi =
            retrofit.create(OidcAccountApi::class.java)
    }
}
