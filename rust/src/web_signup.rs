//! Public self-service account creation.

use crate::{
    Language, auth,
    db::{self, AccountRole, AppState, DatabaseError},
};
use askama::Template;
use axum::{
    Form, Router,
    extract::{ConnectInfo, DefaultBodyLimit, Extension, State},
    http::{HeaderMap, HeaderValue, StatusCode, header},
    response::{Html, IntoResponse, Redirect, Response},
    routing::get,
};
use serde::Deserialize;
use std::net::SocketAddr;
use zeroize::Zeroizing;

const MAX_EMAIL_BYTES: usize = 320;
const MAX_PASSWORD_BYTES: usize = 1024;

/// Browser signup routes. The coordinator adds this router to the public app.
pub(crate) fn router(app: AppState) -> Router {
    Router::new()
        .route("/signup", get(signup_page).post(signup))
        .layer(DefaultBodyLimit::max(8 * 1024))
        .with_state(app)
}

async fn signup_page(State(app): State<AppState>, headers: HeaderMap) -> Response {
    if crate::web::session(
        &crate::web::WebState {
            api: Router::new(),
            app: app.clone(),
        },
        &headers,
    )
    .await
    .is_ok()
    {
        return Redirect::to("/projects").into_response();
    }

    let Some(csrf) = new_csrf() else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let language = crate::language_from_headers(&headers);
    let response = render(
        SignupPage::new(language, csrf.clone(), String::new(), None, None),
        StatusCode::OK,
    );
    with_csrf_cookie(response, &csrf, app.secure_cookies)
}

#[derive(Deserialize)]
struct SignupForm {
    email: String,
    password: String,
    csrf: String,
}

