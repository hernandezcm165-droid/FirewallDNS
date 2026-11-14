package com.firewall.dns

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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

class DnsFirewallService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunReaderThread: Thread? = null
    private val executor = Executors.newFixedThreadPool(12)
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
            val active = connectivityManager.activeNetwork
            if (active == null) FirewallRepository.setNetworkType("Sin red")
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
            FirewallRepository.cacheInvalidateEvent.collect { dnsCache.clear() }
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
                FirewallRepository.setShouldRun(applicationContext, false)
                stopVpn()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                FirewallRepository.setShouldRun(applicationContext, true)
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

    private fun startForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Firewall DNS", NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val openAppIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 1,
            Intent(this, DnsFirewallService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Firewall DNS activo")
            .setContentText("Protegiendo IPv4/IPv6 y bloqueando fugas DNS")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentIntent(openAppIntent)
            .addAction(android.R.drawable.ic_media_pause, "Detener", stopIntent)
            .setOngoing(true)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else 0

        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
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

        TRAPPED_IPV4_DNS.filter { it != upstream }.forEach { ip ->
            builder.addRoute(ip, 32)
        }
        TRAPPED_IPV6_DNS.forEach { ip ->
            builder.addRoute(ip, 128)
        }

        val excluded = FirewallRepository.excludedPackages.value + packageName
        excluded.forEach { pkg ->
            try {
                builder.addDisallowedApplication(pkg)
            } catch (_: PackageManager.NameNotFoundException) {}
        }

        vpnInterface = builder.establish() ?: return
        isRunning = true
        FirewallRepository.setRunning(true)

        val fd = vpnInterface!!.fileDescriptor
        val input = FileInputStream(fd)
        val output = FileOutputStream(fd)

        tunReaderThread = Thread {
            val buffer = ByteArray(4096)
            try {
                while (isRunning && !Thread.interrupted()) {
                    val length = input.read(buffer)
                    if (length < 0) break
                    if (length > 0) {
                        val copy = buffer.copyOf(length)
                        executor.execute { handlePacket(copy, output) }
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
                protocol = req[9].toInt() and 0xFF
                srcIp = req.copyOfRange(12, 16)
                dstIp = req.copyOfRange(16, 20)
            }
            6 -> {
                if (req.size < 40) return
                isIpv6 = true
                ipHeaderLen = 40
                protocol = req[6].toInt() and 0xFF
                srcIp = req.copyOfRange(8, 24)
                dstIp = req.copyOfRange(24, 40)
            }
            else -> return
        }

        if (protocol == 6 && req.size >= ipHeaderLen + 20) {
            buildTcpRstResponse(req, isIpv6, ipHeaderLen, srcIp, dstIp)?.let {
                writePacket(output, it)
            }
            return
        }

        if (protocol != 17 || req.size < ipHeaderLen + 8) return
        val srcPort = readU16(req, ipHeaderLen)
        val dstPort = readU16(req, ipHeaderLen + 2)
        if (dstPort != 53) return

        val dnsPayload = req.copyOfRange(ipHeaderLen + 8, req.size)
        val query = parseDnsQuery(dnsPayload) ?: return

        val appName = resolveAppName(srcIp, srcPort, dstIp, dstPort)
        val (shouldBlock, reason) = FirewallRepository.evaluateDomain(query.domain)

        val dnsResponse: ByteArray?
        val finalReason: String

        if (shouldBlock) {
            dnsResponse = buildNxDomainResponse(dnsPayload, query.questionEndOffset)
            finalReason = reason
        } else {
            val cacheKey = "${query.domain.lowercase()}:${query.qType}"
            val cached = dnsCache[cacheKey]
            val now = System.currentTimeMillis()

            if (cached != null && cached.expiresAt > now) {
                dnsResponse = cached.response.copyOf().apply {
                    this[0] = dnsPayload[0]
                    this[1] = dnsPayload[1]
                }
                finalReason = "$reason (Caché)"
            } else {
                dnsResponse = forwardDnsQuery(dnsPayload)
                if (dnsResponse != null) {
                    if (dnsCache.size > 600) dnsCache.clear()
                    dnsCache[cacheKey] = CachedDns(dnsResponse, now + CACHE_TTL_MS)
                }
                finalReason = reason
            }
        }

        FirewallRepository.addLog(
            domain = query.domain,
            qType = queryTypeToString(query.qType),
            isBlocked = shouldBlock,
            reason = finalReason,
            appName = appName
        )

        if (dnsResponse != null) {
            val responsePacket = if (isIpv6) {
                buildIpv6UdpResponse(srcIp, dstIp, srcPort, dstPort, dnsResponse)
            } else {
                buildIpv4UdpResponse(req, srcIp, dstIp, srcPort, dstPort, dnsResponse)
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
            } catch (_: Exception) {
                "UID $uid"
            }
        }
    }

    private data class DnsQueryInfo(val domain: String, val qType: Int, val questionEndOffset: Int)

    private fun parseDnsQuery(dns: ByteArray): DnsQueryInfo? {
        if (dns.size < 17) return null
        var pos = 12
        val sb = StringBuilder()

        while (pos < dns.size) {
            val len = dns[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                break
            }
            if ((len and 0xC0) == 0xC0) return null
            pos += 1
            if (pos + len > dns.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(dns, pos, len, Charsets.US_ASCII))
            pos += len
        }

        if (pos + 4 > dns.size || sb.isEmpty()) return null
        return DnsQueryInfo(sb.toString(), readU16(dns, pos), pos + 4)
    }

    private fun queryTypeToString(qType: Int): String = when (qType) {
        1 -> "A"; 28 -> "AAAA"; 65 -> "HTTPS"; 5 -> "CNAME"; 16 -> "TXT"; else -> "T$qType"
    }

    private fun buildNxDomainResponse(reqDns: ByteArray, qEnd: Int): ByteArray {
        val resp = reqDns.copyOfRange(0, qEnd)
        resp[2] = 0x81.toByte()
        resp[3] = 0x83.toByte()
        resp[6] = 0; resp[7] = 0; resp[8] = 0; resp[9] = 0; resp[10] = 0; resp[11] = 0
        return resp
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
            val resp = DatagramPacket(buf, buf.size)
            socket.receive(resp)
            buf.copyOf(resp.length)
        } catch (_: Exception) {
            null
        } finally {
            socket?.close()
        }
    }

    private fun buildIpv4UdpResponse(
        req: ByteArray, reqSrcIp: ByteArray, reqDstIp: ByteArray,
        reqSrcPort: Int, reqDstPort: Int, dnsResp: ByteArray
    ): ByteArray {
        val ipLen = 20
        val udpLen = 8 + dnsResp.size
        val total = ipLen + udpLen
        val res = ByteArray(total)

        res[0] = 0x45.toByte()
        writeU16(res, 2, total)
        res[4] = req[4]; res[5] = req[5]
        res[6] = 0x40.toByte()
        res[8] = 64.toByte()
        res[9] = 17.toByte()
        System.arraycopy(reqDstIp, 0, res, 12, 4)
        System.arraycopy(reqSrcIp, 0, res, 16, 4)
        writeU16(res, 10, onesComplementSum(res, 0, ipLen))

        writeU16(res, 20, reqDstPort)
        writeU16(res, 22, reqSrcPort)
        writeU16(res, 24, udpLen)
        System.arraycopy(dnsResp, 0, res, 28, dnsResp.size)

        val udpSum = ipv4TransportChecksum(reqDstIp, reqSrcIp, 17, res, 20, udpLen)
        writeU16(res, 26, if (udpSum == 0) 0xFFFF else udpSum)
        return res
    }

    private fun buildIpv6UdpResponse(
        reqSrcIp: ByteArray, reqDstIp: ByteArray,
        reqSrcPort: Int, reqDstPort: Int, dnsResp: ByteArray
    ): ByteArray {
        val ipLen = 40
        val udpLen = 8 + dnsResp.size
        val res = ByteArray(ipLen + udpLen)

        res[0] = 0x60.toByte()
        writeU16(res, 4, udpLen)
        res[6] = 17.toByte()
        res[7] = 64.toByte()
        System.arraycopy(reqDstIp, 0, res, 8, 16)
        System.arraycopy(reqSrcIp, 0, res, 24, 16)

        writeU16(res, 40, reqDstPort)
        writeU16(res, 42, reqSrcPort)
        writeU16(res, 44, udpLen)
        System.arraycopy(dnsResp, 0, res, 48, dnsResp.size)

        val udpSum = ipv6TransportChecksum(reqDstIp, reqSrcIp, 17, res, 40, udpLen)
        writeU16(res, 46, if (udpSum == 0) 0xFFFF else udpSum)
        return res
    }

    private fun buildTcpRstResponse(
        req: ByteArray, isIpv6: Boolean, reqIhl: Int, srcIp: ByteArray, dstIp: ByteArray
    ): ByteArray? {
        val tcpFlags = req[reqIhl + 13].toInt() and 0xFF
        if ((tcpFlags and 0x04) != 0) return null

        val isSyn = (tcpFlags and 0x02) != 0
        val hasAck = (tcpFlags and 0x10) != 0
        val reqTcpDataOff = ((req[reqIhl + 12].toInt() ushr 4) and 0x0F) * 4
        val reqPayloadLen = (req.size - reqIhl - reqTcpDataOff).coerceAtLeast(0)
        val reqSeq = readU32(req, reqIhl + 4)
        val reqAck = readU32(req, reqIhl + 8)

        val ipLen = if (isIpv6) 40 else 20
        val tcpLen = 20
        val res = ByteArray(ipLen + tcpLen)

        if (isIpv6) {
            res[0] = 0x60.toByte()
            writeU16(res, 4, tcpLen)
            res[6] = 6.toByte()
            res[7] = 64.toByte()
            System.arraycopy(dstIp, 0, res, 8, 16)
            System.arraycopy(srcIp, 0, res, 24, 16)
        } else {
            res[0] = 0x45.toByte()
            writeU16(res, 2, ipLen + tcpLen)
            res[6] = 0x40.toByte()
            res[8] = 64.toByte()
            res[9] = 6.toByte()
            System.arraycopy(dstIp, 0, res, 12, 4)
            System.arraycopy(srcIp, 0, res, 16, 4)
            writeU16(res, 10, onesComplementSum(res, 0, ipLen))
        }

        res[ipLen] = req[reqIhl + 2]; res[ipLen + 1] = req[reqIhl + 3]
        res[ipLen + 2] = req[reqIhl]; res[ipLen + 3] = req[reqIhl + 1]

        if (hasAck) {
            writeU32(res, ipLen + 4, reqAck)
            writeU32(res, ipLen + 8, 0L)
            res[ipLen + 13] = 0x04.toByte()
        } else {
            writeU32(res, ipLen + 4, 0L)
            val ackVal = (reqSeq + reqPayloadLen + (if (isSyn) 1L else 0L)) and 0xFFFFFFFFL
            writeU32(res, ipLen + 8, ackVal)
            res[ipLen + 13] = 0x14.toByte()
        }
        res[ipLen + 12] = 0x50.toByte()

        val tcpSum = if (isIpv6) {
            ipv6TransportChecksum(dstIp, srcIp, 6, res, ipLen, tcpLen)
        } else {
            ipv4TransportChecksum(dstIp, srcIp, 6, res, ipLen, tcpLen)
        }
        writeU16(res, ipLen + 16, tcpSum)
        return res
    }

    private fun ipv4TransportChecksum(
        src: ByteArray, dst: ByteArray, proto: Int, buf: ByteArray, offset: Int, len: Int
    ): Int {
        var sum = 0
        for (i in 0 until 4 step 2) sum += readU16(src, i) + readU16(dst, i)
        sum += proto + len
        return foldAndInvert(sum + rawWordSum(buf, offset, len))
    }

    private fun ipv6TransportChecksum(
        src: ByteArray, dst: ByteArray, proto: Int, buf: ByteArray, offset: Int, len: Int
    ): Int {
        var sum = 0
        for (i in 0 until 16 step 2) sum += readU16(src, i) + readU16(dst, i)
        sum += (len ushr 16) + (len and 0xFFFF) + proto
        return foldAndInvert(sum + rawWordSum(buf, offset, len))
    }

    private fun onesComplementSum(buf: ByteArray, offset: Int, len: Int): Int =
        foldAndInvert(rawWordSum(buf, offset, len))

    private fun rawWordSum(buf: ByteArray, offset: Int, len: Int): Int {
        var sum = 0
        var i = offset
        val end = offset + len
        while (i + 1 < end) {
            sum += readU16(buf, i)
            i += 2
        }
        if (i < end) sum += (buf[i].toInt() and 0xFF) shl 8
        return sum
    }

    private fun foldAndInvert(initialSum: Int): Int {
        var sum = initialSum
        while ((sum ushr 16) != 0) sum = (sum and 0xFFFF) + (sum ushr 16)
        return sum.inv() and 0xFFFF
    }

    private fun readU16(b: ByteArray, i: Int): Int =
        ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

    private fun writeU16(b: ByteArray, i: Int, v: Int) {
        b[i] = ((v ushr 8) and 0xFF).toByte()
        b[i + 1] = (v and 0xFF).toByte()
    }

    private fun readU32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xFF) shl 24) or ((b[i + 1].toLong() and 0xFF) shl 16) or
                ((b[i + 2].toLong() and 0xFF) shl 8) or (b[i + 3].toLong() and 0xFF)

    private fun writeU32(b: ByteArray, i: Int, v: Long) {
        b[i] = ((v ushr 24) and 0xFF).toByte()
        b[i + 1] = ((v ushr 16) and 0xFF).toByte()
        b[i + 2] = ((v ushr 8) and 0xFF).toByte()
        b[i + 3] = (v and 0xFF).toByte()
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
        FirewallRepository.setShouldRun(applicationContext, false)
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
