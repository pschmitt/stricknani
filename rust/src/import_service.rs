//! Browser and Android import endpoints backed by the isolated document worker.

use std::path::PathBuf;

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
    Language, auth,
    db::AppState,
    document_worker::{DocumentRequest, WorkerError, parse_isolated},
    import_document::{ImportDocumentDraft, ProjectImportDraft, YarnImportDraft},
    imports::{self, FetchError},
    web,
};

const MAX_UPLOAD_BYTES: usize = 5 * 1024 * 1024;
const MAX_FORM_BYTES: usize = 8 * 1024 * 1024;
const API_RESPONSE_BYTES: usize = 16 * 1024 * 1024;

#[derive(Clone)]
struct ImportState {
    app: AppState,
    api: Router,
    worker_path: PathBuf,
    external_enabled: bool,
}

/// Build the Android and browser import routes around the shared API router.
pub fn router(api: Router, app: AppState, worker_path: PathBuf, external_enabled: bool) -> Router {
    let state = ImportState {
        app,
        api,
        worker_path,
        external_enabled,
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
    let draft = match parse_url(&state, &form.url).await {
        Ok(draft) => draft,
        Err(error) => return import_error(error),
    };
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
        is_ai_enhanced: false,
        ai_fallback: false,
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
enum ImportFailure {
    Fetch(FetchError),
    Worker(WorkerError),
}

async fn parse_url(state: &ImportState, input: &str) -> Result<ImportDocumentDraft, ImportFailure> {
    let fetched = imports::fetch_url(input)
        .await
        .map_err(ImportFailure::Fetch)?;
    parse_isolated(
        &state.worker_path,
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
    .map_err(ImportFailure::Worker)
}

async fn parse_bytes(
    state: &ImportState,
    bytes: Vec<u8>,
    content_type: Option<String>,
    filename: Option<String>,
    source_url: Option<String>,
) -> Result<ImportDocumentDraft, WorkerError> {
    parse_isolated(
        &state.worker_path,
        DocumentRequest {
            bytes,
            content_type,
            filename,
            source_url,
            fetched_title: None,
            fetched_text: None,
        },
    )
    .await
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
        "url" => parse_url(&state, &form.url).await,
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
        "text" => parse_bytes(
            &state,
            form.text.into_bytes(),
            Some("text/plain".to_owned()),
            Some("pattern.txt".to_owned()),
            None,
        )
        .await
        .map_err(ImportFailure::Worker),
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
        Ok(draft) => render_import_page(
            &headers,
            &session.token,
            ImportPreview::from_draft(&draft),
            None,
            true,
        ),
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
    loop {
        let field = match multipart.next_field().await {
            Ok(Some(field)) => field,
            Ok(None) => break,
            Err(_) => return StatusCode::BAD_REQUEST.into_response(),
        };
        if field.name() != Some("file") || file.is_some() {
            return StatusCode::BAD_REQUEST.into_response();
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
    match parse_bytes(&state, bytes, Some(content_type), Some(filename), None).await {
        Ok(draft) => render_import_page(
            &headers,
            &session.token,
            ImportPreview::from_draft(&draft),
            None,
            true,
        ),
        Err(_) => render_import_page(
            &headers,
            &session.token,
            ImportPreview::default(),
            Some("The uploaded document could not be parsed."),
            false,
        ),
    }
}

#[derive(Debug, Deserialize)]
struct CreateProjectForm {
    csrf: String,
    draft_json: String,
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
    let payload = project_payload(project);
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
                ImportPreview::from_draft(&draft),
                Some("Image import is not available yet."),
                true,
            );
        }
        _ => {
            return render_import_page(
                &headers,
                &session.token,
                ImportPreview::from_draft(&draft),
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
            ImportPreview::from_draft(&draft),
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
        "is_ai_enhanced": false
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
                ImportPreview::from_draft(&draft),
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

fn project_payload(project: &ProjectImportDraft) -> Value {
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
        "is_ai_enhanced": false,
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
    fn from_draft(draft: &ImportDocumentDraft) -> Self {
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
