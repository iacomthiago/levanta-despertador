package com.iacomthiago.levanta

import android.Manifest
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TimePickerLayoutType
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

class MainActivity : ComponentActivity() {

    companion object {
        /** Abre o app já na missão indicada; um número negativo abre a tela inicial. */
        fun intentPara(context: Context, missaoId: Int): Intent =
            Intent(context, MainActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                .putExtra("missao", missaoId)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        // Usado só pelo teste automático, que não consegue falar nem ver a sequência de memória.
        if (intent?.getBooleanExtra("sem_surpresa", false) == true) {
            Repo.updateConfig(Repo.config.copy(surpriseEnabled = false))
        }
        // Também só do teste automático: um turbo para conferir a experiência em dobro.
        if (intent?.getBooleanExtra("turbo_de_teste", false) == true) {
            Jogo.turboDeTeste()
        }
        Scheduler.rescheduleAll(this)
        AlarmService.resumeIfNeeded(this)
        Sync.init(this)
        lerPedido(intent)
        setContent {
            LevantaTheme {
                AppPrincipal()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        lerPedido(intent)
    }

    /** O alarme de uma missão ou um lembrete pediu para abrir uma tela específica. */
    private fun lerPedido(intent: Intent?) {
        if (intent == null || !intent.hasExtra("missao")) return
        Nav.pedido = intent.getIntExtra("missao", -1)
        intent.removeExtra("missao")
    }

    override fun onResume() {
        super.onResume()
        Jogo.fecharDias()
        // Com o alarme tocando, abrir o app leva direto para a tela da missão.
        if (Phase.isActive(Repo.session?.phase)) {
            startActivity(Intent(this, AlarmActivity::class.java))
        }
        // Abrir o app busca o que mudou nos outros aparelhos (se a sincronização estiver ligada).
        Sync.pedir(this)
    }

    override fun onPause() {
        super.onPause()
        // Ao sair, envia o que ficou por subir.
        Sync.pedir(this)
    }
}

private sealed interface Tela {
    data object Inicio : Tela
    data class Editar(val alarme: Alarm?) : Tela
    data object Ajustes : Tela
    data object Historico : Tela
    data object Cadastro : Tela
    data class Treino(val passo: String) : Tela
    data class Missao(val id: Int) : Tela
    data object Estudo : Tela
    data object Recompensas : Tela
    data class Diario(val doEstudo: Boolean) : Tela
    data class Ideias(val doEstudo: Boolean) : Tela
    data object Sincronizar : Tela
}

/** Para onde o botão de voltar leva a partir de cada tela. */
private fun anterior(tela: Tela): Tela = when (tela) {
    is Tela.Cadastro, is Tela.Treino, is Tela.Sincronizar -> Tela.Ajustes
    is Tela.Diario -> if (tela.doEstudo) Tela.Estudo else Tela.Inicio
    is Tela.Ideias -> if (tela.doEstudo) Tela.Estudo else Tela.Inicio
    else -> Tela.Inicio
}

/** Tela de cada tipo de missão: estudo abre os blocos, treino e simples têm tela própria, levantar abre a edição. */
private fun telaDaMissao(alarme: Alarm): Tela = when (alarme.type) {
    Tipo.ESTUDO -> Tela.Estudo
    Tipo.TREINO, Tipo.SIMPLES -> Tela.Missao(alarme.id)
    else -> Tela.Editar(alarme)
}

@Composable
private fun AppPrincipal() {
    var tela by remember { mutableStateOf<Tela>(Tela.Inicio) }

    BackHandler(enabled = tela != Tela.Inicio) {
        tela = anterior(tela)
    }

    // Sincronização ligada: pouco depois de cada mudança ela sobe sozinha e, com o
    // app na frente, busca a cada minuto o que mudou nos outros aparelhos.
    if (Sync.ligada) {
        val contexto = LocalContext.current
        val dono = LocalLifecycleOwner.current
        val marca = Sync.marcaDoEstado()
        LaunchedEffect(marca) {
            delay(2_500L)
            Sync.pedir(contexto)
        }
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000L)
                if (dono.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) Sync.pedir(contexto)
            }
        }
    }

    // O alarme de uma missão (ou um lembrete) pediu uma tela: vai direto para ela.
    val pedido = Nav.pedido
    LaunchedEffect(pedido) {
        if (pedido != null) {
            Nav.pedido = null
            val alarme = Repo.alarms.firstOrNull { it.id == pedido }
            tela = if (alarme == null || alarme.type == Tipo.LEVANTAR) Tela.Inicio else telaDaMissao(alarme)
        }
    }

