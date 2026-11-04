//! Golden tests for URL import extraction. Fixtures are small synthetic pages modelled on real
//! shop, pattern and Ravelry markup; nothing here touches the network.

use std::{
    collections::HashMap,
    future::Future,
    pin::Pin,
    sync::{Arc, Mutex},
};

use serde_json::{Value, json};
use stricknani_server::{
    ai::{AiClient, AiConfig, AiError, AiTransport, ApiKey},
    import_document::{FieldSource, ImportDocumentDraft, PageKind},
    imports::normalize_input_url,
    url_import::{
        ImportTarget, ai_merge, charset, extract_html,
        ravelry::{
            self, RavelryClient, RavelryCredentials, RavelryError, RavelryKind, RavelryTransport,
        },
    },
};
use url::Url;

const FIXTURES: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures/url_import");

fn fixture(name: &str) -> Vec<u8> {
    std::fs::read(format!("{FIXTURES}/{name}")).expect("fixture exists")
}

fn draft_from(name: &str, url: &str) -> ImportDocumentDraft {
    let html = charset::decode_html(&fixture(name), Some("text/html"));
    let url = Url::parse(url).unwrap();
    let extraction = extract_html(&html, Some(&url), None);
    ImportDocumentDraft {
        kind: stricknani_server::import_document::DocumentKind::Html,
        sha256: String::new(),
        source_url: Some(url.to_string()),
        project: extraction.project,
        image: None,
        yarn: extraction.yarn,
        page_kind: extraction.kind,
        provenance: extraction.provenance,
        warnings: extraction.warnings,
    }
}

#[test]
fn english_shop_page_with_json_ld() {
    let draft = draft_from(
        "yarn_shop_en.html",
        "https://shop.yarnhaven.example/products/malabrigo-rios-frost-grey",
    );
    assert_eq!(draft.page_kind, PageKind::Yarn);
    let yarn = draft.yarn.expect("yarn");
    assert_eq!(yarn.name.as_deref(), Some("Malabrigo Rios - Frost Grey"));
    assert_eq!(yarn.brand.as_deref(), Some("Malabrigo"));
    assert_eq!(yarn.colorway.as_deref(), Some("Frost Grey"));
    assert_eq!(
        yarn.fiber_content.as_deref(),
        Some("100% Superwash Merino Wool")
    );
    assert_eq!(yarn.weight_grams, Some(100));
    assert_eq!(yarn.length_meters, Some(192));
    assert_eq!(yarn.weight_category.as_deref(), Some("Worsted"));
    assert_eq!(yarn.recommended_needles.as_deref(), Some("4.5-5 mm"));
    assert_eq!(yarn.gauge.as_deref(), Some("18 sts and 24 rows = 10 cm"));
    assert_eq!(
        yarn.care.as_deref(),
        Some("Machine wash cold, lay flat to dry")
    );
    assert_eq!(yarn.price.as_deref(), Some("15.90 USD"));
    assert_eq!(
        yarn.description.as_deref(),
        Some("A superwash merino worsted with deep, saturated colour. Soft, round and springy.")
    );
    // Two real photos: size variants are merged, logo and payment icons are dropped.
    assert_eq!(yarn.image_urls.len(), 2, "{:?}", yarn.image_urls);
    assert!(
        yarn.image_urls
            .iter()
            .all(|u| u.contains("rios-frost-grey"))
    );
    // Provenance: markup versus text guesses.
    assert_eq!(draft.provenance["yarn.brand"], FieldSource::Structured);
    assert_eq!(draft.provenance["yarn.price"], FieldSource::Structured);
    assert_eq!(draft.provenance["yarn.fiberContent"], FieldSource::Text);
    assert!(!draft.warnings.contains(&"no_structured_data".to_owned()));
}

