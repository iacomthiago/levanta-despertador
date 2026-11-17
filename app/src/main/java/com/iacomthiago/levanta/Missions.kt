package com.iacomthiago.levanta

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import kotlinx.coroutines.delay
import java.text.Normalizer
import java.util.Locale
import kotlin.random.Random

private const val PASSO_CODIGO = "codigo"
private const val PASSO_CONTAS = "contas"
private const val PASSO_FRASE = "frase"
private const val PASSO_QUESTOES = "questoes"
const val PASSO_VOZ = "voz"
const val PASSO_MEMORIA = "memoria"

/**
 * Trégua de volume: cada toque na missão deixa o alarme baixo por 30 segundos,
 * até um teto de 3 minutos por missão. Ficar parado traz o volume de volta.
 */
fun tregua() {
    val agora = System.currentTimeMillis()
    Repo.quietUntil = minOf(agora + 30_000L, Repo.missionStartedAt + 180_000L)
}

fun cameraLiberada(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

fun microfoneLiberado(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

/** Dá para ouvir a afirmação neste aparelho? Precisa do microfone liberado e do serviço de voz. */
fun vozDisponivel(context: Context): Boolean =
    microfoneLiberado(context) && try {
        SpeechRecognizer.isRecognitionAvailable(context)
    } catch (e: Exception) {
        false
    }

/** Sorteia a missão surpresa, sem repetir a da última vez quando há mais de uma opção. */
fun sortearSurpresa(context: Context): String {
    val opcoes = buildList {
        if (vozDisponivel(context) && palavrasDaFrase(Repo.config.affirmation).size >= 2) add(PASSO_VOZ)
        add(PASSO_MEMORIA)
    }
    val anterior = Repo.lastSurprise
    val escolha = opcoes.filter { it != anterior }.ifEmpty { opcoes }.random()
    Repo.lastSurprise = escolha
    return escolha
}

fun palavrasDaFrase(frase: String): List<String> =
    frase.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.take(14)

/** Conduz as missões ligadas nas configurações, uma depois da outra. */
@Composable
fun FluxoDeMissoes(onConcluir: () -> Unit) {
    val context = LocalContext.current
    val config = Repo.config
    val passos = remember {
        buildList {
            if (config.scanEnabled && config.scanCode.isNotBlank() && cameraLiberada(context)) add(PASSO_CODIGO)
            if (config.quizEnabled && Questoes.todas(context).isNotEmpty()) add(PASSO_QUESTOES)
            if (config.mathEnabled) add(PASSO_CONTAS)
            if (config.phraseEnabled && palavrasDaFrase(config.phrase).size >= 3) add(PASSO_FRASE)
            if (config.surpriseEnabled) add(sortearSurpresa(context))
        }.ifEmpty { listOf(PASSO_CONTAS) }
    }
    var indice by remember { mutableIntStateOf(0) }
    var semCamera by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        Repo.missionStartedAt = System.currentTimeMillis()
        tregua()
    }

    val avancar = {
        if (indice + 1 >= passos.size) {
            onConcluir()
        } else {
            indice += 1
            Repo.missionStartedAt = System.currentTimeMillis()
            tregua()
        }
    }

    Pagina(centralizar = true) {
        val surpresa = passos[indice] == PASSO_VOZ || passos[indice] == PASSO_MEMORIA
        Text(
            "MISSÃO ${indice + 1} DE ${passos.size}" + if (surpresa) " · SURPRESA" else "",
            color = Cor.Lilas,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
        )
        Espaco(10)
        key(indice, semCamera) {
            when (passos[indice]) {
                PASSO_CODIGO ->
                    if (semCamera) {
                        MissaoContas(quantidade = 5, nivel = 2, onConcluir = avancar)
                    } else {
                        MissaoCodigo(codigo = config.scanCode, onConcluir = avancar, onSemCamera = { semCamera = true })
                    }

                PASSO_QUESTOES -> MissaoQuestoes(config.quizCount, avancar)
                PASSO_CONTAS -> MissaoContas(config.mathCount, config.mathLevel, avancar)
                PASSO_VOZ -> MissaoVoz(config.affirmation, avancar)
                PASSO_MEMORIA -> MissaoMemoria(avancar)
                else -> MissaoFrase(config.phrase, avancar)
            }
        }
    }
}

// ---------- contas ----------

data class Conta(val texto: String, val resposta: Int)

fun novaConta(nivel: Int): Conta = when (nivel) {
    0 -> {
        val a = Random.nextInt(11, 60)
        val b = Random.nextInt(11, 40)
        Conta("$a + $b", a + b)
    }

    1 -> {
        val a = Random.nextInt(3, 10)
        val b = Random.nextInt(12, 30)
        val c = Random.nextInt(11, 60)
        Conta("$a × $b + $c", a * b + c)
    }

    else -> {
        val a = Random.nextInt(12, 30)
        val b = Random.nextInt(12, 30)
        val c = Random.nextInt(101, 400)
        Conta("$a × $b + $c", a * b + c)
    }
}

@Composable
fun MissaoContas(quantidade: Int, nivel: Int, onConcluir: () -> Unit) {
    val total = quantidade.coerceAtLeast(1)
    var feitas by remember { mutableIntStateOf(0) }
    var conta by remember { mutableStateOf(novaConta(nivel)) }
    var entrada by remember { mutableStateOf("") }
    var erro by remember { mutableStateOf(false) }
    var pronto by remember { mutableStateOf(false) }

    Text("Resolva para desligar", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Espaco(4)
    Text("Conta ${feitas + 1} de $total", color = Cor.Suave, fontSize = 16.sp)
    Espaco(18)
    Text(conta.texto, fontSize = 54.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Text(
        "= " + entrada.ifEmpty { "?" },
        fontSize = 40.sp,
        fontWeight = FontWeight.SemiBold,
        color = if (erro) Cor.Vermelho else Cor.Lilas,
    )
    Text(
        if (erro) "Resposta errada. Tente de novo." else " ",
        color = Cor.Vermelho,
        fontSize = 15.sp,
    )
    Espaco(14)
    Teclado(
        onDigito = { d ->
            tregua()
            erro = false
            if (entrada.length < 6) entrada += d
        },
        onApagar = {
            tregua()
            entrada = entrada.dropLast(1)
        },
        onOk = {
            tregua()
            if (!pronto && entrada.isNotEmpty()) {
                if (entrada.toIntOrNull() == conta.resposta) {
                    entrada = ""
                    if (feitas + 1 >= total) {
                        pronto = true
                        onConcluir()
                    } else {
                        feitas += 1
                        conta = novaConta(nivel)
                    }
                } else {
                    erro = true
                    entrada = ""
                }
            }
        },
    )
}

/** Teclado próprio: não depende do teclado do sistema, que pode falhar sobre a tela de bloqueio. */
@Composable
private fun Teclado(onDigito: (String) -> Unit, onApagar: () -> Unit, onOk: () -> Unit) {
    val linhas = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("Apagar", "0", "OK"),
    )
    Column(modifier = Modifier.widthIn(max = 400.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (linha in linhas) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (tecla in linha) {
                    val ok = tecla == "OK"
                    Button(
                        onClick = {
                            when (tecla) {
                                "Apagar" -> onApagar()
                                "OK" -> onOk()
                                else -> onDigito(tecla)
                            }
                        },
                        modifier = Modifier.weight(1f).height(66.dp),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(0.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (ok) Cor.Roxo else Cor.CartaoAlto,
                            contentColor = if (ok) Cor.SobreRoxo else Cor.Texto,
                        ),
                    ) {
                        Text(
                            tecla,
                            fontSize = if (tecla == "Apagar") 17.sp else 26.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

// ---------- frase ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MissaoFrase(frase: String, onConcluir: () -> Unit) {
    val palavras = remember(frase) { palavrasDaFrase(frase) }
    var ordem by remember { mutableStateOf(palavras.indices.shuffled()) }
    var escolhidas by remember { mutableStateOf(listOf<Int>()) }
    var erro by remember { mutableStateOf(false) }
    var pronto by remember { mutableStateOf(false) }

    Text("Monte seu compromisso", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Espaco(4)
    Text("Toque nas palavras na ordem certa.", color = Cor.Suave, fontSize = 16.sp, textAlign = TextAlign.Center)
    Espaco(18)
    Cartao {
        Text(
            if (escolhidas.isEmpty()) "…" else escolhidas.joinToString(" ") { palavras[it] },
            fontSize = 24.sp,
            lineHeight = 32.sp,
            fontWeight = FontWeight.SemiBold,
            color = if (escolhidas.isEmpty()) Cor.Suave else Cor.Texto,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Text(
        if (erro) "Ordem errada. Recomece do início." else " ",
        color = Cor.Vermelho,
        fontSize = 15.sp,
        modifier = Modifier.height(30.dp),
    )
    Espaco(6)
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        for (i in ordem) {
            Button(
                onClick = {
                    tregua()
                    if (!pronto) {
                        if (palavras[i] == palavras[escolhidas.size]) {
                            erro = false
                            val nova = escolhidas + i
                            escolhidas = nova
                            if (nova.size == palavras.size) {
                                pronto = true
                                onConcluir()
                            }
                        } else {
                            erro = true
                            escolhidas = emptyList()
                            ordem = palavras.indices.shuffled()
                        }
                    }
                },
                enabled = i !in escolhidas,
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Cor.CartaoAlto,
                    contentColor = Cor.Texto,
                    disabledContainerColor = Cor.Cartao,
                    disabledContentColor = Cor.Linha,
                ),
            ) {
                Text(palavras[i], fontSize = 20.sp)
            }
        }
    }
}

// ---------- código em outro cômodo ----------

@Composable
fun MissaoCodigo(codigo: String, onConcluir: () -> Unit, onSemCamera: () -> Unit) {
    var aviso by remember { mutableStateOf(" ") }
    var pronto by remember { mutableStateOf(false) }
    var liberarSaida by remember { mutableStateOf(false) }

    // Saída de emergência: se a câmera falhar, depois de 2 minutos dá para
    // trocar esta missão por contas difíceis em vez de ficar preso.
    LaunchedEffect(Unit) {
        delay(120_000L)
        liberarSaida = true
    }

    Text("Vá até o código", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Espaco(4)
    Text(
        "Leve o tablet e aponte a câmera para o código que você cadastrou.",
        color = Cor.Suave,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
    )
    Espaco(16)
    Leitor(
        modifier = Modifier
            .fillMaxWidth()
            .height(340.dp)
            .clip(RoundedCornerShape(20.dp)),
    ) { lido ->
        tregua()
        if (!pronto) {
            if (lido == codigo) {
                pronto = true
                onConcluir()
            } else {
                aviso = "Esse não é o código cadastrado."
            }
        }
    }
    Espaco(8)
    Text(aviso, color = Cor.Vermelho, fontSize = 15.sp)
    if (liberarSaida) {
        Espaco(8)
        BotaoSecundario("A câmera não funciona: trocar por 5 contas difíceis") { onSemCamera() }
    }
}

/** Câmera lendo QR code ou código de barras continuamente. */
@Composable
fun Leitor(modifier: Modifier = Modifier, onCodigo: (String) -> Unit) {
    val atual by rememberUpdatedState(onCodigo)
    val dono = LocalLifecycleOwner.current
    val visao = remember { arrayOfNulls<BarcodeView>(1) }

    DisposableEffect(dono) {
        val observador = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_RESUME -> visao[0]?.resume()
                Lifecycle.Event.ON_PAUSE -> visao[0]?.pause()
                else -> {}
            }
        }
        dono.lifecycle.addObserver(observador)
        onDispose {
            dono.lifecycle.removeObserver(observador)
            visao[0]?.pause()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            BarcodeView(ctx).apply {
                decodeContinuous(object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult) {
                        val texto = result.text
                        if (!texto.isNullOrEmpty()) atual(texto)
                    }

                    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) {}
                })
                resume()
                visao[0] = this
            }
        },
    )
}

// ---------- questões de certo ou errado ----------

/**
 * Mostra questões do baralho até a pessoa somar [quantidade] acertos. Um erro
 * não conta e obriga a ler a explicação por alguns segundos antes de seguir.
 */
@Composable
fun MissaoQuestoes(quantidade: Int, onConcluir: () -> Unit) {
    val context = LocalContext.current
    val total = quantidade.coerceIn(1, 20)
    val fila = remember { Questoes.fila(context) }
    var posicao by remember { mutableIntStateOf(0) }
    var acertos by remember { mutableIntStateOf(0) }
    var resposta by remember { mutableStateOf<Boolean?>(null) }
    var liberado by remember { mutableStateOf(true) }
    var pronto by remember { mutableStateOf(false) }

    if (fila.isEmpty()) {
        LaunchedEffect(Unit) { onConcluir() }
        return
    }
    val questao = fila[posicao % fila.size]
    val marcada = resposta
    val acertou = marcada == questao.certo

    LaunchedEffect(posicao, marcada) {
        if (marcada != null && !liberado) {
            delay(4000L)
            liberado = true
        }
    }

    val responder = { valor: Boolean ->
        tregua()
        if (resposta == null) {
            val ok = valor == questao.certo
            resposta = valor
            liberado = ok
            Repo.recordAnswer(questao.id, ok)
            if (ok) {
                acertos += 1
                // Cada acerto renova o teto da trégua: ler questão leva tempo.
                Repo.missionStartedAt = System.currentTimeMillis()
                tregua()
            }
        }
    }

    Text("Certo ou errado?", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Espaco(4)
    Text("Acertos: $acertos de $total · ${questao.materia}", color = Cor.Suave, fontSize = 16.sp)
    Espaco(16)
    Cartao {
        Text(questao.texto, fontSize = 21.sp, lineHeight = 29.sp)
    }
    Espaco(16)
    if (marcada == null) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
            for ((rotulo, valor) in listOf("Certo" to true, "Errado" to false)) {
                Button(
                    onClick = { responder(valor) },
                    modifier = Modifier.weight(1f).height(70.dp),
                    shape = RoundedCornerShape(18.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Cor.CartaoAlto, contentColor = Cor.Texto),
                ) {
                    Text(rotulo, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    } else {
        Text(
            if (acertou) "Você acertou." else "Você errou: a afirmação está " + (if (questao.certo) "certa." else "errada."),
            color = if (acertou) Cor.Verde else Cor.Vermelho,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Espaco(6)
        Text(questao.porque, color = Cor.Suave, fontSize = 17.sp, lineHeight = 24.sp, textAlign = TextAlign.Center)
        Espaco(16)
        BotaoPrincipal(if (liberado) "Continuar" else "Leia a explicação…", habilitado = liberado) {
            tregua()
            if (!pronto) {
                if (acertos >= total) {
                    pronto = true
                    onConcluir()
                } else {
                    resposta = null
                    liberado = true
                    posicao += 1
                }
            }
        }
    }
}

// ---------- surpresa: memória ----------

/** Um tabuleiro 3 × 3 acende uma sequência; a pessoa repete na mesma ordem. */
@Composable
fun MissaoMemoria(onConcluir: () -> Unit, tamanho: Int = 5) {
    var rodada by remember { mutableIntStateOf(0) }
    val sequencia = remember(rodada) {
        buildList<Int> {
            while (size < tamanho) {
                val casa = Random.nextInt(9)
                if (isEmpty() || last() != casa) add(casa)
            }
        }
    }
    var aceso by remember { mutableIntStateOf(-1) }
    var tocado by remember { mutableIntStateOf(-1) }
    var toques by remember { mutableIntStateOf(0) }
    var suaVez by remember { mutableStateOf(false) }
    var feitos by remember { mutableIntStateOf(0) }
    var erro by remember { mutableStateOf(false) }
    var pronto by remember { mutableStateOf(false) }

    LaunchedEffect(rodada) {
        suaVez = false
        feitos = 0
        aceso = -1
        delay(if (rodada == 0) 900L else 1500L)
        erro = false
        for (casa in sequencia) {
            aceso = casa
            delay(600L)
            aceso = -1
            delay(250L)
        }
        suaVez = true
    }

    LaunchedEffect(toques) {
        if (tocado >= 0) {
            delay(180L)
            tocado = -1
        }
    }

    Text("Repita a sequência", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Espaco(4)
    Text(
        if (suaVez) "Sua vez: toque nos quadros na mesma ordem ($feitos de $tamanho)." else "Observe os quadros que acendem.",
        color = Cor.Suave,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
    )
    Text(if (erro) "Ordem errada. Observe de novo." else " ", color = Cor.Vermelho, fontSize = 15.sp)
    Espaco(10)
    Column(modifier = Modifier.widthIn(max = 330.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        for (linha in 0..2) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (coluna in 0..2) {
                    val casa = linha * 3 + coluna
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(18.dp))
                            .background(
                                when (casa) {
                                    aceso -> Cor.Lilas
                                    tocado -> Cor.Roxo
                                    else -> Cor.CartaoAlto
                                }
                            )
                            .clickable(enabled = suaVez && !pronto) {
                                tregua()
                                tocado = casa
                                toques += 1
                                if (casa == sequencia[feitos.coerceAtMost(tamanho - 1)]) {
                                    feitos += 1
                                    if (feitos >= tamanho) {
                                        pronto = true
                                        onConcluir()
                                    }
                                } else {
                                    erro = true
                                    rodada += 1
                                }
                            },
                    )
                }
            }
        }
    }
}

// ---------- surpresa: falar a afirmação ----------

private fun palavrasNormalizadas(texto: String): List<String> =
    Normalizer.normalize(texto.lowercase(Locale("pt", "BR")), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .replace(Regex("[^a-z0-9 ]"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotEmpty() }

/** A fala confere quando pelo menos três quartos das palavras da frase aparecem, na ordem. */
fun falaConfere(frase: String, fala: String): Boolean {
    val alvo = palavrasNormalizadas(frase)
    val dito = palavrasNormalizadas(fala)
    if (alvo.isEmpty()) return true
    var inicio = 0
    var achadas = 0
    for (palavra in alvo) {
        val onde = dito.subList(inicio, dito.size).indexOf(palavra)
        if (onde >= 0) {
            achadas += 1
            inicio += onde + 1
        }
    }
    return achadas >= (alvo.size * 3 + 3) / 4
}

/**
 * A pessoa diz a afirmação em voz alta e o tablet confere. Enquanto ouve, o
 * alarme fica mudo por alguns segundos. Se o reconhecimento de voz falhar,
 * a saída é montar a mesma frase tocando nas palavras.
 */
@Composable
fun MissaoVoz(frase: String, onConcluir: () -> Unit) {
    val context = LocalContext.current
    val concluir by rememberUpdatedState(onConcluir)
    var ouvindo by remember { mutableStateOf(false) }
    var tentativa by remember { mutableIntStateOf(0) }
    var falhas by remember { mutableIntStateOf(0) }
    var aviso by remember { mutableStateOf(" ") }
    var trocar by remember { mutableStateOf(false) }
    var pronto by remember { mutableStateOf(false) }

    val reconhecedor = remember {
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
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}

            override fun onError(error: Int) {
                if (!ouvindo) return
                ouvindo = false
                Repo.silenceUntil = 0L
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                        falhas += 1
                        aviso = "Não ouvi a frase. Fale mais alto e mais perto do tablet."
                    }

                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER -> {
                        falhas += 3
                        aviso = "O reconhecimento de voz não respondeu. Verifique a internet do tablet."
                    }

                    else -> {
                        falhas += 3
                        aviso = "O reconhecimento de voz falhou neste tablet."
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                if (!ouvindo) return
                ouvindo = false
                Repo.silenceUntil = 0L
                val ouvidas = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                if (ouvidas.any { falaConfere(frase, it) }) {
                    if (!pronto) {
                        pronto = true
                        concluir()
                    }
                } else {
                    falhas += 1
                    val primeira = ouvidas.firstOrNull().orEmpty().take(60)
                    aviso = if (primeira.isEmpty()) "Não entendi. Diga a frase inteira."
                    else "Ouvi \"$primeira\". Diga a frase inteira."
                }
            }
        })
        onDispose {
            Repo.silenceUntil = 0L
            try {
                reconhecedor?.destroy()
            } catch (e: Exception) {
                // nada a fazer
            }
        }
    }

    // Se o serviço de voz não responder em 12 segundos, a tentativa conta como falha.
    LaunchedEffect(tentativa) {
        if (tentativa > 0) {
            delay(12_000L)
            if (ouvindo) {
                ouvindo = false
                Repo.silenceUntil = 0L
                falhas += 1
                aviso = "Não ouvi a frase. Tente de novo."
                try {
                    reconhecedor?.cancel()
                } catch (e: Exception) {
                    // nada a fazer
                }
            }
        }
    }

    if (trocar) {
        if (palavrasDaFrase(frase).size >= 3) MissaoFrase(frase, onConcluir) else MissaoContas(3, 1, onConcluir)
        return
    }

    Text("Fale em voz alta", fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    Espaco(4)
    Text(
        "Toque no botão e diga a frase inteira. O alarme fica mudo enquanto o tablet escuta.",
        color = Cor.Suave,
        fontSize = 16.sp,
        textAlign = TextAlign.Center,
    )
    Espaco(18)
    Cartao {
        Text(
            frase,
            fontSize = 26.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Espaco(8)
    Text(aviso, color = Cor.Ouro, fontSize = 15.sp, textAlign = TextAlign.Center)
    Espaco(12)
    if (reconhecedor != null) {
        BotaoPrincipal(if (ouvindo) "Ouvindo…" else "Falar agora", habilitado = !ouvindo && !pronto) {
            tregua()
            val agora = System.currentTimeMillis()
            Repo.silenceUntil = minOf(agora + 9_000L, Repo.missionStartedAt + 180_000L)
            aviso = " "
            try {
                reconhecedor.startListening(
                    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "pt-BR")
                        .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                        .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                )
                ouvindo = true
                tentativa += 1
            } catch (e: Exception) {
                Repo.silenceUntil = 0L
                falhas += 3
                aviso = "O reconhecimento de voz falhou neste tablet."
            }
        }
    }
    if (reconhecedor == null || falhas >= 3) {
        Espaco(10)
        BotaoSecundario("Não funcionou: montar a frase tocando") {
            tregua()
            trocar = true
        }
    }
}
