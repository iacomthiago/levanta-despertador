package com.iacomthiago.levanta

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.TimeZone

/**
 * O documento da sincronização e as regras para juntá-lo, iguais às do site
 * (web/nucleo.js). Tudo aqui é conta pura sobre JSON: não toca em tela, rede,
 * alarme nem armazenamento, e por isso roda também nos testes do computador
 * (NucleoTest), que comparam o resultado com o do site caso a caso.
 *
 * Cada item do documento leva a hora da última mudança ("t"). Juntar dois
 * documentos fica com a mudança mais recente de cada item e nunca perde o que
 * só existe de um lado. A experiência não viaja como número somado: é
 * recalculada dos registros, então nada soma em dobro.
 */
object Nucleo {
    private const val DIA_MS = 86_400_000L

    /** O que a tela precisa saber, calculado do documento. */
    data class Derivado(
        val xp: Int,
        val nivel: Int,
        /** Dias seguidos já fechados (até ontem). */
        val sequencia: Int,
        val sequenciaAtual: Int,
        /** Recorde dos dias já fechados. */
        val recorde: Int,
        val turbos: Int,
        val turboLigado: Boolean,
        val hojeCompleto: Boolean,
    )

    private class Seq(val total: Int, val recorde: Int, val turbos: Int)

    // ---------- datas (sempre no horário do aparelho) ----------

    /** Só os testes mexem aqui: minutos a somar ao horário universal. */
    @Volatile
    var fusoFixo: Int? = null

    private fun fuso(ms: Long): Int = fusoFixo ?: (TimeZone.getDefault().getOffset(ms) / 60_000)

    private fun local(ms: Long): Long = ms + fuso(ms) * 60_000L

    fun diaDeNum(n: Long): String = LocalDate.ofEpochDay(n).toString()

    /** Dia (aaaa-mm-dd) de um instante. */
    fun diaDe(ms: Long): String = diaDeNum(Math.floorDiv(local(ms), DIA_MS))

    fun numDia(dia: String): Long = try {
        LocalDate.parse(dia).toEpochDay()
    } catch (e: Exception) {
        0L
    }

    fun somaDias(dia: String, n: Int): String = diaDeNum(numDia(dia) + n)

    /** 1 = segunda ... 7 = domingo. */
    fun diaDaSemana(dia: String): Int = (Math.floorMod(numDia(dia) + 3L, 7L) + 1L).toInt()

    fun segundaDe(dia: String): String = somaDias(dia, 1 - diaDaSemana(dia))

    fun horaDe(ms: Long): Int = (Math.floorMod(local(ms), DIA_MS) / 3_600_000L).toInt()

    // ---------- leitura do documento ----------

    fun chaves(o: JSONObject?): List<String> {
        if (o == null) return emptyList()
        val lista = ArrayList<String>(o.length())
        val it = o.keys()
        while (it.hasNext()) lista.add(it.next())
        return lista
    }

    /** A parte [nome] do documento, criada vazia se faltar. */
    fun parte(doc: JSONObject, nome: String): JSONObject {
        var p = doc.optJSONObject(nome)
        if (p == null) {
            p = JSONObject()
            doc.put(nome, p)
        }
        return p
    }

    private val PARTES = listOf("base", "missoes", "feitos", "blocos", "turbos", "dias", "semanas", "ideias", "pegos", "cfg")

    fun novoDoc(agora: Long): JSONObject {
        val d = JSONObject().put("v", 1).put("criado", diaDe(agora))
        for (p in PARTES) d.put(p, JSONObject())
        d.put("premios", JSONObject().put("v", PREMIOS_PADRAO).put("t", 0))
        d.put("marco", 0)
        return d
    }

    /** Garante que todas as partes existem, para um documento vindo de fora não quebrar nada. */
    fun arrumar(d: JSONObject): JSONObject {
        for (p in PARTES) parte(d, p)
        d.put("v", 1)
        if (d.opt("criado") !is String) d.put("criado", "")
        val premios = d.optJSONObject("premios")
        if (premios == null || premios.opt("v") !is String) {
            d.put("premios", JSONObject().put("v", PREMIOS_PADRAO).put("t", 0))
        }
        d.put("marco", d.optInt("marco", 0))
        return d
    }

