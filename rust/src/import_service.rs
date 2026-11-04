//! Browser and Android import endpoints backed by the isolated document worker.

use std::{path::PathBuf, sync::Arc};

use askama::Template;
use axum::{
    Form, Json, Router,
    body::{Body, to_bytes},
    extract::{DefaultBodyLimit, Multipart, State, rejection::FormRejection},
    http::{HeaderMap, Method, Request, StatusCode, header},
    response::{Html, IntoResponse, Redirect, Response},
    routing::{get, post},
};
use serde::{Deserialize, Serialize};
use serde_json::{Value, json};
use tower::ServiceExt;

use crate::{
    Language,
    ai::{AiClient, AiImage, AiInput, enhance_or_fallback},
    auth,
    db::AppState,
    document_worker::{DocumentRequest, WorkerError, parse_isolated},
    import_document::{
        DocumentKind, ImportDocumentDraft, PageKind, ProjectImportDraft, YarnImportDraft,
    },
    imports::{self, FetchError},
    url_import::{ImportTarget, ravelry},
    web,
};

pub(crate) const MAX_UPLOAD_BYTES: usize = 5 * 1024 * 1024;
const MAX_FORM_BYTES: usize = 8 * 1024 * 1024;
const API_RESPONSE_BYTES: usize = 16 * 1024 * 1024;
const AI_MAX_SOURCE_CHARS: usize = 12_000;

/// Shared parser inputs used by the API and the existing browser import routes.
#[derive(Clone)]
pub(crate) struct ImportContext {
    pub(crate) worker_path: PathBuf,
    pub(crate) ai: Option<Arc<AiClient>>,
}

impl ImportContext {
    pub(crate) fn new(worker_path: PathBuf, ai: Option<Arc<AiClient>>) -> Self {
        Self { worker_path, ai }
    }
}

#[derive(Clone)]
struct ImportState {
    app: AppState,
    api: Router,
    worker_path: PathBuf,
    external_enabled: bool,
    ai: Option<Arc<AiClient>>,
}

/// Build the Android and browser import routes around the shared API router.
pub fn router(api: Router, app: AppState, worker_path: PathBuf, external_enabled: bool) -> Router {
    router_with_ai(api, app, worker_path, external_enabled, None)
}

/// Build import routes with an optional configured AI client. Callers still need
/// to opt in on each import request before any provider call is possible.
pub fn router_with_ai(
    api: Router,
    app: AppState,
    worker_path: PathBuf,
    external_enabled: bool,
    ai: Option<AiClient>,
) -> Router {
    router_with_shared_ai(api, app, worker_path, external_enabled, ai.map(Arc::new))
}

pub(crate) fn router_with_shared_ai(
    api: Router,
    app: AppState,
    worker_path: PathBuf,
    external_enabled: bool,
    ai: Option<Arc<AiClient>>,
) -> Router {
    let state = ImportState {
        app,
        api,
        worker_path,
        external_enabled,
        ai,
    };
    Router::new()
        .route(
            "/projects/import",
            post(android_import).layer(DefaultBodyLimit::max(8 * 1024)),
        )
        .route("/imports", get(import_page))
        .route(
            "/imports/preview",
            post(preview_form).layer(DefaultBodyLimit::max(MAX_FORM_BYTES)),
        )
        .route(
            "/imports/preview-file",
            post(preview_file).layer(DefaultBodyLimit::max(MAX_UPLOAD_BYTES + 64 * 1024)),
        )
        .route(
            "/imports/create-project",
            post(create_project).layer(DefaultBodyLimit::max(MAX_FORM_BYTES)),
        )
        .route(
            "/imports/create-yarn",
            post(create_yarn).layer(DefaultBodyLimit::max(MAX_FORM_BYTES)),
        )
        .with_state(state)
}

#[derive(Debug, Deserialize)]
struct AndroidImportRequest {
    #[serde(rename = "type", default = "default_import_type")]
    import_type: String,
    url: String,
    #[serde(default)]
    use_ai: bool,
}

fn default_import_type() -> String {
    "url".to_owned()
}

#[derive(Debug, Serialize)]
struct AndroidImportResponse {
    name: Option<String>,
    title: Option<String>,
    category: Option<String>,
    needles: Option<String>,
    stitch_sample: Option<String>,
    description: Option<String>,
    notes: Option<String>,
    other_materials: Option<String>,
    tags: Vec<String>,
    link: Option<String>,
    is_ai_enhanced: bool,
    ai_fallback: bool,
    ai_requested: bool,
    content_sha256: String,
    image_urls: Vec<String>,
    steps: Vec<AndroidImportStep>,
}

#[derive(Debug, Serialize)]
struct AndroidImportStep {
    step_number: usize,
    title: Option<String>,
    description: Option<String>,
    images: Vec<String>,
}

async fn android_import(
    State(state): State<ImportState>,
    headers: HeaderMap,
    form: Result<Form<AndroidImportRequest>, FormRejection>,
) -> Response {
    if let Err(error) = auth::bearer_user(&state.app.pool, &headers).await {
        return error.into_response();
    }
    let Form(form) = match form {
        Ok(form) => form,
        Err(_) => return api_error(StatusCode::BAD_REQUEST, "Invalid import form"),
    };
    if form.import_type != "url" {
        return api_error(
            StatusCode::BAD_REQUEST,
            "This endpoint accepts URL imports only",
        );
    }
    if form.url.trim().is_empty() {
        return api_error(StatusCode::BAD_REQUEST, "URL is required");
    }
    if !state.external_enabled {
        return api_error(
            StatusCode::SERVICE_UNAVAILABLE,
            "Remote URL imports are disabled",
        );
    }

    let ai_requested = form.use_ai;
    let (mut draft, source_text) = match parse_url_with_source(&state.worker_path, &form.url).await
    {
        Ok(parsed) => parsed,
        Err(error) => return import_error(error),
    };
    let enhanced = enhance_draft(
        state.ai.as_deref(),
        &mut draft,
        ai_requested,
        source_text,
        Vec::new(),
    )
    .await;
    let project = draft.project;
    Json(AndroidImportResponse {
        name: project.name.clone(),
        title: project.name,
        category: project.category,
        needles: project.needles,
        stitch_sample: project.stitch_sample,
        description: project.description,
        notes: None,
        other_materials: project.other_materials.or(project.yarn),
        tags: Vec::new(),
        link: project.link.or(draft.source_url),
        is_ai_enhanced: enhanced,
        ai_fallback: ai_requested && !enhanced,
        ai_requested,
        content_sha256: draft.sha256,
        image_urls: project.image_urls,
        steps: project
            .steps
            .into_iter()
            .map(|step| AndroidImportStep {
                step_number: step.step_number,
                title: step.title,
                description: step.description,
                images: step.images,
            })
            .collect(),
    })
    .into_response()
}

