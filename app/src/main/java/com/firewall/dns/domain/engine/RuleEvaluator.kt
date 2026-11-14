package com.firewall.dns.domain.engine

object RuleEvaluator {

    fun parseHostLine(raw: String): String? {
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

    fun evaluate(
        domain: String,
        customAllow: Set<String>,
        customBlock: Set<String>,
        hostBlock: Set<String>
    ): Pair<Boolean, String> {
        val clean = domain.lowercase()
        if (matchesDomainOrParent(clean, customAllow)) return false to "Lista blanca"
        if (matchesDomainOrParent(clean, customBlock)) return true to "Regla propia"
        if (matchesDomainOrParent(clean, hostBlock)) return true to "Lista bloqueo"
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
}
