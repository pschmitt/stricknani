use std::{collections::HashMap, future::Future, pin::Pin, sync::Mutex};

use serde_json::{Value, json};
use stricknani_server::{
    ai::{AiClient, AiConfig, AiError, AiImage, AiInput, AiTransport, ApiKey, enhance_or_fallback},
    import_document::{ProjectImportDraft, StepImportDraft},
};
use url::Url;

type PostFuture<'a> = Pin<Box<dyn Future<Output = Result<Value, AiError>> + Send + 'a>>;

#[derive(Default)]
struct Capture {
    endpoint: Option<Url>,
    body: Option<Value>,
    called: bool,
}

struct FixtureTransport {
    response: Result<Value, AiError>,
    capture: Mutex<Capture>,
}

impl FixtureTransport {
    fn response(content: &str) -> Self {
        Self {
            response: Ok(json!({"choices": [{"message": {"content": content}}]})),
            capture: Mutex::new(Capture::default()),
        }
    }

    fn failure(error: AiError) -> Self {
        Self {
            response: Err(error),
            capture: Mutex::new(Capture::default()),
        }
    }
}

impl AiTransport for FixtureTransport {
    fn post<'a>(
        &'a self,
        endpoint: &'a Url,
        _api_key: &'a ApiKey,
        body: &'a Value,
    ) -> PostFuture<'a> {
        Box::pin(async move {
            let mut capture = self.capture.lock().expect("fixture lock");
            capture.endpoint = Some(endpoint.clone());
            capture.body = Some(body.clone());
            capture.called = true;
            self.response.clone()
        })
    }
}

fn config(values: &[(&str, &str)]) -> AiConfig {
    let values: HashMap<_, _> = values.iter().copied().collect();
    AiConfig::from_lookup(true, |key| values.get(key).map(|value| (*value).to_owned()))
        .expect("valid fixture config")
        .expect("AI is enabled")
}

fn input() -> AiInput {
    AiInput {
        source_text: "A German pattern description".to_owned(),
        draft: ProjectImportDraft {
            name: Some("Existing title".to_owned()),
            link: Some("https://example.test/pattern".to_owned()),
            image_urls: vec!["https://example.test/observed.jpg".to_owned()],
            steps: vec![StepImportDraft {
                step_number: 1,
                title: None,
                description: Some("Existing step".to_owned()),
                images: vec!["https://example.test/observed.jpg".to_owned()],
            }],
            ..ProjectImportDraft::default()
        },
        images: vec![AiImage {
            media_type: "image/jpeg".to_owned(),
            bytes: vec![0xff, 0xd8, 0xff],
        }],
    }
}

#[test]
fn provider_key_precedence_and_model_override_are_explicit_and_redacted() {
    let values = HashMap::from([
        ("AI_PROVIDER", "groq"),
        ("AI_API_KEY", "shared-fixture-key"),
        ("GROQ_API_KEY", "provider-fixture-key"),
        ("OPENAI_API_KEY", "fallback-fixture-key"),
        ("AI_MODEL", "fixture-model"),
    ]);
    let mut looked_up = Vec::new();
    let config = AiConfig::from_lookup(true, |key| {
        looked_up.push(key.to_owned());
        values.get(key).map(|value| (*value).to_owned())
    })
    .expect("valid fixture config")
    .expect("AI enabled");

    assert_eq!(config.provider(), stricknani_server::ai::AiProvider::Groq);
    assert_eq!(config.model(), "fixture-model");
    assert_eq!(
        looked_up,
        [
            "AI_PROVIDER",
            "AI_API_KEY",
            "AI_MODEL",
            "AI_BASE_URL",
            "AI_TIMEOUT_SECONDS",
            "AI_BROWSE",
            "AI_BROWSE_TIMEOUT_SECONDS"
        ]
    );
    assert!(!format!("{config:?}").contains("fixture-key"));
}

#[test]
fn provider_key_precedes_compatibility_fallback() {
    let values = HashMap::from([
        ("AI_PROVIDER", "groq"),
        ("GROQ_API_KEY", "provider-fixture-key"),
        ("OPENAI_API_KEY", "fallback-fixture-key"),
    ]);
    let mut looked_up = Vec::new();
    AiConfig::from_lookup(true, |key| {
        looked_up.push(key.to_owned());
        values.get(key).map(|value| (*value).to_owned())
    })
    .expect("valid fixture config")
    .expect("AI enabled");
    assert_eq!(
        looked_up,
        [
            "AI_PROVIDER",
            "AI_API_KEY",
            "AI_API_KEY_FILE",
            "GROQ_API_KEY",
            "AI_MODEL",
            "AI_BASE_URL",
            "AI_TIMEOUT_SECONDS",
            "AI_BROWSE",
            "AI_BROWSE_TIMEOUT_SECONDS"
        ]
    );
}

#[test]
fn disabled_feature_does_not_read_credentials() {
    let mut names = Vec::new();
    let result = AiConfig::from_lookup(false, |name| {
        names.push(name.to_owned());
        Some("fixture-only".to_owned())
    })
    .expect("disabled AI config is valid");
    assert!(result.is_none());
    assert!(names.is_empty());
}

