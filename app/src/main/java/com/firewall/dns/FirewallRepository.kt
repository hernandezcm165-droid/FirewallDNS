package com.firewall.dns

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class DnsLogEntry(
    val id: Long,
    val timestamp: String,
    val domain: String,
    val queryType: String,
    val isBlocked: Boolean,
    val reason: String,
    val appName: String
)

data class FirewallStats(
    val isRunning: Boolean = false,
    val totalAllowed: Long = 0,
    val totalBlocked: Long = 0,
    val loadedRulesCount: Int = 0,
    val networkType: String = "Sin red",
    val upstreamDns: String = "9.9.9.9"
)

object FirewallRepository {

    private const val PREFS_NAME = "firewall_prefs"
    private const val KEY_CUSTOM_BLOCK = "custom_block"
    private const val KEY_CUSTOM_ALLOW = "custom_allow"
    private const val KEY_EXCLUDED_APPS = "excluded_apps"
    private const val KEY_UPSTREAM_DNS = "upstream_dns"
    private const val KEY_SHOULD_RUN = "should_run"
    private const val HOSTS_FILE_NAME = "blocklist.txt"
    private const val MAX_LOGS = 400

    private val _logs = MutableStateFlow<List<DnsLogEntry>>(emptyList())
    val logs: StateFlow<List<DnsLogEntry>> = _logs.asStateFlow()

    private val _stats = MutableStateFlow(FirewallStats())
    val stats: StateFlow<FirewallStats> = _stats.asStateFlow()

    private val _customBlockList = MutableStateFlow<List<String>>(emptyList())
    val customBlockList: StateFlow<List<String>> = _customBlockList.asStateFlow()

    private val _customAllowList = MutableStateFlow<List<String>>(emptyList())
    val customAllowList: StateFlow<List<String>> = _customAllowList.asStateFlow()

    private val _excludedPackages = MutableStateFlow<Set<String>>(emptySet())
    val excludedPackages: StateFlow<Set<String>> = _excludedPackages.asStateFlow()

    private val _cacheInvalidateEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cacheInvalidateEvent: SharedFlow<Unit> = _cacheInvalidateEvent.asSharedFlow()

    private val _rebuildVpnEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val rebuildVpnEvent: SharedFlow<Unit> = _rebuildVpnEvent.asSharedFlow()

    private val hostBlockSet = ConcurrentHashMap.newKeySet<String>()
    private val customBlockSet = ConcurrentHashMap.newKeySet<String>()
    private val customAllowSet = ConcurrentHashMap.newKeySet<String>()

    private var logCounter = 0L
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    @Volatile
    private var initialized = false