#[test]
fn german_latin1_shop_page_with_microdata() {
    let draft = draft_from(
        "yarn_shop_de_latin1.html",
        "https://www.wollhaus-mueller.example/garne/lana-grossa/cool-wool-uni-433",
    );
    assert_eq!(draft.page_kind, PageKind::Yarn);
    let yarn = draft.yarn.expect("yarn");
    assert_eq!(
        yarn.name.as_deref(),
        Some("Lana Grossa Cool Wool Uni 433 Schneeweiß")
    );
    assert_eq!(yarn.brand.as_deref(), Some("Lana Grossa"));
    assert_eq!(yarn.colorway.as_deref(), Some("Schneeweiß (433)"));
    assert_eq!(yarn.fiber_content.as_deref(), Some("100% Schurwolle"));
    assert_eq!(yarn.weight_grams, Some(50));
    assert_eq!(yarn.length_meters, Some(160));
    assert_eq!(yarn.recommended_needles.as_deref(), Some("3.5-4 mm"));
    assert_eq!(
        yarn.gauge.as_deref(),
        Some("22 Maschen und 30 Reihen = 10 x 10 cm")
    );
    assert_eq!(yarn.weight_category.as_deref(), Some("Fingering"));
    assert_eq!(yarn.price.as_deref(), Some("6.95 EUR"));
    assert_eq!(
        yarn.care.as_deref(),
        Some("Maschinenwäsche 40 Grad im Schonwaschgang")
    );
    assert!(
        yarn.description
            .as_deref()
            .unwrap()
            .contains("strapazierfähig")
    );
}

#[test]
fn product_group_with_property_values() {
    let draft = draft_from(
        "yarn_shop_lovecrafts.html",
        "https://www.lovecrafts.example/en-gb/p/drops-air",
    );
    assert_eq!(draft.page_kind, PageKind::Yarn);
    let yarn = draft.yarn.expect("yarn");
    assert_eq!(yarn.name.as_deref(), Some("DROPS Air Yarn"));
    assert_eq!(yarn.brand.as_deref(), Some("DROPS Design"));
    assert_eq!(
        yarn.fiber_content.as_deref(),
        Some("65% Baby Alpaca, 28% Polyamide, 7% Wool")
    );
    assert_eq!(yarn.weight_category.as_deref(), Some("Aran"));
    assert_eq!(yarn.weight_grams, Some(50));
    assert_eq!(yarn.length_meters, Some(150));
    assert_eq!(yarn.recommended_needles.as_deref(), Some("5-6 mm"));
    assert_eq!(yarn.gauge.as_deref(), Some("17 sts x 22 rows = 10 cm"));
    assert_eq!(yarn.price.as_deref(), Some("4.50 GBP"));
    assert_eq!(
        draft.provenance["yarn.fiberContent"],
        FieldSource::Structured
    );
}

#[test]
fn drops_pattern_without_any_structured_data() {
    let draft = draft_from(
        "drops_pattern.html",
        "https://www.garnstudio.com/pattern.php?id=11588&cid=19",
    );
    assert_eq!(draft.page_kind, PageKind::Pattern);
    assert!(draft.warnings.contains(&"no_structured_data".to_owned()));
    let project = &draft.project;
    assert_eq!(
        project.name.as_deref(),
        Some("Snow Flurries / DROPS 245-17")
    );
    assert_eq!(project.designer.as_deref(), Some("DROPS Design"));
    assert_eq!(draft.provenance["project.designer"], FieldSource::Site);
    assert_eq!(
        project.sizes.as_deref(),
        Some("S - M - L - XL - XXL - XXXL")
    );
    assert_eq!(project.difficulty.as_deref(), Some("Fortgeschritten"));
    assert!(
        project
            .stitch_sample
            .as_deref()
            .unwrap()
            .starts_with("17 Maschen x 22 Reihen")
    );
    assert!(project.needles.as_deref().unwrap().contains('5'));
    assert_eq!(
        project.yarn.as_deref(),
        Some("DROPS AIR (Garngruppe C) von Garnstudio")
    );
    assert!(!project.image_urls.is_empty());
}

#[test]
fn drops_pattern_in_header_and_body_layout() {
    let draft = draft_from(
        "drops_pattern_en.html",
        "https://www.garnstudio.com/pattern.php?id=11588&cid=19",
    );
    assert_eq!(draft.page_kind, PageKind::Pattern);
    let project = &draft.project;
    assert_eq!(
        project.name.as_deref(),
        Some("Autumn Amber Sweater / DROPS 244-25")
    );
    assert_eq!(project.designer.as_deref(), Some("DROPS Design"));
    assert_eq!(
        project.sizes.as_deref(),
        Some("S - M - L - XL - XXL - XXXL")
    );
    assert_eq!(project.needles.as_deref(), Some("9 mm, 6 mm"));
    assert_eq!(
        project.stitch_sample.as_deref(),
        Some("10 stitches in width and 14 rows in height with stocking stitch = 10 x 10 cm.")
    );
    assert_eq!(
        project.yarn.as_deref(),
        Some("DROPS SNOW from Garnstudio (belongs to yarn group E)")
    );
    assert_eq!(
        project.image_urls,
        ["https://images.garnstudio.example/drops/mag/244/25/25-2.jpg"]
    );
}

