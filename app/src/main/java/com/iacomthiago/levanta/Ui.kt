package com.iacomthiago.levanta

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

// Paleta do site iacomthiago.com.br: roxo da marca sobre fundo quase preto.
object Cor {
    val Fundo = Color(0xFF08070D)
    val Cartao = Color(0xFF15121F)
    val CartaoAlto = Color(0xFF1F1A2E)
    /** Roxo da marca: fundo dos botões principais. Como texto fica escuro demais; use [Lilas]. */
    val Roxo = Color(0xFF5D03F3)
    val SobreRoxo = Color(0xFFFFFFFF)
    /** Roxo claro para textos de destaque, chaves e contornos ativos. */
    val Lilas = Color(0xFFA77BFF)
    val SobreLilas = Color(0xFF1A0B3D)
    /** Dourado do site: avisos e pontos de atenção. */
    val Ouro = Color(0xFFF6C768)
    val Verde = Color(0xFF7EE0A1)
    val Vermelho = Color(0xFFFF7A7A)
    val Texto = Color(0xFFECEBF1)
    val Suave = Color(0xFFA09DB3)
    val Linha = Color(0xFF2A2540)
}

private val Esquema = darkColorScheme(
    primary = Cor.Lilas,
    onPrimary = Cor.SobreLilas,
    primaryContainer = Color(0xFF34128A),
    onPrimaryContainer = Color(0xFFE6DAFF),
    secondary = Cor.Lilas,
    onSecondary = Cor.SobreLilas,
    secondaryContainer = Color(0xFF34128A),
    onSecondaryContainer = Color(0xFFE6DAFF),
    tertiary = Cor.Ouro,
    onTertiary = Color(0xFF3A2A00),
    background = Cor.Fundo,
    onBackground = Cor.Texto,
    surface = Cor.Cartao,
    onSurface = Cor.Texto,
    surfaceVariant = Cor.CartaoAlto,
    onSurfaceVariant = Cor.Suave,
    surfaceContainer = Cor.Cartao,
    surfaceContainerHigh = Cor.CartaoAlto,
    surfaceContainerHighest = Cor.CartaoAlto,
    error = Cor.Vermelho,
    onError = Color(0xFF3B0A0A),
    outline = Cor.Linha,
    outlineVariant = Cor.Linha,
)

@Composable
fun LevantaTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Esquema) {
        Surface(modifier = Modifier.fillMaxSize(), color = Cor.Fundo, contentColor = Cor.Texto) {
            content()
        }
    }
}

/** Coluna rolável e centralizada; no tablet deitado o conteúdo não se esparrama. */
@Composable
fun Pagina(
    modifier: Modifier = Modifier,
    centralizar: Boolean = false,
    conteudo: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().background(Cor.Fundo),
        contentAlignment = Alignment.TopCenter,
    ) {
        // Altura mínima igual à da tela: assim o conteúdo curto pode ficar
        // centralizado e o comprido rola normalmente.
        val alturaTela = maxHeight
        Column(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .heightIn(min = alturaTela)
                .padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalAlignment = if (centralizar) Alignment.CenterHorizontally else Alignment.Start,
            verticalArrangement = if (centralizar) Arrangement.Center else Arrangement.Top,
            content = conteudo,
        )
    }
}

@Composable
fun Cartao(
    modifier: Modifier = Modifier,
    cor: Color = Cor.Cartao,
    conteudo: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(cor, RoundedCornerShape(20.dp))
            .padding(18.dp),
        content = conteudo,
    )
}

@Composable
fun TituloSecao(texto: String) {
    Text(
        text = texto.uppercase(Locale.getDefault()),
        color = Cor.Suave,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 1.2.sp,
        modifier = Modifier.padding(top = 22.dp, bottom = 8.dp, start = 4.dp),
    )
}

