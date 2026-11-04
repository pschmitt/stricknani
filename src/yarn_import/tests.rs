use std::time::Duration;

use super::*;
use crate::import_document::{DocumentKind, ProjectImportDraft};
use crate::url_import::extract_html;

fn draft_with(yarns: Vec<YarnImportDraft>) -> ImportDocumentDraft {
    ImportDocumentDraft {
        kind: DocumentKind::Html,
        sha256: String::new(),
        source_url: Some("https://www.garnstudio.com/pattern.php?id=1".into()),
        project: ProjectImportDraft {
            yarns,
            ..Default::default()
        },
        image: None,
        yarn: None,
        page_kind: PageKind::Pattern,
        provenance: Default::default(),
        warnings: Vec::new(),
    }
}

fn named(name: &str, brand: Option<&str>) -> YarnImportDraft {
    YarnImportDraft {
        name: Some(name.into()),
        brand: brand.map(str::to_owned),
        ..Default::default()
    }
}

fn stash(id: i64, name: &str, brand: Option<&str>) -> StashYarn {
    StashYarn {
        id,
        name: name.into(),
        brand: brand.map(str::to_owned),
    }
}

#[test]
fn name_normalisation_moves_publisher_and_group_out_of_the_name() {
    let clean = clean_yarn_name(
        "DROPS MERINO EXTRA FINE von Garnstudio (gehört zur Garngruppe B)",
        None,
    );
    assert_eq!(clean.name, "DROPS Merino Extra Fine");
    assert_eq!(clean.brand.as_deref(), Some("Garnstudio"));
    assert_eq!(clean.note.as_deref(), Some("Garngruppe B"));

    let english = clean_yarn_name("DROPS Air by Garnstudio (belongs to yarn group C)", None);
    assert_eq!(english.name, "DROPS Air");
    assert_eq!(english.note.as_deref(), Some("Yarn group C"));

    let plain = clean_yarn_name("Felted Tweed", Some("Rowan"));
    assert_eq!(plain.name, "Felted Tweed");
    assert_eq!(plain.brand.as_deref(), Some("Rowan"));
    assert_eq!(plain.note, None);

    let drops = clean_yarn_name("DROPS Karisma", None);
    assert_eq!(drops.brand.as_deref(), Some("DROPS"));
}

#[test]
fn normalize_yarn_keeps_the_group_as_a_note() {
    let mut yarn = named("DROPS Alpaca (Garngruppe A)", Some("DROPS"));
    normalize_yarn(&mut yarn);
    assert_eq!(yarn.name.as_deref(), Some("DROPS Alpaca"));
    assert_eq!(yarn.notes.as_deref(), Some("Garngruppe A"));
}

#[test]
fn stash_match_exact_ignores_publisher_words_and_order() {
    let yarns = [named("DROPS Merino Extra Fine", Some("Garnstudio"))];
    let stash = [
        stash(1, "Baby Alpaca Silk", Some("DROPS")),
        stash(7, "Merino Extra Fine", Some("DROPS")),
    ];
    let found = match_stash(&yarns, &stash);
    assert_eq!(found.len(), 1);
    assert_eq!((found[0].index, found[0].yarn_id), (0, 7));
    assert!(found[0].exact);
    assert_eq!(found[0].score, 1.0);
}

#[test]
fn stash_match_fuzzy_survives_typos_but_not_other_brands() {
    let yarns = [named("Merino Extra Fine", Some("DROPS"))];
    let near = [stash(3, "Merino Extra Fin", Some("Garnstudio"))];
    let found = match_stash(&yarns, &near);
    assert_eq!(found.len(), 1);
    assert!(!found[0].exact);
    assert!(found[0].score >= FUZZY_THRESHOLD);

    let other_brand = [stash(4, "Merino Extra Fine", Some("Rowan"))];
    assert!(match_stash(&yarns, &other_brand).is_empty());
}

#[test]
fn stash_match_none_for_a_different_yarn() {
    let yarns = [named("Merino Extra Fine", Some("DROPS"))];
    let stash = [
        stash(1, "Baby Alpaca Silk", Some("DROPS")),
        stash(2, "Merino Extra Fine Superwash", None),
        stash(3, "Air", Some("DROPS")),
    ];
    assert!(match_stash(&yarns, &stash).is_empty());
    assert!(match_stash(&[], &stash).is_empty());
}

#[test]
fn one_stash_yarn_is_suggested_once() {
    let yarns = [
        named("Merino Extra Fine", Some("DROPS")),
        named("Merino Extra Fin", Some("DROPS")),
    ];
    let stash = [stash(9, "Merino Extra Fine", Some("DROPS"))];
    let found = match_stash(&yarns, &stash);
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].index, 0);
}

