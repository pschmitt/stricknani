# Rust AI import client

`rust/src/ai.rs` is a bounded, optional client for enhancing an already parsed
project draft. It uses chat completions with JSON mode, which the Python import
paths already use and which is supported by the OpenAI-compatible OpenAI,
OpenRouter, and Groq chat endpoints. OpenAI recommends Structured Outputs when
the selected model supports it; this client deliberately starts with JSON mode
to preserve the common provider contract. See the [OpenAI Chat Completions
reference](https://developers.openai.com/api/reference/resources/chat/subresources/completions/methods/create),
[OpenRouter chat API](https://openrouter.ai/docs/api/api-reference/chat/send-chat-completion-request),
and [Groq structured output guide](https://console.groq.com/docs/structured-outputs).

## Configuration

The server integration is off unless `RUST_AI_ENABLED=true` and the user selects
AI for that import. When disabled,
`AiConfig::from_lookup(false, ...)` returns `None` without reading any key. When
enabled, provider resolution is:

1. `AI_PROVIDER`, default `openai`; accepted values are `openai`, `openrouter`,
   and `groq`. Unknown values fail with a redacted configuration error.
2. API key: `AI_API_KEY`, then the selected provider key
   (`OPENAI_API_KEY`, `OPENROUTER_API_KEY`, or `GROQ_API_KEY`), then
   `OPENAI_API_KEY` as a compatibility fallback.
3. Endpoint: `AI_BASE_URL`, otherwise the provider's built-in API base.
   The base must be an HTTP(S) URL without user information, query, or fragment.
   Non-HTTPS custom endpoints are accepted only for `localhost` or a literal
   loopback address. This is an administrator-controlled endpoint; never derive
   it from an import URL.
4. Model: `AI_MODEL`, otherwise the existing provider default (`gpt-4o-mini`,
   `openai/gpt-4o-mini`, or `llama-3.3-70b-versatile`).

The key is held in a zeroizing string, has a redacted `Debug`, and is marked
sensitive in the Authorization header. The HTTP client disables environment
proxy inheritance and redirects. Errors contain only a fixed category or HTTP
status; response bodies and provider error messages are not surfaced or logged.

## Request and response boundary

The route/caller must require both the global feature setting and an explicit
per-import AI request before calling the client. The client accepts at most
12,000 Unicode characters of source text, a serialized draft up to 128 KiB, and
three explicitly supplied images of at most 2,000,000 bytes each. Supported
image media types are JPEG, PNG, WebP, and GIF. It never fetches URLs.

The request is a non-streaming Chat Completions POST with `model`, system/user
messages, `max_tokens: 4000`, `temperature: 0.1`, and
`response_format: {"type":"json_object"}`. Image bytes are encoded as data
URLs in the user message. The total request is bounded by the input limits. The
client has a 30-second total request timeout (10 seconds to connect) and reads
no more than 1 MiB of response body.

The response must contain a string at `choices[0].message.content` whose value
parses as the draft wire shape. Missing fields preserve the locally parsed
draft. The original source link is never overwritten. Gallery, step, and yarn
image URLs are kept only if they were present in the supplied pre-AI draft;
model-generated URLs are discarded and are never fetched. `enhance_or_fallback`
returns the original draft with `enhanced: false` on disabled, unavailable, or
failed AI. Callers must use that flag as the only source of an AI-enhanced marker.

## Fixture acceptance cases

`rust/tests/ai.rs` uses an injected transport only; no provider network or real
credentials are involved. It covers disabled-key access, provider and key
precedence, custom model selection, endpoint validation, request fields and
image data URLs, draft merge, source-link preservation, hallucinated image URL
filtering, input limits, invalid JSON, typed HTTP failure, redaction, and
fallback without an enhanced marker. The production transport separately
enforces no proxy, no automatic redirect, sensitive bearer headers, timeout,
status-only errors, and the response-body limit.

Provider calls send imported text, selected draft fields, and explicitly chosen
image bytes to the configured third-party provider. The route must make that
external disclosure conditional on the user's explicit AI choice; local
parsing remains the fallback.
