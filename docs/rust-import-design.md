# Rust URL import foundation

`rust/src/imports.rs` provides a bounded fetch layer for routes that import a public URL.
It is a transport and small metadata-extraction foundation; routes, provider integrations,
background work, and image import workflows remain separate work.

## Request policy

- Accept only absolute `http` and `https` URLs with a host. Reject credentials and fragments.
- Resolve each domain for every redirect hop. Reject the whole answer set if any address is
  private, shared, loopback, link-local, reserved, documentation-only, multicast, unspecified,
  IPv4-mapped to a blocked IPv4 address, or otherwise outside globally routable IPv6 unicast.
- Pin all approved DNS answers into the Reqwest client for that hop. Literal IPs are validated
  and used directly.
- Follow at most five redirects manually, validating and resolving each target before connecting.
- Bound each complete fetch to ten seconds and each response body to five MiB. Body streaming
  checks the limit even when `Content-Length` is absent or incorrect.
- The transport explicitly disables proxies and automatic redirects, sends no cookies or auth,
  disables automatic referrers, and requests identity encoding. Reqwest is configured without
  default features, cookies, compression, or system proxy support.
- Errors contain only a typed reason and, for HTTP errors, the status code. The module does not
  log URLs. `FetchResult.final_url` may include private query values; callers must not log it.

Production transport uses Reqwest 0.13.5 with `rustls`, `stream`, and `http2` features. The
`stream` feature supports incremental body limits; `rustls` supplies HTTPS without a native TLS
library requirement. The existing Rust URL parser handles URL syntax.

## Parsing boundary

`FetchResult` retains the bounded body and content type for downstream image or PDF parsers.
For HTML and plain text it also returns decoded text; HTML is sanitized with the existing
Ammonia dependency before basic visible-text extraction, and a small title scan supplies the
`<title>` value. This is intentionally not a replacement for the Python app's richer site
parsers. Sites that require browser TLS fingerprint impersonation may reject standard Reqwest;
the fetch layer reports a redacted transport/status failure instead of weakening its security
policy.

## Testing

`rust/tests/imports.rs` injects resolver and transport fixtures. It covers URL rejection,
address ranges, mixed DNS answers, DNS pinning for redirects, redirect and body limits, error
redaction, and the HTML/binary response boundary. These tests do not contact public hosts.
`rust/tests/import_document.rs` covers structured HTML extraction, text and Markdown drafts,
image metadata, digest shape, size limits, and malformed or unsupported input without network
access.

## Local document extraction

The Rust document parser reads already-fetched or uploaded bytes into a reviewable import draft.
It accepts HTML, plain text, Markdown, PDF, and supported image formats. Drafts carry the SHA-256
digest of the original bytes, source URL, project fields, yarn details, recipe steps, image URL
suggestions, and image format/dimensions. HTML extraction reads Open Graph and standard description
metadata, canonical URLs, image references, and common schema.org Recipe fields such as name,
description, category, ingredients, instructions, and yarn properties. Text and PDF drafts keep a
bounded full-text extra; PDF extraction examines at most 30 pages and stores at most 200,000 text
characters. Image parsing reads dimensions from the header without decoding pixels into an image
buffer.

The parser has a five MiB input cap, limits HTML JSON-LD blocks to 16 and suggested image links to
10, and returns typed redacted errors for invalid or unsupported input. It does not perform OCR,
site-specific knitting-pattern parsing, remote image downloads, AI extraction, or persistence.
Extracted URLs remain untrusted hints and must pass the safe fetch policy before use. Callers should
run PDF parsing off Tokio worker threads; the parser itself is synchronous and has no network or
database access.

HTML parsing uses scraper 0.29.0 with its atomic feature. PDF text extraction uses lopdf 0.45's
decompression limits: at most one MiB per stream during loading, at most 30 selected pages, and
at most 256 KiB of decoded page content per selected page. The retained text is capped at 200,000
characters. These are per-stream and per-page limits, not a strict total parser memory or CPU
budget: the PDF loader still constructs the document object table, and a document can contain many
streams. External PDFs must remain behind the parent application's worker process with hard memory
and wall-time limits. Image and sha2 dependencies provide image-header facts and content digests.

## Import endpoints

The import service accepts the Android URL-encoded POST /projects/import request with type=url,
url, and use_ai. It requires a Bearer token. Remote URL requests return service unavailable until
the explicit external-import flag is enabled. The current route performs extraction only; even
when use_ai is requested, the response reports is_ai_enhanced=false and ai_fallback=false. It
returns the source content SHA-256 as duplicate-review evidence and never merges or deletes records
based on the digest.

Browser users can preview URL, text, and uploaded-file imports at /imports. The browser routes
require the existing session cookie and CSRF token. Project and yarn creation call the normal
versioned API router with the browser session token; the import module does not write domain SQL.
Remote images remain suggestions in the preview. Project creation now accepts the existing v1
`image_urls` list for gallery media and `steps[*].image_urls` for the newly created steps. Image
imports are create-only: project updates ignore those arrays, matching Python API v1. The first
successfully prepared gallery image becomes the primary title image; later gallery images remain
title-kind, nonprimary rows, and step images attach to the corresponding newly inserted step.

Image imports use the shared `imports::fetch_url` policy, so DNS answers are checked and pinned on
every manually followed redirect, with no proxy inheritance or URL-bearing error logs. The media
module applies its existing bounded image decoder and thumbnail/file-storage path, then persists
through short owner-checked transactions after project creation. Invalid, unsupported, duplicate,
too-small, oversized, and failed downloads are skipped best-effort; project creation still returns
201 with any valid media. Exact-byte SHA-256 duplicates in the request are removed. Near-duplicate
SSIM behavior from Python is deliberately not claimed or implemented here.

Image-import resource bounds are at most 10 successful images per gallery/step target, 40 URL
attempts total, five MiB per response, 25 MiB raw bodies retained across the request, and an
eight-second batch deadline. Each fetch and image decode must finish within that shared deadline;
processing stops as soon as it expires. The shared fetcher additionally caps each individual URL
fetch at ten seconds and five MiB, and follows at most five redirects. The shorter batch deadline
keeps create requests within the Android API client's ten-second timeout; imports remain best-effort
and may skip candidates that do not finish in time. `AppState.external_imports` defaults to false. When
disabled, a create request carrying image URLs keeps the existing 501 response and performs no
fetch or database write; production wiring may enable it only through the explicit
`RUST_EXTERNAL_IMPORTS_ENABLED` setting. Tests inject an image fetcher and use in-memory SQLite and
temporary media directories; they do not contact public hosts.
