package com.iacomthiago.levanta

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Quanto vale cada coisa. Estudo é o que mais rende: cada meta conta, e fechar o dia cedo dá bônus. */
object Xp {
    const val LEVANTAR = 30
    const val TREINO = 40
    const val SIMPLES = 10
    const val META = 30
    const val BONUS_ESTUDO = 50
    const val SEMANA = 150

    fun daMissao(tipo: String): Int = when (tipo) {
        Tipo.TREINO -> TREINO
        Tipo.SIMPLES -> SIMPLES
        Tipo.ESTUDO -> 0
        else -> LEVANTAR
    }
}

/** Experiência total necessária para chegar ao nível [n]. Cada nível pede 50 a mais que o anterior. */
fun xpDoNivel(n: Int): Int {
    val k = (n - 1).coerceAtLeast(0)
    return 100 * k + 25 * k * (k - 1)
}

fun nivelDe(xp: Int): Int {
    var n = 1
    while (n < 999 && xpDoNivel(n + 1) <= xp) n++
    return n
}

/** Faixas de nível, com nomes do próprio app: as marchas do carro. */
data class Rank(val nome: String, val desde: Int)

val RANKS = listOf(
    Rank("Partida", 1),
    Rank("Primeira marcha", 3),
    Rank("Segunda marcha", 6),
    Rank("Terceira marcha", 10),
    Rank("Quarta marcha", 15),
    Rank("Quinta marcha", 21),
)

fun indiceDoRank(nivel: Int): Int = RANKS.indexOfLast { nivel >= it.desde }.coerceAtLeast(0)

/** Uma missão concluída num dia ([dia] no formato aaaa-mm-dd). */
data class Feito(val dia: String, val alarmId: Int, val tipo: String, val em: Long, val xp: Int)

/** Uma meta de estudo. Depois de concluída, ela e o [resumo] formam o diário de estudo. */
data class Meta(
    val id: Long,
    val dia: String,
    val texto: String,
    val feitaEm: Long = 0L,
    val resumo: String = "",
)

/** A missão da semana (de segunda a domingo). */
data class Semana(val inicio: String, val desafio: String, val alvo: Int, val premiada: Boolean = false)

/** Uma ideia anotada para depois, para não largar o que está fazendo. */
data class Ideia(val id: Long, val em: Long, val texto: String)

/** Último ganho, para a tela avisar. */
data class Ganho(val xp: Int, val linhas: List<String>, val nivel: Int, val em: Long)

fun hojeTexto(): String = LocalDate.now().toString()

fun contarPalavras(texto: String): Int = texto.trim().split(Regex("\\s+")).count { it.isNotBlank() }

/**
 * Progresso: experiência, nível, sequência de dias, metas de estudo e missão
 * da semana. Fica no mesmo arquivo de preferências do resto do app, em chaves
 * próprias, então atualizar o app não apaga nada.
 */
object Jogo {
    /** Identificador usado no registro do dia de estudo, que não pertence a um alarme só. */
    const val ID_ESTUDO = -2

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context

    var xp by mutableStateOf(0)
        private set
    var feitos by mutableStateOf(listOf<Feito>())
        private set
    var metas by mutableStateOf(listOf<Meta>())
        private set
    var ideias by mutableStateOf(listOf<Ideia>())
        private set

    /** Dias seguidos já fechados (até ontem). O dia de hoje entra em [sequenciaAtual]. */
    var sequencia by mutableStateOf(0)
        private set
    var recorde by mutableStateOf(0)
        private set
    private var fechadoAte = ""

    var semana by mutableStateOf<Semana?>(null)
        private set

    /** Hora em que cada treino começou, por missão. */
    var inicios by mutableStateOf(mapOf<Int, Long>())
        private set

    /** Carimbo da imagem escolhida na galeria; 0 usa a imagem que vem no app. */
    var imagem by mutableStateOf(0L)
        private set

    var ganho by mutableStateOf<Ganho?>(null)

