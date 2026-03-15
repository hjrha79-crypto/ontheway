var CACHE = 'ontheway-v1';

self.addEventListener('install', function(e) {
  self.skipWaiting();
});

self.addEventListener('activate', function(e) {
  clients.claim();
});

self.addEventListener('fetch', function(e) {
  // 공유 타겟 처리
  if (e.request.url.includes('/share') && e.request.method === 'POST') {
    e.respondWith((async function() {
      var data = await e.request.formData();
      var files = data.getAll('images');
      var client = await clients.get(e.clientId) || (await clients.matchAll())[0];

      if (client && files.length > 0) {
        client.postMessage({ type: 'SHARE_IMAGE', files: files });
      }

      return Response.redirect('/?shared=1', 303);
    })());
    return;
  }
});

self.addEventListener('message', function(e) {
  if (e.data && e.data.type === 'SKIP_WAITING') {
    self.skipWaiting();
  }
});
