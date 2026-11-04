# Rust URL import foundation

`src/imports.rs` provides the bounded public-URL fetch layer used by the Android import
endpoint, browser import preview, and create-time image import. The document parser runs through
`document_worker`; `import_service` maps extracted drafts to Android responses and reviewable
browser forms. Optional AI enhancement is wired through the same service when enabled and
explicitly requested by the user.

## Request policy

- Accept only absolute `http` and `https` URLs with a host. Reject credentials and fragments.
- Resolve each domain for every redirect hop. Reject the whole answer set if any address is
  private, shared, loopback, link-local, reserved, documentation-only, multicast, unspecified,
  IPv4-mapped to a blocked IPv4 address, or otherwise outside globally routable IPv6 unicast.
- Pin all approved DNS answers into the Reqwest client for that hop. Literal IPs are validated
  and used directly.
- Follow at most five redirects manually, validating and resolving each target before connecting.
- Bound each complete fetch to fifteen seconds and each response body to five MiB. Body streaming
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
`<title>` value. The richer extraction lives in `url_import` (see below). Sites that require browser TLS
fingerprint impersonation reject standard Reqwest; the fetch layer reports a redacted
transport/status failure instead of weakening its security policy.

## Testing

`tests/imports.rs` injects resolver and transport fixtures. It covers URL rejection,
address ranges, mixed DNS answers, DNS pinning for redirects, redirect and body limits, error
redaction, and the HTML/binary response boundary. These tests do not contact public hosts.
`tests/import_document.rs` covers structured HTML extraction, text and Markdown drafts,
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

The import service accepts the Android URL-encoded `POST /projects/import` request with
`type=url`, `url`, and `use_ai`. It requires a Bearer token. Remote URL requests return service
unavailable until the explicit external-import flag is enabled. When the AI feature is also
enabled and `use_ai` is true, the service may enhance the draft through the configured provider;
the response distinguishes `ai_requested`, `is_ai_enhanced`, and `ai_fallback`. Failures preserve
the local draft and do not mark it as AI-enhanced. The response also returns the source content
SHA-256 as duplicate-review evidence. The digest never merges or deletes records by itself.

Browser users can preview URL, text, and uploaded-file imports at `/imports`. The browser routes
require the existing session cookie and CSRF token. URL preview is subject to the external-import
feature flag; text and file parsing do not fetch a URL. Optional AI enhancement requires both its
server feature flag and explicit form selection. Project and yarn creation call the normal
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
fetch at fifteen seconds and five MiB, and follows at most five redirects. The shorter batch deadline
keeps create requests within the Android API client's ten-second timeout; imports remain best-effort
and may skip candidates that do not finish in time. `AppState.external_imports` defaults to false. When
disabled, a create request carrying image URLs keeps the existing 501 response and performs no
fetch or database write; production wiring may enable it only through the explicit
`RUST_EXTERNAL_IMPORTS_ENABLED` setting. Tests inject an image fetcher and use in-memory SQLite and
temporary media directories; they do not contact public hosts.

`tests/import_service.rs` covers bearer authorization, the disabled remote-URL path, browser
CSRF, text and AI previews, file parsing, AI provenance through creation, and bounded source text.
`tests/image_import.rs` covers safe fetch policy, best-effort create-time media import, exact
byte deduplication, and media persistence with injected transports. `tests/document_worker.rs`
and `tests/import_document.rs` exercise the isolated parser boundary and local formats. OCR
is a separate authorized-media utility at `/utils/ocr`; it is not automatically run during import.

## URL import pipeline

`import_service::import_url` is the one entry point for importing a project or a yarn from a link
(`POST /api/v2/imports/preview` with `type: "url"` and `target: "project" | "yarn"`).

1. **Fetch.** A bare host gets `https://`, a fragment is dropped, then the safe fetcher runs with
   the checks above, a desktop-browser User-Agent, `Accept`, `Accept-Language` (German first) and
   `Sec-Fetch-*` headers, and a 15 second limit. Bytes are decoded with the declared or sniffed
   charset (ISO-8859-1 and Windows-1252 shops included).
2. **Extract** (inside the isolated document worker, `url_import::extract`). Facts come from
   JSON-LD (including `@graph`, with raw newlines repaired), microdata, OpenGraph and `<meta>`
   tags. Product, ProductGroup, HowTo, Recipe, Article and CreativeWork nodes become a yarn
   draft (`draft.yarn`: name, brand, colorway, fiber, weight category, grams and meters per skein,
   needles, gauge, care, price, link, photos) and a project draft (name, designer, category,
   needles, stitch sample, sizes, difficulty, materials, yarn, steps, tags, photos). Gaps are
   filled from the page text: `label: value` lines, table and definition rows, `SIZES:` style
   header-and-body sections (DROPS), and German and English patterns for fiber percentages,
   `125 m / 50 g`, needle ranges and gauge. A per-field `provenance` map records `structured`,
   `site`, `text` or `ai`. The page kind (`yarn`, `pattern`, `unknown`) is scored from markup, URL
   path, title and text.
3. **Pictures.** `og:image`, schema images and `<img>` candidates (largest `srcset`, lazy-loading
   attributes) are scored; logos, icons, payment badges, tracking pixels and tiny images are
   dropped and size variants (`-300x300`, `_600x`) are merged. `GET /api/v2/imports/image` serves
   a same-origin thumbnail through the safe fetcher so the clients can show the candidates without
   contacting the site; picked URLs are fetched again on create (`image_urls`, now also for yarns).
4. **Site data.** DROPS pages get their designer and clean title. Ravelry library pages are
   enriched from the API when credentials are configured (see `rust-ai.md`).
5. **AI** (optional) fills gaps with the validation described in `rust-ai.md`.
6. **Warnings** tell the UI what to say: `no_structured_data`, `little_page_text`,
   `looks_like_yarn_page`, `looks_like_pattern_page`, `not_a_yarn_page`, `ravelry_api_failed`.

Fetch failures map to `import_blocked` (HTTP 401, 403, 429, 503 from the site),
`import_not_found` (404, 410) and `import_fetch_failed`.

### Known limits

- No TLS fingerprint impersonation: no maintained Rust option exists that builds in the Nix
  sandbox without a BoringSSL toolchain, so sites that block by TLS fingerprint answer 403 or
  468 (seen: Shopify shops behind Cloudflare bot protection, an Imperva-protected shop,
  yarnspirations.com). Pasting the page text covers those.
- Pages that render their content with JavaScript yield little text (`little_page_text`).
- Pattern instructions are only imported when the page marks them up as `HowTo`/`Recipe` steps;
  long free-form instructions (DROPS) are not split into steps.
- The Ravelry API mapping follows the public field names; gauge is only taken from the API when
  `gauge_description` is set, otherwise from the page text.

### Tests

`tests/url_import.rs` runs golden fixtures from `tests/fixtures/url_import/`: an English
JSON-LD shop, a German ISO-8859-1 microdata shop, a ProductGroup with `PropertyValue` specs, two
DROPS layouts, a blog `HowTo`, and Ravelry pattern and yarn pages with their API JSON. They also
cover AI merging against a scripted transport and a plain-HTTP OpenAI-compatible server. No test
touches the network; `cargo run --example import_probe -- URL...` is the manual live probe.