    when (val atual = tela) {
        is Tela.Inicio -> TelaInicio(
            onEditar = { tela = Tela.Editar(it) },
            onAbrir = { tela = telaDaMissao(it) },
            onAjustes = { tela = Tela.Ajustes },
            onHistorico = { tela = Tela.Historico },
            onDiario = { tela = Tela.Diario(doEstudo = false) },
            onIdeias = { tela = Tela.Ideias(doEstudo = false) },
            onRecompensas = { tela = Tela.Recompensas },
        )

        is Tela.Editar -> TelaEditar(atual.alarme, onVoltar = { tela = Tela.Inicio })
        is Tela.Ajustes -> TelaAjustes(
            onVoltar = { tela = Tela.Inicio },
            onCadastrar = { tela = Tela.Cadastro },
            onTreinar = { tela = Tela.Treino(it) },
            onSincronizar = { tela = Tela.Sincronizar },
        )

        is Tela.Treino -> TelaTreino(atual.passo, onVoltar = { tela = Tela.Ajustes })
        is Tela.Historico -> TelaHistorico(onVoltar = { tela = Tela.Inicio })
        is Tela.Cadastro -> TelaCadastro(onVoltar = { tela = Tela.Ajustes })
        is Tela.Sincronizar -> TelaSincronizar(onVoltar = { tela = Tela.Ajustes })

        is Tela.Missao -> {
            val alarme = Repo.alarms.firstOrNull { it.id == atual.id }
            if (alarme != null && alarme.type == Tipo.TREINO) {
                TelaMissaoTreino(
                    alarme,
                    onVoltar = { tela = Tela.Inicio },
                    onEditar = { tela = Tela.Editar(alarme) },
                )
            } else if (alarme != null && alarme.type == Tipo.SIMPLES) {
                TelaMissaoSimples(
                    alarme,
                    onVoltar = { tela = Tela.Inicio },
                    onEditar = { tela = Tela.Editar(alarme) },
                )
            } else {
                // A missão foi apagada ou mudou de tipo: volta para o começo.
                LaunchedEffect(atual) { tela = Tela.Inicio }
            }
        }

        is Tela.Estudo -> TelaEstudo(
            onVoltar = { tela = Tela.Inicio },
            onDiario = { tela = Tela.Diario(doEstudo = true) },
            onIdeias = { tela = Tela.Ideias(doEstudo = true) },
        )

        is Tela.Recompensas -> TelaRecompensas(onVoltar = { tela = Tela.Inicio })
        is Tela.Diario -> TelaDiario(onVoltar = { tela = anterior(atual) })
        is Tela.Ideias -> TelaIdeias(onVoltar = { tela = anterior(atual) })
    }
}

@Composable
fun Cabecalho(titulo: String, onVoltar: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onVoltar) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Voltar", tint = Cor.Texto)
        }
        Spacer(Modifier.width(4.dp))
        Text(titulo, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    }
    Espaco(6)
}

// ---------- início ----------