    fun cfgInt(doc: JSONObject, chave: String, padrao: Int): Int {
        val c = parte(doc, "cfg").optJSONObject(chave) ?: return padrao
        return if (c.isNull("v")) padrao else c.optInt("v", padrao)
    }

    fun cfgTexto(doc: JSONObject, chave: String, padrao: String): String {
        val c = parte(doc, "cfg").optJSONObject(chave) ?: return padrao
        return if (c.isNull("v")) padrao else c.optString("v", padrao)
    }

    fun metaDeBlocos(doc: JSONObject): Int = cfgInt(doc, "studyBlocks", 4).coerceIn(1, BLOCOS_POR_DIA)

    fun horaDoBonus(doc: JSONObject): Int = cfgInt(doc, "studyBonusHour", 14).coerceIn(1, 23)

    /** Missões que existem (não apagadas), na ordem do relógio. */
    fun missoes(doc: JSONObject): List<JSONObject> {
        val todas = parte(doc, "missoes")
        val lista = ArrayList<JSONObject>()
        for (id in chaves(todas)) {
            val m = todas.optJSONObject(id) ?: continue
            if (!m.optBoolean("del", false)) lista.add(m)
        }
        return lista.sortedWith(
            compareBy<JSONObject> { it.optInt("hour") * 60 + it.optInt("minute") }.thenBy { it.optInt("id") }
        )
    }

    /** Missões ligadas que caem neste dia da semana. */
    fun doDia(doc: JSONObject, dia: String): List<JSONObject> {
        val d = diaDaSemana(dia)
        return missoes(doc).filter { m ->
            if (!m.optBoolean("enabled", false)) return@filter false
            val dias = m.optJSONArray("days")
            if (dias == null || dias.length() == 0) return@filter true
            (0 until dias.length()).any { dias.optInt(it) == d }
        }
    }

    /**
     * A missão entra na conta da sequência neste dia? No dia em que foi criada,
     * só se a hora dela ainda não tinha passado.
     */
    fun vale(m: JSONObject, dia: String): Boolean {
        if (!m.optBoolean("counts", false)) return false
        val criada = m.optLong("createdAt", 0L)
        if (criada <= 0L) return true
        val diaCriada = diaDe(criada)
        if (dia > diaCriada) return true
        if (dia < diaCriada) return false
        return Math.floorMod(local(criada), DIA_MS) <= (m.optInt("hour") * 60 + m.optInt("minute")) * 60_000L
    }

    fun chaveDoFeito(dia: String, alarmId: Int): String = "$dia|$alarmId"

    /** Feitos que valem (não desfeitos), separados por dia. */
    fun feitosPorDia(doc: JSONObject): Map<String, List<JSONObject>> {
        val feitos = parte(doc, "feitos")
        val mapa = HashMap<String, MutableList<JSONObject>>()
        for (k in chaves(feitos)) {
            val f = feitos.optJSONObject(k) ?: continue
            if (f.optBoolean("del", false)) continue
            mapa.getOrPut(f.optString("dia")) { ArrayList() }.add(f)
        }
        return mapa
    }

    private fun feita(indice: Map<String, List<JSONObject>>, m: JSONObject, dia: String): Boolean {
        val lista = indice[dia] ?: return false
        return if (m.optString("type") == Tipo.ESTUDO) {
            lista.any { it.optString("tipo") == Tipo.ESTUDO }
        } else {
            val id = m.optInt("id")
            lista.any { it.optInt("alarmId") == id }
        }
    }

    /** 1 = dia completo, 0 = faltou missão que conta, 2 = nenhuma missão contava. */
    private fun diaCompleto(doc: JSONObject, indice: Map<String, List<JSONObject>>, dia: String): Int {
        val devidas = doDia(doc, dia).filter { vale(it, dia) }
        if (devidas.isEmpty()) return 2
        return if (devidas.all { feita(indice, it, dia) }) 1 else 0
    }

