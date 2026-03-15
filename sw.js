var CACHE = 'ontheway-v2';
var sharedFiles = [];

self.addEventListener('install', function(e) {
  self.skipWaiting();
});

self.addEventListener('activate', function(e) {
  e.waitUntil(clients.claim());
});

self.addEventListener('fetch', function(e) {
  var url = new URL(e.request.url);

  // 공유 타겟 POST 처리
  if (url.pathname === '/share' && e.request.method === 'POST') {
    e.respondWith((async function() {
      try {
        var formData = await e.request.formData();
        var files = formData.getAll('images');

        if (files.length > 0) {
          // 파일을 ArrayBuffer로 변환해서 저장
          var fileDataArray = [];
          for (var i = 0; i < files.length; i++) {
            var buf = await files[i].arrayBuffer();
            fileDataArray.push({
              name: files[i].name,
              type: files[i].type || 'image/jpeg',
              data: buf
            });
          }

          // 모든 클라이언트에 메시지 전송
          var allClients = await clients.matchAll({ includeUncontrolled: true, type: 'window' });
          for (var c = 0; c < allClients.length; c++) {
            allClients[c].postMessage({
              type: 'SHARE_IMAGE',
              files: fileDataArray
            }, fileDataArray.map(function(f){ return f.data; }));
          }
        }
      } catch(err) {
        console.error('Share error:', err);
      }

      return Response.redirect('/?shared=1', 303);
    })());
    return;
  }
});
