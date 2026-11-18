package com.iacomthiago.levanta

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

// Permissões e ajustes do aparelho sem os quais o alarme pode falhar.

fun notificacoesOk(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

fun telaCheiaOk(context: Context): Boolean =
    Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

fun alarmeExatoOk(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 || context.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()

fun bateriaOk(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

fun sobreporOk(context: Context): Boolean = Settings.canDrawOverlays(context)

/** Abre uma tela de ajustes do sistema; se ela não existir neste aparelho, abre a página do app. */
fun abrirAjuste(context: Context, acao: String, comPacote: Boolean = true) {
    val pacote = Uri.parse("package:${context.packageName}")
    try {
        val intent = Intent(acao).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (comPacote) intent.data = pacote
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pacote).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e2: Exception) {
            // sem tela de ajustes disponível
        }
    }
}

fun abrirAjusteDeNotificacoes(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) {
        abrirAjuste(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }
}

/** Sobe um a cada vez que a tela volta ao primeiro plano, para reconferir as permissões. */
@Composable
fun contarRetomadas(): Int {
    val dono = LocalLifecycleOwner.current
    var n by remember { mutableIntStateOf(0) }
    DisposableEffect(dono) {
        val observador = LifecycleEventObserver { _, evento ->
            if (evento == Lifecycle.Event.ON_RESUME) n++
        }
        dono.lifecycle.addObserver(observador)
        onDispose { dono.lifecycle.removeObserver(observador) }
    }
    return n
}

private class Pendencia(
    val titulo: String,
    val detalhe: String,
    val obrigatoria: Boolean,
    val acao: () -> Unit,
)

/** Cartão que lista o que falta liberar no aparelho. Some quando está tudo certo. */
@Composable
fun Preparacao() {
    val context = LocalContext.current
    val retomadas = contarRetomadas()
    var respostas by remember { mutableIntStateOf(0) }

    val pedirNotificacao = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        respostas++
        if (!ok) abrirAjusteDeNotificacoes(context)
    }
    val pedirMicrofone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        respostas++
        if (!ok) abrirAjuste(context, Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
    }

    // Ler os contadores faz esta função refazer as checagens quando eles mudam.
    @Suppress("UNUSED_VARIABLE")
    val gatilho = retomadas + respostas

    val pendencias = buildList {
        if (!notificacoesOk(context)) add(
            Pendencia(
                "Permitir notificações",
                "É a notificação que abre a tela do alarme com o tablet bloqueado.",
                true,
            ) {
                if (Build.VERSION.SDK_INT >= 33) pedirNotificacao.launch(Manifest.permission.POST_NOTIFICATIONS)
                else abrirAjusteDeNotificacoes(context)
            }
        )
        if (!telaCheiaOk(context)) add(
            Pendencia(
                "Permitir tela cheia",
                "Deixa o alarme tomar a tela inteira por cima do bloqueio.",
                true,
            ) { abrirAjuste(context, "android.settings.MANAGE_APP_USE_FULL_SCREEN_INTENT") }
        )
        if (!alarmeExatoOk(context)) add(
            Pendencia(
                "Permitir alarmes exatos",
                "Sem isso o sistema pode atrasar o horário.",
                true,
            ) { abrirAjuste(context, "android.settings.REQUEST_SCHEDULE_EXACT_ALARM") }
        )
        if (!bateriaOk(context)) add(
            Pendencia(
                "Tirar da economia de bateria",
                "A Samsung costuma encerrar apps parados durante a noite. Escolha \"Permitir\".",
                true,
            ) { abrirAjuste(context, Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS) }
        )
        if (!sobreporOk(context)) add(
            Pendencia(
                "Aparecer sobre outros apps",
                "Recomendado: faz o alarme abrir sozinho mesmo se você estiver usando o tablet.",
                false,
            ) { abrirAjuste(context, Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
        )
        val usaVoz = Repo.config.surpriseEnabled || Repo.alarms.any { it.type == Tipo.ESTUDO }
        if (usaVoz && !microfoneLiberado(context)) add(
            Pendencia(
                "Liberar o microfone",
                "Opcional: deixa o tablet ouvir sua afirmação e os resumos das metas de estudo.",
                false,
            ) { pedirMicrofone.launch(Manifest.permission.RECORD_AUDIO) }
        )
    }

    if (pendencias.isEmpty()) return

    val faltaObrigatoria = pendencias.any { it.obrigatoria }
    Espaco(14)
    Cartao(cor = if (faltaObrigatoria) androidx.compose.ui.graphics.Color(0xFF2A2213) else Cor.Cartao) {
        Text(
            if (faltaObrigatoria) "Antes de confiar no alarme" else "Ajuste recomendado",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            color = if (faltaObrigatoria) Cor.Ouro else Cor.Texto,
        )
        if (faltaObrigatoria) {
            Text(
                "Faltam permissões do tablet. Sem elas o alarme pode não tocar.",
                fontSize = 14.sp,
                color = Cor.Suave,
            )
        }
        for (p in pendencias) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(p.titulo, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(p.detalhe, fontSize = 14.sp, lineHeight = 19.sp, color = Cor.Suave)
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = p.acao,
                    shape = RoundedCornerShape(14.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (p.obrigatoria) Cor.Roxo else Cor.CartaoAlto,
                        contentColor = if (p.obrigatoria) Cor.SobreRoxo else Cor.Texto,
                    ),
                ) {
                    Text("Liberar", fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
