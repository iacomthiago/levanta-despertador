package com.iacomthiago.levanta

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** Pedidos de navegação vindos de fora da tela principal (o alarme de uma missão ou um lembrete). */
object Nav {
    /** Missão a abrir; um número que não é de nenhuma missão leva para a tela inicial. */
    var pedido by mutableStateOf<Int?>(null)
}

/** Cada faixa de nível tem a sua cor, tirada da paleta do app. */
fun corDoRank(indice: Int): Color = when (indice) {
    0 -> Cor.Suave
    1 -> Cor.Lilas
    2 -> Cor.Verde
    3 -> Cor.Ouro
    4 -> Cor.Vermelho
    else -> Cor.Texto
}

fun abrirEndereco(context: Context, endereco: String): Boolean = try {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(endereco)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (e: Exception) {
    false
}

// ---------- peças da tela inicial ----------

/** Aviso do último ganho de experiência. Some sozinho ou com um toque. */
@Composable
fun CartaoGanho() {
    val g = Jogo.ganho ?: return
    LaunchedEffect(g.em) {
        delay(9_000L)
        if (Jogo.ganho?.em == g.em) Jogo.ganho = null
    }
    Cartao(
        cor = Cor.CartaoAlto,
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable { Jogo.ganho = null },
    ) {
        if (g.xp > 0) {
            Text("+${g.xp} XP", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Cor.Verde)
        }
        for (linha in g.linhas) {
            Text(linha, fontSize = 15.sp, color = Cor.Texto)
        }
        if (g.nivel > 0) {
            Espaco(4)
            Text(
                "Você subiu para o nível ${g.nivel}: ${RANKS[indiceDoRank(g.nivel)].nome}.",
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                color = Cor.Ouro,
            )
        }
    }
    Espaco(12)
}

/** A imagem do personagem: a escolhida na galeria ou a que vem no app. A borda tem a cor da faixa de nível. */
@Composable
fun ImagemDoPersonagem() {
    val carimbo = Jogo.imagem
    val escolhida = remember(carimbo) {
        if (carimbo == 0L) {
            null
        } else {
            try {
                BitmapFactory.decodeFile(Jogo.arquivoDaImagem().path)?.asImageBitmap()
            } catch (e: Throwable) {
                null
            }
        }
    }
    val forma = RoundedCornerShape(20.dp)
    val borda = corDoRank(indiceDoRank(Jogo.nivel))
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (escolhida != null && escolhida.width > 0 && escolhida.height > 0) {
            val proporcao = (escolhida.width.toFloat() / escolhida.height.toFloat()).coerceIn(0.6f, 1.78f)
            Image(
                bitmap = escolhida,
                contentDescription = "Seu personagem",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    // Imagem em pé ocupa só parte da largura, para não tomar a tela toda.
                    .fillMaxWidth(if (proporcao < 1f) 0.55f else 1f)
                    .aspectRatio(proporcao)
                    .clip(forma)
                    .border(2.dp, borda, forma),
            )
        } else {
            Image(
                painter = painterResource(R.drawable.carro),
                contentDescription = "Seu personagem",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(forma)
                    .border(2.dp, borda, forma),
            )
        }
    }
}

@Composable
fun Barra(fracao: Float, cor: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(12.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(Cor.Linha),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fracao.coerceIn(0f, 1f))
                .height(12.dp)
                .background(cor),
        )
    }
}

