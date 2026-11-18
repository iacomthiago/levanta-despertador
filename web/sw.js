/*
 * Deixa o Disciplina abrir sem internet. Com internet, busca sempre a versão
 * mais nova dos arquivos do site e guarda uma cópia; sem internet, usa a cópia.
 * As conversas com o GitHub (a sincronização) não passam por aqui.
 */
var CACHE = 'disciplina-v1';
var ARQUIVOS = ['./', 'index.html', 'nucleo.js', 'qr.js', 'nuvem.js', 'app.js', 'manifest.webmanifest', 'icon-180.png', 'icon-512.png', 'carro.webp'];

self.addEventListener('install', function (ev) {
  ev.waitUntil(
    caches
      .open(CACHE)
      .then(function (c) {
        return c.addAll(ARQUIVOS);
      })
      .catch(function () {})
      .then(function () {
        return self.skipWaiting();
      })
  );
});

self.addEventListener('activate', function (ev) {
  ev.waitUntil(
    caches
      .keys()
      .then(function (nomes) {
        return Promise.all(
          nomes
            .filter(function (n) {
              return n.indexOf('disciplina-') === 0 && n !== CACHE;
            })
            .map(function (n) {
              return caches.delete(n);
            })
        );
      })
      .then(function () {
        return self.clients.claim();
      })
  );
});

self.addEventListener('fetch', function (ev) {
  var pedido = ev.request;
  if (pedido.method !== 'GET') return;
  var url = new URL(pedido.url);
  if (url.origin !== self.location.origin) return;
  // Só os arquivos deste site (os outros apps do mesmo endereço cuidam dos seus).
  if (url.pathname.indexOf(new URL('./', self.location.href).pathname) !== 0) return;
  ev.respondWith(
    fetch(pedido, { cache: 'no-cache' })
      .then(function (resposta) {
        if (resposta && resposta.ok) {
          var copia = resposta.clone();
          caches.open(CACHE).then(function (c) {
            c.put(pedido, copia);
          });
        }
        return resposta;
      })
      .catch(function () {
        return caches.match(pedido, { ignoreSearch: true }).then(function (guardado) {
          return guardado || caches.match('index.html');
        });
      })
  );
});
