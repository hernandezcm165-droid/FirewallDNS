package com.firewall.dns.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.firewall.dns.domain.model.DnsLogEntry
import com.firewall.dns.domain.model.FirewallStats

@Composable
fun LogsScreen(
    stats: FirewallStats,
    logs: List<DnsLogEntry>,
    searchQuery: String,
    onlyBlocked: Boolean,
    onSearchQueryChange: (String) -> Unit,
    onToggleOnlyBlocked: () -> Unit,
    onClearLogs: () -> Unit,
    onToggleRule: (DnsLogEntry) -> Unit
) {
    val protectionStatus = if (stats.isRunning) "Protección activa" else "Protección pausada"
    val protectionColor = if (stats.isRunning) Color(0xFF2E7D32) else Color(0xFF6D4C41)

    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = protectionStatus,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Text(
                        text = "${stats.networkType} • DNS ${stats.upstreamDns}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
                    )
                }
                Surface(
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = protectionColor,
                    tonalElevation = 2.dp
                ) {
                    Box(Modifier.size(14.dp))
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Bloqueados", stats.totalBlocked.toString(), Color(0xFFD32F2F), Modifier.weight(1f))
            StatCard("Permitidos", stats.totalAllowed.toString(), Color(0xFF388E3C), Modifier.weight(1f))
            StatCard("Reglas", stats.loadedRulesCount.toString(), Color(0xFF1976D2), Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Buscar dominio...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(6.dp))
            FilterChip(
                selected = onlyBlocked,
                onClick = onToggleOnlyBlocked,
                label = { Text("Bloq.") }
            )
            IconButton(onClick = onClearLogs) {
                Icon(Icons.Default.Delete, contentDescription = "Limpiar")
            }
        }

        Spacer(Modifier.height(8.dp))

        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (stats.isRunning) "Esperando consultas DNS..." else "Enciende el interruptor arriba para empezar",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(logs, key = { it.id }) { entry ->
                    val bg = if (entry.isBlocked) Color(0xFFFFEBEE) else Color(0xFFE8F5E9)
                    val fg = if (entry.isBlocked) Color(0xFFC62828) else Color(0xFF2E7D32)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = entry.domain,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${entry.timestamp} • ${entry.queryType} • ${entry.reason} • ${entry.appName}",
                                    fontSize = 11.sp,
                                    color = fg
                                )
                            }
                            TextButton(onClick = { onToggleRule(entry) }) {
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
fun StatCard(label: String, value: String, color: Color, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(modifier = Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            Text(label, fontSize = 12.sp)
        }
    }
}
