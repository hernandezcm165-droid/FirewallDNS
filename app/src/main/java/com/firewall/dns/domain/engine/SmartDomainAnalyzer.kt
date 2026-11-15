package com.firewall.dns.domain.engine

object SmartDomainAnalyzer {

    // Infraestructura esencial protegida: la IA nunca los bloquea para evitar fallas
    private val CORE_SAFE_HOSTS = setOf(
        "www.facebook.com", "m.facebook.com", "web.facebook.com", "graph.facebook.com",
        "edge-mqtt.facebook.com", "star.c10r.facebook.com", "fbcdn.net", "fbsbx.com",
        "www.instagram.com", "i.instagram.com", "graph.instagram.com", "cdninstagram.com",
        "www.tiktok.com", "m.tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com",
        "tiktokcdn-eu.com", "byteoversea.com", "ibyteimg.com", "ibytedtos.com",
        "whatsapp.net", "whatsapp.com", "telegram.org", "t.me",
        "googlevideo.com", "ytimg.com", "googleapis.com", "gstatic.com",
        "googleusercontent.com", "google.com", "android.com", "samsung.com",
        "samsungcloud.com", "paypal.com", "stripe.com", "mercadopago.com", "mercadolibre.com"
    )

    private val GENERIC_TOKENS = setOf(
        "www", "api", "cdn", "m", "app", "static", "img", "image", "video",
        "media", "mobile", "edge", "gateway", "auth", "login", "account",
        "accounts", "mail", "play", "web", "v1", "v2", "v3", "s1", "s2", "com", "net", "org"
    )

    // Tokens de alta certeza (+3 puntos)
    private val HIGH_CONFIDENCE_TOKENS = setOf(
        "ads", "adserver", "adservice", "adsystem", "adtech", "adnxs",
        "doubleclick", "googlesyndication", "googleadservices", "app-measurement",
        "pixel", "beacon", "telemetry", "analytics", "tracking", "tracker",
        "crashlytics", "appsflyer", "adjust", "amplitude", "mixpanel",
        "unityads", "applovin", "ironsource", "vungle", "mopub", "chartboost",
        "taboola", "outbrain", "criteo", "pubmatic", "openx", "rubiconproject"
    )

    // Tokens sospechosos que requieren señal extra (+2 puntos)
    private val MEDIUM_TOKENS = setOf(
        "ad", "metrics", "click", "clicks", "impression", "impressions",
        "collect", "events", "log", "logs", "mon", "stat", "stats", "ping", "report"
    )

    private val META_TRACKER_TOKENS = setOf(
        "pixel", "an", "tr", "ads", "analytics", "logging", "scribe", "graph-fallback"
    )

    private val TIKTOK_TRACKER_TOKENS = setOf(
        "ads", "analytics", "log", "mon", "telemetry", "metrics", "mcs",
        "pitaya", "ad", "pangolin", "pangle"
    )

    fun evaluate(domain: String, learnedWeights: Map<String, Int>): Pair<Boolean, String>? {
        val clean = domain.lowercase()

        if (matchesDomainOrParent(clean, CORE_SAFE_HOSTS)) {
            return null
        }

        val labels = clean.split('.')
        if (labels.size < 2) return null

        val leftmost = labels.first()
        val parts = labels.dropLast(1).flatMap { it.split('-', '_') }

        // Aprendizaje previo del usuario
        val learnedScore = learnedWeights[leftmost] ?: 0
        if (learnedScore <= -2) return null
        if (learnedScore >= 2) return true to "IA Aprendido ($leftmost)"

        var score = 0
        var category = "Heurística"

        // 1. Ecosistema Meta (Facebook / Instagram)
        if (clean.endsWith(".facebook.com") || clean.endsWith(".instagram.com") || clean.endsWith(".fb.com")) {
            if (parts.any { it in META_TRACKER_TOKENS }) {
                score += 3
                category = "Meta Tracker"
            }
        }

        // 2. Ecosistema TikTok / ByteDance
        if (clean.endsWith(".tiktok.com") || clean.endsWith(".tiktokv.com") ||
            clean.endsWith(".byteoversea.com") || clean.endsWith(".tiktokw.us")
        ) {
            if (parts.any { it in TIKTOK_TRACKER_TOKENS }) {
                score += 3
                category = "TikTok Tracker"
            }
        }

        // 3. SDKs y tokens generales (comparación exacta por token, nunca rompe palabras como "upload")
        if (parts.any { it in HIGH_CONFIDENCE_TOKENS }) {
            score += 3
            if (category == "Heurística") category = "Ads/Tracker"
        } else if (parts.any { it in MEDIUM_TOKENS }) {
            score += 2
            if (category == "Heurística") category = "Telemetría"
        }

        // 4. Señales estructurales (subdominios profundos + dígitos aleatorios tipo DGA)
        if (labels.size >= 4 && score > 0) {
            score += 1
        }
        if (leftmost.length >= 10 && leftmost.count { it.isDigit() } >= 4 &&
            !leftmost.startsWith("cdn") && !leftmost.startsWith("img")
        ) {
            score += 1
        }

        // 5. Ajuste fino con lo aprendido del usuario
        score += learnedScore.coerceIn(-2, 2)

        return if (score >= 3) {
            true to "IA [${score}pts] $category"
        } else {
            null
        }
    }

    fun updateLearning(
        domain: String,
        blockedByUser: Boolean,
        weights: MutableMap<String, Int>
    ) {
        val clean = domain.lowercase()
        val firstDot = clean.indexOf('.')
        if (firstDot <= 0) return
        val token = clean.substring(0, firstDot)
        if (token in GENERIC_TOKENS || token.length < 2) return

        val current = weights[token] ?: 0
        weights[token] = if (blockedByUser) {
            (current + 1).coerceAtMost(5)
        } else {
            (current - 3).coerceAtLeast(-5)
        }
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