#[test]
fn blog_pattern_with_how_to_steps() {
    let draft = draft_from(
        "blog_pattern.html",
        "https://needlesandtea.example/cozy-cable-hat/",
    );
    assert_eq!(draft.page_kind, PageKind::Pattern);
    let project = &draft.project;
    assert_eq!(project.name.as_deref(), Some("Cozy Cable Hat"));
    assert_eq!(project.designer.as_deref(), Some("Mara Linden"));
    assert_eq!(project.category.as_deref(), Some("Hats"));
    assert_eq!(project.sizes.as_deref(), Some("Adult S, M, L"));
    assert_eq!(project.difficulty.as_deref(), Some("Easy"));
    assert_eq!(
        project.stitch_sample.as_deref(),
        Some("20 sts and 28 rows = 10 cm in stockinette")
    );
    assert_eq!(
        project.needles.as_deref(),
        Some("4.5 mm circular needle, 40 cm")
    );
    assert_eq!(
        project.other_materials.as_deref(),
        Some("100 g worsted weight wool\nStitch marker")
    );
    assert_eq!(project.steps.len(), 4);
    assert_eq!(project.steps[0].title.as_deref(), Some("Cast on"));
    assert_eq!(
        project.steps[2].description.as_deref(),
        Some("Work the cable chart for 15 cm.")
    );
    assert_eq!(project.tags, ["hat", "beanie", "cables", "free pattern"]);
    assert_eq!(
        project.image_urls,
        [
            "https://needlesandtea.example/wp-content/uploads/2026/01/cable-hat-1024x1536.jpg",
            "https://needlesandtea.example/wp-content/uploads/2026/01/cable-hat-detail.jpg"
        ]
    );
}

#[test]
fn ravelry_pattern_page_is_not_mistaken_for_yarn_and_api_data_wins() {
    let mut draft = draft_from(
        "ravelry_pattern_page.html",
        "https://www.ravelry.com/patterns/library/hitchhiker",
    );
    assert_eq!(draft.page_kind, PageKind::Pattern);
    assert_eq!(draft.project.designer.as_deref(), Some("Martina Behm"));
    let html = String::from_utf8(fixture("ravelry_pattern_page.html")).unwrap();
    assert_eq!(
        ravelry::id_from_html(&html, RavelryKind::Pattern),
        Some(211562)
    );

    let api: Value = serde_json::from_slice(&fixture("ravelry_pattern_api.json")).unwrap();
    ravelry::apply_pattern(&mut draft, &api);
    let project = &draft.project;
    assert_eq!(project.name.as_deref(), Some("Hitchhiker"));
    assert_eq!(
        project.description.as_deref(),
        Some("A scarf with a sawtooth edge, knit sideways.")
    );
    assert_eq!(project.needles.as_deref(), Some("4 mm, 3.5 mm"));
    assert_eq!(
        project.stitch_sample.as_deref(),
        Some("24 stitches and 36 rows = 4 inches in garter stitch")
    );
    assert_eq!(project.weight_category.as_deref(), Some("Fingering"));
    assert_eq!(project.category.as_deref(), Some("Scarf"));
    assert_eq!(project.difficulty.as_deref(), Some("3.4 / 10"));
    assert_eq!(project.sizes.as_deref(), Some("One size"));
    assert!(
        project
            .other_materials
            .as_deref()
            .unwrap()
            .contains("Yardage: 400-450 yds (366-411 m)")
    );
    assert_eq!(project.yarns.len(), 1);
    assert_eq!(project.yarns[0].brand.as_deref(), Some("Schoppel-Wolle"));
    assert_eq!(
        project.image_urls,
        [
            "https://images4-g.ravelrycache.com/a.jpg",
            "https://images4-g.ravelrycache.com/b.jpg"
        ]
    );
    assert_eq!(draft.provenance["project.needles"], FieldSource::Site);
    assert!(project.tags.contains(&"knitting".to_owned()));
}