@Composable
private fun TelaInicio(
    onEditar: (Alarm?) -> Unit,
    onAbrir: (Alarm) -> Unit,
    onAjustes: () -> Unit,
    onHistorico: () -> Unit,
    onDiario: () -> Unit,
    onIdeias: () -> Unit,
    onRecompensas: () -> Unit,
) {
    val context = LocalContext.current
    val alarmes = Repo.alarms
    var avisoTeste by remember { mutableStateOf(false) }

    val agora by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(20_000L)
        }
    }
    val proximo = remember(alarmes, agora) { Scheduler.nextAlarm(alarmes) }
    val hoje = remember(agora) { LocalDate.now() }
    // Virou o dia com o app aberto: fecha o dia anterior e confere a missão da semana.
    LaunchedEffect(hoje, alarmes) {
        Jogo.fecharDias()
        Jogo.garantirSemana()
    }
    val deHoje = remember(alarmes, hoje) { Jogo.doDia(hoje) }

    Pagina {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Disciplina", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("Missões do dia. Um nível de cada vez.", color = Cor.Suave, fontSize = 15.sp)
            }
            IconButton(onClick = onHistorico) {
                Icon(Icons.Filled.DateRange, contentDescription = "Histórico", tint = Cor.Texto)
            }
            IconButton(onClick = onAjustes) {
                Icon(Icons.Filled.Settings, contentDescription = "Ajustes", tint = Cor.Texto)
            }
        }
        Espaco(16)

        LaunchedEffect(Unit) { Atualizacao.verificar(context) }
        val nova = Atualizacao.nova
        if (nova != null) {
            Cartao {
                Text("Versão nova disponível: ${nova.nome}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Cor.Lilas)
                Text(
                    "Você está na ${Atualizacao.nomeInstalado(context)}. Suas missões, seu nível e seus ajustes continuam.",
                    fontSize = 14.sp,
                    color = Cor.Suave,
                )
                Espaco(12)
                BotaoDeAtualizar()
            }
            Espaco(12)
        }

        CartaoGanho()
        CartaoHeroi()
        Espaco(12)
        CartaoRecompensas(onAbrir = onRecompensas)

        Preparacao()

        TituloSecao("Missões de hoje")
        if (deHoje.isEmpty()) {
            Text(
                "Nenhuma missão para hoje. Crie a primeira em \"Nova missão\".",
                color = Cor.Suave,
                fontSize = 16.sp,
                modifier = Modifier.padding(4.dp),
            )
        }
        for (alarme in deHoje) {
            LinhaMissaoDeHoje(alarme, agora, onAbrir = { onAbrir(alarme) })
            Espaco(10)
        }

        if (Jogo.semana != null) {
            Espaco(4)
            CartaoSemana()
        }

        Espaco(14)
        Cartao {
            Text("PRÓXIMO DESPERTAR", color = Cor.Suave, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            if (proximo != null) {
                Text(hhmm(proximo.second), fontSize = 44.sp, fontWeight = FontWeight.Bold, color = Cor.Lilas, lineHeight = 50.sp)
                Text("${diaCurto(proximo.second)} · ${daquiA(proximo.second)}", fontSize = 16.sp, color = Cor.Texto)
                val deitar = remember(alarmes, agora, Repo.config) { Scheduler.bedtime() }
                if (deitar != null) {
                    Text(
                        "Para dormir ${duracao(Repo.config.sleepMinutes)}, deite até ${hhmm(deitar.first)}.",
                        fontSize = 15.sp,
                        color = Cor.Suave,
                    )
                }
            } else {
                Espaco(6)
                Text("Nenhuma missão de levantar ligada", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        TituloSecao("Todas as missões")
        if (alarmes.isEmpty()) {
            Text("Você ainda não tem missões.", color = Cor.Suave, fontSize = 16.sp, modifier = Modifier.padding(4.dp))
        }
        for (alarme in alarmes) {
            LinhaAlarme(
                alarme = alarme,
                onAbrir = { onEditar(alarme) },
                onLigar = { ligado ->
                    Repo.upsertAlarm(alarme.copy(enabled = ligado))
                    Scheduler.rescheduleAll(context)
                },
            )
            Espaco(10)
        }
        Espaco(6)
        BotaoPrincipal("Nova missão") { onEditar(null) }
        Espaco(10)
        BotaoSecundario("Testar agora (toca em 5 segundos)") {
            Scheduler.scheduleTest(context, 5)
            avisoTeste = true
        }
        if (avisoTeste) {
            Espaco(8)
            Text(
                "O despertador vai tocar em 5 segundos. Bloqueie a tela para ver o alarme abrir sozinho.",
                color = Cor.Verde,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }

        TituloSecao("Mais")
        BotaoSecundario("Ideias para depois", onClick = onIdeias)
        if (Jogo.metas.any { it.feitaEm != 0L }) {
            Espaco(10)
            BotaoSecundario("Diário das metas antigas", onClick = onDiario)
        }
        Espaco(20)
    }
}

/** Botão que baixa a versão nova e abre o instalador do Android, com o andamento do download. */
@Composable
private fun BotaoDeAtualizar() {
    val context = LocalContext.current
    val progresso = Atualizacao.progresso
    BotaoPrincipal(
        if (progresso == null) "Atualizar agora" else "Baixando… $progresso%",
        habilitado = progresso == null,
    ) { Atualizacao.atualizar(context) }
    val aviso = Atualizacao.aviso
    if (aviso != null) {
        Text(aviso, fontSize = 14.sp, color = Cor.Ouro, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
fun Numero(valor: String, legenda: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(valor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(legenda, fontSize = 13.sp, color = Cor.Suave)
    }
}

/** Linha da lista completa: liga, desliga e abre a edição. */
@Composable
private fun LinhaAlarme(alarme: Alarm, onAbrir: () -> Unit, onLigar: (Boolean) -> Unit) {
    Cartao(modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onAbrir)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    hhmm(alarme.hour, alarme.minute),
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (alarme.enabled) Cor.Texto else Cor.Suave,
                )
                val detalhe = listOf(
                    Tipo.nome(alarme.type),
                    alarme.label.takeIf { it != Tipo.nome(alarme.type) }.orEmpty(),
                    resumoDias(alarme.days),
                    if (alarme.toca) "" else "sem toque",
                ).filter { it.isNotBlank() }.joinToString(" · ")
                Text(detalhe, fontSize = 15.sp, color = Cor.Suave)
            }
            Switch(checked = alarme.enabled, onCheckedChange = onLigar)
        }
    }
}

// ---------- nova missão / editar ----------

private fun descricaoDoTipo(tipo: String): String = when (tipo) {
    Tipo.ESTUDO ->
        "Blocos de $MINUTOS_DO_BLOCO minutos para marcar com um toque. Cada bloco vale +${Xp.BLOCO} XP. " +
            "Bater a meta do dia antes da hora combinada dá bônus."

    Tipo.TREINO ->
        "Na hora, abre o seu treino no navegador. Na volta, você toca em Concluí. Vale +${Xp.TREINO} XP."

    Tipo.SIMPLES ->
        "Tarefa rápida, concluída com um toque. Vale +${Xp.SIMPLES} XP."

    else ->
        "O despertador: toca e só para com a missão cumprida. Vale +${Xp.LEVANTAR} XP."
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TelaEditar(original: Alarm?, onVoltar: () -> Unit) {
    val context = LocalContext.current
    val relogio = rememberTimePickerState(
        initialHour = original?.hour ?: 6,
        initialMinute = original?.minute ?: 30,
        is24Hour = true,
    )
    // A primeira missão de um aparelho novo é a de levantar; depois, o mais comum é uma simples.
    var tipo by remember {
        mutableStateOf(
            original?.type ?: if (Repo.alarms.none { it.type == Tipo.LEVANTAR }) Tipo.LEVANTAR else Tipo.SIMPLES
        )
    }
    var dias by remember { mutableStateOf(original?.days?.takeIf { it.isNotEmpty() } ?: (1..7).toSet()) }
    var nome by remember { mutableStateOf(original?.label ?: "") }
    var conta by remember { mutableStateOf(original?.counts ?: true) }
    var toca by remember { mutableStateOf(original?.rings ?: true) }
    var minutos by remember { mutableStateOf(original?.minutes ?: 30) }
    var minimo by remember { mutableStateOf(original?.minPercent ?: 60) }
    var endereco by remember { mutableStateOf(original?.url ?: "") }
    var avisoTeste by remember { mutableStateOf(false) }

    Pagina {
        Cabecalho(if (original == null) "Nova missão" else "Editar missão", onVoltar)

        TituloSecao("Tipo de missão")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (opcao in Tipo.todos) {
                FilterChip(
                    selected = tipo == opcao,
                    onClick = { tipo = opcao },
                    label = { Text(Tipo.nome(opcao), fontSize = 15.sp) },
                )
            }
        }
        Text(
            descricaoDoTipo(tipo),
            fontSize = 14.sp,
            lineHeight = 19.sp,
            color = Cor.Suave,
            modifier = Modifier.padding(top = 4.dp, start = 4.dp),
        )

        Espaco(10)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TimePicker(state = relogio, layoutType = TimePickerLayoutType.Vertical)
        }
        if (tipo != Tipo.LEVANTAR) {
            LinhaChave(
                "Tocar alarme neste horário",
                "Toca forte e para com um toque, que já abre a missão. Desligado, a missão só aparece na lista do dia.",
                toca,
            ) { toca = it }
        }

        TituloSecao("Dias da semana")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (dia in 1..7) {
                FilterChip(
                    selected = dia in dias,
                    onClick = { dias = if (dia in dias) dias - dia else dias + dia },
                    label = { Text(nomeDia(dia), fontSize = 15.sp) },
                )
            }
        }

        TituloSecao(if (tipo == Tipo.SIMPLES) "Nome da missão" else "Nome (opcional)")
        OutlinedTextField(
            value = nome,
            onValueChange = { nome = it.take(40) },
            singleLine = true,
            placeholder = {
                Text(
                    when (tipo) {
                        Tipo.SIMPLES -> "Ex.: Beber água, arrumar a mesa"
                        Tipo.TREINO -> "Ex.: Treino em casa"
                        Tipo.ESTUDO -> "Ex.: Estudo para a prova"
                        else -> "Ex.: Estudar antes do trabalho"
                    }
                )
            },
            modifier = Modifier.fillMaxWidth(),
        )

        if (tipo == Tipo.TREINO) {
            TituloSecao("Treino")
            Cartao {
                LinhaNumero("Duração prevista", minutos, 10..120, passo = 5, rotulo = { "$it min" }) { minutos = it }
                LinhaNumero("Mínimo para concluir", minimo, 0..100, passo = 10, rotulo = { "$it%" }) { minimo = it }
                Text(
                    if (minimo == 0) "O botão Concluí libera assim que você começa."
                    else "O botão Concluí libera ${minutos * minimo / 100} min depois de você tocar em começar.",
                    fontSize = 13.sp,
                    color = Cor.Suave,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Espaco(8)
                OutlinedTextField(
                    value = endereco,
                    onValueChange = { endereco = it.take(200).trim() },
                    singleLine = true,
                    label = { Text("Endereço que abre (vazio usa o seu treino em casa)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        Espaco(10)
        Cartao {
            LinhaChave(
                "Conta para a sequência",
                "Se esta missão ficar sem concluir até o fim do dia, a sequência de dias zera.",
                conta,
            ) { conta = it }
        }

        Espaco(24)
        BotaoPrincipal("Salvar missão") {
            Repo.upsertAlarm(
                Alarm(
                    id = original?.id ?: Repo.nextAlarmId(),
                    hour = relogio.hour,
                    minute = relogio.minute,
                    days = dias.ifEmpty { (1..7).toSet() },
                    enabled = true,
                    label = nome.trim(),
                    type = tipo,
                    counts = conta,
                    rings = toca,
                    minutes = minutos,
                    minPercent = minimo,
                    url = endereco.trim(),
                    createdAt = original?.createdAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
                )
            )
            Scheduler.rescheduleAll(context)
            Jogo.garantirSemana()
            onVoltar()
        }
        if (original != null && original.type != Tipo.LEVANTAR) {
            Espaco(10)
            BotaoSecundario("Testar o alarme desta missão (toca em 5 segundos)") {
                Scheduler.scheduleTest(context, 5, original.id)
                avisoTeste = true
            }
            if (avisoTeste) {
                Espaco(8)
                Text(
                    "Vai tocar em 5 segundos, do jeito que toca no horário.",
                    color = Cor.Verde,
                    fontSize = 15.sp,
                    modifier = Modifier.padding(horizontal = 4.dp),
                )
            }
        }
        if (original != null) {
            Espaco(10)
            BotaoSecundario("Excluir missão") {
                Scheduler.cancel(context, original.id)
                Repo.deleteAlarm(original.id)
                onVoltar()
            }
        }
        Espaco(20)
    }
}

// ---------- ajustes ----------

@Composable
private fun TelaAjustes(
    onVoltar: () -> Unit,
    onCadastrar: () -> Unit,
    onTreinar: (String) -> Unit,
    onSincronizar: () -> Unit,
) {
    val context = LocalContext.current
    val c = Repo.config
    val niveis = listOf("Fácil", "Média", "Difícil")
    var microfone by remember { mutableStateOf(microfoneLiberado(context)) }
    val pedirMicrofone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        microfone = ok
    }

    val pedirCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onCadastrar()
    }
    val cadastrar = {
        if (cameraLiberada(context)) onCadastrar() else pedirCamera.launch(Manifest.permission.CAMERA)
    }

    val escopo = rememberCoroutineScope()
    var avisoImagem by remember { mutableStateOf("") }
    val escolherImagem = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            avisoImagem = "Copiando a imagem…"
            escopo.launch {
                val ok = withContext(Dispatchers.IO) { Jogo.copiarImagem(uri) }
                if (ok) Jogo.definirImagem(true)
                avisoImagem = if (ok) "" else "Não consegui abrir essa imagem. Tente outra."
            }
        }
    }

    Pagina {
        Cabecalho("Ajustes", onVoltar)

        TituloSecao("Seu personagem")
        Cartao {
            ImagemDoPersonagem()
            Espaco(12)
            BotaoSecundario("Escolher imagem da galeria") { escolherImagem.launch("image/*") }
            if (Jogo.imagem != 0L) {
                Espaco(8)
                BotaoSecundario("Voltar para a imagem que vem no app") { Jogo.definirImagem(false) }
            }
            Text(
                avisoImagem.ifEmpty { "A imagem escolhida fica só neste tablet. A borda muda de cor conforme a sua faixa de nível." },
                fontSize = 13.sp,
                color = if (avisoImagem.isEmpty()) Cor.Suave else Cor.Ouro,
                modifier = Modifier.padding(top = 8.dp),
            )
        }

        TituloSecao("Progresso e estudo")
        Cartao {
            LinhaNumero("Bônus de estudo até", c.studyBonusHour, 8..22, rotulo = { "$it h" }) {
                Repo.updateConfig(c.copy(studyBonusHour = it))
            }
            LinhaNumero(
                "Blocos de estudo para o dia contar",
                c.studyBlocks.coerceIn(1, BLOCOS_POR_DIA),
                1..BLOCOS_POR_DIA,
                rotulo = { "$it (${tempoDeBlocos(it)})" },
            ) {
                Repo.updateConfig(c.copy(studyBlocks = it))
                // Baixar a meta pode fechar o dia de hoje com os blocos já marcados.
                Jogo.conferirEstudo()
            }
            LinhaChave(
                "Avisar se a sequência está em risco",
                "Às ${Scheduler.STREAK_WARNING_HOUR} h, se ainda houver missão do dia que conta para a sequência.",
                c.streakWarning,
            ) {
                Repo.updateConfig(c.copy(streakWarning = it))
                Scheduler.rescheduleAll(context)
            }
            Text(
                "Nível ${Jogo.nivel} · ${Jogo.xp} XP · recorde de ${Jogo.recorde} dias seguidos.",
                fontSize = 13.sp,
                color = Cor.Suave,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        TituloSecao("Missões para desligar o despertador")
        Cartao {
            LinhaChave(
                "Questões do concurso",
                "Certo ou errado de Constituição e Licitações. Errou, lê a explicação e responde outra.",
                c.quizEnabled,
            ) { Repo.updateConfig(c.copy(quizEnabled = it)) }
            if (c.quizEnabled) {
                LinhaNumero("Acertos para desligar", c.quizCount, 3..10) { Repo.updateConfig(c.copy(quizCount = it)) }
                Text(
                    "Baralho com ${Questoes.todas(context).size} questões. Primeiro vêm as que você errou e as que ainda não viu.",
                    fontSize = 13.sp,
                    color = Cor.Suave,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Espaco(10)
        Cartao {
            LinhaChave("Contas de matemática", "Resolver contas num teclado na tela.", c.mathEnabled) {
                Repo.updateConfig(c.copy(mathEnabled = it))
            }
            if (c.mathEnabled) {
                LinhaNumero("Quantidade", c.mathCount, 1..10) { Repo.updateConfig(c.copy(mathCount = it)) }
                LinhaNumero("Dificuldade", c.mathLevel, 0..2, rotulo = { niveis[it.coerceIn(0, 2)] }) {
                    Repo.updateConfig(c.copy(mathLevel = it))
                }
            }
        }
        Espaco(10)
        Cartao {
            LinhaChave("Montar a frase", "Tocar nas palavras do seu compromisso na ordem certa.", c.phraseEnabled) {
                Repo.updateConfig(c.copy(phraseEnabled = it))
            }
            if (c.phraseEnabled) {
                Espaco(6)
                OutlinedTextField(
                    value = c.phrase,
                    onValueChange = { Repo.updateConfig(c.copy(phrase = it.take(120))) },
                    label = { Text("Sua frase") },
                    modifier = Modifier.fillMaxWidth(),
                )
                val n = palavrasDaFrase(c.phrase).size
                Text(
                    if (n < 3) "Use pelo menos 3 palavras, senão esta missão é pulada." else "Use de 3 a 14 palavras.",
                    fontSize = 13.sp,
                    color = if (n < 3) Cor.Vermelho else Cor.Suave,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
        Espaco(10)
        Cartao {
            val temCodigo = c.scanCode.isNotBlank()
            LinhaChave(
                "Escanear código em outro cômodo",
                "Só desliga lendo um código que fica longe da cama, como o da pasta de dente no banheiro.",
                c.scanEnabled && temCodigo,
            ) { ligar ->
                if (!ligar) Repo.updateConfig(c.copy(scanEnabled = false))
                else if (temCodigo && cameraLiberada(context)) Repo.updateConfig(c.copy(scanEnabled = true))
                else cadastrar()
            }
            Text(
                if (temCodigo) "Código cadastrado: ${c.scanCode.take(28)}" else "Nenhum código cadastrado.",
                fontSize = 14.sp,
                color = Cor.Suave,
            )
            Espaco(10)
            BotaoSecundario(if (temCodigo) "Trocar o código" else "Cadastrar um código") { cadastrar() }
        }
        Espaco(10)
        Cartao {
            LinhaChave(
                "Falar a afirmação",
                "Uma missão extra a cada despertar: falar sua afirmação em voz alta. Se o tablet não conseguir ouvir, entra uma sequência de memória.",
                c.surpriseEnabled,
            ) { Repo.updateConfig(c.copy(surpriseEnabled = it)) }
            if (c.surpriseEnabled) {
                Espaco(6)
                OutlinedTextField(
                    value = c.affirmation,
                    onValueChange = { Repo.updateConfig(c.copy(affirmation = it.take(120))) },
                    label = { Text("Sua afirmação") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Espaco(8)
                OutlinedTextField(
                    value = c.encouragement,
                    onValueChange = { Repo.updateConfig(c.copy(encouragement = it.take(160))) },
                    label = { Text("O que o tablet responde em voz alta") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "Depois de ouvir a sua afirmação, o tablet diz esta frase. Deixe vazio para ele não falar.",
                    fontSize = 13.sp,
                    color = Cor.Suave,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Espaco(8)
                if (!microfone) {
                    Text(
                        "Para o tablet ouvir a afirmação, libere o microfone. Sem isso, entra a sequência de memória no lugar.",
                        fontSize = 14.sp,
                        color = Cor.Ouro,
                    )
                    Espaco(8)
                    BotaoSecundario("Liberar o microfone") { pedirMicrofone.launch(Manifest.permission.RECORD_AUDIO) }
                    Espaco(8)
                } else if (!vozDisponivel(context)) {
                    Text(
                        "Este tablet não tem reconhecimento de voz ativo; no lugar entra a sequência de memória.",
                        fontSize = 14.sp,
                        color = Cor.Ouro,
                    )
                    Espaco(8)
                }
                BotaoSecundario("Treinar: falar a afirmação") { onTreinar(PASSO_VOZ) }
                Espaco(8)
                BotaoSecundario("Treinar: memória") { onTreinar(PASSO_MEMORIA) }
            }
        }

        TituloSecao("Depois da missão")
        Cartao {
            LinhaNumero(
                "Checagens \"continua de pé?\"",
                c.recheckCount,
                0..3,
                rotulo = { if (it == 0) "Nenhuma" else it.toString() },
            ) { Repo.updateConfig(c.copy(recheckCount = it)) }
            if (c.recheckCount > 0) {
                LinhaNumero("Intervalo até a checagem", c.recheckMinutes, 1..30, rotulo = { "$it min" }) {
                    Repo.updateConfig(c.copy(recheckMinutes = it))
                }
            }
            LinhaChave(
                "Abrir o Claude ao concluir",
                "Depois da última checagem, abre o app do Claude neste tablet, se estiver instalado.",
                c.openClaude,
            ) { Repo.updateConfig(c.copy(openClaude = it)) }
        }

        TituloSecao("Hora de dormir")
        Cartao {
            LinhaChave(
                "Avisar a hora de dormir",
                "Um toque meia hora antes e outro na hora de deitar, calculados a partir do próximo alarme.",
                c.bedtimeEnabled,
            ) {
                Repo.updateConfig(c.copy(bedtimeEnabled = it))
                Scheduler.rescheduleAll(context)
            }
            if (c.bedtimeEnabled) {
                LinhaNumero("Horas de sono", c.sleepMinutes, 300..600, passo = 30, rotulo = { duracao(it) }) {
                    Repo.updateConfig(c.copy(sleepMinutes = it))
                    Scheduler.rescheduleAll(context)
                }
            }
        }

        TituloSecao("Prova")
        Cartao {
            OutlinedTextField(
                value = c.examDate,
                onValueChange = { Repo.updateConfig(c.copy(examDate = it.take(10))) },
                singleLine = true,
                label = { Text("Data da prova (dd/mm/aaaa)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                fraseDaProva(c.examDate)
                    ?: if (c.examDate.isBlank()) "Sem data, a contagem não aparece." else "Data inválida ou que já passou.",
                fontSize = 14.sp,
                color = Cor.Suave,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        TituloSecao("Sincronização com o site")
        Cartao {
            var confirmar by remember { mutableStateOf(false) }
            if (Sync.ligada) {
                Text("Ligada", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Cor.Verde)
                Text(
                    "O que você faz aqui aparece sozinho no site ($SITE_DISCIPLINA), e o que você faz lá aparece aqui. " +
                        "Não precisa apertar nada.",
                    fontSize = 14.sp,
                    color = Cor.Suave,
                )
                if (Sync.situacao.isNotEmpty()) {
                    Text(Sync.situacao, fontSize = 14.sp, color = Cor.Suave, modifier = Modifier.padding(top = 6.dp))
                }
                Espaco(10)
                BotaoSecundario(if (confirmar) "Desligar mesmo? Toque de novo" else "Desligar neste tablet") {
                    if (confirmar) Sync.desligar(context)
                    confirmar = !confirmar
                }
                Text(
                    "Desligar não apaga nada: o progresso continua neste tablet e na sua conta.",
                    fontSize = 13.sp,
                    color = Cor.Suave,
                    modifier = Modifier.padding(top = 8.dp),
                )
            } else {
                Text("Desligada", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "Ligando, o progresso deste tablet aparece igual no site, no celular e no computador. " +
                        "O despertador continua só aqui.",
                    fontSize = 14.sp,
                    color = Cor.Suave,
                )
                Espaco(10)
                BotaoSecundario("Ligar a sincronização", onClick = onSincronizar)
            }
        }

        TituloSecao("Sobre o app")
        Cartao {
            Text("Versão instalada: ${Atualizacao.nomeInstalado(context)}", fontSize = 17.sp)
            Espaco(10)
            val nova = Atualizacao.nova
            if (nova != null) {
                Text("Versão nova disponível: ${nova.nome}", fontSize = 15.sp, color = Cor.Lilas)
                Espaco(8)
                BotaoDeAtualizar()
            } else {
                BotaoSecundario("Procurar atualização") { Atualizacao.verificar(context, forcar = true) }
                val aviso = Atualizacao.aviso
                if (aviso != null) {
                    Text(aviso, fontSize = 14.sp, color = Cor.Suave, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }

        TituloSecao("Som e soneca")
        Cartao {
            LinhaChave(
                "Começar com a abertura do HOTARU",
                "A música toca primeiro. Se a missão não for cumprida a tempo, entra o bipe.",
                c.musicFirst,
            ) { Repo.updateConfig(c.copy(musicFirst = it)) }
            if (c.musicFirst) {
                LinhaNumero("Tempo de música", c.musicSeconds, 30..90, passo = 15, rotulo = { "$it s" }) {
                    Repo.updateConfig(c.copy(musicSeconds = it))
                }
            }
            LinhaChave("Volume crescente", "Começa bem baixinho e vai subindo aos poucos, até o volume máximo escolhido abaixo, em 1 minuto.", c.gradualVolume) {
                Repo.updateConfig(c.copy(gradualVolume = it))
            }
            LinhaNumero("Volume máximo do alarme", c.maxVolume, VOLUME_MINIMO..100, passo = 5, rotulo = { "$it%" }) {
                Repo.updateConfig(c.copy(maxVolume = it))
            }
            Text(
                "Quanto do volume do aparelho o alarme usa. Vale para todos os alarmes. " +
                    "Se acordar a casa, diminua; se não acordar você, aumente.",
                color = Cor.Suave,
                fontSize = 14.sp,
                lineHeight = 19.sp,
            )
            LinhaNumero(
                "Sonecas permitidas",
                c.maxSnoozes,
                0..3,
                rotulo = { if (it == 0) "Nenhuma" else it.toString() },
            ) { Repo.updateConfig(c.copy(maxSnoozes = it)) }
            if (c.maxSnoozes > 0) {
                LinhaNumero("Duração da soneca", c.snoozeMinutes, 1..15, rotulo = { "$it min" }) {
                    Repo.updateConfig(c.copy(snoozeMinutes = it))
                }
            }
        }
        Espaco(24)
    }
}

/** Lê um código pela câmera e guarda como o código da missão. */
@Composable
private fun TelaCadastro(onVoltar: () -> Unit) {
    var lido by remember { mutableStateOf<String?>(null) }

    Pagina {
        Cabecalho("Cadastrar código", onVoltar)
        Text(
            "Aponte a câmera para um código de barras ou QR code que fique longe da cama: " +
                "a embalagem da pasta de dente, um produto na cozinha, um papel colado na parede.",
            fontSize = 16.sp,
            color = Cor.Suave,
        )
        Espaco(14)
        Leitor(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
                .clip(RoundedCornerShape(20.dp)),
        ) { codigo ->
            if (lido == null) lido = codigo
        }
        Espaco(14)
        val atual = lido
        if (atual == null) {
            Text("Procurando um código…", fontSize = 16.sp, color = Cor.Suave)
        } else {
            Text("Código lido", fontSize = 14.sp, color = Cor.Suave)
            Text(atual.take(60), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Cor.Verde)
            Espaco(14)
            BotaoPrincipal("Usar este código") {
                Repo.updateConfig(Repo.config.copy(scanCode = atual, scanEnabled = true))
                onVoltar()
            }
            Espaco(10)
            BotaoSecundario("Ler outro") { lido = null }
        }
        Espaco(20)
    }
}

// ---------- sincronização com o site ----------

/** Liga a sincronização lendo o QR code que o site mostra, ou colando a chave. */
@Composable
private fun TelaSincronizar(onVoltar: () -> Unit) {
    val context = LocalContext.current
    var camera by remember { mutableStateOf(cameraLiberada(context)) }
    val pedirCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        camera = ok
    }
    var colada by remember { mutableStateOf("") }
    // Um código que o GitHub recusou não é tentado de novo enquanto continua na frente da câmera.
    var recusado by remember { mutableStateOf("") }

    Pagina {
        Cabecalho("Sincronizar com o site", onVoltar)
        if (Sync.ligada) {
            Cartao(cor = Cor.CartaoAlto) {
                Text("Sincronização ligada.", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Cor.Verde)
                Espaco(4)
                Text(
                    "O progresso deste tablet já está indo para a sua conta. Daqui para a frente é sozinho.",
                    fontSize = 16.sp,
                    color = Cor.Suave,
                )
            }
            Espaco(16)
            BotaoPrincipal("Voltar", onClick = onVoltar)
        } else {
            Text(
                "1. No computador ou no celular, abra $SITE_DISCIPLINA\n" +
                    "2. Lá, ligue a sincronização e toque em \"Ligar outro aparelho\".\n" +
                    "3. Aponte a câmera deste tablet para o QR code que aparecer.",
                fontSize = 16.sp,
                lineHeight = 23.sp,
                color = Cor.Suave,
            )
            Espaco(14)
            when {
                Sync.ligando -> Text("Conferindo a chave…", fontSize = 18.sp, color = Cor.Lilas)

                camera -> Leitor(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp)
                        .clip(RoundedCornerShape(20.dp)),
                ) { codigo ->
                    val pareceChave = codigo.contains("k=") || codigo.startsWith("ghp_") || codigo.startsWith("github_pat_")
                    if (pareceChave && codigo != recusado && !Sync.ligando && !Sync.ligada) {
                        Sync.ligar(context, codigo) { ok -> if (!ok) recusado = codigo }
                    }
                }

                else -> BotaoPrincipal("Liberar a câmera para ler o QR code") {
                    pedirCamera.launch(Manifest.permission.CAMERA)
                }
            }
            if (Sync.avisoDeLigar.isNotEmpty()) {
                Espaco(10)
                Text(Sync.avisoDeLigar, fontSize = 15.sp, color = Cor.Ouro)
            }

            TituloSecao("Ou cole a chave")
            OutlinedTextField(
                value = colada,
                onValueChange = { colada = it.take(200) },
                singleLine = true,
                label = { Text("Chave do GitHub (começa com ghp_)") },
                modifier = Modifier.fillMaxWidth(),
            )
            Espaco(10)
            BotaoSecundario("Conectar com a chave colada", habilitado = colada.trim().length >= 20 && !Sync.ligando) {
                Sync.ligar(context, colada)
            }
        }
        Espaco(20)
    }
}

// ---------- treino das missões surpresa ----------

/** Deixa experimentar uma missão surpresa fora do alarme, para conferir que funciona neste tablet. */
@Composable
private fun TelaTreino(passo: String, onVoltar: () -> Unit) {
    var feito by remember { mutableStateOf(false) }

    Pagina(centralizar = true) {
        if (feito) {
            Text("Missão cumprida.", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Cor.Verde)
            Espaco(18)
            BotaoPrincipal("Voltar aos ajustes", onClick = onVoltar)
        } else {
            Text("TREINO", color = Cor.Lilas, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
            Espaco(10)
            if (passo == PASSO_VOZ) {
                MissaoVoz(Repo.config.affirmation, onConcluir = { feito = true })
            } else {
                MissaoMemoria(onConcluir = { feito = true })
            }
            Espaco(22)
            BotaoSecundario("Voltar", onClick = onVoltar)
        }
    }
}

// ---------- histórico ----------

@Composable
private fun TelaHistorico(onVoltar: () -> Unit) {
    val historico = Repo.history

    Pagina {
        Cabecalho("Histórico", onVoltar)
        Cartao(cor = Cor.CartaoAlto) {
            Row {
                Numero(Repo.streak().toString(), "despertares seguidos", Modifier.weight(1f))
                val media = Repo.averageMinutesToGetUp(30)
                Numero(if (media == null) "–" else "$media min", "média para levantar", Modifier.weight(1f))
                Numero(historico.count { it.outcome != Outcome.OK }.toString(), "sem resposta", Modifier.weight(1f))
            }
        }
        TituloSecao("Registros")
        if (historico.isEmpty()) {
            Text(
                "Nada por aqui ainda. Cada alarme de verdade entra nesta lista; os testes não.",
                color = Cor.Suave,
                fontSize = 16.sp,
                modifier = Modifier.padding(4.dp),
            )
        }
        for (item in historico.take(90)) {
            val ok = item.outcome == Outcome.OK
            Cartao {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(diaCurto(item.startedAt), fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        val partes = buildList {
                            add("Tocou às ${hhmm(item.startedAt)}")
                            if (ok && item.upAt > 0L) add("missão às ${hhmm(item.upAt)}")
                            if (item.snoozes > 0) add("sonecas: ${item.snoozes}")
                            if (item.relapses > 0) add("recaídas: ${item.relapses}")
                            if (item.label.isNotBlank()) add(item.label)
                        }
                        Text(partes.joinToString(" · "), fontSize = 14.sp, lineHeight = 19.sp, color = Cor.Suave)
                    }
                    Spacer(Modifier.width(10.dp))
                    if (ok) {
                        val minutos = ((item.upAt - item.startedAt).coerceAtLeast(0L) / 60_000L).toInt()
                        Text("$minutos min", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = if (item.relapses > 0) Cor.Ouro else Cor.Verde)
                    } else {
                        Text("Sem resposta", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Cor.Vermelho)
                    }
                }
            }
            Espaco(8)
        }
        Espaco(20)
    }
}
