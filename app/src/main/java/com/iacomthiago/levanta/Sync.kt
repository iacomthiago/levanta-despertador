package com.iacomthiago.levanta

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.ProtocolException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** Endereço do Disciplina no navegador (celular e computador). */
const val SITE_DISCIPLINA = "iacomthiago.github.io/levanta-despertador"

/**
 * Sincronização automática com o site. O progresso fica num gist secreto da
 * conta do GitHub da pessoa (o mesmo esquema do HoloCards); a chave é colada
 * ou lida por QR code uma única vez.
 *
 * Fica longe do despertador de propósito:
 *  - nada no AlarmService, no AlarmActivity nem no Scheduler chama este arquivo;
 *    quem dispara a sincronização é só a tela principal (abrir, sair e mudar algo);
 *  - com um despertar em andamento ela não roda;
 *  - a rede roda fora da linha principal, com tempo limite, e qualquer falha é
 *    engolida: fica para a próxima vez;
 *  - os alarmes de levantar nunca são alterados pelo que vem de fora. O tablet
 *    só publica uma cópia deles, para a sequência de dias bater nos dois lados;
 *  - o estado dela mora em outro arquivo de preferências ("levanta_sync"), para
 *    não pesar no arquivo que o alarme lê.
 *
 * Com a sincronização desligada, nada aqui é executado e o app se comporta
 * exatamente como antes.
 */
object Sync {
    private const val TAG = "Levanta"
    private const val API = "https://api.github.com"
    private const val ARQUIVO = "disciplina.json"
    private const val DESCRICAO = "Disciplina: progresso sincronizado entre aparelhos"

    private var prefs: SharedPreferences? = null
    private var app: Context? = null

    var ligada by mutableStateOf(false)
        private set

    /** Texto curto sobre a última tentativa, para a tela de ajustes. */
    var situacao by mutableStateOf("")
        private set

    /** Verdadeiro enquanto confere a chave para ligar. */
    var ligando by mutableStateOf(false)
        private set

    /** Aviso do que deu errado ao tentar ligar; vazio se nada. */
    var avisoDeLigar by mutableStateOf("")

    private var rodando = false
    private var denovo = false
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private class Resposta(val status: Int, val corpo: String, val etag: String?)

    private class ErroDeChave : Exception()

    fun init(context: Context) {
        if (prefs != null) return
        val contexto = context.applicationContext
        app = contexto
        val p = contexto.getSharedPreferences("levanta_sync", Context.MODE_PRIVATE)
        prefs = p
        ligada = !p.getString("chave", "").isNullOrEmpty() && p.contains("doc")
        situacao = if (ligada) textoDaUltima(p.getLong("ultima", 0L)) else ""
    }

    private fun textoDaUltima(quando: Long): String =
        if (quando <= 0L) "Ainda não sincronizou." else "Última sincronização: ${diaCurto(quando)} às ${hhmm(quando)}."

    /**
     * Um número que muda quando muda qualquer coisa que a sincronização leva.
     * A tela principal lê isto para mandar subir sozinho pouco depois de cada mudança.
     */
    fun marcaDoEstado(): Int {
        val c = Repo.config
        return listOf(
            Jogo.xp, Jogo.blocos, Jogo.feitos.size, Jogo.ideias, Jogo.premios, Jogo.premiosPegos, Jogo.turboEm,
            Jogo.semana, Repo.alarms, c.studyBlocks, c.studyBonusHour, c.examDate,
        ).hashCode()
    }

    // ---------- ligar e desligar ----------

    /** Tira a chave de um QR code do site (um endereço com "#k=...") ou aceita a própria chave colada. */
    fun chaveDe(texto: String): String {
        val limpo = texto.trim()
        val marca = limpo.indexOf("k=")
        if (!limpo.startsWith("http") || marca < 0) return limpo
        val valor = limpo.substring(marca + 2).substringBefore('&')
        return try {
            URLDecoder.decode(valor, "UTF-8")
        } catch (e: Exception) {
            valor
        }
    }