    private val defaultDomains = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "app-measurement.com", "ads.facebook.com", "analytics.tiktok.com"
    )

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            customBlockSet.clear()
            customBlockSet.addAll(prefs.getStringSet(KEY_CUSTOM_BLOCK, emptySet()) ?: emptySet())
            _customBlockList.value = customBlockSet.sorted()

            customAllowSet.clear()
            customAllowSet.addAll(prefs.getStringSet(KEY_CUSTOM_ALLOW, emptySet()) ?: emptySet())
            _customAllowList.value = customAllowSet.sorted()

            val excluded = prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet()) ?: emptySet()
            _excludedPackages.value = excluded.toSet()

            val upstream = prefs.getString(KEY_UPSTREAM_DNS, "9.9.9.9") ?: "9.9.9.9"
            _stats.update { it.copy(upstreamDns = upstream) }

            loadBlocklistFromDisk(context)
            initialized = true
        }
    }

    fun getShouldRun(context: Context): Boolean =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SHOULD_RUN, false)

    fun setShouldRun(context: Context, shouldRun: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_SHOULD_RUN, shouldRun).apply()
    }

    fun getUpstreamDns(): String = _stats.value.upstreamDns

    fun setUpstreamDns(context: Context, dnsIp: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_UPSTREAM_DNS, dnsIp).apply()
        _stats.update { it.copy(upstreamDns = dnsIp) }
        _cacheInvalidateEvent.tryEmit(Unit)
        _rebuildVpnEvent.tryEmit(Unit)
    }

    fun toggleExcludedApp(context: Context, pkg: String) {
        val updated = _excludedPackages.value.toMutableSet()
        if (!updated.add(pkg)) updated.remove(pkg)
        _excludedPackages.value = updated
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_EXCLUDED_APPS, updated).apply()
        _rebuildVpnEvent.tryEmit(Unit)
    }

    private fun loadBlocklistFromDisk(context: Context) {
        hostBlockSet.clear()
        hostBlockSet.addAll(defaultDomains)
        val file = File(context.filesDir, HOSTS_FILE_NAME)
        if (file.exists()) {
            file.bufferedReader().useLines { lines ->
                lines.forEach { line ->
                    parseHostLine(line)?.let { hostBlockSet.add(it) }
                }
            }
        }
        updateRulesCount()
    }

    suspend fun downloadBlocklist(
        context: Context,
        urlString: String = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/light-onlydomains.txt"
    ): Result<Int> = withContext(Dispatchers.IO) {
        val urlsToTry = listOf(
            urlString,
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
        )
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

                val tempSet = HashSet<String>()
                val outFile = File(context.filesDir, HOSTS_FILE_NAME)
                conn.inputStream.bufferedReader().use { reader ->
                    outFile.bufferedWriter().use { writer ->
                        reader.forEachLine { line ->
                            parseHostLine(line)?.let { parsed ->
                                tempSet.add(parsed)
                                writer.write(parsed)
                                writer.newLine()
                            }
                        }
                    }
                }

                hostBlockSet.clear()
                hostBlockSet.addAll(defaultDomains)
                hostBlockSet.addAll(tempSet)
                updateRulesCount()
                _cacheInvalidateEvent.tryEmit(Unit)
                return@withContext Result.success(hostBlockSet.size)
            } catch (e: Exception) {
                lastError = e
            } finally {
                conn?.disconnect()
            }
        }
        Result.failure(lastError ?: Exception("No se pudo descargar la lista"))
    }

    private fun parseHostLine(raw: String): String? {
        val line = raw.substringBefore('#').trim().lowercase()
        if (line.isEmpty()) return null
        val parts = line.split(Regex("\\s+"))
        val candidate = when {
            parts.size >= 2 && (parts[0] == "0.0.0.0" || parts[0] == "127.0.0.1") -> parts[1]
            parts.size == 1 -> parts[0]
            else -> return null
        }.removePrefix("*.")
        return if (candidate.contains('.') && candidate != "localhost") candidate else null
    }

    fun evaluateDomain(domain: String): Pair<Boolean, String> {
        val clean = domain.lowercase()
        if (matchesDomainOrParent(clean, customAllowSet)) return false to "Lista blanca"
        if (matchesDomainOrParent(clean, customBlockSet)) return true to "Regla propia"
        if (matchesDomainOrParent(clean, hostBlockSet)) return true to "Lista bloqueo"
        return false to "Permitido"
    }

    private fun matchesDomainOrParent(domain: String, set: Set<String>): Boolean {
        var current = domain
        while (true) {
            if (set.contains(current)) return true
            val dot = current.indexOf('.')
            if (dot == -1) break
            current = current.substring(dot + 1)
        }
        return false
    }

    fun setCustomRule(context: Context, domain: String, block: Boolean) {
        val clean = domain.trim().lowercase()
        if (!clean.contains('.')) return
        if (block) {
            customAllowSet.remove(clean)
            customBlockSet.add(clean)
        } else {
            customBlockSet.remove(clean)
            customAllowSet.add(clean)
        }
        saveCustomRules(context)
    }

    fun removeCustomRule(context: Context, domain: String) {
        val clean = domain.lowercase()
        customBlockSet.remove(clean)
        customAllowSet.remove(clean)
        saveCustomRules(context)
    }

    private fun saveCustomRules(context: Context) {
        _customBlockList.value = customBlockSet.sorted()
        _customAllowList.value = customAllowSet.sorted()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putStringSet(KEY_CUSTOM_BLOCK, customBlockSet.toSet())
            .putStringSet(KEY_CUSTOM_ALLOW, customAllowSet.toSet())
            .apply()
        updateRulesCount()
        _cacheInvalidateEvent.tryEmit(Unit)
    }

    fun addLog(domain: String, qType: String, isBlocked: Boolean, reason: String, appName: String) {
        val entry = DnsLogEntry(
            id = ++logCounter,
            timestamp = timeFormat.format(Date()),
            domain = domain,
            queryType = qType,
            isBlocked = isBlocked,
            reason = reason,
            appName = appName
        )
        _logs.update { (listOf(entry) + it).take(MAX_LOGS) }
        _stats.update { s ->
            if (isBlocked) s.copy(totalBlocked = s.totalBlocked + 1)
            else s.copy(totalAllowed = s.totalAllowed + 1)
        }
    }

    fun setRunning(running: Boolean) {
        _stats.update { it.copy(isRunning = running) }
    }

    fun setNetworkType(type: String) {
        _stats.update { it.copy(networkType = type) }
    }

    fun clearLogs() {
        _logs.value = emptyList()
        _stats.update { it.copy(totalAllowed = 0, totalBlocked = 0) }
    }

    private fun updateRulesCount() {
        _stats.update { it.copy(loadedRulesCount = hostBlockSet.size + customBlockSet.size) }
    }
}
