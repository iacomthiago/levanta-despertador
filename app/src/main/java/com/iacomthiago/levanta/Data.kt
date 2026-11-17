package com.iacomthiago.levanta

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

const val DEFAULT_PHRASE = "Eu levanto agora e cumpro o que prometi"

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}

/** Um alarme configurado. [days] usa 1 = segunda ... 7 = domingo. */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = (1..7).toSet(),
    val enabled: Boolean = true,
    val label: String = "",
)

/** Regras de disciplina, iguais para todos os alarmes. */
data class Config(
    val mathEnabled: Boolean = true,
    val mathCount: Int = 3,
    val mathLevel: Int = 1,
    val phraseEnabled: Boolean = true,
    val phrase: String = DEFAULT_PHRASE,
    val scanEnabled: Boolean = false,
    val scanCode: String = "",
    val recheckCount: Int = 1,
    val recheckMinutes: Int = 5,
    val gradualVolume: Boolean = true,
    val maxSnoozes: Int = 0,
    val snoozeMinutes: Int = 5,
    val openClaude: Boolean = true,
)

object Phase {
    /** Tocando; só a missão faz parar. */
    const val RINGING = "RINGING"

    /** Em soneca, esperando para tocar de novo. */
    const val SNOOZED = "SNOOZED"

    /** Missão cumprida; esperando a hora da checagem. */
    const val WAITING = "WAITING"

    /** Perguntando "você continua de pé?" com um toque suave. */
    const val RECHECK = "RECHECK"

    /** Não respondeu à checagem: toca de novo e exige a missão outra vez. */
    const val RELAPSE = "RELAPSE"

    fun isLoud(phase: String?) = phase == RINGING || phase == RELAPSE
    fun isActive(phase: String?) = phase == RINGING || phase == RELAPSE || phase == RECHECK
}

/** Um despertar em andamento, do primeiro toque até a última checagem. */
data class Session(
    val alarmId: Int,
    val label: String,
    val test: Boolean,
    val scheduledAt: Long,
    val startedAt: Long,
    val phase: String,
    val phaseSince: Long,
    val upAt: Long = 0L,
    val snoozesUsed: Int = 0,
    val rechecksDone: Int = 0,
    val relapses: Int = 0,
    val nextEventAt: Long = 0L,
)

object Outcome {
    const val OK = "ok"
    const val NO_ANSWER = "sem_resposta"
}

data class HistoryEntry(
    val startedAt: Long,
    val scheduledAt: Long,
    val label: String,
    val upAt: Long,
    val finishedAt: Long,
    val snoozes: Int,
    val relapses: Int,
    val outcome: String,
    val test: Boolean,
)

/**
 * Estado do app, guardado em SharedPreferences e exposto como estado do
 * Compose. Tudo roda no mesmo processo, então a tela do alarme enxerga na
 * hora o que o serviço muda.
 */
object Repo {
    private lateinit var prefs: SharedPreferences

    var alarms by mutableStateOf(listOf<Alarm>())
        private set
    var config by mutableStateOf(Config())
        private set
    var session by mutableStateOf<Session?>(null)
        private set
    var history by mutableStateOf(listOf<HistoryEntry>())
        private set

    /** Resultado do último despertar, para a tela de "bom dia". */
    var lastResult by mutableStateOf<HistoryEntry?>(null)

    /** Trégua de volume enquanto a pessoa resolve a missão. */
    @Volatile
    var quietUntil = 0L

