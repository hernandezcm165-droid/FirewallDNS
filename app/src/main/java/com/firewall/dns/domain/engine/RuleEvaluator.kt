package com.firewall.dns.domain.engine

object RuleEvaluator {

    data class AdblockParseResult(
        val domain: String,
        val isException: Boolean
    )

    private val INVALID_DOMAIN_CHARS = setOf(' ', '*', '"', '\'', '(', ')', '\\')

    fun canonicalizeDomain(raw: String): String? {
        val cleaned = raw.trim().lowercase()
            .removePrefix("@@")
            .removePrefix("||")
            .removePrefix("*.")
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("ftp://")
            .substringBefore('?')
            .substringBefore('/')
            .substringBeforeLast(':')
            .trim()
            .trimEnd('.')

        if (cleaned.isEmpty() || cleaned.startsWith(".")) return null
        if (cleaned.containsAny { it.isWhitespace() } || cleaned.any { it in INVALID_DOMAIN_CHARS }) return null
        if (!cleaned.contains('.')) return null
        val labels = cleaned.split('.')
        if (labels.any { it.isEmpty() || it.length > 63 }) return null
        return cleaned
    }

    // Parsea sintaxis estándar Adblock DNS con soporte para líneas tipo: ||dominio.com^, @@||dominio.com^, 0.0.0.0 dominio.com
    fun parseAdblockLine(raw: String): AdblockParseResult? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("#")) {
            return null
        }

        val noComment = trimmed.substringBefore("#").trim()
        if (noComment.isEmpty()) return null

        val isException = noComment.startsWith("@@")
        val body = when {
            isException -> noComment.removePrefix("@@")
            else -> noComment
        }.trim()

        val candidate = when {
            body.startsWith("||") -> body.removePrefix("||")
            body.startsWith("0.0.0.0 ") -> body.removePrefix("0.0.0.0 ")
            body.startsWith("127.0.0.1 ") -> body.removePrefix("127.0.0.1 ")
            body.startsWith(":: ") -> body.removePrefix(":: ")
            else -> body
        }

        val domainPart = canonicalizeDomain(candidate)
            ?: return null

        return AdblockParseResult(domain = domainPart, isException = isException)
    }

    // Limpia entrada manual del usuario (acepta "dominio.com", "www.dominio.com", "||dominio.com^")
    fun normalizeManualDomain(raw: String): String? {
        val candidate = raw.trim().lowercase()
            .removePrefix("@@")
            .removePrefix("||")
            .removePrefix("*.")
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("ftp://")
            .substringBefore('^')
            .substringBefore('$')
            .substringBefore('/')
            .substringBefore('?')
            .trim()
            .trimEnd('.')

        if (candidate.isEmpty()) return null
        return canonicalizeDomain(candidate)
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
        if (matchesDomainOrParent(clean, hostExceptions)) return false to "Excepción @@||"
        if (matchesDomainOrParent(clean, customBlock)) return true to "Regla propia"
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