#[test]
fn finalize_builds_one_clean_list_from_the_loose_yarn_text() {
    let mut draft = draft_with(Vec::new());
    draft.project.yarn =
        Some("DROPS MERINO EXTRA FINE von Garnstudio (gehört zur Garngruppe B)".into());
    draft.project.fiber_content = Some("100% Schurwolle".into());
    finalize_project_yarns(&mut draft);
    assert_eq!(draft.project.yarns.len(), 1);
    let yarn = &draft.project.yarns[0];
    assert_eq!(yarn.name.as_deref(), Some("DROPS Merino Extra Fine"));
    assert_eq!(yarn.brand.as_deref(), Some("Garnstudio"));
    assert_eq!(yarn.fiber_content.as_deref(), Some("100% Schurwolle"));
}

#[test]
fn finalize_merges_duplicates_and_keeps_the_link() {
    let mut a = named("DROPS Air", None);
    a.amount = Some("300-350 g".into());
    let mut b = named("DROPS AIR von Garnstudio", None);
    b.link = Some("https://www.garnstudio.com/yarn.php?show=air".into());
    let mut draft = draft_with(vec![a, b]);
    finalize_project_yarns(&mut draft);
    assert_eq!(draft.project.yarns.len(), 1);
    assert_eq!(draft.project.yarns[0].amount.as_deref(), Some("300-350 g"));
    assert!(draft.project.yarns[0].link.is_some());
}

#[test]
fn product_links_of_the_page_become_yarn_candidates() {
    let html = r#"<html><body>
        <a href="/yarn.php?show=merino-extra-fine&cid=9"><img src="https://images.garnstudio.com/img/shademap/merinoextrafine/preview_2.jpg" alt="Product image DROPS Merino Extra Fine yarn"></a>
        <a href="/yarns.php">Yarns</a>
        <a href="https://other.example/yarn/x">DROPS Fake Yarn</a>
        <a href="/shop">Shop</a></body></html>"#;
    let base = Url::parse("https://www.garnstudio.com/pattern.php?id=12752&cid=9").unwrap();
    let facts = crate::url_import::facts::collect(html, Some(&base));
    let found = candidates_from_links(&facts.links, Some(base.as_str()));
    assert_eq!(found.len(), 1);
    assert_eq!(found[0].name.as_deref(), Some("DROPS Merino Extra Fine"));
    assert!(
        found[0]
            .link
            .as_deref()
            .unwrap()
            .contains("merino-extra-fine")
    );
    assert!(
        found[0]
            .image_url
            .as_deref()
            .unwrap()
            .ends_with("preview_2.jpg")
    );
    // The extractor keeps the links for the model and the heuristics.
    let extraction = extract_html(html, Some(&base), None);
    assert!(extraction.project.extras.contains_key(PAGE_LINKS_EXTRA));
}

const YARN_PAGE: &str = r#"<html><head><title>DROPS Merino Extra Fine</title>
<script type="application/ld+json">{"@context":"https://schema.org","@type":"Product",
"name":"DROPS Merino Extra Fine","brand":{"@type":"Brand","name":"DROPS"},
"image":"https://images.garnstudio.com/img/shademap/merinoextrafine/preview_2.jpg",
"description":"100% Wolle. 50 g = ca 105 m. Garngruppe B."}</script></head>
<body><h1>DROPS Merino Extra Fine</h1><p>Fiber content: 100% Wool</p>
<p>Weight: 50 g</p><p>Length: 105 m</p></body></html>"#;

fn page_details(url: &str) -> Option<YarnDetails> {
    let base = Url::parse(url).ok()?;
    extract_html(YARN_PAGE, Some(&base), None).yarn
}

#[tokio::test]
async fn enrichment_fills_the_yarn_from_its_product_page() {
    let mut yarn = named("DROPS Merino Extra Fine", Some("Garnstudio"));
    yarn.link = Some("https://www.garnstudio.com/yarn.php?show=merino-extra-fine".into());
    yarn.colorway = Some("10 Natural".into());
    yarn.amount = Some("250-300 g".into());
    let mut draft = draft_with(vec![yarn]);
    let report = enrich_with(&mut draft, Duration::from_secs(5), |url| async move {
        page_details(&url)
    })
    .await;
    assert_eq!(
        report,
        EnrichReport {
            attempted: 1,
            enriched: 1
        }
    );
    let yarn = &draft.project.yarns[0];
    assert_eq!(yarn.weight.as_deref(), Some("50 g"));
    assert_eq!(yarn.length.as_deref(), Some("105 m"));
    assert!(yarn.fiber_content.as_deref().unwrap().contains("100%"));
    assert!(
        yarn.image_url
            .as_deref()
            .unwrap()
            .ends_with("preview_2.jpg")
    );
    // What only the pattern knows stays.
    assert_eq!(yarn.colorway.as_deref(), Some("10 Natural"));
    assert_eq!(yarn.amount.as_deref(), Some("250-300 g"));
    assert_eq!(yarn.name.as_deref(), Some("DROPS Merino Extra Fine"));
    assert!(!draft.warnings.iter().any(|code| code == ENRICH_WARNING));
}

