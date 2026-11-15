package com.firewall.dns.domain.engine

object RuleEvaluator {

    data class AdblockParseResult(
        val domain: String,
        val isException: Boolean
    )

    // Parsea estrictamente sintaxis estándar Adblock DNS: ||dominio.com^ y @@||dominio.com^
    fun parseAdblockLine(raw: String): AdblockParseResult? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("#")) {
            return null
        }

        val isException = trimmed.startsWith("@@||")
        val body = when {
            isException -> trimmed.removePrefix("@@||")
            trimmed.startsWith("||") -> trimmed.removePrefix("||")
            else -> return null
        }

        // Cortar en '^', '$' (modificadores) o '/'
        val domainPart = body
            .substringBefore('^')
            .substringBefore('$')
            .substringBefore('/')
            .trim()
            .lowercase()

        if (domainPart.isEmpty() || !domainPart.contains('.') || domainPart.contains('*')) {
            return null
        }
        return AdblockParseResult(domain = domainPart, isException = isException)
    }

    // Limpia entrada manual del usuario (acepta "dominio.com" o "||dominio.com^")
    fun normalizeManualDomain(raw: String): String? {
        val clean = raw.trim().lowercase()
            .removePrefix("@@||")
            .removePrefix("||")
            .removePrefix("*.")
            .substringBefore('^')
            .substringBefore('$')
            .substringBefore('/')
            .trim()
        return if (clean.contains('.') && !clean.contains(' ')) clean else null
    }

    fun evaluate(
        domain: String,
        customAllow: Set<String>,
        customBlock: Set<String>,
        hostExceptions: Set<String>,
        hostBlock: Set<String>
    ): Pair<Boolean, String> {
        val clean = domain.lowercase()
        if (matchesDomainOrParent(clean, customAllow)) return false to "Lista blanca"
        if (matchesDomainOrParent(clean, customBlock)) return true to "Regla propia"
        if (matchesDomainOrParent(clean, hostExceptions)) return false to "Excepción @@||"
        if (matchesDomainOrParent(clean, hostBlock)) return true to "Adblock ||^"
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