    /**
     * Entra na conta com a chave lida ou colada. Confere a chave no GitHub, monta
     * o documento com o progresso que já existe no tablet e começa a sincronizar.
     */
    fun ligar(context: Context, textoLido: String, aoTerminar: (Boolean) -> Unit = {}) {
        init(context)
        val p = prefs ?: return
        val contexto = app ?: return
        if (ligando) return
        val chave = chaveDe(textoLido)
        if (chave.length < 20) {
            avisoDeLigar = "A chave está incompleta. Leia o QR code de novo ou cole a chave inteira (começa com ghp_)."
            aoTerminar(false)
            return
        }
        ligando = true
        avisoDeLigar = ""
        escopo.launch {
            var ok = false
            try {
                val achado = withContext(Dispatchers.IO) {
                    val id = achar(chave)
                    val remoto = if (id == null) null else ler(chave, id, null)
                    Pair(id, remoto)
                }
                val id = achado.first
                val remoto = achado.second?.takeIf { it.status == 200 }?.let { Nucleo.arrumar(JSONObject(it.corpo)) }
                val agora = System.currentTimeMillis()
                val aparelho = p.getString("aparelho", null) ?: UUID.randomUUID().toString().take(8)
                guardarCopiaDeSeguranca(contexto, p, agora)
                Jogo.fecharDias()
                val doc = Nucleo.novoDoc(agora)
                capturar(doc, agora, primeira = true)
                // O que o tablet já tinha e que não está nos registros entra uma vez só, como base.
                val jaTemBase = remoto?.optJSONObject("base")?.has(aparelho) == true
                if (!jaTemBase) {
                    val hoje = Nucleo.diaDe(agora)
                    Nucleo.parte(doc, "base").put(
                        aparelho,
                        JSONObject()
                            .put("xp", Jogo.xp - Nucleo.xpTotal(doc))
                            .put("seq", Jogo.sequencia)
                            .put("recorde", Jogo.recorde)
                            .put("turbos", Jogo.turbos + if (Jogo.turboEm == hoje) 1 else 0)
                            .put("marco", Jogo.marcoPago)
                            .put("desde", hoje)
                            .put("t", agora),
                    )
                }
                val edicao = p.edit()
                    .putString("aparelho", aparelho)
                    .putString("chave", chave)
                    .putString("doc", doc.toString())
                    .putBoolean("sujo", true)
                    .remove("etag")
                    .remove("ultima")
                if (id == null) edicao.remove("gist") else edicao.putString("gist", id)
                edicao.commit()
                ligada = true
                situacao = "Ligada. Sincronizando…"
                ok = true
            } catch (e: ErroDeChave) {
                avisoDeLigar = "O GitHub não aceitou essa chave. Gere outra no site e leia o QR code de novo."
            } catch (e: Throwable) {
                Log.w(TAG, "Não consegui ligar a sincronização", e)
                avisoDeLigar = "Não consegui falar com o GitHub. Verifique a internet do tablet e tente de novo."
            }
            ligando = false
            aoTerminar(ok)
            if (ok) pedir(contexto)
        }
    }

    /** Para de sincronizar neste tablet. Não apaga nada, nem aqui nem na conta. */
    fun desligar(context: Context) {
        init(context)
        prefs?.edit()?.remove("chave")?.remove("gist")?.remove("etag")?.remove("doc")?.remove("sujo")?.remove("ultima")?.commit()
        ligada = false
        situacao = ""
        avisoDeLigar = ""
    }

    /** Guarda como o progresso estava antes de ligar, para o caso de alguma junção sair errada. */
    private fun guardarCopiaDeSeguranca(context: Context, p: SharedPreferences, agora: Long) {
        val principal = context.getSharedPreferences("levanta", Context.MODE_PRIVATE)
        val copia = JSONObject().put("em", agora)
        for (chave in listOf("jogo", "feitos", "blocos", "ideias", "alarms", "config")) {
            copia.put(chave, principal.getString(chave, null) ?: continue)
        }
        p.edit().putString("copia_de_antes", copia.toString()).commit()
    }

    // ---------- rodada de sincronização ----------

