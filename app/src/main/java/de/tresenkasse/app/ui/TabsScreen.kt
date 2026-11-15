package de.tresenkasse.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.tresenkasse.app.fiscal.aggregate
import de.tresenkasse.app.fiscal.euro
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.of("Europe/Berlin"))

@Composable
fun TabsScreen(vm: KasseViewModel) {
    val tabs by vm.openTabs.collectAsStateWithLifecycle()
    if (tabs.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text("Keine offenen Deckel.\nIn der Kasse Getränke wählen und „Deckel“ tippen.", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(8.dp)) {
        items(tabs, key = { it.tab.id }) { t ->
            val lines = aggregate(t.lines.map { it.toLine() })
            ElevatedCard(
                onClick = { vm.overlay.value = Overlay.TabDetail(t.tab.id) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.tab.name, style = MaterialTheme.typography.titleMedium)
                        Text("seit ${hm.format(Instant.ofEpochMilli(t.tab.openedAt))} · ${lines.sumOf { it.qty }} Posten",
                            style = MaterialTheme.typography.bodySmall)
                    }
                    Text(euro(lines.sumOf { it.totalCents }), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabDetail(vm: KasseViewModel, tabId: Long) {
    val flow = remember(tabId) { vm.tab(tabId) }
    val tw by flow.collectAsStateWithLifecycle(initialValue = null)
    val t = tw ?: return
    val lines = aggregate(t.lines.map { it.toLine() })
    val total = lines.sumOf { it.totalCents }
    val unsigned = t.lines.any { it.tseError != null }
    var showCash by remember { mutableStateOf(false) }
    var confirmCancel by remember { mutableStateOf(false) }
    var showUnbar by remember { mutableStateOf(false) }

    LaunchedEffect(t.tab.state) {
        if (t.tab.state != "OPEN" && vm.overlay.value == Overlay.TabDetail(tabId)) vm.overlay.value = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Deckel: ${t.tab.name}") },
                navigationIcon = { IconButton(onClick = { vm.overlay.value = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
                actions = { IconButton(onClick = { confirmCancel = true }) { Icon(Icons.Default.DeleteForever, "Deckel auflösen") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (unsigned) Text(
                "Einige Bestellungen konnten nicht von der TSE signiert werden (keine Verbindung).",
                color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp),
            )
            LazyColumn(Modifier.weight(1f)) {
                items(lines, key = { "${it.text}|${it.unitCents}|${it.vat}" }) { l ->
                    ListItem(
                        headlineContent = { Text("${l.qty} × ${l.text}") },
                        supportingContent = { Text("je ${euro(l.unitCents)}") },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(euro(l.totalCents), fontWeight = FontWeight.SemiBold)
                                IconButton(onClick = { vm.tabStornoOne(tabId, l) }) { Icon(Icons.Default.RemoveCircleOutline, "Einen stornieren") }
                                IconButton(onClick = { vm.tabAddOne(tabId, l) }) { Icon(Icons.Default.AddCircleOutline, "Noch einen") }
                            }
                        },
                    )
                }
            }
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Row {
                        Text("Summe", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(euro(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { showCash = true }, enabled = total > 0, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.Payments, null); Spacer(Modifier.width(4.dp)); Text("Bar")
                        }
                        FilledTonalButton(onClick = { showUnbar = true }, enabled = total > 0, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.AccountBalance, null); Spacer(Modifier.width(4.dp)); Text("Unbar")
                        }
                    }
                }
            }
        }
    }

    if (showCash) CashDialog(total, onDismiss = { showCash = false }) { given -> showCash = false; vm.payCash(given, tabId) }
    if (showUnbar) UnbarDialog(vm, total, onDismiss = { showUnbar = false },
        onTransfer = { showUnbar = false; vm.startTransfer(tabId) },
        onDebit = { id -> showUnbar = false; vm.payDebit(id, tabId) })
    if (confirmCancel) AlertDialog(
        onDismissRequest = { confirmCancel = false },
        title = { Text("Deckel auflösen?") },
        text = { Text("Alle Posten werden storniert (von der TSE signiert) und der Deckel geschlossen.") },
        confirmButton = { TextButton(onClick = { confirmCancel = false; vm.cancelTab(tabId) }) { Text("Auflösen") } },
        dismissButton = { TextButton(onClick = { confirmCancel = false }) { Text("Abbrechen") } },
    )
}
