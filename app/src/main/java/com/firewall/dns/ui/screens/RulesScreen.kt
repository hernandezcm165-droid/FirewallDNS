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
import androidx.compose.ui.unit.sp
import com.firewall.dns.domain.model.FirewallStats

@Composable
fun RulesScreen(
    stats: FirewallStats,
    customBlock: List<String>,
    customAllow: List<String>,
    blocklistUrls: List<String>,
    smartModeEnabled: Boolean,
    isDownloading: Boolean,
    onToggleSmartMode: (Boolean) -> Unit,
    onAddBlocklistUrl: (String) -> Unit,
    onRemoveBlocklistUrl: (String) -> Unit,
    onDownloadBlocklist: () -> Unit,
    onAddCustomRule: (String, Boolean) -> Unit,
    onRemoveCustomRule: (String) -> Unit
) {
    var domainInput by remember { mutableStateOf("") }
    var urlInput by remember { mutableStateOf("") }

    val presets = listOf(
        "Light" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/light.txt",
        "Normal" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/multi.txt",
        "Pro" to "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/adblock/pro.txt"
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Motor Inteligente v2 + Aprendizaje", fontWeight = FontWeight.Bold)
                        Text(
                            "Puntuación multi-señal con escudo anti-fallas para Meta/TikTok/Ads y aprendizaje local por tus toques.",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Switch(checked = smartModeEnabled, onCheckedChange = onToggleSmartMode)
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Listas Adblock ||dominio.com^ (${stats.loadedRulesCount} reglas)", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = urlInput,
                        onValueChange = { urlInput = it },
                        placeholder = { Text("https://raw.githubusercontent.com/.../adblock.txt") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        onClick = {
                            onAddBlocklistUrl(urlInput)
                            urlInput = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Agregar URL Adblock externa") }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        presets.forEach { (name, url) ->
                            OutlinedButton(
                                onClick = { onAddBlocklistUrl(url) },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("+ $name", fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }

                    Button(
                        onClick = onDownloadBlocklist,
                        enabled = !isDownloading,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(if (isDownloading) "Descargando listas..." else "Descargar / Actualizar listas")
                    }
                }
            }
        }

        if (blocklistUrls.isNotEmpty()) {
            item { Text("URLs configuradas (${blocklistUrls.size})", fontWeight = FontWeight.Bold) }
            items(blocklistUrls) { url ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = url,
                            fontSize = 12.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = { onRemoveBlocklistUrl(url) }) {
                            Icon(Icons.Default.Delete, contentDescription = "Quitar URL")
                        }
                    }
                }
            }
        }

        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Agregar regla manual (||dominio.com^ o dominio)", fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = domainInput,
                        onValueChange = { domainInput = it },
                        placeholder = { Text("ej. ||tracking.ejemplo.com^") },
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
                text = if (isBlocked) "🚫 ||$domain^" else "✅ @@||$domain^",
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
