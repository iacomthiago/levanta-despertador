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
const val DEFAULT_AFFIRMATION = "Eu vou conseguir. Eu vou ser aprovado."
const val DEFAULT_EXAM_DATE = "13/12/2026"
const val DEFAULT_ENCOURAGEMENT = "Thiago, você vai conseguir. Você vai ser aprovado. Eu acredito em você."

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
    }
}

/** Tipos de missão. O de levantar é o despertador: só para com a missão cumprida. */
object Tipo {
    const val LEVANTAR = "levantar"
    const val ESTUDO = "estudo"
    const val TREINO = "treino"
    const val SIMPLES = "simples"

    val todos = listOf(LEVANTAR, ESTUDO, TREINO, SIMPLES)

    fun nome(tipo: String): String = when (tipo) {
        ESTUDO -> "Estudo"
        TREINO -> "Treino"
        SIMPLES -> "Simples"
        else -> "Levantar"
    }
}

const val URL_TREINO = "https://iacomthiago.github.io/treino-em-casa/"

/**
 * Uma missão com horário. [days] usa 1 = segunda ... 7 = domingo.
 *
 * Os alarmes salvos antes de existirem os tipos não têm os campos novos e são
 * lidos como missões de levantar, que é o que eles eram.
 */
data class Alarm(
    val id: Int,
    val hour: Int,
    val minute: Int,
    val days: Set<Int> = (1..7).toSet(),
    val enabled: Boolean = true,
    val label: String = "",
    val type: String = Tipo.LEVANTAR,
    /** Conta para a sequência: sem concluir até o fim do dia, a sequência zera. */
    val counts: Boolean = true,
    /** Toca no horário. A missão de levantar toca sempre. */
    val rings: Boolean = true,
    /** Treino: duração prevista, em minutos. */
    val minutes: Int = 30,
    /** Treino: parte da duração que precisa passar para liberar o "Concluí". */
    val minPercent: Int = 60,
    /** Treino: endereço que abre no navegador; vazio usa [URL_TREINO]. */
    val url: String = "",
    /** Quando a missão foi criada; no dia da criação ela só vale se a hora ainda não passou. */
    val createdAt: Long = 0L,
) {
    val toca: Boolean get() = type == Tipo.LEVANTAR || rings
    val nome: String get() = label.ifBlank { Tipo.nome(type) }
    val endereco: String get() = url.ifBlank { URL_TREINO }
}

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
    /** Questões de certo ou errado do concurso; [quizCount] é o número de acertos exigido. */
    val quizEnabled: Boolean = true,
    val quizCount: Int = 5,
    /** Missão extra a cada despertar: falar a afirmação (memória só se não der para ouvir). */
    val surpriseEnabled: Boolean = true,
    val affirmation: String = DEFAULT_AFFIRMATION,
    /** O que o app responde em voz alta depois de ouvir a afirmação; vazio desliga a resposta. */
    val encouragement: String = DEFAULT_ENCOURAGEMENT,
    /** Começa com a música; depois de [musicSeconds] sem missão cumprida, vira o bipe. */
    val musicFirst: Boolean = true,
    val musicSeconds: Int = 60,
    /** Data da prova no formato dd/mm/aaaa; vazio desliga a contagem. */
    val examDate: String = DEFAULT_EXAM_DATE,
    /** Avisa a hora de deitar para dormir [sleepMinutes] antes do próximo alarme. */
    val bedtimeEnabled: Boolean = true,
    val sleepMinutes: Int = 480,
    /** Fechar todas as metas de estudo antes desta hora dá bônus. */
    val studyBonusHour: Int = 14,
    /** Sobrou da versão com metas escritas; nada mais usa. */
    val minWords: Int = 20,
    /** Quantos blocos de 30 minutos de estudo fecham a missão de estudo do dia. */
    val studyBlocks: Int = 4,
    /** Avisa no começo da noite se ainda há missão que conta para a sequência. */
    val streakWarning: Boolean = true,
)

/** Uma questão de certo ou errado do baralho em assets/questoes.json. */
data class Questao(
    val id: String,
    val materia: String,
    val texto: String,
    val certo: Boolean,
    val porque: String,
)