/** Personagem, nível, barra de experiência e sequência de dias. */
@Composable
fun CartaoHeroi() {
    val nivel = Jogo.nivel
    val indice = indiceDoRank(nivel)
    val cor = corDoRank(indice)
    val base = xpDoNivel(nivel)
    val total = (xpDoNivel(nivel + 1) - base).coerceAtLeast(1)
    val noNivel = (Jogo.xp - base).coerceAtLeast(0)
    val dias = Jogo.sequenciaAtual()
    val pendentes = Jogo.pendentesQueContam()

    Cartao(cor = Cor.CartaoAlto) {
        ImagemDoPersonagem()
        Espaco(14)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Nível $nivel", fontSize = 34.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text(
                RANKS[indice].nome.uppercase(Locale.getDefault()),
                color = cor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            )
        }
        Espaco(8)
        Barra(noNivel.toFloat() / total.toFloat(), cor)
        Espaco(6)
        Row {
            Text("$noNivel / $total XP", fontSize = 14.sp, color = Cor.Suave, modifier = Modifier.weight(1f))
            Text("Total: ${Jogo.xp} XP", fontSize = 14.sp, color = Cor.Suave)
        }
        Espaco(14)
        Row {
            Numero(dias.toString(), if (dias == 1) "dia de sequência" else "dias de sequência", Modifier.weight(1f))
            Numero(maxOf(Jogo.recorde, dias).toString(), "recorde", Modifier.weight(1f))
        }
        Espaco(10)
        when {
            pendentes.isNotEmpty() -> Text(
                "Para o dia contar, falta: " + pendentes.joinToString(", ") { it.nome } + ".",
                fontSize = 15.sp,
                color = Cor.Ouro,
            )

            Jogo.hojeCompleto() -> Text("Hoje está completo.", fontSize = 15.sp, color = Cor.Verde)
        }
        val prova = fraseDaProva(Repo.config.examDate)
        if (prova != null) {
            Espaco(6)
            Text(prova, color = Cor.Ouro, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

class Situacao(val texto: String, val cor: Color, val feita: Boolean = false)

/** Em que pé está a missão hoje. */
fun situacao(alarme: Alarm, agora: Long): Situacao {
    val hoje = hojeTexto()
    if (Jogo.feita(alarme, hoje)) return Situacao("Concluída", Cor.Verde, true)
    val relogio = Instant.ofEpochMilli(agora).atZone(ZoneId.systemDefault()).toLocalTime()
    val passou = relogio.hour * 60 + relogio.minute >= alarme.hour * 60 + alarme.minute
    return when (alarme.type) {
        Tipo.ESTUDO -> {
            val metas = Jogo.metasDe(hoje)
            if (metas.isEmpty()) {
                Situacao("Escreva as metas de hoje", Cor.Ouro)
            } else {
                Situacao("${metas.count { it.feitaEm != 0L }} de ${metas.size} metas", Cor.Lilas)
            }
        }

        Tipo.TREINO -> {
            val inicio = Jogo.inicioDeHoje(alarme.id)
            when {
                inicio != null -> Situacao("Em andamento desde ${hhmm(inicio)}", Cor.Lilas)
                passou -> Situacao("Atrasada", Cor.Ouro)
                else -> Situacao("Pendente", Cor.Suave)
            }
        }

        Tipo.SIMPLES -> if (passou) Situacao("Atrasada", Cor.Ouro) else Situacao("Pendente", Cor.Suave)

        else -> when {
            Repo.session?.alarmId == alarme.id -> Situacao("Em andamento", Cor.Lilas)
            passou -> Situacao("Não cumprida", Cor.Vermelho)
            else -> Situacao("Pendente", Cor.Suave)
        }
    }
}

/** Uma missão de hoje, com a situação. Tocar abre a missão; a simples conclui ali mesmo. */
@Composable
fun LinhaMissaoDeHoje(alarme: Alarm, agora: Long, onAbrir: () -> Unit) {
    val s = situacao(alarme, agora)
    Cartao(modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onAbrir)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.width(96.dp)) {
                Text(
                    if (alarme.toca) hhmm(alarme.hour, alarme.minute) else "Livre",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (s.feita) Cor.Suave else Cor.Texto,
                )
                Text(
                    Tipo.nome(alarme.type).uppercase(Locale.getDefault()),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                    color = Cor.Suave,
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(alarme.nome, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                Text(s.texto, fontSize = 15.sp, color = s.cor)
            }
            Spacer(Modifier.width(8.dp))
            when {
                s.feita -> Text("Feita", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Cor.Verde)

                alarme.type == Tipo.SIMPLES -> Button(
                    onClick = { Jogo.concluir(alarme) },
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cor.Roxo, contentColor = Cor.SobreRoxo),
                ) {
                    Text("Concluir", fontWeight = FontWeight.SemiBold)
                }

                alarme.type == Tipo.ESTUDO -> Text("+${Xp.META} por meta", fontSize = 14.sp, color = Cor.Suave)

                else -> Text("+${Xp.daMissao(alarme.type)} XP", fontSize = 14.sp, color = Cor.Suave)
            }
        }
    }
}

