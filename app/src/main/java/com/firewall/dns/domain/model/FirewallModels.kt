package com.firewall.dns.domain.model

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

data class DnsQueryInfo(
    val domain: String,
    val qType: Int,
    val questionEndOffset: Int
)

data class AppInfoItem(
    val label: String,
    val packageName: String
)
