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

    fun getShouldRun(): Boolean =
        prefs.getBoolean(KEY_SHOULD_RUN, false)

    fun saveShouldRun(shouldRun: Boolean) {
        prefs.edit().putBoolean(KEY_SHOULD_RUN, shouldRun).apply()
    }
}
