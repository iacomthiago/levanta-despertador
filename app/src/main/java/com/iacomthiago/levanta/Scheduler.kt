package com.iacomthiago.levanta

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import java.time.LocalTime
import java.time.ZonedDateTime

/** Agenda os toques no AlarmManager do sistema. */
object Scheduler {
    const val ACTION_ALARM = "com.iacomthiago.levanta.action.ALARM"
    const val ACTION_EVENT = "com.iacomthiago.levanta.action.EVENT"
    const val ACTION_BEDTIME = "com.iacomthiago.levanta.action.BEDTIME"
    const val ACTION_STREAK = "com.iacomthiago.levanta.action.STREAK"

    /** O primeiro aviso de dormir sai meia hora antes da hora de deitar. */
    const val BEDTIME_WARNING_MS = 30 * 60_000L

    /** Hora do aviso "ainda há missão que conta para a sequência". */
    const val STREAK_WARNING_HOUR = 19

    private const val REQUEST_EVENT = 900_001
    private const val REQUEST_TEST = 900_002
    private const val REQUEST_SHOW = 900_003
    private const val REQUEST_BED_WARNING = 900_004
    private const val REQUEST_BED_NOW = 900_005
    private const val REQUEST_STREAK = 900_006

    private val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE

    /** Próximo horário (em milissegundos) em que este alarme deve tocar. */
    fun nextTrigger(alarm: Alarm, now: ZonedDateTime = ZonedDateTime.now()): Long {
        val days = alarm.days.ifEmpty { (1..7).toSet() }
        val earliest = now.plusSeconds(5)
        val time = LocalTime.of(alarm.hour, alarm.minute)
        for (offset in 0L..7L) {
            val candidate = ZonedDateTime.of(now.toLocalDate().plusDays(offset), time, now.zone)
            if (candidate.isAfter(earliest) && candidate.dayOfWeek.value in days) {
                return candidate.toInstant().toEpochMilli()
            }
        }
        return now.plusDays(1).toInstant().toEpochMilli()
    }

    /** Próximo despertar: só as missões de levantar entram, porque é delas que sai a hora de dormir. */
    fun nextAlarm(alarms: List<Alarm>): Pair<Alarm, Long>? =
        alarms.filter { it.enabled && it.type == Tipo.LEVANTAR }
            .map { it to nextTrigger(it) }
            .minByOrNull { it.second }

    fun rescheduleAll(context: Context) {
        for (alarm in Repo.alarms) {
            if (alarm.enabled && alarm.toca) {
                val trigger = nextTrigger(alarm)
                val intent = Intent(context, AlarmReceiver::class.java)
                    .setAction(ACTION_ALARM)
                    .putExtra("alarmId", alarm.id)
                    .putExtra("scheduledAt", trigger)
                setExact(context, trigger, PendingIntent.getBroadcast(context, alarm.id, intent, flags))
            } else {
                cancel(context, alarm.id)
            }
        }
        scheduleBedtime(context)
        scheduleStreakWarning(context)
    }

