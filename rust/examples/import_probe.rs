//! Live smoke probe for the URL import extractor: `cargo run --example import_probe -- URL...`.
//!
//! Fetches each URL once through the production safe fetcher and prints what the rule-based
//! extraction (no AI, no Ravelry API) found. For manual checks from a build host only.

use stricknani_server::{
    imports::{fetch_url, normalize_input_url},
    url_import::{charset::decode_html, extract_html},
};

#[tokio::main(flavor = "current_thread")]
async fn main() {
    for input in std::env::args().skip(1) {
        let url = normalize_input_url(&input);
        let fetched = match fetch_url(&url).await {
            Ok(fetched) => fetched,
            Err(error) => {
                println!("{{\"url\":{input:?},\"error\":\"{error}\"}}");
                continue;
            }
        };
        let html = decode_html(&fetched.body, fetched.content_type.as_deref());
        if std::env::var_os("PROBE_TEXT").is_some() {
            let facts =
                stricknani_server::url_import::facts::collect(&html, Some(&fetched.final_url));
            println!("{}", facts.text.chars().take(4000).collect::<String>());
        }
        let extraction = extract_html(&html, Some(&fetched.final_url), fetched.title.as_deref());
        let value = serde_json::json!({
            "url": input,
            "bytes": fetched.body.len(),
            "kind": extraction.kind,
            "warnings": extraction.warnings,
            "yarn": extraction.yarn,
            "project": {
                "name": extraction.project.name,
                "designer": extraction.project.designer,
                "category": extraction.project.category,
                "needles": extraction.project.needles,
                "stitchSample": extraction.project.stitch_sample,
                "sizes": extraction.project.sizes,
                "difficulty": extraction.project.difficulty,
                "yarn": extraction.project.yarn,
                "otherMaterials": extraction.project.other_materials,
                "steps": extraction.project.steps.len(),
                "images": extraction.project.image_urls.len(),
            },
            "provenance": extraction.provenance,
        });
        println!("{value}");
        tokio::time::sleep(std::time::Duration::from_secs(2)).await;
    }
}
