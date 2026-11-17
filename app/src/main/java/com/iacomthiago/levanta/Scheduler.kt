package com.iacomthiago.levanta

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalTime
import java.time.ZonedDateTime

/** Agenda os toques no AlarmManager do sistema. */
object Scheduler {
    const val ACTION_ALARM = "com.iacomthiago.levanta.action.ALARM"
    const val ACTION_EVENT = "com.iacomthiago.levanta.action.EVENT"

    private const val REQUEST_EVENT = 900_001
    private const val REQUEST_TEST = 900_002
    private const val REQUEST_SHOW = 900_003

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

    fun nextAlarm(alarms: List<Alarm>): Pair<Alarm, Long>? =
        alarms.filter { it.enabled }.map { it to nextTrigger(it) }.minByOrNull { it.second }

    fun rescheduleAll(context: Context) {
        for (alarm in Repo.alarms) {
            if (alarm.enabled) {
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
    }

    fun cancel(context: Context, alarmId: Int) {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(ACTION_ALARM)
        manager(context).cancel(PendingIntent.getBroadcast(context, alarmId, intent, flags))
    }

    /** Alarme de teste: toca daqui a [seconds] segundos e não entra no histórico. */
    fun scheduleTest(context: Context, seconds: Int) {
        val trigger = System.currentTimeMillis() + seconds * 1000L
        val intent = Intent(context, AlarmReceiver::class.java)
            .setAction(ACTION_ALARM)
            .putExtra("alarmId", -1)
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
        when (intent.action) {
            Scheduler.ACTION_ALARM -> {
                AlarmService.send(context, AlarmService.ACTION_START) {
                    putExtra("alarmId", intent.getIntExtra("alarmId", -1))
                    putExtra("scheduledAt", intent.getLongExtra("scheduledAt", System.currentTimeMillis()))
                    putExtra("test", intent.getBooleanExtra("test", false))
                }
                Scheduler.rescheduleAll(context)
            }

            Scheduler.ACTION_EVENT -> AlarmService.send(context, AlarmService.ACTION_EVENT)
        }
    }
}

/** Depois de reiniciar o aparelho ou mudar o relógio, reagenda tudo. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        Scheduler.rescheduleAll(context)
        // Reiniciar o tablet não é saída: se havia um alarme tocando, ele volta.
        AlarmService.resumeIfNeeded(context)
    }
}