fun tituloDoDesafio(s: Semana): String {
    val dias = if (s.alvo == 1) "1 dia" else "${s.alvo} dias"
    return when (s.desafio) {
        Tipo.TREINO -> "Treinar em $dias"
        Tipo.ESTUDO -> "Fechar todas as metas de estudo em $dias"
        else -> "Cumprir a missão de levantar em $dias"
    }
}

@Composable
fun CartaoSemana() {
    val s = Jogo.semana ?: return
    val feito = Jogo.progressoDaSemana(s).coerceAtMost(s.alvo)
    Cartao {
        Text(
            "MISSÃO DA SEMANA",
            color = Cor.Suave,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
        Espaco(4)
        Text(tituloDoDesafio(s), fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Espaco(10)
        Barra(feito.toFloat() / s.alvo.coerceAtLeast(1).toFloat(), Cor.Ouro)
        Espaco(6)
        Text(
            if (s.premiada) "Cumprida. Você ganhou +${Xp.SEMANA} XP."
            else "$feito de ${s.alvo} · vale +${Xp.SEMANA} XP · até domingo",
            fontSize = 15.sp,
            color = if (s.premiada) Cor.Verde else Cor.Suave,
        )
    }
}

// ---------- missão de treino e missão simples ----------

@Composable
fun TelaMissaoTreino(alarme: Alarm, onVoltar: () -> Unit, onEditar: () -> Unit) {
    val context = LocalContext.current
    val agora by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1000L)
        }
    }
    var semNavegador by remember { mutableStateOf(false) }
    val feita = Jogo.feita(alarme)
    val inicio = Jogo.inicioDeHoje(alarme.id)
    val libera = Jogo.liberaEm(alarme)
    val liberado = libera == null || agora >= libera

    Pagina {
        Cabecalho(alarme.nome, onVoltar)
        CartaoGanho()
        Cartao(cor = Cor.CartaoAlto) {
            Text(
                "TREINO · ${alarme.minutes} MIN",
                color = Cor.Suave,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Espaco(6)
            when {
                feita -> Text("Treino concluído hoje.", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Cor.Verde)

                inicio == null -> {
                    Text("Pronto para começar?", fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Espaco(4)
                    Text(
                        "O treino abre no navegador. Na volta, toque em Concluí.",
                        fontSize = 16.sp,
                        color = Cor.Suave,
                    )
                }

                else -> {
                    Text("Em andamento desde ${hhmm(inicio ?: agora)}", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Espaco(4)
                    Text(
                        if (liberado) "Já pode concluir."
                        else "O botão Concluí libera às ${hhmm(libera ?: agora)} (faltam ${contagem((libera ?: agora) - agora)}).",
                        fontSize = 16.sp,
                        color = if (liberado) Cor.Verde else Cor.Suave,
                    )
                }
            }
        }
        Espaco(16)
        if (!feita) {
            if (inicio == null) {
                BotaoPrincipal("Começar treino") {
                    Jogo.iniciarTreino(alarme.id)
                    semNavegador = !abrirEndereco(context, alarme.endereco)
                }
            } else {
                BotaoPrincipal("Concluí (+${Xp.TREINO} XP)", habilitado = liberado) { Jogo.concluir(alarme) }
                Espaco(10)
                BotaoSecundario("Abrir o treino de novo") {
                    semNavegador = !abrirEndereco(context, alarme.endereco)
                }
            }
            if (semNavegador) {
                Espaco(8)
                Text("Não consegui abrir o navegador deste tablet.", fontSize = 15.sp, color = Cor.Ouro)
            }
            Espaco(10)
        }
        BotaoSecundario("Editar esta missão", onClick = onEditar)
        Espaco(20)
    }
}

@Composable
fun TelaMissaoSimples(alarme: Alarm, onVoltar: () -> Unit, onEditar: () -> Unit) {
    val feita = Jogo.feita(alarme)
    Pagina {
        Cabecalho(alarme.nome, onVoltar)
        CartaoGanho()
        Cartao(cor = Cor.CartaoAlto) {
            Text(
                "MISSÃO SIMPLES · ${hhmm(alarme.hour, alarme.minute)}",
                color = Cor.Suave,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Espaco(6)
            Text(
                if (feita) "Concluída hoje." else alarme.nome,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = if (feita) Cor.Verde else Cor.Texto,
            )
        }
        Espaco(16)
        if (!feita) {
            BotaoPrincipal("Concluir (+${Xp.SIMPLES} XP)") { Jogo.concluir(alarme) }
            Espaco(10)
        }
        BotaoSecundario("Editar esta missão", onClick = onEditar)
        Espaco(20)
    }
}

// ---------- ditado ----------

/** Estado do reconhecimento de fala usado para ditar metas, resumos e ideias. */
class Ditado(
    val disponivel: Boolean,
    val semPermissao: Boolean,
    val ouvindo: Boolean,
    val parcial: String,
    val aviso: String,
    val iniciar: () -> Unit,
    val parar: () -> Unit,
    val pedirPermissao: () -> Unit,
)

/**
 * Reconhecimento de fala do próprio Android, o mesmo da missão de falar a
 * afirmação. Cada trecho reconhecido é entregue em [onTexto]; quem chama
 * decide onde colocar. Se falhar, o campo de texto continua lá para digitar.
 */
@Composable
fun rememberDitado(onTexto: (String) -> Unit): Ditado {
    val context = LocalContext.current
    val entregar by rememberUpdatedState(onTexto)
    var microfone by remember { mutableStateOf(microfoneLiberado(context)) }
    val pedir = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        microfone = ok
    }
    var ouvindo by remember { mutableStateOf(false) }
    var parcial by remember { mutableStateOf("") }
    var aviso by remember { mutableStateOf("") }
    var tentativa by remember { mutableIntStateOf(0) }

    val reconhecedor = remember(microfone) {
        try {
            if (vozDisponivel(context)) SpeechRecognizer.createSpeechRecognizer(context) else null
        } catch (e: Exception) {
            null
        }
    }

    DisposableEffect(reconhecedor) {
        reconhecedor?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (!ouvindo) return
                val trecho = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty()
                if (trecho.isNotBlank()) parcial = trecho
            }

            override fun onError(error: Int) {
                if (!ouvindo) return
                ouvindo = false
                parcial = ""
                aviso = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Não ouvi nada. Toque e fale de novo, ou digite."

                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER ->
                        "O reconhecimento de voz não respondeu. Verifique a internet ou digite."

                    else -> "O reconhecimento de voz falhou. Você pode digitar."
                }
            }

            override fun onResults(results: Bundle?) {
                if (!ouvindo) return
                ouvindo = false
                val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull().orEmpty().trim().ifEmpty { parcial.trim() }
                parcial = ""
                if (texto.isEmpty()) {
                    aviso = "Não entendi. Toque e fale de novo, ou digite."
                } else {
                    entregar(texto.replaceFirstChar { it.uppercase() })
                }
            }
        })
        onDispose {
            try {
                reconhecedor?.destroy()
            } catch (e: Exception) {
                // nada a fazer
            }
        }
    }

    // Se o serviço de voz travar, a tela não fica presa em "Ouvindo…".
    LaunchedEffect(tentativa) {
        if (tentativa > 0) {
            delay(120_000L)
            if (ouvindo) {
                ouvindo = false
                parcial = ""
                aviso = "O reconhecimento de voz parou de responder. Você pode digitar."
                try {
                    reconhecedor?.cancel()
                } catch (e: Exception) {
                    // nada a fazer
                }
            }
        }
    }

    val iniciar: () -> Unit = {
        if (reconhecedor != null && !ouvindo) {
            aviso = ""
            parcial = ""
            try {
                reconhecedor.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                        .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                        // Pausas para pensar no meio do parágrafo não encerram a escuta tão cedo.
                        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
                        .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3500L)
                )
                ouvindo = true
                tentativa += 1
            } catch (e: Exception) {
                aviso = "O reconhecimento de voz falhou. Você pode digitar."
            }
        }
    }
    val parar: () -> Unit = {
        try {
            reconhecedor?.stopListening()
        } catch (e: Exception) {
            // nada a fazer
        }
    }

    return Ditado(
        disponivel = reconhecedor != null,
        semPermissao = !microfone,
        ouvindo = ouvindo,
        parcial = parcial,
        aviso = aviso,
        iniciar = iniciar,
        parar = parar,
        pedirPermissao = { pedir.launch(Manifest.permission.RECORD_AUDIO) },
    )
}