#[test]
fn ravelry_yarn_api_fills_clean_fields() {
    let mut draft = draft_from(
        "ravelry_yarn_page.html",
        "https://www.ravelry.com/yarns/library/cascade-220-superwash",
    );
    let html = String::from_utf8(fixture("ravelry_yarn_page.html")).unwrap();
    assert_eq!(ravelry::id_from_html(&html, RavelryKind::Yarn), Some(1234));
    let api: Value = serde_json::from_slice(&fixture("ravelry_yarn_api.json")).unwrap();
    ravelry::apply_yarn(&mut draft, &api);
    assert_eq!(draft.page_kind, PageKind::Yarn);
    let yarn = draft.yarn.unwrap();
    assert_eq!(yarn.brand.as_deref(), Some("Cascade Yarns"));
    assert_eq!(yarn.fiber_content.as_deref(), Some("100% Superwash Wool"));
    assert_eq!(yarn.weight_category.as_deref(), Some("Worsted"));
    assert_eq!(yarn.weight_grams, Some(100));
    assert_eq!(yarn.length_meters, Some(201));
    assert_eq!(yarn.recommended_needles.as_deref(), Some("4.5-5 mm"));
    assert_eq!(yarn.care.as_deref(), Some("Machine washable"));
    assert_eq!(
        yarn.image_urls,
        ["https://images4-g.ravelrycache.com/yarn.jpg"]
    );
    assert_eq!(draft.provenance["yarn.brand"], FieldSource::Site);
}

struct MockRavelry {
    paths: Mutex<Vec<String>>,
}

impl RavelryTransport for MockRavelry {
    fn get_json<'a>(
        &'a self,
        path: &'a str,
        _credentials: &'a RavelryCredentials,
    ) -> Pin<Box<dyn Future<Output = Result<Value, RavelryError>> + Send + 'a>> {
        self.paths.lock().unwrap().push(path.to_owned());
        Box::pin(async { Ok(json!({"pattern": {"name": "x"}})) })
    }
}

#[tokio::test]
async fn ravelry_client_requests_json_paths_and_hides_credentials() {
    let credentials = RavelryCredentials::new("user".into(), "hunter2".into()).unwrap();
    assert!(!format!("{credentials:?}").contains("hunter2"));
    let transport = Arc::new(MockRavelry {
        paths: Mutex::new(Vec::new()),
    });
    let client = RavelryClient::with_transport(credentials, transport.clone());
    client.fetch(RavelryKind::Pattern, 211562).await.unwrap();
    client.fetch(RavelryKind::Yarn, 1234).await.unwrap();
    assert_eq!(
        *transport.paths.lock().unwrap(),
        ["/patterns/211562.json", "/yarns/1234.json"]
    );
    assert!(
        RavelryCredentials::from_lookup(|name| (name == "RAVELRY_USERNAME").then(|| "u".into()))
            .is_none()
    );
}

#[test]
fn pasted_urls_are_normalised() {
    assert_eq!(
        normalize_input_url("  www.drops.example/p?id=1#top "),
        "https://www.drops.example/p?id=1"
    );
    assert_eq!(
        normalize_input_url("http://a.example/x"),
        "http://a.example/x"
    );
}

// ------------------------------------------------------------------ AI

type PostFuture<'a> = Pin<Box<dyn Future<Output = Result<Value, AiError>> + Send + 'a>>;

struct ScriptedAi {
    reply: String,
    bodies: Mutex<Vec<Value>>,
}

impl AiTransport for ScriptedAi {
    fn post<'a>(&'a self, _endpoint: &'a Url, _key: &'a ApiKey, body: &'a Value) -> PostFuture<'a> {
        self.bodies.lock().unwrap().push(body.clone());
        let reply = self.reply.clone();
        Box::pin(async move { Ok(json!({"choices": [{"message": {"content": reply}}]})) })
    }
}