    /**
     * Pede uma rodada: envia o que mudou aqui e busca o que mudou nos outros
     * aparelhos. Volta na hora; o trabalho acontece depois, sem travar a tela.
     * Sem internet, não faz nada e tenta de novo na próxima vez.
     */
    fun pedir(context: Context) {
        try {
            init(context)
            if (!ligada) return
            // Despertar em andamento: a sincronização espera.
            if (Repo.session != null) return
            if (rodando) {
                denovo = true
                return
            }
            rodando = true
            escopo.launch {
                try {
                    rodada()
                } catch (e: ErroDeChave) {
                    situacao = "A chave do GitHub parou de funcionar. Desligue e ligue de novo com uma chave nova."
                } catch (e: Throwable) {
                    Log.w(TAG, "Sincronização falhou; fica para a próxima", e)
                    situacao = "Sem conexão agora. Fica guardado aqui e sobe sozinho depois."
                }
                rodando = false
                if (denovo) {
                    denovo = false
                    delay(1500L)
                    app?.let { pedir(it) }
                }
            }
        } catch (e: Throwable) {
            // nunca deixa um erro daqui chegar à tela
            rodando = false
        }
    }

    private fun lerDoc(p: SharedPreferences): JSONObject? = try {
        p.getString("doc", null)?.let { Nucleo.arrumar(JSONObject(it)) }
    } catch (e: Exception) {
        null
    }

    private suspend fun rodada() {
        val p = prefs ?: return
        val contexto = app ?: return
        val chave = p.getString("chave", null) ?: return
        var doc = lerDoc(p) ?: return

        // 1. O que mudou aqui desde a última vez entra no documento.
        var agora = System.currentTimeMillis()
        val mudouAqui = capturar(doc, agora, primeira = false)
        val ajustou = Nucleo.ajustar(doc, agora)
        if (mudouAqui || ajustou) {
            if (ajustou) aplicar(contexto, doc, agora)
            p.edit().putString("doc", doc.toString()).putBoolean("sujo", true).commit()
        }

        // 2. Busca o documento da conta (ou cria, se este é o primeiro aparelho).
        var id = p.getString("gist", null)
        if (id == null) {
            id = withContext(Dispatchers.IO) { achar(chave) }
            if (id == null) {
                val corpo = doc.toString()
                id = withContext(Dispatchers.IO) { criar(chave, corpo) }
                p.edit().putString("gist", id).remove("etag").putBoolean("sujo", false).commit()
                terminou(p)
                return
            }
            p.edit().putString("gist", id).remove("etag").commit()
        }
        val gist = id
        val etag = p.getString("etag", null)
        val resposta = withContext(Dispatchers.IO) { ler(chave, gist, etag) }
        if (resposta.status == 404) {
            p.edit().remove("gist").remove("etag").commit()
            denovo = true
            return
        }

        // 3. Junta com o que veio e aplica no app.
        if (resposta.status == 200) {
            // O despertador começou enquanto a rede respondia: deixa para depois.
            if (Repo.session != null) return
            val bruto = JSONObject(resposta.corpo)
            if (bruto.optInt("v", 1) > 1) {
                situacao = "Esta conta foi usada numa versão mais nova. Atualize o app para voltar a sincronizar."
                return
            }
            val remoto = Nucleo.arrumar(bruto)
            agora = System.currentTimeMillis()
            doc = lerDoc(p) ?: return
            capturar(doc, agora, primeira = false)
            val junto = Nucleo.juntar(doc, remoto)
            Nucleo.ajustar(junto, agora)
            aplicar(contexto, junto, agora)
            doc = junto
            val edicao = p.edit().putString("doc", doc.toString()).putBoolean("sujo", !Nucleo.igual(junto, remoto))
            if (resposta.etag != null) edicao.putString("etag", resposta.etag) else edicao.remove("etag")
            edicao.commit()
        } else if (resposta.status != 304) {
            throw IllegalStateException("GitHub respondeu ${resposta.status}")
        }

        // 4. Envia, se este aparelho tem algo que a conta ainda não tem.
        if (p.getBoolean("sujo", false)) {
            val corpo = doc.toString()
            val status = withContext(Dispatchers.IO) { gravar(chave, gist, corpo) }
            if (status == 404) {
                p.edit().remove("gist").remove("etag").commit()
                denovo = true
                return
            }
            // Mudou alguma coisa aqui enquanto enviava? Então ainda há o que subir.
            val depois = lerDoc(p) ?: return
            val mudouDeNovo = capturar(depois, System.currentTimeMillis(), primeira = false)
            val edicao = p.edit().remove("etag").putBoolean("sujo", mudouDeNovo)
            if (mudouDeNovo) {
                edicao.putString("doc", depois.toString())
                denovo = true
            }
            edicao.commit()
        }
        terminou(p)
    }