    fun init(context: Context, preferencias: SharedPreferences) {
        if (::prefs.isInitialized) return
        prefs = preferencias
        app = context
        if (prefs.contains("jogo")) {
            ler()
        } else {
            converter()
        }
        fecharDias()
        garantirSemana()
    }

    // ---------- conversão dos dados do Levanta ----------

    /**
     * Primeira vez nesta versão. O que já existia continua: os alarmes viram
     * missões de levantar, os despertares cumpridos viram experiência e a
     * sequência de despertares vira a sequência de dias. A rotina combinada
     * (treino às 15h e estudo de manhã) entra pronta.
     */
    private fun converter() {
        val agora = System.currentTimeMillis()
        val cumpridos = Repo.history.count { it.outcome == Outcome.OK }
        xp = cumpridos * Xp.LEVANTAR
        sequencia = Repo.streak()
        recorde = sequencia
        fechadoAte = LocalDate.now().minusDays(1).toString()

        // O despertar de hoje, cumprido ainda na versão antiga, aparece como concluído.
        val hoje = hojeTexto()
        feitos = Repo.history
            .filter { it.outcome == Outcome.OK && diaDe(it.scheduledAt) == hoje }
            .mapNotNull { registro ->
                val hora = Instant.ofEpochMilli(registro.scheduledAt).atZone(ZoneId.systemDefault()).toLocalTime()
                val alarme = Repo.alarms.firstOrNull { it.hour == hora.hour && it.minute == hora.minute }
                if (alarme == null) null else Feito(hoje, alarme.id, Tipo.LEVANTAR, registro.finishedAt, 0)
            }

        val lista = Repo.alarms.map { it.copy(createdAt = agora) }.toMutableList()
        var proximo = (lista.maxOfOrNull { it.id } ?: 0) + 1
        if (lista.none { it.type == Tipo.ESTUDO }) {
            lista += Alarm(
                id = proximo++, hour = 6, minute = 0, label = "Estudo",
                type = Tipo.ESTUDO, rings = false, createdAt = agora,
            )
        }
        if (lista.none { it.type == Tipo.TREINO }) {
            lista += Alarm(
                id = proximo++, hour = 15, minute = 0, label = "Treino",
                type = Tipo.TREINO, createdAt = agora,
            )
        }
        Repo.replaceAlarms(lista)
        salvar()
    }

    // ---------- leitura e gravação ----------

