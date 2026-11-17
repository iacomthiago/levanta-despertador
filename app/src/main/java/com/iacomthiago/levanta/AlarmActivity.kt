package com.iacomthiago.levanta

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Tela do alarme. Abre por cima do bloqueio, liga a tela e não tem botão de
 * cancelar: o caminho para o silêncio é cumprir a missão.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)

        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val claudeInstalado = intentDoClaude(this) != null

        setContent {
            LevantaTheme {
                TelaDoAlarme(
                    claudeInstalado = claudeInstalado,
                    onFechar = {
                        Repo.lastResult = null
                        finish()
                    },
                    onAbrirClaude = { abrirClaude() },
                )
            }
        }
    }

    /** Enquanto o alarme toca, os botões de volume do aparelho não fazem nada. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val tocando = Phase.isActive(Repo.session?.phase)
        val volume = event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
            event.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        if (tocando && volume) return true
        return super.dispatchKeyEvent(event)
    }

    private fun abrirClaude() {
        val abrir = intentDoClaude(this) ?: return
        val trava = getSystemService(KeyguardManager::class.java)
        if (trava != null && trava.isKeyguardLocked) {
            // Com o tablet bloqueado, o sistema pede o desbloqueio antes de abrir outro app.
            trava.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    iniciar(abrir)
                }
            })
        } else {
            iniciar(abrir)
        }
    }

    private fun iniciar(abrir: android.content.Intent) {
        try {
            startActivity(abrir)
            Repo.lastResult = null
            finish()
        } catch (e: Exception) {
            // o Claude foi desinstalado nesse meio-tempo; fica na tela de bom dia
        }
    }
}

fun intentDoClaude(context: Context): android.content.Intent? =
    context.packageManager.getLaunchIntentForPackage("com.anthropic.claude")

@Composable
private fun TelaDoAlarme(claudeInstalado: Boolean, onFechar: () -> Unit, onAbrirClaude: () -> Unit) {
    val context = LocalContext.current
    val sessao = Repo.session
    val resultado = Repo.lastResult
    val fase = sessao?.phase
    var emMissao by remember { mutableStateOf(false) }

    val agora by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(500L)
        }
    }

    // Voltar não fecha nada enquanto houver um despertar em andamento.
    BackHandler(enabled = sessao != null) {}

    // Qualquer troca de fase tira a pessoa de dentro da missão.
    LaunchedEffect(fase, sessao?.phaseSince) {
        emMissao = false
    }

    when {
        sessao == null && resultado != null ->
            TelaFim(resultado, claudeInstalado, onFechar, onAbrirClaude)

        sessao == null -> {
            // Aberta sem alarme ativo (por exemplo, por uma notificação antiga).
            LaunchedEffect(Unit) { onFechar() }
        }

        Phase.isLoud(fase) ->
            if (emMissao) {
                FluxoDeMissoes(onConcluir = { AlarmService.send(context, AlarmService.ACTION_MISSION_DONE) })
            } else {
                TelaTocando(
                    sessao = sessao,
                    agora = agora,
                    onComecar = {
                        Repo.missionStartedAt = System.currentTimeMillis()
                        tregua()
                        emMissao = true
                    },
                    onSoneca = { AlarmService.send(context, AlarmService.ACTION_SNOOZE) },
                )
            }

        fase == Phase.RECHECK ->
            TelaChecagem(sessao, agora) { AlarmService.send(context, AlarmService.ACTION_RECHECK_OK) }

        fase == Phase.WAITING -> TelaEspera(sessao, agora, onFechar = { (context as? ComponentActivity)?.finish() })

        else -> TelaSoneca(sessao, agora, onFechar = { (context as? ComponentActivity)?.finish() })
    }
}

/**
 * Contagem regressiva. A descrição para leitores de tela é fixa: se mudasse a
 * cada segundo, o leitor ficaria anunciando números sem parar.
 */
@Composable
private fun Contagem(restante: Long, cor: androidx.compose.ui.graphics.Color) {
    Text(
        contagem(restante),
        fontSize = 72.sp,
        fontWeight = FontWeight.Bold,
        color = cor,
        modifier = Modifier.clearAndSetSemantics { contentDescription = "Contagem regressiva" },
    )
}

