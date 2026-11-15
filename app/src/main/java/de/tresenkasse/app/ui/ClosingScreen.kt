package de.tresenkasse.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.tresenkasse.app.data.Closing
import de.tresenkasse.app.fiscal.amountsPerVat
import de.tresenkasse.app.fiscal.euro
import java.time.LocalDate
import java.time.ZoneId

@Composable
fun ClosingScreen(vm: KasseViewModel) {
    val open by vm.unclosed.collectAsStateWithLifecycle()
    val debits by vm.openDebits.collectAsStateWithLifecycle()
    var showDebit by remember { mutableStateOf(false) }
    val closings by vm.closings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirm by remember { mutableStateOf(false) }
    var showExport by remember { mutableStateOf(false) }

    val sales = open.filter { it.receipt.kind == "SALE" || it.receipt.kind == "STORNO" }
    val cash = sales.filter { it.receipt.payment == "CASH" }.sumOf { it.receipt.totalCents }
    val nonCash = sales.filter { it.receipt.payment == "NON_CASH" }.sumOf { it.receipt.totalCents }
    val perVat = amountsPerVat(sales.flatMap { s -> s.lines.map { it.toLine() } })

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        item {
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Seit dem letzten Abschluss", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row2("Belege", "${open.size} (davon ${open.count { it.receipt.kind == "STORNO" }} Storno)")
                    Row2("Bar", euro(cash))
                    Row2("Überweisung", euro(nonCash))
                    perVat.forEach { Row2("Umsatz ${it.vat.label}", "${euro(it.grossCents)} (MwSt ${euro(it.taxCents)})") }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    Row2("Gesamt", euro(cash + nonCash), bold = true)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Lock, null); Spacer(Modifier.width(6.dp)); Text("Tagesabschluss (Z-Bon)")
                    }
                    Text(
                        "Bitte jeden Tag mit Umsatz abschließen. Der Abschluss geht an fiskaly und landet im DSFinV-K-Export.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = { showExport = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Download, null); Spacer(Modifier.width(6.dp)); Text("DSFinV-K-Export für Steuerberater/Finanzamt")
            }
            Spacer(Modifier.height(12.dp))
            ElevatedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Offene Lastschriften", style = MaterialTheme.typography.titleMedium)
                    Row2("Anzahl", debits.size.toString())
                    Row2("Summe", euro(debits.sumOf { it.totalCents }), bold = true)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showDebit = true }, enabled = debits.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.AccountBalance, null); Spacer(Modifier.width(6.dp)); Text("SEPA-Datei fürs Online-Banking")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            if (closings.isNotEmpty()) Text("Abschlüsse", style = MaterialTheme.typography.titleMedium)
        }
        items(closings, key = { it.id }) { c -> ClosingItem(c) { vm.refreshClosing(c.id) } }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Tagesabschluss machen?") },
        text = { Text("${open.size} Belege über ${euro(cash + nonCash)} werden abgeschlossen. Bar in der Kasse laut Belegen: ${euro(cash)}.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.dayClosing() }) { Text("Abschließen") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Abbrechen") } },
    )

    if (showDebit) {
        val zone = ZoneId.of("Europe/Berlin")
        // Frühester sinnvoller Einzugstag: 2 Bankarbeitstage nach heute (Wochenende übersprungen)
        var d = LocalDate.now(zone)
        var added = 0
        while (added < 2) { d = d.plusDays(1); if (d.dayOfWeek.value <= 5) added++ }
        val date = d
        AlertDialog(
            onDismissRequest = { showDebit = false },
            title = { Text("SEPA-Lastschriftdatei erstellen?") },
            text = { Text("${debits.size} Lastschriften über ${euro(debits.sumOf { it.totalCents })}, Einzug am " +
                "%02d.%02d.%d.".format(date.dayOfMonth, date.monthValue, date.year) +
                " Die Datei (pain.008) lädst du im Online-Banking unter „Datei hochladen“ / „SEPA-Sammler“ hoch. " +
                "Danach gelten die Lastschriften als eingereicht.") },
            confirmButton = { TextButton(onClick = {
                showDebit = false
                vm.exportDebits(date) { f -> shareFile(context, f, "application/xml") }
            }) { Text("Erstellen") } },
            dismissButton = { TextButton(onClick = { showDebit = false }) { Text("Abbrechen") } },
        )
    }

    if (showExport) {
        val zone = ZoneId.of("Europe/Berlin")
        val now = System.currentTimeMillis() / 1000
        fun since(d: LocalDate) = d.atStartOfDay(zone).toEpochSecond()
        val today = LocalDate.now(zone)
        val options = listOf(
            "Dieser Monat" to since(today.withDayOfMonth(1)),
            "Letzter Monat bis heute" to since(today.minusMonths(1).withDayOfMonth(1)),
            "Dieses Jahr" to since(today.withDayOfYear(1)),
            "Letztes Jahr bis heute" to since(today.minusYears(1).withDayOfYear(1)),
        )
        AlertDialog(
            onDismissRequest = { showExport = false },
            title = { Text("Zeitraum für den Export") },
            text = {
                Column {
                    options.forEach { (label, from) ->
                        TextButton(onClick = {
                            showExport = false
                            vm.export(from, now) { f -> shareFile(context, f, "application/zip") }
                        }) { Text(label) }
                    }
                    Text("Den TSE-Export (TAR) erstellst du bei Bedarf im fiskaly-Dashboard.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showExport = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun Row2(label: String, value: String, bold: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal)
    }
}

@Composable
private fun ClosingItem(c: Closing, onRefresh: () -> Unit) {
    val (label, color) = when (c.state) {
        "COMPLETED" -> "übertragen" to MaterialTheme.colorScheme.secondary
        "PENDING" -> "wird geprüft" to MaterialTheme.colorScheme.tertiary
        "ERROR" -> "Fehler" to MaterialTheme.colorScheme.error
        else -> "nicht übertragen" to MaterialTheme.colorScheme.error
    }
    ListItem(
        headlineContent = { Text("Z-Nr. ${c.exportId} · ${c.businessDate}") },
        supportingContent = {
            Column {
                Text("${c.receiptCount} Belege · bar ${euro(c.cashCents)} · Überw. ${euro(c.nonCashCents)}")
                Text("19 %: ${euro(c.normalGrossCents)} · 7 %: ${euro(c.reducedGrossCents)}")
                Text(label, color = color, fontWeight = FontWeight.SemiBold)
                c.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(euro(c.totalCents), fontWeight = FontWeight.Bold)
                if (c.state != "COMPLETED") IconButton(onClick = onRefresh) { Icon(Icons.Default.Refresh, "Erneut prüfen/übertragen") }
            }
        },
    )
    HorizontalDivider()
}

