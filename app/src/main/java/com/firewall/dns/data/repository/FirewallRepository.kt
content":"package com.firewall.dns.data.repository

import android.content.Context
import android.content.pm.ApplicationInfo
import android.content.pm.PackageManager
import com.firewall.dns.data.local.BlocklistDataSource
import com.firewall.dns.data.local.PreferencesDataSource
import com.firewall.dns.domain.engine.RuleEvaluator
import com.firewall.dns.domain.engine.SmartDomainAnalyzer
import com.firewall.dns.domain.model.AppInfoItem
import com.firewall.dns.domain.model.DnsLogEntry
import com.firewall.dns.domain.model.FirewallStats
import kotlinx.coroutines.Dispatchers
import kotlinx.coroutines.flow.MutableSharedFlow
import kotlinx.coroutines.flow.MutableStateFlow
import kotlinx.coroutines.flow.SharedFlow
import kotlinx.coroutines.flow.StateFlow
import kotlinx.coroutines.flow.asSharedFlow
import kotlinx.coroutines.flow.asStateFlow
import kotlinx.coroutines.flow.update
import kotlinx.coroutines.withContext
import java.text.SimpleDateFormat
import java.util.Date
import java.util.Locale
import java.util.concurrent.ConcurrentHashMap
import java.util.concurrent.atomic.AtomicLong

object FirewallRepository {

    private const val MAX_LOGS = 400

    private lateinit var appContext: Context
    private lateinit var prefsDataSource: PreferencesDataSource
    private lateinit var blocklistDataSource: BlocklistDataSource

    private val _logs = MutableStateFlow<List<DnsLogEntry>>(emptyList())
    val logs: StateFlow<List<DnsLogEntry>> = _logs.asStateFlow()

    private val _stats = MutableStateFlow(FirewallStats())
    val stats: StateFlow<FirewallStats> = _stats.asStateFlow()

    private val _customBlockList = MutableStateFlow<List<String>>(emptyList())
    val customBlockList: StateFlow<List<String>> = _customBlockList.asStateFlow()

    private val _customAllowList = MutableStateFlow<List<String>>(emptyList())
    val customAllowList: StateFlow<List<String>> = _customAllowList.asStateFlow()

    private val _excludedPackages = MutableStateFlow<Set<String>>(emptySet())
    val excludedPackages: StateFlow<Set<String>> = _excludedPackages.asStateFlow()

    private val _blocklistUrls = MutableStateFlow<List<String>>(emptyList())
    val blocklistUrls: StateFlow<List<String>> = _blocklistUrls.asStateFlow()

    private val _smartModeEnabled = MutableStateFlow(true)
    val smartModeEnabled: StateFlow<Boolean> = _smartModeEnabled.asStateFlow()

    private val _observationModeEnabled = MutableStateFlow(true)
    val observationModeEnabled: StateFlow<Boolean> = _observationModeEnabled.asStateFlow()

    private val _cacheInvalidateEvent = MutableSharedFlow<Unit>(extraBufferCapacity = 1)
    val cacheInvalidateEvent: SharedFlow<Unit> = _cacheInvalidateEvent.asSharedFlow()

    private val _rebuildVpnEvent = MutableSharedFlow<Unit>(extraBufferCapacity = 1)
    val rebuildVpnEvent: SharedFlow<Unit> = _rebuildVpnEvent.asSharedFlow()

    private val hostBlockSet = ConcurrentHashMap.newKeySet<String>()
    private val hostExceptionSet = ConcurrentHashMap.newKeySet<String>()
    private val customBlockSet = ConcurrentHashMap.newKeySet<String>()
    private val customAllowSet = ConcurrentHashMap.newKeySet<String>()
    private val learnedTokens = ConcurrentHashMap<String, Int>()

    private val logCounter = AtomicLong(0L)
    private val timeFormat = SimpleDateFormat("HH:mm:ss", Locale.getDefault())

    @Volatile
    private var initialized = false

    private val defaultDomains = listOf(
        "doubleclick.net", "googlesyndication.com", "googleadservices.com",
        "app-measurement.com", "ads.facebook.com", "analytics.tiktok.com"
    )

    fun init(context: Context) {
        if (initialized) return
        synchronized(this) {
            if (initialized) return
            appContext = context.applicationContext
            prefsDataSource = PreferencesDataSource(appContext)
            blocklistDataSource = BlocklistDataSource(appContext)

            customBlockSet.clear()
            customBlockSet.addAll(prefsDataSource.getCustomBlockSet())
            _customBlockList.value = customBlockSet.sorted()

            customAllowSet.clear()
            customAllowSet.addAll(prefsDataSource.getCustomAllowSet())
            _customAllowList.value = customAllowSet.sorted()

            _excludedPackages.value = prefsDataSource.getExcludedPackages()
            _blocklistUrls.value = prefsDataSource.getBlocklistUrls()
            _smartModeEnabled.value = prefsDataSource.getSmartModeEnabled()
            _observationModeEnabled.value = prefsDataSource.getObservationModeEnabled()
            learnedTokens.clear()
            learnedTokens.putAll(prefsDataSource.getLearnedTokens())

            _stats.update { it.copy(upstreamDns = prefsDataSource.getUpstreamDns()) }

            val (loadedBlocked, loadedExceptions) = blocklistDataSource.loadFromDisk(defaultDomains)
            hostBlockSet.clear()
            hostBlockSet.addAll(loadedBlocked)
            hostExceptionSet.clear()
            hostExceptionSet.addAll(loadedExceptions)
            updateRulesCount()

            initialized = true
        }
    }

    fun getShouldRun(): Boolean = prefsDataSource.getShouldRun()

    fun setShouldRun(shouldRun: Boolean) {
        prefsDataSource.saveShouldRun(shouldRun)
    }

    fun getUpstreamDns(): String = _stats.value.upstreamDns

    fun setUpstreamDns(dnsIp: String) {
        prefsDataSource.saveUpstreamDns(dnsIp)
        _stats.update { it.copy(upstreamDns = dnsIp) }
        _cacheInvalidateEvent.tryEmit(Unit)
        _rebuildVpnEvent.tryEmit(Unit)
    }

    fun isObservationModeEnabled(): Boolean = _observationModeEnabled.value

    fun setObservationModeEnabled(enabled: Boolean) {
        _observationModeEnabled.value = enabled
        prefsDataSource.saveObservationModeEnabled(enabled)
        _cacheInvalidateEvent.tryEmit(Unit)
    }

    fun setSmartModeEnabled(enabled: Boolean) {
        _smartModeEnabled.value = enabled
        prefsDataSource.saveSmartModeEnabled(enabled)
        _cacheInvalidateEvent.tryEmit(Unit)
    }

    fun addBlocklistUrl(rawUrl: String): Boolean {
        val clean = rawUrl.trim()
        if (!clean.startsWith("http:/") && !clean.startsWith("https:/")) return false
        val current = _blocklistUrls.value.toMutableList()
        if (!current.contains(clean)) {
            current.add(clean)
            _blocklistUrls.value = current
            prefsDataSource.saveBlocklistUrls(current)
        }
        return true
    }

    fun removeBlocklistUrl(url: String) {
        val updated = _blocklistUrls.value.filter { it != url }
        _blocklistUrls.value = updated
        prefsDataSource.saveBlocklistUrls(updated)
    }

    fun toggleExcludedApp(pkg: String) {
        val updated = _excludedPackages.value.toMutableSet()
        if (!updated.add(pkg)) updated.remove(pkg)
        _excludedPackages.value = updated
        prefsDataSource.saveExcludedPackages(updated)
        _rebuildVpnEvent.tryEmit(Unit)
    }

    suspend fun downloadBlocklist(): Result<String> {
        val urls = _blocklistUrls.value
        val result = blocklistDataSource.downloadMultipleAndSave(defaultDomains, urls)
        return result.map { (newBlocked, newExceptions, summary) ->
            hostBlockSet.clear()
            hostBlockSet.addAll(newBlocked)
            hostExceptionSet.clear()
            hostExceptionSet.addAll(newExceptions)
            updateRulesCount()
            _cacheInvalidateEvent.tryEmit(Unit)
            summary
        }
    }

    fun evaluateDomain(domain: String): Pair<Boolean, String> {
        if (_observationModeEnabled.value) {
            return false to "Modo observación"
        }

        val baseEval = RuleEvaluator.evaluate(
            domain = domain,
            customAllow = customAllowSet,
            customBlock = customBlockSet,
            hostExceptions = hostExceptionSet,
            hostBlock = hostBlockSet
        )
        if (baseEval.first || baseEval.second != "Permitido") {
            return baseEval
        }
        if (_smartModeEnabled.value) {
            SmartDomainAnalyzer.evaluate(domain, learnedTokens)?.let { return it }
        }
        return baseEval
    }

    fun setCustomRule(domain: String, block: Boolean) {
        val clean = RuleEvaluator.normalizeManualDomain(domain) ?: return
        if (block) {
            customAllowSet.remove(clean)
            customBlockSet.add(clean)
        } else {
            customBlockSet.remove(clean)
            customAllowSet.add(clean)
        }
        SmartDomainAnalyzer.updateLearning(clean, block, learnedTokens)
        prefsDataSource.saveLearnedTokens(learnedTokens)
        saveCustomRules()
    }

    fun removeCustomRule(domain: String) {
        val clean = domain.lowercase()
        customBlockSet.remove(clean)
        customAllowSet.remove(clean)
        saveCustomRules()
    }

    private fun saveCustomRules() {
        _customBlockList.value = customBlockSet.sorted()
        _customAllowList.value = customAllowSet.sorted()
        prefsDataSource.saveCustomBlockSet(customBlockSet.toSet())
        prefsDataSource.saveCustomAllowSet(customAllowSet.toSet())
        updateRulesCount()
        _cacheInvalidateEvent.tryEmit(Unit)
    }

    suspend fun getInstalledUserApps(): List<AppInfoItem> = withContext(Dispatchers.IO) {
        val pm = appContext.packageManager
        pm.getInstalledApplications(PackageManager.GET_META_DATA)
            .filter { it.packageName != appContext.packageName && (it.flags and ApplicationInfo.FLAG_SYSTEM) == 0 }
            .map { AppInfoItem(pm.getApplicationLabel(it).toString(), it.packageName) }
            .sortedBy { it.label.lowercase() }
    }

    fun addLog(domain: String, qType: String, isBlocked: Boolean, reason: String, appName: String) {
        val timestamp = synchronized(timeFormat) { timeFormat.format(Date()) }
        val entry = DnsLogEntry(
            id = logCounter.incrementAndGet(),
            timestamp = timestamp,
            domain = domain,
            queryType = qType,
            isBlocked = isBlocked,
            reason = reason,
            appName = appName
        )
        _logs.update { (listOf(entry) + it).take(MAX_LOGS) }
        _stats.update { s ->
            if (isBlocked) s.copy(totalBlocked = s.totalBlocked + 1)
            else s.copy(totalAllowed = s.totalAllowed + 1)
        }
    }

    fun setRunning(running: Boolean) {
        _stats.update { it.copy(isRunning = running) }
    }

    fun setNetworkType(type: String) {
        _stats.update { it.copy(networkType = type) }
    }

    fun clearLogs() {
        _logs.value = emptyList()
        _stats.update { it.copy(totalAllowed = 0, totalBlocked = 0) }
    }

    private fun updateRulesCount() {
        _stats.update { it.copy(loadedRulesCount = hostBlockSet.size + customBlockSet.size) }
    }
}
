package com.firewall.dns.data.local

import android.content.Context

