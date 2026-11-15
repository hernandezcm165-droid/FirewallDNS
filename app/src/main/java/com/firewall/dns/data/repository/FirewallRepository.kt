package com.firewall.dns.data.repository

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.firewall.dns.data.local.BlocklistDataSource
import com.firewall.dns.data.local.PreferencesDataSource
import com.firewall.dns.domain.engine.RuleEvaluator
import com.firewall.dns.domain.engine.SmartDomainAnalyzer
import com.firewall.dns.domain.model.AppInfoItem
import com.firewall.dns.domain.model.DnsLogEntry
import com.firewall.dns.domain.model.FirewallStats
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

object FirewallRepository {

    private const val MAX_LOGS = 400

    private lateinit var appContext: Context
    private lateinit var prefsDataSource: PreferencesDataSource
    private lateinit var blocklistDataSource: BlocklistDataSource

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

    private val _blocklistUrls = MutableStateFlow<List<String>>(emptyList())
    val blocklistUrls: StateFlow<List<String>> = _blocklistUrls.asStateFlow()

    private val _smartModeEnabled = MutableStateFlow(true)
    val smartModeEnabled: StateFlow<Boolean> = _smartModeEnabled.asStateFlow()

    private val _observationModeEnabled = MutableStateFlow(true)
    val observationModeEnabled: StateFlow<Boolean> = _observationModeEnabled.asStateFlow()

    private val _cacheInvalidateEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cacheInvalidateEvent: SharedFlow<Unit> = _cacheInvalidateEvent.asSharedFlow()

    private val _rebuildVpnEvent = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val rebuildVpnEvent: SharedFlow<Unit> = _rebuildVpnEvent.asSharedFlow()

    private val hostBlockSet = ConcurrentHashMap.newKeySet<String>()
    private val hostExceptionSet = ConcurrentHashMap.newKeySet<String>()
    private val customBlockSet = ConcurrentHashMap.newKeySet<String>()
    private val customAllowSet = ConcurrentHashMap.newKeySet<String>()
    private val learnedTokens = ConcurrentHashMap<String, Int>()

    private val logCounter = AtomicLong(0L)
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
            appContext = context.applicationContext
            prefsDataSource = PreferencesDataSource(appContext)
            blocklistDataSource = BlocklistDataSource(appContext)

            customBlockSet.clear()
            customBlockSet.addAll(prefsDataSource.getCustomBlockSet())
            _customBlockList.value = customBlockSet.sorted()

            customAllowSet.clear()
            customAllowSet.addAll(prefsDataSource.getCustomAllowSet())
            _customAllowList.value = customAllowSet.sorted()

            _excludedPackages.value = prefsDataSource.getExcludedPackages()
            _blocklistUrls.value = prefsDataSource.getBlocklistUrls()
            _smartModeEnabled.value = prefsDataSource.getSmartModeEnabled()
            _observationModeEnabled.value = prefsDataSource.getObservationModeEnabled()
            learnedTokens.clear()
            learnedTokens.putAll(prefsDataSource.getLearnedTokens())

            _stats.update { it.copy(upstreamDns = prefsDataSource.getUpstreamDns()) }

            val (loadedBlocked, loadedExceptions) = blocklistDataSource.loadFromDisk(defaultDomains)
            hostBlockSet.clear()
            hostBlockSet.addAll(loadedBlocked)
            hostExceptionSet.clear()
            hostExceptionSet.addAll(loadedExceptions)
            updateRulesCount()

