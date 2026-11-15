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
    Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatCard("Bloqueados", stats.totalBlocked.toString(), MaterialTheme.colorScheme.error, Modifier.weight(1f))
            StatCard("Permitidos", stats.totalAllowed.toString(), MaterialTheme.colorScheme.primary, Modifier.weight(1f))
            StatCard("Reglas", stats.loadedRulesCount.toString(), MaterialTheme.colorScheme.tertiary, Modifier.weight(1f))
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Buscar dominio...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Buscar") },
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
                Icon(Icons.Default.Delete, contentDescription = "Limpiar registros")
            }
        }

        Spacer(Modifier.height(8.dp))

        if (logs.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = if (stats.isRunning) "Esperando consultas DNS..." else "Activa el firewall para empezar",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(logs, key = { it.id }) { entry ->
                    val bg = if (entry.isBlocked) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                    val fg = if (entry.isBlocked) {
                        MaterialTheme.colorScheme.onErrorContainer
                    } else {
                        MaterialTheme.colorScheme.onSecondaryContainer
                    }
                    Card(
                        colors = CardDefaults.cardColors(containerColor = bg),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
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
                                    color = fg,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
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