@Composable
private fun TelaTocando(sessao: Session, agora: Long, onComecar: () -> Unit, onSoneca: () -> Unit) {
    val recaida = sessao.phase == Phase.RELAPSE
    val config = Repo.config
    val sonecasRestantes = config.maxSnoozes - sessao.snoozesUsed

    Pagina(centralizar = true) {
        Text(
            if (recaida) "VOCÊ NÃO CONFIRMOU" else "HORA DE LEVANTAR",
            color = if (recaida) Cor.Vermelho else Cor.Lilas,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp,
        )
        Text(hhmm(agora), fontSize = 104.sp, fontWeight = FontWeight.Bold, lineHeight = 112.sp)
        if (sessao.label.isNotBlank()) {
            Text(sessao.label, fontSize = 22.sp, color = Cor.Suave, textAlign = TextAlign.Center)
        }
        Espaco(10)
        Text(
            if (recaida) "O alarme voltou. Cumpra a missão de novo para parar."
            else "O alarme só para quando a missão for cumprida.",
            fontSize = 17.sp,
            color = Cor.Suave,
            textAlign = TextAlign.Center,
        )
        Espaco(28)
        BotaoPrincipal("Começar missão", onClick = onComecar)
        if (!recaida && sonecasRestantes > 0) {
            Espaco(12)
            val minutos = if (sessao.test) "20 s" else "${config.snoozeMinutes} min"
            BotaoSecundario("Soneca de $minutos (restam $sonecasRestantes)", onClick = onSoneca)
        }
        if (sessao.test) {
            Espaco(18)
            Text(
                "Este é um teste: soneca e checagem levam 20 segundos e nada entra no histórico.",
                fontSize = 14.sp,
                color = Cor.Suave,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun TelaEspera(sessao: Session, agora: Long, onFechar: () -> Unit) {
    Pagina(centralizar = true) {
        Text("MISSÃO CUMPRIDA", color = Cor.Verde, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Espaco(8)
        Text("Não volte para a cama.", fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Espaco(20)
        Text("Checagem em", fontSize = 17.sp, color = Cor.Suave)
        Contagem(sessao.nextEventAt - agora, Cor.Texto)
        Espaco(8)
        Text(
            "Daqui a pouco o tablet pergunta se você continua de pé. " +
                "Sem resposta em 45 segundos, o alarme volta.",
            fontSize = 16.sp,
            color = Cor.Suave,
            textAlign = TextAlign.Center,
        )
        Espaco(28)
        BotaoSecundario("Fechar esta tela", onClick = onFechar)
    }
}

@Composable
private fun TelaChecagem(sessao: Session, agora: Long, onConfirmar: () -> Unit) {
    Pagina(centralizar = true) {
        Text("CHECAGEM", color = Cor.Lilas, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Espaco(8)
        Text("Você continua de pé?", fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Espaco(16)
        Contagem(sessao.nextEventAt - agora, Cor.Lilas)
        Text(
            "Confirme antes do tempo acabar ou o alarme volta com a missão inteira.",
            fontSize = 16.sp,
            color = Cor.Suave,
            textAlign = TextAlign.Center,
        )
        Espaco(28)
        BotaoPrincipal("Estou de pé", onClick = onConfirmar)
    }
}

@Composable
private fun TelaSoneca(sessao: Session, agora: Long, onFechar: () -> Unit) {
    Pagina(centralizar = true) {
        Text("SONECA", color = Cor.Suave, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Espaco(8)
        Text("O alarme volta em", fontSize = 20.sp, color = Cor.Suave)
        Contagem(sessao.nextEventAt - agora, Cor.Texto)
        Espaco(28)
        BotaoSecundario("Fechar esta tela", onClick = onFechar)
    }
}

@Composable
private fun TelaFim(
    resultado: HistoryEntry,
    claudeInstalado: Boolean,
    onFechar: () -> Unit,
    onAbrirClaude: () -> Unit,
) {
    val ok = resultado.outcome == Outcome.OK
    val abrirSozinho = ok && claudeInstalado && Repo.config.openClaude

    // O ritual continua: missão cumprida, o Claude abre em seguida.
    LaunchedEffect(resultado.finishedAt) {
        if (abrirSozinho) {
            delay(2500L)
            onAbrirClaude()
        }
    }

    Pagina(centralizar = true) {
        if (ok) {
            val minutos = ((resultado.upAt - resultado.startedAt).coerceAtLeast(0L) / 60_000L).toInt()
            Text("BOM DIA", color = Cor.Verde, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Espaco(8)
            Text("Você levantou.", fontSize = 36.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Espaco(14)
            Text(
                when {
                    minutos <= 0 -> "Missão cumprida em menos de um minuto."
                    minutos == 1 -> "Missão cumprida 1 minuto depois do alarme."
                    else -> "Missão cumprida $minutos minutos depois do alarme."
                },
                fontSize = 18.sp,
                color = Cor.Suave,
                textAlign = TextAlign.Center,
            )
            if (resultado.relapses > 0) {
                Text(
                    "Recaídas na checagem: ${resultado.relapses}",
                    fontSize = 16.sp,
                    color = Cor.Vermelho,
                )
            }
            if (!resultado.test) {
                Espaco(6)
                val seguidos = Repo.streak()
                Text(
                    if (seguidos == 1) "Sequência: 1 despertar" else "Sequência: $seguidos despertares",
                    fontSize = 18.sp,
                    color = Cor.Lilas,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        } else {
            Text("SEM RESPOSTA", color = Cor.Vermelho, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Espaco(8)
            Text(
                "O alarme tocou por 20 minutos e ninguém cumpriu a missão.",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Espaco(10)
            Text("Ficou registrado no histórico.", fontSize = 17.sp, color = Cor.Suave)
        }
        Espaco(28)
        if (ok && claudeInstalado) {
            BotaoPrincipal(if (abrirSozinho) "Abrindo o Claude…" else "Abrir o Claude", onClick = onAbrirClaude)
            Espaco(12)
        }
        BotaoSecundario("Fechar", onClick = onFechar)
    }
}