    /** Números dos blocos de estudo marcados no dia, em ordem. */
    fun blocosDe(doc: JSONObject, dia: String): List<Int> {
        val doDiaB = parte(doc, "blocos").optJSONObject(dia) ?: return emptyList()
        return (1..BLOCOS_POR_DIA).filter { doDiaB.optJSONObject(it.toString())?.optBoolean("on", false) == true }
    }

    fun multiplicador(doc: JSONObject, dia: String): Int = if (parte(doc, "turbos").optLong(dia, 0L) != 0L) 2 else 1

    private fun progressoDaSemana(indice: Map<String, List<JSONObject>>, inicio: String, desafio: String): Int {
        var total = 0
        for (i in 0 until 7) {
            val lista = indice[somaDias(inicio, i)] ?: continue
            if (lista.any { it.optString("tipo") == desafio }) total++
        }
        return total
    }

    fun xpTotal(doc: JSONObject): Int {
        var xp = 0
        val base = parte(doc, "base")
        for (k in chaves(base)) xp += base.optJSONObject(k)?.optInt("xp", 0) ?: 0
        val feitos = parte(doc, "feitos")
        for (k in chaves(feitos)) {
            val f = feitos.optJSONObject(k) ?: continue
            if (!f.optBoolean("del", false)) xp += f.optInt("xp", 0) * multiplicador(doc, f.optString("dia"))
        }
        for (dia in chaves(parte(doc, "blocos"))) xp += blocosDe(doc, dia).size * Xp.BLOCO * multiplicador(doc, dia)
        val semanas = parte(doc, "semanas")
        for (k in chaves(semanas)) if (semanas.optJSONObject(k)?.optBoolean("premiada", false) == true) xp += Xp.SEMANA
        return xp.coerceAtLeast(0)
    }

    private fun primeiroDia(doc: JSONObject, hoje: String): String {
        var primeiro = doc.optString("criado", "").ifEmpty { hoje }
        val base = parte(doc, "base")
        for (k in chaves(base)) {
            val desde = base.optJSONObject(k)?.optString("desde", "") ?: ""
            if (desde.isNotEmpty() && desde < primeiro) primeiro = desde
        }
        return if (primeiro > hoje) hoje else primeiro
    }

    /**
     * Sequência de dias fechados (até ontem), recorde e turbos ganhos por
     * sequência. Um aparelho que entrou na conta já com uma sequência (o tablet)
     * traz o número dele em "base"; a partir do dia em que entrou, vale o maior.
     */
    private fun sequenciaDe(doc: JSONObject, hoje: String): Seq {
        val parteBase = parte(doc, "base")
        val bases = chaves(parteBase).mapNotNull { parteBase.optJSONObject(it) }
        var total = 0
        var recorde = 0
        var turbos = 0
        for (b in bases) recorde = maxOf(recorde, b.optInt("recorde", 0))
        val ontem = numDia(hoje) - 1
        var n = numDia(primeiroDia(doc, hoje))
        if (ontem - n > 800) n = ontem - 800
        val comeco = diaDeNum(n)
        for (b in bases) {
            val desde = b.optString("desde", "")
            if (desde.isNotEmpty() && desde < comeco) total = maxOf(total, b.optInt("seq", 0))
        }
        val dias = parte(doc, "dias")
        while (n <= ontem) {
            val d = diaDeNum(n)
            for (b in bases) if (b.optString("desde", "") == d) total = maxOf(total, b.optInt("seq", 0))
            val r = dias.optJSONObject(d)?.optInt("r", 2) ?: 2
            if (r == 1) {
                total++
                if (total % DIAS_POR_TURBO == 0) turbos++
            } else if (r == 0) {
                total = 0
            }
            recorde = maxOf(recorde, total)
            n++
        }
        for (b in bases) {
            val desde = b.optString("desde", "")
            if (desde.isEmpty() || desde >= hoje) total = maxOf(total, b.optInt("seq", 0))
        }
        recorde = maxOf(recorde, total)
        return Seq(total, recorde, turbos)
    }

