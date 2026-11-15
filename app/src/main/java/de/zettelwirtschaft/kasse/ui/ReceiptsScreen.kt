package de.zettelwirtschaft.kasse.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.zettelwirtschaft.kasse.data.ReceiptWithLines
import de.zettelwirtschaft.kasse.fiscal.GiroCode
import de.zettelwirtschaft.kasse.fiscal.ReceiptText
import de.zettelwirtschaft.kasse.fiscal.euro
import java.io.File

@Composable
fun ReceiptsScreen(vm: KasseViewModel) {
    val list by vm.receipts.collectAsStateWithLifecycle()
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Noch keine Belege") }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(list, key = { it.receipt.id }) { rw ->
            val r = rw.receipt
            ListItem(
                headlineContent = {
                    Text(buildString {
                        append("Nr. ${r.number}")
                        when (r.kind) {
                            "STORNO" -> append(" · Storno zu ${r.stornoOfNumber}")
                            "ABORT" -> append(" · abgebrochen")
                        }
                        if (r.stornoedById != null) append(" · storniert")
                        r.tabName?.let { append(" · $it") }
                    })
                },
                supportingContent = {
                    Text(ReceiptText.time(r.timeEnd ?: (r.createdAt / 1000)) +
                        (if (r.payment == "CASH") " · bar" else if (r.mandateRef != null) " · Lastschrift" else if (r.payment == "NON_CASH") " · Überweisung" else "") +
                        (if (r.tseError != null) " · ohne TSE" else ""))
                },
                trailingContent = { Text(euro(r.totalCents), fontWeight = FontWeight.SemiBold) },
                modifier = Modifier.clickableRow { vm.overlay.value = Overlay.ShowReceipt(r.id) },
            )
            HorizontalDivider()
        }
    }
}

@Composable
fun ReceiptView(vm: KasseViewModel, receiptId: Long) {
    val config by vm.config.collectAsStateWithLifecycle()
    var rw by remember(receiptId) { mutableStateOf<ReceiptWithLines?>(null) }
    LaunchedEffect(receiptId) { rw = vm.receipt(receiptId) }
    val current = rw ?: return
    val r = current.receipt
    val text = remember(current, config) { ReceiptText.build(current, config) }
    val context = LocalContext.current
    var confirmStorno by remember { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (r.kind == "SALE" && r.payment == "CASH" && r.givenCents != null) {
            Text("Rückgeld ${euro(r.givenCents - r.totalCents)}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
        }
        Text("Beleg zum Mitnehmen", style = MaterialTheme.typography.titleMedium)
        Text("Mit der Handykamera scannen oder als Bild teilen", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        val qr = remember(text) { qrBitmap(text, 900).asImageBitmap() }
        Image(qr, "Beleg als QR-Code", Modifier.fillMaxWidth(0.85f).aspectRatio(1f))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { shareReceipt(context, current, text) }) {
                Icon(Icons.Default.Share, null); Spacer(Modifier.width(4.dp)); Text("Als Bild teilen")
            }
            Button(onClick = { vm.overlay.value = null }) { Text("Fertig") }
        }
        Spacer(Modifier.height(16.dp))
        if (r.tseError != null) Text(r.tseError, color = MaterialTheme.colorScheme.error)
        Surface(shape = MaterialTheme.shapes.medium, tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, modifier = Modifier.padding(12.dp))
        }
        r.qrCodeData?.let { data ->
            Spacer(Modifier.height(16.dp))
            Text("Prüf-QR-Code der TSE (für die Kassen-Nachschau)", style = MaterialTheme.typography.bodySmall)
            val tseQr = remember(data) { qrBitmap(data, 500).asImageBitmap() }
            Image(tseQr, "TSE-QR-Code", Modifier.size(180.dp))
        }
        if (r.kind == "SALE" && r.stornoedById == null) {
            Spacer(Modifier.height(16.dp))
            OutlinedButton(onClick = { confirmStorno = true }, colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.AutoMirrored.Filled.Undo, null); Spacer(Modifier.width(4.dp)); Text("Beleg stornieren")
            }
        }
    }

    if (confirmStorno) AlertDialog(
        onDismissRequest = { confirmStorno = false },
        title = { Text("Beleg Nr. ${r.number} stornieren?") },
        text = { Text("Es wird ein Storno-Beleg über ${euro(-r.totalCents)} erstellt. " +
            when {
                r.payment == "CASH" -> "Den Betrag gibst du bar zurück."
                r.mandateRef != null && r.debitExportedAt == null -> "Die Lastschrift wird dann nicht eingezogen."
                else -> "Den Betrag überweist du selbst zurück."
            }) },
        confirmButton = { TextButton(onClick = { confirmStorno = false; vm.storno(r.id) }) { Text("Stornieren") } },
        dismissButton = { TextButton(onClick = { confirmStorno = false }) { Text("Abbrechen") } },
    )
}

