//! Operator CLI entry point for deleting an account.
//!
//! It reuses the account service behind the app's "Delete account", so the
//! account, everything it owns, its media files and its audit history go the
//! same way, and the last active administrator stays protected.

use crate::{
    account_service::{self, AccountError, DeleteProof},
    db::AppState,
};

/// Deletes the account with `email` (active or disabled) and all it owns.
pub async fn delete_user(state: &AppState, email: &str) -> Result<(), &'static str> {
    let email = email.trim().to_lowercase();
    let user_id = sqlx::query_scalar::<_, i64>("SELECT id FROM users WHERE email = ?")
        .bind(&email)
        .fetch_optional(&state.pool)
        .await
        .map_err(|_| "cannot look up user")?
        .ok_or("unknown user")?;
    account_service::delete_account(state, user_id, DeleteProof::Operator, &email)
        .await
        .map_err(|error| match error {
            AccountError::LastAdministrator => "cannot delete the last administrator",
            _ => "cannot delete user",
        })
}
