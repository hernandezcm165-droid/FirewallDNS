package com.firewall.dns.data.local

import android.content.Context
import com.firewall.dns.domain.engine.RuleEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class BlocklistDataSource(context: Context) {

    private val filesDir: File = context.applicationContext.filesDir

    companion object {
        private const val ADBLOCK_FILE_NAME = "adblock_rules.txt"
    }

    // Devuelve Pair(bloqueados, excepciones @@||)
    fun loadFromDisk(defaultDomains: List<String>): Pair<Set<String>, Set<String>> {
        val blocked = HashSet<String>(defaultDomains)
        val exceptions = HashSet<String>()
        val file = File(filesDir, ADBLOCK_FILE_NAME)
        if (file.exists()) {
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    RuleEvaluator.parseAdblockLine(line)?.let { parsed ->
                        if (parsed.isException) exceptions.add(parsed.domain)
                        else blocked.add(parsed.domain)
                    }
                }
            }
        }
        return blocked to exceptions
    }

    suspend fun downloadMultipleAndSave(
        defaultDomains: List<String>,
        urls: List<String>
    ): Result<Triple<Set<String>, Set<String>, String>> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            return@withContext Result.failure(Exception("Agrega al menos una URL en sintaxis Adblock"))
        }

        val blocked = HashSet<String>(defaultDomains)
        val exceptions = HashSet<String>()
        var successCount = 0
        var lastError: Exception? = null

        for (targetUrl in urls) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 25000
                    setRequestProperty("User-Agent", "FirewallDNS/1.0")
                }
                if (conn.responseCode != 200) {
                    lastError = Exception("HTTP ${conn.responseCode}")
                    continue
                }
                conn.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        RuleEvaluator.parseAdblockLine(line)?.let { parsed ->
                            if (parsed.isException) exceptions.add(parsed.domain)
                            else blocked.add(parsed.domain)
                        }
                    }
                }
                successCount++
            } catch (e: Exception) {
                lastError = e
            } finally {
                conn?.disconnect()
            }
        }

        if (successCount == 0) {
            return@withContext Result.failure(lastError ?: Exception("No se pudo descargar ninguna lista"))
        }

        val outFile = File(filesDir, ADBLOCK_FILE_NAME)
        outFile.bufferedWriter().use { writer ->
            blocked.forEach { domain ->
                writer.write("||$domain^")
                writer.newLine()
            }
            exceptions.forEach { domain ->
                writer.write("@@||$domain^")
                writer.newLine()
            }
        }

        val summary = "Adblock OK ($successCount/${urls.size}): ${blocked.size} reglas"
        Result.success(Triple(blocked, exceptions, summary))
    }
}
