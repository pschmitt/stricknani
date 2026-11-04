//! Optional, bounded AI enhancement for an already parsed project import draft.
//!
//! The caller must enable AI explicitly. This module never fetches source URLs or
//! image URLs: it sends only the supplied text, draft, and explicitly supplied
//! image bytes to the configured provider. Provider responses are untrusted and
//! image references are retained only when they already occur in the draft.

use std::{fmt, future::Future, pin::Pin, sync::Arc, time::Duration};

use base64::{Engine as _, engine::general_purpose::STANDARD};
use reqwest::{Client, header};
use serde::Deserialize;
use serde_json::{Value, json};
use thiserror::Error;
use url::{Host, Url};
use zeroize::Zeroizing;

use crate::import_document::{ProjectImportDraft, StepImportDraft, YarnImportDraft};

const MAX_TEXT_CHARS: usize = 12_000;
const MAX_IMAGE_BYTES: usize = 2_000_000;
const MAX_IMAGES: usize = 3;
const MAX_RESPONSE_BYTES: usize = 1024 * 1024;
const REQUEST_TIMEOUT: Duration = Duration::from_secs(60);
const MAX_DRAFT_BYTES: usize = 128 * 1024;
const MAX_NORMALIZE_CHARS: usize = 40_000;

/// A supported OpenAI-compatible chat-completions provider.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum AiProvider {
    OpenAi,
    OpenRouter,
    Groq,
    /// Any other OpenAI-compatible endpoint, such as a local gateway or an agent server. Needs
    /// `AI_BASE_URL`; the model defaults to `hermes-agent` unless `AI_MODEL` is set.
    Custom,
}

impl AiProvider {
    fn parse(value: &str) -> Result<Self, AiError> {
        match value.trim().to_ascii_lowercase().as_str() {
            "openai" => Ok(Self::OpenAi),
            "openrouter" => Ok(Self::OpenRouter),
            "groq" => Ok(Self::Groq),
            "custom" | "openai-compatible" | "hermes" => Ok(Self::Custom),
            _ => Err(AiError::InvalidProvider),
        }
    }

    fn default_base_url(self) -> &'static str {
        match self {
            Self::OpenAi => "https://api.openai.com/v1",
            Self::OpenRouter => "https://openrouter.ai/api/v1",
            Self::Groq => "https://api.groq.com/openai/v1",
            Self::Custom => "",
        }
    }

    fn default_model(self) -> &'static str {
        match self {
            Self::OpenAi => "gpt-4o-mini",
            Self::OpenRouter => "openai/gpt-4o-mini",
            Self::Groq => "llama-3.3-70b-versatile",
            Self::Custom => "hermes-agent",
        }
    }

    fn key_variable(self) -> &'static str {
        match self {
            Self::OpenAi => "OPENAI_API_KEY",
            Self::OpenRouter => "OPENROUTER_API_KEY",
            Self::Groq => "GROQ_API_KEY",
            Self::Custom => "AI_API_KEY",
        }
    }
}

/// Secret provider credential. Its debug representation is always redacted.
pub struct ApiKey(Zeroizing<String>);

impl ApiKey {
    fn new(value: String) -> Self {
        Self(Zeroizing::new(value))
    }

    fn as_str(&self) -> &str {
        self.0.as_str()
    }
}

impl fmt::Debug for ApiKey {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("ApiKey([REDACTED])")
    }
}

/// Validated provider settings. Use [`AiConfig::from_lookup`] so disabled AI
/// never reads a key from the environment.
pub struct AiConfig {
    provider: AiProvider,
    endpoint: Url,
    model: String,
    api_key: ApiKey,
    timeout: Duration,
}

impl fmt::Debug for AiConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("AiConfig")
            .field("provider", &self.provider)
            .field("endpoint", &self.endpoint)
            .field("model", &self.model)
            .field("api_key", &self.api_key)
            .finish()
    }
}