    @Volatile
    var missionStartedAt = 0L

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("levanta", Context.MODE_PRIVATE)
        alarms = readAlarms()
        config = readConfig()
        session = readSession()
        history = readHistory()
    }

    // ---------- alarmes ----------

    fun nextAlarmId(): Int = (alarms.maxOfOrNull { it.id } ?: 0) + 1

    fun upsertAlarm(alarm: Alarm) {
        val list = if (alarms.any { it.id == alarm.id }) {
            alarms.map { if (it.id == alarm.id) alarm else it }
        } else {
            alarms + alarm
        }
        alarms = list.sortedBy { it.hour * 60 + it.minute }
        writeAlarms()
    }

    fun deleteAlarm(id: Int) {
        alarms = alarms.filterNot { it.id == id }
        writeAlarms()
    }

    private fun writeAlarms() {
        val arr = JSONArray()
        for (a in alarms) {
            arr.put(
                JSONObject()
                    .put("id", a.id)
                    .put("hour", a.hour)
                    .put("minute", a.minute)
                    .put("days", JSONArray(a.days.sorted()))
                    .put("enabled", a.enabled)
                    .put("label", a.label)
            )
        }
        prefs.edit().putString("alarms", arr.toString()).apply()
    }

    private fun readAlarms(): List<Alarm> {
        val raw = prefs.getString("alarms", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val d = o.optJSONArray("days") ?: JSONArray()
                Alarm(
                    id = o.getInt("id"),
                    hour = o.getInt("hour"),
                    minute = o.getInt("minute"),
                    days = (0 until d.length()).map { d.getInt(it) }.toSet(),
                    enabled = o.optBoolean("enabled", true),
                    label = o.optString("label", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    // ---------- configurações ----------

    fun updateConfig(value: Config) {
        config = value
        val o = JSONObject()
            .put("mathEnabled", value.mathEnabled)
            .put("mathCount", value.mathCount)
            .put("mathLevel", value.mathLevel)
            .put("phraseEnabled", value.phraseEnabled)
            .put("phrase", value.phrase)
            .put("scanEnabled", value.scanEnabled)
            .put("scanCode", value.scanCode)
            .put("recheckCount", value.recheckCount)
            .put("recheckMinutes", value.recheckMinutes)
            .put("gradualVolume", value.gradualVolume)
            .put("maxSnoozes", value.maxSnoozes)
            .put("snoozeMinutes", value.snoozeMinutes)
            .put("openClaude", value.openClaude)
        prefs.edit().putString("config", o.toString()).apply()
    }

    private fun readConfig(): Config {
        val raw = prefs.getString("config", null) ?: return Config()
        return try {
            val o = JSONObject(raw)
            val d = Config()
            Config(
                mathEnabled = o.optBoolean("mathEnabled", d.mathEnabled),
                mathCount = o.optInt("mathCount", d.mathCount),
                mathLevel = o.optInt("mathLevel", d.mathLevel),
                phraseEnabled = o.optBoolean("phraseEnabled", d.phraseEnabled),
                phrase = o.optString("phrase", d.phrase),
                scanEnabled = o.optBoolean("scanEnabled", d.scanEnabled),
                scanCode = o.optString("scanCode", d.scanCode),
                recheckCount = o.optInt("recheckCount", d.recheckCount),
                recheckMinutes = o.optInt("recheckMinutes", d.recheckMinutes),
                gradualVolume = o.optBoolean("gradualVolume", d.gradualVolume),
                maxSnoozes = o.optInt("maxSnoozes", d.maxSnoozes),
                snoozeMinutes = o.optInt("snoozeMinutes", d.snoozeMinutes),
                openClaude = o.optBoolean("openClaude", d.openClaude),
            )
        } catch (e: Exception) {
            Config()
        }
    }

    // ---------- despertar em andamento ----------

    fun updateSession(value: Session) {
        session = value
        val o = JSONObject()
            .put("alarmId", value.alarmId)
            .put("label", value.label)
            .put("test", value.test)
            .put("scheduledAt", value.scheduledAt)
            .put("startedAt", value.startedAt)
            .put("phase", value.phase)
            .put("phaseSince", value.phaseSince)
            .put("upAt", value.upAt)
            .put("snoozesUsed", value.snoozesUsed)
            .put("rechecksDone", value.rechecksDone)
            .put("relapses", value.relapses)
            .put("nextEventAt", value.nextEventAt)
        // commit(): se o processo morrer logo depois, o estado já está em disco.
        prefs.edit().putString("session", o.toString()).commit()
    }

    private fun readSession(): Session? {
        val raw = prefs.getString("session", null) ?: return null
        return try {
            val o = JSONObject(raw)
            Session(
                alarmId = o.getInt("alarmId"),
                label = o.optString("label", ""),
                test = o.optBoolean("test", false),
                scheduledAt = o.getLong("scheduledAt"),
                startedAt = o.getLong("startedAt"),
                phase = o.getString("phase"),
                phaseSince = o.getLong("phaseSince"),
                upAt = o.optLong("upAt", 0L),
                snoozesUsed = o.optInt("snoozesUsed", 0),
                rechecksDone = o.optInt("rechecksDone", 0),
                relapses = o.optInt("relapses", 0),
                nextEventAt = o.optLong("nextEventAt", 0L),
            )
        } catch (e: Exception) {
            null
        }
    }

    /** Encerra o despertar atual e registra no histórico (testes não entram). */
    fun finishSession(outcome: String) {
        val s = session ?: return
        val entry = HistoryEntry(
            startedAt = s.startedAt,
            scheduledAt = s.scheduledAt,
            label = s.label,
            upAt = s.upAt,
            finishedAt = System.currentTimeMillis(),
            snoozes = s.snoozesUsed,
            relapses = s.relapses,
            outcome = outcome,
            test = s.test,
        )
        session = null
        prefs.edit().remove("session").commit()
        lastResult = entry
        quietUntil = 0L
        if (!s.test) {
            history = (listOf(entry) + history).take(400)
            writeHistory()
        }
    }

    // ---------- histórico ----------

    private fun writeHistory() {
        val arr = JSONArray()
        for (h in history) {
            arr.put(
                JSONObject()
                    .put("startedAt", h.startedAt)
                    .put("scheduledAt", h.scheduledAt)
                    .put("label", h.label)
                    .put("upAt", h.upAt)
                    .put("finishedAt", h.finishedAt)
                    .put("snoozes", h.snoozes)
                    .put("relapses", h.relapses)
                    .put("outcome", h.outcome)
            )
        }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    private fun readHistory(): List<HistoryEntry> {
        val raw = prefs.getString("history", null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                HistoryEntry(
                    startedAt = o.getLong("startedAt"),
                    scheduledAt = o.optLong("scheduledAt", o.getLong("startedAt")),
                    label = o.optString("label", ""),
                    upAt = o.optLong("upAt", 0L),
                    finishedAt = o.optLong("finishedAt", 0L),
                    snoozes = o.optInt("snoozes", 0),
                    relapses = o.optInt("relapses", 0),
                    outcome = o.optString("outcome", Outcome.OK),
                    test = false,
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun clearHistory() {
        history = emptyList()
        prefs.edit().remove("history").apply()
    }

    /** Dias seguidos (do mais recente para trás) em que a missão foi cumprida. */
    fun streak(): Int = history.takeWhile { it.outcome == Outcome.OK }.size

    /** Média, em minutos, entre o alarme tocar e a missão ser cumprida. */
    fun averageMinutesToGetUp(last: Int = 7): Int? {
        val done = history.filter { it.outcome == Outcome.OK && it.upAt > 0L }.take(last)
        if (done.isEmpty()) return null
        return (done.map { (it.upAt - it.startedAt) / 60000.0 }.average()).toInt()
    }
}
