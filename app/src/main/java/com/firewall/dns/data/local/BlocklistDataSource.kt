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
        private const val HOSTS_FILE_NAME = "blocklist.txt"
    }

    fun loadFromDisk(defaultDomains: List<String>): Set<String> {
        val result = HashSet<String>(defaultDomains)
        val file = File(filesDir, HOSTS_FILE_NAME)
        if (file.exists()) {
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    RuleEvaluator.parseHostLine(line)?.let { result.add(it) }
                }
            }
        }
        return result
    }

    suspend fun downloadMultipleAndSave(
        defaultDomains: List<String>,
        urls: List<String>
    ): Result<Pair<Set<String>, String>> = withContext(Dispatchers.IO) {
        if (urls.isEmpty()) {
            return@withContext Result.failure(Exception("Agrega al menos una URL raw"))
        }

        val mergedSet = HashSet<String>(defaultDomains)
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
                    lastError = Exception("HTTP ${conn.responseCode} en $targetUrl")
                    continue
                }
                conn.inputStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        RuleEvaluator.parseHostLine(line)?.let { mergedSet.add(it) }
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

        val outFile = File(filesDir, HOSTS_FILE_NAME)
        outFile.bufferedWriter().use { writer ->
            mergedSet.forEach { domain ->
                writer.write(domain)
                writer.newLine()
            }
        }

        val summary = "Listas OK ($successCount/${urls.size}): ${mergedSet.size} dominios"
        Result.success(mergedSet to summary)
    }
}