impl AiConfig {
    /// Read provider settings from `lookup`; when disabled, no key variable is
    /// read and `Ok(None)` is returned.
    pub fn from_lookup(
        enabled: bool,
        mut lookup: impl FnMut(&str) -> Option<String>,
    ) -> Result<Option<Self>, AiError> {
        if !enabled {
            return Ok(None);
        }

        let provider =
            AiProvider::parse(&lookup("AI_PROVIDER").unwrap_or_else(|| "openai".into()))?;
        let api_key = lookup("AI_API_KEY")
            .map(|value| value.trim().to_owned())
            .filter(|value| !value.is_empty())
            .or_else(|| {
                // A secret file path, for example a sops-nix secret.
                lookup("AI_API_KEY_FILE")
                    .filter(|path| !path.trim().is_empty())
                    .and_then(|path| std::fs::read_to_string(path.trim()).ok())
                    .map(|value| value.trim().to_owned())
                    .filter(|value| !value.is_empty())
            })
            .or_else(|| {
                lookup(provider.key_variable())
                    .map(|value| value.trim().to_owned())
                    .filter(|value| !value.is_empty())
            })
            .or_else(|| {
                lookup("OPENAI_API_KEY")
                    .map(|value| value.trim().to_owned())
                    .filter(|value| !value.is_empty())
            })
            .ok_or(AiError::MissingApiKey)?;

        let model = lookup("AI_MODEL")
            .filter(|value| !value.trim().is_empty())
            .unwrap_or_else(|| provider.default_model().to_owned());
        let model = model.trim().to_owned();
        if model.len() > 128 || model.chars().any(char::is_control) {
            return Err(AiError::InvalidConfiguration);
        }

        let base_url = lookup("AI_BASE_URL").unwrap_or_else(|| provider.default_base_url().into());
        let endpoint = chat_endpoint(&base_url)?;
        let timeout = lookup("AI_TIMEOUT_SECONDS")
            .and_then(|value| value.trim().parse::<u64>().ok())
            .map_or(REQUEST_TIMEOUT, |seconds| {
                Duration::from_secs(seconds.clamp(5, 300))
            });
        Ok(Some(Self {
            provider,
            endpoint,
            model,
            api_key: ApiKey::new(api_key),
            timeout,
        }))
    }

    /// Resolve settings from process environment, without reading credentials
    /// when the feature is disabled.
    pub fn from_environment(enabled: bool) -> Result<Option<Self>, AiError> {
        Self::from_lookup(enabled, |name| std::env::var(name).ok())
    }

    /// Provider selected by the validated configuration.
    pub fn provider(&self) -> AiProvider {
        self.provider
    }

    /// Model selected by `AI_MODEL` or the provider default.
    pub fn model(&self) -> &str {
        &self.model
    }
}

fn chat_endpoint(base: &str) -> Result<Url, AiError> {
    let mut url = Url::parse(base).map_err(|_| AiError::InvalidConfiguration)?;
    let authority = base
        .split_once("://")
        .map(|(_, remainder)| remainder.split(['/', '?', '#']).next().unwrap_or_default())
        .ok_or(AiError::InvalidConfiguration)?;
    if !matches!(url.scheme(), "https" | "http")
        || url.host().is_none()
        || authority.contains('@')
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return Err(AiError::InvalidConfiguration);
    }
    if url.scheme() != "https" && !is_loopback_host(url.host()) {
        return Err(AiError::InvalidConfiguration);
    }
    let path = format!("{}/chat/completions", url.path().trim_end_matches('/'));
    url.set_path(&path);
    Ok(url)
}

fn is_loopback_host(host: Option<Host<&str>>) -> bool {
    match host {
        Some(Host::Domain(domain)) => domain.eq_ignore_ascii_case("localhost"),
        Some(Host::Ipv4(address)) => address.is_loopback(),
        Some(Host::Ipv6(address)) => address.is_loopback(),
        None => false,
    }
}

/// An image that the user explicitly chose to send for AI analysis.
#[derive(Clone, Debug)]
pub struct AiImage {
    pub media_type: String,
    pub bytes: Vec<u8>,
}

/// Text and already parsed facts for a single AI enhancement request.
#[derive(Clone, Debug)]
pub struct AiInput {
    pub source_text: String,
    pub draft: ProjectImportDraft,
    pub images: Vec<AiImage>,
}

/// Caller-facing result: `enhanced` is true only after a valid provider result.
#[derive(Clone, Debug)]
pub struct AiOutcome {
    pub draft: ProjectImportDraft,
    pub enhanced: bool,
    pub failure: Option<AiError>,
}