    private fun terminou(p: SharedPreferences) {
        val agora = System.currentTimeMillis()
        p.edit().putLong("ultima", agora).apply()
        situacao = textoDaUltima(agora)
    }

    // ---------- do app para o documento ----------

    private fun missaoJson(a: Alarm, t: Long): JSONObject = JSONObject()
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
        .put("t", t)
        .put("del", false)

    private fun alarmeDe(m: JSONObject): Alarm? {
        val tipo = m.optString("type")
        if (tipo !in Tipo.todos) return null
        val dias = m.optJSONArray("days")
        return Alarm(
            id = m.optInt("id"),
            hour = m.optInt("hour").coerceIn(0, 23),
            minute = m.optInt("minute").coerceIn(0, 59),
            days = if (dias == null) emptySet() else (0 until dias.length()).map { dias.optInt(it) }.filter { it in 1..7 }.toSet(),
            enabled = m.optBoolean("enabled", true),
            label = m.optString("label", ""),
            type = tipo,
            counts = m.optBoolean("counts", true),
            rings = m.optBoolean("rings", true),
            minutes = m.optInt("minutes", 30),
            minPercent = m.optInt("minPercent", 60),
            url = m.optString("url", ""),
            createdAt = m.optLong("createdAt", 0L),
        )
    }

