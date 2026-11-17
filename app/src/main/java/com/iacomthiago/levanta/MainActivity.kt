package com.iacomthiago.levanta

import android.Manifest
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        Scheduler.rescheduleAll(this)
        AlarmService.resumeIfNeeded(this)
        setContent {
            LevantaTheme {
                AppPrincipal()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Com o alarme tocando, abrir o app leva direto para a tela da missão.
        if (Phase.isActive(Repo.session?.phase)) {
            startActivity(Intent(this, AlarmActivity::class.java))
        }
    }
}

private sealed interface Tela {
    data object Inicio : Tela
    data class Editar(val alarme: Alarm?) : Tela
    data object Ajustes : Tela
    data object Historico : Tela
    data object Cadastro : Tela
}

@Composable
private fun AppPrincipal() {
    var tela by remember { mutableStateOf<Tela>(Tela.Inicio) }

    BackHandler(enabled = tela != Tela.Inicio) {
        tela = if (tela == Tela.Cadastro) Tela.Ajustes else Tela.Inicio
    }

    when (val atual = tela) {
        is Tela.Inicio -> TelaInicio(
            onEditar = { tela = Tela.Editar(it) },
            onAjustes = { tela = Tela.Ajustes },
            onHistorico = { tela = Tela.Historico },
        )

        is Tela.Editar -> TelaEditar(atual.alarme, onVoltar = { tela = Tela.Inicio })
        is Tela.Ajustes -> TelaAjustes(onVoltar = { tela = Tela.Inicio }, onCadastrar = { tela = Tela.Cadastro })
        is Tela.Historico -> TelaHistorico(onVoltar = { tela = Tela.Inicio })
        is Tela.Cadastro -> TelaCadastro(onVoltar = { tela = Tela.Ajustes })
    }
}

@Composable
private fun Cabecalho(titulo: String, onVoltar: () -> Unit) {
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
private fun TelaInicio(onEditar: (Alarm?) -> Unit, onAjustes: () -> Unit, onHistorico: () -> Unit) {
    val context = LocalContext.current
    val alarmes = Repo.alarms
    val historico = Repo.history
    var avisoTeste by remember { mutableStateOf(false) }

    val agora by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(20_000L)
        }
    }
    val proximo = remember(alarmes, agora) { Scheduler.nextAlarm(alarmes) }

    Pagina {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Levanta", fontSize = 32.sp, fontWeight = FontWeight.Bold)
                Text("Só para de tocar com a missão cumprida.", color = Cor.Suave, fontSize = 15.sp)
            }
            IconButton(onClick = onHistorico) {
                Icon(Icons.Filled.DateRange, contentDescription = "Histórico", tint = Cor.Texto)
            }
            IconButton(onClick = onAjustes) {
                Icon(Icons.Filled.Settings, contentDescription = "Ajustes", tint = Cor.Texto)
            }
        }
        Espaco(16)

        Cartao(cor = Cor.CartaoAlto) {
            Text("PRÓXIMO ALARME", color = Cor.Suave, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp)
            if (proximo != null) {
                Text(hhmm(proximo.second), fontSize = 60.sp, fontWeight = FontWeight.Bold, color = Cor.Lilas, lineHeight = 66.sp)
                Text("${diaCurto(proximo.second)} · ${daquiA(proximo.second)}", fontSize = 16.sp, color = Cor.Texto)
            } else {
                Espaco(6)
                Text("Nenhum alarme ligado", fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            }
            Espaco(14)
            Row {
                Numero(Repo.streak().toString(), "despertares seguidos", Modifier.weight(1f))
                val media = remember(historico) { Repo.averageMinutesToGetUp() }
                Numero(if (media == null) "–" else "$media min", "média para levantar", Modifier.weight(1f))
            }
        }

        Preparacao()

        TituloSecao("Alarmes")
        if (alarmes.isEmpty()) {
            Text("Você ainda não tem alarmes.", color = Cor.Suave, fontSize = 16.sp, modifier = Modifier.padding(4.dp))
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
        BotaoPrincipal("Novo alarme") { onEditar(null) }
        Espaco(10)
        BotaoSecundario("Testar agora (toca em 5 segundos)") {
            Scheduler.scheduleTest(context, 5)
            avisoTeste = true
        }
        if (avisoTeste) {
            Espaco(8)
            Text(
                "Vai tocar em 5 segundos. Bloqueie a tela para ver o alarme abrir sozinho.",
                color = Cor.Verde,
                fontSize = 15.sp,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
        Espaco(20)
    }
}

@Composable
private fun Numero(valor: String, legenda: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(valor, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text(legenda, fontSize = 13.sp, color = Cor.Suave)
    }
}

@Composable
private fun LinhaAlarme(alarme: Alarm, onAbrir: () -> Unit, onLigar: (Boolean) -> Unit) {
    Cartao(modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onAbrir)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    hhmm(alarme.hour, alarme.minute),
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (alarme.enabled) Cor.Texto else Cor.Suave,
                )
                val detalhe = listOf(resumoDias(alarme.days), alarme.label).filter { it.isNotBlank() }.joinToString(" · ")
                Text(detalhe, fontSize = 15.sp, color = Cor.Suave)
            }
            Switch(checked = alarme.enabled, onCheckedChange = onLigar)
        }
    }
}

// ---------- novo alarme / editar ----------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TelaEditar(original: Alarm?, onVoltar: () -> Unit) {
    val context = LocalContext.current
    val relogio = rememberTimePickerState(
        initialHour = original?.hour ?: 6,
        initialMinute = original?.minute ?: 30,
        is24Hour = true,
    )
    var dias by remember { mutableStateOf(original?.days?.takeIf { it.isNotEmpty() } ?: (1..7).toSet()) }
    var nome by remember { mutableStateOf(original?.label ?: "") }

    Pagina {
        Cabecalho(if (original == null) "Novo alarme" else "Editar alarme", onVoltar)
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            TimePicker(state = relogio, layoutType = TimePickerLayoutType.Vertical)
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

        TituloSecao("Nome (opcional)")
        OutlinedTextField(
            value = nome,
            onValueChange = { nome = it.take(40) },
            singleLine = true,
            placeholder = { Text("Ex.: Estudar antes do trabalho") },
            modifier = Modifier.fillMaxWidth(),
        )

        Espaco(24)
        BotaoPrincipal("Salvar alarme") {
            Repo.upsertAlarm(
                Alarm(
                    id = original?.id ?: Repo.nextAlarmId(),
                    hour = relogio.hour,
                    minute = relogio.minute,
                    days = dias.ifEmpty { (1..7).toSet() },
                    enabled = true,
                    label = nome.trim(),
                )
            )
            Scheduler.rescheduleAll(context)
            onVoltar()
        }
        if (original != null) {
            Espaco(10)
            BotaoSecundario("Excluir alarme") {
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
private fun TelaAjustes(onVoltar: () -> Unit, onCadastrar: () -> Unit) {
    val context = LocalContext.current
    val c = Repo.config
    val niveis = listOf("Fácil", "Média", "Difícil")

    val pedirCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) onCadastrar()
    }
    val cadastrar = {
        if (cameraLiberada(context)) onCadastrar() else pedirCamera.launch(Manifest.permission.CAMERA)
    }

    Pagina {
        Cabecalho("Ajustes", onVoltar)

        TituloSecao("Missões para desligar")
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

        TituloSecao("Som e soneca")
        Cartao {
            LinhaChave("Volume crescente", "Começa baixo e chega ao máximo em 45 segundos.", c.gradualVolume) {
                Repo.updateConfig(c.copy(gradualVolume = it))
            }
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