    /** Agenda o aviso do começo da noite sobre as missões que contam para a sequência. */
    private fun scheduleStreakWarning(context: Context) {
        val am = manager(context)
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_STREAK)
        val operacao = PendingIntent.getBroadcast(context, REQUEST_STREAK, intent, flags)
        if (!Repo.config.streakWarning) {
            am.cancel(operacao)
            return
        }
        val agora = ZonedDateTime.now()
        var quando = ZonedDateTime.of(agora.toLocalDate(), LocalTime.of(STREAK_WARNING_HOUR, 0), agora.zone)
        if (!quando.isAfter(agora.plusSeconds(5))) quando = quando.plusDays(1)
        val millis = quando.toInstant().toEpochMilli()
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operacao)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, operacao)
        }
    }

    /**
     * Hora de deitar para dormir o combinado antes do próximo alarme, junto com
     * a hora desse alarme. Nulo se o aviso está desligado ou não há alarme ligado.
     */
    fun bedtime(): Pair<Long, Long>? {
        if (!Repo.config.bedtimeEnabled) return null
        val proximo = nextAlarm(Repo.alarms) ?: return null
        val sono = Repo.config.sleepMinutes.coerceIn(240, 720) * 60_000L
        return (proximo.second - sono) to proximo.second
    }

    /** Agenda os dois avisos de dormir (meia hora antes e na hora); os que já passaram são cancelados. */
    private fun scheduleBedtime(context: Context) {
        val am = manager(context)
        val plano = bedtime()
        val agora = System.currentTimeMillis()
        for ((pedido, antecedencia) in listOf(REQUEST_BED_WARNING to BEDTIME_WARNING_MS, REQUEST_BED_NOW to 0L)) {
            val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_BEDTIME)
            if (plano == null || plano.first - antecedencia <= agora) {
                am.cancel(PendingIntent.getBroadcast(context, pedido, intent, flags))
                continue
            }
            intent.putExtra("aviso", antecedencia > 0L)
                .putExtra("deitar", plano.first)
                .putExtra("alarme", plano.second)
            val quando = plano.first - antecedencia
            val operacao = PendingIntent.getBroadcast(context, pedido, intent, flags)
            try {
                // Não usa setAlarmClock: o aviso de dormir não deve aparecer no
                // sistema como se fosse o próximo despertador.
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, operacao)
            } catch (e: SecurityException) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, quando, operacao)
            }
        }
    }

    fun cancel(context: Context, alarmId: Int) {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM)
        manager(context).cancel(PendingIntent.getBroadcast(context, alarmId, intent, flags))
    }

    /**
     * Alarme de teste: toca daqui a [seconds] segundos e não entra no histórico.
     * Sem [alarmId] é o despertador; com ele, toca como a missão indicada.
     */
    fun scheduleTest(context: Context, seconds: Int, alarmId: Int = -1) {
        val trigger = System.currentTimeMillis() + seconds * 1000L
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .putExtra("alarmId", alarmId)
            .putExtra("scheduledAt", trigger)
            .putExtra("test", true)
        setExact(context, trigger, PendingIntent.getBroadcast(context, REQUEST_TEST, intent, flags))
    }

    /** Fim da soneca ou hora da checagem "você continua de pé?". */
    fun scheduleEvent(context: Context, atMillis: Long) {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_EVENT)
        setExact(context, atMillis, PendingIntent.getBroadcast(context, REQUEST_EVENT, intent, flags))
    }

    private fun manager(context: Context) = context.getSystemService(AlarmManager::class.java)

    private fun setExact(context: Context, trigger: Long, operation: PendingIntent) {
        val am = manager(context)
        val show = PendingIntent.getActivity(
            context, REQUEST_SHOW, Intent(context, MainActivity::class.java), flags
        )
        try {
            // setAlarmClock é o modo mais confiável: fura o modo de repouso do
            // aparelho e autoriza iniciar o serviço que toca o som.
            am.setAlarmClock(AlarmManager.AlarmClockInfo(trigger, show), operation)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, operation)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        Jogo.fecharDias()
        when (intent.action) {
            Scheduler.ACTION_STREAK -> {
                Lembrete.sequencia(context)
                Scheduler.rescheduleAll(context)
            }

            Scheduler.ACTION_ALARM -> {
                AlarmService.send(context, AlarmService.ACTION_START) {
                    putExtra("alarmId", intent.getIntExtra("alarmId", -1))
                    putExtra("scheduledAt", intent.getLongExtra("scheduledAt", System.currentTimeMillis()))
                    putExtra("test", intent.getBooleanExtra("test", false))
                }
                Scheduler.rescheduleAll(context)
            }

            Scheduler.ACTION_EVENT -> AlarmService.send(context, AlarmService.ACTION_EVENT)

            Scheduler.ACTION_BEDTIME -> Dormir.avisar(
                context,
                aviso = intent.getBooleanExtra("aviso", false),
                deitar = intent.getLongExtra("deitar", 0L),
                alarme = intent.getLongExtra("alarme", 0L),
            )
        }
    }
}

/** Depois de reiniciar o aparelho ou mudar o relógio, reagenda tudo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        Jogo.fecharDias()
        Scheduler.rescheduleAll(context)
        // Reiniciar o tablet não é saída: se havia um alarme tocando, ele volta.
        AlarmService.resumeIfNeeded(context)
    }
}

/** Notificação com um toque suave avisando que está na hora de ir dormir. */
object Dormir {
    private const val CANAL = "dormir"
    private const val ID = 77