/// Redacted failures suitable for logging or mapping to a caller fallback.
#[derive(Clone, Copy, Debug, Error, Eq, PartialEq)]
pub enum AiError {
    #[error("AI provider configuration is invalid")]
    InvalidConfiguration,
    #[error("AI provider is unsupported")]
    InvalidProvider,
    #[error("AI provider API key is not configured")]
    MissingApiKey,
    #[error("AI input exceeds a configured limit")]
    InputTooLarge,
    #[error("AI image type is not supported")]
    UnsupportedImageType,
    #[error("AI request could not be sent")]
    Transport,
    #[error("AI request timed out")]
    Timeout,
    #[error("AI provider returned HTTP status {0}")]
    HttpStatus(u16),
    #[error("AI provider response exceeds the configured limit")]
    ResponseTooLarge,
    #[error("AI provider response is invalid")]
    InvalidResponse,
    #[error("AI provider returned invalid JSON content")]
    InvalidJson,
}

type PostFuture<'a> = Pin<Box<dyn Future<Output = Result<Value, AiError>> + Send + 'a>>;

/// Injectable transport boundary for offline tests and provider clients.
pub trait AiTransport: Send + Sync {
    fn post<'a>(
        &'a self,
        endpoint: &'a Url,
        api_key: &'a ApiKey,
        body: &'a Value,
    ) -> PostFuture<'a>;
}

/// Reqwest transport that does not inherit proxies or follow redirects.
pub struct ReqwestAiTransport {
    client: Client,
}

impl ReqwestAiTransport {
    pub fn new() -> Result<Self, AiError> {
        Self::with_timeout(REQUEST_TIMEOUT)
    }

    pub fn with_timeout(timeout: Duration) -> Result<Self, AiError> {
        Self::build(timeout, false)
    }

    /// Like [`Self::with_timeout`]; a plain-HTTP (loopback) endpoint never negotiates TLS, so it
    /// does not require system CA certificates, which the Nix build sandbox lacks.
    pub fn for_endpoint(endpoint: &Url, timeout: Duration) -> Result<Self, AiError> {
        Self::build(timeout, endpoint.scheme() == "http")
    }

    fn build(timeout: Duration, plain_http: bool) -> Result<Self, AiError> {
        let mut builder = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(timeout)
            .connect_timeout(Duration::from_secs(10));
        if plain_http {
            builder = builder.tls_certs_only(Vec::new());
        }
        let client = builder.build().map_err(|_| AiError::InvalidConfiguration)?;
        Ok(Self { client })
    }
}

impl AiTransport for ReqwestAiTransport {
    fn post<'a>(
        &'a self,
        endpoint: &'a Url,
        api_key: &'a ApiKey,
        body: &'a Value,
    ) -> PostFuture<'a> {
        Box::pin(async move {
            let authorization_value = Zeroizing::new(format!("Bearer {}", api_key.as_str()));
            let mut authorization = header::HeaderValue::from_str(authorization_value.as_str())
                .map_err(|_| AiError::InvalidConfiguration)?;
            authorization.set_sensitive(true);
            let response = self
                .client
                .post(endpoint.clone())
                .header(header::AUTHORIZATION, authorization)
                .header(header::CONTENT_TYPE, "application/json")
                .body(serde_json::to_vec(body).map_err(|_| AiError::InvalidConfiguration)?)
                .send()
                .await
                .map_err(|error| {
                    if error.is_timeout() {
                        AiError::Timeout
                    } else {
                        AiError::Transport
                    }
                })?;

            let status = response.status();
            if !status.is_success() {
                return Err(AiError::HttpStatus(status.as_u16()));
            }
            let mut response = response;
            let mut bytes = Vec::new();
            while let Some(chunk) = response.chunk().await.map_err(|error| {
                if error.is_timeout() {
                    AiError::Timeout
                } else {
                    AiError::Transport
                }
            })? {
                if bytes.len().saturating_add(chunk.len()) > MAX_RESPONSE_BYTES {
                    return Err(AiError::ResponseTooLarge);
                }
                bytes.extend_from_slice(&chunk);
            }
            serde_json::from_slice(&bytes).map_err(|_| AiError::InvalidResponse)
        })
    }
}

