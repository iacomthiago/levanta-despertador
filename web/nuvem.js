/*
 * Onde o progresso fica guardado na internet: um gist secreto na conta do
 * GitHub de cada pessoa (o mesmo esquema do HoloCards). A chave é um token do
 * GitHub com permissão só de "gist", colado uma vez em cada aparelho.
 */
(function (raiz) {
  'use strict';

  var API = 'https://api.github.com';
  // Só nos testes, com o site aberto no próprio computador: aponta para a API de mentira (ci/mock_gist.py).
  try {
    if ((location.hostname === 'localhost' || location.hostname === '127.0.0.1') && localStorage.getItem('disciplina.api')) {
      API = localStorage.getItem('disciplina.api');
    }
  } catch (e) {
    // sem armazenamento: usa o GitHub de verdade
  }
  var ARQUIVO = 'disciplina.json';
  var DESCRICAO = 'Disciplina: progresso sincronizado entre aparelhos';

  /** tipo: "chave" (recusada), "permissao", "limite", "rede" ou "outro". */
  function Erro(tipo, detalhe) {
    this.tipo = tipo;
    this.message = 'Nuvem: ' + tipo + (detalhe ? ' (' + detalhe + ')' : '');
  }
  Erro.prototype = Object.create(Error.prototype);

  function pedir(chave, metodo, caminho, corpo, extra) {
    var cabecalhos = { Accept: 'application/vnd.github+json', Authorization: 'Bearer ' + chave };
    if (corpo) cabecalhos['Content-Type'] = 'application/json';
    for (var k in extra || {}) cabecalhos[k] = extra[k];
    return fetch(API + caminho, {
      method: metodo,
      headers: cabecalhos,
      body: corpo ? JSON.stringify(corpo) : undefined,
      cache: 'no-store',
    }).then(
      function (r) {
        if (r.status === 401) throw new Erro('chave');
        if (r.status === 403 || r.status === 429) {
          var resta = r.headers.get('x-ratelimit-remaining');
          throw new Erro(resta === '0' || r.status === 429 ? 'limite' : 'permissao');
        }
        return r;
      },
      function () {
        throw new Erro('rede');
      }
    );
  }

  /** Procura o gist do Disciplina na conta. Devolve o id ou null. Havendo mais de um, vale o mais antigo. */
  function achar(chave) {
    var melhor = null;
    function pagina(n) {
      return pedir(chave, 'GET', '/gists?per_page=100&page=' + n).then(function (r) {
        if (!r.ok) throw new Erro('outro', r.status);
        return r.json().then(function (lista) {
          lista.forEach(function (g) {
            if (g.files && g.files[ARQUIVO] && (!melhor || g.created_at < melhor.created_at)) melhor = g;
          });
          if (lista.length === 100 && n < 10) return pagina(n + 1);
          return melhor ? melhor.id : null;
        });
      });
    }
    return pagina(1);
  }

  /** Lê o documento. Com [etag], responde { status: 304 } quando nada mudou. */
  function ler(chave, id, etag) {
    return pedir(chave, 'GET', '/gists/' + encodeURIComponent(id), null, etag ? { 'If-None-Match': etag } : null).then(function (r) {
      if (r.status === 304) return { status: 304 };
      if (r.status === 404) return { status: 404 };
      if (!r.ok) throw new Erro('outro', r.status);
      var novaEtag = r.headers.get('etag');
      return r.json().then(function (g) {
        var arq = g.files && g.files[ARQUIVO];
        if (!arq) return { status: 404 };
        var texto = arq.truncated && arq.raw_url
          ? fetch(arq.raw_url, { cache: 'no-store' }).then(function (x) {
              return x.text();
            })
          : Promise.resolve(arq.content);
        return texto.then(function (t) {
          var doc;
          try {
            doc = JSON.parse(t);
          } catch (e) {
            throw new Erro('outro', 'conteúdo ilegível');
          }
          return { status: 200, doc: doc, etag: novaEtag };
        });
      });
    });
  }

  /** Cria o gist secreto com o documento. Devolve o id. */
  function criar(chave, doc) {
    var arquivos = {};
    arquivos[ARQUIVO] = { content: JSON.stringify(doc) };
    return pedir(chave, 'POST', '/gists', { description: DESCRICAO, public: false, files: arquivos }).then(function (r) {
      if (r.status === 404) throw new Erro('permissao');
      if (!r.ok) throw new Erro('outro', r.status);
      return r.json().then(function (g) {
        return g.id;
      });
    });
  }

  function gravar(chave, id, doc) {
    var arquivos = {};
    arquivos[ARQUIVO] = { content: JSON.stringify(doc) };
    return pedir(chave, 'PATCH', '/gists/' + encodeURIComponent(id), { files: arquivos }).then(function (r) {
      if (r.status === 404) return { status: 404 };
      if (!r.ok) throw new Erro('outro', r.status);
      return { status: 200 };
    });
  }

  raiz.Nuvem = { achar: achar, ler: ler, criar: criar, gravar: gravar, Erro: Erro, ARQUIVO: ARQUIVO };
})(typeof self !== 'undefined' ? self : this);
