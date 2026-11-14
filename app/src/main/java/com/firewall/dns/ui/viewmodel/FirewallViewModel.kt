package com.firewall.dns.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.firewall.dns.data.repository.FirewallRepository
import com.firewall.dns.domain.model.AppInfoItem
import com.firewall.dns.domain.model.DnsLogEntry
import com.firewall.dns.domain.model.FirewallStats
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class FirewallViewModel(application: Application) : AndroidViewModel(application) {

    val stats: StateFlow<FirewallStats> = FirewallRepository.stats
    val customBlockList: StateFlow<List<String>> = FirewallRepository.customBlockList
    val customAllowList: StateFlow<List<String>> = FirewallRepository.customAllowList
    val excludedPackages: StateFlow<Set<String>> = FirewallRepository.excludedPackages

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _onlyBlocked = MutableStateFlow(false)
    val onlyBlocked: StateFlow<Boolean> = _onlyBlocked.asStateFlow()

    private val _appSearch = MutableStateFlow("")
    val appSearch: StateFlow<String> = _appSearch.asStateFlow()

    private val _isDownloading = MutableStateFlow(false)
    val isDownloading: StateFlow<Boolean> = _isDownloading.asStateFlow()

    private val _installedApps = MutableStateFlow<List<AppInfoItem>>(emptyList())

    private val _toastMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val toastMessage: SharedFlow<String> = _toastMessage.asSharedFlow()

    val filteredLogs: StateFlow<List<DnsLogEntry>> = combine(
        FirewallRepository.logs,
        _onlyBlocked,
        _searchQuery
    ) { logs, blockedOnly, query ->
        logs.filter { entry ->
            (!blockedOnly || entry.isBlocked) &&
                (query.isBlank() || entry.domain.contains(query, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val filteredApps: StateFlow<List<AppInfoItem>> = combine(
        _installedApps,
        _appSearch
    ) { apps, query ->
        apps.filter { app ->
            query.isBlank() ||
                app.label.contains(query, ignoreCase = true) ||
                app.packageName.contains(query, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        FirewallRepository.init(application)
        loadInstalledApps()
    }

    private fun loadInstalledApps() {
        viewModelScope.launch {
            try {
                _installedApps.value = FirewallRepository.getInstalledUserApps()
            } catch (_: Exception) {
                _installedApps.value = emptyList()
            }
        }
    }

    fun shouldAutoStartVpn(): Boolean =
        FirewallRepository.getShouldRun() && !FirewallRepository.stats.value.isRunning

    fun onSearchQueryChange(query: String) {
        _searchQuery.value = query
    }

    fun onToggleOnlyBlocked() {
        _onlyBlocked.value = !_onlyBlocked.value
    }

    fun onAppSearchChange(query: String) {
        _appSearch.value = query
    }

    fun setCustomRule(domain: String, block: Boolean) {
        FirewallRepository.setCustomRule(domain, block)
    }

    fun removeCustomRule(domain: String) {
        FirewallRepository.removeCustomRule(domain)
    }

    fun clearLogs() {
        FirewallRepository.clearLogs()
    }

    fun setUpstreamDns(dnsIp: String) {
        FirewallRepository.setUpstreamDns(dnsIp)
    }

    fun toggleExcludedApp(packageName: String) {
        FirewallRepository.toggleExcludedApp(packageName)
    }

    fun downloadBlocklist() {
        if (_isDownloading.value) return
        viewModelScope.launch {
            _isDownloading.value = true
            val result = FirewallRepository.downloadBlocklist()
            _isDownloading.value = false
            result.onSuccess { count ->
                _toastMessage.tryEmit("Lista actualizada: $count dominios")
            }.onFailure { err ->
                _toastMessage.tryEmit("Error: ${err.message}")
            }
        }
    }
}
