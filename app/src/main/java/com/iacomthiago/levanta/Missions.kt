package com.iacomthiago.levanta

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import kotlin.random.Random

private const val PASSO_CODIGO = "codigo"
private const val PASSO_CONTAS = "contas"
private const val PASSO_FRASE = "frase"

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
            if (config.mathEnabled) add(PASSO_CONTAS)
            if (config.phraseEnabled && palavrasDaFrase(config.phrase).size >= 3) add(PASSO_FRASE)
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
        Text(
            "MISSÃO ${indice + 1} DE ${passos.size}",
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

                PASSO_CONTAS -> MissaoContas(config.mathCount, config.mathLevel, avancar)
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
