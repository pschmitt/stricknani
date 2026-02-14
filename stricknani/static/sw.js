/* Minimal service worker to satisfy installability requirements.
 *
 * Offline caching is implemented separately (see TODO T32).
 */

self.addEventListener("install", (event) => {
	event.waitUntil(self.skipWaiting());
});

self.addEventListener("activate", (event) => {
	event.waitUntil(self.clients.claim());
});