#[test]
fn provider_and_custom_base_url_validation_reject_unsafe_authorities() {
    for values in [
        vec![("AI_PROVIDER", "unknown"), ("AI_API_KEY", "fixture")],
        vec![
            ("AI_API_KEY", "fixture"),
            ("AI_BASE_URL", "https://user:pass@example.test/v1"),
        ],
        vec![
            ("AI_API_KEY", "fixture"),
            ("AI_BASE_URL", "https://@example.test/v1"),
        ],
        vec![
            ("AI_API_KEY", "fixture"),
            ("AI_BASE_URL", "https://example.test/v1?token=x"),
        ],
        vec![
            ("AI_API_KEY", "fixture"),
            ("AI_BASE_URL", "http://example.test/v1"),
        ],
    ] {
        assert!(
            AiConfig::from_lookup(true, |key| {
                values
                    .iter()
                    .find(|(name, _)| *name == key)
                    .map(|(_, value)| (*value).to_owned())
            })
            .is_err()
        );
    }
}

#[tokio::test]
async fn chat_request_uses_provider_model_json_mode_image_data_url_and_merges_safely() {
    let output = json!({
        "name": "Verbesserter Titel",
        "description": "Deutsche Beschreibung",
        "link": "https://hallucinated.test/replace-source",
        "imageUrls": [
            "https://example.test/observed.jpg",
            "https://hallucinated.test/image.jpg"
        ],
        "steps": [{
            "stepNumber": 1,
            "title": "Schritt eins",
            "description": "Neue Beschreibung",
            "images": [
                "https://example.test/observed.jpg",
                "https://hallucinated.test/step.jpg"
            ]
        }]
    })
    .to_string();
    let transport = std::sync::Arc::new(FixtureTransport::response(&output));
    let client = AiClient::with_transport(
        config(&[("AI_PROVIDER", "openrouter"), ("AI_API_KEY", "fixture-key")]),
        transport.clone(),
    );

    let result = client
        .enhance(&input())
        .await
        .expect("fixture response parses");
    assert_eq!(result.name.as_deref(), Some("Verbesserter Titel"));
    assert_eq!(result.link.as_deref(), Some("https://example.test/pattern"));
    assert_eq!(
        result.image_urls,
        ["https://example.test/observed.jpg".to_owned()]
    );
    assert_eq!(
        result.steps[0].images,
        ["https://example.test/observed.jpg".to_owned()]
    );

    let capture = transport.capture.lock().expect("fixture lock");
    assert_eq!(
        capture.endpoint.as_ref().unwrap().as_str(),
        "https://openrouter.ai/api/v1/chat/completions"
    );
    let body = capture.body.as_ref().unwrap();
    assert_eq!(body["model"], "openai/gpt-4o-mini");
    assert_eq!(body["max_tokens"], 4000);
    assert_eq!(body["temperature"], 0.1);
    assert_eq!(body["response_format"]["type"], "json_object");
    assert_eq!(body["messages"][1]["content"][1]["type"], "image_url");
    assert!(
        body["messages"][1]["content"][1]["image_url"]["url"]
            .as_str()
            .unwrap()
            .starts_with("data:image/jpeg;base64,")
    );
}

#[tokio::test]
async fn failed_provider_returns_original_draft_without_enhanced_marker() {
    let transport = std::sync::Arc::new(FixtureTransport::failure(AiError::HttpStatus(503)));
    let client = AiClient::with_transport(config(&[("AI_API_KEY", "fixture-key")]), transport);
    let original = input();
    let outcome = enhance_or_fallback(Some(&client), original.clone()).await;

    assert!(!outcome.enhanced);
    assert_eq!(outcome.draft, original.draft);
    assert_eq!(outcome.failure, Some(AiError::HttpStatus(503)));
    assert!(!outcome.failure.unwrap().to_string().contains("fixture-key"));
}

#[tokio::test]
async fn invalid_provider_json_uses_original_draft_fallback() {
    let transport = std::sync::Arc::new(FixtureTransport::response("not-json"));
    let client = AiClient::with_transport(config(&[("AI_API_KEY", "fixture-key")]), transport);
    let original = input();
    let outcome = enhance_or_fallback(Some(&client), original.clone()).await;
    assert!(!outcome.enhanced);
    assert_eq!(outcome.draft, original.draft);
    assert_eq!(outcome.failure, Some(AiError::InvalidJson));
}

#[tokio::test]
async fn oversized_input_is_rejected_before_transport() {
    let transport = std::sync::Arc::new(FixtureTransport::response("{}"));
    let client =
        AiClient::with_transport(config(&[("AI_API_KEY", "fixture-key")]), transport.clone());
    let mut request = input();
    request.source_text = "x".repeat(12_001);
    assert_eq!(
        client.enhance(&request).await.unwrap_err(),
        AiError::InputTooLarge
    );
    assert!(!transport.capture.lock().unwrap().called);

    let mut request = input();
    request.images[0].bytes = vec![0; 2_000_001];
    assert_eq!(
        client.enhance(&request).await.unwrap_err(),
        AiError::InputTooLarge
    );
    assert!(!transport.capture.lock().unwrap().called);
}

#[tokio::test]
async fn disabled_ai_fallback_is_unmodified_and_not_enhanced() {
    let original = input();
    let outcome = enhance_or_fallback(None, original.clone()).await;
    assert!(!outcome.enhanced);
    assert_eq!(outcome.draft, original.draft);
    assert!(outcome.failure.is_none());
}
