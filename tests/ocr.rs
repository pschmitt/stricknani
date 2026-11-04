use std::sync::{Arc, Mutex};

use stricknani_server::ocr::{
    DEFAULT_OCR_LANG, MAX_OCR_BYTES, OcrError, OcrFuture, OcrLanguage, OcrService, OcrTransport,
};

#[derive(Default)]
struct FixtureTransport {
    languages: Mutex<Vec<String>>,
}

impl OcrTransport for FixtureTransport {
    fn recognize<'a>(&'a self, _image_bytes: Vec<u8>, language: OcrLanguage) -> OcrFuture<'a> {
        self.languages
            .lock()
            .expect("fixture mutex should not be poisoned")
            .push(language.as_str().to_owned());
        Box::pin(async { Ok("hello from OCR\n".to_owned()) })
    }
}

#[tokio::test]
async fn service_passes_default_and_requested_languages_to_transport() {
    let transport = Arc::new(FixtureTransport::default());
    let service = OcrService::with_transport(transport.clone());

    assert_eq!(
        service
            .recognize(vec![1, 2, 3], None)
            .await
            .expect("fixture OCR should succeed"),
        "hello from OCR\n"
    );
    service
        .recognize(vec![4, 5, 6], Some("eng+deu"))
        .await
        .expect("fixture OCR should succeed");

    let languages = transport
        .languages
        .lock()
        .expect("fixture mutex should not be poisoned")
        .clone();
    assert_eq!(
        languages,
        vec![DEFAULT_OCR_LANG.to_owned(), "eng+deu".to_owned()]
    );
}

#[tokio::test]
async fn invalid_language_is_rejected_before_transport() {
    let transport = Arc::new(FixtureTransport::default());
    let service = OcrService::with_transport(transport.clone());

    assert_eq!(
        service.recognize(vec![1], Some("--version")).await,
        Err(OcrError::InvalidLanguage)
    );
    assert!(
        transport
            .languages
            .lock()
            .expect("fixture mutex should not be poisoned")
            .is_empty()
    );
}

#[tokio::test]
async fn byte_limit_is_enforced_before_transport() {
    let transport = Arc::new(FixtureTransport::default());
    let service = OcrService::with_transport(transport.clone());

    assert_eq!(
        service.recognize(vec![0; MAX_OCR_BYTES + 1], None).await,
        Err(OcrError::FileTooLarge)
    );
    assert!(
        transport
            .languages
            .lock()
            .expect("fixture mutex should not be poisoned")
            .is_empty()
    );
}

#[test]
fn language_validation_only_accepts_tesseract_language_tokens() {
    for language in ["script/Latin", "eng;touch", "--version", ""] {
        assert_eq!(
            OcrLanguage::parse(Some(language)),
            Err(OcrError::InvalidLanguage),
            "language: {language:?}"
        );
    }

    assert_eq!(
        OcrLanguage::parse(Some("deu+eng"))
            .expect("language expression should be valid")
            .as_str(),
        "deu+eng"
    );
    assert_eq!(
        OcrLanguage::parse(Some("eng"))
            .expect("language token should be valid")
            .as_str(),
        "eng"
    );
}
