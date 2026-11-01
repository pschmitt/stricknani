# Isolated document parsing worker

`import_document::parse_document` processes bytes from external import sources. The Rust server
must call it through `document_worker::parse_isolated`, which starts the same server executable in
the hidden `document-worker` mode. The child handles exactly one JSON request from stdin and one
JSON response on stdout. The worker code does not fetch URLs, persist data, log request contents,
or write diagnostics to stderr.

The request includes document bytes encoded as base64 plus optional content type, filename, source
URL, fetched title, and fetched text. The parent validates field bounds and caps serialized input
at 8 MiB; source document bytes are capped at 5 MiB. The worker response is capped at 2 MiB. Errors
use a small fixed enum and never include the source URL, content, or operating-system error text.

The parent allows at most two child processes at once. It uses the explicit executable path, no
shell, a cleared environment, a ten-second wall-clock timeout, a null stderr, and `kill_on_drop`.
On timeout it kills and reaps the child. Linux workers set `RLIMIT_AS` to 512 MiB, `RLIMIT_CPU` to five seconds, and
`RLIMIT_CORE` to zero before parsing. If those limits cannot be applied, or the binary is running
on a non-Linux platform, the worker returns a redacted error and refuses to parse.

The parser records a source URL only as metadata after URL validation and makes no network
requests. Any later import fetch must go through the server's separate URL safety policy. The child
process inherits its normal OS permissions; this worker is a resource-limited process boundary,
not an operating-system network or filesystem sandbox.

The server binary must dispatch `document-worker` synchronously before creating a Tokio runtime,
then call `document_worker::run_stdio()`. The module requires the Rustix `process` feature for
resource limits and Tokio's `process` and `sync` features for child management and the concurrency
semaphore. `rust/tests/document_worker.rs` exercises the actual CLI worker with a plain-text draft,
an invalid PDF, malformed JSON, oversized JSON, and an oversized document rejected by the parent.
