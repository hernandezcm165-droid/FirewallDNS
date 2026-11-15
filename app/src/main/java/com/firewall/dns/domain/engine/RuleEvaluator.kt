package com.firewall.dns.domain.engine

object RuleEvaluator {

    data class AdblockParseResult(
        val domain: String,
        val isException: Boolean
    )

    fun canonicalizeDomain(raw: String): String? {
        val cleaned = raw.trim().lowercase()
            .removePrefix("@@")
            .removePrefix("||")
            .removePrefix("*.")
            .removePrefix("https://")
            .removePrefix("http://")
            .removePrefix("ftp://")
            .substringBefore('^')
            .substringBefore('$')
            .substringBefore('?')
            .substringBefore('/')
            .substringBeforeLast(':')
            .trim()
            .trimEnd('.')

        if (cleaned.isEmpty() || cleaned.startsWith('.')) return null
        if (cleaned.any { it.isWhitespace() }) return null
        if (!cleaned.contains('.')) return null

        val labels = cleaned.split('.')
        if (labels.any { label ->
                label.isEmpty() || label.length > 63 ||
                    label.first() == '-' || label.last() == '-' ||
                    label.any { char -> !(char in 'a'..'z' || char in '0'..'9' || char == '-') }
            }
        ) return null

        return cleaned
    }

    // Supports common DNS-oriented Adblock and hosts-file formats.
    fun parseAdblockLine(raw: String): AdblockParseResult? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || trimmed.startsWith("!") || trimmed.startsWith("#")) return null

        val noComment = trimmed.substringBefore("#").trim()
        if (noComment.isEmpty()) return null

        val isException = noComment.startsWith("@@")
        val body = if (isException) noComment.removePrefix("@@") else noComment
        val candidate = when {
            body.startsWith("||") -> body.removePrefix("||")
                .substringBefore('^')
                .substringBefore('$')
            body.startsWith("0.0.0.0 ") -> body.removePrefix("0.0.0.0 ").substringBefore(' ')
            body.startsWith("127.0.0.1 ") -> body.removePrefix("127.0.0.1 ").substringBefore(' ')
            body.startsWith(":: ") -> body.removePrefix(":: ").substringBefore(' ')
            else -> body.substringBefore('^').substringBefore('$')
        }

        val domain = canonicalizeDomain(candidate) ?: return null
        return AdblockParseResult(domain = domain, isException = isException)
    }

    // Accepts a domain, URL hostname, wildcard or Adblock-style domain rule.
    fun normalizeManualDomain(raw: String): String? = canonicalizeDomain(
        raw.trim()
            .removePrefix("@@")
            .removePrefix("||")
            .removePrefix("*.")
            .substringBefore('^')
            .substringBefore('$')
    )

    fun evaluate(
        domain: String,
        customAllow: Set<String>,
        customBlock: Set<String>,
        hostExceptions: Set<String>,
        hostBlock: Set<String>
    ): Pair<Boolean, String> {
        val clean = domain.trim().trimEnd('.').lowercase()

        // Explicit user allow rules and downloaded-list exceptions take precedence over blocks.
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
