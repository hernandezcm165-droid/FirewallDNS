package com.firewall.dns

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startFirewallService()
    }

    private val notifPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        requestAndStartVpn()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FirewallRepository.init(applicationContext)

        if (FirewallRepository.getShouldRun(this) && !FirewallRepository.stats.value.isRunning) {
            if (VpnService.prepare(this) == null) startFirewallService()
        }

        setContent {
            MaterialTheme {
                FirewallApp(
                    onToggleVpn = { enable ->
                        if (enable) checkPermissionsAndStart() else stopFirewallService()
                    },
                    onOpenBatteryOpt = { requestIgnoreBatteryOptimization() },
                    onOpenVpnSettings = { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                )
            }
        }
    }

    private fun checkPermissionsAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            requestAndStartVpn()
        }
    }

    private fun requestAndStartVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) vpnPermissionLauncher.launch(intent) else startFirewallService()
    }

    private fun startFirewallService() {
        val intent = Intent(this, DnsFirewallService::class.java).apply {
            action = DnsFirewallService.ACTION_START
        }
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopFirewallService() {
        startService(Intent(this, DnsFirewallService::class.java).apply {
            action = DnsFirewallService.ACTION_STOP
        })
    }

    @SuppressLint("BatteryLife")
    private fun requestIgnoreBatteryOptimization() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "La optimización ya está desactivada", Toast.LENGTH_SHORT).show()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FirewallApp(
    onToggleVpn: (Boolean) -> Unit,
    onOpenBatteryOpt: () -> Unit,
    onOpenVpnSettings: () -> Unit
) {
    val stats by FirewallRepository.stats.collectAsState()
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Registro", "Reglas", "Apps / Ajustes")

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Column {
                            Text("Firewall DNS", fontWeight = FontWeight.Bold)
                            Text(
                                text = "Red: ${stats.networkType} • DNS: ${stats.upstreamDns}",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    actions = {
                        Switch(
                            checked = stats.isRunning,
                            onCheckedChange = onToggleVpn,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                )
                TabRow(selectedTabIndex = selectedTab) {
                    tabs.forEachIndexed { index, title ->
                        Tab(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            text = { Text(title) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                0 -> LogsTab(stats)
                1 -> RulesTab(stats)
                2 -> AppsAndSettingsTab(stats, onOpenBatteryOpt, onOpenVpnSettings)
            }
        }
    }
}

@Composable
fun LogsTab(stats: FirewallStats) {
    val context = LocalContext.current
    val logs by FirewallRepository.logs.collectAsState()
    var onlyBlocked by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filtered = remember(logs, onlyBlocked, searchQuery) {
        logs.filter {
            (!onlyBlocked || it.isBlocked) &&
                (searchQuery.isBlank() || it.domain.contains(searchQuery, ignoreCase = true))
        }
    }

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Bloqueados", stats.totalBlocked.toString(), Color(0xFFD32F2F), Modifier.weight(1f))
            StatCard("Permitidos", stats.totalAllowed.toString(), Color(0xFF388E3C), Modifier.weight(1f))
            StatCard("Reglas", stats.loadedRulesCount.toString(), Color(0xFF1976D2), Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Buscar dominio...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = onlyBlocked,
                onClick = { onlyBlocked = !onlyBlocked },
                label = { Text("Bloq.") }
            )
            IconButton(onClick = { FirewallRepository.clearLogs() }) {
                Icon(Icons.Default.Delete, contentDescription = "Limpiar")
            }
        }

        Spacer(Modifier.height(8.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (stats.isRunning) "Esperando consultas DNS..." else "Enciende el interruptor arriba para empezar",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(filtered, key = { it.id }) { entry ->
                    val bg = if (entry.isBlocked) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
                    val fg = if (entry.isBlocked) Color(0xFFC62828) else Color(0xFF2E7D32)
                    Card(colors = CardDefaults.cardColors(containerColor = bg), modifier = Modifier.fillMaxWidth()) {
                        Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(entry.domain, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${entry.timestamp} • ${entry.queryType} • ${entry.reason} • ${entry.appName}",
                                    fontSize = 11.sp, color = fg
                                )
                            }
                            TextButton(onClick = {
                                FirewallRepository.setCustomRule(context, entry.domain, block = !entry.isBlocked)
                            }) {
                                Text(if (entry.isBlocked) "Permitir" else "Bloquear")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun RulesTab(stats: FirewallStats) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val customBlock by FirewallRepository.customBlockList.collectAsState()
    val customAllow by FirewallRepository.customAllowList.collectAsState()

    var domainInput by remember { mutableStateOf("") }
    var isDownloading by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Listas automáticas (${stats.loadedRulesCount} dominios)", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = {
                            isDownloading = true
                            scope.launch {
                                val res = FirewallRepository.downloadBlocklist(context)
                                isDownloading = false
                                res.onSuccess {
                                    Toast.makeText(context, "Actualizada: $it dominios", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(context, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = !isDownloading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isDownloading) "Descargando lista..." else "Descargar / Actualizar HaGeZi Light")
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Agregar regla manual", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        placeholder = { Text("ej. tracking.ejemplo.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                FirewallRepository.setCustomRule(context, domainInput, block = true)
                                domainInput = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Bloquear") }
                        OutlinedButton(
                            onClick = {
                                FirewallRepository.setCustomRule(context, domainInput, block = false)
                                domainInput = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Permitir") }
                    }
                }
            }
        }

        if (customBlock.isNotEmpty()) {
            item { Text("Lista Negra Propia (${customBlock.size})", fontWeight = FontWeight.Bold) }
            items(customBlock) { domain ->
                RuleRow(domain, isBlocked = true) { FirewallRepository.removeCustomRule(context, domain) }
            }
        }

        if (customAllow.isNotEmpty()) {
            item { Text("Lista Blanca Propia (${customAllow.size})", fontWeight = FontWeight.Bold) }
            items(customAllow) { domain ->
                RuleRow(domain, isBlocked = false) { FirewallRepository.removeCustomRule(context, domain) }
            }
        }
    }
}

@Composable
fun AppsAndSettingsTab(
    stats: FirewallStats,
    onOpenBatteryOpt: () -> Unit,
    onOpenVpnSettings: () -> Unit
) {
    val context = LocalContext.current
    val excluded by FirewallRepository.excludedPackages.collectAsState()
    var appSearch by remember { mutableStateOf("") }
    var installedApps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    LaunchedEffect(Unit) {
        installedApps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            pm.getInstalledApplications(PackageManager.GET_META_DATA)
                .filter { it.packageName != context.packageName && (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
                .map { pm.getApplicationLabel(it).toString() to it.packageName }
                .sortedBy { it.first.lowercase() }
        }
    }

    val dnsOptions = listOf(
        "9.9.9.9" to "Quad9 (Seguridad)",
        "1.1.1.1" to "Cloudflare (Rápido)",
        "8.8.8.8" to "Google DNS",
        "94.140.14.14" to "AdGuard DNS"
    )

    val filteredApps = remember(installedApps, appSearch) {
        installedApps.filter {
            appSearch.isBlank() || it.first.contains(appSearch, true) || it.second.contains(appSearch, true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("DNS Upstream (para tráfico permitido)", fontWeight = FontWeight.Bold)
                    dnsOptions.forEach { (ip, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = stats.upstreamDns == ip,
                                onClick = { FirewallRepository.setUpstreamDns(context, ip) }
                            )
                            Text("$label ($ip)", fontSize = 14.sp)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = onOpenBatteryOpt, modifier = Modifier.weight(1f)) {
                            Text("Batería sin restr.", fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = onOpenVpnSettings, modifier = Modifier.weight(1f)) {
                            Text("VPN siempre activa", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        item {
            Text("Excluir apps del filtro (Split Tunneling)", fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            OutlinedTextField(
                value = appSearch,
                onValueChange = { appSearch = it },
                placeholder = { Text("Buscar app instalada...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        items(filteredApps, key = { it.second }) { (label, pkg) ->
            val isExcluded = excluded.contains(pkg)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(label, fontWeight = FontWeight.Medium)
                        Text(pkg, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(
                        checked = isExcluded,
                        onCheckedChange = { FirewallRepository.toggleExcludedApp(context, pkg) }
                    )
                }
            }
        }
    }
}

@Composable
fun RuleRow(domain: String, isBlocked: Boolean, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (isBlocked) "🚫 $domain" else "✅ $domain",
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Eliminar")
            }
        }
    }
}

@Composable
fun StatCard(label: String, value: String, color: Color, modifier: Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 12.sp)
        }
    }
}
