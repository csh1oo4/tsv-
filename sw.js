/* TSV 교대달력 — GitHub Pages 웹앱용 Service Worker
   정책: Network-First. 온라인에서는 항상 최신 index.html을 받고,
   네트워크가 없거나 4초 안에 응답이 없을 때만 캐시로 대신 보여 준다. */
const CACHE = "tsv-duty-calendar-v3";
const CORE = [
  "./index.html",
  "./manifest.webmanifest",
  "./apple-touch-icon.png",
  "./icon-192.png",
  "./icon-512.png"
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => Promise.allSettled(CORE.map((url) => cache.add(url))))
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

const networkFirst = async (request, cacheKey) => {
  const cache = await caches.open(CACHE);
  try {
    const response = await Promise.race([
      fetch(request, { cache: "no-cache" }),
      new Promise((_, reject) => setTimeout(() => reject(new Error("timeout")), 4000))
    ]);
    if (response && response.ok) {
      cache.put(cacheKey || request, response.clone()).catch(() => {});
    }
    return response;
  } catch (err) {
    const cached = await cache.match(cacheKey || request, { ignoreSearch: true });
    if (cached) return cached;
    if (request.mode === "navigate") {
      const shell = await cache.match("./index.html");
      if (shell) return shell;
    }
    throw err;
  }
};

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (request.method !== "GET") return;

  const url = new URL(request.url);
  if (url.origin !== self.location.origin) return; // Firebase·Open-Meteo 등은 그대로 통과

  // 미리보기(/preview/)·AMMK 달력(/ammk-preview/)은 이 SW가 가로채지 않는다
  // — 각 페이지는 자기 폴더의 SW(scope ./preview/, ./ammk-preview/)를 쓴다
  if (url.pathname.includes("/preview/") || url.pathname.includes("/ammk-preview/")) return;

  // 화면(문서) 요청은 항상 index.html 하나로 수렴 (해시 라우팅 앱)
  if (request.mode === "navigate") {
    event.respondWith(networkFirst("./index.html"));
    return;
  }

  // 나머지 정적 자원: 캐시 우선, 없으면 네트워크
  event.respondWith(
    caches.match(request, { ignoreSearch: true }).then((cached) => cached || networkFirst(request))
  );
});
