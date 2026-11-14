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
        private const val PRIMARY_URL =
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt"
        private const val FALLBACK_URL =
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
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

    suspend fun downloadAndSave(
        defaultDomains: List<String>,
        urlString: String = PRIMARY_URL
    ): Result<Set<String>> = withContext(Dispatchers.IO) {
        val urlsToTry = listOf(urlString, FALLBACK_URL)
        var lastError: Exception? = null

        for (targetUrl in urlsToTry) {
            var conn: HttpURLConnection? = null
            try {
                conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 10000
                    readTimeout = 20000
                    setRequestProperty("User-Agent", "FirewallDNS/1.0")
                }
                if (conn.responseCode != 200) {
                    lastError = Exception("HTTP ${conn.responseCode}")
                    continue
                }

                val tempSet = HashSet<String>(defaultDomains)
                val outFile = File(filesDir, HOSTS_FILE_NAME)
                conn.inputStream.bufferedReader().use { reader ->
                    outFile.bufferedWriter().use { writer ->
                        reader.forEachLine { line ->
                            RuleEvaluator.parseHostLine(line)?.let { parsed ->
                                tempSet.add(parsed)
                                writer.write(parsed)
                                writer.newLine()
                            }
                        }
                    }
                }
                return@withContext Result.success(tempSet)
            } catch (e: Exception) {
                lastError = e
            } finally {
                conn?.disconnect()
            }
        }
        Result.failure(lastError ?: Exception("No se pudo descargar la lista"))
    }
}