            initialized = true
        }
    }

    fun getShouldRun(): Boolean = prefsDataSource.getShouldRun()

    fun setShouldRun(shouldRun: Boolean) {
        prefsDataSource.saveShouldRun(shouldRun)
    }

    fun getUpstreamDns(): String = _stats.value.upstreamDns

    fun setUpstreamDns(dnsIp: String) {
        prefsDataSource.saveUpstreamDns(dnsIp)
        _stats.update { it.copy(upstreamDns = dnsIp) }
        _cacheInvalidateEvent.tryEmit(Unit)
        _rebuildVpnEvent.tryEmit(Unit)
    }

    fun isObservationModeEnabled(): Boolean = _observationModeEnabled.value

    fun setObservationModeEnabled(enabled: Boolean) {
        _observationModeEnabled.value = enabled
        prefsDataSource.saveObservationModeEnabled(enabled)
        _cacheInvalidateEvent.tryEmit(Unit)
    }

    fun setSmartModeEnabled(enabled: Boolean) {
        _smartModeEnabled.value = enabled
        prefsDataSource.saveSmartModeEnabled(enabled)
        _cacheInvalidateEvent.tryEmit(Unit)
    }

    fun addBlocklistUrl(rawUrl: String): Boolean {
        val clean = rawUrl.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return false
        val current = _blocklistUrls.value.toMutableList()
        if (!current.contains(clean)) {
            current.add(clean)
            _blocklistUrls.value = current
            prefsDataSource.saveBlocklistUrls(current)
        }
        return true
    }

    fun removeBlocklistUrl(url: String) {
        val updated = _blocklistUrls.value.filter { it != url }
        _blocklistUrls.value = updated
        prefsDataSource.saveBlocklistUrls(updated)
    }

    fun toggleExcludedApp(pkg: String) {
        val updated = _excludedPackages.value.toMutableSet()
        if (!updated.add(pkg)) updated.remove(pkg)
        _excludedPackages.value = updated
        prefsDataSource.saveExcludedPackages(updated)
        _rebuildVpnEvent.tryEmit(Unit)
    }

    suspend fun downloadBlocklist(): Result<String> {
        val urls = _blocklistUrls.value
        val result = blocklistDataSource.downloadMultipleAndSave(defaultDomains, urls)
        return result.map { (newBlocked, newExceptions, summary) ->
            hostBlockSet.clear()
            hostBlockSet.addAll(newBlocked)
            hostExceptionSet.clear()
            hostExceptionSet.addAll(newExceptions)
            updateRulesCount()
            _cacheInvalidateEvent.tryEmit(Unit)
            summary
        }
    }

    fun evaluateDomain(domain: String): Pair<Boolean, String> {
        val baseEval = RuleEvaluator.evaluate(
            domain = domain,
            customAllow = customAllowSet,
            customBlock = customBlockSet,
            hostExceptions = hostExceptionSet,
            hostBlock = hostBlockSet
        )
        if (baseEval.first || baseEval.second != "Permitido") return baseEval
        if (_smartModeEnabled.value) {
            SmartDomainAnalyzer.evaluate(domain, learnedTokens)?.let { return it }
        }
        return baseEval
    }

    fun setCustomRule(domain: String, block: Boolean) {
        val clean = RuleEvaluator.normalizeManualDomain(domain) ?: return
        if (block) {
            customAllowSet.remove(clean)
            customBlockSet.add(clean)
        } else {
            customBlockSet.remove(clean)
            customAllowSet.add(clean)
        }
        SmartDomainAnalyzer.updateLearning(clean, block, learnedTokens)
        prefsDataSource.saveLearnedTokens(learnedTokens)
        saveCustomRules()
    }

    fun removeCustomRule(domain: String) {
        val clean = domain.lowercase()
        customBlockSet.remove(clean)
        customAllowSet.remove(clean)
        saveCustomRules()
    }

    private fun saveCustomRules() {
        _customBlockList.value = customBlockSet.sorted()
        _customAllowList.value = customAllowSet.sorted()
        prefsDataSource.saveCustomBlockSet(customBlockSet.toSet())
        prefsDataSource.saveCustomAllowSet(customAllowSet.toSet())
        updateRulesCount()
        _cacheInvalidateEvent.tryEmit(Unit)
    }

    suspend fun getInstalledUserApps(): List<AppInfoItem> = withContext(Dispatchers.IO) {
        val pm = appContext.packageManager
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { it.packageName != appContext.packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
            .map { AppInfoItem(pm.getApplicationLabel(it).toString(), it.packageName) }
            .sortedBy { it.label.lowercase() }
    }

    fun addLog(domain: String, qType: String, isBlocked: Boolean, reason: String, appName: String) {
        val timestamp = synchronized(timeFormat) { timeFormat.format(Date()) }
        val entry = DnsLogEntry(
            id = logCounter.incrementAndGet(),
            timestamp = timestamp,
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
