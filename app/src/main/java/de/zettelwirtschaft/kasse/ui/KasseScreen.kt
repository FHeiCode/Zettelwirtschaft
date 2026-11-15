package de.zettelwirtschaft.kasse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.zettelwirtschaft.kasse.fiscal.aggregate
import de.zettelwirtschaft.kasse.fiscal.euro
import de.zettelwirtschaft.kasse.fiscal.parseEuro

@Composable
fun KasseScreen(vm: KasseViewModel) {
    val products by vm.products.collectAsStateWithLifecycle()
    val cart by vm.cart.collectAsStateWithLifecycle()
    val all by vm.allProducts.collectAsStateWithLifecycle()
    val byId = all.associateBy { it.id }
    val total = cart.entries.sumOf { (id, q) -> (byId[id]?.priceCents ?: 0) * q }
    var showCash by remember { mutableStateOf(false) }
    var showTabs by remember { mutableStateOf(false) }
    var showUnbar by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(110.dp),
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(products, key = { it.id }) { p ->
                val q = cart[p.id] ?: 0
                ElevatedCard(
                    onClick = { vm.add(p) },
                    colors = if (q > 0) CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    else CardDefaults.elevatedCardColors(),
                    modifier = Modifier.height(88.dp),
                ) {
                    Column(Modifier.fillMaxSize().padding(8.dp), verticalArrangement = Arrangement.SpaceBetween) {
                        Text(p.name, fontWeight = FontWeight.SemiBold, maxLines = 2)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(euro(p.priceCents), style = MaterialTheme.typography.bodySmall)
                            if (q > 0) Text("× $q", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }

        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                if (cart.isNotEmpty()) {
                    LazyColumn(Modifier.heightIn(max = 160.dp)) {
                        items(cart.entries.toList(), key = { it.key }) { (id, q) ->
                            val p = byId[id] ?: return@items
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                IconButton(onClick = { vm.remove(p) }) { Icon(Icons.Default.RemoveCircleOutline, "Einen weniger") }
                                Text("$q × ${p.name}", Modifier.weight(1f))
                                Text(euro(p.priceCents * q))
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = 4.dp))
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Summe", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(euro(total), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    if (cart.isNotEmpty()) IconButton(onClick = { vm.clearCart() }) { Icon(Icons.Default.Clear, "Leeren") }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { showCash = true }, enabled = total > 0, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Payments, null); Spacer(Modifier.width(4.dp)); Text("Bar")
                    }
                    FilledTonalButton(onClick = { showUnbar = true }, enabled = total > 0, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.AccountBalance, null); Spacer(Modifier.width(4.dp)); Text("Unbar")
                    }
                    OutlinedButton(onClick = { showTabs = true }, enabled = cart.isNotEmpty(), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Receipt, null); Spacer(Modifier.width(4.dp)); Text("Deckel")
                    }
                }
            }
        }
    }

    if (showCash) CashDialog(total, onDismiss = { showCash = false }) { given ->
        showCash = false; vm.payCash(given)
    }
    if (showUnbar) UnbarDialog(vm, total, onDismiss = { showUnbar = false },
        onTransfer = { showUnbar = false; vm.startTransfer() },
        onDebit = { id -> showUnbar = false; vm.payDebit(id) })
    if (showTabs) TabPicker(vm, onDismiss = { showTabs = false }) { id, name ->
        showTabs = false; vm.bookToTab(id, name)
    }
}

/** Bar kassieren: gegebener Betrag (optional) und Rückgeld. */
@Composable
fun CashDialog(totalCents: Long, onDismiss: () -> Unit, onPay: (Long?) -> Unit) {
    var input by remember { mutableStateOf("") }
    val given = parseEuro(input)
    val suggestions = listOf(500L, 1000L, 2000L, 5000L).filter { it >= totalCents }.take(3)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Bar: ${euro(totalCents)}") },
        text = {
            Column {
                OutlinedTextField(
                    value = input, onValueChange = { input = it },
                    label = { Text("Gegeben (optional)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    isError = input.isNotBlank() && (given == null || given < totalCents),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                    suggestions.forEach { s ->
                        AssistChip(onClick = { input = (s / 100).toString() }, label = { Text(euro(s)) })
                    }
                }
                if (given != null && given >= totalCents) {
                    Text(
                        "Rückgeld: ${euro(given - totalCents)}",
                        style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 12.dp).fillMaxWidth(), textAlign = TextAlign.Center,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onPay(given) },
                enabled = input.isBlank() || (given != null && given >= totalCents),
            ) { Text("Kassieren") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Deckel auswählen oder neu anlegen. */
@Composable
private fun TabPicker(vm: KasseViewModel, onDismiss: () -> Unit, onPick: (Long?, String) -> Unit) {
    val tabs by vm.openTabs.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Auf welchen Deckel?") },
        text = {
            Column {
                OutlinedTextField(
                    value = name, onValueChange = { name = it }, singleLine = true,
                    label = { Text("Neuer Deckel (Name oder Tisch)") },
                    trailingIcon = {
                        IconButton(onClick = { onPick(null, name) }, enabled = name.isNotBlank()) { Icon(Icons.Default.Add, "Anlegen") }
                    },
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 300.dp)) {
                    items(tabs, key = { it.tab.id }) { t ->
                        val sum = aggregate(t.lines.map { it.toLine() }).sumOf { it.totalCents }
                        ListItem(
                            headlineContent = { Text(t.tab.name) },
                            trailingContent = { Text(euro(sum)) },
                            modifier = Modifier.clickableRow { onPick(t.tab.id, "") },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

fun Modifier.clickableRow(onClick: () -> Unit) = this.clickable(onClick = onClick)