/** Botão de falar com os avisos do ditado. [principal] deixa o botão em destaque. */
@Composable
fun BotaoDeFalar(ditado: Ditado, rotulo: String, principal: Boolean = false) {
    when {
        ditado.disponivel -> {
            val texto = if (ditado.ouvindo) "Ouvindo… toque para parar" else rotulo
            val acao = { if (ditado.ouvindo) ditado.parar() else ditado.iniciar() }
            if (principal) BotaoPrincipal(texto, onClick = acao) else BotaoSecundario(texto, onClick = acao)
        }

        ditado.semPermissao -> BotaoSecundario("Liberar o microfone para falar") { ditado.pedirPermissao() }

        else -> Text(
            "Este tablet está sem reconhecimento de voz agora. Digite no campo.",
            fontSize = 14.sp,
            color = Cor.Ouro,
        )
    }
    if (ditado.parcial.isNotBlank()) {
        Text(ditado.parcial, fontSize = 16.sp, color = Cor.Lilas, modifier = Modifier.padding(top = 8.dp))
    }
    if (ditado.aviso.isNotBlank()) {
        Text(ditado.aviso, fontSize = 14.sp, color = Cor.Ouro, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---------- estudo ----------

@Composable
private fun LinhaMeta(meta: Meta, concluivel: Boolean, onResumir: (Long) -> Unit) {
    val feita = meta.feitaEm != 0L
    Cartao {
        Text(meta.texto, fontSize = 18.sp, lineHeight = 24.sp, color = if (feita) Cor.Suave else Cor.Texto)
        if (feita) {
            Text("Concluída às ${hhmm(meta.feitaEm)}", fontSize = 14.sp, color = Cor.Verde)
        } else {
            Espaco(8)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (concluivel) {
                    Button(
                        onClick = { onResumir(meta.id) },
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Cor.Roxo, contentColor = Cor.SobreRoxo),
                    ) {
                        Text("Concluir meta", fontWeight = FontWeight.SemiBold)
                    }
                }
                TextButton(onClick = { Jogo.excluirMeta(meta.id) }) {
                    Text("Excluir", color = Cor.Suave)
                }
            }
        }
    }
    Espaco(8)
}

/** Metas de estudo de hoje e de amanhã. Uma meta por linha; pode colar várias de uma vez. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TelaEstudo(onVoltar: () -> Unit, onResumir: (Long) -> Unit, onDiario: () -> Unit, onIdeias: () -> Unit) {
    val agora by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val hoje = remember(agora) { hojeTexto() }
    val amanha = remember(agora) { LocalDate.now().plusDays(1).toString() }
    val hora = remember(agora) { Instant.ofEpochMilli(agora).atZone(ZoneId.systemDefault()).hour }
    val metas = Jogo.metas
    val deHoje = metas.filter { it.dia == hoje }
    val deAmanha = metas.filter { it.dia == amanha }
    val atrasadas = metas.filter { it.feitaEm == 0L && it.dia < hoje }
    val feitas = deHoje.count { it.feitaEm != 0L }
    val limite = Repo.config.studyBonusHour.coerceIn(1, 23)
    val fechado = Jogo.feitos.any { it.dia == hoje && it.tipo == Tipo.ESTUDO }
    var texto by remember { mutableStateOf("") }
    var paraAmanha by remember { mutableStateOf(false) }
    val ditado = rememberDitado { falado ->
        texto = if (texto.isBlank()) falado else texto.trimEnd() + "\n" + falado
    }

    Pagina {
        Cabecalho("Estudo", onVoltar)
        CartaoGanho()
        Cartao(cor = Cor.CartaoAlto) {
            Text(
                "METAS DE HOJE",
                color = Cor.Suave,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Text(
                if (deHoje.isEmpty()) "Nenhuma ainda" else "$feitas de ${deHoje.size}",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = if (fechado) Cor.Verde else Cor.Lilas,
            )
            Text(
                when {
                    fechado -> "Dia de estudo fechado."
                    deHoje.isEmpty() -> "Escreva as metas e conclua todas até as $limite h para ganhar +${Xp.BONUS_ESTUDO} XP de bônus."
                    hora < limite -> "Feche todas até as $limite h e ganhe +${Xp.BONUS_ESTUDO} XP de bônus."
                    else -> "Passou das $limite h: o bônus de hoje ficou para trás, mas cada meta ainda vale +${Xp.META} XP."
                },
                fontSize = 15.sp,
                color = Cor.Suave,
            )
        }

        if (deHoje.isNotEmpty()) {
            TituloSecao("Hoje")
            for (meta in deHoje) {
                LinhaMeta(meta, concluivel = true, onResumir = onResumir)
            }
        }

        if (atrasadas.isNotEmpty()) {
            Espaco(8)
            Cartao {
                Text(
                    if (atrasadas.size == 1) "1 meta de dias anteriores ficou sem concluir."
                    else "${atrasadas.size} metas de dias anteriores ficaram sem concluir.",
                    fontSize = 16.sp,
                    color = Cor.Ouro,
                )
                Espaco(10)
                BotaoSecundario("Trazer para hoje") { Jogo.trazerAtrasadasParaHoje() }
                Espaco(8)
                BotaoSecundario("Descartar as antigas") { Jogo.descartarAtrasadas() }
            }
        }

        TituloSecao("Adicionar metas")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !paraAmanha,
                onClick = { paraAmanha = false },
                label = { Text("Para hoje", fontSize = 15.sp) },
            )
            FilterChip(
                selected = paraAmanha,
                onClick = { paraAmanha = true },
                label = { Text("Para amanhã", fontSize = 15.sp) },
            )
        }
        Espaco(6)
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it.take(4000) },
            label = { Text("Uma meta por linha") },
            placeholder = { Text("Pode colar várias de uma vez.") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Espaco(10)
        BotaoPrincipal("Adicionar metas", habilitado = texto.isNotBlank()) {
            Jogo.adicionarMetas(texto, if (paraAmanha) amanha else hoje)
            texto = ""
        }
        Espaco(10)
        BotaoDeFalar(ditado, "Ditar uma meta")

        if (deAmanha.isNotEmpty()) {
            TituloSecao("Amanhã")
            for (meta in deAmanha) {
                LinhaMeta(meta, concluivel = false, onResumir = onResumir)
            }
        }

        TituloSecao("Mais")
        BotaoSecundario("Diário de estudo", onClick = onDiario)
        Espaco(10)
        BotaoSecundario("Anotar uma ideia para depois", onClick = onIdeias)
        Espaco(20)
    }
}

/**
 * Conclui uma meta: a pessoa fala (ou digita) um parágrafo curto sobre o que
 * estudou. O app só conta as palavras; não julga o conteúdo.
 */
@Composable
fun TelaResumo(metaId: Long, onVoltar: () -> Unit) {
    val meta = Jogo.metas.firstOrNull { it.id == metaId }
    if (meta == null || meta.feitaEm != 0L) {
        LaunchedEffect(Unit) { onVoltar() }
        return
    }
    var texto by remember { mutableStateOf("") }
    val minimo = Repo.config.minWords.coerceIn(1, 200)
    val palavras = contarPalavras(texto)
    val ditado = rememberDitado { falado -> texto = (texto.trimEnd() + " " + falado).trim() }

    Pagina {
        Cabecalho("Concluir meta", onVoltar)
        Cartao(cor = Cor.CartaoAlto) {
            Text(
                "META",
                color = Cor.Suave,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Text(meta.texto, fontSize = 22.sp, lineHeight = 29.sp, fontWeight = FontWeight.Bold)
        }
        Espaco(14)
        Text(
            "Fale um parágrafo curto: o que você estudou, o que aprendeu ou que dúvida foi resolvida.",
            fontSize = 16.sp,
            lineHeight = 22.sp,
            color = Cor.Suave,
        )
        Espaco(12)
        BotaoDeFalar(
            ditado,
            if (texto.isBlank()) "Falar agora" else "Falar mais",
            principal = palavras < minimo,
        )
        // O contador fica acima do campo para continuar visível com o teclado aberto.
        Text(
            "$palavras de $minimo palavras",
            fontSize = 14.sp,
            color = if (palavras >= minimo) Cor.Verde else Cor.Suave,
            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp),
        )
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it.take(4000) },
            label = { Text("Seu resumo") },
            minLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        Espaco(16)
        BotaoPrincipal("Guardar e concluir (+${Xp.META} XP)", habilitado = palavras >= minimo) {
            Jogo.concluirMeta(meta.id, texto)
        }
        Espaco(20)
    }
}

