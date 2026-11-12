package blue.anika.wolle.data.passkey

import android.content.Context
import android.os.Build
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnsupportedException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/**
 * Where the UI's window comes from. Credential Manager shows its sheet over an Activity, so the
 * screens hand one in; the view models and the repository never touch a [Context] themselves (and
 * tests pass a host that is never asked).
 */
fun interface PasskeyHost {
    fun activityContext(): Context
}

/** Credential Manager passkeys need Android 9 (API 28) with Google Play services below Android 14. */
private const val PASSKEY_MIN_SDK = 28

/** The result of one Credential Manager call. */
sealed interface PasskeyOutcome {
    /** The response JSON the server verifies (`registrationResponseJson` or the assertion). */
    data class Success(val json: String) : PasskeyOutcome

    data class Failed(val failure: PasskeyFailure) : PasskeyOutcome
}

/**
 * A thin wrapper around AndroidX Credential Manager, so the flows can be tested with a fake. The
 * request JSON is the server's `options.publicKey`; the response JSON goes back to the server as it
 * is.
 */
interface PasskeyGateway {
    /** This device can do passkeys at all (Android 9+; the provider may still say no later). */
    fun isSupported(): Boolean

    suspend fun create(host: PasskeyHost, requestJson: String): PasskeyOutcome

    suspend fun get(host: PasskeyHost, requestJson: String): PasskeyOutcome
}

@Singleton
class CredentialManagerGateway @Inject constructor() : PasskeyGateway {

    override fun isSupported(): Boolean = Build.VERSION.SDK_INT >= PASSKEY_MIN_SDK

    override suspend fun create(host: PasskeyHost, requestJson: String): PasskeyOutcome {
        val context = host.activityContext()
        return try {
            val response =
                CredentialManager.create(context)
                    .createCredential(context, CreatePublicKeyCredentialRequest(requestJson))
            when (response) {
                is CreatePublicKeyCredentialResponse ->
                    PasskeyOutcome.Success(response.registrationResponseJson)
                else -> PasskeyOutcome.Failed(PasskeyFailure.Other(null))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CreateCredentialException) {
            PasskeyOutcome.Failed(e.toCreateFailure())
        } catch (e: Exception) {
            Timber.w(e, "Creating a passkey failed")
            PasskeyOutcome.Failed(PasskeyFailure.Other(null))
        }
    }

    override suspend fun get(host: PasskeyHost, requestJson: String): PasskeyOutcome {
        val context = host.activityContext()
        return try {
            val response =
                CredentialManager.create(context)
                    .getCredential(
                        context,
                        GetCredentialRequest(listOf(GetPublicKeyCredentialOption(requestJson))),
                    )
            val credential = response.credential
            if (credential is PublicKeyCredential) {
                PasskeyOutcome.Success(credential.authenticationResponseJson)
            } else {
                PasskeyOutcome.Failed(PasskeyFailure.Other(null))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialException) {
            PasskeyOutcome.Failed(e.toGetFailure())
        } catch (e: Exception) {
            Timber.w(e, "Getting a passkey failed")
            PasskeyOutcome.Failed(PasskeyFailure.Other(null))
        }
    }
}

/**
 * Credential Manager's registration errors in terms of the app. A WebAuthn `InvalidStateError` (the
 * authenticator already holds a credential from the exclude list) is "already registered".
 */
internal fun CreateCredentialException.toCreateFailure(): PasskeyFailure =
    when (this) {
        is CreateCredentialCancellationException -> PasskeyFailure.Cancelled
        is CreatePublicKeyCredentialDomException ->
            when (domError) {
                is InvalidStateError -> PasskeyFailure.AlreadyRegistered
                is NotAllowedError -> PasskeyFailure.Cancelled
                else -> PasskeyFailure.Other(null)
            }
        is CreateCredentialNoCreateOptionException,
        is CreateCredentialProviderConfigurationException,
        is CreateCredentialUnsupportedException -> PasskeyFailure.Unsupported
        else -> PasskeyFailure.Other(null)
    }

/** Credential Manager's sign-in errors in terms of the app. */
internal fun GetCredentialException.toGetFailure(): PasskeyFailure =
    when (this) {
        is GetCredentialCancellationException -> PasskeyFailure.Cancelled
        is NoCredentialException -> PasskeyFailure.NoCredential
        is GetPublicKeyCredentialDomException ->
            if (domError is NotAllowedError) PasskeyFailure.Cancelled
            else PasskeyFailure.Other(null)
        is GetCredentialProviderConfigurationException,
        is GetCredentialUnsupportedException -> PasskeyFailure.Unsupported
        else -> PasskeyFailure.Other(null)
    }