    private fun turbosGuardados(doc: JSONObject, seq: Seq): Int {
        var daBase = 0
        var marcoBase = 0
        val base = parte(doc, "base")
        for (k in chaves(base)) {
            val b = base.optJSONObject(k) ?: continue
            daBase += b.optInt("turbos", 0)
            marcoBase = maxOf(marcoBase, b.optInt("marco", 0))
        }
        val porNivel = maxOf(0, doc.optInt("marco", 0) - marcoBase) / NIVEIS_POR_RECOMPENSA
        val turbos = parte(doc, "turbos")
        val usados = chaves(turbos).count { turbos.optLong(it, 0L) != 0L }
        return maxOf(0, daBase + porNivel + seq.turbos - usados)
    }

    fun derivar(doc: JSONObject, agora: Long): Derivado {
        val hoje = diaDe(agora)
        val indice = feitosPorDia(doc)
        val xp = xpTotal(doc)
        val seq = sequenciaDe(doc, hoje)
        val completo = diaCompleto(doc, indice, hoje) == 1
        return Derivado(
            xp = xp,
            nivel = nivelDe(xp),
            sequencia = seq.total,
            sequenciaAtual = seq.total + if (completo) 1 else 0,
            recorde = seq.recorde,
            turbos = turbosGuardados(doc, seq),
            turboLigado = multiplicador(doc, hoje) == 2,
            hojeCompleto = completo,
        )
    }

    // ---------- ajuste: o que decorre dos dados ----------

    /**
     * Deixa o documento coerente depois de uma mudança ou de uma junção: missão
     * da semana, fechamento do estudo de hoje, dias encerrados e recompensas de
     * nível. Com os mesmos dados, qualquer aparelho chega ao mesmo resultado.
     * Devolve se mudou alguma coisa.
     */
    fun ajustar(doc: JSONObject, agora: Long): Boolean {
        var mudou = false
        val hoje = diaDe(agora)

        // 1. Missão da semana: escolhida uma vez, entre os tipos de missão que existem.
        val inicio = segundaDe(hoje)
        val semanas = parte(doc, "semanas")
        if (semanas.optJSONObject(inicio) == null) {
            val tipos = missoes(doc).filter { it.optBoolean("enabled", false) }.map { it.optString("type") }.toSet()
            val opcoes = listOf(Tipo.ESTUDO, Tipo.TREINO, Tipo.LEVANTAR).filter { it in tipos }
            if (opcoes.isNotEmpty()) {
                val numero = Math.floorDiv(numDia(inicio), 7L)
                val restantes = 8 - diaDaSemana(hoje)
                semanas.put(
                    inicio,
                    JSONObject()
                        .put("desafio", opcoes[Math.floorMod(numero, opcoes.size.toLong()).toInt()])
                        .put("alvo", minOf(5, restantes).coerceAtLeast(1))
                        .put("c", agora)
                        .put("premiada", false)
                        .put("t", agora),
                )
                mudou = true
            }
        }

        // 2. Estudo de hoje: fecha com a meta de blocos; antes da hora combinada, com bônus.
        val meta = metaDeBlocos(doc)
        val doDiaB = parte(doc, "blocos").optJSONObject(hoje)
        val horas = ArrayList<Long>()
        if (doDiaB != null) {
            for (n in 1..BLOCOS_POR_DIA) {
                val b = doDiaB.optJSONObject(n.toString()) ?: continue
                if (b.optBoolean("on", false)) horas.add(b.optLong("t", 0L))
            }
        }
        horas.sort()
        val feitos = parte(doc, "feitos")
        val chave = chaveDoFeito(hoje, Jogo.ID_ESTUDO)
        val atual = feitos.optJSONObject(chave)
        val vivo = atual != null && !atual.optBoolean("del", false)
        if (horas.size >= meta) {
            val quando = horas[meta - 1].let { if (it == 0L) agora else it }
            val bonus = if (diaDe(quando) == hoje && horaDe(quando) < horaDoBonus(doc)) Xp.BONUS_ESTUDO else 0
            if (!vivo || atual!!.optInt("xp", 0) != bonus) {
                feitos.put(
                    chave,
                    JSONObject()
                        .put("dia", hoje)
                        .put("alarmId", Jogo.ID_ESTUDO)
                        .put("tipo", Tipo.ESTUDO)
                        .put("em", quando)
                        .put("xp", bonus)
                        .put("t", agora)
                        .put("del", false),
                )
                mudou = true
            }
        } else if (vivo) {
            feitos.put(
                chave,
                JSONObject()
                    .put("dia", hoje)
                    .put("alarmId", Jogo.ID_ESTUDO)
                    .put("tipo", Tipo.ESTUDO)
                    .put("em", atual!!.optLong("em", 0L))
                    .put("xp", atual.optInt("xp", 0))
                    .put("t", agora)
                    .put("del", true),
            )
            mudou = true
        }

        val indice = feitosPorDia(doc)

        // 3. Missão da semana cumprida (ou desfeita, se um bloco foi desmarcado).
        val s = semanas.optJSONObject(inicio)
        if (s != null) {
            val cumprida = progressoDaSemana(indice, inicio, s.optString("desafio")) >= s.optInt("alvo", 5)
            if (s.optBoolean("premiada", false) != cumprida) {
                s.put("premiada", cumprida)
                s.put("t", agora)
                mudou = true
            }
        }

        // 4. Dias encerrados. Um dia já fechado só é refeito se chegaram registros novos dele.
        val dias = parte(doc, "dias")
        val hojeN = numDia(hoje)
        var d = numDia(primeiroDia(doc, hoje))
        if (hojeN - 1 - d > 800) d = hojeN - 1 - 800
        while (d < hojeN) {
            val dia = diaDeNum(d)
            val reg = dias.optJSONObject(dia)
            val qtd = indice[dia]?.size ?: 0
            if (reg == null || reg.optInt("n", 0) != qtd) {
                dias.put(dia, JSONObject().put("r", diaCompleto(doc, indice, dia)).put("n", qtd).put("t", agora))
                mudou = true
            }
            d++
        }

        // 5. Recompensa de nível: cada múltiplo de 5 alcançado fica pago para sempre.
        val alvo = nivelDe(xpTotal(doc)) / NIVEIS_POR_RECOMPENSA * NIVEIS_POR_RECOMPENSA
        if (alvo > doc.optInt("marco", 0)) {
            doc.put("marco", alvo)
            mudou = true
        }
        return mudou
    }

