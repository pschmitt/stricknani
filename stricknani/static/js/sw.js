/* Stricknani service worker.
 *
 * Implements offline support (TODO T32):
 *  - Cache-first strategy for the app shell (core CSS/JS/icons/fonts).
 *  - Network-first (falling back to a per-page runtime cache, then an
 *    offline fallback page) for full-page navigations. This is how
 *    "recently viewed" project/yarn pages stay available offline: the app
 *    has no separate JSON API, so the rendered HTML pages ARE the content
 *    we cache.
 *  - Everything else (htmx fragment requests, form submissions, media
 *    attachments, search, etc.) is left to the network as-is. Those
 *    interactive/auth-sensitive endpoints are not safe or useful to cache,
 *    so offline users simply see the normal browser network error for them
 *    while the app shell and previously-viewed pages keep working.
 *
 * Cache naming is tied to BUILD_VERSION, which the server injects into this
 * file (see `stricknani/main.py::service_worker`) from the app version plus
 * a per-process-start token. That way every deploy gets fresh cache names
 * and `activate` cleans up the previous deploy's caches instead of serving
 * stale HTML/CSS/JS forever.
 *
 * Security note: the runtime page cache is per-browser-profile, same as
 * cookies, so it does not expose data across different browser profiles.
 * To avoid stale authenticated pages lingering in the cache after a user
 * logs out on a shared device, the app sends this worker a
 * `CLEAR_RUNTIME_CACHE` message on logout (see `stricknani/static/js/app.js`).
 */

const BUILD_VERSION = "__STRICKNANI_BUILD_VERSION__";
const CACHE_PREFIX = "stricknani-";
const STATIC_CACHE = `${CACHE_PREFIX}static-${BUILD_VERSION}`;
const RUNTIME_CACHE = `${CACHE_PREFIX}runtime-${BUILD_VERSION}`;
const OFFLINE_URL = "/offline";

// Core app-shell assets, precached on install so the app has something to
// show even before the user has visited any other page.
const PRECACHE_URLS = [
	OFFLINE_URL,
	"/manifest.webmanifest",
	"/static/favicon.svg",
	"/static/css/app.css",
	"/static/css/tailwind.css",
	"/static/js/app.js",
	"/static/js/htmx/csrf.js",
	"/static/vendor/htmx/htmx.min.js",
	"/static/vendor/daisyui/daisyui.css",
	"/static/vendor/daisyui/themes.css",
	"/static/vendor/mdi/css/materialdesignicons.min.css",
	"/static/vendor/mdi/fonts/materialdesignicons-webfont.woff2",
	"/static/icons/icon-192.png",
	"/static/icons/icon-512.png",
];

self.addEventListener("install", (event) => {
	event.waitUntil(
		(async () => {
			const cache = await caches.open(STATIC_CACHE);
			// Best-effort: a single missing/failed asset must not block
			// installation of the rest of the app shell.
			await Promise.allSettled(
				PRECACHE_URLS.map(async (url) => {
					const response = await fetch(url, { cache: "reload" });
					if (response?.ok) {
						await cache.put(url, response);
					}
				}),
			);
			await self.skipWaiting();
		})(),
	);
});

self.addEventListener("activate", (event) => {
	event.waitUntil(
		(async () => {
			const keys = await caches.keys();
			await Promise.all(
				keys
					.filter(
						(key) =>
							key.startsWith(CACHE_PREFIX) &&
							key !== STATIC_CACHE &&
							key !== RUNTIME_CACHE,
					)
					.map((key) => caches.delete(key)),
			);
			await self.clients.claim();
		})(),
	);
});

self.addEventListener("message", (event) => {
	if (event.data?.type === "CLEAR_RUNTIME_CACHE") {
		event.waitUntil(caches.delete(RUNTIME_CACHE));
	}
});

// Network-first for full-page navigations, with a runtime cache fallback so
// recently viewed pages stay available offline, and an offline fallback
// page as the last resort.
async function handleNavigate(request) {
	const runtimeCache = await caches.open(RUNTIME_CACHE);
	try {
		const response = await fetch(request);
		// Only cache direct, successful responses. Skip redirected responses
		// (e.g. an expired session bouncing to /login) so we never associate
		// one page's URL with a different page's content.
		if (response?.ok && !response.redirected) {
			runtimeCache.put(request, response.clone());
		}
		return response;
	} catch {
		const cached = await runtimeCache.match(request);
		if (cached) {
			return cached;
		}
		const staticCache = await caches.open(STATIC_CACHE);
		const offline = await staticCache.match(OFFLINE_URL);
		if (offline) {
			return offline;
		}
		return Response.error();
	}
}

// Cache-first for the app shell's static assets.
async function handleStaticAsset(request) {
	const cache = await caches.open(STATIC_CACHE);
	const cached = await cache.match(request);
	if (cached) {
		return cached;
	}
	try {
		const response = await fetch(request);
		if (response?.ok) {
			cache.put(request, response.clone());
		}
		return response;
	} catch {
		return Response.error();
	}
}

self.addEventListener("fetch", (event) => {
	const { request } = event;
	if (request.method !== "GET") {
		return;
	}

	const url = new URL(request.url);
	if (url.origin !== self.location.origin) {
		return;
	}

	if (request.mode === "navigate") {
		event.respondWith(handleNavigate(request));
		return;
	}

	if (
		url.pathname.startsWith("/static/") ||
		url.pathname === "/manifest.webmanifest"
	) {
		event.respondWith(handleStaticAsset(request));
	}
});