/** Última vez que uma questão apareceu e se a resposta foi certa. */
data class Desempenho(val vistaEm: Long, val acertou: Boolean)

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
    /** Tipo da missão que tocou; as que não são de levantar param com um toque. */
    val type: String = Tipo.LEVANTAR,
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

    /** Silêncio total por alguns segundos, para o microfone ouvir a afirmação. */
    @Volatile
    var silenceUntil = 0L

    /** Desempenho por questão, para trazer primeiro as erradas e as nunca vistas. */
    var quizStats = mapOf<String, Desempenho>()
        private set

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences("levanta", Context.MODE_PRIVATE)
        alarms = readAlarms()
        config = readConfig()
        session = readSession()
        history = readHistory()
        quizStats = readQuizStats()
        Jogo.init(context.applicationContext, prefs)
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

    /** Troca a lista inteira de uma vez (usado na conversão dos dados antigos). */
    fun replaceAlarms(list: List<Alarm>) {
        alarms = list.sortedBy { it.hour * 60 + it.minute }
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
                    .put("type", a.type)
                    .put("counts", a.counts)
                    .put("rings", a.rings)
                    .put("minutes", a.minutes)
                    .put("minPercent", a.minPercent)
                    .put("url", a.url)
                    .put("createdAt", a.createdAt)
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
                    type = o.optString("type", Tipo.LEVANTAR).takeIf { it in Tipo.todos } ?: Tipo.LEVANTAR,
                    counts = o.optBoolean("counts", true),
                    rings = o.optBoolean("rings", true),
                    minutes = o.optInt("minutes", 30),
                    minPercent = o.optInt("minPercent", 60),
                    url = o.optString("url", ""),
                    createdAt = o.optLong("createdAt", 0L),
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
            .put("quizEnabled", value.quizEnabled)
            .put("quizCount", value.quizCount)
            .put("surpriseEnabled", value.surpriseEnabled)
            .put("affirmation", value.affirmation)
            .put("encouragement", value.encouragement)
            .put("musicFirst", value.musicFirst)
            .put("musicSeconds", value.musicSeconds)
            .put("examDate", value.examDate)
            .put("bedtimeEnabled", value.bedtimeEnabled)
            .put("sleepMinutes", value.sleepMinutes)
            .put("studyBonusHour", value.studyBonusHour)
            .put("minWords", value.minWords)
            .put("studyBlocks", value.studyBlocks)
            .put("streakWarning", value.streakWarning)
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
                quizEnabled = o.optBoolean("quizEnabled", d.quizEnabled),
                quizCount = o.optInt("quizCount", d.quizCount),
                surpriseEnabled = o.optBoolean("surpriseEnabled", d.surpriseEnabled),
                affirmation = o.optString("affirmation", d.affirmation),
                encouragement = o.optString("encouragement", d.encouragement),
                musicFirst = o.optBoolean("musicFirst", d.musicFirst),
                musicSeconds = o.optInt("musicSeconds", d.musicSeconds),
                examDate = o.optString("examDate", d.examDate),
                bedtimeEnabled = o.optBoolean("bedtimeEnabled", d.bedtimeEnabled),
                sleepMinutes = o.optInt("sleepMinutes", d.sleepMinutes),
                studyBonusHour = o.optInt("studyBonusHour", d.studyBonusHour),
                minWords = o.optInt("minWords", d.minWords),
                studyBlocks = o.optInt("studyBlocks", d.studyBlocks),
                streakWarning = o.optBoolean("streakWarning", d.streakWarning),
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
            .put("type", value.type)
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
                type = o.optString("type", Tipo.LEVANTAR),
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
        silenceUntil = 0L
        if (!s.test) {
            history = (listOf(entry) + history).take(400)
            writeHistory()
            if (outcome == Outcome.OK) Jogo.concluirLevantar(s.alarmId, s.scheduledAt)
        }
    }

    /**
     * Encerra, sem registrar no histórico, o toque de uma missão que não é a de
     * levantar: essas param com um toque e a conclusão acontece na tela da missão.
     */
    fun dropSession() {
        if (session == null) return
        session = null
        prefs.edit().remove("session").commit()
        quietUntil = 0L
        silenceUntil = 0L
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

    // ---------- questões e missão surpresa ----------

    fun recordAnswer(id: String, acertou: Boolean) {
        quizStats = quizStats + (id to Desempenho(System.currentTimeMillis(), acertou))
        val o = JSONObject()
        for ((chave, d) in quizStats) {
            o.put(chave, JSONObject().put("t", d.vistaEm).put("ok", d.acertou))
        }
        prefs.edit().putString("quizStats", o.toString()).apply()
    }

    private fun readQuizStats(): Map<String, Desempenho> {
        val raw = prefs.getString("quizStats", null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            buildMap {
                for (chave in o.keys()) {
                    val d = o.getJSONObject(chave)
                    put(chave, Desempenho(d.optLong("t", 0L), d.optBoolean("ok", false)))
                }
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /** Qual missão surpresa saiu da última vez, para não repetir em seguida. */
    var lastSurprise: String
        get() = prefs.getString("lastSurprise", "") ?: ""
        set(value) {
            prefs.edit().putString("lastSurprise", value).apply()
        }

    /** Despertares seguidos (do mais recente para trás) em que a missão foi cumprida. */
    fun streak(): Int = history.takeWhile { it.outcome == Outcome.OK }.size

    /** Média, em minutos, entre o alarme tocar e a missão ser cumprida. */
    fun averageMinutesToGetUp(last: Int = 7): Int? {
        val done = history.filter { it.outcome == Outcome.OK && it.upAt > 0L }.take(last)
        if (done.isEmpty()) return null
        return (done.map { (it.upAt - it.startedAt) / 60000.0 }.average()).toInt()
    }
}

/** Baralho de questões de certo ou errado, lido uma vez de assets/questoes.json. */
object Questoes {
    private var cache: List<Questao>? = null

    fun todas(context: Context): List<Questao> {
        cache?.let { return it }
        val lista = try {
            val texto = context.applicationContext.assets.open("questoes.json").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(texto)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Questao(
                    id = o.getString("id"),
                    materia = o.optString("materia", ""),
                    texto = o.getString("texto"),
                    certo = o.getBoolean("certo"),
                    porque = o.optString("porque", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        cache = lista
        return lista
    }

    /**
     * Ordem em que as questões aparecem hoje: primeiro as que foram erradas da
     * última vez, depois as nunca vistas, depois as acertadas há mais tempo.
     * Dentro de cada grupo a ordem é sorteada.
     */
    fun fila(context: Context): List<Questao> {
        val stats = Repo.quizStats
        return todas(context).shuffled().sortedWith(
            compareBy<Questao> {
                val d = stats[it.id]
                when {
                    d == null -> 1
                    !d.acertou -> 0
                    else -> 2
                }
            }.thenBy { stats[it.id]?.vistaEm ?: 0L }
        )
    }
}
