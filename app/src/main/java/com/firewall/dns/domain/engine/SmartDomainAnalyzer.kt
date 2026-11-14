package com.firewall.dns.domain.engine

object SmartDomainAnalyzer {

    // Dominios esenciales que la heurística NUNCA debe tocar para no romper videos, chats o login
    private val CORE_SAFE_HOSTS = setOf(
        "www.facebook.com", "m.facebook.com", "web.facebook.com", "graph.facebook.com",
        "edge-mqtt.facebook.com", "star.c10r.facebook.com", "fbcdn.net", "fbsbx.com",
        "www.instagram.com", "i.instagram.com", "graph.instagram.com", "cdninstagram.com",
        "www.tiktok.com", "m.tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com",
        "tiktokcdn-eu.com", "byteoversea.com", "ibyteimg.com", "ibytedtos.com",
        "whatsapp.net", "whatsapp.com", "googlevideo.com", "ytimg.com",
        "googleapis.com", "gstatic.com", "googleusercontent.com", "google.com",
        "android.com", "samsung.com", "samsungcloud.com"
    )

    // Palabras genéricas que nunca deben aprenderse como patrón sospechoso
    private val GENERIC_TOKENS = setOf(
        "www", "api", "cdn", "m", "app", "static", "img", "image", "video",
        "media", "mobile", "edge", "gateway", "auth", "login", "account",
        "accounts", "mail", "play", "web", "v1", "v2", "v3", "s1", "s2"
    )

    // Patrones específicos de rastreo/ads en Meta, TikTok y SDKs generales
    private val TELEMETRY_PREFIXES = listOf(
        "ads.", "ads-", "adservice", "adsystem", "adserver", "analytics",
        "telemetry", "metrics", "pixel", "beacon", "tracker", "tracking",
        "app-measurement", "doubleclick", "log-upload", "log-va", "log-sg",
        "log-useast", "mon-va", "mon-sg", "mon.", "scribe.", "crashlytics"
    )

    fun evaluate(domain: String, learnedWeights: Map<String, Int>): Pair<Boolean, String>? {
        val clean = domain.lowercase()

        // 1. Escudo anti-fallas: si es infraestructura esencial, nunca bloquear por heurística
        if (matchesDomainOrParent(clean, CORE_SAFE_HOSTS)) {
            return null
        }

        val token = extractLeftToken(clean)
        val learnedScore = if (token != null) (learnedWeights[token] ?: 0) else 0

        // Si el usuario ya corrigió este patrón antes como falso positivo, respetarlo
        if (learnedScore <= -2) {
            return null
        }

        // 2. Aprendizaje positivo previo del usuario
        if (learnedScore >= 2 && token != null) {
            return true to "IA Aprendido ($token)"
        }

        // 3. Detección específica Meta (Facebook / Instagram) y TikTok
        if (clean.endsWith(".facebook.com") || clean.endsWith(".instagram.com")) {
            if (clean.startsWith("pixel.") || clean.startsWith("an.") ||
                clean.startsWith("tr.") || clean.startsWith("ads.") ||
                clean.contains("analytics") || clean.contains("logging")
            ) {
                return true to "IA (Meta Tracker)"
            }
        }

        if (clean.endsWith(".tiktok.com") || clean.endsWith(".tiktokv.com") ||
            clean.endsWith(".byteoversea.com")
        ) {
            if (clean.startsWith("ads") || clean.startsWith("analytics") ||
                clean.startsWith("log") || clean.startsWith("mon") ||
                clean.contains("telemetry") || clean.contains("metrics")
            ) {
                return true to "IA (TikTok Tracker)"
            }
        }

        // 4. Patrones generales de publicidad / telemetría
        if (TELEMETRY_PREFIXES.any { clean.startsWith(it) || clean.contains(".$it") }) {
            return true to "IA (Telemetría/Ads)"
        }

        return null
    }

    fun updateLearning(
        domain: String,
        blockedByUser: Boolean,
        weights: MutableMap<String, Int>
    ) {
        val token = extractLeftToken(domain.lowercase()) ?: return
        if (token in GENERIC_TOKENS || token.length < 3) return
        val current = weights[token] ?: 0
        val updated = if (blockedByUser) (current + 1).coerceAtMost(5) else (current - 3).coerceAtLeast(-5)
        weights[token] = updated
    }

    private fun extractLeftToken(domain: String): String? {
        val firstDot = domain.indexOf('.')
        if (firstDot <= 0) return null
        return domain.substring(0, firstDot)
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
