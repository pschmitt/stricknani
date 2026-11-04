//! Shared browser-session helpers (session lookup, CSRF tokens, rich-text rendering).
//!
//! The server-rendered pages that used to live here were replaced by the SvelteKit app under
//! `/app/`; only the helpers still used by the API and the remaining form endpoints stay.
use crate::{auth, db::AppState};
use axum::{Router, http::HeaderMap};
use sha2::{Digest, Sha256};
use subtle::ConstantTimeEq;

#[derive(Clone)]
pub(crate) struct WebState {
    /// Constructed by the remaining form endpoints; nothing reads it any more.
    #[expect(dead_code)]
    pub(crate) api: Router,
    pub(crate) app: AppState,
}

pub(crate) struct Session {
    pub(crate) token: String,
}

/// Authenticate a browser session cookie and return its token.
pub(crate) async fn session(
    state: &WebState,
    headers: &HeaderMap,
) -> Result<Session, auth::AuthError> {
    auth::browser_user(&state.app.pool, headers, state.app.secure_cookies).await?;
    let token = auth::cookie(headers, auth::session_cookie_name(state.app.secure_cookies))
        .ok_or(auth::AuthError::Unauthorized)?
        .to_owned();
    Ok(Session { token })
}

pub(crate) fn csrf(token: &str) -> String {
    let mut hash = Sha256::new();
    hash.update(b"stricknani browser csrf v1\0");
    hash.update(token.as_bytes());
    hex::encode(hash.finalize())
}

pub(crate) fn valid_csrf(token: &str, supplied: &str) -> bool {
    bool::from(csrf(token).as_bytes().ct_eq(supplied.as_bytes()))
}

pub(crate) fn rich_text(source: &str) -> String {
    let mut html = String::new();
    pulldown_cmark::html::push_html(
        &mut html,
        pulldown_cmark::Parser::new_ext(
            source,
            pulldown_cmark::Options::ENABLE_TABLES | pulldown_cmark::Options::ENABLE_STRIKETHROUGH,
        ),
    );
    ammonia::clean(&html)
}
