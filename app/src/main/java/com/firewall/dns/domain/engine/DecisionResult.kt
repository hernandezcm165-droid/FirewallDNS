package com.firewall.dns.domain.engine

enum class RuleSource {
    MANUAL_ALLOW,
    MANUAL_BLOCK,
    LIST_EXCEPTION,
    LIST_BLOCK,
    SMART_HEURISTIC,
    CACHE
}

data class DecisionResult(
    val blocked: Boolean,
    val reason: String,
    val source: RuleSource,
    val score: Int = 0,
    val cacheTtlMs: Long = 30_000L
)
