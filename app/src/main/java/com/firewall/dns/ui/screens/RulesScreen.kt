package com.firewall.dns.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.firewall.dns.domain.model.FirewallStats

@Composable
fun RulesScreen(
    stats: FirewallStats,
    customBlock: List<String>,
    customAllow: List<String>,
    isDownloading: Boolean,
    onDownloadBlocklist: () -> Unit,
    onAddCustomRule: (String, Boolean) -> Unit,
    onRemoveCustomRule: (String) -> Unit
) {
    var domainInput by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Listas automáticas (${stats.loadedRulesCount} dominios)", fontWeight = FontWeight.Bold)
                    Button(
                        onClick = onDownloadBlocklist,
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
                                onAddCustomRule(domainInput, true)
                                domainInput = ""
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Bloquear") }
                        OutlinedButton(
                            onClick = {
                                onAddCustomRule(domainInput, false)
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
                RuleRow(domain, isBlocked = true) { onRemoveCustomRule(domain) }
            }
        }

        if (customAllow.isNotEmpty()) {
            item { Text("Lista Blanca Propia (${customAllow.size})", fontWeight = FontWeight.Bold) }
            items(customAllow) { domain ->
                RuleRow(domain, isBlocked = false) { onRemoveCustomRule(domain) }
            }
        }
    }
}

@Composable
private fun RuleRow(domain: String, isBlocked: Boolean, onDelete: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
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
