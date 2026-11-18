/*
 * Casos de junção calculados pelo site, para o app Android conferir que chega
 * aos mesmos resultados (app/src/test/.../NucleoTest.kt).
 *
 * Uso: node web/vetores.js > ci-out/vetores.json
 * Cada caso tem dois documentos (a, b), a hora, o documento esperado depois de
 * juntar e ajustar, e os números que a tela mostra.
 */
(function () {
  'use strict';
  var N = typeof module !== 'undefined' && module.exports ? require('./nucleo.js') : self.Nucleo;
  var FUSO = -180;
  N.definirFuso(FUSO);
  var MIN = 60000;
  var HORA = 60 * MIN;
  var DIA = 24 * HORA;
  /** Quarta-feira, 18/11/2026, às 10:00 de Brasília. */
  var T0 = Date.UTC(2026, 10, 18, 13, 0, 0);
  var casos = [];

  function contaNova(agora) {
    var d = N.novoDoc(agora);
    N.missoesIniciais(d, agora);
    return d;
  }

  function caso(nome, a, b, agora) {
    var junto = N.juntar(a, b);
    N.ajustar(junto, agora);
    var v = N.derivar(junto, agora);
    casos.push({
      nome: nome,
      fuso: FUSO,
      agora: agora,
      a: N.copia(a),
      b: b ? N.copia(b) : null,
      junto: junto,
      deriv: {
        xp: v.xp,
        nivel: v.nivel,
        sequencia: v.sequencia,
        sequenciaAtual: v.sequenciaAtual,
        recorde: v.recorde,
        turbos: v.turbos,
        turboLigado: v.turboLigado,
        hojeCompleto: v.hojeCompleto,
      },
    });
  }

  function nosDoisSentidos(nome, a, b, agora) {
    caso(nome + ' (a com b)', a, b, agora);
    caso(nome + ' (b com a)', b, a, agora);
  }

  var n;

  // Conta recém-criada, sozinha.
  caso('conta nova', contaNova(T0), null, T0 + MIN);

  // Um bloco em cada aparelho, no mesmo dia.
  (function () {
    var base = contaNova(T0 - HORA);
    var site = N.copia(base);
    var tablet = N.copia(base);
    N.alternarBloco(site, 1, T0 + 1 * MIN);
    N.alternarBloco(site, 2, T0 + 2 * MIN);
    N.alternarBloco(tablet, 3, T0 + 3 * MIN);
    N.alternarBloco(tablet, 4, T0 + 4 * MIN);
    nosDoisSentidos('blocos em dois aparelhos', site, tablet, T0 + 10 * MIN);
    var junto = N.juntar(site, tablet);
    N.ajustar(junto, T0 + 10 * MIN);
    var site2 = N.copia(junto);
    N.alternarBloco(site2, 1, T0 + 11 * MIN);
    nosDoisSentidos('bloco desmarcado depois', site2, tablet, T0 + 12 * MIN);
  })();

  // O tablet fechou a meta com bônus; o site, atrasado, fechou depois sem bônus.
  (function () {
    var base = contaNova(T0 - HORA);
    for (n = 1; n <= 3; n++) N.alternarBloco(base, n, T0 + n * MIN);
    var tablet = N.copia(base);
    var site = N.copia(base);
    N.alternarBloco(tablet, 4, T0 + 3 * HORA + 50 * MIN);
    N.alternarBloco(site, 7, T0 + 5 * HORA);
    nosDoisSentidos('bônus de estudo em disputa', site, tablet, T0 + 6 * HORA);
  })();

  // Missões, ideias, prêmios, ajustes e turbo mexidos dos dois lados.
  (function () {
    var base = contaNova(T0 - HORA);
    base.base.tablet = { xp: 480, seq: 3, recorde: 6, turbos: 2, marco: 0, desde: '2026-11-18', t: T0 - HORA };
    N.ajustar(base, T0 - HORA);
    var site = N.copia(base);
    var tablet = N.copia(base);
    N.concluir(site, site.missoes[100002], T0 + MIN);
    N.anotarIdeia(site, 'Trocar o tênis', T0 + 2 * MIN);
    var simples = N.salvarMissao(site, { hour: 11, minute: 30, type: 'simples', label: 'Beber água', rings: true }, T0 + 3 * MIN, function () { return 0.25; });
    N.concluir(site, site.missoes[simples], T0 + 4 * MIN);
    N.definirCfg(site, 'studyBlocks', 3, T0 + 5 * MIN);
    N.anotarIdeia(tablet, 'Rever licitações', T0 + 6 * MIN);
    N.definirPremios(tablet, 'um sorvete\numa pizza', T0 + 7 * MIN);
    N.usarTurbo(tablet, T0 + 8 * MIN);
    for (n = 1; n <= 3; n++) N.alternarBloco(tablet, n, T0 + (8 + n) * MIN);
    N.pegarPremio(tablet, 5, T0 + 12 * MIN);
    N.definirCfg(tablet, 'examDate', '13/12/2026', T0 + 13 * MIN);
    nosDoisSentidos('mudanças variadas', site, tablet, T0 + 20 * MIN);
    var junto = N.juntar(site, tablet);
    N.ajustar(junto, T0 + 20 * MIN);
    var site2 = N.copia(junto);
    N.excluirIdeia(site2, N.ideias(site2)[0].id, T0 + 21 * MIN);
    N.excluirMissao(site2, simples, T0 + 22 * MIN);
    N.alternarBloco(site2, 2, T0 + 23 * MIN);
    nosDoisSentidos('apagar e desmarcar depois da junção', site2, tablet, T0 + 30 * MIN);
  })();

  // Oito dias seguidos, a missão da semana e o turbo dos 7 dias; depois, um dia perdido.
  (function () {
    var d = contaNova(T0 - HORA);
    for (var dia = 0; dia < 8; dia++) {
      var manha = T0 + dia * DIA;
      for (n = 1; n <= 4; n++) N.alternarBloco(d, n, manha + n * MIN);
      N.concluir(d, d.missoes[100002], manha + HORA);
    }
    caso('oito dias seguidos', d, null, T0 + 8 * DIA);
    caso('dois dias sem nada', d, null, T0 + 10 * DIA);
    var outro = N.copia(d);
    N.ajustar(outro, T0 + 8 * DIA);
    for (n = 1; n <= 5; n++) N.alternarBloco(outro, n, T0 + 8 * DIA + n * MIN);
    N.concluir(outro, outro.missoes[100002], T0 + 8 * DIA + HORA);
    nosDoisSentidos('nono dia feito em outro aparelho', d, outro, T0 + 9 * DIA);
  })();

  // Um aparelho fechou o dia sem saber do que o outro tinha feito.
  (function () {
    var base = contaNova(T0 - HORA);
    var tablet = N.copia(base);
    var site = N.copia(base);
    for (n = 1; n <= 4; n++) N.alternarBloco(tablet, n, T0 + n * MIN);
    N.concluir(tablet, tablet.missoes[100002], T0 + HORA);
    N.ajustar(site, T0 + DIA);
    N.ajustar(tablet, T0 + DIA + MIN);
    nosDoisSentidos('dia fechado sem os dados do outro', site, tablet, T0 + DIA + 2 * MIN);
  })();

  // O tablet entra numa conta que o site já tinha começado.
  (function () {
    var site = contaNova(T0 - 2 * DIA);
    N.ajustar(site, T0 - 2 * DIA);
    for (n = 1; n <= 4; n++) N.alternarBloco(site, n, T0 - DIA + n * MIN);
    N.ajustar(site, T0);
    var tablet = N.novoDoc(T0);
    tablet.missoes[1] = { id: 1, hour: 5, minute: 0, days: [1, 2, 3, 4, 5, 6, 7], enabled: true, label: '', type: 'levantar', counts: true, rings: true, minutes: 30, minPercent: 60, url: '', createdAt: T0 - 30 * DIA, t: T0, del: false };
    tablet.missoes[2] = { id: 2, hour: 6, minute: 0, days: [1, 2, 3, 4, 5], enabled: true, label: 'Estudo', type: 'estudo', counts: true, rings: false, minutes: 30, minPercent: 60, url: '', createdAt: T0 - 30 * DIA, t: T0, del: false };
    tablet.feitos['2026-11-18|1'] = { dia: '2026-11-18', alarmId: 1, tipo: 'levantar', em: T0 - 4 * HORA, xp: 30, t: T0, del: false };
    tablet.feitos['2026-11-17|1'] = { dia: '2026-11-17', alarmId: 1, tipo: 'levantar', em: T0 - 28 * HORA, xp: 30, t: T0, del: false };
    tablet.blocos['2026-11-18'] = { 1: { on: true, t: T0 - HORA }, 2: { on: true, t: T0 - HORA }, 5: { on: false, t: T0 - 30 * MIN } };
    tablet.semanas['2026-11-16'] = { desafio: 'levantar', alvo: 3, c: T0, premiada: false, t: T0 };
    tablet.base.tablet = { xp: 2100, seq: 12, recorde: 20, turbos: 3, marco: 5, desde: '2026-11-18', t: T0 };
    tablet.turbos['2026-11-18'] = T0 - 2 * HORA;
    tablet.premios = { v: N.PREMIOS_PADRAO, t: 1 };
    tablet.cfg = { studyBlocks: { v: 4, t: 1 }, studyBonusHour: { v: 14, t: 1 }, examDate: { v: '13/12/2026', t: 1 } };
    tablet.marco = 5;
    nosDoisSentidos('tablet entra na conta do site', tablet, site, T0 + MIN);
    caso('tablet entra numa conta vazia', tablet, null, T0 + MIN);
  })();

  if (typeof module !== 'undefined' && module.exports) process.stdout.write(JSON.stringify(casos));
  else self.vetores = casos;
})();