fn ai_client(reply: &str) -> (AiClient, Arc<ScriptedAi>) {
    let values: HashMap<&str, &str> = HashMap::from([
        ("AI_API_KEY", "k"),
        ("AI_PROVIDER", "custom"),
        ("AI_BASE_URL", "http://127.0.0.1:1/v1"),
    ]);
    let config = AiConfig::from_lookup(true, |key| values.get(key).map(|v| (*v).to_owned()))
        .unwrap()
        .unwrap();
    let transport = Arc::new(ScriptedAi {
        reply: reply.to_owned(),
        bodies: Mutex::new(Vec::new()),
    });
    (
        AiClient::with_transport(config, transport.clone()),
        transport,
    )
}

const SOURCE: &str = "Lana Grossa Cool Wool. 75 % Schurwolle, 25 % Polyamid. Lauflänge 160 m / 50 g. Nadelstärke 3,5 mm.";

#[tokio::test]
async fn ai_fills_gaps_but_never_overrides_markup_or_invents_numbers() {
    let mut draft = draft_from("yarn_shop_en.html", "https://shop.yarnhaven.example/p");
    // Pretend the fiber came from a text guess and the brand from markup.
    draft
        .provenance
        .insert("yarn.fiberContent".into(), FieldSource::Text);
    draft.yarn.as_mut().unwrap().colorway = None;
    draft.provenance.remove("yarn.colorway");
    let reply = "```json\n{\"pageKind\":\"yarn\",\"yarn\":{\"brand\":\"Somebody Else\",\"colorway\":\"Frost\",\
        \"fiberContent\":\"75% Wool, 25% Nylon\",\"weightGrams\":999,\"link\":\"https://evil.example\"}}\n```";
    let (client, transport) = ai_client(reply);
    let (system, user) = ai_merge::build_messages(ImportTarget::Yarn, &draft, SOURCE);
    assert!(system.contains("untrusted data"));
    let answer = client.complete_json(&system, &user).await.unwrap();
    let changed = ai_merge::apply(&mut draft, &answer, SOURCE, ImportTarget::Yarn);
    let yarn = draft.yarn.unwrap();
    assert_eq!(yarn.brand.as_deref(), Some("Malabrigo"), "markup wins");
    assert_eq!(yarn.colorway.as_deref(), Some("Frost"));
    assert_eq!(draft.provenance["yarn.colorway"], FieldSource::Ai);
    assert_eq!(
        yarn.fiber_content.as_deref(),
        Some("75% Wool, 25% Nylon"),
        "text guess is replaced by grounded AI output"
    );
    assert_eq!(draft.provenance["yarn.fiberContent"], FieldSource::Ai);
    assert_ne!(yarn.weight_grams, Some(999), "ungrounded quantity rejected");
    assert_eq!(yarn.length_meters, Some(192), "existing value is kept");
    assert_eq!(
        yarn.link.as_deref(),
        Some("https://shop.yarnhaven.example/p")
    );
    assert_eq!(changed, 2);
    let body = transport.bodies.lock().unwrap()[0].clone();
    assert!(
        body["messages"][1]["content"]
            .as_str()
            .unwrap()
            .contains("<<<PAGE")
    );
}

#[tokio::test]
async fn ai_garbage_is_an_error_and_steps_must_be_supported_by_the_page() {
    let (client, _) = ai_client("sorry, I cannot do that");
    assert_eq!(
        client.complete_json("s", "u").await.unwrap_err(),
        AiError::InvalidJson
    );

    let mut draft = draft_from(
        "drops_pattern.html",
        "https://www.garnstudio.com/pattern.php?id=1",
    );
    let answer = json!({"project": {"steps": [
        {"title": "Cast on", "description": "Teleport to the moon and bake a cake"}]}});
    let source = "Der Pullover wird von oben nach unten in Runden gestrickt.";
    assert_eq!(
        ai_merge::apply(&mut draft, &answer, source, ImportTarget::Project),
        0
    );
    assert!(draft.project.steps.is_empty());
    let answer = json!({"project": {"steps": [
        {"title": "Körper", "description": "Der Pullover wird von oben nach unten gestrickt"}]}});
    assert_eq!(
        ai_merge::apply(&mut draft, &answer, source, ImportTarget::Project),
        1
    );
    assert_eq!(draft.provenance["project.steps"], FieldSource::Ai);
}