/** O diário de estudo: data, meta e o resumo que concluiu cada uma. */
@Composable
fun TelaDiario(onVoltar: () -> Unit) {
    val context = LocalContext.current
    val entradas = remember(Jogo.metas) { Jogo.diario() }
    var copiado by remember { mutableStateOf(false) }

    Pagina {
        Cabecalho("Diário de estudo", onVoltar)
        if (entradas.isEmpty()) {
            Text(
                "Cada meta de estudo concluída entra aqui com o resumo que você falou.",
                color = Cor.Suave,
                fontSize = 16.sp,
                modifier = Modifier.padding(4.dp),
            )
        } else {
            BotaoSecundario(if (copiado) "Diário copiado" else "Copiar o diário inteiro") {
                val tudo = entradas.joinToString("\n\n") {
                    "${diaCurto(it.feitaEm)} ${hhmm(it.feitaEm)}\nMeta: ${it.texto}\n${it.resumo}"
                }
                try {
                    val area = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    area.setPrimaryClip(ClipData.newPlainText("Diário de estudo", tudo))
                    copiado = true
                } catch (e: Exception) {
                    // texto grande demais para a área de transferência deste aparelho
                }
            }
        }
        var ultimoDia = ""
        for (meta in entradas.take(300)) {
            if (meta.dia != ultimoDia) {
                ultimoDia = meta.dia
                TituloSecao(diaCurto(meta.feitaEm))
            }
            Cartao {
                Text(meta.texto, fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold)
                Text("Concluída às ${hhmm(meta.feitaEm)}", fontSize = 13.sp, color = Cor.Suave)
                if (meta.resumo.isNotBlank()) {
                    Espaco(6)
                    Text(meta.resumo, fontSize = 16.sp, lineHeight = 23.sp)
                }
            }
            Espaco(8)
        }
        Espaco(20)
    }
}

