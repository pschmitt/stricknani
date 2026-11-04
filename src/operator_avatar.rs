//! Operator CLI entry points for setting and clearing a user's avatar.
//!
//! They reuse the account service used by the app's avatar upload and delete,
//! so validation, resizing, thumbnails and database updates stay identical.

use std::path::Path;

use sqlx::SqlitePool;

use crate::{account_service, media};

async fn active_user_id(pool: &SqlitePool, email: &str) -> Result<i64, &'static str> {
    sqlx::query_scalar::<_, i64>("SELECT id FROM users WHERE email = ? AND active = 1")
        .bind(email.trim().to_lowercase())
        .fetch_optional(pool)
        .await
        .map_err(|_| "cannot look up user")?
        .ok_or("unknown or disabled user")
}

/// Stores `bytes` as the avatar of the active user with `email`.
pub async fn set_avatar(
    pool: &SqlitePool,
    media_root: &Path,
    email: &str,
    bytes: Vec<u8>,
    original_filename: String,
) -> Result<(), &'static str> {
    let user_id = active_user_id(pool, email).await?;
    account_service::save_avatar(
        pool,
        media_root,
        user_id,
        media::AvatarUpload {
            bytes,
            original_filename,
        },
    )
    .await
    .map(|_| ())
    .map_err(|error| match error {
        account_service::AccountError::InvalidAvatar(_) => "invalid avatar image",
        _ => "cannot save avatar",
    })
}

/// Removes the avatar of the active user with `email`.
pub async fn clear_avatar(pool: &SqlitePool, email: &str) -> Result<(), &'static str> {
    let user_id = active_user_id(pool, email).await?;
    account_service::delete_avatar(pool, user_id)
        .await
        .map_err(|_| "cannot clear avatar")
}
