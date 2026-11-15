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
        val decision = evaluateDecision(domain, customAllow, customBlock, hostExceptions, hostBlock)
        return decision.blocked to decision.reason
    }

    fun evaluateDecision(
        domain: String,
        customAllow: Set<String>,
        customBlock: Set<String>,
        hostExceptions: Set<String>,
        hostBlock: Set<String>
    ): DecisionResult {
        val clean = domain.trim().trimEnd('.').lowercase()

        if (matchesDomainOrParent(clean, customAllow)) {
            return DecisionResult(false, "Lista blanca", RuleSource.MANUAL_ALLOW)
        }
        if (matchesDomainOrParent(clean, hostExceptions)) {
            return DecisionResult(false, "Excepción @@||", RuleSource.LIST_EXCEPTION)
        }
        if (matchesDomainOrParent(clean, customBlock)) {
            return DecisionResult(true, "Regla propia", RuleSource.MANUAL_BLOCK)
        }
        if (matchesDomainOrParent(clean, hostBlock)) {
            return DecisionResult(true, "Adblock ||^", RuleSource.LIST_BLOCK)
        }
        return DecisionResult(false, "Permitido", RuleSource.CACHE, cacheTtlMs = 30_000L)
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
