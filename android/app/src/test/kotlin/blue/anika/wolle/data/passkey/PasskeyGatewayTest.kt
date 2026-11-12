package blue.anika.wolle.data.passkey

import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.CreateCredentialUnknownException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnknownException
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.domerrors.InvalidStateError
import androidx.credentials.exceptions.domerrors.NotAllowedError
import androidx.credentials.exceptions.publickeycredential.CreatePublicKeyCredentialDomException
import androidx.credentials.exceptions.publickeycredential.GetPublicKeyCredentialDomException
import org.junit.Assert.assertEquals
import org.junit.Test

/** What Credential Manager throws, as the app explains it. */
class PasskeyGatewayTest {
    @Test
    fun `dismissing the registration sheet is a cancel`() {
        assertEquals(
            PasskeyFailure.Cancelled,
            CreateCredentialCancellationException().toCreateFailure(),
        )
        assertEquals(
            PasskeyFailure.Cancelled,
            CreatePublicKeyCredentialDomException(NotAllowedError(), "denied").toCreateFailure(),
        )
    }

    @Test
    fun `a credential the device already holds is already registered`() {
        assertEquals(
            PasskeyFailure.AlreadyRegistered,
            CreatePublicKeyCredentialDomException(InvalidStateError(), "exists").toCreateFailure(),
        )
    }

    @Test
    fun `no provider to create the passkey is unsupported`() {
        assertEquals(
            PasskeyFailure.Unsupported,
            CreateCredentialNoCreateOptionException().toCreateFailure(),
        )
        assertEquals(
            PasskeyFailure.Unsupported,
            CreateCredentialProviderConfigurationException().toCreateFailure(),
        )
        assertEquals(
            PasskeyFailure.Other(null),
            CreateCredentialUnknownException().toCreateFailure(),
        )
    }

    @Test
    fun `dismissing the sign-in sheet is a cancel`() {
        assertEquals(PasskeyFailure.Cancelled, GetCredentialCancellationException().toGetFailure())
        assertEquals(
            PasskeyFailure.Cancelled,
            GetPublicKeyCredentialDomException(NotAllowedError(), "denied").toGetFailure(),
        )
    }

    @Test
    fun `no passkey on the phone for this server is its own failure`() {
        assertEquals(PasskeyFailure.NoCredential, NoCredentialException().toGetFailure())
    }

    @Test
    fun `no credential provider on the phone is unsupported`() {
        assertEquals(
            PasskeyFailure.Unsupported,
            GetCredentialProviderConfigurationException().toGetFailure(),
        )
        assertEquals(PasskeyFailure.Other(null), GetCredentialUnknownException().toGetFailure())
    }
}
