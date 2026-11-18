package com.iacomthiago.levanta

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Confere que o app junta e calcula igual ao site. Os casos são gerados pelo
 * próprio site (node web/vetores.js) logo antes deste teste, no GitHub Actions.
 */
class NucleoTest {

    private fun casos(): JSONArray {
        val caminho = System.getProperty("vetores") ?: "../ci-out/vetores.json"
        val arquivo = File(caminho)
        assertTrue("Faltou gerar os casos: node web/vetores.js > ci-out/vetores.json", arquivo.exists())
        return JSONArray(arquivo.readText(Charsets.UTF_8))
    }

    @Test
    fun juntaEAjustaIgualAoSite() {
        val casos = casos()
        assertTrue("Poucos casos: ${casos.length()}", casos.length() >= 10)
        val falhas = ArrayList<String>()
        for (i in 0 until casos.length()) {
            val caso = casos.getJSONObject(i)
            val nome = caso.getString("nome")
            Nucleo.fusoFixo = caso.getInt("fuso")
            val agora = caso.getLong("agora")
            val a = caso.getJSONObject("a")
            val b = caso.optJSONObject("b")
            val junto = Nucleo.juntar(a, b)
            Nucleo.ajustar(junto, agora)
            val esperado = caso.getJSONObject("junto")
            if (!Nucleo.igual(junto, esperado)) {
                falhas += "$nome: documento diferente em ${diferenca(junto, esperado, "")}"
                continue
            }
            // Ajustar de novo não pode mudar nada.
            if (Nucleo.ajustar(junto, agora)) falhas += "$nome: ajustar duas vezes mudou o documento"

            val v = Nucleo.derivar(junto, agora)
            val d = caso.getJSONObject("deriv")
            val obtido = listOf(v.xp, v.nivel, v.sequencia, v.sequenciaAtual, maxOf(v.recorde, v.sequenciaAtual), v.turbos, v.turboLigado, v.hojeCompleto)
            val certo = listOf(
                d.getInt("xp"), d.getInt("nivel"), d.getInt("sequencia"), d.getInt("sequenciaAtual"),
                d.getInt("recorde"), d.getInt("turbos"), d.getBoolean("turboLigado"), d.getBoolean("hojeCompleto"),
            )
            if (obtido != certo) falhas += "$nome: números $obtido, esperado $certo"
        }
        Nucleo.fusoFixo = null
        if (falhas.isNotEmpty()) fail(falhas.joinToString("\n"))
    }

    @Test
    fun datasBatemComOCalendario() {
        Nucleo.fusoFixo = -180
        // 18/11/2026 é uma quarta-feira; 13:00 no horário universal são 10:00 em Brasília.
        val t0 = 1795006800000L
        assertEquals("2026-11-18", Nucleo.diaDe(t0))
        assertEquals(10, Nucleo.horaDe(t0))
        assertEquals(3, Nucleo.diaDaSemana("2026-11-18"))
        assertEquals("2026-11-16", Nucleo.segundaDe("2026-11-18"))
        assertEquals("2026-11-17", Nucleo.diaDe(t0 - 11 * 3_600_000L))
        Nucleo.fusoFixo = null
    }

    /** Primeiro lugar em que dois documentos diferem, para a mensagem de erro dizer onde olhar. */
    private fun diferenca(a: Any?, b: Any?, caminho: String): String {
        if (a is JSONObject && b is JSONObject) {
            for (k in (Nucleo.chaves(a) + Nucleo.chaves(b)).distinct().sorted()) {
                if (!a.has(k)) return "$caminho/$k (falta aqui; no site: ${b.opt(k)})"
                if (!b.has(k)) return "$caminho/$k (sobra aqui: ${a.opt(k)})"
                if (!Nucleo.igual(a.opt(k), b.opt(k))) return diferenca(a.opt(k), b.opt(k), "$caminho/$k")
            }
        }
        return "$caminho (aqui: $a; no site: $b)"
    }
}