#[tokio::test]
async fn failed_or_slow_enrichment_falls_back_to_the_page_data() {
    let mut broken = named("DROPS Air", None);
    broken.link = Some("https://www.garnstudio.com/yarn.php?show=air".into());
    broken.fiber_content = Some("70% Alpaka".into());
    let mut slow = named("DROPS Karisma", None);
    slow.link = Some("https://www.garnstudio.com/yarn.php?show=karisma".into());
    let unsafe_link = YarnImportDraft {
        link: Some("javascript:alert(1)".into()),
        ..named("DROPS Nepal", None)
    };
    let mut draft = draft_with(vec![broken.clone(), slow.clone(), unsafe_link]);
    let report = enrich_with(&mut draft, Duration::from_millis(50), |url| async move {
        if url.contains("karisma") {
            tokio::time::sleep(Duration::from_secs(5)).await;
            return page_details(&url);
        }
        None
    })
    .await;
    assert_eq!(report.attempted, 2);
    assert_eq!(report.enriched, 0);
    assert_eq!(draft.project.yarns[0], broken);
    assert_eq!(draft.project.yarns[1], slow);
    assert!(draft.warnings.iter().any(|code| code == ENRICH_WARNING));
}

#[tokio::test]
async fn enrichment_is_bounded_to_a_few_yarns() {
    let yarns = (0..7)
        .map(|n| YarnImportDraft {
            link: Some(format!("https://www.garnstudio.com/yarn.php?show=y{n}")),
            ..named(&format!("DROPS Yarn {n}"), None)
        })
        .collect();
    let mut draft = draft_with(yarns);
    let report = enrich_with(&mut draft, Duration::from_secs(5), |url| async move {
        page_details(&url)
    })
    .await;
    assert_eq!(report.attempted, MAX_ENRICHED);
}

#[test]
fn model_yarns_need_page_support_for_links_and_numbers() {
    let mut draft = draft_with(Vec::new());
    draft.project.extras.insert(
        PAGE_LINKS_EXTRA.into(),
        links_to_extra(&[PageLinkFact {
            url: "https://www.garnstudio.com/yarn.php?show=air".into(),
            text: "DROPS Air".into(),
            image_url: Some("https://images.garnstudio.com/air.jpg".into()),
            image_alt: None,
        }]),
    );
    let answer = json!([
        {"name": "DROPS Air", "colorway": "01 Natural", "amount": "300-350 g",
         "url": "https://www.garnstudio.com/yarn.php?show=air",
         "imageUrl": "https://images.garnstudio.com/air.jpg"},
        {"name": "DROPS Fantasy", "amount": "999 g", "url": "https://evil.example/x"},
    ]);
    let source = "Materials: DROPS Air 300-350 g colour 01 Natural";
    let accepted = accept_ai_yarns(&mut draft, &answer, source, ai_merge::Grounding::PageText);
    assert_eq!(accepted, 1, "the unsupported yarn is dropped");
    let yarn = &draft.project.yarns[0];
    assert_eq!(
        yarn.link.as_deref(),
        Some("https://www.garnstudio.com/yarn.php?show=air")
    );
    assert!(yarn.image_url.is_some());
    assert_eq!(yarn.amount.as_deref(), Some("300-350 g"));
    strip_internal_extras(&mut draft);
    assert!(!draft.project.extras.contains_key(PAGE_LINKS_EXTRA));
}

#[tokio::test]
async fn stash_only_contains_the_importing_users_own_yarns() {
    let pool = crate::db::connect("sqlite::memory:").await.unwrap();
    let admin: i64 = sqlx::query_scalar(
        "INSERT INTO users (email, password_hash, is_admin) VALUES ('a@x.test','h',1) RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    let other: i64 = sqlx::query_scalar(
        "INSERT INTO users (email, password_hash) VALUES ('o@x.test','h') RETURNING id",
    )
    .fetch_one(&pool)
    .await
    .unwrap();
    for (owner, name) in [(admin, "Admin wool"), (other, "Other wool")] {
        sqlx::query(
            "INSERT INTO yarns (owner_id,name,created_at,updated_at) VALUES (?,?,'2026-01-01T00:00:00Z','2026-01-01T00:00:00Z')",
        )
        .bind(owner)
        .bind(name)
        .execute(&pool)
        .await
        .unwrap();
    }
    let stash = load_stash(&pool, admin).await;
    assert_eq!(stash.len(), 1);
    assert_eq!(stash[0].name, "Admin wool");
}