    /**
     * Leva para o documento o que mudou no app desde a última vez, carimbando a
     * hora em cada item mudado. Devolve se mudou alguma coisa. Na [primeira] vez
     * (ao ligar), prêmios e ajustes entram com carimbo antigo, para os da conta,
     * se já existirem, valerem mais.
     */
    private fun capturar(doc: JSONObject, agora: Long, primeira: Boolean): Boolean {
        var mudou = false
        val hoje = Nucleo.diaDe(agora)
        val carimboDeAjuste = if (primeira) 1L else agora

        // Turbo ligado hoje.
        val turbos = Nucleo.parte(doc, "turbos")
        if (Jogo.turboEm == hoje && turbos.optLong(hoje, 0L) == 0L) {
            turbos.put(hoje, agora)
            mudou = true
        }

        // Missões. As de levantar vão só como cópia, para a conta da sequência.
        val missoes = Nucleo.parte(doc, "missoes")
        val locais = HashSet<String>()
        for (a in Repo.alarms) {
            val k = a.id.toString()
            locais += k
            val atual = missoes.optJSONObject(k)
            if (atual == null || atual.optBoolean("del", false) || alarmeDe(atual) != a) {
                missoes.put(k, missaoJson(a, agora))
                mudou = true
            }
        }
        for (k in Nucleo.chaves(missoes)) {
            val m = missoes.optJSONObject(k) ?: continue
            if (!m.optBoolean("del", false) && k !in locais) {
                m.put("del", true).put("t", agora)
                mudou = true
            }
        }

        // Missões concluídas. O valor guardado é o de antes do turbo.
        val feitos = Nucleo.parte(doc, "feitos")
        val chavesLocais = HashSet<String>()
        var estudoDeHojeEm = 0L
        for (f in Jogo.feitos) {
            val k = Nucleo.chaveDoFeito(f.dia, f.alarmId)
            chavesLocais += k
            if (f.dia == hoje && f.tipo == Tipo.ESTUDO) estudoDeHojeEm = f.em
            val atual = feitos.optJSONObject(k)
            if (atual == null || atual.optBoolean("del", false)) {
                feitos.put(
                    k,
                    JSONObject()
                        .put("dia", f.dia)
                        .put("alarmId", f.alarmId)
                        .put("tipo", f.tipo)
                        .put("em", f.em)
                        .put("xp", f.xp / Nucleo.multiplicador(doc, f.dia))
                        .put("t", agora)
                        .put("del", false),
                )
                mudou = true
            }
        }
        // O fechamento do estudo de hoje pode ter sido desfeito aqui (um bloco desmarcado).
        for (k in Nucleo.chaves(feitos)) {
            val f = feitos.optJSONObject(k) ?: continue
            if (!f.optBoolean("del", false) && f.optString("dia") == hoje && f.optString("tipo") == Tipo.ESTUDO && k !in chavesLocais) {
                f.put("del", true).put("t", agora)
                mudou = true
            }
        }

        // Blocos de estudo, um por um.
        val blocos = Nucleo.parte(doc, "blocos")
        for ((dia, marcados) in Jogo.blocos) {
            var doDia = blocos.optJSONObject(dia)
            // Ao ligar, os blocos de hoje ficam com a hora em que a meta foi batida, para o bônus não mudar.
            val carimbo = if (primeira && dia == hoje && estudoDeHojeEm > 0L) estudoDeHojeEm else agora
            for (n in 1..BLOCOS_POR_DIA) {
                val aqui = n in marcados
                val noDoc = doDia?.optJSONObject(n.toString())?.optBoolean("on", false) == true
                if (aqui != noDoc) {
                    if (doDia == null) {
                        doDia = JSONObject()
                        blocos.put(dia, doDia)
                    }
                    doDia.put(n.toString(), JSONObject().put("on", aqui).put("t", carimbo))
                    mudou = true
                }
            }
        }

        // Ideias.
        val ideias = Nucleo.parte(doc, "ideias")
        val ids = HashSet<String>()
        for (i in Jogo.ideias) {
            val k = i.id.toString()
            ids += k
            val atual = ideias.optJSONObject(k)
            if (atual == null || atual.optBoolean("del", false)) {
                ideias.put(k, JSONObject().put("id", i.id).put("em", i.em).put("texto", i.texto).put("t", agora).put("del", false))
                mudou = true
            }
        }
        // O app guarda no máximo 300 ideias; cheio, a falta de uma não quer dizer que foi apagada.
        if (Jogo.ideias.size < 300) {
            for (k in Nucleo.chaves(ideias)) {
                val i = ideias.optJSONObject(k) ?: continue
                if (!i.optBoolean("del", false) && k !in ids) {
                    i.put("del", true).put("t", agora)
                    mudou = true
                }
            }
        }

        // Prêmios, prêmios já pegos e ajustes de estudo.
        if (doc.optJSONObject("premios")?.optString("v") != Jogo.premios) {
            doc.put("premios", JSONObject().put("v", Jogo.premios).put("t", carimboDeAjuste))
            mudou = true
        }
        val pegos = Nucleo.parte(doc, "pegos")
        for (nivel in Jogo.premiosPegos) {
            if (pegos.optLong(nivel.toString(), 0L) == 0L) {
                pegos.put(nivel.toString(), agora)
                mudou = true
            }
        }
        val cfg = Nucleo.parte(doc, "cfg")
        val c = Repo.config
        if (cfg.optJSONObject("studyBlocks")?.optInt("v", -1) != c.studyBlocks) {
            cfg.put("studyBlocks", JSONObject().put("v", c.studyBlocks).put("t", carimboDeAjuste))
            mudou = true
        }
        if (cfg.optJSONObject("studyBonusHour")?.optInt("v", -1) != c.studyBonusHour) {
            cfg.put("studyBonusHour", JSONObject().put("v", c.studyBonusHour).put("t", carimboDeAjuste))
            mudou = true
        }
        if (cfg.optJSONObject("examDate")?.optString("v") != c.examDate) {
            cfg.put("examDate", JSONObject().put("v", c.examDate).put("t", carimboDeAjuste))
            mudou = true
        }

        // Missão da semana e maior nível de recompensa já pago.
        val s = Jogo.semana
        if (s != null) {
            val semanas = Nucleo.parte(doc, "semanas")
            val atual = semanas.optJSONObject(s.inicio)
            if (atual == null) {
                semanas.put(
                    s.inicio,
                    JSONObject().put("desafio", s.desafio).put("alvo", s.alvo).put("c", agora).put("premiada", s.premiada).put("t", agora),
                )
                mudou = true
            } else if (atual.optBoolean("premiada", false) != s.premiada) {
                atual.put("premiada", s.premiada).put("t", agora)
                mudou = true
            }
        }
        if (Jogo.marcoPago > doc.optInt("marco", 0)) {
            doc.put("marco", Jogo.marcoPago)
            mudou = true
        }
        return mudou
    }