    // ---------- junção de dois documentos ----------

    private fun tempo(x: Any?): Long = (x as? JSONObject)?.optLong("t", 0L) ?: 0L

    private fun marcado(x: Any?, campo: String): Boolean = (x as? JSONObject)?.optBoolean(campo, false) == true

    /** O mais recente dos dois; no empate, o que tiver [prefere] ligado, senão o primeiro. */
    private fun maisNovo(a: Any?, b: Any?, prefere: String?): Any? {
        if (a == null) return b
        if (b == null) return a
        val ta = tempo(a)
        val tb = tempo(b)
        if (tb > ta) return b
        if (ta > tb) return a
        if (prefere != null && marcado(b, prefere) && !marcado(a, prefere)) return b
        return a
    }

    private fun unir(a: JSONObject?, b: JSONObject?, prefere: String? = null): JSONObject {
        val r = JSONObject()
        if (a != null) for (k in chaves(a)) r.put(k, maisNovo(a.opt(k), b?.opt(k), prefere))
        if (b != null) for (k in chaves(b)) if (!r.has(k)) r.put(k, b.opt(k))
        return r
    }

    private fun unirMenor(a: JSONObject, b: JSONObject): JSONObject {
        val r = JSONObject()
        for (k in chaves(a)) r.put(k, a.opt(k))
        for (k in chaves(b)) {
            val va = r.optLong(k, 0L)
            val vb = b.optLong(k, 0L)
            r.put(k, if (va > 0 && vb > 0) minOf(va, vb) else if (va > 0) va else vb)
        }
        return r
    }