@Composable
fun TransferView(vm: KasseViewModel, receiptId: Long) {
    val config by vm.config.collectAsStateWithLifecycle()
    var rw by remember(receiptId) { mutableStateOf<ReceiptWithLines?>(null) }
    LaunchedEffect(receiptId) { rw = vm.receipt(receiptId) }
    val current = rw ?: return
    val r = current.receipt
    var confirm by remember { mutableStateOf(false) }
    val reference = "${config.businessName.take(40)} Beleg ${r.number}".trim()
    val payload = remember(r, config) {
        GiroCode.payload(config.accountHolder, config.iban, config.bic, r.totalCents, reference)
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Überweisung", style = MaterialTheme.typography.titleLarge)
        Text(euro(r.totalCents), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
        r.tabName?.let { Text("Deckel: $it") }
        Spacer(Modifier.height(8.dp))
        Text("Mit der Banking-App scannen (GiroCode)", textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        val qr = remember(payload) { qrBitmap(payload, 900).asImageBitmap() }
        Image(qr, "GiroCode", Modifier.fillMaxWidth(0.85f).aspectRatio(1f))
        Spacer(Modifier.height(8.dp))
        Text("${config.accountHolder}\n${GiroCode.normalizeIban(config.iban).chunked(4).joinToString(" ")}\nZweck: $reference",
            style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
        if (r.tseError != null) {
            Spacer(Modifier.height(8.dp))
            Text(r.tseError, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
            Icon(Icons.Default.CheckCircle, null); Spacer(Modifier.width(8.dp)); Text("Zahlung eingegangen")
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { vm.cancelTransfer(r.id) }, modifier = Modifier.fillMaxWidth()) { Text("Abbrechen") }
    }

    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text("Geld wirklich eingegangen?") },
        text = { Text("Bitte in deiner Banking-App prüfen, dass ${euro(r.totalCents)} mit dem Zweck „$reference“ angekommen sind. " +
            "Erst dann wird der Verkauf abgeschlossen.") },
        confirmButton = { TextButton(onClick = { confirm = false; vm.confirmTransfer(r.id, fromCart = r.tabId == null) }) { Text("Ja, eingegangen") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("Noch nicht") } },
    )
}

/** Beleg als PNG (Text plus TSE-QR-Code) über den Android-Teilen-Dialog weitergeben. */
fun shareReceipt(context: Context, rw: ReceiptWithLines, text: String) {
    val lines = text.lines()
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE; textSize = 26f; color = android.graphics.Color.BLACK }
    val lineH = 34
    val width = 760
    val wrapped = lines.flatMap { l -> if (l.isEmpty()) listOf("") else l.chunked(44) }
    val qr = rw.receipt.qrCodeData?.let { qrBitmap(it, 360) }
    val height = 40 + wrapped.size * lineH + (qr?.let { it.height + 40 } ?: 0) + 40
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)
    var y = 40f + lineH
    for (l in wrapped) { c.drawText(l, 30f, y, paint); y += lineH }
    qr?.let { c.drawBitmap(it, ((width - it.width) / 2).toFloat(), y + 10, null) }
    val dir = File(context.cacheDir, "belege").apply { mkdirs() }
    val f = File(dir, "Beleg_${rw.receipt.number}.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    shareFile(context, f, "image/png")
}

fun shareFile(context: Context, f: File, mime: String) {
    val uri = FileProvider.getUriForFile(context, context.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, f.name))
}
