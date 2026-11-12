package blue.anika.wolle.di

import blue.anika.wolle.data.passkey.CredentialManagerGateway
import blue.anika.wolle.data.passkey.HttpPasskeyLoginApi
import blue.anika.wolle.data.passkey.PasskeyApi
import blue.anika.wolle.data.passkey.PasskeyGateway
import blue.anika.wolle.data.passkey.PasskeyLoginApi
import blue.anika.wolle.data.passkey.PasskeyRepository
import blue.anika.wolle.data.passkey.PasskeyService
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import retrofit2.Retrofit

@Module
@InstallIn(SingletonComponent::class)
abstract class PasskeyModule {
    @Binds abstract fun bindPasskeyGateway(impl: CredentialManagerGateway): PasskeyGateway

    @Binds abstract fun bindPasskeyLoginApi(impl: HttpPasskeyLoginApi): PasskeyLoginApi

    @Binds abstract fun bindPasskeyService(impl: PasskeyRepository): PasskeyService

    companion object {
        @Provides
        @Singleton
        fun providePasskeyApi(retrofit: Retrofit): PasskeyApi =
            retrofit.create(PasskeyApi::class.java)
    }
}
