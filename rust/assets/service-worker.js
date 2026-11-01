/* Public offline shell only. Authenticated pages, APIs, and media stay network-only. */
const CACHE_PREFIX = "stricknani-pwa-";
const CACHE_NAME = `${CACHE_PREFIX}v2`;
const LEGACY_CACHE_PREFIXES = ["stricknani-static-", "stricknani-runtime-"];
const OFFLINE_PATH = "/offline";
const PUBLIC_PATHS = new Set([
	OFFLINE_PATH,
	"/manifest.webmanifest",
	"/register-pwa.js",
	"/static/css/app.css",
	"/static/css/material.css",
	"/static/css/rust.css",
	"/static/favicon.svg",
	"/static/icons/icon-192.png",
	"/static/icons/icon-512.png",
]);

function cacheablePublicResponse(response, requestedUrl) {
	if (!response?.ok || response.redirected || response.type !== "basic") {
		return false;
	}
	const responseUrl = new URL(response.url);
	return responseUrl.origin === self.location.origin && responseUrl.href === requestedUrl.href;
}

self.addEventListener("install", (event) => {
	event.waitUntil(
		(async () => {
			const cache = await caches.open(CACHE_NAME);
			await Promise.allSettled(
				[...PUBLIC_PATHS].map(async (path) => {
					const url = new URL(path, self.location.origin);
					const response = await fetch(url, {
						cache: "reload",
						credentials: "omit",
						redirect: "error",
					});
					if (cacheablePublicResponse(response, url)) {
						await cache.put(url.href, response);
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
							(key.startsWith(CACHE_PREFIX) && key !== CACHE_NAME) ||
							LEGACY_CACHE_PREFIXES.some((prefix) => key.startsWith(prefix)),
					)
					.map((key) => caches.delete(key)),
			);
			const cache = await caches.open(CACHE_NAME);
			const entries = await cache.keys();
			await Promise.all(
				entries
					.filter((request) => {
						const url = new URL(request.url);
						return (
							url.origin !== self.location.origin ||
							url.search !== "" ||
							!PUBLIC_PATHS.has(url.pathname)
						);
					})
					.map((request) => cache.delete(request)),
			);
			await self.clients.claim();
		})(),
	);
});

async function cachedPublicAsset(url) {
	const cache = await caches.open(CACHE_NAME);
	const cached = await cache.match(url.href);
	if (cached) {
		return cached;
	}
	try {
		const response = await fetch(url, {
			cache: "no-store",
			credentials: "omit",
			redirect: "error",
		});
		if (cacheablePublicResponse(response, url)) {
			await cache.put(url.href, response.clone());
		}
		return response;
	} catch {
		return Response.error();
	}
}

async function offlineNavigation(request) {
	try {
		return await fetch(request);
	} catch {
		const cache = await caches.open(CACHE_NAME);
		return (await cache.match(new URL(OFFLINE_PATH, self.location.origin).href)) || Response.error();
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

	if (url.search === "" && PUBLIC_PATHS.has(url.pathname)) {
		event.respondWith(cachedPublicAsset(url));
		return;
	}

	if (request.mode === "navigate") {
		// Navigation responses are never cached. The fallback is used only on a network failure,
		// never for an HTTP response such as a login redirect, 401, or 500.
		event.respondWith(offlineNavigation(request));
	}
});