fn api_error(status: StatusCode, detail: &'static str) -> Response {
    (status, axum::Json(json!({ "detail": detail }))).into_response()
}

fn import_error(error: ImportFailure) -> Response {
    let (status, detail) = match error {
        ImportFailure::Fetch(FetchError::BodyTooLarge) => (
            StatusCode::PAYLOAD_TOO_LARGE,
            "Imported document is too large",
        ),
        ImportFailure::Fetch(FetchError::Timeout) => {
            (StatusCode::GATEWAY_TIMEOUT, "Remote import timed out")
        }
        ImportFailure::Fetch(FetchError::UnsafeAddress) => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "Remote URL is not allowed",
        ),
        ImportFailure::Fetch(FetchError::HttpStatus(_)) => (
            StatusCode::BAD_GATEWAY,
            "Remote server could not provide the document",
        ),
        ImportFailure::Fetch(_) => (
            StatusCode::BAD_GATEWAY,
            "Remote document could not be fetched",
        ),
        ImportFailure::Worker(WorkerError::RequestTooLarge) => (
            StatusCode::PAYLOAD_TOO_LARGE,
            "Imported document is too large",
        ),
        ImportFailure::Worker(WorkerError::UnsupportedDocument) => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "This document type is not supported",
        ),
        ImportFailure::Worker(WorkerError::InvalidDocument) => (
            StatusCode::UNPROCESSABLE_ENTITY,
            "The imported document could not be parsed",
        ),
        ImportFailure::Worker(_) => (
            StatusCode::SERVICE_UNAVAILABLE,
            "Document parser is unavailable",
        ),
    };
    api_error(status, detail)
}

#[derive(Debug)]
pub(crate) enum ImportFailure {
    Fetch(FetchError),
    Worker(WorkerError),
}

pub(crate) async fn parse_url_with_source(
    worker_path: &std::path::Path,
    input: &str,
) -> Result<(ImportDocumentDraft, String), ImportFailure> {
    import_url(worker_path, input, ImportTarget::Project).await
}

/// Fetch a page and turn it into a draft: safe fetch, structured extraction in the isolated
/// worker, then Ravelry enrichment when the page is a Ravelry library entry and a key pair is
/// configured. The second value is the page text for optional AI normalisation.
pub(crate) async fn import_url(
    worker_path: &std::path::Path,
    input: &str,
    target: ImportTarget,
) -> Result<(ImportDocumentDraft, String), ImportFailure> {
    let url = imports::normalize_input_url(input);
    let fetched = imports::fetch_url(&url)
        .await
        .map_err(ImportFailure::Fetch)?;
    let ravelry = ravelry::client_from_environment()
        .and_then(|client| ravelry::detect(&fetched.final_url).map(|kind| (client, kind)));
    let ravelry_id = ravelry.and_then(|(_, kind)| {
        let html =
            crate::url_import::charset::decode_html(&fetched.body, fetched.content_type.as_deref());
        ravelry::id_from_html(&html, kind)
    });
    let mut draft = parse_isolated(
        worker_path,
        DocumentRequest {
            bytes: fetched.body,
            content_type: fetched.content_type,
            filename: None,
            source_url: Some(fetched.final_url.to_string()),
            fetched_title: fetched.title,
            fetched_text: fetched.text,
        },
    )
    .await
    .map_err(ImportFailure::Worker)?;
    let mut source_text = take_source_text(&mut draft);
    if draft.kind == DocumentKind::Html {
        if let (Some((client, kind)), Some(id)) = (ravelry, ravelry_id) {
            match client.fetch(kind, id).await {
                Ok(json) => match kind {
                    ravelry::RavelryKind::Pattern => ravelry::apply_pattern(&mut draft, &json),
                    ravelry::RavelryKind::Yarn => ravelry::apply_yarn(&mut draft, &json),
                },
                Err(_) => draft.warnings.push("ravelry_api_failed".to_owned()),
            }
        }
    } else {
        crate::url_import::extract::enrich_from_text(&mut draft, &source_text);
    }
    shape_for_target(&mut draft, target);
    if source_text.is_empty() {
        source_text = draft.project.description.clone().unwrap_or_default();
    }
    Ok((draft, source_text))
}

/// Remove the internal text extras so they never reach a client; return the text for AI use.
fn take_source_text(draft: &mut ImportDocumentDraft) -> String {
    let page_text = match draft.project.extras.remove("page_text") {
        Some(serde_json::Value::String(value)) => value,
        _ => String::new(),
    };
    draft.project.extras.remove("structured_data");
    if !page_text.is_empty() {
        return page_text;
    }
    // Text and PDF documents keep their full text as a visible extra on purpose.
    match draft.project.extras.get("full_text") {
        Some(serde_json::Value::String(value)) => value.clone(),
        _ => String::new(),
    }
}

/// Reconcile the draft with what the user asked for and add warnings the UI can explain.
fn shape_for_target(draft: &mut ImportDocumentDraft, target: ImportTarget) {
    match target {
        ImportTarget::Yarn => {
            let yarn = draft.yarn.get_or_insert_with(Default::default);
            if draft.page_kind == PageKind::Pattern
                && yarn.length_meters.is_none()
                && yarn.fiber_content.is_none()
                && let Some(first) = draft.project.yarns.first()
            {
                yarn.name = first.name.clone().or_else(|| yarn.name.take());
                yarn.brand = first.brand.clone().or_else(|| yarn.brand.take());
                yarn.colorway = first.colorway.clone();
                yarn.weight_category = first.weight_category.clone();
                yarn.link = first.link.clone().or_else(|| yarn.link.take());
                draft.warnings.push("not_a_yarn_page".to_owned());
            } else if draft.page_kind == PageKind::Pattern {
                draft.warnings.push("looks_like_pattern_page".to_owned());
            }
        }
        ImportTarget::Project => {
            if draft.page_kind == PageKind::Yarn {
                draft.warnings.push("looks_like_yarn_page".to_owned());
            }
        }
    }
}