class PreferencesDataSource(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "firewall_prefs"
        private const val KEY_CUSTOM_BLOCK = "custom_block"
        private const val KEY_CUSTOM_ALLOW = "custom_allow"
        private const val KEY_EXCLUDED_APPS = "excluded_apps"
        private const val KEY_UPSTREAM_DNS = "upstream_dns"
        private const val KEY_SHOULD_RUN = "should_run"
        private const val KEY_BLOCKLIST_URLS = "blocklist_urls"
        private const val KEY_SMART_MODE = "smart_mode_enabled"
        private const val KEY_LEARNED_TOKENS = "learned_tokens"
        private const val KEY_ADBLOCK_MIGRATED = "adblock_syntax_migrated_v1"
        private const val KEY_OBSERVATION_MODE = "observation_mode_enabled"

        const val DEFAULT_ADBLOCK_URL =
            "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt"
    }

    init {
        if (!prefs.getBoolean(KEY_ADBLOCK_MIGRATED, false)) {
            prefs.edit()
                .putString(KEY_BLOCKLIST_URLS, DEFAULT_ADBLOCK_URL)
                .putBoolean(KEY_ADBLOCK_MIGRATED, true)
                .apply()
        }
    }

    fun getCustomBlockSet(): Set<String> =
        prefs.getStringSet(KEY_CUSTOM_BLOCK, emptySet())?.toSet() ?: emptySet()

    fun saveCustomBlockSet(set: Set<String>) {
        prefs.edit().putStringSet(KEY_CUSTOM_BLOCK, set).apply()
    }

    fun getCustomAllowSet(): Set<String> =
        prefs.getStringSet(KEY_CUSTOM_ALLOW, emptySet())?.toSet() ?: emptySet()

    fun saveCustomAllowSet(set: Set<String>) {
        prefs.edit().putStringSet(KEY_CUSTOM_ALLOW, set).apply()
    }

    fun getExcludedPackages(): Set<String> =
        prefs.getStringSet(KEY_EXCLUDED_APPS, emptySet())?.toSet() ?: emptySet()

    fun saveExcludedPackages(set: Set<String>) {
        prefs.edit().putStringSet(KEY_EXCLUDED_APPS, set).apply()
    }

    fun getUpstreamDns(): String =
        prefs.getString(KEY_UPSTREAM_DNS, "9.9.9.9") ?: "9.9.9.9"

    fun saveUpstreamDns(dnsIp: String) {
        prefs.edit().putString(KEY_UPSTREAM_DNS, dnsIp).apply()
    }

    fun getShouldRun(): Boolean = prefs.getBoolean(KEY_SHOULD_RUN, false)

    fun saveShouldRun(shouldRun: Boolean) {
        prefs.edit().putBoolean(KEY_SHOULD_RUN, shouldRun).apply()
    }

    fun getBlocklistUrls(): List<String> {
        val raw = prefs.getString(KEY_BLOCKLIST_URLS, null)
        return if (raw == null) listOf(DEFAULT_ADBLOCK_URL)
        else raw.lines().map { it.trim() }.filter { it.startsWith("http") }
    }

    fun saveBlocklistUrls(urls: List<String>) {
        prefs.edit().putString(KEY_BLOCKLIST_URLS, urls.joinToString("\n")).apply()
    }

    fun getSmartModeEnabled(): Boolean = prefs.getBoolean(KEY_SMART_MODE, true)

    fun saveSmartModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SMART_MODE, enabled).apply()
    }

    fun getObservationModeEnabled(): Boolean =
        prefs.getBoolean(KEY_OBSERVATION_MODE, true)

    fun saveObservationModeEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_OBSERVATION_MODE, enabled).apply()
    }

    fun getLearnedTokens(): MutableMap<String, Int> {
        val raw = prefs.getString(KEY_LEARNED_TOKENS, "") ?: ""
        val map = mutableMapOf<String, Int>()
        raw.lines().forEach { line ->
            val idx = line.indexOf('=')
            if (idx > 0) {
                val token = line.substring(0, idx)
                val score = line.substring(idx + 1).toIntOrNull()
                if (score != null) map[token] = score
            }
        }
        return map
    }

    fun saveLearnedTokens(tokens: Map<String, Int>) {
        val serialized = tokens.entries.joinToString("\n") { "${it.key}=${it.value}" }
        prefs.edit().putString(KEY_LEARNED_TOKENS, serialized).apply()
    }
}
