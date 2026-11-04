package blue.anika.wolle.data.account

import android.content.Context
import android.net.Uri
import blue.anika.wolle.data.api.dto.ApiErrorEnvelopeDto
import blue.anika.wolle.data.settings.SettingsRepository
import blue.anika.wolle.data.uploads.deleteCapturedPhoto
import blue.anika.wolle.ui.common.isOfflineFailure
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.HttpException

/** Largest avatar the server takes (25 MiB file cap, same as the browser). */
const val MAX_AVATAR_BYTES = 25 * 1024 * 1024

sealed interface TokenListResult {
    data class Loaded(val tokens: List<ApiTokenDto>) : TokenListResult

    /** An older server (404/405/401 on these routes): hide the account features, no error. */
    data object Unsupported : TokenListResult

    data class Failed(val offline: Boolean) : TokenListResult
}

sealed interface CreateTokenResult {
    data class Created(val token: CreatedTokenDto) : CreateTokenResult

    /** 401 `invalid_credentials` or a 422 on `current_password`. */
    data object WrongPassword : CreateTokenResult

    /** 403 `reauth_required`. */
    data object ReauthRequired : CreateTokenResult

    data object RateLimited : CreateTokenResult

    data object NameTooLong : CreateTokenResult

    data object SessionExpired : CreateTokenResult

    data class Failed(val offline: Boolean) : CreateTokenResult
}

sealed interface RevokeResult {
    /** [currentToken] true: the app revoked itself and must sign out locally. */
    data class Revoked(val currentToken: Boolean) : RevokeResult

    /** 404: the token is already gone, which is what the user wanted. */
    data object AlreadyGone : RevokeResult

    data class Failed(val offline: Boolean) : RevokeResult
}

sealed interface PasswordChangeResult {
    /** 204: every session and token of the account, this one included, is revoked. */
    data object Changed : PasswordChangeResult

    data object CurrentIncorrect : PasswordChangeResult

    /** The server refused the new password (common or too long; too short is checked locally). */
    data object NewRejected : PasswordChangeResult

    data object RateLimited : PasswordChangeResult

    data class Failed(val offline: Boolean) : PasswordChangeResult
}

sealed interface AvatarResult {
    data class Uploaded(val path: String) : AvatarResult

    data object Removed : AvatarResult

    data object TooLarge : AvatarResult

    data object UnsupportedImage : AvatarResult

    data class Failed(val offline: Boolean) : AvatarResult
}

sealed interface AccountProfileResult {
    data class Loaded(val account: AccountDto) : AccountProfileResult

    /** An older server without bearer account reads (401/404/405): hide the Gravatar toggle. */
    data object Unsupported : AccountProfileResult

    data class Failed(val offline: Boolean) : AccountProfileResult
}

/** What the account UI needs from the server; faked in tests. */
interface AccountGateway {
    /** `GET /account`; remembers the avatar path it reports so the bar shows it right away. */
    suspend fun loadProfile(): AccountProfileResult

    /** `PATCH /account {use_gravatar}`; remembers the resulting avatar path. */
    suspend fun setUseGravatar(enabled: Boolean): AccountProfileResult

    suspend fun loadTokens(): TokenListResult

    suspend fun createToken(name: String, currentPassword: String): CreateTokenResult

    suspend fun revokeToken(id: Long): RevokeResult

    suspend fun changePassword(currentPassword: String, newPassword: String): PasswordChangeResult

    /** [uri] is a picker `content://` URI. */
    suspend fun uploadAvatar(uri: String): AvatarResult

    suspend fun deleteAvatar(): AvatarResult
}

/** The status and v2 `error.code` of a failed call, or null for anything but an HTTP error. */
internal data class HttpFailure(
    val status: Int,
    val code: String?,
    val fields: Map<String, String> = emptyMap(),
)

private val errorJson = Json { ignoreUnknownKeys = true }

internal fun Throwable.httpFailure(): HttpFailure? {
    if (this !is HttpException) return null
    val body = runCatching { response()?.errorBody()?.string() }.getOrNull().orEmpty()
    val error =
        runCatching { errorJson.decodeFromString<ApiErrorEnvelopeDto>(body).error }.getOrNull()
    return HttpFailure(code(), error?.code, error?.fields.orEmpty())
}

/** A medium-style local date for an RFC 3339 timestamp; the raw text when it cannot be parsed. */
fun formatTokenDate(
    iso: String,
    locale: Locale = Locale.getDefault(),
    zone: ZoneId = ZoneId.systemDefault(),
): String =
    runCatching {
            DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(locale)
                .withZone(zone)
                .format(Instant.parse(iso))
        }
        .getOrDefault(iso)

