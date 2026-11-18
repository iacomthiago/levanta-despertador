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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    if (Jogo.feita(alarme, hoje)) {
        val blocos = Jogo.blocosDe(hoje).size
        val texto = if (alarme.type == Tipo.ESTUDO && blocos > 0) "Meta batida · ${tempoDeBlocos(blocos)}" else "Concluída"
        return Situacao(texto, Cor.Verde, true)
    }
    val relogio = Instant.ofEpochMilli(agora).atZone(ZoneId.systemDefault()).toLocalTime()
    val passou = relogio.hour * 60 + relogio.minute >= alarme.hour * 60 + alarme.minute
    return when (alarme.type) {
        Tipo.ESTUDO -> {
            val blocos = Jogo.blocosDe(hoje).size
            if (blocos == 0) {
                Situacao("Marque os blocos de 30 minutos", Cor.Ouro)
            } else {
                Situacao("$blocos de ${Jogo.metaDeBlocos()} blocos", Cor.Lilas)
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
            // Criada hoje depois da própria hora: o primeiro toque é só amanhã.
            passou && !Jogo.vale(alarme.copy(counts = true), LocalDate.now()) -> Situacao("Começa amanhã", Cor.Suave)
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

                alarme.type == Tipo.ESTUDO -> Text("+${Xp.BLOCO} por bloco", fontSize = 14.sp, color = Cor.Suave)

                else -> Text("+${Xp.daMissao(alarme.type)} XP", fontSize = 14.sp, color = Cor.Suave)
            }
        }
    }
}

fun tituloDoDesafio(s: Semana): String {
    val dias = if (s.alvo == 1) "1 dia" else "${s.alvo} dias"
    return when (s.desafio) {
        Tipo.TREINO -> "Treinar em $dias"
        Tipo.ESTUDO -> "Bater a meta de estudo em $dias"
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

/** Um bloco de estudo de 30 minutos: tocar marca como feito; tocar de novo desmarca. */
@Composable
private fun CaixaDeBloco(numero: Int, feito: Boolean, modifier: Modifier = Modifier) {
    val forma = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .height(76.dp)
            .clip(forma)
            .background(if (feito) Cor.Roxo else Cor.CartaoAlto)
            .border(1.dp, if (feito) Cor.Lilas else Cor.Linha, forma)
            .clickable { Jogo.alternarBloco(numero) }
            .semantics { contentDescription = if (feito) "Bloco $numero feito" else "Bloco $numero" },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                numero.toString(),
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                color = if (feito) Cor.SobreRoxo else Cor.Texto,
            )
            Text(
                if (feito) "Feito" else "$MINUTOS_DO_BLOCO min",
                fontSize = 12.sp,
                color = if (feito) Cor.SobreRoxo else Cor.Suave,
            )
        }
    }
}