    /** A missão da semana é a de quem a criou primeiro; o "cumprida" é o mais recente. */
    private fun juntarSemana(a: JSONObject?, b: JSONObject?): JSONObject? {
        if (a == null) return b
        if (b == null) return a
        val primeira = if (b.optLong("c", 0L) < a.optLong("c", 0L)) b else a
        val recente = if (tempo(b) > tempo(a)) b else a
        return JSONObject()
            .put("desafio", primeira.opt("desafio"))
            .put("alvo", primeira.opt("alvo"))
            .put("c", primeira.opt("c"))
            .put("premiada", recente.optBoolean("premiada", false))
            .put("t", tempo(recente))
    }

    /**
     * Junta o documento deste aparelho com o que veio de outro. Cada item fica
     * com a mudança mais recente; o que só existe de um lado é mantido.
     */
    fun juntar(meu: JSONObject, outro: JSONObject?): JSONObject {
        val a = arrumar(JSONObject(meu.toString()))
        if (outro == null) return a
        val b = arrumar(JSONObject(outro.toString()))
        val r = JSONObject().put("v", 1)
        val ca = a.optString("criado", "")
        val cb = b.optString("criado", "")
        r.put("criado", if (ca.isNotEmpty() && cb.isNotEmpty()) minOf(ca, cb) else ca.ifEmpty { cb })
        r.put("base", unir(a.optJSONObject("base"), b.optJSONObject("base")))
        r.put("missoes", unir(a.optJSONObject("missoes"), b.optJSONObject("missoes"), "del"))
        r.put("feitos", unir(a.optJSONObject("feitos"), b.optJSONObject("feitos"), "del"))
        val blocos = JSONObject()
        val ba = parte(a, "blocos")
        val bb = parte(b, "blocos")
        for (dia in chaves(ba)) blocos.put(dia, unir(ba.optJSONObject(dia), bb.optJSONObject(dia), "on"))
        for (dia in chaves(bb)) if (!blocos.has(dia)) blocos.put(dia, bb.optJSONObject(dia) ?: JSONObject())
        r.put("blocos", blocos)
        r.put("turbos", unirMenor(parte(a, "turbos"), parte(b, "turbos")))
        r.put("dias", unir(a.optJSONObject("dias"), b.optJSONObject("dias")))
        val semanas = JSONObject()
        val sa = parte(a, "semanas")
        val sb = parte(b, "semanas")
        for (k in chaves(sa)) semanas.put(k, juntarSemana(sa.optJSONObject(k), sb.optJSONObject(k)))
        for (k in chaves(sb)) if (!semanas.has(k)) semanas.put(k, sb.opt(k))
        r.put("semanas", semanas)
        r.put("ideias", unir(a.optJSONObject("ideias"), b.optJSONObject("ideias"), "del"))
        r.put("premios", maisNovo(a.optJSONObject("premios"), b.optJSONObject("premios"), null))
        r.put("pegos", unirMenor(parte(a, "pegos"), parte(b, "pegos")))
        r.put("cfg", unir(a.optJSONObject("cfg"), b.optJSONObject("cfg")))
        r.put("marco", maxOf(a.optInt("marco", 0), b.optInt("marco", 0)))
        return r
    }

    /** Comparação de conteúdo, sem ligar para a ordem das chaves. */
    fun igual(a: Any?, b: Any?): Boolean {
        val x = if (a == JSONObject.NULL) null else a
        val y = if (b == JSONObject.NULL) null else b
        if (x == null || y == null) return x == null && y == null
        if (x is JSONObject) {
            if (y !is JSONObject || x.length() != y.length()) return false
            for (k in chaves(x)) if (!y.has(k) || !igual(x.opt(k), y.opt(k))) return false
            return true
        }
        if (x is JSONArray) {
            if (y !is JSONArray || x.length() != y.length()) return false
            for (i in 0 until x.length()) if (!igual(x.opt(i), y.opt(i))) return false
            return true
        }
        if (x is Number && y is Number) return x.toDouble() == y.toDouble()
        if (x is Number || y is Number) return false
        return x == y
    }
}