    private fun ler() {
        try {
            val o = JSONObject(prefs.getString("jogo", "{}") ?: "{}")
            xp = o.optInt("xp", 0)
            sequencia = o.optInt("sequencia", 0)
            recorde = o.optInt("recorde", sequencia)
            fechadoAte = o.optString("fechadoAte", "")
            imagem = o.optLong("imagem", 0L)
            val s = o.optJSONObject("semana")
            semana = if (s == null) null else Semana(
                inicio = s.optString("inicio", ""),
                desafio = s.optString("desafio", Tipo.TREINO),
                alvo = s.optInt("alvo", 5),
                premiada = s.optBoolean("premiada", false),
            )
            val i = o.optJSONObject("inicios")
            val lidos = mutableMapOf<Int, Long>()
            if (i != null) {
                for (chave in i.keys()) {
                    val id = chave.toIntOrNull() ?: continue
                    lidos[id] = i.optLong(chave, 0L)
                }
            }
            inicios = lidos
        } catch (e: Exception) {
            // dados ilegíveis: começa do zero em vez de travar o app
        }
        feitos = try {
            val arr = JSONArray(prefs.getString("feitos", "[]") ?: "[]")
            (0 until arr.length()).map { n ->
                val o = arr.getJSONObject(n)
                Feito(
                    dia = o.getString("dia"),
                    alarmId = o.optInt("alarmId", 0),
                    tipo = o.optString("tipo", Tipo.LEVANTAR),
                    em = o.optLong("em", 0L),
                    xp = o.optInt("xp", 0),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        metas = try {
            val arr = JSONArray(prefs.getString("metas", "[]") ?: "[]")
            (0 until arr.length()).map { n ->
                val o = arr.getJSONObject(n)
                Meta(
                    id = o.getLong("id"),
                    dia = o.getString("dia"),
                    texto = o.optString("texto", ""),
                    feitaEm = o.optLong("feitaEm", 0L),
                    resumo = o.optString("resumo", ""),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
        ideias = try {
            val arr = JSONArray(prefs.getString("ideias", "[]") ?: "[]")
            (0 until arr.length()).map { n ->
                val o = arr.getJSONObject(n)
                Ideia(id = o.getLong("id"), em = o.optLong("em", 0L), texto = o.optString("texto", ""))
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun salvar() {
        val o = JSONObject()
            .put("xp", xp)
            .put("sequencia", sequencia)
            .put("recorde", recorde)
            .put("fechadoAte", fechadoAte)
            .put("imagem", imagem)
        val s = semana
        if (s != null) {
            o.put(
                "semana",
                JSONObject()
                    .put("inicio", s.inicio)
                    .put("desafio", s.desafio)
                    .put("alvo", s.alvo)
                    .put("premiada", s.premiada)
            )
        }
        val i = JSONObject()
        for ((id, quando) in inicios) i.put(id.toString(), quando)
        o.put("inicios", i)

        val arr = JSONArray()
        for (f in feitos) {
            arr.put(
                JSONObject()
                    .put("dia", f.dia)
                    .put("alarmId", f.alarmId)
                    .put("tipo", f.tipo)
                    .put("em", f.em)
                    .put("xp", f.xp)
            )
        }
        // commit(): experiência ganha não pode se perder se o processo morrer logo depois.
        prefs.edit().putString("jogo", o.toString()).putString("feitos", arr.toString()).commit()
    }

    private fun salvarMetas() {
        val arr = JSONArray()
        for (m in metas) {
            arr.put(
                JSONObject()
                    .put("id", m.id)
                    .put("dia", m.dia)
                    .put("texto", m.texto)
                    .put("feitaEm", m.feitaEm)
                    .put("resumo", m.resumo)
            )
        }
        prefs.edit().putString("metas", arr.toString()).commit()
    }

    private fun salvarIdeias() {
        val arr = JSONArray()
        for (i in ideias) {
            arr.put(JSONObject().put("id", i.id).put("em", i.em).put("texto", i.texto))
        }
        prefs.edit().putString("ideias", arr.toString()).apply()
    }

    // ---------- experiência e nível ----------

    val nivel: Int get() = nivelDe(xp)

    private fun ganhar(valor: Int, motivo: String) {
        val agora = System.currentTimeMillis()
        val antes = nivelDe(xp)
        xp += valor
        val depois = nivelDe(xp)
        val subiu = if (depois > antes) depois else 0
        val anterior = ganho
        ganho = if (anterior != null && agora - anterior.em < 5_000L) {
            Ganho(anterior.xp + valor, anterior.linhas + motivo, maxOf(anterior.nivel, subiu), agora)
        } else {
            Ganho(valor, listOf(motivo), subiu, agora)
        }
    }

    private fun registrar(feito: Feito, motivo: String) {
        feitos = (feitos + feito).takeLast(4000)
        ganhar(feito.xp, motivo)
        conferirSemana()
        salvar()
    }

    // ---------- missões do dia ----------

    /** Missões ligadas que caem neste dia da semana. */
    fun doDia(dia: LocalDate): List<Alarm> =
        Repo.alarms.filter { it.enabled && dia.dayOfWeek.value in it.days.ifEmpty { (1..7).toSet() } }

    /**
     * A missão entra na conta da sequência neste dia? No dia em que foi criada,
     * só se a hora dela ainda não tinha passado: criar às 16h uma missão das
     * 15h não derruba a sequência de hoje.
     */
    fun vale(alarme: Alarm, dia: LocalDate): Boolean {
        if (!alarme.counts) return false
        if (alarme.createdAt <= 0L) return true
        val criada = Instant.ofEpochMilli(alarme.createdAt).atZone(ZoneId.systemDefault())
        val diaCriada = criada.toLocalDate()
        return when {
            dia.isAfter(diaCriada) -> true
            dia.isBefore(diaCriada) -> false
            else -> !criada.toLocalTime().isAfter(LocalTime.of(alarme.hour, alarme.minute))
        }
    }

    fun feita(alarme: Alarm, dia: String = hojeTexto()): Boolean =
        if (alarme.type == Tipo.ESTUDO) {
            feitos.any { it.dia == dia && it.tipo == Tipo.ESTUDO }
        } else {
            feitos.any { it.dia == dia && it.alarmId == alarme.id }
        }

    /** Conclui hoje uma missão de treino ou simples. Devolve a experiência ganha (0 se já estava feita). */
    fun concluir(alarme: Alarm): Int {
        val dia = hojeTexto()
        if (alarme.type == Tipo.ESTUDO || alarme.type == Tipo.LEVANTAR) return 0
        if (feita(alarme, dia)) return 0
        val valor = Xp.daMissao(alarme.type)
        registrar(Feito(dia, alarme.id, alarme.type, System.currentTimeMillis(), valor), "${alarme.nome}: concluída")
        Lembrete.limpar(app, alarme.id)
        return valor
    }

    /** Chamado quando o despertar termina com a missão cumprida. */
    fun concluirLevantar(alarmId: Int, tocouEm: Long) {
        val dia = Instant.ofEpochMilli(tocouEm).atZone(ZoneId.systemDefault()).toLocalDate().toString()
        if (feitos.any { it.dia == dia && it.alarmId == alarmId }) return
        registrar(Feito(dia, alarmId, Tipo.LEVANTAR, System.currentTimeMillis(), Xp.LEVANTAR), "Você levantou")
    }

    /** Missões de hoje que contam para a sequência e ainda não foram concluídas. */
    fun pendentesQueContam(): List<Alarm> {
        val hoje = LocalDate.now()
        val texto = hoje.toString()
        return doDia(hoje).filter { vale(it, hoje) && !feita(it, texto) }
    }

    // ---------- sequência de dias ----------

    private fun diaCompleto(dia: LocalDate): Boolean? {
        val devidas = doDia(dia).filter { vale(it, dia) }
        if (devidas.isEmpty()) return null
        val texto = dia.toString()
        return devidas.all { feita(it, texto) }
    }

    /**
     * Fecha os dias que já acabaram: dia com tudo o que conta concluído soma
     * um; dia com missão que conta sem concluir zera. Dia sem missão que conta
     * não muda nada.
     */
    fun fecharDias() {
        val hoje = LocalDate.now()
        val ontem = hoje.minusDays(1)
        val fechado = try {
            LocalDate.parse(fechadoAte)
        } catch (e: Exception) {
            ontem
        }
        if (!fechado.isBefore(ontem)) {
            if (fechadoAte.isEmpty()) {
                fechadoAte = ontem.toString()
                salvar()
            }
            return
        }
        var total = sequencia
        var cursor = fechado.plusDays(1)
        if (ChronoUnit.DAYS.between(cursor, hoje) > 60L) {
            total = 0
            cursor = hoje.minusDays(60)
        }
        while (cursor.isBefore(hoje)) {
            when (diaCompleto(cursor)) {
                true -> total += 1
                false -> total = 0
                null -> {}
            }
            cursor = cursor.plusDays(1)
        }
        sequencia = total
        if (total > recorde) recorde = total
        fechadoAte = ontem.toString()
        salvar()
        garantirSemana()
    }

    fun hojeCompleto(): Boolean = diaCompleto(LocalDate.now()) == true

    /** Sequência para mostrar: os dias fechados mais o de hoje, se hoje já está completo. */
    fun sequenciaAtual(): Int = sequencia + if (hojeCompleto()) 1 else 0

    // ---------- estudo ----------

    fun metasDe(dia: String): List<Meta> = metas.filter { it.dia == dia }

    private val marcador = Regex("^(?:[-*•–]+\\s*|\\d+[.)]\\s+)")

    /** Acrescenta uma meta por linha de [texto]; devolve quantas entraram. */
    fun adicionarMetas(texto: String, dia: String): Int {
        val linhas = texto.split('\n')
            .map { it.trim().replace(marcador, "").trim() }
            .filter { it.isNotEmpty() }
            .take(30)
        if (linhas.isEmpty()) return 0
        val base = System.currentTimeMillis() * 100L
        metas = metas + linhas.mapIndexed { n, linha -> Meta(base + n, dia, linha.take(240)) }
        salvarMetas()
        return linhas.size
    }

    fun excluirMeta(id: Long) {
        metas = metas.filterNot { it.id == id && it.feitaEm == 0L }
        salvarMetas()
        conferirEstudo()
    }

    /** Metas de dias anteriores que ficaram sem concluir. */
    fun atrasadas(): List<Meta> {
        val hoje = hojeTexto()
        return metas.filter { it.feitaEm == 0L && it.dia < hoje }
    }

    fun trazerAtrasadasParaHoje() {
        val hoje = hojeTexto()
        metas = metas.map { if (it.feitaEm == 0L && it.dia < hoje) it.copy(dia = hoje) else it }
        salvarMetas()
    }

    fun descartarAtrasadas() {
        val hoje = hojeTexto()
        metas = metas.filterNot { it.feitaEm == 0L && it.dia < hoje }
        salvarMetas()
    }

    /** Conclui a meta com o resumo falado ou digitado e guarda no diário. */
    fun concluirMeta(id: Long, resumo: String): Boolean {
        val meta = metas.firstOrNull { it.id == id } ?: return false
        if (meta.feitaEm != 0L) return false
        val agora = System.currentTimeMillis()
        val hoje = hojeTexto()
        metas = metas.map {
            if (it.id == id) it.copy(feitaEm = agora, resumo = resumo.trim().take(4000), dia = hoje) else it
        }.takeLast(3000)
        salvarMetas()
        ganhar(Xp.META, "Meta de estudo concluída")
        salvar()
        conferirEstudo()
        return true
    }

    /** Todas as metas de hoje concluídas fecham a missão de estudo do dia; antes da hora combinada, com bônus. */
    private fun conferirEstudo() {
        val hoje = hojeTexto()
        val lista = metasDe(hoje)
        if (lista.isEmpty() || lista.any { it.feitaEm == 0L }) return
        if (feitos.any { it.dia == hoje && it.tipo == Tipo.ESTUDO }) return
        val limite = Repo.config.studyBonusHour.coerceIn(1, 23)
        val noPrazo = LocalTime.now().hour < limite
        registrar(
            Feito(hoje, ID_ESTUDO, Tipo.ESTUDO, System.currentTimeMillis(), if (noPrazo) Xp.BONUS_ESTUDO else 0),
            if (noPrazo) "Todas as metas antes das $limite h" else "Todas as metas do dia fechadas",
        )
    }

    /** O diário: metas concluídas, da mais recente para a mais antiga. */
    fun diario(): List<Meta> = metas.filter { it.feitaEm != 0L }.sortedByDescending { it.feitaEm }

    // ---------- treino ----------

    private fun diaDe(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDate().toString()

    fun inicioDeHoje(alarmId: Int): Long? = inicios[alarmId]?.takeIf { diaDe(it) == hojeTexto() }

    fun iniciarTreino(alarmId: Int) {
        if (inicioDeHoje(alarmId) != null) return
        val hoje = hojeTexto()
        inicios = inicios.filterValues { diaDe(it) == hoje } + (alarmId to System.currentTimeMillis())
        salvar()
    }

    /** Hora em que o "Concluí" libera: a parte mínima da duração, contada de quando o treino começou. */
    fun liberaEm(alarme: Alarm): Long? {
        val inicio = inicioDeHoje(alarme.id) ?: return null
        val minimo = alarme.minutes.coerceIn(1, 600) * 60_000L * alarme.minPercent.coerceIn(0, 100) / 100L
        return inicio + minimo
    }

    // ---------- missão da semana ----------

    /** Escolhe a missão desta semana, se ainda não há uma. Roda entre os tipos de missão que existem. */
    fun garantirSemana() {
        val hoje = LocalDate.now()
        val inicio = hoje.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (semana?.inicio == inicio.toString()) return
        val tipos = Repo.alarms.filter { it.enabled }.map { it.type }.toSet()
        val opcoes = listOf(Tipo.ESTUDO, Tipo.TREINO, Tipo.LEVANTAR).filter { it in tipos }
        if (opcoes.isEmpty()) {
            if (semana != null) {
                semana = null
                salvar()
            }
            return
        }
        val numero = (inicio.toEpochDay() / 7L).toInt()
        val restantes = 8 - hoje.dayOfWeek.value
        semana = Semana(
            inicio = inicio.toString(),
            desafio = opcoes[numero % opcoes.size],
            alvo = minOf(5, restantes).coerceAtLeast(1),
        )
        salvar()
    }

    fun progressoDaSemana(s: Semana): Int {
        val fim = try {
            LocalDate.parse(s.inicio).plusDays(6).toString()
        } catch (e: Exception) {
            return 0
        }
        return feitos.filter { it.tipo == s.desafio && it.dia >= s.inicio && it.dia <= fim }
            .map { it.dia }
            .distinct()
            .size
    }

    private fun conferirSemana() {
        val s = semana ?: return
        if (s.premiada || progressoDaSemana(s) < s.alvo) return
        semana = s.copy(premiada = true)
        ganhar(Xp.SEMANA, "Missão da semana cumprida")
    }

    // ---------- ideias para depois ----------

    fun anotarIdeia(texto: String) {
        val limpo = texto.trim().take(500)
        if (limpo.isEmpty()) return
        val agora = System.currentTimeMillis()
        ideias = (listOf(Ideia(agora, agora, limpo)) + ideias).take(300)
        salvarIdeias()
    }

    fun excluirIdeia(id: Long) {
        ideias = ideias.filterNot { it.id == id }
        salvarIdeias()
    }

    // ---------- imagem do personagem ----------

    fun arquivoDaImagem(): File = File(app.filesDir, "personagem.jpg")

    /**
     * Copia para dentro do app a imagem escolhida na galeria, reduzida para não
     * pesar na memória. Chamar fora da linha principal; devolve se deu certo.
     */
    fun copiarImagem(uri: Uri): Boolean = try {
        val limites = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, limites) }
        val maior = maxOf(limites.outWidth, limites.outHeight)
        var amostra = 1
        while (maior / (amostra * 2) >= 1600) amostra *= 2
        val opcoes = BitmapFactory.Options().apply { inSampleSize = amostra }
        val lido = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opcoes) }
        if (lido == null) {
            false
        } else {
            val lado = maxOf(lido.width, lido.height)
            val pronto = if (lado > 1600) {
                val fator = 1600f / lado
                Bitmap.createScaledBitmap(lido, (lido.width * fator).toInt(), (lido.height * fator).toInt(), true)
            } else {
                lido
            }
            arquivoDaImagem().outputStream().use { pronto.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            true
        }
    } catch (e: Throwable) {
        false
    }

    /** Passa a usar a imagem copiada ([usar] verdadeiro) ou volta para a que vem no app. */
    fun definirImagem(usar: Boolean) {
        imagem = if (usar) System.currentTimeMillis() else 0L
        salvar()
    }
}
