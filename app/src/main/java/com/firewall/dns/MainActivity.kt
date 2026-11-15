package com.firewall.dns

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModelProvider
import com.firewall.dns.service.DnsFirewallService
import com.firewall.dns.ui.screens.AppsSettingsScreen
import com.firewall.dns.ui.screens.LogsScreen
import com.firewall.dns.ui.screens.RulesScreen
import com.firewall.dns.ui.theme.FirewallTheme
import com.firewall.dns.ui.viewmodel.FirewallViewModel

class MainActivity : ComponentActivity() {

    private lateinit var firewallViewModel: FirewallViewModel

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
        val crashPrefs = getSharedPreferences("crash_debug", MODE_PRIVATE)
        val savedCrash = crashPrefs.getString("last_crash", null)

        val prevHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            crashPrefs.edit().putString("last_crash", throwable.stackTraceToString()).commit()
            prevHandler?.uncaughtException(thread, throwable)
        }

        super.onCreate(savedInstanceState)

        if (!savedCrash.isNullOrBlank()) {
            showCrashScreen(savedCrash, crashPrefs)
            return
        }

        try {
            firewallViewModel = ViewModelProvider(
                this,
                ViewModelProvider.AndroidViewModelFactory.getInstance(application)
            )[FirewallViewModel::class.java]

            if (firewallViewModel.shouldAutoStartVpn() && VpnService.prepare(this) == null) {
                startFirewallService()
            }

            setContent {
                FirewallTheme {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        FirewallApp(
                            viewModel = firewallViewModel,
                            onToggleVpn = { enable ->
                                if (enable) checkPermissionsAndStart() else stopFirewallService()
                            },
                            onOpenBatteryOpt = { requestIgnoreBatteryOptimization() },
                            onOpenVpnSettings = { startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) }
                        )
                    }
                }
            }
        } catch (t: Throwable) {
            val trace = t.stackTraceToString()
            crashPrefs.edit().putString("last_crash", trace).commit()
            showCrashScreen(trace, crashPrefs)
        }
    }

    private fun showCrashScreen(trace: String, crashPrefs: android.content.SharedPreferences) {
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val topPad = (56 * density).toInt()

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, topPad, pad, pad)
            setBackgroundColor(android.graphics.Color.WHITE)
        }
        val title = TextView(this).apply {
            text = "Error detectado al iniciar:"
            textSize = 18f
            setTextColor(android.graphics.Color.RED)
        }
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, pad / 2, 0, pad / 2)
        }
        val copyBtn = Button(this).apply {
            text = "Copiar error"
            setOnClickListener {
                val cb = getSystemService(ClipboardManager::class.java)
                cb.setPrimaryClip(ClipData.newPlainText("Crash", trace))
                Toast.makeText(this@MainActivity, "Error copiado", Toast.LENGTH_SHORT).show()
            }
        }
        val retryBtn = Button(this).apply {
            text = "Limpiar y reintentar"
            setOnClickListener {
                crashPrefs.edit().remove("last_crash").commit()
                recreate()
            }
        }
        btnRow.addView(copyBtn)
        btnRow.addView(retryBtn)

        val scroll = ScrollView(this)
        val body = TextView(this).apply {
            text = trace
            textSize = 12f
            setTextColor(android.graphics.Color.BLACK)
        }
        scroll.addView(body)
        layout.addView(title)
        layout.addView(btnRow)
        layout.addView(scroll)
        setContentView(layout)
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
    viewModel: FirewallViewModel,
    onToggleVpn: (Boolean) -> Unit,
    onOpenBatteryOpt: () -> Unit,
    onOpenVpnSettings: () -> Unit
) {
    val context = LocalContext.current
    val stats by viewModel.stats.collectAsState()
    val logs by viewModel.filteredLogs.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val onlyBlocked by viewModel.onlyBlocked.collectAsState()
    val customBlock by viewModel.customBlockList.collectAsState()
    val customAllow by viewModel.customAllowList.collectAsState()
    val blocklistUrls by viewModel.blocklistUrls.collectAsState()
    val smartModeEnabled by viewModel.smartModeEnabled.collectAsState()
    val isDownloading by viewModel.isDownloading.collectAsState()
    val apps by viewModel.filteredApps.collectAsState()
    val excludedPackages by viewModel.excludedPackages.collectAsState()
    val appSearch by viewModel.appSearch.collectAsState()

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Actividad", "Reglas", "Apps")

    LaunchedEffect(Unit) {
        viewModel.toastMessage.collect { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Shield,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text("Firewall DNS", fontWeight = FontWeight.Bold)
                                Text(
                                    text = "Protección DNS • Candido y Elizabeth",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ),
                    actions = {
                        Switch(
                            checked = stats.isRunning,
                            onCheckedChange = onToggleVpn,
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                )
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
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
                0 -> LogsScreen(
                    stats = stats,
                    logs = logs,
                    searchQuery = searchQuery,
                    onlyBlocked = onlyBlocked,
                    onSearchQueryChange = viewModel::onSearchQueryChange,
                    onToggleOnlyBlocked = viewModel::onToggleOnlyBlocked,
                    onClearLogs = viewModel::clearLogs,
                    onToggleRule = { entry ->
                        viewModel.setCustomRule(entry.domain, block = !entry.isBlocked)
                    }
                )
                1 -> RulesScreen(
                    stats = stats,
                    customBlock = customBlock,
                    customAllow = customAllow,
                    blocklistUrls = blocklistUrls,
                    smartModeEnabled = smartModeEnabled,
                    isDownloading = isDownloading,
                    onToggleSmartMode = viewModel::toggleSmartMode,
                    onAddBlocklistUrl = viewModel::addBlocklistUrl,
                    onRemoveBlocklistUrl = viewModel::removeBlocklistUrl,
                    onDownloadBlocklist = viewModel::downloadBlocklist,
                    onAddCustomRule = viewModel::setCustomRule,
                    onRemoveCustomRule = viewModel::removeCustomRule
                )
                2 -> AppsSettingsScreen(
                    stats = stats,
                    apps = apps,
                    excludedPackages = excludedPackages,
                    appSearch = appSearch,
                    onAppSearchChange = viewModel::onAppSearchChange,
                    onSelectUpstreamDns = viewModel::setUpstreamDns,
                    onToggleExcludedApp = viewModel::toggleExcludedApp,
                    onOpenBatteryOpt = onOpenBatteryOpt,
                    onOpenVpnSettings = onOpenVpnSettings
                )
            }
        }
    }
}

	








































									























 