    fun avisar(context: Context, aviso: Boolean, deitar: Long, alarme: Long) {
        // Só vale se o plano continua o mesmo: o alarme pode ter mudado ou sido desligado.
        val plano = Scheduler.bedtime() ?: return
        if (kotlin.math.abs(plano.second - alarme) > 60_000L) return
        if (Repo.session != null) return

        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            val canal = NotificationChannel(CANAL, "Hora de dormir", NotificationManager.IMPORTANCE_HIGH)
            canal.description = "Avisa a hora de deitar para dormir o suficiente antes do alarme."
            // O toque sai pelo volume de alarme, para ser ouvido mesmo com a mídia baixa.
            canal.setSound(
                Uri.parse("android.resource://" + context.packageName + "/" + R.raw.chime),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            nm.createNotificationChannel(canal)
        }

        val titulo = if (aviso) "Hora de dormir em 30 minutos" else "Hora de dormir"
        val texto = "Alarme às ${hhmm(alarme)}. Deitando às ${hhmm(deitar)} você dorme ${duracao(Repo.config.sleepMinutes)}."
        val abrir = PendingIntent.getActivity(
            context,
            2,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificacao = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(abrir)
            .build()
        try {
            nm.notify(ID, notificacao)
        } catch (e: Exception) {
            Log.w("Levanta", "Não foi possível avisar a hora de dormir", e)
        }
    }
}

/** Avisos silenciosos das missões: uma que ficou esperando e a sequência em risco. */
object Lembrete {
    private const val CANAL = "missoes"
    private const val ID_SEQUENCIA = 78

    private fun gerenciador(context: Context): NotificationManager {
        val nm = context.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CANAL) == null) {
            val canal = NotificationChannel(CANAL, "Missões do dia", NotificationManager.IMPORTANCE_DEFAULT)
            canal.description = "Lembra uma missão que ficou esperando e avisa quando a sequência está em risco."
            nm.createNotificationChannel(canal)
        }
        return nm
    }

    private fun publicar(context: Context, id: Int, titulo: String, texto: String, missaoId: Int) {
        val abrir = PendingIntent.getActivity(
            context,
            3000 + id,
            MainActivity.intentPara(context, missaoId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notificacao = NotificationCompat.Builder(context, CANAL)
            .setSmallIcon(R.drawable.ic_stat_alarm)
            .setContentTitle(titulo)
            .setContentText(texto)
            .setStyle(NotificationCompat.BigTextStyle().bigText(texto))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(abrir)
            .build()
        try {
            gerenciador(context).notify(id, notificacao)
        } catch (e: Exception) {
            Log.w("Levanta", "Não foi possível publicar o lembrete", e)
        }
    }

    /** O alarme da missão tocou e ninguém atendeu, ou tocou no meio do despertar. */
    fun missaoEsperando(context: Context, alarme: Alarm) {
        if (Jogo.feita(alarme)) return
        publicar(context, 200 + alarme.id, "Missão esperando: ${alarme.nome}", "Toque para abrir a missão.", alarme.id)
    }

    fun limpar(context: Context, alarmId: Int) {
        try {
            context.getSystemService(NotificationManager::class.java).cancel(200 + alarmId)
        } catch (e: Exception) {
            // nada a fazer
        }
    }

    /** No começo da noite: o que ainda falta para o dia contar na sequência. */
    fun sequencia(context: Context) {
        if (!Repo.config.streakWarning || Repo.session != null) return
        val pendentes = Jogo.pendentesQueContam()
        if (pendentes.isEmpty()) return
        val dias = Jogo.sequencia
        val titulo = when {
            dias == 1 -> "Sua sequência de 1 dia depende de hoje"
            dias > 1 -> "Sua sequência de $dias dias depende de hoje"
            else -> "Ainda há missão para hoje"
        }
        publicar(context, ID_SEQUENCIA, titulo, "Falta: " + pendentes.joinToString(", ") { it.nome } + ".", -1)
    }
}
