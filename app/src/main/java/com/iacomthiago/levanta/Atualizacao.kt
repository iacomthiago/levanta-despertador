package com.iacomthiago.levanta

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Atualização por dentro do app. O Levanta não vem da loja, então ele mesmo
 * pergunta ao GitHub qual é a versão mais recente, baixa o instalador e abre
 * a tela de instalação do Android. O toque final em "Instalar" é da pessoa:
 * o sistema não deixa um app de fora da loja se instalar em silêncio.
 *
 * O número da versão é o número da execução no GitHub Actions: a etiqueta
 * "v1.0.7" corresponde ao versionCode 7.
 */
object Atualizacao {
    private const val TAG = "Levanta"
    private const val REPOSITORIO = "iacomthiago/levanta-despertador"
    private const val INSTALADOR = "levanta.apk"

    data class Nova(val numero: Int, val nome: String, val url: String)

    /** Versão mais nova que a instalada, quando existe. */
    var nova by mutableStateOf<Nova?>(null)
        private set

    /** Nulo quando parado; de 0 a 100 enquanto baixa. */
    var progresso by mutableStateOf<Int?>(null)
        private set

    var aviso by mutableStateOf<String?>(null)
        private set

    private var conferidoEm = 0L
    private var conferindo = false

    // Escopo próprio: sair da tela no meio do download não o interrompe.
    private val escopo = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    fun numeroInstalado(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    } catch (e: Exception) {
        0
    }

    fun nomeInstalado(context: Context): String = try {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /**
     * Consulta o GitHub. Sem [forcar], no máximo uma vez a cada meia hora e
     * sem avisar nada quando falha ou quando já está em dia.
     */
    fun verificar(context: Context, forcar: Boolean = false) {
        val app = context.applicationContext
        val agora = System.currentTimeMillis()
        if (conferindo) return
        if (!forcar && agora - conferidoEm < 30 * 60_000L) return
        conferidoEm = agora
        conferindo = true
        if (forcar) aviso = "Consultando…"
        escopo.launch {
            val achada = withContext(Dispatchers.IO) {
                try {
                    buscar()
                } catch (e: Exception) {
                    Log.w(TAG, "Consulta de atualização falhou", e)
                    null
                }
            }
            conferindo = false
            if (achada == null) {
                if (forcar) aviso = "Não consegui consultar o GitHub. Verifique a internet do tablet."
            } else if (achada.numero > numeroInstalado(app)) {
                nova = achada
                aviso = null
            } else {
                nova = null
                if (forcar) aviso = "Você já está na versão mais recente."
            }
        }
    }

    private fun buscar(): Nova? {
        val conexao = URL("https://api.github.com/repos/$REPOSITORIO/releases/latest").openConnection() as HttpURLConnection
        conexao.connectTimeout = 10_000
        conexao.readTimeout = 10_000
        conexao.setRequestProperty("Accept", "application/vnd.github+json")
        conexao.setRequestProperty("User-Agent", "Levanta-Android")
        try {
            if (conexao.responseCode != 200) return null
            val resposta = JSONObject(conexao.inputStream.bufferedReader().use { it.readText() })
            val etiqueta = resposta.optString("tag_name")
            val numero = etiqueta.substringAfterLast('.').toIntOrNull() ?: return null
            val arquivos = resposta.optJSONArray("assets") ?: return null
            for (i in 0 until arquivos.length()) {
                val arquivo = arquivos.getJSONObject(i)
                if (arquivo.optString("name") == INSTALADOR) {
                    return Nova(numero, etiqueta.removePrefix("v"), arquivo.getString("browser_download_url"))
                }
            }
            return null
        } finally {
            conexao.disconnect()
        }
    }

    /** Baixa o instalador da versão nova e abre a tela de instalação do Android. */
    fun atualizar(context: Context) {
        val app = context.applicationContext
        val alvo = nova ?: return
        if (progresso != null) return
        progresso = 0
        aviso = null
        escopo.launch {
            val arquivo = withContext(Dispatchers.IO) {
                try {
                    baixar(app, alvo.url)
                } catch (e: Exception) {
                    Log.w(TAG, "Download da atualização falhou", e)
                    null
                }
            }
            progresso = null
            if (arquivo == null) {
                aviso = "O download falhou. Verifique a internet e tente de novo."
                return@launch
            }
            try {
                val uri = FileProvider.getUriForFile(app, app.packageName + ".arquivos", arquivo)
                app.startActivity(
                    Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                aviso = "Toque em Atualizar na tela do Android. Se ele pedir, permita instalar a partir do Levanta."
            } catch (e: Exception) {
                Log.w(TAG, "Não abriu o instalador", e)
                aviso = "Não consegui abrir o instalador do Android."
            }
        }
    }

    private fun baixar(context: Context, endereco: String): File? {
        val pasta = File(context.cacheDir, "atualizacao")
        pasta.mkdirs()
        val arquivo = File(pasta, INSTALADOR)
        val conexao = URL(endereco).openConnection() as HttpURLConnection
        conexao.connectTimeout = 15_000
        conexao.readTimeout = 30_000
        conexao.instanceFollowRedirects = true
        conexao.setRequestProperty("User-Agent", "Levanta-Android")
        try {
            if (conexao.responseCode != 200) return null
            val total = conexao.contentLengthLong
            conexao.inputStream.use { entrada ->
                arquivo.outputStream().use { saida ->
                    val pedaco = ByteArray(64 * 1024)
                    var lidos = 0L
                    while (true) {
                        val n = entrada.read(pedaco)
                        if (n < 0) break
                        saida.write(pedaco, 0, n)
                        lidos += n
                        if (total > 0L) progresso = ((lidos * 100L) / total).toInt().coerceIn(0, 99)
                    }
                }
            }
            // Um instalador de verdade tem vários megabytes; menos que isso é página de erro.
            return if (arquivo.length() > 1_000_000L) arquivo else null
        } finally {
            conexao.disconnect()
        }
    }
}
