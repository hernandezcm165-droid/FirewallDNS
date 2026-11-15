package com.firewall.dns.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.firewall.dns.domain.model.FirewallStats

@Composable
fun DashboardScreen(
    stats: FirewallStats,
    onToggleProtection: (Boolean) -> Unit,
    onOpenLogs: () -> Unit,
    onOpenRules: () -> Unit
) {
    val statusText = if (stats.isRunning) "Tu conexión está protegida" else "La protección está pausada"
    val statusSupportingText = if (stats.isRunning) {
        "El filtro DNS está activo en esta red."
    } else {
        "Activa el firewall para filtrar consultas DNS."
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    if (stats.isRunning) "PROTECCIÓN ACTIVA" else "PROTECCIÓN INACTIVA",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    statusText,
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Text(
                    statusSupportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Button(
                    onClick = { onToggleProtection(!stats.isRunning) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (stats.isRunning) "Pausar protección" else "Activar protección")
                }
            }
        }

        Text("Resumen", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SummaryCard(
                title = "Bloqueados",
                value = stats.totalBlocked.toString(),
                modifier = Modifier.weight(1f)
            )
            SummaryCard(
                title = "Permitidos",
                value = stats.totalAllowed.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Conexión", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                HorizontalDivider()
                InfoRow("Red", stats.networkType)
                InfoRow("DNS ascendente", stats.upstreamDns)
                InfoRow("Reglas cargadas", stats.loadedRulesCount.toString())
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = onOpenLogs, modifier = Modifier.weight(1f)) {
                Text("Ver actividad")
            }
            OutlinedButton(onClick = onOpenRules, modifier = Modifier.weight(1f)) {
                Text("Administrar reglas")
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String, modifier: Modifier = Modifier) {
    Card(modifier = modifier) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}
