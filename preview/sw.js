/* TSV 교대달력 — 프리뷰(/preview/) 전용 Service Worker
   루트(https://csh1oo4.github.io/tsv-/)의 sw.js 와는 scope 가 달라 서로 영향을 주지 않는다.
   정책: Network-First (오프라인이면 마지막으로 받은 프리뷰 화면). */
const CACHE = "tsv-preview-v1";

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => Promise.allSettled([cache.add("./index.html")]))
      .then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const request = event.request;
  if (request.method !== "GET") return;
  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return;
  if (request.mode !== "navigate") return;

  event.respondWith(
    fetch(request, { cache: "no-cache" })
      .then((response) => {
        if (response && response.ok) {
          caches.open(CACHE).then((cache) => cache.put("./index.html", response.clone())).catch(() => {});
        }
        return response;
      })
      .catch(() => caches.match("./index.html"))
  );
});