/// Whether the model may read the page itself, and why.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) enum BrowseState {
    /// Browsing is off or the AI was not requested: the page text is the only evidence.
    Off,
    /// The page text is fine; the model may still open the page, but numbers stay grounded in
    /// the text.
    Allowed { url: String },
    /// Too little text came back: the model reads the page and its answer is checked loosely.
    Thin { url: String },
    /// The server was refused (403, 429, challenge page): the model works from the URL alone.
    Blocked { url: String, error: FetchError },
}

/// A fetched and parsed link, plus how the AI step may read the page.
pub(crate) struct UrlImport {
    pub draft: ImportDocumentDraft,
    pub source_text: String,
    pub browse: BrowseState,
}

const CHALLENGE_MARKERS: [&str; 9] = [
    "just a moment",
    "attention required",
    "verify you are human",
    "verifying you are human",
    "checking your browser",
    "enable javascript and cookies",
    "access denied",
    "are you a robot",
    "captcha",
];

/// Bot-protection interstitials are short pages that say so.
fn looks_like_challenge(text: &str) -> bool {
    if text.chars().count() > 2_000 {
        return false;
    }
    let lower = text.to_lowercase();
    CHALLENGE_MARKERS
        .iter()
        .any(|marker| lower.contains(marker))
}

/// A draft with nothing but the link, for pages only the AI browser can read.
fn skeleton_draft(url: &str, target: ImportTarget) -> ImportDocumentDraft {
    ImportDocumentDraft {
        kind: DocumentKind::Html,
        sha256: hex::encode(<sha2::Sha256 as sha2::Digest>::digest(url.as_bytes())),
        source_url: Some(url.to_owned()),
        project: ProjectImportDraft {
            link: Some(url.to_owned()),
            ..Default::default()
        },
        image: None,
        yarn: (target == ImportTarget::Yarn).then(Default::default),
        page_kind: PageKind::Unknown,
        provenance: Default::default(),
        warnings: Vec::new(),
    }
}

/// Like [`import_url`], but when `browse` is set a refused or unreadable page is not an error:
/// the draft is left for the AI step to fill by reading the page in a browser.
pub(crate) async fn import_url_browsing(
    worker_path: &std::path::Path,
    input: &str,
    target: ImportTarget,
    browse: bool,
) -> Result<UrlImport, ImportFailure> {
    let url = imports::normalize_input_url(input);
    let result = import_url(worker_path, input, target).await;
    classify_import(result, url, target, browse)
}

/// Decide how the AI step may read the page, from the outcome of the server's own fetch.
fn classify_import(
    result: Result<(ImportDocumentDraft, String), ImportFailure>,
    url: String,
    target: ImportTarget,
    browse: bool,
) -> Result<UrlImport, ImportFailure> {
    match result {
        Ok((draft, source_text)) => {
            let state = if !browse {
                BrowseState::Off
            } else {
                let page_url = draft.source_url.clone().unwrap_or(url);
                if looks_like_challenge(&source_text) {
                    return Ok(UrlImport {
                        draft: skeleton_draft(&page_url, target),
                        source_text: String::new(),
                        browse: BrowseState::Blocked {
                            url: page_url,
                            error: FetchError::HttpStatus(403),
                        },
                    });
                }
                if source_text.chars().count() < crate::url_import::ai_merge::THIN_TEXT_CHARS {
                    BrowseState::Thin { url: page_url }
                } else {
                    BrowseState::Allowed { url: page_url }
                }
            };
            Ok(UrlImport {
                draft,
                source_text,
                browse: state,
            })
        }
        Err(ImportFailure::Fetch(error @ FetchError::HttpStatus(403 | 429 | 503))) if browse => {
            Ok(UrlImport {
                draft: skeleton_draft(&url, target),
                source_text: String::new(),
                browse: BrowseState::Blocked { url, error },
            })
        }
        Err(error) => Err(error),
    }
}

/// Optional AI pass for URL imports. Returns whether the model contributed.
pub(crate) async fn normalize_with_ai(
    ai: Option<&AiClient>,
    draft: &mut ImportDocumentDraft,
    requested: bool,
    source_text: &str,
    target: ImportTarget,
    browse: &BrowseState,
) -> bool {
    use crate::url_import::ai_merge::{self, BrowseContext, Grounding};
    let Some(client) = ai.filter(|_| requested) else {
        return false;
    };
    let browse = if client.browse_enabled() {
        browse
    } else {
        &BrowseState::Off
    };
    let missing = source_text.trim().is_empty();
    let (context, browsed_url) = match browse {
        BrowseState::Off => (None, None),
        BrowseState::Allowed { url } => (
            Some(BrowseContext {
                url,
                text_missing: false,
            }),
            None,
        ),
        BrowseState::Thin { url } | BrowseState::Blocked { url, .. } => (
            Some(BrowseContext {
                url,
                text_missing: missing,
            }),
            Some(url.as_str()),
        ),
    };
    if missing && context.is_none() {
        return false;
    }
    let (system, user) = ai_merge::build_messages_with(target, draft, source_text, context);
    let Ok(answer) = client.complete_json(&system, &user).await else {
        return false;
    };
    let grounding = browsed_url.map_or(Grounding::PageText, |page_url| Grounding::Browsed {
        page_url,
    });
    ai_merge::apply_with(draft, &answer, source_text, target, grounding) > 0
}