@Composable
fun BotaoPrincipal(
    texto: String,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.fillMaxWidth().heightIn(min = 58.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Cor.Roxo, contentColor = Cor.SobreRoxo),
    ) {
        Text(texto, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
fun BotaoSecundario(
    texto: String,
    modifier: Modifier = Modifier,
    habilitado: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = habilitado,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Text(texto, fontSize = 16.sp, textAlign = TextAlign.Center)
    }
}

/** Linha de configuração com chave liga/desliga. */
@Composable
fun LinhaChave(titulo: String, detalhe: String?, ligado: Boolean, onMudar: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(titulo, fontSize = 17.sp)
            if (detalhe != null) Text(detalhe, color = Cor.Suave, fontSize = 14.sp, lineHeight = 19.sp)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = ligado, onCheckedChange = onMudar)
    }
}

/** Linha de configuração com botões de menos e mais. */
@Composable
fun LinhaNumero(
    titulo: String,
    valor: Int,
    faixa: IntRange,
    passo: Int = 1,
    rotulo: (Int) -> String = { it.toString() },
    onMudar: (Int) -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(titulo, fontSize = 17.sp, modifier = Modifier.weight(1f))
        BotaoRedondo("−", habilitado = valor - passo >= faixa.first) { onMudar(valor - passo) }
        Text(
            rotulo(valor),
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 84.dp).padding(horizontal = 8.dp),
        )
        BotaoRedondo("+", habilitado = valor + passo <= faixa.last) { onMudar(valor + passo) }
    }
}

@Composable
private fun BotaoRedondo(texto: String, habilitado: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = habilitado,
        modifier = Modifier.size(44.dp),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Cor.CartaoAlto, contentColor = Cor.Texto),
    ) {
        Text(texto, fontSize = 22.sp)
    }
}

@Composable
fun Espaco(altura: Int) {
    Spacer(Modifier.height(altura.dp))
}

// ---------- formatação ----------

private val formatoHora = DateTimeFormatter.ofPattern("HH:mm")
private val formatoDia = DateTimeFormatter.ofPattern("EEE, dd/MM", Locale("pt", "BR"))

fun hhmm(hora: Int, minuto: Int): String = String.format(Locale.ROOT, "%02d:%02d", hora, minuto)

fun hhmm(millis: Long): String = formatoHora.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun diaCurto(millis: Long): String = formatoDia.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

/** "mm:ss" para contagens regressivas. */
fun contagem(millis: Long): String {
    val total = (millis.coerceAtLeast(0L) + 999L) / 1000L
    return String.format(Locale.ROOT, "%02d:%02d", total / 60, total % 60)
}

/** "em 7 h 12 min" */
fun daquiA(millis: Long): String {
    val minutos = ((millis - System.currentTimeMillis()).coerceAtLeast(0L) + 59_999L) / 60_000L
    val h = minutos / 60
    val m = minutos % 60
    return when {
        h >= 48 -> "em ${h / 24} dias"
        h > 0 && m > 0 -> "em $h h $m min"
        h > 0 -> "em $h h"
        else -> "em $m min"
    }
}

private val nomesDias = listOf("Seg", "Ter", "Qua", "Qui", "Sex", "Sáb", "Dom")

fun nomeDia(dia: Int): String = nomesDias[(dia - 1).coerceIn(0, 6)]

fun resumoDias(dias: Set<Int>): String {
    val d = dias.ifEmpty { (1..7).toSet() }
    return when {
        d.size == 7 -> "Todos os dias"
        d == setOf(1, 2, 3, 4, 5) -> "Segunda a sexta"
        d == setOf(6, 7) -> "Fim de semana"
        else -> d.sorted().joinToString(", ") { nomeDia(it) }
    }
}

private val formatoData = DateTimeFormatter.ofPattern("d/M/uuuu")

/** Dias até a data da prova ("dd/mm/aaaa"); nulo se a data for inválida ou já tiver passado. */
fun diasParaProva(texto: String): Long? = try {
    val dia = LocalDate.parse(texto.trim(), formatoData)
    ChronoUnit.DAYS.between(LocalDate.now(), dia).takeIf { it >= 0L }
} catch (e: Exception) {
    null
}

fun fraseDaProva(texto: String): String? = when (val dias = diasParaProva(texto)) {
    null -> null
    0L -> "A prova é hoje."
    1L -> "Falta 1 dia para a prova."
    else -> "Faltam $dias dias para a prova."
}
