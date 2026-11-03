package blue.anika.wolle.data.repository

import blue.anika.wolle.data.api.AdminApi
import blue.anika.wolle.data.api.ApiCapabilities
import blue.anika.wolle.data.api.dto.AdminUserDto
import blue.anika.wolle.data.api.dto.ApiErrorEnvelopeDto
import blue.anika.wolle.data.api.dto.AuditDetailDto
import blue.anika.wolle.data.api.dto.AuditPageDto
import blue.anika.wolle.data.api.dto.CreateAdminUserRequest
import blue.anika.wolle.data.api.dto.ResetPasswordRequest
import blue.anika.wolle.data.api.dto.SetUserActiveRequest
import blue.anika.wolle.data.settings.SettingsRepository
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import retrofit2.HttpException

/** Why an administrator request did not work, in terms the screens can explain. */
sealed interface AdminFailure {
    /** No connection or the server could not be reached. */
    data object Offline : AdminFailure

    /** 401/403: the token is not an administrator's (or not accepted for admin routes yet). */
    data object NotAllowed : AdminFailure

    /** The change would leave no active administrator. */
    data object LastAdmin : AdminFailure

    data object EmailInUse : AdminFailure

    data object InvalidEmail : AdminFailure

    /** Password shorter than 12 characters. */
    data object PasswordTooShort : AdminFailure

    data object PasswordTooCommon : AdminFailure

    data object PasswordTooLong : AdminFailure

    data object NotFound : AdminFailure

    data object Other : AdminFailure
}

class AdminException(val failure: AdminFailure, cause: Throwable? = null) :
    Exception(failure.toString(), cause)

/** Turns any thrown request error into an [AdminFailure]. Pure, so it is unit-tested. */
fun Throwable.toAdminFailure(json: Json = ADMIN_ERROR_JSON): AdminFailure {
    if (this is AdminException) return failure
    if (this is HttpException) {
        val code =
            runCatching {
                    json
                        .decodeFromString<ApiErrorEnvelopeDto>(
                            response()?.errorBody()?.string().orEmpty()
                        )
                        .error
                        ?.code
                }
                .getOrNull()
        return adminFailureFor(code(), code)
    }
    var current: Throwable? = this
    while (current != null) {
        if (current is IOException) return AdminFailure.Offline
        current = current.cause
    }
    return AdminFailure.Other
}

fun adminFailureFor(status: Int, code: String?): AdminFailure =
    when {
        code == "admin_conflict" -> AdminFailure.LastAdmin
        code == "email_in_use" -> AdminFailure.EmailInUse
        code == "invalid_email" -> AdminFailure.InvalidEmail
        code == "invalid_password" -> AdminFailure.PasswordTooShort
        code == "common_password" -> AdminFailure.PasswordTooCommon
        code == "password_too_long" -> AdminFailure.PasswordTooLong
        status == 401 || status == 403 -> AdminFailure.NotAllowed
        status == 404 -> AdminFailure.NotFound
        else -> AdminFailure.Other
    }

private val ADMIN_ERROR_JSON = Json { ignoreUnknownKeys = true }

/**
 * Administrator data (audit log and accounts). Online-only: nothing is cached in Room.
 *
 * Visibility of the Admin entry comes from the current user's `is_admin` in [ApiCapabilities] (one
 * `GET /api/v2/meta` per app start and token). When the server has no v2 meta (an older server),
 * [probeAdmin] falls back to asking the user list once: 200 means yes, 401/403 means no.
 */
@Singleton
class AdminRepository
internal constructor(
    private val api: AdminApi,
    private val capabilities: ApiCapabilities,
    private val tokenProvider: () -> String,
    private val savedEmail: () -> String?,
    private val saveEmail: (String) -> Unit,
) {
    @Inject
    constructor(
        api: AdminApi,
        capabilities: ApiCapabilities,
        settings: SettingsRepository,
    ) : this(
        api,
        capabilities,
        { settings.credentials.value.apiToken },
        { settings.accountEmail.value },
        { settings.setAccountEmail(it) },
    )

    private val _isAdmin = MutableStateFlow<Boolean?>(null)

    /** null until the first probe finished (or failed to reach the server). */
    val isAdmin: StateFlow<Boolean?> = _isAdmin.asStateFlow()

    private var probedToken: String? = null

    /** Email of the signed-in account: from the server's meta when known, else the saved one. */
    val currentEmail: String?
        get() = capabilities.current.user?.email ?: savedEmail()

    /** Server id of the signed-in account; only known when the server speaks v2. */
    val currentUserId: Long?
        get() = capabilities.current.user?.id

    suspend fun probeAdmin(): Boolean? {
        val token = tokenProvider()
        if (token.isBlank()) {
            probedToken = null
            _isAdmin.value = null
            return null
        }
        if (probedToken == token && _isAdmin.value != null) return _isAdmin.value
        val user = capabilities.resolve().user
        if (user != null) {
            if (user.email != savedEmail()) saveEmail(user.email)
            return remember(token, user.isAdmin)
        }
        return probeByUserList(token)
    }

    private suspend fun probeByUserList(token: String): Boolean? =
        try {
            api.listUsers(page = 1)
            remember(token, true)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            when (failure.toAdminFailure()) {
                AdminFailure.NotAllowed -> remember(token, false)
                // Offline or a server hiccup: stay hidden for now and ask again next time.
                else -> null
            }
        }

    private fun remember(token: String, admin: Boolean): Boolean {
        probedToken = token
        _isAdmin.value = admin
        return admin
    }

    suspend fun auditPage(page: Int, entity: String?, action: String?): AuditPageDto = call {
        api.listAudit(page, entity, action?.trim()?.ifBlank { null })
    }

    suspend fun auditDetail(id: Long): AuditDetailDto = call { api.getAuditEvent(id).data }

    suspend fun users(page: Int) = call {
        capabilities.resolve()
        api.listUsers(page)
    }

    suspend fun createUser(email: String, password: String, isAdmin: Boolean): AdminUserDto = call {
        api.createUser(CreateAdminUserRequest(email.trim(), password, isAdmin)).data
    }

    suspend fun setActive(id: Long, active: Boolean): AdminUserDto = call {
        api.setActive(id, SetUserActiveRequest(active)).data
    }

    suspend fun resetPassword(id: Long, newPassword: String) {
        call { api.resetPassword(id, ResetPasswordRequest(newPassword)) }
    }

    private suspend fun <T> call(block: suspend () -> T): T =
        try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Exception) {
            throw AdminException(failure.toAdminFailure(), failure)
        }
}