@Singleton
class AccountRepository
internal constructor(
    private val api: AccountV2Api,
    private val saveAvatarPath: (String?) -> Unit,
    private val readUpload: suspend (String) -> AvatarUpload?,
) : AccountGateway {
    @Inject
    constructor(
        api: AccountV2Api,
        settings: SettingsRepository,
        @ApplicationContext context: Context,
    ) : this(
        api,
        { settings.setAccountAvatarUrl(it) },
        { uri -> readFromResolver(context, uri) },
    )

    override suspend fun loadProfile(): AccountProfileResult = profileCall { api.account().data }

    override suspend fun setUseGravatar(enabled: Boolean): AccountProfileResult = profileCall {
        api.updateAccount(UpdateAccountRequest(enabled)).data
    }

    private suspend fun profileCall(call: suspend () -> AccountDto): AccountProfileResult =
        try {
            val account = call()
            saveAvatarPath(account.avatarUrl)
            AccountProfileResult.Loaded(account)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val http = failure.httpFailure()
            when {
                http != null && http.status in UNSUPPORTED_STATUSES ->
                    AccountProfileResult.Unsupported
                else -> AccountProfileResult.Failed(failure.isOfflineFailure())
            }
        }

    override suspend fun loadTokens(): TokenListResult =
        try {
            TokenListResult.Loaded(api.tokens().data)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val http = failure.httpFailure()
            when {
                http != null && http.status in UNSUPPORTED_STATUSES -> TokenListResult.Unsupported
                else -> TokenListResult.Failed(failure.isOfflineFailure())
            }
        }

    override suspend fun createToken(name: String, currentPassword: String): CreateTokenResult =
        try {
            CreateTokenResult.Created(
                api.createToken(CreateTokenRequest(name.trim(), currentPassword)).data
            )
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val http = failure.httpFailure()
            when {
                http == null -> CreateTokenResult.Failed(failure.isOfflineFailure())
                http.status == 403 && http.code == "reauth_required" ->
                    CreateTokenResult.ReauthRequired
                http.status == 401 && http.code == "invalid_credentials" ->
                    CreateTokenResult.WrongPassword
                http.status == 401 -> CreateTokenResult.SessionExpired
                http.status == 429 -> CreateTokenResult.RateLimited
                http.status == 422 && http.code == "validation_error" ->
                    CreateTokenResult.NameTooLong
                else -> CreateTokenResult.Failed(offline = false)
            }
        }

    override suspend fun revokeToken(id: Long): RevokeResult =
        try {
            val response = api.revokeToken(id)
            when {
                response.isSuccessful ->
                    RevokeResult.Revoked(response.body()?.data?.currentToken == true)
                response.code() == 404 -> RevokeResult.AlreadyGone
                else -> RevokeResult.Failed(offline = false)
            }
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            RevokeResult.Failed(failure.isOfflineFailure())
        }

    override suspend fun changePassword(
        currentPassword: String,
        newPassword: String,
    ): PasswordChangeResult =
        try {
            api.changePassword(ChangePasswordRequest(currentPassword, newPassword))
            PasswordChangeResult.Changed
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val http = failure.httpFailure()
            when {
                http == null -> PasswordChangeResult.Failed(failure.isOfflineFailure())
                http.status == 429 -> PasswordChangeResult.RateLimited
                http.status == 422 -> http.passwordFieldFailure()
                else -> PasswordChangeResult.Failed(offline = false)
            }
        }

    /** A 422 names the field: `current_password` (wrong) or `new_password` (policy). */
    private fun HttpFailure.passwordFieldFailure(): PasswordChangeResult =
        when {
            "current_password" in fields -> PasswordChangeResult.CurrentIncorrect
            "new_password" in fields -> PasswordChangeResult.NewRejected
            else -> PasswordChangeResult.Failed(offline = false)
        }

    override suspend fun uploadAvatar(uri: String): AvatarResult =
        try {
            val upload = readUpload(uri) ?: return AvatarResult.Failed(offline = false)
            if (upload.bytes.size > MAX_AVATAR_BYTES) return AvatarResult.TooLarge
            val part =
                MultipartBody.Part.createFormData(
                    "file",
                    upload.fileName,
                    upload.bytes.toRequestBody(upload.contentType.toMediaTypeOrNull()),
                )
            val path = api.uploadAvatar(part).data.avatarUrl
            saveAvatarPath(path)
            AvatarResult.Uploaded(path)
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            val http = failure.httpFailure()
            when {
                http == null -> AvatarResult.Failed(failure.isOfflineFailure())
                http.status == 413 || http.code == "payload_too_large" -> AvatarResult.TooLarge
                http.code == "invalid_image" -> AvatarResult.UnsupportedImage
                else -> AvatarResult.Failed(offline = false)
            }
        }

    override suspend fun deleteAvatar(): AvatarResult =
        try {
            api.deleteAvatar()
            // Without an upload the server may fall back to the Gravatar.
            val fallback =
                try {
                    api.account().data.avatarUrl
                } catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    null
                }
            saveAvatarPath(fallback)
            AvatarResult.Removed
        } catch (failure: Exception) {
            if (failure is CancellationException) throw failure
            AvatarResult.Failed(failure.isOfflineFailure())
        }

    private companion object {
        /**
         * An older server has no bearer account routes: 404/405 (no route) or 401 (cookie only).
         */
        val UNSUPPORTED_STATUSES = setOf(401, 404, 405)
    }
}

/** A picked image read into memory. */
internal class AvatarUpload(val fileName: String, val contentType: String, val bytes: ByteArray)

private suspend fun readFromResolver(context: Context, uriString: String): AvatarUpload? =
    withContext(Dispatchers.IO) {
        val uri = Uri.parse(uriString)
        val resolver = context.contentResolver
        val type = resolver.getType(uri) ?: "image/jpeg"
        val bytes =
            try {
                resolver.openInputStream(uri)?.use { readAtMost(it, MAX_AVATAR_BYTES + 1) }
            } finally {
                // A camera temp file is now in memory (or unreadable); drop it either way.
                context.deleteCapturedPhoto(uri)
            } ?: return@withContext null
        AvatarUpload("avatar", type, bytes)
    }

/** Reads at most [limit] bytes, so an oversized pick is detected without loading all of it. */
private fun readAtMost(input: java.io.InputStream, limit: Int): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (out.size() < limit) {
        val read = input.read(buffer, 0, minOf(buffer.size, limit - out.size()))
        if (read < 0) break
        out.write(buffer, 0, read)
    }
    return out.toByteArray()
}
