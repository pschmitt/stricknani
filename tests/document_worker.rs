use std::io::Write;
use std::path::Path;
use std::process::{Command, Stdio};

use stricknani_server::document_worker::{DocumentRequest, WorkerError, parse_isolated};
use stricknani_server::import_document::DocumentKind;

const WORKER_COMMAND: &str = "document-worker";
const MAX_REQUEST_BYTES: usize = 8 * 1024 * 1024;

fn worker_request(bytes: &[u8], content_type: &str, filename: &str) -> DocumentRequest {
    DocumentRequest {
        bytes: bytes.to_vec(),
        content_type: Some(content_type.to_owned()),
        filename: Some(filename.to_owned()),
        source_url: Some("https://example.invalid/pattern".to_owned()),
        fetched_title: None,
        fetched_text: None,
    }
}

fn run_worker_stdio(input: &[u8]) -> std::process::Output {
    let mut child = Command::new(env!("CARGO_BIN_EXE_stricknani-server"))
        .arg(WORKER_COMMAND)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::null())
        .spawn()
        .expect("worker binary should start");
    child
        .stdin
        .take()
        .expect("worker stdin should be piped")
        .write_all(input)
        .expect("worker should accept bounded input");
    child
        .wait_with_output()
        .expect("worker process should be reaped")
}

#[tokio::test]
async fn isolated_worker_returns_a_text_draft_without_fetching_its_source_url() {
    let executable = Path::new(env!("CARGO_BIN_EXE_stricknani-server"));
    let draft = parse_isolated(
        executable,
        worker_request(
            b"Cast on 40 stitches and work in ribbing.",
            "text/plain",
            "ribbed-hat.txt",
        ),
    )
    .await
    .expect("plain text should parse in the worker");

    assert_eq!(draft.kind, DocumentKind::PlainText);
    assert_eq!(draft.project.name.as_deref(), Some("ribbed-hat"));
    assert_eq!(
        draft.project.link.as_deref(),
        Some("https://example.invalid/pattern")
    );
    assert!(
        draft
            .project
            .description
            .as_deref()
            .is_some_and(|text| text.contains("Cast on 40 stitches"))
    );
}

#[tokio::test]
async fn isolated_worker_returns_a_redacted_error_for_invalid_pdf() {
    let executable = Path::new(env!("CARGO_BIN_EXE_stricknani-server"));
    let error = parse_isolated(
        executable,
        worker_request(
            b"%PDF-not a valid document",
            "application/pdf",
            "broken.pdf",
        ),
    )
    .await
    .expect_err("invalid PDF must be rejected");

    assert_eq!(error, WorkerError::InvalidDocument);
    assert_eq!(error.to_string(), "document could not be decoded");
}

#[test]
fn worker_rejects_malformed_json_without_echoing_input() {
    let output = run_worker_stdio(b"{\"source_url\":\"https://private.example/token\"");

    assert!(output.status.success());
    assert_eq!(output.stdout, br#"{"error":"invalid_request"}"#);
    assert!(output.stderr.is_empty());
}

#[test]
fn worker_rejects_oversized_json_before_deserializing_it() {
    let input = vec![b' '; MAX_REQUEST_BYTES + 1];
    let output = run_worker_stdio(&input);

    assert!(output.status.success());
    assert_eq!(output.stdout, br#"{"error":"request_too_large"}"#);
    assert!(output.stderr.is_empty());
}

#[tokio::test]
async fn parent_rejects_document_bytes_over_the_parser_limit_before_spawning() {
    let executable = Path::new(env!("CARGO_BIN_EXE_stricknani-server"));
    let request = worker_request(&vec![b'x'; 5 * 1024 * 1024 + 1], "text/plain", "large.txt");

    assert_eq!(
        parse_isolated(executable, request).await,
        Err(WorkerError::RequestTooLarge)
    );
}