pub(crate) async fn enhance_draft(
    ai: Option<&AiClient>,
    draft: &mut ImportDocumentDraft,
    requested: bool,
    source_text: String,
    images: Vec<AiImage>,
) -> bool {
    if !requested {
        return false;
    }
    let source_text = source_text.chars().take(AI_MAX_SOURCE_CHARS).collect();
    let outcome = enhance_or_fallback(
        ai,
        AiInput {
            source_text,
            draft: draft.project.clone(),
            images,
        },
    )
    .await;
    draft.project = outcome.draft;
    outcome.enhanced
}

pub(crate) async fn parse_bytes(
    worker_path: &std::path::Path,
    bytes: Vec<u8>,
    content_type: Option<String>,
    filename: Option<String>,
    source_url: Option<String>,
) -> Result<ImportDocumentDraft, WorkerError> {
    let mut draft = parse_isolated(
        worker_path,
        DocumentRequest {
            bytes,
            content_type,
            filename,
            source_url,
            fetched_title: None,
            fetched_text: None,
        },
    )
    .await?;
    take_source_text(&mut draft);
    Ok(draft)
}

#[derive(Template)]
#[template(path = "import.html")]
struct ImportPage {
    labels: ImportLabels,
    csrf: String,
    has_error: bool,
    error: String,
    has_preview: bool,
    preview: ImportPreview,
}

#[derive(Default)]
struct ImportPreview {
    draft_json: String,
    sha256: String,
    is_ai_enhanced: bool,
    name: String,
    description: String,
    category: String,
    needles: String,
    stitch_sample: String,
    other_materials: String,
    yarn: String,
    link: String,
    step_count: usize,
    image_count: usize,
    has_yarn: bool,
    yarn_name: String,
    yarn_brand: String,
    yarn_colorway: String,
    yarn_weight: String,
    yarn_length: String,
    yarn_weight_category: String,
    yarn_fiber_content: String,
    yarn_link: String,
}

#[derive(Clone)]
struct ImportLabels {
    language: &'static str,
    title: &'static str,
    navigation: &'static str,
    projects: &'static str,
    yarns: &'static str,
    sign_out: &'static str,
    source_url: &'static str,
    text: &'static str,
    file: &'static str,
    ai_enhance: &'static str,
    preview: &'static str,
    draft: &'static str,
    create_project: &'static str,
    create_yarn: &'static str,
    content_hash: &'static str,
    category: &'static str,
    needles: &'static str,
    stitch_sample: &'static str,
    other_materials: &'static str,
    yarn: &'static str,
    description: &'static str,
    name: &'static str,
    link: &'static str,
    brand: &'static str,
    colorway: &'static str,
    weight: &'static str,
    length: &'static str,
    weight_category: &'static str,
    fiber_content: &'static str,
}

impl ImportLabels {
    fn new(language: Language) -> Self {
        let tr = |english, german| crate::i18n::text(language, english, german);
        Self {
            language: tr("en", "de"),
            title: tr("Import a pattern", "Anleitung importieren"),
            navigation: tr("Main navigation", "Hauptnavigation"),
            projects: tr("Projects", "Projekte"),
            yarns: tr("Yarns", "Garne"),
            sign_out: tr("Sign out", "Abmelden"),
            source_url: tr("Pattern URL", "Anleitungs-URL"),
            text: tr("Pattern text", "Anleitungstext"),
            file: tr("Pattern file", "Anleitungsdatei"),
            ai_enhance: tr("Improve this draft with AI", "Entwurf mit KI verbessern"),
            preview: tr("Preview import", "Importvorschau"),
            draft: tr("Review the extracted details", "Extrahierte Angaben prüfen"),
            create_project: tr("Create project", "Projekt erstellen"),
            create_yarn: tr("Create yarn", "Garn erstellen"),
            content_hash: tr("Content fingerprint", "Inhaltsfingerabdruck"),
            category: tr("Category", "Kategorie"),
            needles: tr("Needles", "Nadeln"),
            stitch_sample: tr("Stitch sample", "Maschenprobe"),
            other_materials: tr("Other materials", "Weitere Materialien"),
            yarn: tr("Yarn", "Garn"),
            description: tr("Description", "Beschreibung"),
            name: tr("Name", "Name"),
            link: tr("Link", "Link"),
            brand: tr("Brand", "Marke"),
            colorway: tr("Colorway", "Farbe"),
            weight: tr("Weight", "Gewicht"),
            length: tr("Length", "Länge"),
            weight_category: tr("Weight category", "Garnstärke"),
            fiber_content: tr("Fiber content", "Faserzusammensetzung"),
        }
    }
}

