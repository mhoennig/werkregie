// Werkregie's service worker: keeps the page itself for offline starts, never the data.
// Network first, so a new version shows at once; the cache answers only when the network does not.
// /api/ is never touched: its answers carry private data behind the access token.

const CACHE = "werkregie-shell";
const SHELL = [
    "/",
    "/werkregie.css",
    "/favicon.svg",
    "/manifest.webmanifest",
    "/fonts/plex-sans-400.woff2",
    "/fonts/plex-sans-500.woff2",
    "/fonts/plex-sans-600.woff2",
    "/fonts/plex-sans-700.woff2",
    "/fonts/plex-mono-400.woff2",
    "/fonts/plex-mono-500.woff2",
];

self.addEventListener("install", (event) => {
    event.waitUntil(caches.open(CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (event) => {
    event.waitUntil(
        caches.keys()
            .then((keys) => Promise.all(keys.filter((key) => key !== CACHE).map((key) => caches.delete(key))))
            .then(() => self.clients.claim()),
    );
});

self.addEventListener("fetch", (event) => {
    const request = event.request;
    const url = new URL(request.url);
    if (request.method !== "GET" || url.origin !== self.location.origin || url.pathname.startsWith("/api/")) return;
    event.respondWith(
        fetch(request)
            .then((response) => {
                if (response.ok) {
                    const copy = response.clone();
                    caches.open(CACHE).then((cache) => cache.put(request.mode === "navigate" ? "/" : request, copy));
                }
                return response;
            })
            .catch(() => caches.match(request.mode === "navigate" ? "/" : request)
                .then((cached) => cached || Response.error())),
    );
});
