//! Browser account settings and administrative user management.
use crate::{Language, auth, db::AppState, media, web};
use askama::Template;
use axum::{
    Form, Router,
    extract::{DefaultBodyLimit, Multipart, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{Html, IntoResponse, Redirect, Response},
    routing::{get, post},
};
use serde::Deserialize;
use sqlx::{Row, SqlitePool};
use zeroize::Zeroizing;

const MAX_PASSWORD_BYTES: usize = 1024;
const MIN_PASSWORD_BYTES: usize = 12;
const MAX_TOKEN_NAME_BYTES: usize = 255;
const MAX_TOKENS_PER_HOUR: i64 = 5;
const MAX_AVATAR_REQUEST_BYTES: usize = 27 * 1024 * 1024;

pub(crate) fn router(api: Router, app: AppState) -> Router {
    let state = web::WebState { api, app };
    let account_routes = Router::new()
        .route("/account", get(account))
        .route("/account/api-tokens", post(create_token))
        .route("/account/api-tokens/{id}/revoke", post(revoke_token))
        .route("/account/password", post(change_password))
        .route("/account/avatar/delete", post(delete_avatar))
        .route("/admin/users", get(admin_users).post(create_user))
        .route("/admin/users/{id}/active", post(set_user_active))
        .route("/admin/users/{id}/reset-password", post(reset_password))
        .layer(DefaultBodyLimit::max(8 * 1024));
    let avatar_route = Router::new()
        .route("/account/avatar", post(upload_avatar))
        .layer(DefaultBodyLimit::max(MAX_AVATAR_REQUEST_BYTES));
    account_routes.merge(avatar_route).with_state(state)
}

async fn account(State(state): State<web::WebState>, headers: HeaderMap) -> Response {
    let session = match web::session(&state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    render_account(&state, &headers, &session, None, None, StatusCode::OK).await
}

async fn admin_users(State(state): State<web::WebState>, headers: HeaderMap) -> Response {
    let session = match web::session(&state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    match is_admin(&state.app.pool, session.user_id).await {
        Ok(true) => {}
        Ok(false) => return StatusCode::FORBIDDEN.into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
    render_admin(&state, &headers, &session, None, StatusCode::OK).await
}

#[derive(Deserialize)]
struct CsrfForm {
    csrf: String,
}

#[derive(Deserialize)]
struct CreateTokenForm {
    csrf: String,
    name: Option<String>,
}

async fn create_token(
    State(state): State<web::WebState>,
    headers: HeaderMap,
    Form(form): Form<CreateTokenForm>,
) -> Response {
    let session = match web::session(&state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    if !web::valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let name = form.name.unwrap_or_default().trim().to_owned();
    let name = if name.is_empty() {
        "API token".to_owned()
    } else {
        name
    };
    if name.len() > MAX_TOKEN_NAME_BYTES {
        return render_account(
            &state,
            &headers,
            &session,
            Some("Token names must be 255 characters or fewer."),
            None,
            StatusCode::UNPROCESSABLE_ENTITY,
        )
        .await;
    }
    let recent: i64 = match sqlx::query_scalar(
        "SELECT count(*) FROM api_tokens WHERE user_id = ? AND purpose = 'api' \
         AND created_at > unixepoch() - 3600",
    )
    .bind(session.user_id)
    .fetch_one(&state.app.pool)
    .await
    {
        Ok(count) => count,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    if recent >= MAX_TOKENS_PER_HOUR {
        return render_account(
            &state,
            &headers,
            &session,
            Some("Too many API tokens were created recently. Try again later."),
            None,
            StatusCode::TOO_MANY_REQUESTS,
        )
        .await;
    }
    let token = match auth::generate_token() {
        Ok(token) => token,
        Err(()) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let token_hash = auth::token_hash(token.as_bytes());
    if sqlx::query(
        "INSERT INTO api_tokens (user_id, name, token_hash, created_at) VALUES (?, ?, ?, unixepoch())",
    )
    .bind(session.user_id)
    .bind(name)
    .bind(token_hash)
    .execute(&state.app.pool)
    .await
    .is_err()
    {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    render_account(
        &state,
        &headers,
        &session,
        None,
        Some(token),
        StatusCode::OK,
    )
    .await
}

#[derive(Deserialize)]
struct RevokeTokenForm {
    csrf: String,
}

async fn revoke_token(
    State(state): State<web::WebState>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
    Form(form): Form<RevokeTokenForm>,
) -> Response {
    let session = match web::session(&state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    if !web::valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    match sqlx::query(
        "UPDATE api_tokens SET revoked_at = unixepoch() \
         WHERE id = ? AND user_id = ? AND purpose = 'api' AND revoked_at IS NULL",
    )
    .bind(id)
    .bind(session.user_id)
    .execute(&state.app.pool)
    .await
    {
        Ok(_) => Redirect::to("/account").into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

#[derive(Deserialize)]
struct ChangePasswordForm {
    csrf: String,
    current_password: String,
    new_password: String,
}

async fn change_password(
    State(state): State<web::WebState>,
    headers: HeaderMap,
    Form(mut form): Form<ChangePasswordForm>,
) -> Response {
    let current_password = Zeroizing::new(std::mem::take(&mut form.current_password));
    let new_password = Zeroizing::new(std::mem::take(&mut form.new_password));
    let session = match web::session(&state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    if !web::valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    if !password_length_valid(&current_password, 1)
        || !password_length_valid(&new_password, MIN_PASSWORD_BYTES)
    {
        return render_account(
            &state,
            &headers,
            &session,
            Some("The new password must contain at least 12 characters."),
            None,
            StatusCode::UNPROCESSABLE_ENTITY,
        )
        .await;
    }
    let old_hash = match sqlx::query_scalar::<_, String>(
        "SELECT password_hash FROM users WHERE id = ? AND active = 1",
    )
    .bind(session.user_id)
    .fetch_optional(&state.app.pool)
    .await
    {
        Ok(Some(hash)) => hash,
        Ok(None) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    match auth::verify_password_for_account(current_password, old_hash.clone()).await {
        Ok(true) => {}
        Ok(false) => {
            return render_account(
                &state,
                &headers,
                &session,
                Some("Current password is incorrect."),
                None,
                StatusCode::UNPROCESSABLE_ENTITY,
            )
            .await;
        }
        Err(()) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
    let new_hash = match auth::hash_password_for_account(new_password).await {
        Ok(hash) => hash,
        Err(()) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    match update_password_and_revoke_sessions(
        &state.app.pool,
        session.user_id,
        &new_hash,
        false,
        Some(&old_hash),
    )
    .await
    {
        Ok(true) => clear_browser_session(&state, Redirect::to("/login").into_response()),
        Ok(false) => StatusCode::CONFLICT.into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

async fn upload_avatar(
    State(state): State<web::WebState>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> Response {
    let session = match web::session(&state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let csrf = match media::read_avatar_csrf(&mut multipart).await {
        Ok(csrf) => csrf,
        Err(error) => return avatar_upload_error(&state, &headers, &session, error).await,
    };
    if !web::valid_csrf(&session.token, &csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let Some(root) = state.app.media_root.clone() else {
        return StatusCode::SERVICE_UNAVAILABLE.into_response();
    };
    let upload = match media::read_avatar_upload(multipart).await {
        Ok(upload) => upload,
        Err(error) => return avatar_upload_error(&state, &headers, &session, error).await,
    };
    let image = match media::prepare_avatar(upload.bytes).await {
        Ok(image) => image,
        Err(error) => return avatar_upload_error(&state, &headers, &session, error).await,
    };
    let filename = match media::save_avatar(&root, session.user_id, &image).await {
        Ok(filename) => filename,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let created_at = chrono::Utc::now().to_rfc3339_opts(chrono::SecondsFormat::Micros, true);
    let mut tx = match state.app.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => {
            media::cleanup_avatar(&root, session.user_id, &filename).await;
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    match sqlx::query(
        "INSERT INTO user_avatars \
         (user_id, filename, original_filename, content_type, width, height, created_at) \
         VALUES (?, ?, ?, ?, ?, ?, ?) \
         ON CONFLICT(user_id) DO UPDATE SET filename = excluded.filename, \
           original_filename = excluded.original_filename, content_type = excluded.content_type, \
           width = excluded.width, height = excluded.height, created_at = excluded.created_at",
    )
    .bind(session.user_id)
    .bind(&filename)
    .bind(upload.original_filename)
    .bind(image.content_type)
    .bind(i64::from(image.width))
    .bind(i64::from(image.height))
    .bind(&created_at)
    .execute(&mut *tx)
    .await
    {
        Ok(_) => {}
        Err(_) => {
            let _ = tx.rollback().await;
            media::cleanup_avatar(&root, session.user_id, &filename).await;
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    };
    let profile = sqlx::query("UPDATE users SET profile_image = ? WHERE id = ? AND active = 1")
        .bind(&filename)
        .bind(session.user_id)
        .execute(&mut *tx)
        .await;
    match profile {
        Ok(result) if result.rows_affected() == 1 => {}
        Ok(_) => {
            let _ = tx.rollback().await;
            media::cleanup_avatar(&root, session.user_id, &filename).await;
            return Redirect::to("/login").into_response();
        }
        Err(_) => {
            let _ = tx.rollback().await;
            media::cleanup_avatar(&root, session.user_id, &filename).await;
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    if tx.commit().await.is_err() {
        media::cleanup_avatar(&root, session.user_id, &filename).await;
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    Redirect::to("/account").into_response()
}

async fn avatar_upload_error(
    state: &web::WebState,
    headers: &HeaderMap,
    session: &web::Session,
    error: media::UploadError,
) -> Response {
    let language = crate::language_from_headers(headers);
    let (message, status) = match error {
        media::UploadError::TooLarge => (
            crate::i18n::text(
                language,
                "The image exceeds the upload size limit.",
                "Das Bild überschreitet die Upload-Größe.",
            ),
            StatusCode::PAYLOAD_TOO_LARGE,
        ),
        media::UploadError::InvalidImage | media::UploadError::BadMultipart => (
            crate::i18n::text(
                language,
                "Choose a supported image file and try again.",
                "Wähle ein unterstütztes Bildformat und versuche es erneut.",
            ),
            StatusCode::UNPROCESSABLE_ENTITY,
        ),
    };
    render_account(state, headers, session, Some(message), None, status).await
}

async fn delete_avatar(
    State(state): State<web::WebState>,
    headers: HeaderMap,
    Form(form): Form<CsrfForm>,
) -> Response {
    let session = match web::session(&state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    if !web::valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let mut tx = match state.app.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    if sqlx::query("DELETE FROM user_avatars WHERE user_id = ?")
        .bind(session.user_id)
        .execute(&mut *tx)
        .await
        .is_err()
    {
        let _ = tx.rollback().await;
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let profile = sqlx::query("UPDATE users SET profile_image = NULL WHERE id = ? AND active = 1")
        .bind(session.user_id)
        .execute(&mut *tx)
        .await;
    match profile {
        Ok(result) if result.rows_affected() == 1 => {}
        Ok(_) => {
            let _ = tx.rollback().await;
            return Redirect::to("/login").into_response();
        }
        Err(_) => {
            let _ = tx.rollback().await;
            return StatusCode::INTERNAL_SERVER_ERROR.into_response();
        }
    }
    if tx.commit().await.is_err() {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    Redirect::to("/account").into_response()
}

#[derive(Deserialize)]
struct CreateUserForm {
    csrf: String,
    email: String,
    password: String,
    is_admin: Option<String>,
}

async fn create_user(
    State(state): State<web::WebState>,
    headers: HeaderMap,
    Form(mut form): Form<CreateUserForm>,
) -> Response {
    let mut password = Zeroizing::new(std::mem::take(&mut form.password));
    let session = match require_admin(&state, &headers).await {
        Ok(session) => session,
        Err(error) => return error.into_response(),
    };
    if !web::valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let email = form.email.trim().to_lowercase();
    if !valid_email(&email) || !password_length_valid(&password, MIN_PASSWORD_BYTES) {
        return render_admin(
            &state,
            &headers,
            &session,
            Some("Enter a valid email and a password of at least 12 characters."),
            StatusCode::UNPROCESSABLE_ENTITY,
        )
        .await;
    }
    let is_admin = form.is_admin.as_deref() == Some("1");
    let user_id =
        match crate::db::create_user(&state.app.pool, &email, std::mem::take(&mut *password)).await
        {
            Ok(id) => id,
            Err(crate::db::DatabaseError::Account(_)) => {
                return render_admin(
                    &state,
                    &headers,
                    &session,
                    Some("An account with that email already exists."),
                    StatusCode::CONFLICT,
                )
                .await;
            }
            Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        };
    if is_admin
        && sqlx::query("UPDATE users SET is_admin = 1 WHERE id = ?")
            .bind(user_id)
            .execute(&state.app.pool)
            .await
            .is_err()
    {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    Redirect::to("/admin/users").into_response()
}

#[derive(Deserialize)]
struct SetActiveForm {
    csrf: String,
    active: String,
}

async fn set_user_active(
    State(state): State<web::WebState>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
    Form(form): Form<SetActiveForm>,
) -> Response {
    let session = match require_admin(&state, &headers).await {
        Ok(session) => session,
        Err(error) => return error.into_response(),
    };
    if !web::valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let Some(active) = (match form.active.as_str() {
        "0" => Some(false),
        "1" => Some(true),
        _ => None,
    }) else {
        return StatusCode::UNPROCESSABLE_ENTITY.into_response();
    };
    if id == session.user_id && !active {
        return StatusCode::CONFLICT.into_response();
    }
    let mut tx = match state.app.pool.begin().await {
        Ok(tx) => tx,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let actor = match sqlx::query(
        "UPDATE users SET active = active \
         WHERE id = ? AND active = 1 AND is_admin = 1",
    )
    .bind(session.user_id)
    .execute(&mut *tx)
    .await
    {
        Ok(result) => result,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    if actor.rows_affected() == 0 {
        return StatusCode::FORBIDDEN.into_response();
    }
    let target = match sqlx::query("SELECT active, is_admin FROM users WHERE id = ?")
        .bind(id)
        .fetch_optional(&mut *tx)
        .await
    {
        Ok(Some(row)) => (
            row.get::<i64, _>("active") != 0,
            row.get::<i64, _>("is_admin") != 0,
        ),
        Ok(None) => return StatusCode::NOT_FOUND.into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    if !active && target.0 && target.1 {
        let active_admins: i64 = match sqlx::query_scalar(
            "SELECT count(*) FROM users WHERE active = 1 AND is_admin = 1",
        )
        .fetch_one(&mut *tx)
        .await
        {
            Ok(count) => count,
            Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        };
        if active_admins <= 1 {
            return StatusCode::CONFLICT.into_response();
        }
    }
    if sqlx::query("UPDATE users SET active = ? WHERE id = ?")
        .bind(active)
        .bind(id)
        .execute(&mut *tx)
        .await
        .is_err()
    {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    if !active
        && sqlx::query(
            "UPDATE api_tokens SET revoked_at = unixepoch() \
             WHERE user_id = ? AND purpose = 'browser' AND revoked_at IS NULL",
        )
        .bind(id)
        .execute(&mut *tx)
        .await
        .is_err()
    {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    if tx.commit().await.is_err() {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    }
    Redirect::to("/admin/users").into_response()
}

#[derive(Deserialize)]
struct ResetPasswordForm {
    csrf: String,
    new_password: String,
}

async fn reset_password(
    State(state): State<web::WebState>,
    headers: HeaderMap,
    axum::extract::Path(id): axum::extract::Path<i64>,
    Form(mut form): Form<ResetPasswordForm>,
) -> Response {
    let password = Zeroizing::new(std::mem::take(&mut form.new_password));
    let session = match require_admin(&state, &headers).await {
        Ok(session) => session,
        Err(error) => return error.into_response(),
    };
    if !web::valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    if !password_length_valid(&password, MIN_PASSWORD_BYTES) {
        return render_admin(
            &state,
            &headers,
            &session,
            Some("The new password must contain at least 12 characters."),
            StatusCode::UNPROCESSABLE_ENTITY,
        )
        .await;
    }
    let hash = match auth::hash_password_for_account(password).await {
        Ok(hash) => hash,
        Err(()) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    match update_password_and_revoke_sessions(&state.app.pool, id, &hash, true, None).await {
        Ok(true) => Redirect::to("/admin/users").into_response(),
        Ok(false) => StatusCode::NOT_FOUND.into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

async fn update_password_and_revoke_sessions(
    pool: &SqlitePool,
    user_id: i64,
    password_hash: &str,
    reactivate: bool,
    expected_password_hash: Option<&str>,
) -> Result<bool, sqlx::Error> {
    let mut tx = pool.begin().await?;
    let result = if reactivate {
        sqlx::query("UPDATE users SET password_hash = ?, active = 1 WHERE id = ?")
            .bind(password_hash)
            .bind(user_id)
            .execute(&mut *tx)
            .await?
    } else if let Some(expected_hash) = expected_password_hash {
        sqlx::query(
            "UPDATE users SET password_hash = ? WHERE id = ? AND active = 1 AND password_hash = ?",
        )
        .bind(password_hash)
        .bind(user_id)
        .bind(expected_hash)
        .execute(&mut *tx)
        .await?
    } else {
        sqlx::query("UPDATE users SET password_hash = ? WHERE id = ? AND active = 1")
            .bind(password_hash)
            .bind(user_id)
            .execute(&mut *tx)
            .await?
    };
    if result.rows_affected() == 0 {
        return Ok(false);
    }
    sqlx::query(
        "UPDATE api_tokens SET revoked_at = unixepoch() \
         WHERE user_id = ? AND purpose = 'browser' AND revoked_at IS NULL",
    )
    .bind(user_id)
    .execute(&mut *tx)
    .await?;
    tx.commit().await?;
    Ok(true)
}

fn clear_browser_session(state: &web::WebState, mut response: Response) -> Response {
    let cookie = format!(
        "{}=; Path=/; HttpOnly; SameSite=Lax; Max-Age=0{}",
        auth::session_cookie_name(state.app.secure_cookies),
        if state.app.secure_cookies {
            "; Secure"
        } else {
            ""
        }
    );
    if let Ok(value) = HeaderValue::from_str(&cookie) {
        response.headers_mut().append(header::SET_COOKIE, value);
    }
    response
}

async fn require_admin(
    state: &web::WebState,
    headers: &HeaderMap,
) -> Result<web::Session, AccountAccessError> {
    let session = match web::session(state, headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Err(AccountAccessError::Unauthenticated),
        Err(_) => return Err(AccountAccessError::Internal),
    };
    match is_admin(&state.app.pool, session.user_id).await {
        Ok(true) => Ok(session),
        Ok(false) => Err(AccountAccessError::Forbidden),
        Err(_) => Err(AccountAccessError::Internal),
    }
}

enum AccountAccessError {
    Unauthenticated,
    Forbidden,
    Internal,
}

impl IntoResponse for AccountAccessError {
    fn into_response(self) -> Response {
        match self {
            Self::Unauthenticated => Redirect::to("/login").into_response(),
            Self::Forbidden => StatusCode::FORBIDDEN.into_response(),
            Self::Internal => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
        }
    }
}

async fn is_admin(pool: &SqlitePool, user_id: i64) -> Result<bool, sqlx::Error> {
    let admin =
        sqlx::query_scalar::<_, i64>("SELECT is_admin FROM users WHERE id = ? AND active = 1")
            .bind(user_id)
            .fetch_optional(pool)
            .await?;
    Ok(admin == Some(1))
}

fn password_length_valid(password: &str, minimum: usize) -> bool {
    (minimum..=MAX_PASSWORD_BYTES).contains(&password.len())
}

fn valid_email(email: &str) -> bool {
    email.len() <= 320
        && email.contains('@')
        && !email.chars().any(char::is_whitespace)
        && !email.starts_with('@')
        && !email.ends_with('@')
}

async fn render_account(
    state: &web::WebState,
    headers: &HeaderMap,
    session: &web::Session,
    error: Option<&str>,
    issued_token: Option<String>,
    status: StatusCode,
) -> Response {
    let rows = match sqlx::query(
        "SELECT id, name, created_at FROM api_tokens \
         WHERE user_id = ? AND purpose = 'api' AND revoked_at IS NULL ORDER BY id DESC",
    )
    .bind(session.user_id)
    .fetch_all(&state.app.pool)
    .await
    {
        Ok(rows) => rows,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let tokens = rows
        .into_iter()
        .map(|row| TokenRow {
            id: row.get("id"),
            name: row.get("name"),
            created_at: format_created(row.get("created_at")),
        })
        .collect();
    let is_admin = match is_admin(&state.app.pool, session.user_id).await {
        Ok(is_admin) => is_admin,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let avatar_filename = match sqlx::query_scalar::<_, String>(
        "SELECT filename FROM user_avatars WHERE user_id = ?",
    )
    .bind(session.user_id)
    .fetch_optional(&state.app.pool)
    .await
    {
        Ok(filename) => filename,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let avatar_url = avatar_filename.map(|filename| {
        format!(
            "/media/thumbnails/users/{}/{}",
            session.user_id,
            media::avatar_thumbnail_name(&filename)
        )
    });
    render(
        AccountPage::new(
            crate::language_from_headers(headers),
            session,
            web::csrf(&session.token),
            error.map(str::to_owned),
            None,
            tokens,
            Vec::new(),
            avatar_url,
            issued_token,
            is_admin,
            false,
        ),
        status,
    )
}

async fn render_admin(
    state: &web::WebState,
    headers: &HeaderMap,
    session: &web::Session,
    error: Option<&str>,
    status: StatusCode,
) -> Response {
    let rows = match sqlx::query(
        "SELECT id, email, active, is_admin FROM users ORDER BY email COLLATE NOCASE",
    )
    .fetch_all(&state.app.pool)
    .await
    {
        Ok(rows) => rows,
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let users = rows
        .into_iter()
        .map(|row| UserRow {
            id: row.get("id"),
            email: row.get("email"),
            active: row.get::<i64, _>("active") != 0,
            is_admin: row.get::<i64, _>("is_admin") != 0,
        })
        .collect();
    render(
        AccountPage::new(
            crate::language_from_headers(headers),
            session,
            web::csrf(&session.token),
            error.map(str::to_owned),
            None,
            Vec::new(),
            users,
            None,
            None,
            true,
            true,
        ),
        status,
    )
}

fn format_created(timestamp: i64) -> String {
    chrono::DateTime::from_timestamp(timestamp, 0)
        .map(|timestamp| timestamp.format("%Y-%m-%d %H:%M UTC").to_string())
        .unwrap_or_else(|| timestamp.to_string())
}

#[derive(Template)]
#[template(path = "accounts.html")]
struct AccountPage {
    language: &'static str,
    title: &'static str,
    navigation: &'static str,
    projects: &'static str,
    yarns: &'static str,
    account: &'static str,
    admin_users: &'static str,
    sign_out: &'static str,
    signed_in_as: &'static str,
    users_heading: &'static str,
    email_label: &'static str,
    status_label: &'static str,
    role_label: &'static str,
    actions_label: &'static str,
    active_label: &'static str,
    disabled_label: &'static str,
    administrator_label: &'static str,
    member_label: &'static str,
    disable: &'static str,
    enable: &'static str,
    new_password: &'static str,
    reset_password: &'static str,
    create_user: &'static str,
    create: &'static str,
    tokens_heading: &'static str,
    no_tokens: &'static str,
    name_label: &'static str,
    created_label: &'static str,
    revoke: &'static str,
    create_token: &'static str,
    change_password: &'static str,
    current_password: &'static str,
    token_created: &'static str,
    token_shown_once: &'static str,
    avatar_heading: &'static str,
    avatar_alt: &'static str,
    avatar_help: &'static str,
    avatar_file: &'static str,
    upload_avatar: &'static str,
    delete_avatar: &'static str,
    no_avatar: &'static str,
    email: String,
    csrf: String,
    message: Option<String>,
    error: Option<String>,
    tokens: Vec<TokenRow>,
    users: Vec<UserRow>,
    avatar_url: Option<String>,
    issued_token: Option<String>,
    is_admin: bool,
    admin_page: bool,
    current_user_id: i64,
}

impl AccountPage {
    #[allow(clippy::too_many_arguments)]
    fn new(
        language: Language,
        session: &web::Session,
        csrf: String,
        error: Option<String>,
        message: Option<String>,
        tokens: Vec<TokenRow>,
        users: Vec<UserRow>,
        avatar_url: Option<String>,
        issued_token: Option<String>,
        is_admin: bool,
        admin_page: bool,
    ) -> Self {
        let tr = |en, de| crate::i18n::text(language, en, de);
        Self {
            language: tr("en", "de"),
            title: tr("Account settings", "Kontoeinstellungen"),
            navigation: tr("Main navigation", "Hauptnavigation"),
            projects: tr("Projects", "Projekte"),
            yarns: tr("Yarns", "Garne"),
            account: tr("Account", "Konto"),
            admin_users: tr("Users", "Benutzerkonten"),
            sign_out: tr("Sign out", "Abmelden"),
            signed_in_as: tr("Signed in as", "Angemeldet als"),
            users_heading: tr("User accounts", "Benutzerkonten"),
            email_label: tr("Email", "E-Mail"),
            status_label: tr("Status", "Status"),
            role_label: tr("Role", "Rolle"),
            actions_label: tr("Actions", "Aktionen"),
            active_label: tr("Active", "Aktiv"),
            disabled_label: tr("Disabled", "Deaktiviert"),
            administrator_label: tr("Administrator", "Administrator"),
            member_label: tr("Member", "Mitglied"),
            disable: tr("Disable", "Deaktivieren"),
            enable: tr("Enable", "Aktivieren"),
            new_password: tr("New password", "Neues Passwort"),
            reset_password: tr("Reset password", "Passwort zurücksetzen"),
            create_user: tr("Create an account", "Konto erstellen"),
            create: tr("Create", "Erstellen"),
            tokens_heading: tr("API tokens", "API-Tokens"),
            no_tokens: tr(
                "You have no active API tokens.",
                "Du hast keine aktiven API-Tokens.",
            ),
            name_label: tr("Name", "Name"),
            created_label: tr("Created", "Erstellt"),
            revoke: tr("Revoke", "Widerrufen"),
            create_token: tr("Create API token", "API-Token erstellen"),
            change_password: tr("Change password", "Passwort ändern"),
            current_password: tr("Current password", "Aktuelles Passwort"),
            token_created: tr("Token created", "Token erstellt"),
            token_shown_once: tr(
                "Copy this token now. It will not be shown again.",
                "Kopiere dieses Token jetzt. Es wird nicht erneut angezeigt.",
            ),
            avatar_heading: tr("Profile image", "Profilbild"),
            avatar_alt: tr("Your profile image", "Dein Profilbild"),
            avatar_help: tr(
                "Choose a PNG, JPEG, WebP, GIF, BMP, or TIFF image up to 25 MB.",
                "Wähle ein PNG-, JPEG-, WebP-, GIF-, BMP- oder TIFF-Bild bis 25 MB.",
            ),
            avatar_file: tr("Image file", "Bilddatei"),
            upload_avatar: tr("Upload or replace image", "Bild hochladen oder ersetzen"),
            delete_avatar: tr("Delete profile image", "Profilbild löschen"),
            no_avatar: tr("No profile image set.", "Kein Profilbild festgelegt."),
            email: session.email.clone(),
            csrf,
            message,
            error,
            tokens,
            users,
            avatar_url,
            issued_token,
            is_admin,
            admin_page,
            current_user_id: session.user_id,
        }
    }
}

fn render(page: AccountPage, status: StatusCode) -> Response {
    match page.render() {
        Ok(html) => {
            let mut response = (status, Html(html)).into_response();
            response
                .headers_mut()
                .insert(header::CACHE_CONTROL, HeaderValue::from_static("no-store"));
            response
        }
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

struct TokenRow {
    id: i64,
    name: String,
    created_at: String,
}

struct UserRow {
    id: i64,
    email: String,
    active: bool,
    is_admin: bool,
}

#[cfg(test)]
mod tests {
    use super::update_password_and_revoke_sessions;
    use crate::db;

    #[tokio::test]
    async fn stale_verified_password_cannot_overwrite_a_newer_change() {
        let pool = db::connect("sqlite::memory:")
            .await
            .expect("test database should migrate");
        let user_id = db::create_user(
            &pool,
            "cas@example.test",
            "initial-account-password".to_owned(),
        )
        .await
        .expect("test account should be created");
        let old_hash: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
            .bind(user_id)
            .fetch_one(&pool)
            .await
            .unwrap();
        sqlx::query("UPDATE users SET password_hash = 'newer-admin-reset' WHERE id = ?")
            .bind(user_id)
            .execute(&pool)
            .await
            .unwrap();

        let changed = update_password_and_revoke_sessions(
            &pool,
            user_id,
            "stale-browser-change",
            false,
            Some(&old_hash),
        )
        .await
        .unwrap();
        assert!(!changed);
        let stored: String = sqlx::query_scalar("SELECT password_hash FROM users WHERE id = ?")
            .bind(user_id)
            .fetch_one(&pool)
            .await
            .unwrap();
        assert_eq!(stored, "newer-admin-reset");
    }
}