async fn import_page(State(state): State<ImportState>, headers: HeaderMap) -> Response {
    let session = match web::session(
        &web::WebState {
            api: state.api.clone(),
            app: state.app.clone(),
        },
        &headers,
    )
    .await
    {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    render_import_page(
        &headers,
        &session.token,
        ImportPreview::default(),
        None,
        false,
    )
}

#[derive(Debug, Deserialize)]
struct PreviewForm {
    csrf: String,
    #[serde(rename = "type")]
    source_type: String,
    #[serde(default)]
    url: String,
    #[serde(default)]
    text: String,
    #[serde(default)]
    use_ai: bool,
}

async fn preview_form(
    State(state): State<ImportState>,
    headers: HeaderMap,
    form: Result<Form<PreviewForm>, FormRejection>,
) -> Response {
    let web_state = web::WebState {
        api: state.api.clone(),
        app: state.app.clone(),
    };
    let session = match web::session(&web_state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let Form(form) = match form {
        Ok(form) => form,
        Err(_) => {
            return render_import_page(
                &headers,
                &session.token,
                ImportPreview::default(),
                Some("The submitted form is invalid."),
                false,
            );
        }
    };
    if !web::valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }

    let result = match form.source_type.as_str() {
        "url" if !state.external_enabled => {
            return render_import_page(
                &headers,
                &session.token,
                ImportPreview::default(),
                Some("Remote URL imports are disabled."),
                false,
            );
        }
        "url" => parse_url_with_source(&state.worker_path, &form.url).await,
        "text" if form.text.trim().is_empty() => {
            return render_import_page(
                &headers,
                &session.token,
                ImportPreview::default(),
                Some("Pattern text is required."),
                false,
            );
        }
        "text" if form.text.len() > MAX_UPLOAD_BYTES => {
            return render_import_page(
                &headers,
                &session.token,
                ImportPreview::default(),
                Some("The submitted document is too large."),
                false,
            );
        }
        "text" => {
            let source_text = form.text.clone();
            parse_bytes(
                &state.worker_path,
                form.text.into_bytes(),
                Some("text/plain".to_owned()),
                Some("pattern.txt".to_owned()),
                None,
            )
            .await
            .map(|draft| (draft, source_text))
            .map_err(ImportFailure::Worker)
        }
        _ => {
            return render_import_page(
                &headers,
                &session.token,
                ImportPreview::default(),
                Some("Choose URL or text import."),
                false,
            );
        }
    };
    match result {
        Ok((mut draft, source_text)) => {
            let is_ai_enhanced = enhance_draft(
                state.ai.as_deref(),
                &mut draft,
                form.use_ai,
                source_text,
                Vec::new(),
            )
            .await;
            render_import_page(
                &headers,
                &session.token,
                ImportPreview::from_draft(&draft, is_ai_enhanced),
                None,
                true,
            )
        }
        Err(_) => render_import_page(
            &headers,
            &session.token,
            ImportPreview::default(),
            Some("The imported document could not be fetched or parsed."),
            false,
        ),
    }
}

async fn preview_file(
    State(state): State<ImportState>,
    headers: HeaderMap,
    mut multipart: Multipart,
) -> Response {
    let web_state = web::WebState {
        api: state.api.clone(),
        app: state.app.clone(),
    };
    let session = match web::session(&web_state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let Ok(Some(field)) = multipart.next_field().await else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    if field.name() != Some("csrf") {
        return StatusCode::BAD_REQUEST.into_response();
    }
    let csrf = match field.text().await {
        Ok(csrf) => csrf,
        Err(_) => return StatusCode::BAD_REQUEST.into_response(),
    };
    if !web::valid_csrf(&session.token, &csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let mut file: Option<(String, String, Vec<u8>)> = None;
    let mut use_ai = false;
    let mut ai_field_seen = false;
    loop {
        let field = match multipart.next_field().await {
            Ok(Some(field)) => field,
            Ok(None) => break,
            Err(_) => return StatusCode::BAD_REQUEST.into_response(),
        };
        match field.name() {
            Some("use_ai") if !ai_field_seen => {
                ai_field_seen = true;
                use_ai = matches!(field.text().await.as_deref(), Ok("true" | "1" | "on"));
                continue;
            }
            Some("file") if file.is_none() => {}
            _ => return StatusCode::BAD_REQUEST.into_response(),
        }
        let filename = field.file_name().unwrap_or("upload").to_owned();
        let content_type = field
            .content_type()
            .map(str::to_owned)
            .unwrap_or_else(|| "application/octet-stream".to_owned());
        let bytes = match field.bytes().await {
            Ok(bytes) if bytes.len() <= MAX_UPLOAD_BYTES => bytes.to_vec(),
            Ok(_) => return StatusCode::PAYLOAD_TOO_LARGE.into_response(),
            Err(_) => return StatusCode::BAD_REQUEST.into_response(),
        };
        file = Some((filename, content_type, bytes));
    }
    let Some((filename, content_type, bytes)) = file else {
        return StatusCode::BAD_REQUEST.into_response();
    };
    let ai_image_bytes = use_ai.then(|| bytes.clone());
    match parse_bytes(
        &state.worker_path,
        bytes,
        Some(content_type),
        Some(filename),
        None,
    )
    .await
    {
        Ok(mut draft) => {
            let source_text = String::new();
            let ai_images = match (
                draft
                    .image
                    .as_ref()
                    .and_then(|facts| ai_media_type(&facts.format)),
                ai_image_bytes,
            ) {
                (Some(media_type), Some(bytes)) => {
                    vec![AiImage {
                        media_type: media_type.to_owned(),
                        bytes,
                    }]
                }
                _ => Vec::new(),
            };
            let is_ai_enhanced = enhance_draft(
                state.ai.as_deref(),
                &mut draft,
                use_ai,
                source_text,
                ai_images,
            )
            .await;
            render_import_page(
                &headers,
                &session.token,
                ImportPreview::from_draft(&draft, is_ai_enhanced),
                None,
                true,
            )
        }
        Err(_) => render_import_page(
            &headers,
            &session.token,
            ImportPreview::default(),
            Some("The uploaded document could not be parsed."),
            false,
        ),
    }
}

pub(crate) fn ai_media_type(image_format: &str) -> Option<&'static str> {
    match image_format {
        "jpeg" => Some("image/jpeg"),
        "png" => Some("image/png"),
        "webp" => Some("image/webp"),
        "gif" => Some("image/gif"),
        _ => None,
    }
}

#[derive(Debug, Deserialize)]
struct CreateProjectForm {
    csrf: String,
    draft_json: String,
    #[serde(default)]
    is_ai_enhanced: bool,
    name: String,
    #[serde(default)]
    description: String,
    #[serde(default)]
    category: String,
    #[serde(default)]
    needles: String,
    #[serde(default)]
    stitch_sample: String,
    #[serde(default)]
    other_materials: String,
    #[serde(default)]
    yarn: String,
    #[serde(default)]
    link: String,
}

async fn create_project(
    State(state): State<ImportState>,
    headers: HeaderMap,
    form: Result<Form<CreateProjectForm>, FormRejection>,
) -> Response {
    let web_state = web::WebState {
        api: state.api.clone(),
        app: state.app.clone(),
    };
    let session = match web::session(&web_state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let Form(form) = match form {
        Ok(form) => form,
        Err(_) => return StatusCode::BAD_REQUEST.into_response(),
    };
    if !web::valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let mut draft: ImportDocumentDraft = match serde_json::from_str(&form.draft_json) {
        Ok(draft) => draft,
        Err(_) => return StatusCode::BAD_REQUEST.into_response(),
    };
    let project = &mut draft.project;
    project.name = Some(form.name.trim().to_owned());
    project.description = optional(form.description);
    project.category = optional(form.category);
    project.needles = optional(form.needles);
    project.stitch_sample = optional(form.stitch_sample);
    project.other_materials = optional(form.other_materials);
    project.yarn = optional(form.yarn);
    project.link = optional(form.link).or_else(|| project.link.clone());
    let payload = project_payload(project, form.is_ai_enhanced);
    let response = match api_call(
        &state.api,
        &session.token,
        Method::POST,
        "/api/v1/projects",
        payload,
    )
    .await
    {
        Ok((status, value)) if status.is_success() => value,
        Ok((StatusCode::NOT_IMPLEMENTED, _)) => {
            return render_import_page(
                &headers,
                &session.token,
                ImportPreview::from_draft(&draft, form.is_ai_enhanced),
                Some("Image import is not available yet."),
                true,
            );
        }
        _ => {
            return render_import_page(
                &headers,
                &session.token,
                ImportPreview::from_draft(&draft, form.is_ai_enhanced),
                Some("The project could not be created."),
                true,
            );
        }
    };
    let Some(id) = response["id"].as_i64() else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    Redirect::to(&format!("/projects/{id}")).into_response()
}

#[derive(Debug, Deserialize)]
struct CreateYarnForm {
    csrf: String,
    draft_json: String,
    #[serde(default)]
    is_ai_enhanced: bool,
    yarn_name: String,
    #[serde(default)]
    yarn_brand: String,
    #[serde(default)]
    yarn_colorway: String,
    #[serde(default)]
    yarn_weight: String,
    #[serde(default)]
    yarn_length: String,
    #[serde(default)]
    yarn_weight_category: String,
    #[serde(default)]
    yarn_fiber_content: String,
    #[serde(default)]
    yarn_link: String,
}

async fn create_yarn(
    State(state): State<ImportState>,
    headers: HeaderMap,
    form: Result<Form<CreateYarnForm>, FormRejection>,
) -> Response {
    let web_state = web::WebState {
        api: state.api.clone(),
        app: state.app.clone(),
    };
    let session = match web::session(&web_state, &headers).await {
        Ok(session) => session,
        Err(auth::AuthError::Unauthorized) => return Redirect::to("/login").into_response(),
        Err(_) => return StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    };
    let Form(form) = match form {
        Ok(form) => form,
        Err(_) => return StatusCode::BAD_REQUEST.into_response(),
    };
    if !web::valid_csrf(&session.token, &form.csrf) {
        return StatusCode::FORBIDDEN.into_response();
    }
    let draft: ImportDocumentDraft = match serde_json::from_str(&form.draft_json) {
        Ok(draft) => draft,
        Err(_) => return StatusCode::BAD_REQUEST.into_response(),
    };
    let name = form.yarn_name.trim().to_owned();
    if name.is_empty() {
        return render_import_page(
            &headers,
            &session.token,
            ImportPreview::from_draft(&draft, form.is_ai_enhanced),
            Some("A yarn name is required."),
            true,
        );
    }
    let payload = json!({
        "name": name,
        "brand": optional(form.yarn_brand),
        "colorway": optional(form.yarn_colorway),
        "weight_grams": number_prefix(&form.yarn_weight),
        "length_meters": number_prefix(&form.yarn_length),
        "weight_category": optional(form.yarn_weight_category),
        "fiber_content": optional(form.yarn_fiber_content),
        "link": optional(form.yarn_link),
        "is_ai_enhanced": form.is_ai_enhanced
    });
    let response = match api_call(
        &state.api,
        &session.token,
        Method::POST,
        "/api/v1/yarns",
        payload,
    )
    .await
    {
        Ok((status, value)) if status.is_success() => value,
        _ => {
            return render_import_page(
                &headers,
                &session.token,
                ImportPreview::from_draft(&draft, form.is_ai_enhanced),
                Some("The yarn could not be created."),
                true,
            );
        }
    };
    let Some(id) = response["id"].as_i64() else {
        return StatusCode::INTERNAL_SERVER_ERROR.into_response();
    };
    Redirect::to(&format!("/yarns/{id}")).into_response()
}

fn project_payload(project: &ProjectImportDraft, is_ai_enhanced: bool) -> Value {
    json!({
        "name": project.name.as_deref().filter(|name| !name.trim().is_empty()).unwrap_or("Imported project"),
        "category": project.category,
        "needles": project.needles,
        "stitch_sample": project.stitch_sample,
        "description": project.description,
        "notes": null,
        "other_materials": project.other_materials.as_deref().or(project.yarn.as_deref()),
        "tags": [],
        "link": project.link,
        "is_ai_enhanced": is_ai_enhanced,
        "yarn_ids": [],
        "steps": project.steps.iter().map(|step| json!({
            "title": step.title.as_deref().filter(|title| !title.trim().is_empty()).unwrap_or("Step"),
            "description": step.description,
            "step_number": step.step_number,
            "image_urls": []
        })).collect::<Vec<_>>(),
        "image_urls": []
    })
}

async fn api_call(
    api: &Router,
    token: &str,
    method: Method,
    path: &str,
    payload: Value,
) -> Result<(StatusCode, Value), StatusCode> {
    let body = serde_json::to_vec(&payload).map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let request = Request::builder()
        .method(method)
        .uri(path)
        .header(header::AUTHORIZATION, format!("Bearer {token}"))
        .header(header::CONTENT_TYPE, "application/json")
        .body(Body::from(body))
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let response = match api.clone().oneshot(request).await {
        Ok(response) => response,
        Err(never) => match never {},
    };
    let status = response.status();
    let bytes = to_bytes(response.into_body(), API_RESPONSE_BYTES)
        .await
        .map_err(|_| StatusCode::INTERNAL_SERVER_ERROR)?;
    let value = serde_json::from_slice(&bytes).unwrap_or(Value::Null);
    Ok((status, value))
}

fn render_import_page(
    headers: &HeaderMap,
    token: &str,
    preview: ImportPreview,
    error: Option<&str>,
    has_preview: bool,
) -> Response {
    let labels = ImportLabels::new(crate::language_from_headers(headers));
    let page = ImportPage {
        labels,
        csrf: web::csrf(token),
        has_error: error.is_some(),
        error: error.unwrap_or_default().to_owned(),
        has_preview,
        preview,
    };
    match page.render() {
        Ok(html) => Html(html).into_response(),
        Err(_) => StatusCode::INTERNAL_SERVER_ERROR.into_response(),
    }
}

impl ImportPreview {
    fn from_draft(draft: &ImportDocumentDraft, is_ai_enhanced: bool) -> Self {
        let yarn = draft.project.yarns.first().cloned().unwrap_or_else(|| {
            draft
                .project
                .yarn
                .as_deref()
                .map(|name| YarnImportDraft {
                    name: Some(name.to_owned()),
                    ..YarnImportDraft::default()
                })
                .unwrap_or_default()
        });
        Self {
            draft_json: serde_json::to_string(draft).unwrap_or_default(),
            sha256: draft.sha256.clone(),
            is_ai_enhanced,
            name: draft.project.name.clone().unwrap_or_default(),
            description: draft.project.description.clone().unwrap_or_default(),
            category: draft.project.category.clone().unwrap_or_default(),
            needles: draft.project.needles.clone().unwrap_or_default(),
            stitch_sample: draft.project.stitch_sample.clone().unwrap_or_default(),
            other_materials: draft.project.other_materials.clone().unwrap_or_default(),
            yarn: draft.project.yarn.clone().unwrap_or_default(),
            link: draft
                .project
                .link
                .clone()
                .or_else(|| draft.source_url.clone())
                .unwrap_or_default(),
            step_count: draft.project.steps.len(),
            image_count: draft.project.image_urls.len(),
            has_yarn: yarn
                .name
                .as_deref()
                .is_some_and(|name| !name.trim().is_empty()),
            yarn_name: yarn.name.unwrap_or_default(),
            yarn_brand: yarn.brand.unwrap_or_default(),
            yarn_colorway: yarn.colorway.unwrap_or_default(),
            yarn_weight: yarn.weight.unwrap_or_default(),
            yarn_length: yarn.length.unwrap_or_default(),
            yarn_weight_category: yarn.weight_category.unwrap_or_default(),
            yarn_fiber_content: yarn.fiber_content.unwrap_or_default(),
            yarn_link: yarn.link.unwrap_or_default(),
        }
    }
}

fn optional(value: String) -> Option<String> {
    let value = value.trim().to_owned();
    (!value.is_empty()).then_some(value)
}

fn number_prefix(value: &str) -> Option<i64> {
    let digits: String = value
        .trim()
        .chars()
        .take_while(char::is_ascii_digit)
        .collect();
    digits.parse().ok()
}

#[cfg(test)]
mod browse_tests {
    use std::collections::HashMap;

    use serde_json::{Value, json};
    use tokio::io::{AsyncReadExt, AsyncWriteExt};

    use super::*;
    use crate::import_document::FieldSource;

    /// Serve one OpenAI-compatible reply over plain HTTP and hand back the request body.
    async fn mock_ai(reply: Value) -> (AiClient, tokio::task::JoinHandle<Value>) {
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let port = listener.local_addr().unwrap().port();
        let server = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            let mut request = Vec::new();
            let mut buffer = [0_u8; 8192];
            let body_start = loop {
                let read = socket.read(&mut buffer).await.unwrap();
                request.extend_from_slice(&buffer[..read]);
                if let Some(index) = request.windows(4).position(|w| w == b"\r\n\r\n") {
                    let head = String::from_utf8_lossy(&request[..index]).to_ascii_lowercase();
                    let length: usize = head
                        .lines()
                        .find_map(|l| {
                            l.strip_prefix("content-length:")
                                .map(|v| v.trim().parse().unwrap())
                        })
                        .unwrap_or(0);
                    if request.len() >= index + 4 + length {
                        break index + 4;
                    }
                }
            };
            let content =
                json!({"choices": [{"message": {"content": reply.to_string()}}]}).to_string();
            socket
                .write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{content}",
                        content.len()
                    )
                    .as_bytes(),
                )
                .await
                .unwrap();
            serde_json::from_slice(&request[body_start..]).unwrap()
        });
        let base = format!("http://127.0.0.1:{port}/v1");
        let values = HashMap::from([
            ("AI_PROVIDER", "custom"),
            ("AI_BASE_URL", base.as_str()),
            ("AI_API_KEY", "k"),
            ("AI_BROWSE", "true"),
        ]);
        let config =
            crate::ai::AiConfig::from_lookup(true, |key| values.get(key).map(|v| (*v).to_owned()))
                .unwrap()
                .unwrap();
        (AiClient::new(config).unwrap(), server)
    }

    fn fetched(text: &str) -> Result<(ImportDocumentDraft, String), ImportFailure> {
        let mut draft = skeleton_draft("https://shop.example/p/1", ImportTarget::Yarn);
        draft
            .provenance
            .insert("yarn.name".into(), FieldSource::Structured);
        Ok((draft, text.to_owned()))
    }

    #[test]
    fn refused_fetches_become_browse_only_when_browsing_is_on() {
        let url = "https://shop.example/p/1".to_owned();
        for status in [403, 429, 503] {
            let imported = classify_import(
                Err(ImportFailure::Fetch(FetchError::HttpStatus(status))),
                url.clone(),
                ImportTarget::Yarn,
                true,
            )
            .unwrap();
            assert!(matches!(imported.browse, BrowseState::Blocked { .. }));
            assert!(imported.source_text.is_empty());
            assert_eq!(imported.draft.source_url.as_deref(), Some(url.as_str()));
            assert!(imported.draft.yarn.is_some());
        }
        // Without browsing, and for errors browsing cannot fix, nothing changes.
        for (status, browse) in [(403, false), (404, true), (401, true)] {
            assert!(
                classify_import(
                    Err(ImportFailure::Fetch(FetchError::HttpStatus(status))),
                    url.clone(),
                    ImportTarget::Yarn,
                    browse,
                )
                .is_err()
            );
        }
        assert!(
            classify_import(
                Err(ImportFailure::Fetch(FetchError::UnsafeAddress)),
                url,
                ImportTarget::Yarn,
                true,
            )
            .is_err()
        );
    }

    #[test]
    fn thin_text_and_challenge_pages_are_detected() {
        let url = "https://shop.example/p/1".to_owned();
        let state = |text: &str, browse| {
            classify_import(fetched(text), url.clone(), ImportTarget::Yarn, browse)
                .unwrap()
                .browse
        };
        assert_eq!(state("short", false), BrowseState::Off);
        assert!(matches!(state("short", true), BrowseState::Thin { .. }));
        assert!(matches!(
            state("Just a moment... Verify you are human", true),
            BrowseState::Blocked { .. }
        ));
        assert!(matches!(
            state(&"a real product page ".repeat(60), true),
            BrowseState::Allowed { .. }
        ));
        // A long page that merely mentions a captcha is not a challenge page.
        assert!(matches!(
            state(&format!("captcha {}", "yarn ".repeat(600)), true),
            BrowseState::Allowed { .. }
        ));
    }

    #[tokio::test]
    async fn blocked_page_is_read_by_the_ai_from_the_url_alone() {
        let imported = classify_import(
            Err(ImportFailure::Fetch(FetchError::HttpStatus(403))),
            "https://shop.example/p/1".to_owned(),
            ImportTarget::Yarn,
            true,
        )
        .unwrap();
        let mut draft = imported.draft;
        let (client, server) = mock_ai(json!({
            "pageKind": "yarn",
            "yarn": {"name": "Rios", "brand": "Malabrigo", "weightGrams": 100, "lengthMeters": 192,
                     "fiberContent": "100% Merino", "link": "https://evil.example"},
            "imageUrls": [
                "https://shop.example/img/1.jpg",
                "https://cdn.shopify.com/s/files/2.jpg",
                "https://evil.example/3.jpg",
                "http://shop.example/4.jpg",
                "https://127.0.0.1/5.jpg"
            ]
        }))
        .await;
        assert!(client.browse_enabled());
        let ok = normalize_with_ai(
            Some(&client),
            &mut draft,
            true,
            "",
            ImportTarget::Yarn,
            &imported.browse,
        )
        .await;
        assert!(ok);

        let request = server.await.unwrap();
        let system = request["messages"][0]["content"].as_str().unwrap();
        let user = request["messages"][1]["content"].as_str().unwrap();
        assert!(user.contains("Page URL: https://shop.example/p/1"));
        assert!(!user.contains("<<<PAGE"), "no page text is sent");
        assert!(user.contains("No page text is available"));
        assert!(system.contains("browser tools"));
        assert!(system.contains("never instructions"));
        assert!(system.contains("only the JSON object"));

        let yarn = draft.yarn.as_ref().unwrap();
        assert_eq!(yarn.name.as_deref(), Some("Rios"));
        // Quantities cannot be checked against page text; they are kept and flagged as AI.
        assert_eq!(yarn.weight_grams, Some(100));
        assert_eq!(yarn.length_meters, Some(192));
        assert_eq!(draft.provenance["yarn.weightGrams"], FieldSource::Ai);
        assert_eq!(draft.provenance["yarn.fiberContent"], FieldSource::Ai);
        assert!(draft.warnings.iter().any(|w| w == "ai_browsed"));
        // Model-supplied links stay ignored; only same-site and CDN images pass.
        assert_eq!(yarn.link, None);
        assert_eq!(
            yarn.image_urls,
            vec![
                "https://shop.example/img/1.jpg".to_owned(),
                "https://cdn.shopify.com/s/files/2.jpg".to_owned()
            ]
        );
        assert_eq!(draft.provenance["yarn.imageUrls"], FieldSource::Ai);
    }

    #[tokio::test]
    async fn readable_page_keeps_numbers_grounded_in_its_text() {
        let text = "Rios by Malabrigo. 100% Merino. ".repeat(20);
        let imported = classify_import(
            fetched(&text),
            "https://shop.example/p/1".to_owned(),
            ImportTarget::Yarn,
            true,
        )
        .unwrap();
        assert!(matches!(imported.browse, BrowseState::Allowed { .. }));
        let mut draft = imported.draft;
        let (client, server) = mock_ai(json!({
            "yarn": {"colorway": "Frost", "weightGrams": 999, "fiberContent": "50% Silk"},
            "imageUrls": ["https://shop.example/img/1.jpg"]
        }))
        .await;
        assert!(
            normalize_with_ai(
                Some(&client),
                &mut draft,
                true,
                &text,
                ImportTarget::Yarn,
                &imported.browse,
            )
            .await
        );
        let user = server.await.unwrap()["messages"][1]["content"]
            .as_str()
            .unwrap()
            .to_owned();
        assert!(user.contains("Page URL: https://shop.example/p/1"));
        assert!(user.contains("<<<PAGE"));
        let yarn = draft.yarn.unwrap();
        assert_eq!(yarn.colorway.as_deref(), Some("Frost"));
        assert_eq!(yarn.weight_grams, None, "ungrounded number rejected");
        assert_eq!(yarn.fiber_content, None, "ungrounded number rejected");
        assert!(yarn.image_urls.is_empty(), "images only in browsed mode");
        assert!(!draft.warnings.iter().any(|w| w == "ai_browsed"));
    }

    #[tokio::test]
    async fn failed_ai_call_leaves_nothing_contributed() {
        // Nothing listens on port 1.
        let values = HashMap::from([
            ("AI_PROVIDER", "custom"),
            ("AI_BASE_URL", "http://127.0.0.1:1/v1"),
            ("AI_API_KEY", "k"),
            ("AI_BROWSE", "1"),
        ]);
        let config =
            crate::ai::AiConfig::from_lookup(true, |key| values.get(key).map(|v| (*v).to_owned()))
                .unwrap()
                .unwrap();
        let client = AiClient::new(config).unwrap();
        let mut draft = skeleton_draft("https://shop.example/p/1", ImportTarget::Yarn);
        let browse = BrowseState::Blocked {
            url: "https://shop.example/p/1".into(),
            error: FetchError::HttpStatus(403),
        };
        assert!(
            !normalize_with_ai(
                Some(&client),
                &mut draft,
                true,
                "",
                ImportTarget::Yarn,
                &browse
            )
            .await
        );
    }
}