async fn signup(
    State(app): State<AppState>,
    headers: HeaderMap,
    peer: Option<Extension<ConnectInfo<SocketAddr>>>,
    Form(mut form): Form<SignupForm>,
) -> Response {
    let mut password = Zeroizing::new(std::mem::take(&mut form.password));
    if !crate::web::valid_unauth_csrf(&headers, &form.csrf, app.secure_cookies) {
        return StatusCode::FORBIDDEN.into_response();
    }

    let language = crate::language_from_headers(&headers);
    let tr = |english, german| crate::i18n::text(language, english, german);
    let email = form.email.trim().to_lowercase();
    let ip = peer
        .map(|Extension(ConnectInfo(address))| address.ip().to_string())
        .unwrap_or_else(|| "unknown".to_owned());
    let keys = [auth::rate_key("ip", &ip), auth::rate_key("email", &email)];
    if app.auth_limiter.reserve(&keys).is_none() {
        return render_error(
            &headers,
            &email,
            tr(
                "Too many signup attempts. Please try again later.",
                "Zu viele Registrierungsversuche. Bitte versuche es später erneut.",
            ),
            app.secure_cookies,
            StatusCode::TOO_MANY_REQUESTS,
        );
    }

    if email.is_empty()
        || email.len() > MAX_EMAIL_BYTES
        || !email.contains('@')
        || email.contains(char::is_whitespace)
        || password.len() > MAX_PASSWORD_BYTES
    {
        return render_error(
            &headers,
            &email,
            tr(
                "Please enter a valid email address and password.",
                "Bitte gib eine gültige E-Mail-Adresse und ein gültiges Passwort ein.",
            ),
            app.secure_cookies,
            StatusCode::UNPROCESSABLE_ENTITY,
        );
    }
    let policy_error = match auth::validate_new_password(&password) {
        Ok(()) => None,
        Err(auth::NewPasswordError::TooShort) => Some(tr(
            "Passwords must be at least 12 characters long.",
            "Passwörter müssen mindestens 12 Zeichen lang sein.",
        )),
        Err(auth::NewPasswordError::Common) => Some(tr(
            "This password is too common. Please choose a stronger one.",
            "Dieses Passwort ist zu häufig. Bitte wähle ein stärkeres Passwort.",
        )),
    };
    if let Some(message) = policy_error {
        return render_error(
            &headers,
            &email,
            message,
            app.secure_cookies,
            StatusCode::UNPROCESSABLE_ENTITY,
        );
    }

    let password = std::mem::take(&mut *password);
    match db::create_account(&app.pool, &email, password, AccountRole::Member).await {
        Ok(_) => {
            let response = render(
                SignupPage::new(
                    language,
                    String::new(),
                    String::new(),
                    None,
                    Some(tr(
                        "Your account was created. You can sign in now.",
                        "Dein Konto wurde erstellt. Du kannst dich jetzt anmelden.",
                    )),
                ),
                StatusCode::CREATED,
            );
            crate::web::with_cookie(
                response,
                crate::web::unauth_csrf_cookie_name(app.secure_cookies),
                "",
                0,
                app.secure_cookies,
            )
        }
        Err(DatabaseError::InvalidAccount) => render_error(
            &headers,
            &email,
            tr(
                "Please enter a valid email address and password.",
                "Bitte gib eine gültige E-Mail-Adresse und ein gültiges Passwort ein.",
            ),
            app.secure_cookies,
            StatusCode::UNPROCESSABLE_ENTITY,
        ),
        Err(DatabaseError::Account(error))
            if error
                .as_database_error()
                .is_some_and(sqlx::error::DatabaseError::is_unique_violation) =>
        {
            render_error(
                &headers,
                &email,
                tr(
                    "An account could not be created with those details.",
                    "Mit diesen Angaben konnte kein Konto erstellt werden.",
                ),
                app.secure_cookies,
                StatusCode::CONFLICT,
            )
        }
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

fn render_error(
    headers: &HeaderMap,
    email: &str,
    message: &'static str,
    secure: bool,
    status: StatusCode,
) -> Response {
    let Some(csrf) = new_csrf() else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    let response = render(
        SignupPage::new(
            crate::language_from_headers(headers),
            csrf.clone(),
            email.to_owned(),
            Some(message),
            None,
        ),
        status,
    );
    with_csrf_cookie(response, &csrf, secure)
}

fn new_csrf() -> Option<String> {
    use rand::RngCore;
    let mut bytes = [0_u8; 32];
    rand::rngs::OsRng.try_fill_bytes(&mut bytes).ok()?;
    Some(hex::encode(bytes))
}

fn with_csrf_cookie(response: Response, csrf: &str, secure: bool) -> Response {
    crate::web::with_cookie(
        response,
        crate::web::unauth_csrf_cookie_name(secure),
        csrf,
        600,
        secure,
    )
}

fn render(page: SignupPage, status: StatusCode) -> Response {
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

#[derive(Template)]
#[template(path = "signup.html")]
struct SignupPage {
    language: &'static str,
    title: &'static str,
    email_label: &'static str,
    password_label: &'static str,
    submit: &'static str,
    login: &'static str,
    password_help: &'static str,
    csrf: String,
    email: String,
    error: Option<&'static str>,
    success: Option<&'static str>,
}

impl SignupPage {
    fn new(
        language: Language,
        csrf: String,
        email: String,
        error: Option<&'static str>,
        success: Option<&'static str>,
    ) -> Self {
        let tr = |english, german| crate::i18n::text(language, english, german);
        Self {
            language: if language == Language::English {
                "en"
            } else {
                "de"
            },
            title: tr("Create an account", "Konto erstellen"),
            email_label: tr("Email", "E-Mail"),
            password_label: tr("Password", "Passwort"),
            submit: tr("Create account", "Konto erstellen"),
            login: tr("Sign in", "Anmelden"),
            password_help: tr(
                "Use at least 12 characters and avoid common passwords.",
                "Verwende mindestens 12 Zeichen und vermeide häufige Passwörter.",
            ),
            csrf,
            email,
            error,
            success,
        }
    }
}
