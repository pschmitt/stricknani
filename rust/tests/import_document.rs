use std::io::Cursor;

use image::{DynamicImage, ImageFormat};
use stricknani_server::import_document::{
    DocumentInput, DocumentKind, DocumentParseError, parse_document,
};
use url::Url;

fn source_url() -> Url {
    Url::parse("https://patterns.example/knit/cardigan").expect("fixture URL is valid")
}

#[test]
fn extracts_html_metadata_schema_recipe_fields_and_resolved_links() {
    let html = br#"
        <html><head>
          <meta property="og:title" content="Cable cardigan">
          <meta name="description" content="A warm cardigan pattern.">
          <meta property="og:site_name" content="Knit Lab">
          <link rel="canonical" href="../cardigan">
          <script type="application/ld+json">
          {
            "@context": "https://schema.org",
            "@type": "Recipe",
            "name": "Cardigan recipe",
            "recipeCategory": "Sweater",
            "recipeIngredient": ["Worsted yarn", "4 mm needles"],
            "yarns": [{"name":"Cloud Wool","brand":{"name":"Acme"},"weight":"100 g","url":"/yarn/cloud"}],
            "recipeInstructions": [
              {"@type":"HowToStep","name":"Cast on","text":"Cast on 80 stitches.","image":"/steps/one.jpg"},
              {"@type":"HowToStep","text":"Work the ribbing."}
            ]
          }
          </script>
        </head><body><img src="/cover.jpg"><p>Visible fallback text.</p></body></html>
    "#;
    let source = source_url();

    let parsed = parse_document(DocumentInput {
        bytes: html,
        content_type: Some("text/html; charset=utf-8"),
        filename: None,
        source_url: Some(&source),
        fetched_title: None,
        fetched_text: None,
    })
    .expect("HTML fixture parses");

    assert_eq!(parsed.kind, DocumentKind::Html);
    assert_eq!(parsed.project.name.as_deref(), Some("Cable cardigan"));
    assert_eq!(
        parsed.project.description.as_deref(),
        Some("A warm cardigan pattern.")
    );
    assert_eq!(parsed.project.category.as_deref(), Some("Sweater"));
    assert_eq!(parsed.project.brand.as_deref(), Some("Knit Lab"));
    assert_eq!(
        parsed.project.link.as_deref(),
        Some("https://patterns.example/cardigan")
    );
    assert_eq!(
        parsed.project.other_materials.as_deref(),
        Some("Worsted yarn\n4 mm needles")
    );
    assert_eq!(parsed.project.yarns.len(), 1);
    assert_eq!(parsed.project.yarns[0].name.as_deref(), Some("Cloud Wool"));
    assert_eq!(parsed.project.yarns[0].brand.as_deref(), Some("Acme"));
    assert_eq!(
        parsed.project.yarns[0].link.as_deref(),
        Some("https://patterns.example/yarn/cloud")
    );
    assert_eq!(parsed.project.steps.len(), 2);
    assert_eq!(parsed.project.steps[0].title.as_deref(), Some("Cast on"));
    assert_eq!(parsed.project.steps[0].step_number, 1);
    assert_eq!(
        parsed.project.steps[0].images,
        ["https://patterns.example/steps/one.jpg"]
    );
    assert_eq!(
        parsed.project.image_urls,
        ["https://patterns.example/cover.jpg"]
    );
    assert_eq!(parsed.sha256.len(), 64);
}

#[test]
fn markdown_and_plain_text_produce_bounded_project_drafts() {
    let markdown = b"# Mittens\n\nKnit these warm mittens in wool.";
    let parsed = parse_document(DocumentInput {
        bytes: markdown,
        content_type: Some("text/markdown"),
        filename: Some("ignored.md"),
        source_url: Some(&source_url()),
        fetched_title: None,
        fetched_text: None,
    })
    .expect("Markdown fixture parses");
    assert_eq!(parsed.kind, DocumentKind::Markdown);
    assert_eq!(parsed.project.name.as_deref(), Some("Mittens"));
    assert_eq!(
        parsed.project.description.as_deref(),
        Some("# Mittens\n\nKnit these warm mittens in wool.")
    );
    assert!(parsed.project.extras.contains_key("full_text"));

    let plain = parse_document(DocumentInput {
        bytes: b"Free-form pattern notes",
        content_type: Some("text/plain"),
        filename: Some("notes.txt"),
        source_url: None,
        fetched_title: None,
        fetched_text: None,
    })
    .expect("plain text fixture parses");
    assert_eq!(plain.kind, DocumentKind::PlainText);
    assert_eq!(plain.project.name.as_deref(), Some("notes"));
    assert_eq!(
        plain.project.description.as_deref(),
        Some("Free-form pattern notes")
    );
}

#[test]
fn image_parser_reports_format_dimensions_and_stable_digest() {
    let mut bytes = Vec::new();
    DynamicImage::new_rgb8(2, 3)
        .write_to(&mut Cursor::new(&mut bytes), ImageFormat::Png)
        .expect("fixture PNG encodes");

    let parsed = parse_document(DocumentInput {
        bytes: &bytes,
        content_type: Some("image/png"),
        filename: Some("sample.png"),
        source_url: None,
        fetched_title: None,
        fetched_text: None,
    })
    .expect("image fixture parses");

    assert_eq!(parsed.kind, DocumentKind::Image);
    assert_eq!(
        parsed.image,
        Some(stricknani_server::import_document::ImageFacts {
            format: "png".to_owned(),
            width: 2,
            height: 3,
        })
    );
    assert_eq!(parsed.sha256.len(), 64);
}

#[test]
fn malformed_pdf_unsupported_content_and_oversize_inputs_fail_closed() {
    assert_eq!(
        parse_document(DocumentInput {
            bytes: b"%PDF-not-a-pdf",
            content_type: Some("application/pdf"),
            filename: None,
            source_url: None,
            fetched_title: None,
            fetched_text: None,
        })
        .err(),
        Some(DocumentParseError::InvalidDocument)
    );
    assert_eq!(
        parse_document(DocumentInput {
            bytes: b"binary",
            content_type: Some("application/octet-stream"),
            filename: None,
            source_url: None,
            fetched_title: None,
            fetched_text: None,
        })
        .err(),
        Some(DocumentParseError::UnsupportedType)
    );
    let too_large = vec![b'x'; 5 * 1024 * 1024 + 1];
    assert_eq!(
        parse_document(DocumentInput {
            bytes: &too_large,
            content_type: Some("text/plain"),
            filename: None,
            source_url: None,
            fetched_title: None,
            fetched_text: None,
        })
        .err(),
        Some(DocumentParseError::TooLarge)
    );
}
