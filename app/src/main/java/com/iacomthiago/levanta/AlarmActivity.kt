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
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * Tela do alarme. Abre por cima do bloqueio, liga a tela e não tem botão de
 * cancelar: o caminho para o silêncio é cumprir a missão.
 */
class AlarmActivity : ComponentActivity() {

    /** Verdadeiro entre o toque que para uma missão e a abertura da tela seguinte. */
    private var saindo by mutableStateOf(false)

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
                    saindo = saindo,
                    claudeInstalado = claudeInstalado,
                    onFechar = {
                        Repo.lastResult = null
                        finish()
                    },
                    onAbrirClaude = { abrirClaude() },
                    onMissao = { abrir -> atenderMissao(abrir) },
                )
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Um alarme novo chegou com esta tela ainda aberta: ela volta a mostrar o que toca.
        if (Repo.session != null) saindo = false
    }

    /**
     * Missões que não são a de levantar param com um toque. Com [abrir], o
     * toque já leva para a missão: conclui a simples, abre o treino no
     * navegador ou mostra os blocos de estudo.
     */
    private fun atenderMissao(abrir: Boolean) {
        val sessao = Repo.session ?: return
        if (sessao.type == Tipo.LEVANTAR) return
        val alarme = Repo.alarms.firstOrNull { it.id == sessao.alarmId }
        saindo = true
        Repo.dropSession()
        // Sem despertar em andamento, o serviço para o som e se encerra.
        AlarmService.send(this, AlarmService.ACTION_RESUME)
        if (alarme == null || !abrir) {
            finish()
            return
        }
        Lembrete.limpar(this, alarme.id)
        val telaDoApp = MainActivity.intentPara(this, alarme.id)
        when (alarme.type) {
            Tipo.SIMPLES -> {
                Jogo.concluir(alarme)
                desbloquearEAbrir(MainActivity.intentPara(this, -1), null)
            }

            Tipo.TREINO -> {
                Jogo.iniciarTreino(alarme.id)
                Nav.pedido = alarme.id
                val navegador = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(alarme.endereco),
                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                desbloquearEAbrir(navegador, telaDoApp)
            }

            else -> desbloquearEAbrir(telaDoApp, null)
        }
    }

    /** Abre [destino], pedindo o desbloqueio antes se o tablet estiver bloqueado. */
    private fun desbloquearEAbrir(destino: android.content.Intent, reserva: android.content.Intent?) {
        val abrir = {
            try {
                startActivity(destino)
            } catch (e: Exception) {
                // sem navegador para abrir o endereço: fica na tela da missão
                if (reserva != null) {
                    try {
                        startActivity(reserva)
                    } catch (e2: Exception) {
                        // nada a fazer
                    }
                }
            }
            finish()
        }
        val trava = getSystemService(KeyguardManager::class.java)
        if (trava != null && trava.isKeyguardLocked) {
            trava.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                override fun onDismissSucceeded() {
                    abrir()
                }

                override fun onDismissCancelled() {
                    finish()
                }

                override fun onDismissError() {
                    finish()
                }
            })
        } else {
            abrir()
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
private fun TelaDoAlarme(
    saindo: Boolean,
    claudeInstalado: Boolean,
    onFechar: () -> Unit,
    onAbrirClaude: () -> Unit,
    onMissao: (Boolean) -> Unit,
) {
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
        sessao == null && saindo -> Pagina(centralizar = true) {
            Text("Abrindo a missão…", fontSize = 22.sp, color = Cor.Suave, textAlign = TextAlign.Center)
        }

        sessao == null && resultado != null ->
            TelaFim(resultado, claudeInstalado, onFechar, onAbrirClaude)

        sessao == null -> {
            // Aberta sem alarme ativo (por exemplo, por uma notificação antiga).
            LaunchedEffect(Unit) { onFechar() }
        }

        sessao.type != Tipo.LEVANTAR ->
            if (Phase.isLoud(fase)) {
                TelaMissaoTocando(sessao, agora, onMissao)
            } else {
                // Missões não têm soneca nem checagem; qualquer outro estado é resto e sai de cena.
                LaunchedEffect(Unit) {
                    Repo.dropSession()
                    onFechar()
                }
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

/** Tela do alarme de uma missão de estudo, treino ou simples: para com um toque. */
@Composable
private fun TelaMissaoTocando(sessao: Session, agora: Long, onMissao: (Boolean) -> Unit) {
    val alarme = Repo.alarms.firstOrNull { it.id == sessao.alarmId }
    val botao: String
    val detalhe: String
    when (sessao.type) {
        Tipo.TREINO -> {
            botao = "Começar o treino agora"
            detalhe = "Abre o treino de ${alarme?.minutes ?: 30} minutos no navegador. Na volta, toque em Concluí."
        }

        Tipo.ESTUDO -> {
            val blocos = Jogo.blocosDe(hojeTexto()).size
            botao = "Abrir os blocos de estudo"
            detalhe = when {
                blocos == 0 -> "A cada $MINUTOS_DO_BLOCO minutos de estudo, marque um bloco."
                else -> "$blocos de ${Jogo.metaDeBlocos()} blocos marcados hoje."
            }
        }

        else -> {
            botao = "Concluir agora (+${Xp.SIMPLES} XP)"
            detalhe = "Um toque e está feita."
        }
    }

    Pagina(centralizar = true) {
        Text("HORA DA MISSÃO", color = Cor.Lilas, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
        Text(hhmm(agora), fontSize = 104.sp, fontWeight = FontWeight.Bold, lineHeight = 112.sp)
        Text(
            sessao.label.ifBlank { Tipo.nome(sessao.type) },
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Espaco(8)
        Text(detalhe, fontSize = 17.sp, color = Cor.Suave, textAlign = TextAlign.Center)
        Espaco(28)
        BotaoPrincipal(botao) { onMissao(true) }
        Espaco(12)
        BotaoSecundario("Parar o som e fazer depois") { onMissao(false) }
        if (sessao.test) {
            Espaco(18)
            Text(
                "Este é um teste do alarme desta missão.",
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
            Image(
                painter = painterResource(R.drawable.carro),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(20.dp)),
            )
            Espaco(18)
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
                val dias = Jogo.sequenciaAtual()
                Text(
                    "+${Xp.LEVANTAR} XP · Nível ${Jogo.nivel}",
                    fontSize = 18.sp,
                    color = Cor.Lilas,
                    fontWeight = FontWeight.SemiBold,
                )
                if (dias > 0) {
                    Text(
                        if (dias == 1) "Sequência: 1 dia" else "Sequência: $dias dias",
                        fontSize = 18.sp,
                        color = Cor.Lilas,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            val prova = fraseDaProva(Repo.config.examDate)
            if (prova != null) {
                Espaco(6)
                Text(prova, fontSize = 18.sp, color = Cor.Ouro, fontWeight = FontWeight.SemiBold)
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
