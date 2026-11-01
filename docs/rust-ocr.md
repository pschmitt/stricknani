# Rust OCR core

The OCR core accepts image bytes that the caller has already resolved and
authorized. It does not interpret media URLs, access storage, or make ownership
decisions. The `/utils/ocr` route should parse `src`, resolve the media record,
check its owner, and only then call the service.

```rust,ignore
let text = state
    .ocr
    .recognize(authorized_image_bytes, request.lang.as_deref())
    .await?;
```

`OcrService::new()` configures the optional `tesseract` executable. Clone one
service into application state so requests share its two-job limit. Tests can
use `OcrService::with_transport(Arc<dyn OcrTransport>)` to supply deterministic
results without starting a process.

The default language is `deu+eng`. Language input is limited to a single
ASCII token made of alphanumerics, `_`, `+`, and `-`, with an alphanumeric
first byte. The image input limit is 25 MiB; normalized image decoding also
enforces dimension, allocation, and pixel limits. Tesseract runs without a
shell, has no stdin or captured stderr, has a 30-second deadline, and returns
at most 4 MiB of stdout. Its temporary PNG is private and removed on success
or failure. Cancellation of an HTTP request does not detach an unbounded
process: its worker retains the semaphore slot until timeout handling has
killed and reaped Tesseract.

Errors use stable, redacted variants (`OcrError`) that contain no path, stderr,
or command output. The route can map `NotAvailable` to 501,
`FileTooLarge`/`InvalidImage`/`InvalidLanguage`/`OutputTooLarge` to 400, and
`TimedOut`/`Failed`/`Internal` to a generic 500 response. Do not expose the
underlying OS or image-decoder errors.

The old Python implementation caches OCR output next to media using media
mtime and language. The Rust core intentionally has no disk cache: the future
route supplies bytes, and a digest-keyed cache can be added when its ownership,
invalidation, and `force` semantics are designed at the storage boundary.

## Server integration

`RUST_OCR_ENABLED=true` enables authenticated `POST /utils/ocr`. It accepts
origin-relative registered project/yarn media paths only; foreign media is
reported as missing. Browser requests also require `X-CSRF-Token`. The Nix
candidate supplies Tesseract with German and English language data. The default
is disabled. No network service or Python runtime is used.