#[tokio::test]
async fn openai_compatible_endpoint_over_plain_http() {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
    let port = listener.local_addr().unwrap().port();
    let server = tokio::spawn(async move {
        let (mut socket, _) = listener.accept().await.unwrap();
        let mut request = Vec::new();
        let mut buffer = [0_u8; 4096];
        loop {
            let read = socket.read(&mut buffer).await.unwrap();
            request.extend_from_slice(&buffer[..read]);
            let text = String::from_utf8_lossy(&request).to_string();
            if let Some(index) = text.find("\r\n\r\n") {
                let length: usize = text[..index]
                    .lines()
                    .find_map(|l| {
                        l.to_ascii_lowercase()
                            .strip_prefix("content-length:")
                            .map(|v| v.trim().parse().unwrap())
                    })
                    .unwrap_or(0);
                if request.len() >= index + 4 + length {
                    break;
                }
            }
        }
        let body = r#"{"choices":[{"message":{"content":"{\"pageKind\":\"yarn\"}"}}]}"#;
        socket
            .write_all(format!("HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: {}\r\nconnection: close\r\n\r\n{body}", body.len()).as_bytes())
            .await
            .unwrap();
        String::from_utf8_lossy(&request).to_string()
    });
    let base = format!("http://127.0.0.1:{port}/v1");
    let values = HashMap::from([
        ("AI_PROVIDER", "hermes"),
        ("AI_BASE_URL", base.as_str()),
        ("AI_API_KEY", "secret-key"),
    ]);
    let config = AiConfig::from_lookup(true, |key| values.get(key).map(|v| (*v).to_owned()))
        .unwrap()
        .unwrap();
    assert_eq!(config.model(), "hermes-agent");
    let client = AiClient::new(config).unwrap();
    let answer = client.complete_json("system", "user").await.unwrap();
    assert_eq!(answer["pageKind"], "yarn");
    let request = server.await.unwrap();
    assert!(request.starts_with("POST /v1/chat/completions"));
    assert!(
        request
            .to_ascii_lowercase()
            .contains("authorization: bearer secret-key")
    );
}

#[test]
fn api_key_can_come_from_a_file() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("key");
    std::fs::write(&path, "file-key\n").unwrap();
    let path = path.to_string_lossy().to_string();
    let values = HashMap::from([
        ("AI_PROVIDER", "custom"),
        ("AI_BASE_URL", "http://localhost:8642/v1"),
        ("AI_API_KEY_FILE", path.as_str()),
    ]);
    let config = AiConfig::from_lookup(true, |key| values.get(key).map(|v| (*v).to_owned()));
    assert!(config.unwrap().is_some());
    let missing = HashMap::from([
        ("AI_PROVIDER", "custom"),
        ("AI_BASE_URL", "http://localhost:8642/v1"),
    ]);
    assert_eq!(
        AiConfig::from_lookup(true, |key| missing.get(key).map(|v| (*v).to_owned())).unwrap_err(),
        AiError::MissingApiKey
    );
}

#[test]
fn drops_page_gives_the_lazy_diagram_and_pdf_as_assets_not_gallery_photos() {
    use stricknani_server::import_document::AssetKind;
    let draft = draft_from(
        "pattern_drops_diagram.html",
        "https://www.garnstudio.com/pattern.php?id=12752&cid=9",
    );
    let project = &draft.project;
    assert_eq!(project.assets.len(), 2, "{:?}", project.assets);
    assert_eq!(project.assets[0].kind, AssetKind::Diagram);
    assert_eq!(
        project.assets[0].url,
        "https://images.garnstudio.com/drops/mag/273/22/22-chart.jpg"
    );
    assert_eq!(
        project.assets[0].label.as_deref(),
        Some("Diagram for DROPS 273-22")
    );
    assert_eq!(project.assets[1].kind, AssetKind::Pdf);
    assert_eq!(
        project.assets[1].url,
        "https://www.garnstudio.com/downloads/273-22.pdf"
    );
    assert!(
        project.image_urls.iter().all(|url| !url.contains("chart")),
        "{:?}",
        project.image_urls
    );
    assert!(!project.image_urls.is_empty());
}

#[test]
fn bot_protection_page_has_no_assets() {
    let html = "<html><head><title>Just a moment...</title></head><body>Checking your browser</body></html>";
    let url = Url::parse("https://www.garnstudio.com/pattern.php?id=12752&cid=9").unwrap();
    let extraction = extract_html(html, Some(&url), None);
    assert!(extraction.project.assets.is_empty());
}
