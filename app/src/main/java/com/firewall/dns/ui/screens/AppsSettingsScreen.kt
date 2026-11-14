package com.firewall.dns.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firewall.dns.domain.model.AppInfoItem
import com.firewall.dns.domain.model.FirewallStats

@Composable
fun AppsSettingsScreen(
    stats: FirewallStats,
    apps: List<AppInfoItem>,
    excludedPackages: Set<String>,
    appSearch: String,
    onAppSearchChange: (String) -> Unit,
    onSelectUpstreamDns: (String) -> Unit,
    onToggleExcludedApp: (String) -> Unit,
    onOpenBatteryOpt: () -> Unit,
    onOpenVpnSettings: () -> Unit
) {
    val dnsOptions = listOf(
        "9.9.9.9" to "Quad9 (Seguridad)",
        "1.1.1.1" to "Cloudflare (Rápido)",
        "8.8.8.8" to "Google DNS",
        "94.140.14.14" to "AdGuard DNS"
    )

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
                                onClick = { onSelectUpstreamDns(ip) }
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
                onValueChange = onAppSearchChange,
                placeholder = { Text("Buscar app instalada...") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }

        items(apps, key = { it.packageName }) { app ->
            val isExcluded = excludedPackages.contains(app.packageName)
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(app.label, fontWeight = FontWeight.Medium)
                        Text(
                            app.packageName,
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(
                        checked = isExcluded,
                        onCheckedChange = { onToggleExcludedApp(app.packageName) }
                    )
                }
            }
        }
    }
}
