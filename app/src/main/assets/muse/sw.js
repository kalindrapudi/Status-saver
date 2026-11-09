'use strict';
/* Offline shell + Web Share Target receiver for the installed PWA. */
const SHELL = 'muse-shell-v2';
const FILES = ['./', './index.html', './style.css', './app.js', './providers.js', './icon.svg', './manifest.webmanifest'];

self.addEventListener('install', e => { e.waitUntil(caches.open(SHELL).then(c => c.addAll(FILES))); self.skipWaiting(); });
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(keys => Promise.all(keys.filter(k => k.startsWith('muse-shell-') && k !== SHELL).map(k => caches.delete(k)))));
  self.clients.claim();
});
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method === 'POST' && url.pathname.endsWith('/share-target')) {
    e.respondWith((async () => {
      const form = await e.request.formData();
      const cache = await caches.open('muse-share');
      let i = 0;
      for (const f of form.getAll('images')) {
        if (f && f.type && f.type.startsWith('image/')) await cache.put(`./shared/${Date.now()}-${i++}-${encodeURIComponent(f.name || 'image')}`, new Response(f));
      }
      return Response.redirect('./?shared=1#create', 303);
    })());
    return;
  }
  if (e.request.method !== 'GET' || url.origin !== location.origin) return; // never cache API calls
  e.respondWith(fetch(e.request).then(r => { const copy = r.clone(); caches.open(SHELL).then(c => c.put(e.request, copy)); return r; }).catch(() => caches.match(e.request)));
});