/// AI client using a validated configuration and injectable transport.
pub struct AiClient {
    config: AiConfig,
    transport: Arc<dyn AiTransport>,
}

impl AiClient {
    pub fn new(config: AiConfig) -> Result<Self, AiError> {
        let transport = Arc::new(ReqwestAiTransport::for_endpoint(
            &config.endpoint,
            config.timeout,
        )?);
        Ok(Self { config, transport })
    }

    pub fn with_transport(config: AiConfig, transport: Arc<dyn AiTransport>) -> Self {
        Self { config, transport }
    }

    /// Enhance a draft using only caller-provided source text and image bytes.
    pub async fn enhance(&self, input: &AiInput) -> Result<ProjectImportDraft, AiError> {
        validate_input(input)?;
        let draft_json =
            serde_json::to_string(&input.draft).map_err(|_| AiError::InvalidResponse)?;
        if draft_json.len() > MAX_DRAFT_BYTES {
            return Err(AiError::InputTooLarge);
        }

        let mut content = vec![json!({
            "type": "text",
            "text": format!(
                "Extract and improve knitting pattern fields from the supplied source. Return one JSON object only, using camelCase field names matching this draft. Preserve the source language. Do not invent image URLs. Keep the source link unchanged.\n\nSource text:\n{}\n\nExisting parsed draft JSON:\n{}",
                input.source_text, draft_json
            )
        })];
        for image in &input.images {
            content.push(json!({
                "type": "image_url",
                "image_url": {
                    "url": format!("data:{};base64,{}", image.media_type, STANDARD.encode(&image.bytes))
                }
            }));
        }
        let body = json!({
            "model": self.config.model,
            "messages": [
                {"role": "system", "content": "You extract knitting pattern information. Reply with valid JSON only. Use null for unknown fields. Preserve the language and source link."},
                {"role": "user", "content": content}
            ],
            "max_tokens": 4000,
            "temperature": 0.1,
            "response_format": {"type": "json_object"}
        });
        let response = self
            .transport
            .post(&self.config.endpoint, &self.config.api_key, &body)
            .await?;
        let raw = response
            .pointer("/choices/0/message/content")
            .and_then(Value::as_str)
            .ok_or(AiError::InvalidResponse)?;
        if raw.len() > MAX_RESPONSE_BYTES {
            return Err(AiError::ResponseTooLarge);
        }
        let output: AiDraftResponse =
            serde_json::from_str(raw).map_err(|_| AiError::InvalidJson)?;
        Ok(merge_response(input.draft.clone(), output))
    }
}

impl AiClient {
    /// Ask the model for a JSON object answering `system` and `user`. Returns the parsed object.
    /// Text-only; used by the URL import normaliser.
    pub async fn complete_json(&self, system: &str, user: &str) -> Result<Value, AiError> {
        if user.chars().count() > MAX_NORMALIZE_CHARS {
            return Err(AiError::InputTooLarge);
        }
        let body = json!({
            "model": self.config.model,
            "messages": [
                {"role": "system", "content": system},
                {"role": "user", "content": user}
            ],
            "max_tokens": 3000,
            "temperature": 0.0,
            "response_format": {"type": "json_object"}
        });
        let response = self
            .transport
            .post(&self.config.endpoint, &self.config.api_key, &body)
            .await?;
        let raw = response
            .pointer("/choices/0/message/content")
            .and_then(Value::as_str)
            .ok_or(AiError::InvalidResponse)?;
        if raw.len() > MAX_RESPONSE_BYTES {
            return Err(AiError::ResponseTooLarge);
        }
        crate::url_import::ai_merge::parse_answer(raw).ok_or(AiError::InvalidJson)
    }
}

/// Return the original parsed draft when AI is disabled, unavailable, or fails.
/// The caller must use `enhanced` as the sole source of any enhanced marker.
pub async fn enhance_or_fallback(client: Option<&AiClient>, input: AiInput) -> AiOutcome {
    let Some(client) = client else {
        return AiOutcome {
            draft: input.draft,
            enhanced: false,
            failure: None,
        };
    };
    match client.enhance(&input).await {
        Ok(draft) => AiOutcome {
            draft,
            enhanced: true,
            failure: None,
        },
        Err(error) => AiOutcome {
            draft: input.draft,
            enhanced: false,
            failure: Some(error),
        },
    }
}