    // ---------- do documento para o app ----------

    /** Passa para o app o resultado da junção. Os alarmes de levantar ficam como estão. */
    private fun aplicar(context: Context, doc: JSONObject, agora: Long) {
        val hoje = Nucleo.diaDe(agora)
        val v = Nucleo.derivar(doc, agora)

        // Missões de estudo, treino e simples: valem as do documento.
        val locais = Repo.alarms
        val deLevantar = locais.filter { it.type == Tipo.LEVANTAR }
        val idsDeLevantar = deLevantar.map { it.id }.toSet()
        val outras = Nucleo.missoes(doc).mapNotNull { alarmeDe(it) }.filter { it.type != Tipo.LEVANTAR && it.id !in idsDeLevantar }
        val novas = deLevantar + outras
        if (novas.toSet() != locais.toSet()) {
            val ficaram = novas.map { it.id }.toSet()
            Repo.replaceAlarms(novas)
            for (a in locais) if (a.id !in ficaram) Scheduler.cancel(context, a.id)
            Scheduler.rescheduleAll(context)
        }

        // Ajustes de estudo e data da prova.
        val c = Repo.config
        val novo = c.copy(
            studyBlocks = Nucleo.cfgInt(doc, "studyBlocks", c.studyBlocks),
            studyBonusHour = Nucleo.cfgInt(doc, "studyBonusHour", c.studyBonusHour),
            examDate = Nucleo.cfgTexto(doc, "examDate", c.examDate),
        )
        if (novo != c) Repo.updateConfig(novo)

        val parteFeitos = Nucleo.parte(doc, "feitos")
        val feitos = Nucleo.chaves(parteFeitos).mapNotNull { parteFeitos.optJSONObject(it) }
            .filter { !it.optBoolean("del", false) }
            .map {
                val dia = it.optString("dia")
                Feito(dia, it.optInt("alarmId"), it.optString("tipo", Tipo.LEVANTAR), it.optLong("em", 0L), it.optInt("xp", 0) * Nucleo.multiplicador(doc, dia))
            }
            .sortedBy { it.em }

        val parteBlocos = Nucleo.parte(doc, "blocos")
        val dias = Nucleo.chaves(parteBlocos).sorted().takeLast(400)
        val blocos = dias.associateWith { Nucleo.blocosDe(doc, it).toSet() }.filter { it.value.isNotEmpty() || it.key == hoje }

        val parteIdeias = Nucleo.parte(doc, "ideias")
        val ideias = Nucleo.chaves(parteIdeias).mapNotNull { parteIdeias.optJSONObject(it) }
            .filter { !it.optBoolean("del", false) }
            .map { Ideia(it.optLong("id"), it.optLong("em", 0L), it.optString("texto", "")) }
            .sortedWith(compareByDescending<Ideia> { it.em }.thenByDescending { it.id })
            .take(300)

        val partePegos = Nucleo.parte(doc, "pegos")
        val pegos = Nucleo.chaves(partePegos).filter { partePegos.optLong(it, 0L) != 0L }.mapNotNull { it.toIntOrNull() }.toSet()

        val inicio = Nucleo.segundaDe(hoje)
        val s = Nucleo.parte(doc, "semanas").optJSONObject(inicio)
        val semana = if (s == null) null else Semana(inicio, s.optString("desafio", Tipo.TREINO), s.optInt("alvo", 5), s.optBoolean("premiada", false))

        Jogo.aplicarSync(
            novoXp = v.xp,
            novosFeitos = feitos,
            novosBlocos = blocos,
            novosTurbos = v.turbos,
            novoTurboEm = if (v.turboLigado) hoje else Jogo.turboEm,
            novoMarco = maxOf(doc.optInt("marco", 0), Jogo.marcoPago),
            novosPremios = doc.optJSONObject("premios")?.optString("v", "") ?: "",
            novosPegos = pegos,
            novasIdeias = ideias,
            novaSequencia = v.sequencia,
            novoRecorde = v.recorde,
            novaSemana = semana,
        )
    }