// ---------- ideias para depois ----------

/** Lugar para largar uma ideia que apareceu no meio de uma missão e voltar ao que estava fazendo. */
@Composable
fun TelaIdeias(onVoltar: () -> Unit) {
    var texto by remember { mutableStateOf("") }
    val ditado = rememberDitado { falado -> texto = (texto.trimEnd() + " " + falado).trim() }

    Pagina {
        Cabecalho("Ideias para depois", onVoltar)
        Text(
            "Teve uma ideia no meio de uma missão? Anote aqui e volte para o que estava fazendo. Ela não se perde.",
            fontSize = 16.sp,
            lineHeight = 22.sp,
            color = Cor.Suave,
        )
        Espaco(12)
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it.take(500) },
            label = { Text("A ideia, em poucas palavras") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Espaco(10)
        BotaoPrincipal("Guardar ideia", habilitado = texto.isNotBlank()) {
            Jogo.anotarIdeia(texto)
            texto = ""
        }
        Espaco(10)
        BotaoDeFalar(ditado, "Falar a ideia")

        if (Jogo.ideias.isNotEmpty()) {
            TituloSecao("Guardadas")
        }
        for (ideia in Jogo.ideias) {
            Cartao {
                Text(ideia.texto, fontSize = 17.sp, lineHeight = 23.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${diaCurto(ideia.em)} às ${hhmm(ideia.em)}",
                        fontSize = 13.sp,
                        color = Cor.Suave,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = { Jogo.excluirIdeia(ideia.id) }) {
                        Text("Apagar", color = Cor.Suave)
                    }
                }
            }
            Espaco(8)
        }
        Espaco(20)
    }
}