/** O estudo do dia em blocos de 30 minutos: estudou um bloco, toca nele. Nada para escrever. */
@Composable
fun TelaEstudo(onVoltar: () -> Unit, onDiario: () -> Unit, onIdeias: () -> Unit) {
    val agora by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(30_000L)
        }
    }
    val hoje = remember(agora) { hojeTexto() }
    val hora = remember(agora) { Instant.ofEpochMilli(agora).atZone(ZoneId.systemDefault()).hour }
    val marcados = Jogo.blocosDe(hoje)
    val alvo = Jogo.metaDeBlocos()
    val limite = Repo.config.studyBonusHour.coerceIn(1, 23)
    val fechado = Jogo.feitos.any { it.dia == hoje && it.tipo == Tipo.ESTUDO }

    Pagina {
        Cabecalho("Estudo", onVoltar)
        CartaoGanho()
        Cartao(cor = Cor.CartaoAlto) {
            Text(
                "ESTUDO DE HOJE",
                color = Cor.Suave,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Text(
                if (marcados.isEmpty()) "Nenhum bloco ainda" else tempoDeBlocos(marcados.size),
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                color = if (fechado) Cor.Verde else Cor.Lilas,
            )
            Text(
                "${marcados.size} de $alvo blocos para o dia contar",
                fontSize = 16.sp,
                color = if (fechado) Cor.Verde else Cor.Texto,
            )
            Espaco(8)
            Barra((marcados.size.toFloat() / alvo.toFloat()).coerceIn(0f, 1f), if (fechado) Cor.Verde else Cor.Lilas)
            Espaco(8)
            Text(
                when {
                    fechado -> "Meta de hoje batida. Cada bloco a mais continua valendo +${Xp.BLOCO} XP."
                    hora < limite -> "Chegue a $alvo blocos até as $limite h e ganhe +${Xp.BONUS_ESTUDO} XP de bônus."
                    else -> "Passou das $limite h: o bônus de hoje ficou para trás, mas cada bloco ainda vale +${Xp.BLOCO} XP."
                },
                fontSize = 15.sp,
                color = Cor.Suave,
            )
        }

        TituloSecao("Blocos de $MINUTOS_DO_BLOCO minutos")
        Text(
            "Estudou $MINUTOS_DO_BLOCO minutos? Toque em um bloco. Tocar de novo desmarca.",
            fontSize = 15.sp,
            color = Cor.Suave,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Espaco(10)
        for (linha in (1..BLOCOS_POR_DIA).chunked(7)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (numero in linha) {
                    CaixaDeBloco(numero, numero in marcados, Modifier.weight(1f))
                }
            }
            Espaco(8)
        }

        TituloSecao("Mais")
        BotaoSecundario("Anotar uma ideia para depois", onClick = onIdeias)
        if (Jogo.metas.any { it.feitaEm != 0L }) {
            Espaco(10)
            BotaoSecundario("Diário das metas antigas", onClick = onDiario)
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
                "Aqui ficam os resumos das metas escritas no jeito antigo. Não há nenhum guardado.",
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

// ---------- recompensas ----------

/** Quanto falta de experiência para chegar ao [nivel]. */
private fun faltaPara(nivel: Int): Int = (xpDoNivel(nivel) - Jogo.xp).coerceAtLeast(0)

private fun textoDosTurbos(n: Int): String = if (n == 1) "1 turbo guardado" else "$n turbos guardados"

/** Resumo das recompensas na tela inicial. Tocar abre a tela de recompensas. */
@Composable
fun CartaoRecompensas(onAbrir: () -> Unit) {
    val marco = Jogo.proximoMarco()
    val premio = Jogo.premioDoNivel(marco)
    Cartao(modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onAbrir)) {
        Text(
            "RECOMPENSAS",
            color = Cor.Suave,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.2.sp,
        )
        Espaco(4)
        Text("Próxima no nível $marco", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(
            "Faltam ${faltaPara(marco)} XP · +1 turbo" + if (premio.isEmpty()) "" else " e $premio",
            fontSize = 15.sp,
            color = Cor.Suave,
        )
        Espaco(6)
        if (Jogo.turboLigado()) {
            Text(
                "Turbo ligado hoje: tudo vale o dobro.",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Cor.Ouro,
            )
        } else {
            Text(
                textoDosTurbos(Jogo.turbos),
                fontSize = 15.sp,
                color = if (Jogo.turbos > 0) Cor.Ouro else Cor.Suave,
            )
        }
        if (premio.isEmpty()) {
            Text("Toque para escolher os seus prêmios.", fontSize = 14.sp, color = Cor.Lilas)
        }
    }
}

/** Turbos para usar e os prêmios da vida real combinados para cada nível de recompensa. */
@Composable
fun TelaRecompensas(onVoltar: () -> Unit) {
    var texto by remember { mutableStateOf(Jogo.premios) }
    val ditado = rememberDitado { falado ->
        texto = if (texto.isBlank()) falado else texto.trimEnd() + "\n" + falado
    }
    val nivel = Jogo.nivel
    val combinados = Jogo.premios.split('\n').count { it.isNotBlank() }
    val ultimo = maxOf(Jogo.proximoMarco(), combinados * NIVEIS_POR_RECOMPENSA)
    val marcos = (NIVEIS_POR_RECOMPENSA..ultimo step NIVEIS_POR_RECOMPENSA).toList().takeLast(12)

    Pagina {
        Cabecalho("Recompensas", onVoltar)
        CartaoGanho()
        Cartao(cor = Cor.CartaoAlto) {
            Text(
                "TURBO",
                color = Cor.Suave,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Text(
                textoDosTurbos(Jogo.turbos),
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                color = if (Jogo.turbos > 0) Cor.Ouro else Cor.Lilas,
            )
            Text(
                "Um turbo faz o dia inteiro valer o dobro de XP, incluindo o que você já fez hoje. " +
                    "Você ganha um a cada $NIVEIS_POR_RECOMPENSA níveis e a cada $DIAS_POR_TURBO dias de sequência.",
                fontSize = 15.sp,
                color = Cor.Suave,
            )
            Espaco(12)
            if (Jogo.turboLigado()) {
                Text(
                    "Turbo ligado hoje: tudo vale o dobro.",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Cor.Ouro,
                )
            } else {
                BotaoPrincipal("Usar um turbo hoje (XP em dobro)", habilitado = Jogo.turbos > 0) {
                    Jogo.usarTurbo()
                }
            }
        }

        TituloSecao("Prêmios da vida real")
        Text(
            "Escreva um prêmio por linha. O primeiro é o do nível $NIVEIS_POR_RECOMPENSA, " +
                "o segundo o do nível ${NIVEIS_POR_RECOMPENSA * 2}, e assim por diante.",
            fontSize = 15.sp,
            color = Cor.Suave,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        Espaco(8)
        OutlinedTextField(
            value = texto,
            onValueChange = { texto = it.take(2000) },
            label = { Text("Um prêmio por linha") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
        Espaco(10)
        BotaoPrincipal("Guardar prêmios", habilitado = texto.trim() != Jogo.premios.trim()) {
            Jogo.definirPremios(texto)
        }
        Espaco(10)
        BotaoDeFalar(ditado, "Ditar um prêmio")

        TituloSecao("Níveis de recompensa")
        for (marco in marcos) {
            val premio = Jogo.premioDoNivel(marco)
            val alcancado = nivel >= marco
            Cartao {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Nível $marco",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (alcancado) Cor.Verde else Cor.Texto,
                        )
                        Text(
                            if (premio.isEmpty()) "+1 turbo · sem prêmio escolhido" else "+1 turbo",
                            fontSize = 14.sp,
                            color = Cor.Suave,
                        )
                        if (premio.isNotEmpty()) {
                            Text("Prêmio: $premio", fontSize = 17.sp, color = Cor.Texto)
                        }
                        Text(
                            if (alcancado) "Alcançado" else "Faltam ${faltaPara(marco)} XP",
                            fontSize = 14.sp,
                            color = if (alcancado) Cor.Verde else Cor.Suave,
                        )
                    }
                    if (alcancado && premio.isNotEmpty()) {
                        Spacer(Modifier.width(8.dp))
                        if (marco in Jogo.premiosPegos) {
                            Text("Pego", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Cor.Verde)
                        } else {
                            Button(
                                onClick = { Jogo.pegarPremio(marco) },
                                shape = RoundedCornerShape(14.dp),
                                colors = ButtonDefaults.buttonColors(containerColor = Cor.Roxo, contentColor = Cor.SobreRoxo),
                            ) {
                                Text("Já peguei", fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
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
