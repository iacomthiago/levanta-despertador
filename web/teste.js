/* Testes das regras e da junção. Abrir teste.html no navegador, ou rodar com node teste.js. */
(function () {
  'use strict';
  var N = typeof module !== 'undefined' && module.exports ? require('./nucleo.js') : self.Nucleo;
  var linhas = [];
  var falhas = 0;

  function confere(nome, obtido, esperado) {
    var ok = N.igual(obtido, esperado);
    if (!ok) falhas++;
    linhas.push((ok ? 'ok     ' : 'FALHOU ') + nome + (ok ? '' : '  obtido=' + JSON.stringify(obtido) + '  esperado=' + JSON.stringify(esperado)));
  }

  // Horário de Brasília fixo, para o resultado não depender de onde o teste roda.
  N.definirFuso(-180);
  var MIN = 60000;
  var HORA = 60 * MIN;
  var DIA = 24 * HORA;
  /** Quarta-feira, 18/11/2026, às 10:00 de Brasília. */
  var T0 = Date.UTC(2026, 10, 18, 13, 0, 0);

  function contaNova(agora) {
    var d = N.novoDoc(agora);
    N.missoesIniciais(d, agora);
    return d;
  }

  // ----- níveis -----
  confere('nível 1 com 0 XP', N.nivelDe(0), 1);
  confere('nível 2 com 100 XP', N.nivelDe(100), 2);
  confere('nível 3 com 250 XP', N.nivelDe(250), 3);
  confere('nível 5 pede 700 XP', N.xpDoNivel(5), 700);
  confere('faixa do nível 6', N.RANKS[N.indiceDoRank(6)].nome, 'Segunda marcha');
  confere('dia da semana de 18/11/2026', N.diaDaSemana('2026-11-18'), 3);
  confere('segunda da semana', N.segundaDe('2026-11-18'), '2026-11-16');
  confere('dia de T0', N.diaDe(T0), '2026-11-18');
  confere('tempo de 5 blocos', N.tempoDeBlocos(5), '2 h 30 min');

  // ----- o mesmo caminho do teste do emulador: 0, 150, 160, 320 com turbo, 400 -----
  (function () {
    var d = contaNova(T0 - HORA);
    d.base.tablet = { xp: 0, seq: 0, recorde: 0, turbos: 1, marco: 0, desde: '2026-11-18', t: 1 };
    N.ajustar(d, T0 - HORA);
    confere('começa com 0 XP', N.derivar(d, T0).xp, 0);
    for (var n = 1; n <= 4; n++) N.alternarBloco(d, n, T0 + n * MIN);
    var v = N.derivar(d, T0 + 10 * MIN);
    confere('4 blocos antes das 14 h: 150 XP', v.xp, 150);
    confere('estudo fechado', v.estudoFechado, true);
    var simples = N.salvarMissao(d, { hour: 11, minute: 0, type: 'simples', label: 'Água' }, T0 + 11 * MIN, function () { return 0.5; });
    N.concluir(d, d.missoes[simples], T0 + 12 * MIN);
    confere('missão simples: 160 XP', N.derivar(d, T0 + 12 * MIN).xp, 160);
    confere('concluir de novo não soma', N.concluir(d, d.missoes[simples], T0 + 13 * MIN), false);
    confere('turbo ligado', N.usarTurbo(d, T0 + 14 * MIN), true);
    v = N.derivar(d, T0 + 14 * MIN);
    confere('turbo dobra o dia: 320 XP', v.xp, 320);
    confere('turbo gasto', v.turbos, 0);
    confere('segundo turbo não liga', N.usarTurbo(d, T0 + 15 * MIN), false);
    N.concluir(d, d.missoes[100002], T0 + 16 * MIN);
    v = N.derivar(d, T0 + 16 * MIN);
    confere('treino com turbo: 400 XP', v.xp, 400);
    confere('hoje completo', v.hojeCompleto, true);
    confere('sequência de hoje', v.sequenciaAtual, 1);
    confere('ajustar de novo não muda nada', N.ajustar(d, T0 + 17 * MIN), false);

    // Desmarcar um bloco devolve o bloco e o bônus (em dobro, por causa do turbo).
    N.alternarBloco(d, 4, T0 + 20 * MIN);
    v = N.derivar(d, T0 + 20 * MIN);
    confere('bloco desmarcado: volta para 250', v.xp, 250);
    confere('estudo reaberto', v.estudoFechado, false);
    // Marcar de novo depois das 14 h fecha sem bônus.
    N.alternarBloco(d, 4, T0 + 5 * HORA);
    v = N.derivar(d, T0 + 5 * HORA);
    confere('remarcado às 15 h: sem bônus', v.xp, 300);
    confere('estudo fechado de novo', v.estudoFechado, true);
  })();

  // ----- junção: um bloco no site e outro no tablet, no mesmo dia -----
  (function () {
    var base = contaNova(T0 - HORA);
    var site = N.copia(base);
    var tablet = N.copia(base);
    N.alternarBloco(site, 1, T0 + 1 * MIN);
    N.alternarBloco(site, 2, T0 + 2 * MIN);
    N.alternarBloco(tablet, 3, T0 + 3 * MIN);
    N.alternarBloco(tablet, 4, T0 + 4 * MIN);
    var agora = T0 + 10 * MIN;
    var a = N.juntar(site, tablet);
    N.ajustar(a, agora);
    var b = N.juntar(tablet, site);
    N.ajustar(b, agora);
    confere('junção: os 4 blocos aparecem', N.blocosDe(a, '2026-11-18'), [1, 2, 3, 4]);
    confere('junção: a ordem não importa', a, b);
    confere('junção: 150 XP (4 blocos e bônus)', N.derivar(a, agora).xp, 150);
    confere('juntar consigo mesmo não muda', N.juntar(a, a), a);
    confere('juntar de novo com um dos lados não muda', N.juntar(a, site), a);

    // Depois da junção, o site desmarca o bloco 1; a cópia velha do tablet não o ressuscita.
    var site2 = N.copia(a);
    N.alternarBloco(site2, 1, agora + MIN);
    var c = N.juntar(site2, tablet);
    N.ajustar(c, agora + 2 * MIN);
    confere('desmarcado mais tarde continua desmarcado', N.blocosDe(c, '2026-11-18'), [2, 3, 4]);
    confere('e o XP acompanha', N.derivar(c, agora + 2 * MIN).xp, 75);
  })();

  // ----- junção: o tablet fechou a meta com bônus; o site, atrasado, fechou depois sem bônus -----
  (function () {
    var base = contaNova(T0 - HORA);
    for (var n = 1; n <= 3; n++) N.alternarBloco(base, n, T0 + n * MIN);
    var tablet = N.copia(base);
    var site = N.copia(base);
    N.alternarBloco(tablet, 4, T0 + 3 * HORA + 50 * MIN); // 13:50
    N.alternarBloco(site, 7, T0 + 5 * HORA); // 15:00, sem saber do bloco 4
    confere('tablet sozinho: bônus', N.derivar(tablet, T0 + 6 * HORA).xp, 150);
    confere('site sozinho: sem bônus', N.derivar(site, T0 + 6 * HORA).xp, 100);
    var j = N.juntar(site, tablet);
    N.ajustar(j, T0 + 6 * HORA);
    confere('juntos: 5 blocos e o bônus das 13:50', N.derivar(j, T0 + 6 * HORA).xp, 175);
    var k = N.juntar(tablet, site);
    N.ajustar(k, T0 + 6 * HORA);
    confere('nos dois sentidos', N.derivar(k, T0 + 6 * HORA).xp, 175);
  })();

  // ----- junção: missão concluída num lado, ideia no outro, prêmios editados -----
  (function () {
    var base = contaNova(T0 - HORA);
    var site = N.copia(base);
    var tablet = N.copia(base);
    N.concluir(site, site.missoes[100002], T0 + MIN);
    N.anotarIdeia(tablet, 'Rever licitações', T0 + 2 * MIN);
    N.definirPremios(tablet, 'um sorvete', T0 + 3 * MIN);
    N.anotarIdeia(site, 'Trocar o tênis', T0 + 4 * MIN);
    var j = N.juntar(site, tablet);
    N.ajustar(j, T0 + 5 * MIN);
    confere('treino do site chega', N.derivar(j, T0 + 5 * MIN).xp, 40);
    confere('as duas ideias ficam', N.ideias(j).map(function (i) { return i.texto; }), ['Trocar o tênis', 'Rever licitações']);
    confere('prêmio mais recente vale', N.premioDoNivel(j, 5), 'um sorvete');
    var id = N.ideias(j)[0].id;
    var site2 = N.copia(j);
    N.excluirIdeia(site2, id, T0 + 6 * MIN);
    var j2 = N.juntar(tablet, site2);
    confere('ideia apagada não volta', N.ideias(j2).map(function (i) { return i.texto; }), ['Rever licitações']);
  })();

  // ----- sequência de dias, turbo a cada 7 dias e missão da semana -----
  (function () {
    var inicio = T0 - HORA; // quarta, 09:00
    var d = contaNova(inicio);
    N.ajustar(d, inicio);
    var v = N.derivar(d, inicio);
    confere('missão da semana criada na quarta: alvo 5', v.semana.alvo, 5);
    confere('desafio da semana', v.semana.desafio, ['estudo', 'treino'][Math.floor(N.numDia('2026-11-16') / 7) % 2]);
    var desafio = v.semana.desafio;
    var xpEsperado = 0;
    for (var dia = 0; dia < 8; dia++) {
      var manha = T0 + dia * DIA;
      for (var n = 1; n <= 4; n++) N.alternarBloco(d, n, manha + n * MIN);
      N.concluir(d, d.missoes[100002], manha + HORA);
      xpEsperado += 150 + 40;
    }
    // Dia 0 = quarta 18; a semana seguinte começa na segunda 23 (dia 5).
    var fim = T0 + 8 * DIA; // quinta, 26/11
    N.ajustar(d, fim);
    v = N.derivar(d, fim);
    confere('8 dias fechados', v.sequencia, 8);
    confere('recorde', v.recorde, 8);
    confere('7 dias seguidos dão 1 turbo', v.turbos, 1 + Math.floor(Math.max(0, d.marco) / 5));
    // Primeira semana (qua a dom): 5 dias com o desafio feito, alvo 5: cumprida. A segunda (seg a qua): 3 de 5.
    confere('semana 1 cumprida', d.semanas['2026-11-16'].premiada, true);
    confere('semana 2 em andamento', v.semana.progresso, 3);
    confere('XP com a missão da semana', v.xp, xpEsperado + 150);
    confere('nível e recompensa', [v.nivel, d.marco], [N.nivelDe(xpEsperado + 150), Math.floor(N.nivelDe(xpEsperado + 150) / 5) * 5]);

    // Um dia sem nada: a sequência zera no fechamento do dia seguinte.
    var depois = fim + 2 * DIA; // sábado 28; quinta sem nada e sexta sem nada
    N.ajustar(d, depois);
    v = N.derivar(d, depois);
    confere('dia perdido zera a sequência', v.sequencia, 0);
    confere('recorde fica', v.recorde, 8);
    confere('desafio usado no teste é válido', desafio === 'estudo' || desafio === 'treino', true);
  })();

  // ----- dia fechado sem dados e completado depois por outro aparelho -----
  (function () {
    var base = contaNova(T0 - HORA);
    var tablet = N.copia(base);
    var site = N.copia(base);
    // Tablet, sem internet, faz tudo na quarta.
    for (var n = 1; n <= 4; n++) N.alternarBloco(tablet, n, T0 + n * MIN);
    N.concluir(tablet, tablet.missoes[100002], T0 + HORA);
    // Site abre na quinta sem saber de nada: fecha a quarta como perdida.
    var quinta = T0 + DIA;
    N.ajustar(site, quinta);
    confere('site sozinho: quarta perdida', site.dias['2026-11-18'].r, 0);
    N.ajustar(tablet, quinta + MIN);
    var j = N.juntar(site, tablet);
    N.ajustar(j, quinta + 2 * MIN);
    confere('com os dados do tablet a quarta conta', j.dias['2026-11-18'].r, 1);
    confere('sequência 1', N.derivar(j, quinta + 2 * MIN).sequencia, 1);
    var j2 = N.juntar(tablet, site);
    N.ajustar(j2, quinta + 2 * MIN);
    confere('nos dois sentidos dá o mesmo', j2, j);
  })();

  // ----- o tablet entra numa conta trazendo o que já tinha -----
  (function () {
    var d = contaNova(T0 - 2 * DIA);
    N.ajustar(d, T0 - 2 * DIA);
    N.ajustar(d, T0);
    d.base.tablet = { xp: 1234, seq: 5, recorde: 9, turbos: 2, marco: 5, desde: '2026-11-18', t: T0 };
    N.ajustar(d, T0);
    var v = N.derivar(d, T0);
    confere('XP que o tablet já tinha', v.xp, 1234);
    confere('sequência do tablet', v.sequencia, 5);
    confere('recorde do tablet', v.recorde, 9);
    confere('turbos do tablet (níveis já pagos não pagam de novo)', v.turbos, 2);
    confere('marco acompanha o nível', d.marco, Math.floor(N.nivelDe(1234) / 5) * 5);
    // No dia seguinte, com o dia 18 completo, a sequência continua de 5 para 6.
    for (var n = 1; n <= 4; n++) N.alternarBloco(d, n, T0 + n * MIN);
    N.concluir(d, d.missoes[100002], T0 + HORA);
    N.ajustar(d, T0 + DIA);
    confere('sequência continua: 6', N.derivar(d, T0 + DIA).sequencia, 6);
  })();

  // ----- missão criada depois da própria hora não conta no dia da criação -----
  (function () {
    var d = N.novoDoc(T0);
    var id = N.salvarMissao(d, { hour: 9, minute: 0, type: 'simples', label: 'Cedo' }, T0, function () { return 0.1; });
    confere('criada às 10 h para as 9 h: hoje não vale', N.vale(d.missoes[id], '2026-11-18'), false);
    confere('amanhã vale', N.vale(d.missoes[id], '2026-11-19'), true);
    var id2 = N.salvarMissao(d, { hour: 10, minute: 0, type: 'simples', label: 'Na hora' }, T0, function () { return 0.2; });
    confere('criada às 10:00 para as 10:00: vale', N.vale(d.missoes[id2], '2026-11-18'), true);
    N.excluirMissao(d, id, T0 + MIN);
    confere('missão apagada some', N.missoes(d).map(function (m) { return m.label; }), ['Na hora']);
  })();

  var resumo = (falhas === 0 ? 'TUDO CERTO: ' : 'FALHAS: ' + falhas + ' de ') + linhas.length + ' conferências';
  var texto = resumo + '\n\n' + linhas.join('\n');
  if (typeof document !== 'undefined') {
    var saida = document.getElementById('saida');
    saida.textContent = texto;
    saida.className = falhas === 0 ? 'ok' : 'falha';
    self.resultado = { falhas: falhas, total: linhas.length, linhas: linhas };
  } else {
    console.log(texto);
    if (falhas > 0) process.exit(1);
  }
})();
