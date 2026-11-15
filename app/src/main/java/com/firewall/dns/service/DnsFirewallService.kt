package com.firewall.dns.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.firewall.dns.MainActivity
import com.firewall.dns.data.repository.FirewallRepository
import com.firewall.dns.domain.engine.DnsPacketEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DnsFirewallService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunReaderThread: Thread? = null
    private val executor = ThreadPoolExecutor(
        4, 4, 0L, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue(256),
        ThreadPoolExecutor.CallerRunsPolicy()
    )
    private val writeLock = Any()
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var isRunning = false

    private data class CachedDns(val response: ByteArray, val expiresAt: Long)
    private val dnsCache = ConcurrentHashMap<String, CachedDns>()
    private val uidAppCache = ConcurrentHashMap<Int, String>()

    private lateinit var connectivityManager: ConnectivityManager

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val type = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Datos móviles"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Otra red"
            }
            FirewallRepository.setNetworkType(type)
        }

        override fun onLost(network: Network) {
            if (connectivityManager.activeNetwork == null) FirewallRepository.setNetworkType("Sin red")
        }
    }

    companion object {
        const val ACTION_START = "ACTION_START_DNS_VPN"
        const val ACTION_STOP = "ACTION_STOP_DNS_VPN"
        private const val CHANNEL_ID = "dns_firewall_channel"
        private const val NOTIFICATION_ID = 2001
        private const val CACHE_TTL_MS = 60_000L
        private val TRAPPED_IPV4_DNS = listOf("8.8.8.8", "8.8.4.4", "1.1.1.1", "1.0.0.1")
        private val TRAPPED_IPV6_DNS = listOf(
            "2001:4860:4860::8888", "2001:4860:4860::8844",
            "2606:4700:4700::1111", "2606:4700:4700::1001"
        )
    }

    override fun onCreate() {
        super.onCreate()
        FirewallRepository.init(applicationContext)
        connectivityManager = getSystemService(ConnectivityManager::class.java)

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            .build()
        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
        } catch (_: Exception) {}

        serviceScope.launch {
            FirewallRepository.cacheInvalidateEvent.collect {
                dnsCache.clear()
                if (isRunning) updateForegroundNotification()
            }
        }
        serviceScope.launch {
            FirewallRepository.rebuildVpnEvent.collect {
                if (isRunning) startVpn()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                FirewallRepository.setShouldRun(false)
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                FirewallRepository.setShouldRun(true)
                startForegroundNotification()
                startVpn()
            }
            null -> {
                startForegroundNotification()
                startVpn()
            }
        }
        return START_STICKY
    }

    private fun notificationText(): String = if (FirewallRepository.isObservationModeEnabled()) {
        "Monitoreo + bloqueo por reglas DNS; DoH/DoT no se inspecciona"
    } else {
        "Bloqueo DNS activo; DoH/DoT no se inspecciona"
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setContentTitle("Firewall DNS activo")
        .setContentText(notificationText())
        .setSmallIcon(android.R.drawable.ic_lock_lock)
        .setContentIntent(
            PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .addAction(
            android.R.drawable.ic_media_pause,
            "Detener",
            PendingIntent.getService(
                this, 1,
                Intent(this, DnsFirewallService::class.java).apply { action = ACTION_STOP },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .setOngoing(true)
        .build()

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Firewall DNS", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), fgsType)
    }

    private fun updateForegroundNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
    }

    @Synchronized
    private fun startVpn() {
        closeInterface()
        val upstream = FirewallRepository.getUpstreamDns()
        val builder = Builder()
            .setSession("FirewallDNS")
            .addAddress("10.10.10.2", 32)
            .addDnsServer("10.10.10.1")
            .addRoute("10.10.10.1", 32)
            .addAddress("fd00:10:10::2", 128)
            .addDnsServer("fd00:10:10::1")
            .addRoute("fd00:10:10::1", 128)
        TRAPPED_IPV4_DNS.filter { it != upstream }.forEach { builder.addRoute(it, 32) }
        TRAPPED_IPV6_DNS.forEach { builder.addRoute(it, 128) }

        (FirewallRepository.excludedPackages.value + packageName).forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {}
        }

        val established = builder.establish()
        if (established == null) {
            stopVpn()
            return
        }
        vpnInterface = established
        isRunning = true
        FirewallRepository.setRunning(true)
        val input = FileInputStream(established.fileDescriptor)
        val output = FileOutputStream(established.fileDescriptor)

        tunReaderThread = Thread {
            val buffer = ByteArray(4096)
            try {
                while (isRunning && !Thread.interrupted()) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    if (length > 0) {
                        val copy = buffer.copyOf(length)
                        executor.execute {
                            try {
                                handlePacket(copy, output)
                            } catch (_: Throwable) {
                                // Un paquete inválido no debe cerrar el servicio.
                            }
                        }
                    }
                }
            } catch (_: IOException) {}
        }.apply { start() }
    }

    private fun handlePacket(req: ByteArray, output: FileOutputStream) {
        if (req.isEmpty()) return
        val version = (req[0].toInt() ushr 4) and 0x0F
        val isIpv6: Boolean
        val ipHeaderLen: Int
        val protocol: Int
        val srcIp: ByteArray
        val dstIp: ByteArray

        when (version) {
            4 -> {
                if (req.size < 20) return
                isIpv6 = false
                ipHeaderLen = (req[0].toInt() and 0x0F) * 4
                if (ipHeaderLen < 20 || req.size < ipHeaderLen) return
                val fragmentField = DnsPacketEngine.readU16(req, 6)
                if ((fragmentField and 0x3FFF) != 0) return
                protocol = req[9].toInt() and 0xFF
                srcIp = req.copyOfRange(12, 16)
                dstIp = req.copyOfRange(16, 20)
            }
            6 -> {
                if (req.size < 40) return
                isIpv6 = true
                ipHeaderLen = 40
                protocol = req[6].toInt() and 0xFF
                if (protocol != 17 && protocol != 6) return
                srcIp = req.copyOfRange(8, 24)
                dstIp = req.copyOfRange(24, 40)
            }
            else -> return
        }

        if (protocol == 6) {
            if (req.size < ipHeaderLen + 20) return
            val tcpDestinationPort = DnsPacketEngine.readU16(req, ipHeaderLen + 2)
            if (tcpDestinationPort == 53) {
                DnsPacketEngine.buildTcpRstResponse(req, isIpv6, ipHeaderLen, srcIp, dstIp)?.let {
                    writePacket(output, it)
                }
            }
            return
        }

        if (protocol != 17 || req.size < ipHeaderLen + 8) return
        val srcPort = DnsPacketEngine.readU16(req, ipHeaderLen)
        val dstPort = DnsPacketEngine.readU16(req, ipHeaderLen + 2)
        val udpLength = DnsPacketEngine.readU16(req, ipHeaderLen + 4)
        if (dstPort != 53 || udpLength < 8 || ipHeaderLen + udpLength > req.size) return

        val dnsPayload = req.copyOfRange(ipHeaderLen + 8, ipHeaderLen + udpLength)
        val query = DnsPacketEngine.parseDnsQuery(dnsPayload) ?: return
        val appName = resolveAppName(srcIp, srcPort, dstIp, dstPort)
        val (ruleWouldBlock, ruleReason) = FirewallRepository.evaluateDomain(query.domain)
        val observationMode = FirewallRepository.isObservationModeEnabled()

        // Monitoreo registra el contexto, pero las reglas de bloqueo se aplican en ambos modos.
        val shouldBlock = ruleWouldBlock
        val finalReason = when {
            observationMode && shouldBlock -> "Monitoreo + bloqueo: $ruleReason"
            observationMode -> "Monitoreo: permitido"
            else -> ruleReason
        }

        val dnsResponse: ByteArray?
        if (shouldBlock) {
            dnsResponse = DnsPacketEngine.buildNxDomainResponse(dnsPayload, query.questionEndOffset)
        } else {
            val cacheKey = "${query.domain.lowercase()}:${query.qType}:${FirewallRepository.getUpstreamDns()}"
            val now = System.currentTimeMillis()
            val cached = dnsCache[cacheKey]
            if (cached != null && cached.expiresAt > now) {
                dnsResponse = cached.response.copyOf().apply {
                    this[0] = dnsPayload[0]
                    this[1] = dnsPayload[1]
                }
            } else {
                dnsResponse = forwardDnsQuery(dnsPayload)
                if (dnsResponse != null) {
                    if (dnsCache.size > 600) dnsCache.clear()
                    dnsCache[cacheKey] = CachedDns(dnsResponse, now + CACHE_TTL_MS)
                }
            }
        }

        FirewallRepository.addLog(
            domain = query.domain,
            qType = DnsPacketEngine.queryTypeToString(query.qType),
            isBlocked = shouldBlock,
            reason = finalReason,
            appName = appName
        )

        if (dnsResponse != null) {
            val responsePacket = if (isIpv6) {
                DnsPacketEngine.buildIpv6UdpResponse(srcIp, dstIp, srcPort, dstPort, dnsResponse)
            } else {
                DnsPacketEngine.buildIpv4UdpResponse(req, srcIp, dstIp, srcPort, dstPort, dnsResponse)
            }
            writePacket(output, responsePacket)
        }
    }

    private fun writePacket(output: FileOutputStream, data: ByteArray) {
        synchronized(writeLock) {
            try {
                output.write(data)
            } catch (_: IOException) {}
        }
    }

    private fun resolveAppName(srcIp: ByteArray, srcPort: Int, dstIp: ByteArray, dstPort: Int): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return "Sistema / Red"
        val uid = try {
            connectivityManager.getConnectionOwnerUid(
                OsConstants.IPPROTO_UDP,
                InetSocketAddress(InetAddress.getByAddress(srcIp), srcPort),
                InetSocketAddress(InetAddress.getByAddress(dstIp), dstPort)
            )
        } catch (_: Exception) {
            Process.INVALID_UID
        }
        if (uid == Process.INVALID_UID || uid <= 0) return "Sistema / DNS"
        return uidAppCache.getOrPut(uid) {
            try {
                val pkgs = packageManager.getPackagesForUid(uid)
                if (!pkgs.isNullOrEmpty()) {
                    val info = packageManager.getApplicationInfo(pkgs[0], 0)
                    packageManager.getApplicationLabel(info).toString()
                } else "UID $uid"
            } catch (_: Exception) { "UID $uid" }
        }
    }

    private fun forwardDnsQuery(dnsPayload: ByteArray): ByteArray? {
        var socket: DatagramSocket? = null
        return try {
            socket = DatagramSocket()
            socket.soTimeout = 3500
            if (!protect(socket)) return null
            val addr = InetAddress.getByName(FirewallRepository.getUpstreamDns())
            socket.send(DatagramPacket(dnsPayload, dnsPayload.size, addr, 53))
            val buf = ByteArray(4096)
            val response = DatagramPacket(buf, buf.size)
            socket.receive(response)
            if (response.length >= 12) buf.copyOf(response.length) else null
        } catch (_: Exception) {
            null
        } finally {
            socket?.close()
        }
    }

    private fun closeInterface() {
        isRunning = false
        FirewallRepository.setRunning(false)
        tunReaderThread?.interrupt()
        tunReaderThread = null
        try { vpnInterface?.close() } catch (_: IOException) {}
        vpnInterface = null
    }

    private fun stopVpn() {
        closeInterface()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        FirewallRepository.setShouldRun(false)
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        serviceScope.cancel()
        closeInterface()
        executor.shutdownNow()
        super.onDestroy()
    }
}
