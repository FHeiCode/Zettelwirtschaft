package de.zettelwirtschaft.kasse.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.zettelwirtschaft.kasse.data.Product
import de.zettelwirtschaft.kasse.fiscal.GiroCode
import de.zettelwirtschaft.kasse.fiscal.SepaDebit
import de.zettelwirtschaft.kasse.fiscal.Vat
import de.zettelwirtschaft.kasse.fiscal.euro
import de.zettelwirtschaft.kasse.fiscal.parseEuro

@Composable
fun SettingsScreen(vm: KasseViewModel) {
    val c by vm.config.collectAsStateWithLifecycle()
    var name by remember { mutableStateOf(c.businessName) }
    var address by remember { mutableStateOf(c.businessAddress) }
    var taxNo by remember { mutableStateOf(c.taxNumber) }
    var holder by remember { mutableStateOf(c.accountHolder) }
    var iban by remember { mutableStateOf(c.iban) }
    var bic by remember { mutableStateOf(c.bic) }
    var creditorId by remember { mutableStateOf(c.creditorId) }
    var key by remember { mutableStateOf(c.apiKey) }
    var secret by remember { mutableStateOf(c.apiSecret) }
    val ibanOk = iban.isBlank() || GiroCode.isValidIban(iban)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Section("Betrieb (steht auf jedem Beleg)")
        OutlinedTextField(name, { name = it; vm.updateConfig { x -> x.copy(businessName = it) } }, Modifier.fillMaxWidth(), label = { Text("Name des Betriebs") }, singleLine = true)
        OutlinedTextField(address, { address = it; vm.updateConfig { x -> x.copy(businessAddress = it) } }, Modifier.fillMaxWidth(), label = { Text("Anschrift (Straße, PLZ Ort)") }, minLines = 2)
        OutlinedTextField(taxNo, { taxNo = it; vm.updateConfig { x -> x.copy(taxNumber = it) } }, Modifier.fillMaxWidth(), label = { Text("Steuernummer oder USt-IdNr. (optional)") }, singleLine = true)

        Section("Konto für Überweisungen (GiroCode)")
        OutlinedTextField(holder, { holder = it; vm.updateConfig { x -> x.copy(accountHolder = it) } }, Modifier.fillMaxWidth(), label = { Text("Kontoinhaber") }, singleLine = true)
        OutlinedTextField(iban, { iban = it; vm.updateConfig { x -> x.copy(iban = GiroCode.normalizeIban(it)) } }, Modifier.fillMaxWidth(),
            label = { Text("IBAN") }, singleLine = true, isError = !ibanOk,
            supportingText = { if (!ibanOk) Text("IBAN ist ungültig") })
        OutlinedTextField(bic, { bic = it; vm.updateConfig { x -> x.copy(bic = it.trim()) } }, Modifier.fillMaxWidth(), label = { Text("BIC (optional)") }, singleLine = true)

        Section("Lastschrift (Stammgäste)")
        val credOk = creditorId.isBlank() || SepaDebit.isValidCreditorId(creditorId)
        OutlinedTextField(creditorId, { creditorId = it; vm.updateConfig { x -> x.copy(creditorId = it.replace(" ", "").uppercase()) } }, Modifier.fillMaxWidth(),
            label = { Text("Gläubiger-ID (von der Bundesbank)") }, singleLine = true, isError = !credOk,
            supportingText = { Text(if (!credOk) "Gläubiger-ID ist ungültig" else "z. B. DE98ZZZ09999999999") })
        OutlinedButton(onClick = { vm.overlay.value = Overlay.Mandates }) {
            Icon(Icons.Default.Draw, null); Spacer(Modifier.width(6.dp)); Text("Stammgäste & Mandate")
        }

        Section("TSE (fiskaly)")
        Text("API-Key und Secret legst du im fiskaly-Dashboard an. Zum Ausprobieren reicht die kostenlose TEST-Umgebung; " +
            "für den echten Betrieb brauchst du einen LIVE-Key.", style = MaterialTheme.typography.bodySmall)
        val locked = c.tssId.isNotBlank()
        OutlinedTextField(key, { key = it.trim(); vm.updateConfig { x -> x.copy(apiKey = it.trim()) } }, Modifier.fillMaxWidth(),
            label = { Text("API-Key") }, singleLine = true, enabled = !locked)
        OutlinedTextField(secret, { secret = it.trim(); vm.updateConfig { x -> x.copy(apiSecret = it.trim()) } }, Modifier.fillMaxWidth(),
            label = { Text("API-Secret") }, singleLine = true, enabled = !locked,
            visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        if (c.tseReady) {
            Text("TSE eingerichtet ✓", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
            Text("TSE: ${c.tssSerial.take(24)}…\nKasse: ${c.clientSerial}", style = MaterialTheme.typography.bodySmall)
            if (!c.cashRegisterRegistered) OutlinedButton(onClick = { vm.setupTse() }) { Text("DSFinV-K-Anmeldung wiederholen") }
        } else {
            Button(onClick = { vm.setupTse() }, enabled = c.hasCredentials) {
                Icon(Icons.Default.VerifiedUser, null); Spacer(Modifier.width(6.dp))
                Text(if (locked) "Einrichtung fortsetzen" else "TSE einrichten")
            }
        }
        Text("Wichtig: Eine neue Kasse musst du innerhalb eines Monats über „Mein ELSTER“ beim Finanzamt melden " +
            "(Kassenmeldung nach § 146a Abs. 4 AO). Die Seriennummern dafür stehen oben.", style = MaterialTheme.typography.bodySmall)

        Section("Getränkekarte")
        OutlinedButton(onClick = { vm.overlay.value = Overlay.Products }) {
            Icon(Icons.Default.Edit, null); Spacer(Modifier.width(6.dp)); Text("Produkte bearbeiten")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsEditor(vm: KasseViewModel) {
    val products by vm.allProducts.collectAsStateWithLifecycle()
    var editing by remember { mutableStateOf<Product?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Getränkekarte") },
                navigationIcon = { IconButton(onClick = { vm.overlay.value = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { editing = Product(name = "", priceCents = 0, sort = (products.maxOfOrNull { it.sort } ?: 0) + 1) },
                icon = { Icon(Icons.Default.Add, null) }, text = { Text("Neu") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(products, key = { it.id }) { p ->
                ListItem(
                    headlineContent = { Text(p.name + if (!p.active) " (ausgeblendet)" else "") },
                    supportingContent = { Text("${euro(p.priceCents)} · MwSt ${Vat.of(p.vat).label}") },
                    modifier = Modifier.clickableRow { editing = p },
                )
                HorizontalDivider()
            }
        }
    }
    editing?.let { p -> ProductDialog(p, onDismiss = { editing = null }, onSave = { vm.saveProduct(it); editing = null },
        onDelete = { vm.deleteProduct(p); editing = null }) }
}

@Composable
private fun ProductDialog(p: Product, onDismiss: () -> Unit, onSave: (Product) -> Unit, onDelete: () -> Unit) {
    var name by remember { mutableStateOf(p.name) }
    var price by remember { mutableStateOf(if (p.priceCents > 0) euro(p.priceCents).removeSuffix(" €") else "") }
    var vat by remember { mutableStateOf(Vat.of(p.vat)) }
    var active by remember { mutableStateOf(p.active) }
    var sort by remember { mutableStateOf(p.sort.toString()) }
    val cents = parseEuro(price.replace(".", ""))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (p.id == 0L) "Neues Produkt" else "Produkt bearbeiten") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Bezeichnung") }, singleLine = true)
                OutlinedTextField(price, { price = it }, label = { Text("Preis in €") }, singleLine = true,
                    isError = price.isNotBlank() && cents == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("Mehrwertsteuer")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(vat == Vat.NORMAL, { vat = Vat.NORMAL }, label = { Text("19 % (Getränke)") })
                    FilterChip(vat == Vat.REDUCED, { vat = Vat.REDUCED }, label = { Text("7 % (Speisen)") })
                }
                OutlinedTextField(sort, { sort = it.filter(Char::isDigit) }, label = { Text("Reihenfolge") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(active, { active = it }); Spacer(Modifier.width(8.dp)); Text("In der Kasse anzeigen")
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank() && cents != null && cents > 0, onClick = {
                onSave(p.copy(name = name.trim(), priceCents = cents!!, vat = vat.api, active = active, sort = sort.toIntOrNull() ?: 0))
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (p.id != 0L) TextButton(onClick = onDelete) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}
