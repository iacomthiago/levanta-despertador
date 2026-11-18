/*
 * Disciplina: regras do jogo e formato de dados compartilhado com o app Android.
 *
 * As regras e os valores são os de Progresso.kt. O documento ("doc") é o mesmo
 * JSON que o app Android grava (Sync.kt): cada item leva a hora da última
 * mudança ("t"), e juntar dois documentos nunca perde o que só um dos lados tem.
 * Nada aqui toca em tela, rede ou armazenamento.
 */
(function (raiz) {
  'use strict';

  var BLOCOS_POR_DIA = 14;
  var MINUTOS_DO_BLOCO = 30;
  var NIVEIS_POR_RECOMPENSA = 5;
  var DIAS_POR_TURBO = 7;
  var ID_ESTUDO = -2;
  var URL_TREINO = 'https://iacomthiago.github.io/treino-em-casa/';
  var PREMIOS_PADRAO =
    'um lanche caprichado\n' +
    'uma pizza\n' +
    'uma noite de anime ou filme com pipoca\n' +
    'um jantar fora, no lugar que você escolher\n' +
    'um presente para você, algo que está querendo comprar\n' +
    'um passeio de um dia inteiro';

  var Xp = { LEVANTAR: 30, TREINO: 40, SIMPLES: 10, BLOCO: 25, BONUS_ESTUDO: 50, SEMANA: 150 };
  var Tipo = { LEVANTAR: 'levantar', ESTUDO: 'estudo', TREINO: 'treino', SIMPLES: 'simples' };
  var RANKS = [
    { nome: 'Partida', desde: 1 },
    { nome: 'Primeira marcha', desde: 3 },
    { nome: 'Segunda marcha', desde: 6 },
    { nome: 'Terceira marcha', desde: 10 },
    { nome: 'Quarta marcha', desde: 15 },
    { nome: 'Quinta marcha', desde: 21 },
  ];

  function xpDaMissao(tipo) {
    if (tipo === Tipo.TREINO) return Xp.TREINO;
    if (tipo === Tipo.SIMPLES) return Xp.SIMPLES;
    if (tipo === Tipo.ESTUDO) return 0;
    return Xp.LEVANTAR;
  }

  /** Experiência total para chegar ao nível n. Cada nível pede 50 a mais que o anterior. */
  function xpDoNivel(n) {
    var k = Math.max(0, n - 1);
    return 100 * k + 25 * k * (k - 1);
  }

  function nivelDe(xp) {
    var n = 1;
    while (n < 999 && xpDoNivel(n + 1) <= xp) n++;
    return n;
  }

  function indiceDoRank(nivel) {
    var i = 0;
    for (var k = 0; k < RANKS.length; k++) if (nivel >= RANKS[k].desde) i = k;
    return i;
  }

  function tempoDeBlocos(n) {
    var minutos = n * MINUTOS_DO_BLOCO;
    var h = Math.floor(minutos / 60);
    var m = minutos % 60;
    if (h === 0) return m + ' min';
    if (m === 0) return h + ' h';
    return h + ' h ' + m + ' min';
  }

  function nomeDoTipo(tipo) {
    if (tipo === Tipo.ESTUDO) return 'Estudo';
    if (tipo === Tipo.TREINO) return 'Treino';
    if (tipo === Tipo.SIMPLES) return 'Simples';
    return 'Levantar';
  }

  // ---------- datas (sempre no horário do aparelho) ----------

  /** Só os testes mexem aqui: minutos a somar ao horário universal. */
  var fusoFixo = null;

  function fuso(ms) {
    return fusoFixo !== null ? fusoFixo : -new Date(ms).getTimezoneOffset();
  }

  function local(ms) {
    return new Date(ms + fuso(ms) * 60000);
  }

  function dois(n) {
    return n < 10 ? '0' + n : '' + n;
  }

  function diaDeNum(n) {
    var d = new Date(n * 86400000);
    return d.getUTCFullYear() + '-' + dois(d.getUTCMonth() + 1) + '-' + dois(d.getUTCDate());
  }

  /** Dia (aaaa-mm-dd) de um instante. */
  function diaDe(ms) {
    var d = local(ms);
    return d.getUTCFullYear() + '-' + dois(d.getUTCMonth() + 1) + '-' + dois(d.getUTCDate());
  }

  /** Dias desde 1970-01-01, como LocalDate.toEpochDay. */
  function numDia(dia) {
    var p = String(dia).split('-');
    return Math.floor(Date.UTC(+p[0], +p[1] - 1, +p[2]) / 86400000);
  }

  function somaDias(dia, n) {
    return diaDeNum(numDia(dia) + n);
  }

  /** 1 = segunda ... 7 = domingo. */
  function diaDaSemana(dia) {
    return ((((numDia(dia) + 3) % 7) + 7) % 7) + 1;
  }

  function segundaDe(dia) {
    return somaDias(dia, 1 - diaDaSemana(dia));
  }

  function horaDe(ms) {
    return local(ms).getUTCHours();
  }

  function minutoDoDia(ms) {
    var d = local(ms);
    return d.getUTCHours() * 60 + d.getUTCMinutes();
  }

  // ---------- leitura do documento ----------

  function int(v, padrao) {
    var n = Number(v);
    return isFinite(n) ? Math.trunc(n) : padrao || 0;
  }

  function obj(v) {
    return v && typeof v === 'object' && !Array.isArray(v) ? v : {};
  }

  function novoDoc(agora) {
    return {
      v: 1,
      criado: diaDe(agora),
      base: {},
      missoes: {},
      feitos: {},
      blocos: {},
      turbos: {},
      dias: {},
      semanas: {},
      ideias: {},
      premios: { v: PREMIOS_PADRAO, t: 0 },
      pegos: {},
      cfg: {},
      marco: 0,
    };
  }

  /** Garante que todas as partes existem, para um documento vindo de fora não quebrar nada. */
  function arrumar(doc) {
    var d = obj(doc);
    var partes = ['base', 'missoes', 'feitos', 'blocos', 'turbos', 'dias', 'semanas', 'ideias', 'pegos', 'cfg'];
    for (var i = 0; i < partes.length; i++) d[partes[i]] = obj(d[partes[i]]);
    d.v = 1;
    if (typeof d.criado !== 'string') d.criado = '';
    if (!d.premios || typeof d.premios.v !== 'string') d.premios = { v: PREMIOS_PADRAO, t: 0 };
    d.marco = int(d.marco);
    return d;
  }

  function cfg(doc, chave, padrao) {
    var c = doc.cfg[chave];
    return c && c.v !== undefined && c.v !== null ? c.v : padrao;
  }

  function metaDeBlocos(doc) {
    return Math.min(BLOCOS_POR_DIA, Math.max(1, int(cfg(doc, 'studyBlocks', 4), 4)));
  }

  function horaDoBonus(doc) {
    return Math.min(23, Math.max(1, int(cfg(doc, 'studyBonusHour', 14), 14)));
  }

  function nomeDaMissao(m) {
    return m.label && String(m.label).trim() ? m.label : nomeDoTipo(m.type);
  }

  /** Missões que existem (não apagadas), na ordem do relógio. */
  function missoes(doc) {
    var lista = [];
    for (var id in doc.missoes) {
      var m = doc.missoes[id];
      if (m && !m.del) lista.push(m);
    }
    lista.sort(function (a, b) {
      return a.hour * 60 + a.minute - (b.hour * 60 + b.minute) || a.id - b.id;
    });
    return lista;
  }

  /** Missões ligadas que caem neste dia da semana. */
  function doDia(doc, dia) {
    var d = diaDaSemana(dia);
    return missoes(doc).filter(function (m) {
      if (!m.enabled) return false;
      var dias = Array.isArray(m.days) ? m.days : [];
      return dias.length === 0 || dias.indexOf(d) >= 0;
    });
  }

  /**
   * A missão entra na conta da sequência neste dia? No dia em que foi criada,
   * só se a hora dela ainda não tinha passado.
   */
  function vale(m, dia) {
    if (!m.counts) return false;
    var criada = Number(m.createdAt) || 0;
    if (criada <= 0) return true;
    var diaCriada = diaDe(criada);
    if (dia > diaCriada) return true;
    if (dia < diaCriada) return false;
    var l = local(criada);
    var doDiaMs = ((l.getUTCHours() * 60 + l.getUTCMinutes()) * 60 + l.getUTCSeconds()) * 1000 + l.getUTCMilliseconds();
    return doDiaMs <= (m.hour * 60 + m.minute) * 60000;
  }

  function chaveDoFeito(dia, alarmId) {
    return dia + '|' + alarmId;
  }

  /** Feitos que valem (não desfeitos), separados por dia. */
  function feitosPorDia(doc) {
    var mapa = {};
    for (var k in doc.feitos) {
      var f = doc.feitos[k];
      if (!f || f.del) continue;
      (mapa[f.dia] = mapa[f.dia] || []).push(f);
    }
    return mapa;
  }

  function feita(indice, m, dia) {
    var lista = indice[dia];
    if (!lista) return false;
    for (var i = 0; i < lista.length; i++) {
      if (m.type === Tipo.ESTUDO ? lista[i].tipo === Tipo.ESTUDO : lista[i].alarmId === m.id) return true;
    }
    return false;
  }

  /** 1 = dia completo, 0 = faltou missão que conta, 2 = nenhuma missão contava. */
  function diaCompleto(doc, indice, dia) {
    var devidas = doDia(doc, dia).filter(function (m) {
      return vale(m, dia);
    });
    if (devidas.length === 0) return 2;
    for (var i = 0; i < devidas.length; i++) if (!feita(indice, devidas[i], dia)) return 0;
    return 1;
  }

  /** Números dos blocos de estudo marcados no dia, em ordem. */
  function blocosDe(doc, dia) {
    var doDiaB = doc.blocos[dia];
    var lista = [];
    if (!doDiaB) return lista;
    for (var n = 1; n <= BLOCOS_POR_DIA; n++) if (doDiaB[n] && doDiaB[n].on) lista.push(n);
    return lista;
  }

  function multiplicador(doc, dia) {
    return doc.turbos[dia] ? 2 : 1;
  }

  function progressoDaSemana(indice, inicio, desafio) {
    var total = 0;
    for (var i = 0; i < 7; i++) {
      var lista = indice[somaDias(inicio, i)];
      if (!lista) continue;
      for (var k = 0; k < lista.length; k++) {
        if (lista[k].tipo === desafio) {
          total++;
          break;
        }
      }
    }
    return total;
  }

  function xpTotal(doc) {
    var xp = 0;
    var k;
    for (k in doc.base) xp += int(doc.base[k] && doc.base[k].xp);
    for (k in doc.feitos) {
      var f = doc.feitos[k];
      if (f && !f.del) xp += int(f.xp) * multiplicador(doc, f.dia);
    }
    for (var dia in doc.blocos) xp += blocosDe(doc, dia).length * Xp.BLOCO * multiplicador(doc, dia);
    for (k in doc.semanas) if (doc.semanas[k] && doc.semanas[k].premiada) xp += Xp.SEMANA;
    return Math.max(0, xp);
  }

  function primeiroDia(doc, hoje) {
    var primeiro = doc.criado || hoje;
    for (var k in doc.base) {
      var desde = doc.base[k] && doc.base[k].desde;
      if (desde && desde < primeiro) primeiro = desde;
    }
    return primeiro > hoje ? hoje : primeiro;
  }

  /**
   * Sequência de dias fechados (até ontem), recorde e turbos ganhos por
   * sequência. Um aparelho que entrou na conta já com uma sequência (o tablet)
   * traz o número dele em "base"; a partir do dia em que entrou, vale o maior.
   */
  function sequenciaDe(doc, hoje) {
    var bases = [];
    for (var k in doc.base) if (doc.base[k]) bases.push(doc.base[k]);
    var total = 0;
    var recorde = 0;
    var turbos = 0;
    var i;
    for (i = 0; i < bases.length; i++) recorde = Math.max(recorde, int(bases[i].recorde));
    var ontem = numDia(hoje) - 1;
    var n = numDia(primeiroDia(doc, hoje));
    if (ontem - n > 800) n = ontem - 800;
    var comeco = diaDeNum(n);
    for (i = 0; i < bases.length; i++) {
      if (bases[i].desde && bases[i].desde < comeco) total = Math.max(total, int(bases[i].seq));
    }
    for (; n <= ontem; n++) {
      var d = diaDeNum(n);
      for (i = 0; i < bases.length; i++) if (bases[i].desde === d) total = Math.max(total, int(bases[i].seq));
      var reg = doc.dias[d];
      var r = reg ? int(reg.r, 2) : 2;
      if (r === 1) {
        total++;
        if (total % DIAS_POR_TURBO === 0) turbos++;
      } else if (r === 0) {
        total = 0;
      }
      recorde = Math.max(recorde, total);
    }
    for (i = 0; i < bases.length; i++) {
      if (!bases[i].desde || bases[i].desde >= hoje) total = Math.max(total, int(bases[i].seq));
    }
    recorde = Math.max(recorde, total);
    return { total: total, recorde: recorde, turbos: turbos };
  }

  function turbosGuardados(doc, seq) {
    var daBase = 0;
    var marcoBase = 0;
    for (var k in doc.base) {
      daBase += int(doc.base[k] && doc.base[k].turbos);
      marcoBase = Math.max(marcoBase, int(doc.base[k] && doc.base[k].marco));
    }
    var porNivel = Math.floor(Math.max(0, int(doc.marco) - marcoBase) / NIVEIS_POR_RECOMPENSA);
    var usados = 0;
    for (var dia in doc.turbos) if (doc.turbos[dia]) usados++;
    return Math.max(0, daBase + porNivel + seq.turbos - usados);
  }

  function premioDoNivel(doc, nivel) {
    var linhas = String(doc.premios.v || '')
      .split('\n')
      .map(function (l) {
        return l.trim();
      })
      .filter(function (l) {
        return l.length > 0;
      });
    return linhas[Math.floor(nivel / NIVEIS_POR_RECOMPENSA) - 1] || '';
  }

  /** Tudo o que a tela mostra, calculado do documento. */
  function derivar(doc, agora) {
    var hoje = diaDe(agora);
    var indice = feitosPorDia(doc);
    var xp = xpTotal(doc);
    var nivel = nivelDe(xp);
    var seq = sequenciaDe(doc, hoje);
    var hojeR = diaCompleto(doc, indice, hoje);
    var deHoje = doDia(doc, hoje);
    var pendentes = deHoje.filter(function (m) {
      return vale(m, hoje) && !feita(indice, m, hoje);
    });
    var inicio = segundaDe(hoje);
    var s = doc.semanas[inicio] || null;
    var semana = null;
    if (s) {
      semana = {
        inicio: inicio,
        desafio: s.desafio,
        alvo: int(s.alvo, 5),
        premiada: !!s.premiada,
        progresso: progressoDaSemana(indice, inicio, s.desafio),
      };
    }
    var blocosHoje = blocosDe(doc, hoje);
    var sequenciaAtual = seq.total + (hojeR === 1 ? 1 : 0);
    return {
      hoje: hoje,
      xp: xp,
      nivel: nivel,
      rank: indiceDoRank(nivel),
      xpNoNivel: Math.max(0, xp - xpDoNivel(nivel)),
      xpDoNivelTodo: Math.max(1, xpDoNivel(nivel + 1) - xpDoNivel(nivel)),
      sequencia: seq.total,
      sequenciaAtual: sequenciaAtual,
      recorde: Math.max(seq.recorde, sequenciaAtual),
      turbos: turbosGuardados(doc, seq),
      turboLigado: multiplicador(doc, hoje) === 2,
      semana: semana,
      deHoje: deHoje,
      pendentes: pendentes,
      hojeCompleto: hojeR === 1,
      indice: indice,
      blocosHoje: blocosHoje,
      metaDeBlocos: metaDeBlocos(doc),
      horaDoBonus: horaDoBonus(doc),
      estudoFechado: !!(indice[hoje] || []).some(function (f) {
        return f.tipo === Tipo.ESTUDO;
      }),
      proximoMarco: (Math.floor(nivel / NIVEIS_POR_RECOMPENSA) + 1) * NIVEIS_POR_RECOMPENSA,
    };
  }

  // ---------- ajuste: o que decorre dos dados ----------

  /**
   * Deixa o documento coerente depois de uma mudança ou de uma junção: missão
   * da semana, fechamento do estudo de hoje, dias encerrados e recompensas de
   * nível. Com os mesmos dados, qualquer aparelho chega ao mesmo resultado.
   * Devolve se mudou alguma coisa.
   */
  function ajustar(doc, agora) {
    var mudou = false;
    var hoje = diaDe(agora);
    var k;

    // 1. Missão da semana: escolhida uma vez, entre os tipos de missão que existem.
    var inicio = segundaDe(hoje);
    if (!doc.semanas[inicio]) {
      var tipos = {};
      missoes(doc).forEach(function (m) {
        if (m.enabled) tipos[m.type] = true;
      });
      var opcoes = [Tipo.ESTUDO, Tipo.TREINO, Tipo.LEVANTAR].filter(function (t) {
        return tipos[t];
      });
      if (opcoes.length > 0) {
        var numero = Math.floor(numDia(inicio) / 7);
        var restantes = 8 - diaDaSemana(hoje);
        doc.semanas[inicio] = {
          desafio: opcoes[numero % opcoes.length],
          alvo: Math.max(1, Math.min(5, restantes)),
          c: agora,
          premiada: false,
          t: agora,
        };
        mudou = true;
      }
    }

    // 2. Estudo de hoje: fecha com a meta de blocos; antes da hora combinada, com bônus.
    var meta = metaDeBlocos(doc);
    var doDiaB = doc.blocos[hoje] || {};
    var horas = [];
    for (var n = 1; n <= BLOCOS_POR_DIA; n++) if (doDiaB[n] && doDiaB[n].on) horas.push(Number(doDiaB[n].t) || 0);
    horas.sort(function (a, b) {
      return a - b;
    });
    var chave = chaveDoFeito(hoje, ID_ESTUDO);
    var atual = doc.feitos[chave];
    var vivo = !!(atual && !atual.del);
    if (horas.length >= meta) {
      var quando = horas[meta - 1] || agora;
      var bonus = diaDe(quando) === hoje && horaDe(quando) < horaDoBonus(doc) ? Xp.BONUS_ESTUDO : 0;
      if (!vivo || int(atual.xp) !== bonus) {
        doc.feitos[chave] = { dia: hoje, alarmId: ID_ESTUDO, tipo: Tipo.ESTUDO, em: quando, xp: bonus, t: agora, del: false };
        mudou = true;
      }
    } else if (vivo) {
      doc.feitos[chave] = { dia: hoje, alarmId: ID_ESTUDO, tipo: Tipo.ESTUDO, em: atual.em, xp: int(atual.xp), t: agora, del: true };
      mudou = true;
    }

    var indice = feitosPorDia(doc);

    // 3. Missão da semana cumprida (ou desfeita, se um bloco foi desmarcado).
    var s = doc.semanas[inicio];
    if (s) {
      var cumprida = progressoDaSemana(indice, inicio, s.desafio) >= int(s.alvo, 5);
      if (!!s.premiada !== cumprida) {
        s.premiada = cumprida;
        s.t = agora;
        mudou = true;
      }
    }

    // 4. Dias encerrados. Um dia já fechado só é refeito se chegaram registros novos dele.
    var quantos = {};
    for (k in doc.feitos) {
      var f = doc.feitos[k];
      if (f && !f.del) quantos[f.dia] = (quantos[f.dia] || 0) + 1;
    }
    var hojeN = numDia(hoje);
    var d = numDia(primeiroDia(doc, hoje));
    if (hojeN - 1 - d > 800) d = hojeN - 1 - 800;
    for (; d < hojeN; d++) {
      var dia = diaDeNum(d);
      var reg = doc.dias[dia];
      var qtd = quantos[dia] || 0;
      if (reg && int(reg.n) === qtd) continue;
      var r = diaCompleto(doc, indice, dia);
      doc.dias[dia] = { r: r, n: qtd, t: agora };
      mudou = true;
    }

    // 5. Recompensa de nível: cada múltiplo de 5 alcançado fica pago para sempre.
    var alvo = Math.floor(nivelDe(xpTotal(doc)) / NIVEIS_POR_RECOMPENSA) * NIVEIS_POR_RECOMPENSA;
    if (alvo > int(doc.marco)) {
      doc.marco = alvo;
      mudou = true;
    }
    return mudou;
  }

  // ---------- junção de dois documentos ----------

  function tempo(x) {
    return x && isFinite(Number(x.t)) ? Number(x.t) : 0;
  }

  /** O mais recente dos dois; no empate, quem [prefere] indicar, senão o primeiro. */
  function maisNovo(a, b, prefere) {
    if (!a) return b;
    if (!b) return a;
    var ta = tempo(a);
    var tb = tempo(b);
    if (tb > ta) return b;
    if (ta > tb) return a;
    if (prefere && prefere(b) && !prefere(a)) return b;
    return a;
  }

  function unir(a, b, prefere) {
    var r = {};
    var k;
    a = obj(a);
    b = obj(b);
    for (k in a) r[k] = maisNovo(a[k], b[k], prefere);
    for (k in b) if (!(k in r)) r[k] = b[k];
    return r;
  }

  function apagado(x) {
    return !!x.del;
  }

  function ligado(x) {
    return !!x.on;
  }

  function unirMenor(a, b) {
    var r = {};
    var k;
    a = obj(a);
    b = obj(b);
    for (k in a) r[k] = a[k];
    for (k in b) {
      var va = Number(r[k]) || 0;
      var vb = Number(b[k]) || 0;
      r[k] = va > 0 && vb > 0 ? Math.min(va, vb) : va > 0 ? va : vb;
    }
    return r;
  }

  function copia(x) {
    return JSON.parse(JSON.stringify(x));
  }

  /**
   * Junta o documento deste aparelho com o que veio de outro. Cada item fica
   * com a mudança mais recente; o que só existe de um lado é mantido.
   */
  function juntar(meu, outro) {
    var a = arrumar(copia(meu));
    if (!outro) return a;
    var b = arrumar(copia(outro));
    var r = { v: 1 };
    r.criado = a.criado && b.criado ? (a.criado < b.criado ? a.criado : b.criado) : a.criado || b.criado;
    r.base = unir(a.base, b.base);
    r.missoes = unir(a.missoes, b.missoes, apagado);
    r.feitos = unir(a.feitos, b.feitos, apagado);
    r.blocos = {};
    var dia;
    for (dia in a.blocos) r.blocos[dia] = unir(a.blocos[dia], b.blocos[dia], ligado);
    for (dia in b.blocos) if (!(dia in r.blocos)) r.blocos[dia] = obj(b.blocos[dia]);
    r.turbos = unirMenor(a.turbos, b.turbos);
    r.dias = unir(a.dias, b.dias);
    r.semanas = {};
    var k;
    for (k in a.semanas) r.semanas[k] = juntarSemana(a.semanas[k], b.semanas[k]);
    for (k in b.semanas) if (!(k in r.semanas)) r.semanas[k] = b.semanas[k];
    r.ideias = unir(a.ideias, b.ideias, apagado);
    r.premios = maisNovo(a.premios, b.premios);
    r.pegos = unirMenor(a.pegos, b.pegos);
    r.cfg = unir(a.cfg, b.cfg);
    r.marco = Math.max(a.marco, b.marco);
    return r;
  }

  /** A missão da semana é a de quem a criou primeiro; o "cumprida" é o mais recente. */
  function juntarSemana(a, b) {
    if (!a) return b;
    if (!b) return a;
    var ca = Number(a.c) || 0;
    var cb = Number(b.c) || 0;
    var primeira = cb < ca ? b : a;
    var recente = tempo(b) > tempo(a) ? b : a;
    return { desafio: primeira.desafio, alvo: primeira.alvo, c: primeira.c, premiada: !!recente.premiada, t: tempo(recente) };
  }

  /** Comparação de conteúdo, sem ligar para a ordem das chaves. */
  function igual(a, b) {
    if (a === b) return true;
    if (typeof a !== typeof b || a === null || b === null) return false;
    if (typeof a !== 'object') return false;
    if (Array.isArray(a) !== Array.isArray(b)) return false;
    if (Array.isArray(a)) {
      if (a.length !== b.length) return false;
      for (var i = 0; i < a.length; i++) if (!igual(a[i], b[i])) return false;
      return true;
    }
    var ka = Object.keys(a);
    if (ka.length !== Object.keys(b).length) return false;
    for (var k = 0; k < ka.length; k++) {
      if (!Object.prototype.hasOwnProperty.call(b, ka[k])) return false;
      if (!igual(a[ka[k]], b[ka[k]])) return false;
    }
    return true;
  }

  // ---------- ações (cada uma carimba a hora e depois ajusta) ----------

  /** Marca ou desmarca um bloco de estudo de hoje. */
  function alternarBloco(doc, numero, agora) {
    if (numero < 1 || numero > BLOCOS_POR_DIA) return;
    var hoje = diaDe(agora);
    var doDiaB = (doc.blocos[hoje] = doc.blocos[hoje] || {});
    var atual = doDiaB[numero];
    doDiaB[numero] = { on: !(atual && atual.on), t: agora };
    ajustar(doc, agora);
  }

  /** Conclui hoje uma missão de treino ou simples. Devolve se concluiu. */
  function concluir(doc, m, agora) {
    if (!m || m.type === Tipo.ESTUDO || m.type === Tipo.LEVANTAR) return false;
    var hoje = diaDe(agora);
    var chave = chaveDoFeito(hoje, m.id);
    var atual = doc.feitos[chave];
    if (atual && !atual.del) return false;
    doc.feitos[chave] = { dia: hoje, alarmId: m.id, tipo: m.type, em: agora, xp: xpDaMissao(m.type), t: agora, del: false };
    ajustar(doc, agora);
    return true;
  }

  /** Liga um turbo guardado: o dia inteiro vale o dobro, incluindo o que já foi feito. */
  function usarTurbo(doc, agora) {
    var v = derivar(doc, agora);
    if (v.turbos <= 0 || v.turboLigado) return false;
    doc.turbos[v.hoje] = agora;
    ajustar(doc, agora);
    return true;
  }

  function anotarIdeia(doc, texto, agora) {
    var limpo = String(texto || '').trim().slice(0, 500);
    if (!limpo) return;
    var id = agora;
    while (doc.ideias[id]) id++;
    doc.ideias[id] = { id: id, em: agora, texto: limpo, t: agora, del: false };
  }

  function excluirIdeia(doc, id, agora) {
    var i = doc.ideias[id];
    if (!i) return;
    doc.ideias[id] = { id: i.id, em: i.em, texto: i.texto, t: agora, del: true };
  }

  /** Ideias guardadas, da mais recente para a mais antiga. */
  function ideias(doc) {
    var lista = [];
    for (var k in doc.ideias) if (doc.ideias[k] && !doc.ideias[k].del) lista.push(doc.ideias[k]);
    lista.sort(function (a, b) {
      return b.em - a.em || b.id - a.id;
    });
    return lista;
  }

  function definirPremios(doc, texto, agora) {
    var limpo = String(texto || '').slice(0, 2000);
    doc.premios = { v: limpo.trim() ? limpo : PREMIOS_PADRAO, t: agora };
  }

  function pegarPremio(doc, nivel, agora) {
    if (!doc.pegos[nivel]) doc.pegos[nivel] = agora;
  }

  function definirCfg(doc, chave, valor, agora) {
    doc.cfg[chave] = { v: valor, t: agora };
    ajustar(doc, agora);
  }

  /** Cria ou altera uma missão. Sem id, cria uma com número próprio. */
  function salvarMissao(doc, m, agora, sorteio) {
    var id = gravarMissao(doc, m, agora, sorteio);
    ajustar(doc, agora);
    return id;
  }

  function gravarMissao(doc, m, agora, sorteio) {
    var id = m.id;
    if (!id) {
      do {
        id = 100000 + Math.floor((sorteio || Math.random)() * 899000000);
      } while (doc.missoes[id]);
    }
    var antiga = doc.missoes[id];
    doc.missoes[id] = {
      id: id,
      hour: Math.min(23, Math.max(0, int(m.hour))),
      minute: Math.min(59, Math.max(0, int(m.minute))),
      days: (Array.isArray(m.days) ? m.days : [1, 2, 3, 4, 5, 6, 7])
        .map(function (x) {
          return int(x);
        })
        .filter(function (x) {
          return x >= 1 && x <= 7;
        })
        .sort(),
      enabled: m.enabled !== false,
      label: String(m.label || '').slice(0, 60),
      type: m.type,
      counts: m.counts !== false,
      rings: !!m.rings,
      minutes: Math.min(600, Math.max(1, int(m.minutes, 30) || 30)),
      minPercent: Math.min(100, Math.max(0, m.minPercent === undefined ? 60 : int(m.minPercent, 60))),
      url: String(m.url || '').slice(0, 300),
      createdAt: antiga && antiga.createdAt ? antiga.createdAt : m.createdAt || agora,
      t: agora,
      del: false,
    };
    return id;
  }

  function excluirMissao(doc, id, agora) {
    var m = doc.missoes[id];
    if (!m) return;
    var c = copia(m);
    c.del = true;
    c.t = agora;
    doc.missoes[id] = c;
    ajustar(doc, agora);
  }

  /** As duas missões com que uma conta nova começa. */
  function missoesIniciais(doc, agora) {
    gravarMissao(doc, { id: 100001, hour: 6, minute: 0, type: Tipo.ESTUDO, rings: false, createdAt: agora }, agora);
    gravarMissao(doc, { id: 100002, hour: 15, minute: 0, type: Tipo.TREINO, rings: true, createdAt: agora }, agora);
    ajustar(doc, agora);
  }

  var api = {
    BLOCOS_POR_DIA: BLOCOS_POR_DIA,
    MINUTOS_DO_BLOCO: MINUTOS_DO_BLOCO,
    NIVEIS_POR_RECOMPENSA: NIVEIS_POR_RECOMPENSA,
    DIAS_POR_TURBO: DIAS_POR_TURBO,
    ID_ESTUDO: ID_ESTUDO,
    URL_TREINO: URL_TREINO,
    PREMIOS_PADRAO: PREMIOS_PADRAO,
    Xp: Xp,
    Tipo: Tipo,
    RANKS: RANKS,
    xpDaMissao: xpDaMissao,
    xpDoNivel: xpDoNivel,
    nivelDe: nivelDe,
    indiceDoRank: indiceDoRank,
    tempoDeBlocos: tempoDeBlocos,
    nomeDoTipo: nomeDoTipo,
    nomeDaMissao: nomeDaMissao,
    diaDe: diaDe,
    numDia: numDia,
    somaDias: somaDias,
    diaDaSemana: diaDaSemana,
    segundaDe: segundaDe,
    horaDe: horaDe,
    minutoDoDia: minutoDoDia,
    novoDoc: novoDoc,
    arrumar: arrumar,
    cfg: cfg,
    missoes: missoes,
    doDia: doDia,
    vale: vale,
    feita: feita,
    blocosDe: blocosDe,
    multiplicador: multiplicador,
    premioDoNivel: premioDoNivel,
    xpTotal: xpTotal,
    derivar: derivar,
    ajustar: ajustar,
    juntar: juntar,
    igual: igual,
    copia: copia,
    alternarBloco: alternarBloco,
    concluir: concluir,
    usarTurbo: usarTurbo,
    anotarIdeia: anotarIdeia,
    excluirIdeia: excluirIdeia,
    ideias: ideias,
    definirPremios: definirPremios,
    pegarPremio: pegarPremio,
    definirCfg: definirCfg,
    salvarMissao: salvarMissao,
    excluirMissao: excluirMissao,
    missoesIniciais: missoesIniciais,
    definirFuso: function (minutos) {
      fusoFixo = minutos;
    },
  };

  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else raiz.Nucleo = api;
})(typeof self !== 'undefined' ? self : this);
