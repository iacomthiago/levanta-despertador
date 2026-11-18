/*
 * Disciplina no navegador: telas, armazenamento neste aparelho e sincronização
 * automática. As regras ficam em nucleo.js; aqui só se mostra e se guarda.
 */
(function () {
  'use strict';
  var N = self.Nucleo;
  var Nuvem = self.Nuvem;
  var QR = self.QR;

  // Todos os apps de iacomthiago.github.io dividem o mesmo armazenamento: por isso o prefixo.
  var CH = {
    doc: 'disciplina.doc',
    chave: 'disciplina.chave',
    gist: 'disciplina.gist',
    etag: 'disciplina.etag',
    sujo: 'disciplina.sujo',
    ultima: 'disciplina.ultima',
    inicios: 'disciplina.inicios',
    imagem: 'disciplina.imagem',
    ignorar: 'disciplina.ignorar',
  };
  var PAGINA_DA_CHAVE = 'https://github.com/settings/tokens/new?scopes=gist&description=Disciplina';
  var CORES_DO_RANK = ['var(--suave)', 'var(--lilas)', 'var(--verde)', 'var(--ouro)', 'var(--vermelho)', 'var(--texto)'];
  var NOMES_DIAS = ['Seg', 'Ter', 'Qua', 'Qui', 'Sex', 'Sáb', 'Dom'];

  // ---------- armazenamento ----------

  function pega(k) {
    try {
      return localStorage.getItem(k);
    } catch (e) {
      return null;
    }
  }

  function poe(k, v) {
    try {
      localStorage.setItem(k, v);
      return true;
    } catch (e) {
      return false;
    }
  }

  function tira(k) {
    try {
      localStorage.removeItem(k);
    } catch (e) {
      // sem armazenamento: segue só na memória
    }
  }

  function lerDoc() {
    var bruto = pega(CH.doc);
    if (!bruto) return null;
    try {
      return N.arrumar(JSON.parse(bruto));
    } catch (e) {
      return null;
    }
  }

  var doc = lerDoc();
  var tela = { nome: 'inicio', m: {} };
  var ganho = null;
  var ultimoDia = '';
  var sinc = { rodando: false, denovo: false, erro: '', ultima: Number(pega(CH.ultima)) || 0, versao: 0, timer: 0 };

  function agora() {
    return Date.now();
  }

  function salvarDoc() {
    if (doc) poe(CH.doc, JSON.stringify(doc));
  }

  /** Depois de qualquer mudança feita aqui: guarda e manda subir sozinho. */
  function mudou() {
    salvarDoc();
    sinc.versao++;
    if (pega(CH.chave)) {
      poe(CH.sujo, '1');
      agendarSinc(2000);
    }
  }

  // ---------- formatação ----------

  function esc(s) {
    return String(s === undefined || s === null ? '' : s).replace(/[&<>"']/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c];
    });
  }

  function dois(n) {
    return n < 10 ? '0' + n : '' + n;
  }

  function hhmm(h, m) {
    return dois(h) + ':' + dois(m);
  }

  function hhmmDe(ms) {
    var d = new Date(ms);
    return hhmm(d.getHours(), d.getMinutes());
  }

  function diaCurto(ms) {
    var d = new Date(ms);
    var nomes = ['dom', 'seg', 'ter', 'qua', 'qui', 'sex', 'sáb'];
    return nomes[d.getDay()] + '., ' + dois(d.getDate()) + '/' + dois(d.getMonth() + 1);
  }

  function contagem(ms) {
    var total = Math.floor((Math.max(0, ms) + 999) / 1000);
    return dois(Math.floor(total / 60)) + ':' + dois(total % 60);
  }

  function resumoDias(dias) {
    var d = (dias && dias.length ? dias.slice() : [1, 2, 3, 4, 5, 6, 7]).sort();
    var texto = d.join('');
    if (d.length === 7) return 'Todos os dias';
    if (texto === '12345') return 'Segunda a sexta';
    if (texto === '67') return 'Fim de semana';
    return d
      .map(function (x) {
        return NOMES_DIAS[x - 1];
      })
      .join(', ');
  }

  /** Dias até a data da prova ("dd/mm/aaaa"); null se a data for inválida ou já tiver passado. */
  function diasParaProva(texto) {
    var p = /^\s*(\d{1,2})\/(\d{1,2})\/(\d{4})\s*$/.exec(texto || '');
    if (!p) return null;
    var dia = +p[1];
    var mes = +p[2];
    var data = new Date(+p[3], mes - 1, dia);
    if (data.getMonth() !== mes - 1 || data.getDate() !== dia) return null;
    var hoje = new Date();
    var falta = Math.round((data - new Date(hoje.getFullYear(), hoje.getMonth(), hoje.getDate())) / 86400000);
    return falta >= 0 ? falta : null;
  }

  function fraseDaProva(texto) {
    var dias = diasParaProva(texto);
    if (dias === null) return '';
    if (dias === 0) return 'A prova é hoje.';
    if (dias === 1) return 'Falta 1 dia para a prova.';
    return 'Faltam ' + dias + ' dias para a prova.';
  }

  function textoDosTurbos(n) {
    return n === 1 ? '1 turbo guardado' : n + ' turbos guardados';
  }

  function tituloDoDesafio(s) {
    var dias = s.alvo === 1 ? '1 dia' : s.alvo + ' dias';
    if (s.desafio === N.Tipo.TREINO) return 'Treinar em ' + dias;
    if (s.desafio === N.Tipo.ESTUDO) return 'Bater a meta de estudo em ' + dias;
    return 'Cumprir a missão de levantar em ' + dias;
  }

  function toca(m) {
    return m.type === N.Tipo.LEVANTAR || m.rings;
  }

  // ---------- peças ----------

  var ICONE_VOLTAR = '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20z"/></svg>';
  var ICONE_AJUSTES =
    '<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M19.14 12.94c.04-.3.06-.61.06-.94s-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.49.49 0 0 0-.59-.22l-2.39.96a7 7 0 0 0-1.62-.94l-.36-2.54A.48.48 0 0 0 13.92 2h-3.84a.48.48 0 0 0-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96a.48.48 0 0 0-.59.22L2.73 8.47a.48.48 0 0 0 .12.61l2.03 1.58c-.05.3-.08.63-.08.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32a.48.48 0 0 0-.12-.61zM12 15.6A3.6 3.6 0 1 1 12 8.4a3.6 3.6 0 0 1 0 7.2z"/></svg>';

  function cabecalho(titulo) {
    return (
      '<div class="topo"><button class="icone" data-a="voltar" aria-label="Voltar">' + ICONE_VOLTAR + '</button>' +
      '<h2 class="cresce">' + esc(titulo) + '</h2></div><div class="e6"></div>'
    );
  }

  function e(n) {
    return '<div class="e' + n + '"></div>';
  }

  function secao(texto) {
    return '<div class="rotulo secao">' + esc(texto) + '</div>';
  }

  function barra(fracao, cor) {
    var f = Math.max(0, Math.min(1, fracao));
    return '<div class="barra"><i style="width:' + (f * 100).toFixed(1) + '%;background:' + cor + '"></i></div>';
  }

  function chave(ligada, acao, extra, desligada) {
    return (
      '<button class="chave' + (ligada ? ' on' : '') + '" role="switch" aria-checked="' + (ligada ? 'true' : 'false') + '" data-a="' + acao + '"' +
      (extra || '') + (desligada ? ' disabled' : '') + '></button>'
    );
  }

  function maisMenos(valor, min, max, passo, rotulo, acao) {
    return (
      '<div class="mais-menos"><button data-a="' + acao + '" data-v="' + (valor - passo) + '"' + (valor - passo < min ? ' disabled' : '') +
      ' aria-label="Menos">−</button><span>' + esc(rotulo) + '</span><button data-a="' + acao + '" data-v="' + (valor + passo) + '"' +
      (valor + passo > max ? ' disabled' : '') + ' aria-label="Mais">+</button></div>'
    );
  }

  function cartaoGanho() {
    if (!ganho) return '';
    var h = '<button class="cartao alto toque" data-a="fecharGanho">';
    if (ganho.xp > 0) h += '<div class="verde forte" style="font-size:26px">+' + ganho.xp + ' XP</div>';
    ganho.linhas.forEach(function (l) {
      h += '<div class="p15">' + esc(l) + '</div>';
    });
    if (ganho.nivel > 0) {
      h += e(4) + '<div class="ouro forte p17">Você subiu para o nível ' + ganho.nivel + ': ' + esc(N.RANKS[N.indiceDoRank(ganho.nivel)].nome) + '.</div>';
    }
    return h + '</button>' + e(12);
  }

  function imagemDoPersonagem(v) {
    var propria = pega(CH.imagem);
    return '<div class="heroi"><img src="' + (propria ? esc(propria) : 'carro.webp') + '" alt="Seu personagem" style="border-color:' + CORES_DO_RANK[v.rank] + '"></div>';
  }

  function linhaDaSinc() {
    var texto;
    if (!pega(CH.chave)) texto = 'Guardado só neste aparelho. Em Ajustes dá para ligar a sincronização.';
    else if (sinc.erro === 'chave') texto = 'A chave do GitHub parou de funcionar. Abra Ajustes para colar outra.';
    else if (sinc.erro === 'versao') texto = 'Esta conta foi usada numa versão mais nova. Feche e abra o site para atualizar.';
    else if (sinc.erro) texto = 'Sem conexão agora. Fica guardado aqui e sobe sozinho depois.';
    else if (sinc.ultima) texto = 'Sincronizado às ' + hhmmDe(sinc.ultima) + '.';
    else texto = 'Sincronizando…';
    return '<div class="rodape" id="sinc">' + esc(texto) + '</div>';
  }

  // ---------- ações com aviso de ganho ----------

  /** Faz a mudança e monta o aviso do que foi ganho, como o app faz. */
  function agir(motivo, fazer) {
    var t = agora();
    var antes = N.derivar(doc, t);
    var marcoAntes = doc.marco;
    var semanaAntes = antes.semana && antes.semana.premiada;
    fazer(t);
    var depois = N.derivar(doc, t);
    var linhas = [];
    if (motivo) linhas.push(motivo);
    if (!antes.estudoFechado && depois.estudoFechado) {
      var registro = doc.feitos[depois.hoje + '|' + N.ID_ESTUDO];
      linhas.push(registro && registro.xp > 0 ? 'Meta de estudo batida antes das ' + depois.horaDoBonus + ' h' : 'Meta de estudo do dia batida');
    }
    if (!semanaAntes && depois.semana && depois.semana.premiada) linhas.push('Missão da semana cumprida');
    for (var marco = marcoAntes + N.NIVEIS_POR_RECOMPENSA; marco <= doc.marco; marco += N.NIVEIS_POR_RECOMPENSA) {
      var premio = N.premioDoNivel(doc, marco);
      linhas.push('Recompensa do nível ' + marco + ': +1 turbo' + (premio ? ' e o seu prêmio, ' + premio : ''));
    }
    var delta = depois.xp - antes.xp;
    if (delta > 0 || (delta === 0 && motivo && linhas.length > 0 && depois.turboLigado && !antes.turboLigado)) {
      var junta = ganho && t - ganho.em < 5000;
      ganho = {
        xp: (junta ? ganho.xp : 0) + delta,
        linhas: (junta ? ganho.linhas : []).concat(linhas),
        nivel: Math.max(junta ? ganho.nivel : 0, depois.nivel > antes.nivel ? depois.nivel : 0),
        em: t,
      };
      var marca = t;
      setTimeout(function () {
        if (ganho && ganho.em === marca) {
          ganho = null;
          desenhar();
        }
      }, 9000);
    } else {
      ganho = null;
    }
    mudou();
    desenhar();
  }

  // ---------- treino: a hora em que começou fica só neste aparelho ----------

  function inicios() {
    try {
      return JSON.parse(pega(CH.inicios) || '{}') || {};
    } catch (e2) {
      return {};
    }
  }

  function inicioDeHoje(id) {
    var quando = inicios()[id];
    return quando && N.diaDe(quando) === N.diaDe(agora()) ? quando : null;
  }

  function liberaEm(m) {
    var inicio = inicioDeHoje(m.id);
    if (!inicio) return null;
    var minutos = Math.min(600, Math.max(1, m.minutes || 30));
    var parte = Math.min(100, Math.max(0, m.minPercent === undefined ? 60 : m.minPercent));
    return inicio + Math.floor((minutos * 60000 * parte) / 100);
  }

  // ---------- telas ----------

  function situacao(m, v, t) {
    if (N.feita(v.indice, m, v.hoje)) {
      var qtd = v.blocosHoje.length;
      return { texto: m.type === N.Tipo.ESTUDO && qtd > 0 ? 'Meta batida · ' + N.tempoDeBlocos(qtd) : 'Concluída', cor: 'verde', feita: true };
    }
    var passou = N.minutoDoDia(t) >= m.hour * 60 + m.minute;
    if (m.type === N.Tipo.ESTUDO) {
      var b = v.blocosHoje.length;
      return b === 0 ? { texto: 'Marque os blocos de 30 minutos', cor: 'ouro' } : { texto: b + ' de ' + v.metaDeBlocos + ' blocos', cor: 'lilas' };
    }
    if (m.type === N.Tipo.TREINO) {
      var inicio = inicioDeHoje(m.id);
      if (inicio) return { texto: 'Em andamento desde ' + hhmmDe(inicio), cor: 'lilas' };
      return passou ? { texto: 'Atrasada', cor: 'ouro' } : { texto: 'Pendente', cor: 'suave' };
    }
    if (m.type === N.Tipo.SIMPLES) return passou ? { texto: 'Atrasada', cor: 'ouro' } : { texto: 'Pendente', cor: 'suave' };
    // Levantar: é o despertador, que só existe no app do tablet.
    if (passou && !N.vale(Object.assign({}, m, { counts: true }), v.hoje)) return { texto: 'Começa amanhã · só no tablet', cor: 'suave' };
    return passou ? { texto: 'Não cumprida · só no tablet', cor: 'vermelho' } : { texto: 'Pendente · só no tablet', cor: 'suave' };
  }

  function linhaMissaoDeHoje(m, v, t) {
    var s = situacao(m, v, t);
    var direita;
    if (s.feita) direita = '<span class="verde forte">Feita</span>';
    else if (m.type === N.Tipo.SIMPLES) direita = '<button class="botao pequeno" data-a="concluir" data-id="' + m.id + '">Concluir</button>';
    else if (m.type === N.Tipo.ESTUDO) direita = '<span class="suave p14">+' + N.Xp.BLOCO + ' por bloco</span>';
    else direita = '<span class="suave p14">+' + N.xpDaMissao(m.type) + ' XP</span>';
    var abre = m.type === N.Tipo.LEVANTAR ? '' : ' data-a="abrirMissao" data-id="' + m.id + '" role="button" tabindex="0" style="cursor:pointer"';
    return (
      '<div class="cartao missao"' + abre + '><div class="hora"><b' + (s.feita ? ' class="suave"' : '') + '>' +
      (toca(m) ? hhmm(m.hour, m.minute) : 'Livre') + '</b><span>' + esc(N.nomeDoTipo(m.type)) + '</span></div>' +
      '<div class="cresce" style="flex:1;min-width:0"><div class="nome">' + esc(N.nomeDaMissao(m)) + '</div><div class="p15 ' + s.cor + '">' + esc(s.texto) + '</div></div>' +
      direita + '</div>' + e(10)
    );
  }

  function telaInicio() {
    var t = agora();
    var v = N.derivar(doc, t);
    var cor = CORES_DO_RANK[v.rank];
    var h =
      '<div class="topo"><div class="cresce"><h1>Disciplina</h1><p class="suave p15">Missões do dia. Um nível de cada vez.</p></div>' +
      '<button class="icone" data-a="ir" data-tela="ajustes" aria-label="Ajustes">' + ICONE_AJUSTES + '</button></div>' + e(16);
    h += cartaoGanho();

    // Personagem, nível, barra de experiência e sequência.
    h += '<div class="cartao alto">' + imagemDoPersonagem(v) + e(14);
    h += '<div class="nivel"><b>Nível ' + v.nivel + '</b><span style="color:' + cor + '">' + esc(N.RANKS[v.rank].nome) + '</span></div>' + e(8);
    h += barra(v.xpNoNivel / v.xpDoNivelTodo, cor) + e(6);
    h += '<div class="linha suave p14"><span class="cresce">' + v.xpNoNivel + ' / ' + v.xpDoNivelTodo + ' XP</span><span>Total: ' + v.xp + ' XP</span></div>' + e(14);
    h +=
      '<div class="linha"><div class="cresce numero"><b>' + v.sequenciaAtual + '</b><span>' + (v.sequenciaAtual === 1 ? 'dia de sequência' : 'dias de sequência') +
      '</span></div><div class="cresce numero"><b>' + v.recorde + '</b><span>recorde</span></div></div>' + e(10);
    if (v.pendentes.length > 0) {
      h += '<p class="ouro p15">Para o dia contar, falta: ' + esc(v.pendentes.map(N.nomeDaMissao).join(', ')) + '.</p>';
    } else if (v.hojeCompleto) {
      h += '<p class="verde p15">Hoje está completo.</p>';
    }
    var prova = fraseDaProva(N.cfg(doc, 'examDate', ''));
    if (prova) h += e(6) + '<p class="ouro p17 meio">' + esc(prova) + '</p>';
    h += '</div>' + e(12);

    // Recompensas.
    var premio = N.premioDoNivel(doc, v.proximoMarco);
    h += '<button class="cartao toque" data-a="ir" data-tela="recompensas"><div class="rotulo">Recompensas</div>' + e(4);
    h += '<div class="forte" style="font-size:20px">Próxima no nível ' + v.proximoMarco + '</div>';
    h += '<div class="suave p15">Faltam ' + Math.max(0, N.xpDoNivel(v.proximoMarco) - v.xp) + ' XP · +1 turbo' + (premio ? ' e ' + esc(premio) : '') + '</div>' + e(6);
    h += v.turboLigado
      ? '<div class="ouro p15 meio">Turbo ligado hoje: tudo vale o dobro.</div>'
      : '<div class="p15 ' + (v.turbos > 0 ? 'ouro' : 'suave') + '">' + textoDosTurbos(v.turbos) + '</div>';
    h += '</button>';

    h += secao('Missões de hoje');
    if (v.deHoje.length === 0) h += '<p class="suave" style="padding:4px">Nenhuma missão para hoje. Crie a primeira em "Nova missão".</p>';
    v.deHoje.forEach(function (m) {
      h += linhaMissaoDeHoje(m, v, t);
    });

    if (v.semana) {
      var s = v.semana;
      var feito = Math.min(s.progresso, s.alvo);
      h += e(4) + '<div class="cartao"><div class="rotulo">Missão da semana</div>' + e(4);
      h += '<div class="forte" style="font-size:20px">' + esc(tituloDoDesafio(s)) + '</div>' + e(10);
      h += barra(feito / Math.max(1, s.alvo), 'var(--ouro)') + e(6);
      h += s.premiada
        ? '<div class="verde p15">Cumprida. Você ganhou +' + N.Xp.SEMANA + ' XP.</div>'
        : '<div class="suave p15">' + feito + ' de ' + s.alvo + ' · vale +' + N.Xp.SEMANA + ' XP · até domingo</div>';
      h += '</div>';
    }

    h += secao('Todas as missões');
    var todas = N.missoes(doc);
    if (todas.length === 0) h += '<p class="suave" style="padding:4px">Você ainda não tem missões.</p>';
    todas.forEach(function (m) {
      var doTablet = m.type === N.Tipo.LEVANTAR;
      h +=
        '<div class="cartao missao"><div class="cresce" style="flex:1;min-width:0;' + (doTablet ? '' : 'cursor:pointer') + '"' +
        (doTablet ? '' : ' data-a="editar" data-id="' + m.id + '" role="button" tabindex="0"') + '><div class="nome"' +
        (m.enabled ? '' : ' style="color:var(--suave)"') + '>' + hhmm(m.hour, m.minute) + ' · ' + esc(N.nomeDaMissao(m)) + '</div><div class="suave p14">' +
        esc(N.nomeDoTipo(m.type)) + ' · ' + esc(resumoDias(m.days)) + (doTablet ? ' · muda só no tablet' : '') + '</div></div>' +
        chave(m.enabled, 'ligarMissao', ' data-id="' + m.id + '" aria-label="Missão ligada"', doTablet) + '</div>' + e(10);
    });
    h += e(6) + '<button class="botao" data-a="editar">Nova missão</button>';

    h += secao('Mais');
    h += '<button class="botao sec" data-a="ir" data-tela="ideias">Ideias para depois</button>' + e(20);
    h += linhaDaSinc();
    return h;
  }

  function telaEstudo() {
    var t = agora();
    var v = N.derivar(doc, t);
    var qtd = v.blocosHoje.length;
    var hora = N.horaDe(t);
    var corTopo = v.estudoFechado ? 'verde' : 'lilas';
    var h = cabecalho('Estudo') + cartaoGanho();
    h += '<div class="cartao alto"><div class="rotulo">Estudo de hoje</div>';
    h += '<div class="forte ' + corTopo + '" style="font-size:40px;line-height:1.2">' + (qtd === 0 ? 'Nenhum bloco ainda' : N.tempoDeBlocos(qtd)) + '</div>';
    h += '<div' + (v.estudoFechado ? ' class="verde"' : '') + '>' + qtd + ' de ' + v.metaDeBlocos + ' blocos para o dia contar</div>' + e(8);
    h += barra(qtd / v.metaDeBlocos, v.estudoFechado ? 'var(--verde)' : 'var(--lilas)') + e(8);
    var aviso;
    if (v.estudoFechado) aviso = 'Meta de hoje batida. Cada bloco a mais continua valendo +' + N.Xp.BLOCO + ' XP.';
    else if (hora < v.horaDoBonus) aviso = 'Chegue a ' + v.metaDeBlocos + ' blocos até as ' + v.horaDoBonus + ' h e ganhe +' + N.Xp.BONUS_ESTUDO + ' XP de bônus.';
    else aviso = 'Passou das ' + v.horaDoBonus + ' h: o bônus de hoje ficou para trás, mas cada bloco ainda vale +' + N.Xp.BLOCO + ' XP.';
    h += '<p class="suave p15">' + aviso + '</p></div>';

    h += secao('Blocos de ' + N.MINUTOS_DO_BLOCO + ' minutos');
    h += '<p class="suave p15" style="padding:0 4px">Estudou ' + N.MINUTOS_DO_BLOCO + ' minutos? Toque em um bloco. Tocar de novo desmarca.</p>' + e(10);
    h += '<div class="blocos">';
    for (var n = 1; n <= N.BLOCOS_POR_DIA; n++) {
      var feito = v.blocosHoje.indexOf(n) >= 0;
      h +=
        '<button class="bloco' + (feito ? ' feito' : '') + '" data-a="bloco" data-n="' + n + '" aria-pressed="' + feito + '" aria-label="Bloco ' + n +
        (feito ? ' feito' : '') + '"><b>' + n + '</b><span>' + (feito ? 'Feito' : N.MINUTOS_DO_BLOCO + ' min') + '</span></button>';
    }
    h += '</div>';
    h += secao('Mais') + '<button class="botao sec" data-a="ir" data-tela="ideias">Anotar uma ideia para depois</button>' + e(20);
    return h;
  }

  function telaMissao() {
    var m = doc.missoes[tela.id];
    if (!m || m.del || (m.type !== N.Tipo.TREINO && m.type !== N.Tipo.SIMPLES)) {
      tela = { nome: 'inicio', m: {} };
      return telaInicio();
    }
    var t = agora();
    var v = N.derivar(doc, t);
    var feita = N.feita(v.indice, m, v.hoje);
    var h = cabecalho(N.nomeDaMissao(m)) + cartaoGanho();
    if (m.type === N.Tipo.SIMPLES) {
      h += '<div class="cartao alto"><div class="rotulo">Missão simples · ' + hhmm(m.hour, m.minute) + '</div>' + e(6);
      h += '<div class="forte' + (feita ? ' verde' : '') + '" style="font-size:26px">' + (feita ? 'Concluída hoje.' : esc(N.nomeDaMissao(m))) + '</div></div>' + e(16);
      if (!feita) h += '<button class="botao" data-a="concluir" data-id="' + m.id + '">Concluir (+' + N.Xp.SIMPLES + ' XP)</button>' + e(10);
    } else {
      var inicio = inicioDeHoje(m.id);
      var libera = liberaEm(m);
      var liberado = libera === null || t >= libera;
      h += '<div class="cartao alto"><div class="rotulo">Treino · ' + (m.minutes || 30) + ' min</div>' + e(6);
      if (feita) {
        h += '<div class="forte verde" style="font-size:26px">Treino concluído hoje.</div>';
      } else if (!inicio) {
        h += '<div class="forte" style="font-size:26px">Pronto para começar?</div>' + e(4);
        h += '<p class="suave">O treino abre em outra aba. Na volta, toque em Concluí.</p>';
      } else {
        h += '<div class="forte" style="font-size:24px">Em andamento desde ' + hhmmDe(inicio) + '</div>' + e(4);
        h += liberado
          ? '<p class="verde">Já pode concluir.</p>'
          : '<p class="suave">O botão Concluí libera às ' + hhmmDe(libera) + ' (faltam ' + contagem(libera - t) + ').</p>';
      }
      h += '</div>' + e(16);
      if (!feita) {
        var endereco = m.url && String(m.url).trim() ? m.url : N.URL_TREINO;
        if (!inicio) {
          h += '<a class="botao" href="' + esc(endereco) + '" target="_blank" rel="noopener" data-a="comecarTreino" data-id="' + m.id + '">Começar treino</a>';
        } else {
          h += '<button class="botao" data-a="concluir" data-id="' + m.id + '"' + (liberado ? '' : ' disabled') + '>Concluí (+' + N.Xp.TREINO + ' XP)</button>' + e(10);
          h += '<a class="botao sec" href="' + esc(endereco) + '" target="_blank" rel="noopener">Abrir o treino de novo</a>';
        }
        h += e(10);
        if (inicio && !liberado) relogio(1000);
      }
    }
    h += '<button class="botao sec" data-a="editar" data-id="' + m.id + '">Editar esta missão</button>' + e(20);
    return h;
  }

  function descricaoDoTipo(tipo) {
    if (tipo === N.Tipo.ESTUDO) return 'Estudo em blocos de 30 minutos: cada bloco marcado vale XP e a meta do dia fecha a missão.';
    if (tipo === N.Tipo.TREINO) return 'Abre o treino e só libera o Concluí depois de um tempo mínimo.';
    return 'Uma tarefa de um toque só: fez, concluiu.';
  }

  function telaEditar() {
    var r = tela.m;
    var nova = !r.id;
    var h = cabecalho(nova ? 'Nova missão' : 'Editar missão');
    h += secao('Tipo de missão') + '<div class="chips">';
    [N.Tipo.ESTUDO, N.Tipo.TREINO, N.Tipo.SIMPLES].forEach(function (tipo) {
      h += '<button class="chip' + (r.type === tipo ? ' on' : '') + '" data-a="rascunho" data-k="type" data-v="' + tipo + '">' + N.nomeDoTipo(tipo) + '</button>';
    });
    h += '</div>' + e(8) + '<p class="suave p14" style="padding:0 4px">' + descricaoDoTipo(r.type) + '</p>';

    h += secao('Horário');
    h += '<input type="time" data-m="hora" value="' + esc(r.hora) + '" aria-label="Horário da missão">';

    h += secao('Dias da semana') + '<div class="chips">';
    for (var d = 1; d <= 7; d++) {
      h += '<button class="chip' + (r.days.indexOf(d) >= 0 ? ' on' : '') + '" data-a="diaDoRascunho" data-v="' + d + '">' + NOMES_DIAS[d - 1] + '</button>';
    }
    h += '</div>';

    h += secao(r.type === N.Tipo.SIMPLES ? 'Nome da missão' : 'Nome (opcional)');
    h += '<input type="text" data-m="label" maxlength="60" value="' + esc(r.label) + '" placeholder="' + esc(N.nomeDoTipo(r.type)) + '" aria-label="Nome da missão">';

    if (r.type === N.Tipo.TREINO) {
      h += secao('Treino') + '<div class="cartao">';
      h += '<div class="linha"><span class="cresce p17">Duração</span>' + maisMenos(r.minutes, 5, 180, 5, r.minutes + ' min', 'numeroDoRascunho" data-k="minutes') + '</div>' + e(8);
      h +=
        '<div class="linha"><span class="cresce p17">Tempo mínimo para concluir</span>' +
        maisMenos(r.minPercent, 0, 100, 10, r.minPercent + '%', 'numeroDoRascunho" data-k="minPercent') + '</div>' + e(12);
      h += '<label class="campo" for="url">Endereço do treino (vazio usa o treino em casa)</label>';
      h += '<input type="text" id="url" data-m="url" maxlength="300" value="' + esc(r.url) + '" placeholder="' + esc(N.URL_TREINO) + '" inputmode="url" autocapitalize="off">';
      h += '</div>';
    }

    h += secao('Regras') + '<div class="cartao">';
    h +=
      '<div class="linha"><div class="cresce"><div class="p17">Conta para a sequência</div><div class="suave p14">Sem concluir até o fim do dia, a sequência zera.</div></div>' +
      chave(r.counts, 'rascunho', ' data-k="counts" data-v="' + (r.counts ? '' : '1') + '" aria-label="Conta para a sequência"') + '</div>' + e(12);
    h +=
      '<div class="linha"><div class="cresce"><div class="p17">Tocar no horário</div><div class="suave p14">Só o app do tablet toca. Aqui no site a missão aparece, mas não faz som.</div></div>' +
      chave(r.rings, 'rascunho', ' data-k="rings" data-v="' + (r.rings ? '' : '1') + '" aria-label="Tocar no horário"') + '</div>';
    h += '</div>' + e(20);

    var falta = r.type === N.Tipo.SIMPLES && !String(r.label).trim();
    h += '<button class="botao" data-a="guardarMissao"' + (falta || r.days.length === 0 ? ' disabled' : '') + '>Guardar</button>';
    if (falta) h += e(8) + '<p class="ouro p14" style="padding:0 4px">Dê um nome para a missão simples.</p>';
    if (!nova) {
      h += e(10);
      h += r.confirmar
        ? '<button class="botao sec" style="color:var(--vermelho)" data-a="apagarMissao">Apagar mesmo? Toque de novo</button>'
        : '<button class="botao sec" data-a="rascunho" data-k="confirmar" data-v="1">Apagar esta missão</button>';
    }
    return h + e(20);
  }

  function telaRecompensas() {
    var t = agora();
    var v = N.derivar(doc, t);
    if (tela.m.premios === undefined) tela.m.premios = doc.premios.v;
    var h = cabecalho('Recompensas') + cartaoGanho();
    h += '<div class="cartao alto"><div class="rotulo">Turbo</div>';
    h += '<div class="forte ' + (v.turbos > 0 ? 'ouro' : 'lilas') + '" style="font-size:30px">' + textoDosTurbos(v.turbos) + '</div>';
    h +=
      '<p class="suave p15">Um turbo faz o dia inteiro valer o dobro de XP, incluindo o que você já fez hoje. Você ganha um a cada ' +
      N.NIVEIS_POR_RECOMPENSA + ' níveis e a cada ' + N.DIAS_POR_TURBO + ' dias de sequência.</p>' + e(12);
    h += v.turboLigado
      ? '<div class="ouro forte p17">Turbo ligado hoje: tudo vale o dobro.</div>'
      : '<button class="botao" data-a="turbo"' + (v.turbos > 0 ? '' : ' disabled') + '>Usar um turbo hoje (XP em dobro)</button>';
    h += '</div>';

    h += secao('Prêmios da vida real');
    h +=
      '<p class="suave p15" style="padding:0 4px">Os prêmios já vêm escolhidos; troque se quiser. É um por linha: o primeiro é o do nível ' +
      N.NIVEIS_POR_RECOMPENSA + ', o segundo o do nível ' + N.NIVEIS_POR_RECOMPENSA * 2 + ', e assim por diante.</p>' + e(8);
    h += '<textarea data-m="premios" maxlength="2000" rows="6" aria-label="Um prêmio por linha" data-redesenha="1">' + esc(tela.m.premios) + '</textarea>' + e(10);
    h += '<button class="botao" data-a="guardarPremios" id="guardarPremios"' + (tela.m.premios.trim() !== String(doc.premios.v).trim() ? '' : ' disabled') + '>Guardar prêmios</button>';

    h += secao('Níveis de recompensa');
    var combinados = String(doc.premios.v).split('\n').filter(function (l) {
      return l.trim();
    }).length;
    var ultimo = Math.max(v.proximoMarco, combinados * N.NIVEIS_POR_RECOMPENSA);
    var marcos = [];
    for (var marco = N.NIVEIS_POR_RECOMPENSA; marco <= ultimo; marco += N.NIVEIS_POR_RECOMPENSA) marcos.push(marco);
    marcos.slice(-12).forEach(function (mc) {
      var premio = N.premioDoNivel(doc, mc);
      var alcancado = v.nivel >= mc;
      h += '<div class="cartao linha"><div class="cresce"><div class="forte' + (alcancado ? ' verde' : '') + '" style="font-size:19px">Nível ' + mc + '</div>';
      h += '<div class="suave p14">' + (premio ? '+1 turbo' : '+1 turbo · sem prêmio escolhido') + '</div>';
      if (premio) h += '<div class="p17">Prêmio: ' + esc(premio) + '</div>';
      h += '<div class="p14 ' + (alcancado ? 'verde' : 'suave') + '">' + (alcancado ? 'Alcançado' : 'Faltam ' + Math.max(0, N.xpDoNivel(mc) - v.xp) + ' XP') + '</div></div>';
      if (alcancado && premio) {
        h += doc.pegos[mc]
          ? '<span class="verde forte">Pego</span>'
          : '<button class="botao pequeno" data-a="pegarPremio" data-v="' + mc + '">Já peguei</button>';
      }
      h += '</div>' + e(8);
    });
    return h + e(20);
  }

  function telaIdeias() {
    if (tela.m.ideia === undefined) tela.m.ideia = '';
    var h = cabecalho('Ideias para depois');
    h += '<p class="suave">Teve uma ideia no meio de uma missão? Anote aqui e volte para o que estava fazendo. Ela não se perde.</p>' + e(12);
    h += '<textarea data-m="ideia" maxlength="500" rows="3" placeholder="A ideia, em poucas palavras" aria-label="A ideia, em poucas palavras">' + esc(tela.m.ideia) + '</textarea>' + e(10);
    h += '<button class="botao" data-a="guardarIdeia">Guardar ideia</button>';
    var lista = N.ideias(doc);
    if (lista.length > 0) h += secao('Guardadas');
    lista.forEach(function (i) {
      h += '<div class="cartao"><div class="p17" style="white-space:pre-wrap;overflow-wrap:anywhere">' + esc(i.texto) + '</div>';
      h += '<div class="linha"><span class="cresce suave p13">' + diaCurto(i.em) + ' às ' + hhmmDe(i.em) + '</span>';
      h += '<button class="botao texto" data-a="apagarIdeia" data-id="' + i.id + '">Apagar</button></div></div>' + e(8);
    });
    return h + e(20);
  }

  function noIphone() {
    return /iPhone|iPad|iPod/.test(navigator.userAgent) || (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
  }

  function instalado() {
    return (self.matchMedia && matchMedia('(display-mode: standalone)').matches) || navigator.standalone === true;
  }

  function telaAjustes() {
    var t = agora();
    var v = N.derivar(doc, t);
    var conectado = !!pega(CH.chave);
    if (tela.m.prova === undefined) tela.m.prova = N.cfg(doc, 'examDate', '');
    var h = cabecalho('Ajustes');

    h += secao('Sincronização') + '<div class="cartao">';
    if (conectado) {
      h += '<div class="p17 meio">Ligada</div><p class="suave p14">O que você faz aqui sobe sozinho, e o que foi feito em outro aparelho aparece ao abrir. Não precisa apertar nada.</p>' + e(6);
      h += '<p class="p14 suave" id="sincAjustes">' + esc(textoCurtoDaSinc()) + '</p>' + e(12);
      h += '<button class="botao sec" data-a="ir" data-tela="qr">Ligar outro aparelho (QR code)</button>' + e(10);
      h += tela.m.desligar
        ? '<button class="botao sec" style="color:var(--vermelho)" data-a="desconectar">Desligar mesmo? Toque de novo</button>'
        : '<button class="botao sec" data-a="marca" data-k="desligar">Desligar neste aparelho</button>';
      h += e(8) + '<p class="suave p13">Desligar não apaga nada: o progresso continua guardado neste aparelho e na sua conta.</p>';
    } else {
      h += '<div class="p17 meio">Desligada</div><p class="suave p14">O progresso está guardado só neste aparelho. Ligando, ele passa a aparecer igual no tablet, no celular e no computador.</p>' + e(12);
      h += '<button class="botao" data-a="ir" data-tela="entrar">Ligar a sincronização</button>';
    }
    h += '</div>';

    h += secao('Progresso e estudo') + '<div class="cartao">';
    h += '<div class="linha"><span class="cresce p17">Bônus de estudo até</span>' + maisMenos(v.horaDoBonus, 8, 22, 1, v.horaDoBonus + ' h', 'cfg" data-k="studyBonusHour') + '</div>' + e(10);
    h +=
      '<div class="linha"><span class="cresce p17">Blocos de estudo para o dia contar</span>' +
      maisMenos(v.metaDeBlocos, 1, N.BLOCOS_POR_DIA, 1, v.metaDeBlocos + ' (' + N.tempoDeBlocos(v.metaDeBlocos) + ')', 'cfg" data-k="studyBlocks') + '</div>' + e(8);
    h += '<p class="suave p13">Nível ' + v.nivel + ' · ' + v.xp + ' XP · recorde de ' + v.recorde + ' dias seguidos.</p></div>';

    h += secao('Prova') + '<div class="cartao">';
    h += '<label class="campo" for="prova">Data da prova (dd/mm/aaaa)</label>';
    h += '<input type="text" id="prova" data-m="prova" maxlength="10" inputmode="numeric" value="' + esc(tela.m.prova) + '">';
    var frase = fraseDaProva(tela.m.prova);
    h += '<p class="suave p14" id="fraseProva" style="padding-top:6px">' + esc(frase || (String(tela.m.prova).trim() ? 'Data inválida ou que já passou.' : 'Sem data, a contagem não aparece.')) + '</p></div>';

    h += secao('Seu personagem') + '<div class="cartao">' + imagemDoPersonagem(v) + e(12);
    h += '<label class="botao sec" style="display:grid;place-items:center;cursor:pointer">Escolher uma imagem<input type="file" accept="image/*" id="arquivoImagem" hidden></label>';
    if (pega(CH.imagem)) h += e(8) + '<button class="botao sec" data-a="imagemPadrao">Voltar para a imagem que vem no app</button>';
    h += '<p class="p13 ' + (tela.m.avisoImagem ? 'ouro' : 'suave') + '" style="padding-top:8px">' +
      esc(tela.m.avisoImagem || 'A imagem escolhida fica só neste aparelho. A borda muda de cor conforme a sua faixa de nível.') + '</p></div>';

    if (!instalado()) {
      h += secao('Deixar na tela inicial') + '<div class="cartao">';
      if (noIphone()) {
        h += '<ol class="passos p15"><li>Toque no botão abaixo.</li><li>Toque em <b>Compartilhar</b> (o quadrado com a seta) e depois em <b>Adicionar à Tela de Início</b>.</li><li>Abra o Disciplina pelo ícone novo.</li></ol>' + e(10);
        h += '<button class="botao sec" data-a="prepararIphone">' + (tela.m.preparado ? 'Pronto: agora use Compartilhar' : 'Preparar para a tela de início') + '</button>';
        if (conectado) {
          h += e(8) + '<p class="suave p13">Se o ícone novo abrir pedindo a chave, volte aqui, toque em Copiar a chave e cole lá.</p>' + e(8);
          h += '<button class="botao sec" data-a="copiarChave">' + (tela.m.copiado ? 'Chave copiada' : 'Copiar a chave') + '</button>';
        }
      } else {
        h += '<p class="suave p15">No menu do navegador, escolha <b>Instalar app</b> ou <b>Adicionar à tela inicial</b>. O Disciplina passa a abrir como um aplicativo.</p>';
      }
      h += '</div>';
    }

    h += secao('Sobre') + '<div class="cartao"><p class="suave p14">O site tem as missões de estudo, treino e simples. O despertador (a missão de levantar) só existe no app do tablet, porque um site não consegue tocar alarme.</p></div>';
    return h + e(24);
  }

  function textoCurtoDaSinc() {
    if (sinc.erro === 'chave') return 'A chave do GitHub parou de funcionar. Desligue e ligue de novo com uma chave nova.';
    if (sinc.erro === 'versao') return 'Esta conta foi usada numa versão mais nova. Feche e abra o site para atualizar.';
    if (sinc.erro) return 'Sem conexão agora. Fica guardado aqui e sobe sozinho depois.';
    if (sinc.ultima) return 'Última sincronização: ' + diaCurto(sinc.ultima) + ' às ' + hhmmDe(sinc.ultima) + '.';
    return 'Sincronizando…';
  }

  // ---------- entrar na conta ----------

  function telaBoasVindas() {
    var h = e(20) + '<h1>Disciplina</h1><p class="suave p15">Missões do dia. Um nível de cada vez.</p>' + e(20);
    h += '<div class="cartao alto"><p>Marque o estudo em blocos de 30 minutos, conclua as missões do dia e suba de nível. O progresso fica guardado neste aparelho.</p></div>' + e(16);
    h += '<button class="botao" data-a="comecar">Começar agora</button>' + e(10);
    h += '<button class="botao sec" data-a="ir" data-tela="entrar">Já uso o Disciplina em outro aparelho</button>' + e(16);
    h += '<p class="suave p14" style="padding:0 4px">Quer o mesmo progresso em mais de um aparelho? Dá para ligar a sincronização agora ou depois, em Ajustes.</p>';
    return h;
  }

  function telaEntrar() {
    if (tela.m.chave === undefined) tela.m.chave = '';
    var h = cabecalho('Sincronizar aparelhos');
    h += '<p class="suave">O progresso fica guardado na sua conta do GitHub (gratuita), numa nota que só você vê. Faça uma vez em cada aparelho.</p>' + e(14);
    h += '<div class="cartao"><ol class="passos">';
    h += '<li>Abra <a href="' + PAGINA_DA_CHAVE + '" target="_blank" rel="noopener">esta página do GitHub</a>, que já vem preenchida. Se pedir, entre na sua conta ou crie uma.</li>';
    h += '<li>Em <b>Expiration</b>, escolha <b>No expiration</b>. No fim da página, clique em <b>Generate token</b>.</li>';
    h += '<li>Copie a chave que aparece (começa com <b>ghp_</b>), cole aqui embaixo e toque em Conectar.</li>';
    h += '</ol></div>' + e(14);
    h += '<input type="password" data-m="chave" value="' + esc(tela.m.chave) + '" placeholder="Cole aqui a chave do GitHub" autocomplete="off" autocapitalize="off" spellcheck="false" aria-label="Chave do GitHub">' + e(10);
    h += '<button class="botao" data-a="conectar"' + (tela.m.ocupado ? ' disabled' : '') + '>' + (tela.m.ocupado ? 'Conectando…' : 'Conectar') + '</button>';
    if (tela.m.aviso) h += e(10) + '<p class="ouro p15" style="padding:0 4px">' + esc(tela.m.aviso) + '</p>';
    h += e(14) + '<p class="suave p14" style="padding:0 4px">Já ligou em outro aparelho? Lá, abra Ajustes e toque em "Ligar outro aparelho": aparece um QR code para apontar a câmera, sem digitar nada.</p>';
    return h + e(20);
  }

  function telaSemConta() {
    var h = cabecalho('Conta nova');
    h += '<div class="cartao alto"><p>Não achei progresso do Disciplina guardado nesta conta do GitHub.</p></div>' + e(16);
    h += '<button class="botao" data-a="ir" data-tela="qr">Já uso o app no tablet: ligar o tablet</button>' + e(8);
    h += '<p class="suave p14" style="padding:0 4px">Mostro um QR code para o tablet ler. O progresso que está no tablet vem para cá.</p>' + e(16);
    h += '<button class="botao sec" data-a="comecarNaConta">Começar do zero aqui</button>';
    return h + e(20);
  }

  function enderecoComChave() {
    return location.origin + location.pathname + '#k=' + encodeURIComponent(pega(CH.chave) || '');
  }

  function telaQr() {
    var h = cabecalho('Ligar outro aparelho');
    h += '<div class="qr">' + QR.svg(enderecoComChave(), 280) + '</div>' + e(14);
    h += '<div class="cartao"><p class="meio">No tablet (app Disciplina)</p><p class="suave p15">Ajustes &gt; Sincronização &gt; Ler o QR code, e aponte para esta tela.</p>' + e(10);
    h += '<p class="meio">No celular</p><p class="suave p15">Aponte a câmera para o QR code e abra o endereço que aparecer.</p></div>' + e(12);
    if (!doc) {
      h += '<p class="lilas" style="padding:0 4px">Esperando o tablet enviar o progresso… Esta tela muda sozinha.</p>' + e(12);
    }
    h += '<p class="suave p13" style="padding:0 4px">Este QR code é a chave da sua conta: mostre só para os seus aparelhos.</p>' + e(12);
    h += '<button class="botao sec" data-a="copiarChave">' + (tela.m.copiado ? 'Chave copiada' : 'Copiar a chave, para colar em outro aparelho') + '</button>';
    return h + e(20);
  }

  // ---------- desenho e navegação ----------

  var TELAS = {
    inicio: telaInicio,
    estudo: telaEstudo,
    missao: telaMissao,
    editar: telaEditar,
    recompensas: telaRecompensas,
    ideias: telaIdeias,
    ajustes: telaAjustes,
    boasVindas: telaBoasVindas,
    entrar: telaEntrar,
    semConta: telaSemConta,
    qr: telaQr,
  };
  var SEM_DOC = { boasVindas: 1, entrar: 1, semConta: 1, qr: 1 };
  var relogioTimer = 0;
  var telaDesenhada = '';

  function relogio(ms) {
    clearTimeout(relogioTimer);
    relogioTimer = setTimeout(desenhar, ms);
  }

  function desenhar() {
    clearTimeout(relogioTimer);
    if (!doc && !SEM_DOC[tela.nome]) tela = { nome: 'boasVindas', m: {} };
    if (doc) viradaDoDia();
    var app = document.getElementById('app');
    var ativo = document.activeElement;
    var foco = ativo && ativo.getAttribute ? ativo.getAttribute('data-m') : null;
    var cursor = foco && ativo.selectionStart !== undefined ? [ativo.selectionStart, ativo.selectionEnd] : null;
    var rolagem = window.scrollY;
    app.innerHTML = TELAS[tela.nome]();
    if (telaDesenhada === tela.nome) window.scrollTo(0, rolagem);
    else window.scrollTo(0, 0);
    telaDesenhada = tela.nome;
    if (foco) {
      var campo = app.querySelector('[data-m="' + foco + '"]');
      if (campo) {
        campo.focus();
        try {
          if (cursor) campo.setSelectionRange(cursor[0], cursor[1]);
        } catch (e2) {
          // campos de hora não aceitam posição do cursor
        }
      }
    }
    vigiarQr();
  }

  /** Virou o dia com o site aberto: fecha o dia anterior e confere a missão da semana. */
  function viradaDoDia() {
    var hoje = N.diaDe(agora());
    if (hoje === ultimoDia) return;
    ultimoDia = hoje;
    if (N.ajustar(doc, agora())) mudou();
  }

  function ir(nome, extra) {
    if (history.state && history.state.tela === nome) {
      // já está nela
    } else if (nome === 'inicio' || nome === 'boasVindas') {
      history.replaceState({ tela: nome }, '');
    } else {
      history.pushState({ tela: nome }, '');
    }
    tela = Object.assign({ nome: nome, m: {} }, extra || {});
    desenhar();
  }

  /** A seta de voltar usa o histórico do navegador, para o botão de voltar do aparelho fazer o mesmo. */
  function voltar() {
    if (history.state && history.state.tela === tela.nome && tela.nome !== 'inicio' && tela.nome !== 'boasVindas') history.back();
    else voltarDeFato();
  }

  function voltarDeFato() {
    var destino = { estudo: 'inicio', missao: 'inicio', editar: 'inicio', recompensas: 'inicio', ajustes: 'inicio', entrar: doc ? 'ajustes' : 'boasVindas', semConta: 'entrar', qr: doc ? 'ajustes' : 'semConta' }[tela.nome];
    if (tela.nome === 'ideias') destino = tela.de || 'inicio';
    if (tela.nome === 'editar' && tela.de) destino = tela.de;
    tela = Object.assign({ nome: destino || 'inicio', m: {} }, tela.nome === 'editar' && tela.de === 'missao' ? { id: tela.m.id } : {});
    desenhar();
  }

  function rascunhoDe(m) {
    if (!m) {
      return { id: 0, type: N.Tipo.SIMPLES, hora: '08:00', days: [1, 2, 3, 4, 5, 6, 7], label: '', counts: true, rings: true, minutes: 30, minPercent: 60, url: '', enabled: true };
    }
    return {
      id: m.id, type: m.type, hora: hhmm(m.hour, m.minute), days: (m.days && m.days.length ? m.days : [1, 2, 3, 4, 5, 6, 7]).slice(), label: m.label || '',
      counts: !!m.counts, rings: !!m.rings, minutes: m.minutes || 30, minPercent: m.minPercent === undefined ? 60 : m.minPercent, url: m.url || '',
      enabled: m.enabled, createdAt: m.createdAt,
    };
  }

  // ---------- o que cada toque faz ----------

  var acoes = {
    voltar: voltar,
    ir: function (d) {
      ir(d.tela, d.tela === 'ideias' && tela.nome === 'estudo' ? { de: 'estudo' } : null);
    },
    fecharGanho: function () {
      ganho = null;
      desenhar();
    },
    comecar: function () {
      doc = N.novoDoc(agora());
      N.missoesIniciais(doc, agora());
      mudou();
      ir('inicio');
    },
    abrirMissao: function (d) {
      var m = doc.missoes[d.id];
      if (!m) return;
      if (m.type === N.Tipo.ESTUDO) ir('estudo');
      else ir('missao', { id: m.id });
    },
    bloco: function (d) {
      var n = +d.n;
      var marcado = N.blocosDe(doc, N.diaDe(agora())).indexOf(n) >= 0;
      agir(marcado ? '' : 'Bloco de estudo de ' + N.MINUTOS_DO_BLOCO + ' minutos', function (t) {
        N.alternarBloco(doc, n, t);
      });
    },
    concluir: function (d) {
      var m = doc.missoes[d.id];
      if (!m) return;
      if (m.type === N.Tipo.TREINO) {
        var libera = liberaEm(m);
        if (libera !== null && agora() < libera) return;
      }
      agir(N.nomeDaMissao(m) + ': concluída', function (t) {
        N.concluir(doc, m, t);
      });
    },
    comecarTreino: function (d) {
      // O próprio link abre o treino em outra aba; aqui só se anota a hora do começo.
      var mapa = {};
      var hoje = N.diaDe(agora());
      var antigos = inicios();
      for (var k in antigos) if (N.diaDe(antigos[k]) === hoje) mapa[k] = antigos[k];
      if (!mapa[d.id]) mapa[d.id] = agora();
      poe(CH.inicios, JSON.stringify(mapa));
      setTimeout(desenhar, 300);
      return 'seguir';
    },
    turbo: function () {
      var v = N.derivar(doc, agora());
      var jaGanho = v.xp > 0 && (v.blocosHoje.length > 0 || (v.indice[v.hoje] || []).length > 0);
      agir(jaGanho ? 'Turbo ligado: o que você já fez hoje passou a valer o dobro' : 'Turbo ligado: hoje tudo vale o dobro', function (t) {
        N.usarTurbo(doc, t);
      });
      if (!ganho) {
        ganho = { xp: 0, linhas: ['Turbo ligado: hoje tudo vale o dobro'], nivel: 0, em: agora() };
        desenhar();
      }
    },
    guardarPremios: function () {
      N.definirPremios(doc, tela.m.premios, agora());
      tela.m.premios = doc.premios.v;
      mudou();
      desenhar();
    },
    pegarPremio: function (d) {
      N.pegarPremio(doc, +d.v, agora());
      mudou();
      desenhar();
    },
    guardarIdeia: function () {
      if (!String(tela.m.ideia || '').trim()) return;
      N.anotarIdeia(doc, tela.m.ideia, agora());
      tela.m.ideia = '';
      mudou();
      desenhar();
    },
    apagarIdeia: function (d) {
      N.excluirIdeia(doc, d.id, agora());
      mudou();
      desenhar();
    },
    editar: function (d) {
      var m = d.id ? doc.missoes[d.id] : null;
      if (m && m.type === N.Tipo.LEVANTAR) return;
      var de = tela.nome === 'missao' ? 'missao' : 'inicio';
      ir('editar', { m: rascunhoDe(m), de: de });
    },
    rascunho: function (d) {
      var v = d.v;
      if (d.k === 'counts' || d.k === 'rings' || d.k === 'confirmar') v = !!d.v;
      tela.m[d.k] = v;
      if (d.k === 'type' && !tela.m.id) tela.m.rings = v !== N.Tipo.ESTUDO;
      desenhar();
    },
    numeroDoRascunho: function (d) {
      tela.m[d.k] = +d.v;
      desenhar();
    },
    diaDoRascunho: function (d) {
      var dia = +d.v;
      var i = tela.m.days.indexOf(dia);
      if (i >= 0) tela.m.days.splice(i, 1);
      else tela.m.days.push(dia);
      desenhar();
    },
    guardarMissao: function () {
      var r = tela.m;
      var hm = /^(\d{1,2}):(\d{2})/.exec(r.hora || '') || [0, 8, 0];
      var id = N.salvarMissao(
        doc,
        { id: r.id, hour: +hm[1], minute: +hm[2], days: r.days, enabled: r.enabled, label: String(r.label).trim(), type: r.type, counts: r.counts, rings: r.rings, minutes: r.minutes, minPercent: r.minPercent, url: String(r.url).trim(), createdAt: r.createdAt },
        agora()
      );
      mudou();
      tela.m.id = id;
      history.replaceState({ tela: 'inicio' }, '');
      tela = { nome: 'inicio', m: {} };
      desenhar();
    },
    apagarMissao: function () {
      N.excluirMissao(doc, tela.m.id, agora());
      mudou();
      history.replaceState({ tela: 'inicio' }, '');
      tela = { nome: 'inicio', m: {} };
      desenhar();
    },
    ligarMissao: function (d) {
      var m = doc.missoes[d.id];
      if (!m || m.type === N.Tipo.LEVANTAR) return;
      N.salvarMissao(doc, Object.assign({}, m, { enabled: !m.enabled }), agora());
      mudou();
      desenhar();
    },
    cfg: function (d) {
      N.definirCfg(doc, d.k, +d.v, agora());
      mudou();
      desenhar();
    },
    marca: function (d) {
      tela.m[d.k] = true;
      desenhar();
    },
    imagemPadrao: function () {
      tira(CH.imagem);
      desenhar();
    },
    conectar: function () {
      conectar(String(tela.m.chave || '').trim());
    },
    comecarNaConta: function () {
      doc = N.novoDoc(agora());
      N.missoesIniciais(doc, agora());
      mudou();
      ir('inicio');
      sincronizar();
    },
    desconectar: function () {
      poe(CH.ignorar, pega(CH.chave) || '');
      [CH.chave, CH.gist, CH.etag, CH.sujo, CH.ultima].forEach(tira);
      sinc.erro = '';
      sinc.ultima = 0;
      tela.m = {};
      desenhar();
    },
    copiarChave: function () {
      var texto = pega(CH.chave) || '';
      var feito = function () {
        tela.m.copiado = true;
        desenhar();
      };
      if (navigator.clipboard && navigator.clipboard.writeText) navigator.clipboard.writeText(texto).then(feito, function () {});
    },
    prepararIphone: function () {
      // Sem o manifesto, o iPhone guarda no ícone o endereço atual, que leva a chave junto.
      var manifesto = document.getElementById('manifesto');
      if (manifesto) manifesto.remove();
      if (pega(CH.chave)) history.replaceState(history.state, '', '#k=' + encodeURIComponent(pega(CH.chave)));
      tela.m.preparado = true;
      desenhar();
    },
  };

  document.addEventListener('click', function (ev) {
    var el = ev.target.closest ? ev.target.closest('[data-a]') : null;
    if (!el || el.disabled) return;
    var acao = acoes[el.getAttribute('data-a')];
    if (!acao) return;
    var resultado = acao(el.dataset, el);
    if (resultado !== 'seguir') ev.preventDefault();
  });

  document.addEventListener('keydown', function (ev) {
    if ((ev.key === 'Enter' || ev.key === ' ') && ev.target.getAttribute && ev.target.getAttribute('role') === 'button' && ev.target.tagName === 'DIV') {
      ev.preventDefault();
      ev.target.click();
    }
    if (ev.key === 'Enter' && ev.target.getAttribute && ev.target.getAttribute('data-m') === 'chave') acoes.conectar();
  });

  document.addEventListener('input', function (ev) {
    var el = ev.target;
    var k = el.getAttribute ? el.getAttribute('data-m') : null;
    if (!k) return;
    tela.m[k] = el.value;
    if (k === 'premios') {
      var botao = document.getElementById('guardarPremios');
      if (botao) botao.disabled = el.value.trim() === String(doc.premios.v).trim();
    }
    if (k === 'prova') {
      var frase = fraseDaProva(el.value);
      var valida = frase || !el.value.trim();
      var p = document.getElementById('fraseProva');
      if (p) p.textContent = frase || (el.value.trim() ? 'Data inválida ou que já passou.' : 'Sem data, a contagem não aparece.');
      if (valida && el.value.trim() !== String(N.cfg(doc, 'examDate', '')).trim()) {
        N.definirCfg(doc, 'examDate', el.value.trim(), agora());
        mudou();
      }
    }
    if (k === 'label' && tela.nome === 'editar') {
      // o botão Guardar depende do nome na missão simples
      clearTimeout(relogioTimer);
      relogioTimer = setTimeout(desenhar, 400);
    }
  });

  document.addEventListener('change', function (ev) {
    if (ev.target.id !== 'arquivoImagem' || !ev.target.files || !ev.target.files[0]) return;
    var arquivo = ev.target.files[0];
    var leitor = new FileReader();
    leitor.onload = function () {
      var img = new Image();
      img.onload = function () {
        var fator = Math.min(1, 1280 / Math.max(img.width, img.height));
        var tela2 = document.createElement('canvas');
        tela2.width = Math.round(img.width * fator);
        tela2.height = Math.round(img.height * fator);
        tela2.getContext('2d').drawImage(img, 0, 0, tela2.width, tela2.height);
        var ok = poe(CH.imagem, tela2.toDataURL('image/jpeg', 0.85));
        tela.m.avisoImagem = ok ? '' : 'Não consegui guardar essa imagem. Tente uma menor.';
        desenhar();
      };
      img.onerror = function () {
        tela.m.avisoImagem = 'Não consegui abrir essa imagem. Tente outra.';
        desenhar();
      };
      img.src = leitor.result;
    };
    leitor.readAsDataURL(arquivo);
  });

  window.addEventListener('popstate', function () {
    if (tela.nome === 'inicio' || tela.nome === 'boasVindas') return;
    voltarDeFato();
  });

  // ---------- sincronização automática ----------

  function agendarSinc(ms) {
    clearTimeout(sinc.timer);
    sinc.timer = setTimeout(sincronizar, ms);
  }

  function atualizarLinhaDaSinc() {
    var linha = document.getElementById('sinc');
    if (linha) linha.outerHTML = linhaDaSinc();
    var curta = document.getElementById('sincAjustes');
    if (curta) curta.textContent = textoCurtoDaSinc();
  }

  /**
   * Busca o que mudou nos outros aparelhos, junta com o que há aqui e envia a
   * diferença. Roda sozinha: ao abrir, ao voltar para a aba, depois de cada
   * mudança e de tempos em tempos. Sem internet, tenta de novo depois.
   */
  function sincronizar() {
    var chaveAtual = pega(CH.chave);
    if (!chaveAtual || !doc) return Promise.resolve();
    if (sinc.rodando) {
      sinc.denovo = true;
      return Promise.resolve();
    }
    sinc.rodando = true;
    var id = pega(CH.gist);
    var passo;
    if (id) {
      passo = Promise.resolve(id);
    } else {
      passo = Nuvem.achar(chaveAtual).then(function (achado) {
        if (achado) {
          tira(CH.etag);
          return achado;
        }
        var versao = sinc.versao;
        return Nuvem.criar(chaveAtual, doc).then(function (novo) {
          if (sinc.versao === versao) tira(CH.sujo);
          return novo;
        });
      });
    }
    return passo
      .then(function (gist) {
        id = gist;
        poe(CH.gist, id);
        return Nuvem.ler(chaveAtual, id, pega(CH.etag));
      })
      .then(function (r) {
        if (r.status === 404) {
          tira(CH.gist);
          tira(CH.etag);
          sinc.denovo = true;
          return null;
        }
        if (r.status === 200) {
          if (r.doc && Number(r.doc.v) > 1) throw new Nuvem.Erro('versao');
          var remoto = N.arrumar(N.copia(r.doc));
          var junto = N.juntar(doc, remoto);
          N.ajustar(junto, agora());
          var mudouAqui = !N.igual(junto, doc);
          doc = junto;
          salvarDoc();
          if (r.etag) poe(CH.etag, r.etag);
          if (N.igual(junto, remoto)) tira(CH.sujo);
          else poe(CH.sujo, '1');
          if (mudouAqui) {
            sinc.versao++;
            desenhar();
          }
        }
        if (!pega(CH.sujo)) return null;
        var versao = sinc.versao;
        return Nuvem.gravar(chaveAtual, id, doc).then(function (g) {
          tira(CH.etag);
          if (g.status === 404) {
            tira(CH.gist);
            sinc.denovo = true;
          } else if (sinc.versao === versao) {
            tira(CH.sujo);
          } else {
            sinc.denovo = true;
          }
        });
      })
      .then(function () {
        sinc.erro = '';
        sinc.ultima = agora();
        poe(CH.ultima, String(sinc.ultima));
      })
      .catch(function (erro) {
        sinc.erro = (erro && erro.tipo) || 'rede';
      })
      .then(function () {
        sinc.rodando = false;
        atualizarLinhaDaSinc();
        if (sinc.denovo) {
          sinc.denovo = false;
          agendarSinc(1500);
        }
      });
  }

  var MENSAGENS = {
    chave: 'O GitHub não aceitou essa chave. Confira se copiou inteira (começa com ghp_).',
    permissao: 'Essa chave não tem a permissão "gist". Gere outra pela página do passo 1.',
    limite: 'O GitHub pediu para esperar um pouco. Tente de novo em alguns minutos.',
    rede: 'Sem conexão com o GitHub agora. Confira a internet e tente de novo.',
    versao: 'Esta conta foi usada numa versão mais nova. Feche e abra o site para atualizar.',
    outro: 'O GitHub respondeu com um erro. Tente de novo daqui a pouco.',
  };

  /** Entra na conta com a chave: carrega o que já existe lá, ou começa uma conta. */
  function conectar(chaveNova, silencioso) {
    if (chaveNova.length < 20) {
      tela.m.aviso = 'Cole a chave inteira. Ela começa com ghp_.';
      if (!silencioso) desenhar();
      return Promise.resolve(false);
    }
    tela.m.ocupado = true;
    tela.m.aviso = '';
    if (!silencioso) desenhar();
    return Nuvem.achar(chaveNova)
      .then(function (id) {
        poe(CH.chave, chaveNova);
        tira(CH.ignorar);
        tira(CH.etag);
        sinc.erro = '';
        if (!id) {
          tira(CH.gist);
          if (doc) {
            // Já havia progresso neste aparelho: ele vira a conta.
            poe(CH.sujo, '1');
            ir('inicio');
            sincronizar();
          } else {
            ir('semConta');
          }
          return true;
        }
        poe(CH.gist, id);
        return Nuvem.ler(chaveNova, id).then(function (r) {
          if (r.status !== 200) throw new Nuvem.Erro('outro');
          if (Number(r.doc && r.doc.v) > 1) throw new Nuvem.Erro('versao');
          var remoto = N.arrumar(N.copia(r.doc));
          doc = doc ? N.juntar(doc, remoto) : remoto;
          N.ajustar(doc, agora());
          salvarDoc();
          if (r.etag) poe(CH.etag, r.etag);
          if (!N.igual(doc, remoto)) poe(CH.sujo, '1');
          sinc.ultima = agora();
          ir('inicio');
          sincronizar();
          return true;
        });
      })
      .catch(function (erro) {
        var tipo = (erro && erro.tipo) || 'rede';
        if (tela.nome !== 'entrar') tela = { nome: 'entrar', m: { chave: chaveNova } };
        tela.m.ocupado = false;
        tela.m.aviso = MENSAGENS[tipo] || MENSAGENS.outro;
        desenhar();
        return false;
      });
  }

  /** Na tela do QR sem progresso ainda: espera o tablet criar a conta e carrega sozinho. */
  var qrTimer = 0;
  function vigiarQr() {
    clearTimeout(qrTimer);
    if (tela.nome !== 'qr' || doc) return;
    qrTimer = setTimeout(function () {
      var chaveAtual = pega(CH.chave);
      if (tela.nome !== 'qr' || doc || !chaveAtual) return;
      Nuvem.achar(chaveAtual)
        .then(function (id) {
          if (!id || doc) return vigiarQr();
          return conectar(chaveAtual, true);
        })
        .catch(function () {
          vigiarQr();
        });
    }, 5000);
  }

  // ---------- partida ----------

  /** Chave vinda no endereço (QR code lido pela câmera, ou ícone da tela de início do iPhone). */
  function chaveDoEndereco() {
    var achou = /[#&]k=([^&]+)/.exec(location.hash || '');
    if (!achou) return '';
    var valor = '';
    try {
      valor = decodeURIComponent(achou[1]);
    } catch (e2) {
      valor = achou[1];
    }
    // No app instalado o endereço de partida é fixo; no navegador, a chave sai da barra.
    if (!instalado()) history.replaceState(null, '', location.pathname + location.search);
    return valor;
  }

  function partir() {
    var daUrl = chaveDoEndereco();
    history.replaceState({ tela: 'inicio' }, '');
    if (daUrl && daUrl !== pega(CH.chave) && daUrl !== pega(CH.ignorar)) {
      tela = { nome: 'entrar', m: { chave: daUrl } };
      desenhar();
      conectar(daUrl);
    } else {
      if (pega(CH.chave) && !doc) tela = { nome: 'semConta', m: {} };
      desenhar();
      sincronizar();
    }

    document.addEventListener('visibilitychange', function () {
      if (document.visibilityState === 'visible') {
        desenhar();
        sincronizar();
      } else if (pega(CH.sujo)) {
        clearTimeout(sinc.timer);
        sincronizar();
      }
    });
    window.addEventListener('online', function () {
      sincronizar();
    });
    window.addEventListener('focus', function () {
      sincronizar();
    });
    // Com o site aberto: confere os outros aparelhos a cada 45 segundos e mantém a tela em dia.
    setInterval(function () {
      if (document.visibilityState !== 'visible') return;
      sincronizar();
      var ativo = document.activeElement;
      var digitando = ativo && (ativo.tagName === 'INPUT' || ativo.tagName === 'TEXTAREA');
      if (!digitando && (tela.nome === 'inicio' || tela.nome === 'estudo')) desenhar();
    }, 45000);

    if ('serviceWorker' in navigator && (location.protocol === 'https:' || location.hostname === 'localhost' || location.hostname === '127.0.0.1')) {
      navigator.serviceWorker.register('sw.js').catch(function () {});
    }
  }

  // Para os testes automáticos do próprio site.
  self.Disciplina = {
    doc: function () {
      return doc;
    },
    sincronizar: sincronizar,
    estado: function () {
      return { tela: tela.nome, sinc: { erro: sinc.erro, ultima: sinc.ultima, rodando: sinc.rodando } };
    },
  };

  partir();
})();
