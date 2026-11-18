/*
 * Gerador de QR code pequeno e sem dependências: modo de bytes, correção de
 * erros nível L, versões 1 a 10 (até 271 caracteres). Serve para passar a chave
 * da conta de um aparelho para outro sem digitar.
 */
(function (raiz) {
  'use strict';

  var ECC_POR_BLOCO = [0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18];
  var BLOCOS = [0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4];

  function modulosDeDados(ver) {
    var r = (16 * ver + 128) * ver + 64;
    if (ver >= 2) {
      var alinh = Math.floor(ver / 7) + 2;
      r -= (25 * alinh - 10) * alinh - 55;
      if (ver >= 7) r -= 36;
    }
    return r;
  }

  function palavrasDeDados(ver) {
    return Math.floor(modulosDeDados(ver) / 8) - ECC_POR_BLOCO[ver] * BLOCOS[ver];
  }

  function vezes(x, y) {
    var z = 0;
    for (var i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11d);
      z ^= ((y >>> i) & 1) * x;
    }
    return z;
  }

  function divisor(grau) {
    var r = [];
    for (var i = 0; i < grau - 1; i++) r.push(0);
    r.push(1);
    var raizA = 1;
    for (i = 0; i < grau; i++) {
      for (var j = 0; j < r.length; j++) {
        r[j] = vezes(r[j], raizA);
        if (j + 1 < r.length) r[j] ^= r[j + 1];
      }
      raizA = vezes(raizA, 2);
    }
    return r;
  }

  function resto(dados, div) {
    var r = div.map(function () {
      return 0;
    });
    dados.forEach(function (b) {
      var fator = b ^ r.shift();
      r.push(0);
      div.forEach(function (coef, i) {
        r[i] ^= vezes(coef, fator);
      });
    });
    return r;
  }

  function utf8(texto) {
    var s = unescape(encodeURIComponent(texto));
    var bytes = [];
    for (var i = 0; i < s.length; i++) bytes.push(s.charCodeAt(i));
    return bytes;
  }

  /** Devolve a matriz do QR (true = módulo escuro) ou null se o texto não couber. */
  function gerar(texto, mascaraFixa) {
    var bytes = utf8(texto);
    var ver = 0;
    for (var v = 1; v <= 10; v++) {
      var cabecalho = 4 + (v <= 9 ? 8 : 16);
      if (cabecalho + bytes.length * 8 <= palavrasDeDados(v) * 8) {
        ver = v;
        break;
      }
    }
    if (!ver) return null;

    // Sequência de bits: modo, tamanho, dados, terminador e preenchimento.
    var bits = [];
    function poe(valor, n) {
      for (var i = n - 1; i >= 0; i--) bits.push((valor >>> i) & 1);
    }
    poe(4, 4);
    poe(bytes.length, ver <= 9 ? 8 : 16);
    bytes.forEach(function (b) {
      poe(b, 8);
    });
    var capacidade = palavrasDeDados(ver) * 8;
    poe(0, Math.min(4, capacidade - bits.length));
    poe(0, (8 - (bits.length % 8)) % 8);
    for (var pad = 0xec; bits.length < capacidade; pad ^= 0xec ^ 0x11) poe(pad, 8);
    var dados = [];
    for (var i = 0; i < bits.length; i += 8) {
      var b = 0;
      for (var j = 0; j < 8; j++) b = (b << 1) | bits[i + j];
      dados.push(b);
    }

    // Blocos com correção de erros, intercalados.
    var nBlocos = BLOCOS[ver];
    var eccLen = ECC_POR_BLOCO[ver];
    var total = Math.floor(modulosDeDados(ver) / 8);
    var curtos = nBlocos - (total % nBlocos);
    var tamCurto = Math.floor(total / nBlocos);
    var div = divisor(eccLen);
    var blocos = [];
    for (i = 0, j = 0; i < nBlocos; i++) {
      var parte = dados.slice(j, j + tamCurto - eccLen + (i < curtos ? 0 : 1));
      j += parte.length;
      var ecc = resto(parte, div);
      if (i < curtos) parte.push(0);
      blocos.push(parte.concat(ecc));
    }
    var palavras = [];
    for (i = 0; i < blocos[0].length; i++) {
      for (j = 0; j < blocos.length; j++) {
        if (i !== tamCurto - eccLen || j >= curtos) palavras.push(blocos[j][i]);
      }
    }

    // Matriz e desenhos fixos.
    var tam = ver * 4 + 17;
    var m = [];
    var fixo = [];
    for (i = 0; i < tam; i++) {
      m.push(new Array(tam).fill(false));
      fixo.push(new Array(tam).fill(false));
    }
    function fixa(x, y, escuro) {
      m[y][x] = escuro;
      fixo[y][x] = true;
    }
    for (i = 0; i < tam; i++) {
      fixa(6, i, i % 2 === 0);
      fixa(i, 6, i % 2 === 0);
    }
    function olho(cx, cy) {
      for (var dy = -4; dy <= 4; dy++) {
        for (var dx = -4; dx <= 4; dx++) {
          var dist = Math.max(Math.abs(dx), Math.abs(dy));
          var x = cx + dx;
          var y = cy + dy;
          if (x >= 0 && x < tam && y >= 0 && y < tam) fixa(x, y, dist !== 2 && dist !== 4);
        }
      }
    }
    olho(3, 3);
    olho(tam - 4, 3);
    olho(3, tam - 4);
    var pos = [];
    if (ver > 1) {
      var nAlinh = Math.floor(ver / 7) + 2;
      var passo = Math.ceil((ver * 4 + 4) / (nAlinh * 2 - 2)) * 2;
      pos = [6];
      for (var p = tam - 7; pos.length < nAlinh; p -= passo) pos.splice(1, 0, p);
    }
    for (i = 0; i < pos.length; i++) {
      for (j = 0; j < pos.length; j++) {
        var canto = (i === 0 && j === 0) || (i === 0 && j === pos.length - 1) || (i === pos.length - 1 && j === 0);
        if (canto) continue;
        for (var dy = -2; dy <= 2; dy++) {
          for (var dx = -2; dx <= 2; dx++) fixa(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
        }
      }
    }
    function formato(mascara) {
      var dado = (1 << 3) | mascara; // nível L
      var r = dado;
      for (var k = 0; k < 10; k++) r = (r << 1) ^ ((r >>> 9) * 0x537);
      var f = ((dado << 10) | r) ^ 0x5412;
      function bit(n) {
        return ((f >>> n) & 1) !== 0;
      }
      var k2;
      for (k2 = 0; k2 <= 5; k2++) fixa(8, k2, bit(k2));
      fixa(8, 7, bit(6));
      fixa(8, 8, bit(7));
      fixa(7, 8, bit(8));
      for (k2 = 9; k2 < 15; k2++) fixa(14 - k2, 8, bit(k2));
      for (k2 = 0; k2 < 8; k2++) fixa(tam - 1 - k2, 8, bit(k2));
      for (k2 = 8; k2 < 15; k2++) fixa(8, tam - 15 + k2, bit(k2));
      fixa(8, tam - 8, true);
    }
    formato(0);
    if (ver >= 7) {
      var rv = ver;
      for (i = 0; i < 12; i++) rv = (rv << 1) ^ ((rv >>> 11) * 0x1f25);
      var bv = (ver << 12) | rv;
      for (i = 0; i < 18; i++) {
        var escuro = ((bv >>> i) & 1) !== 0;
        var a = tam - 11 + (i % 3);
        var c = Math.floor(i / 3);
        fixa(a, c, escuro);
        fixa(c, a, escuro);
      }
    }

    // Dados em ziguezague, da direita para a esquerda.
    var n = 0;
    for (var direita = tam - 1; direita >= 1; direita -= 2) {
      if (direita === 6) direita = 5;
      for (var vert = 0; vert < tam; vert++) {
        for (j = 0; j < 2; j++) {
          var x = direita - j;
          var sobe = ((direita + 1) & 2) === 0;
          var y = sobe ? tam - 1 - vert : vert;
          if (!fixo[y][x] && n < palavras.length * 8) {
            m[y][x] = ((palavras[n >>> 3] >>> (7 - (n & 7))) & 1) !== 0;
            n++;
          }
        }
      }
    }

    function mascarar(k) {
      for (var y = 0; y < tam; y++) {
        for (var x = 0; x < tam; x++) {
          if (fixo[y][x]) continue;
          var inverte;
          if (k === 0) inverte = (x + y) % 2 === 0;
          else if (k === 1) inverte = y % 2 === 0;
          else if (k === 2) inverte = x % 3 === 0;
          else if (k === 3) inverte = (x + y) % 3 === 0;
          else if (k === 4) inverte = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0;
          else if (k === 5) inverte = ((x * y) % 2) + ((x * y) % 3) === 0;
          else if (k === 6) inverte = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0;
          else inverte = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0;
          if (inverte) m[y][x] = !m[y][x];
        }
      }
    }

    // Nota de cada máscara: sequências longas, quadrados, falsos olhos e desequilíbrio.
    function nota() {
      var pontos = 0;
      var x, y, cor, seguidos;
      function falsoOlho(l) {
        var a = '10111010000';
        var b = '00001011101';
        var s = l.join('');
        var q = 0;
        for (var i2 = 0; i2 + 11 <= s.length; i2++) {
          var t = s.substr(i2, 11);
          if (t === a || t === b) q++;
        }
        return q;
      }
      for (y = 0; y < tam; y++) {
        cor = null;
        seguidos = 0;
        var linha = [];
        var coluna = [];
        var corC = null;
        var seguidosC = 0;
        for (x = 0; x < tam; x++) {
          linha.push(m[y][x] ? 1 : 0);
          coluna.push(m[x][y] ? 1 : 0);
          if (m[y][x] === cor) {
            seguidos++;
            if (seguidos === 5) pontos += 3;
            else if (seguidos > 5) pontos++;
          } else {
            cor = m[y][x];
            seguidos = 1;
          }
          if (m[x][y] === corC) {
            seguidosC++;
            if (seguidosC === 5) pontos += 3;
            else if (seguidosC > 5) pontos++;
          } else {
            corC = m[x][y];
            seguidosC = 1;
          }
        }
        pontos += 40 * (falsoOlho(linha) + falsoOlho(coluna));
      }
      var escuros = 0;
      for (y = 0; y < tam; y++) {
        for (x = 0; x < tam; x++) {
          if (m[y][x]) escuros++;
          if (x + 1 < tam && y + 1 < tam && m[y][x] === m[y][x + 1] && m[y][x] === m[y + 1][x] && m[y][x] === m[y + 1][x + 1]) pontos += 3;
        }
      }
      pontos += Math.floor(Math.abs((escuros * 20) / (tam * tam) - 10)) * 10;
      return pontos;
    }

    var melhor = 0;
    var menor = Infinity;
    for (var k = 0; k < 8; k++) {
      // Só os testes fixam a máscara, para comparar com outro gerador.
      if (mascaraFixa !== undefined && k !== mascaraFixa) continue;
      mascarar(k);
      formato(k);
      var pts = nota();
      if (pts < menor) {
        menor = pts;
        melhor = k;
      }
      mascarar(k);
    }
    mascarar(melhor);
    formato(melhor);
    return { tamanho: tam, versao: ver, mascara: melhor, modulos: m };
  }

  /** O QR como desenho SVG (texto), com a margem branca que os leitores pedem. */
  function svg(texto, lado) {
    var q = gerar(texto);
    if (!q) return '';
    var margem = 4;
    var t = q.tamanho + margem * 2;
    var d = '';
    for (var y = 0; y < q.tamanho; y++) {
      for (var x = 0; x < q.tamanho; x++) {
        if (q.modulos[y][x]) d += 'M' + (x + margem) + ' ' + (y + margem) + 'h1v1h-1z';
      }
    }
    return (
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ' + t + ' ' + t + '" width="' + (lado || 260) + '" height="' + (lado || 260) +
      '" shape-rendering="crispEdges" role="img" aria-label="QR code da conta"><rect width="' + t + '" height="' + t +
      '" fill="#fff"/><path d="' + d + '" fill="#000"/></svg>'
    );
  }

  var api = { gerar: gerar, svg: svg };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else raiz.QR = api;
})(typeof self !== 'undefined' ? self : this);