    // ---------- conversa com o GitHub (sempre fora da linha principal) ----------

    private fun pedirHttp(chave: String, metodo: String, caminho: String, corpo: String?, etag: String?): Resposta {
        val conexao = URL(API + caminho).openConnection() as HttpURLConnection
        conexao.connectTimeout = 15_000
        conexao.readTimeout = 20_000
        try {
            conexao.requestMethod = metodo
        } catch (e: ProtocolException) {
            // Aparelho que não conhece PATCH: o GitHub aceita POST no mesmo endereço.
            conexao.requestMethod = "POST"
        }
        conexao.setRequestProperty("Accept", "application/vnd.github+json")
        conexao.setRequestProperty("Authorization", "Bearer $chave")
        conexao.setRequestProperty("User-Agent", "Disciplina-Android")
        if (etag != null) conexao.setRequestProperty("If-None-Match", etag)
        try {
            if (corpo != null) {
                conexao.doOutput = true
                conexao.setRequestProperty("Content-Type", "application/json")
                conexao.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
            }
            val status = conexao.responseCode
            if (status == 401) throw ErroDeChave()
            val fluxo = if (status in 200..299) conexao.inputStream else conexao.errorStream
            val texto = fluxo?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            return Resposta(status, texto, conexao.getHeaderField("ETag"))
        } finally {
            conexao.disconnect()
        }
    }

    /** Procura o gist do Disciplina na conta. Havendo mais de um, vale o mais antigo. */
    private fun achar(chave: String): String? {
        var melhorId: String? = null
        var melhorData = ""
        for (pagina in 1..10) {
            val r = pedirHttp(chave, "GET", "/gists?per_page=100&page=$pagina", null, null)
            if (r.status != 200) throw IllegalStateException("GitHub respondeu ${r.status}")
            val lista = JSONArray(r.corpo)
            for (i in 0 until lista.length()) {
                val g = lista.optJSONObject(i) ?: continue
                if (g.optJSONObject("files")?.has(ARQUIVO) != true) continue
                val data = g.optString("created_at")
                if (melhorId == null || data < melhorData) {
                    melhorId = g.optString("id")
                    melhorData = data
                }
            }
            if (lista.length() < 100) break
        }
        return melhorId
    }

    /** Lê o documento da conta. Com [etag], o status 304 quer dizer que nada mudou. */
    private fun ler(chave: String, id: String, etag: String?): Resposta {
        val r = pedirHttp(chave, "GET", "/gists/" + URLEncoder.encode(id, "UTF-8"), null, etag)
        if (r.status != 200) return r
        val arquivo = JSONObject(r.corpo).optJSONObject("files")?.optJSONObject(ARQUIVO) ?: return Resposta(404, "", null)
        val bruto = arquivo.optString("raw_url", "")
        val texto = if (arquivo.optBoolean("truncated", false) && bruto.startsWith("https://")) {
            // Documento grande demais para vir junto: o GitHub entrega por outro endereço.
            val conexao = URL(bruto).openConnection() as HttpURLConnection
            conexao.connectTimeout = 15_000
            conexao.readTimeout = 30_000
            try {
                conexao.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conexao.disconnect()
            }
        } else {
            arquivo.optString("content", "")
        }
        return Resposta(200, texto, r.etag)
    }

    private fun corpoDoGist(conteudo: String): JSONObject =
        JSONObject().put("files", JSONObject().put(ARQUIVO, JSONObject().put("content", conteudo)))

    private fun criar(chave: String, conteudo: String): String {
        val corpo = corpoDoGist(conteudo).put("description", DESCRICAO).put("public", false)
        val r = pedirHttp(chave, "POST", "/gists", corpo.toString(), null)
        if (r.status !in 200..299) throw IllegalStateException("GitHub respondeu ${r.status}")
        return JSONObject(r.corpo).getString("id")
    }

    private fun gravar(chave: String, id: String, conteudo: String): Int {
        val r = pedirHttp(chave, "PATCH", "/gists/" + URLEncoder.encode(id, "UTF-8"), corpoDoGist(conteudo).toString(), null)
        if (r.status == 404) return 404
        if (r.status !in 200..299) throw IllegalStateException("GitHub respondeu ${r.status}")
        return r.status
    }
}