fn validate_input(input: &AiInput) -> Result<(), AiError> {
    if input.source_text.chars().count() > MAX_TEXT_CHARS || input.images.len() > MAX_IMAGES {
        return Err(AiError::InputTooLarge);
    }
    for image in &input.images {
        if image.bytes.len() > MAX_IMAGE_BYTES {
            return Err(AiError::InputTooLarge);
        }
        if !matches!(
            image.media_type.as_str(),
            "image/jpeg" | "image/png" | "image/webp" | "image/gif"
        ) {
            return Err(AiError::UnsupportedImageType);
        }
    }
    Ok(())
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AiDraftResponse {
    name: Option<String>,
    description: Option<String>,
    category: Option<String>,
    yarn: Option<String>,
    yarns: Option<Vec<AiYarn>>,
    needles: Option<String>,
    #[serde(alias = "other_materials")]
    other_materials: Option<String>,
    #[serde(alias = "stitch_sample")]
    stitch_sample: Option<String>,
    #[serde(alias = "gauge_width")]
    gauge_width: Option<i64>,
    #[serde(alias = "gauge_height")]
    gauge_height: Option<i64>,
    #[serde(alias = "fiber_content")]
    fiber_content: Option<String>,
    colorway: Option<String>,
    #[serde(alias = "weight_category")]
    weight_category: Option<String>,
    steps: Option<Vec<AiStep>>,
    #[serde(alias = "image_urls")]
    image_urls: Option<Vec<String>>,
    brand: Option<String>,
    // Deliberately ignored: AI must never replace the source URL.
    link: Option<String>,
}

fn merge_response(mut draft: ProjectImportDraft, output: AiDraftResponse) -> ProjectImportDraft {
    let observed = observed_image_urls(&draft);
    let known_yarn_links = draft
        .yarns
        .iter()
        .filter_map(|yarn| yarn.link.clone())
        .collect::<std::collections::HashSet<_>>();
    merge_string(&mut draft.name, output.name);
    merge_string(&mut draft.description, output.description);
    merge_string(&mut draft.category, output.category);
    merge_string(&mut draft.yarn, output.yarn);
    merge_string(&mut draft.needles, output.needles);
    merge_string(&mut draft.other_materials, output.other_materials);
    merge_string(&mut draft.stitch_sample, output.stitch_sample);
    merge_string(&mut draft.fiber_content, output.fiber_content);
    merge_string(&mut draft.colorway, output.colorway);
    merge_string(&mut draft.weight_category, output.weight_category);
    merge_string(&mut draft.brand, output.brand);
    if let Some(value) = output.gauge_width {
        draft.gauge_width = Some(value);
    }
    if let Some(value) = output.gauge_height {
        draft.gauge_height = Some(value);
    }
    if let Some(yarns) = output.yarns {
        draft.yarns = merge_yarns(
            std::mem::take(&mut draft.yarns),
            yarns.into_iter().map(AiYarn::into_yarn).collect(),
            &known_yarn_links,
            &observed,
        );
    }

    if let Some(images) = output.image_urls {
        let filtered: Vec<_> = images
            .into_iter()
            .filter(|url| observed.contains(url))
            .collect();
        if !filtered.is_empty() {
            draft.image_urls = filtered;
        }
    }
    if let Some(steps) = output.steps {
        draft.steps = merge_steps(std::mem::take(&mut draft.steps), steps, &observed);
    } else {
        for step in &mut draft.steps {
            step.images.retain(|url| observed.contains(url));
        }
    }
    // Keep existing yarn image suggestions only when they are observed too.
    for yarn in &mut draft.yarns {
        if let Some(url) = &yarn.image_url
            && !observed.contains(url)
        {
            yarn.image_url = None;
        }
    }
    let _ = output.link;
    draft
}

fn merge_steps(
    mut old: Vec<StepImportDraft>,
    incoming: Vec<AiStep>,
    observed: &std::collections::HashSet<String>,
) -> Vec<StepImportDraft> {
    let mut merged = Vec::with_capacity(old.len().max(incoming.len()));
    for candidate in incoming {
        let mut step = candidate.into_step();
        step.images.retain(|url| observed.contains(url));
        if let Some(index) = old
            .iter()
            .position(|existing| existing.step_number == step.step_number)
        {
            let existing = old.remove(index);
            if step.title.as_deref().is_none_or(str::is_empty) {
                step.title = existing.title;
            }
            if step.description.as_deref().is_none_or(str::is_empty) {
                step.description = existing.description;
            }
            if step.images.is_empty() {
                step.images = existing.images;
            }
        }
        merged.push(step);
    }
    merged.extend(old);
    merged
}

fn merge_yarns(
    mut old: Vec<YarnImportDraft>,
    incoming: Vec<YarnImportDraft>,
    known_links: &std::collections::HashSet<String>,
    observed_images: &std::collections::HashSet<String>,
) -> Vec<YarnImportDraft> {
    let mut merged = Vec::with_capacity(old.len().max(incoming.len()));
    for mut yarn in incoming {
        if let Some(link) = &yarn.link
            && !known_links.contains(link)
        {
            yarn.link = None;
        }
        if let Some(image) = &yarn.image_url
            && !observed_images.contains(image)
        {
            yarn.image_url = None;
        }
        if let Some(index) = old.iter().position(|existing| {
            normalized(&existing.name) == normalized(&yarn.name)
                && normalized(&existing.brand) == normalized(&yarn.brand)
                && yarn.name.is_some()
        }) {
            let existing = old.remove(index);
            fill_yarn_gaps(&mut yarn, existing);
        }
        merged.push(yarn);
    }
    merged.extend(old);
    merged
}

fn normalized(value: &Option<String>) -> String {
    value
        .as_deref()
        .unwrap_or_default()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ")
        .to_lowercase()
}

fn fill_yarn_gaps(target: &mut YarnImportDraft, existing: YarnImportDraft) {
    macro_rules! fill {
        ($($field:ident),+ $(,)?) => {
            $(if target.$field.as_ref().is_none_or(|value| value.trim().is_empty()) {
                target.$field = existing.$field;
            })+
        };
    }
    fill!(
        name,
        brand,
        colorway,
        weight,
        length,
        weight_category,
        fiber_content,
        link,
        image_url
    );
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AiStep {
    #[serde(alias = "step_number")]
    #[serde(default)]
    step_number: usize,
    title: Option<String>,
    description: Option<String>,
    #[serde(default)]
    images: Vec<String>,
}

#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
struct AiYarn {
    name: Option<String>,
    brand: Option<String>,
    colorway: Option<String>,
    weight: Option<String>,
    length: Option<String>,
    #[serde(alias = "weight_category")]
    weight_category: Option<String>,
    #[serde(alias = "fiber_content")]
    fiber_content: Option<String>,
    link: Option<String>,
    #[serde(alias = "image_url")]
    image_url: Option<String>,
}

impl AiYarn {
    fn into_yarn(self) -> YarnImportDraft {
        YarnImportDraft {
            name: self.name,
            brand: self.brand,
            colorway: self.colorway,
            weight: self.weight,
            length: self.length,
            weight_category: self.weight_category,
            fiber_content: self.fiber_content,
            link: self.link,
            image_url: self.image_url,
        }
    }
}

impl AiStep {
    fn into_step(self) -> StepImportDraft {
        StepImportDraft {
            step_number: self.step_number,
            title: self.title,
            description: self.description,
            images: self.images,
        }
    }
}

fn merge_string(target: &mut Option<String>, candidate: Option<String>) {
    if let Some(value) = candidate
        .map(|value| value.trim().to_owned())
        .filter(|value| !value.is_empty())
    {
        *target = Some(value);
    }
}

fn observed_image_urls(draft: &ProjectImportDraft) -> std::collections::HashSet<String> {
    draft
        .image_urls
        .iter()
        .cloned()
        .chain(
            draft
                .steps
                .iter()
                .flat_map(|step| step.images.iter().cloned()),
        )
        .chain(draft.yarns.iter().filter_map(|yarn| yarn.image_url.clone()))
        .collect()
}
