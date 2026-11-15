package de.zettelwirtschaft.kasse.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as ACanvas
import android.graphics.Paint
import android.graphics.Path as APath
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.zettelwirtschaft.kasse.data.Config
import de.zettelwirtschaft.kasse.data.Mandate
import de.zettelwirtschaft.kasse.fiscal.Creditor
import de.zettelwirtschaft.kasse.fiscal.GiroCode
import de.zettelwirtschaft.kasse.fiscal.SepaDebit
import de.zettelwirtschaft.kasse.fiscal.euro
import java.io.ByteArrayOutputStream
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Unbar bezahlen: Überweisung per GiroCode oder Lastschrift von einem Stammgast mit Mandat. */
@Composable
fun UnbarDialog(vm: KasseViewModel, totalCents: Long, onDismiss: () -> Unit, onTransfer: () -> Unit, onDebit: (Long) -> Unit) {
    val mandates by vm.mandates.collectAsStateWithLifecycle()
    val active = mandates.filter { it.active }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unbar: ${euro(totalCents)}") },
        text = {
            Column {
                FilledTonalButton(onClick = onTransfer, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.QrCode2, null); Spacer(Modifier.width(6.dp)); Text("Überweisung (GiroCode)")
                }
                Spacer(Modifier.height(12.dp))
                Text("Lastschrift von", style = MaterialTheme.typography.titleSmall)
                if (active.isEmpty()) Text("Noch keine Mandate. Stammgäste legst du in den Einstellungen an.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 280.dp)) {
                    items(active, key = { it.id }) { m ->
                        ListItem(
                            headlineContent = { Text(m.name) },
                            supportingContent = { Text("IBAN …" + m.iban.takeLast(4)) },
                            modifier = Modifier.clickableRow { onDebit(m.id) },
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MandatesEditor(vm: KasseViewModel) {
    val mandates by vm.mandates.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var creating by remember { mutableStateOf(false) }
    var revoke by remember { mutableStateOf<Mandate?>(null) }

    if (creating) {
        NewMandate(vm, config, onClose = { creating = false })
        return
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stammgäste & Mandate") },
                navigationIcon = { IconButton(onClick = { vm.overlay.value = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
        floatingActionButton = {
            if (config.debitReady) ExtendedFloatingActionButton(onClick = { creating = true },
                icon = { Icon(Icons.Default.Draw, null) }, text = { Text("Neues Mandat") })
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (!config.debitReady) item {
                Text("Für Lastschriften bitte zuerst Kontoinhaber, IBAN und Gläubiger-ID in den Einstellungen eintragen.",
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            }
            items(mandates, key = { it.id }) { m ->
                ListItem(
                    headlineContent = { Text(m.name + if (!m.active) " (widerrufen)" else "") },
                    supportingContent = {
                        Text("${m.reference} · ${GiroCode.normalizeIban(m.iban).chunked(4).joinToString(" ")}\n" +
                            "unterschrieben ${DateFormat.getDateInstance().format(Date(m.signedAt))}")
                    },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { shareMandate(context, m, config) }) { Icon(Icons.Default.Share, "Kopie teilen") }
                            if (m.active) IconButton(onClick = { revoke = m }) { Icon(Icons.Default.Block, "Widerrufen") }
                        }
                    },
                )
                HorizontalDivider()
            }
        }
    }
    revoke?.let { m ->
        AlertDialog(
            onDismissRequest = { revoke = null },
            title = { Text("Mandat von ${m.name} widerrufen?") },
            text = { Text("Danach ist keine neue Lastschrift mehr möglich. Schon gebuchte, offene Lastschriften bleiben in der Liste.") },
            confirmButton = { TextButton(onClick = { vm.revokeMandate(m); revoke = null }) { Text("Widerrufen") } },
            dismissButton = { TextButton(onClick = { revoke = null }) { Text("Abbrechen") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewMandate(vm: KasseViewModel, config: Config, onClose: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var iban by remember { mutableStateOf("") }
    var bic by remember { mutableStateOf("") }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var current by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    val ibanOk = GiroCode.isValidIban(iban)
    val previewText = SepaDebit.mandateText(Creditor(config.accountHolder, config.iban, config.bic, config.creditorId), "wird beim Speichern vergeben")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Neues Lastschriftmandat") },
                navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name des Kontoinhabers") }, singleLine = true)
            OutlinedTextField(iban, { iban = it }, Modifier.fillMaxWidth(), label = { Text("IBAN") }, singleLine = true,
                isError = iban.isNotBlank() && !ibanOk, supportingText = { if (iban.isNotBlank() && !ibanOk) Text("IBAN ist ungültig") })
            OutlinedTextField(bic, { bic = it }, Modifier.fillMaxWidth(), label = { Text("BIC (optional)") }, singleLine = true)
            Surface(tonalElevation = 2.dp, shape = MaterialTheme.shapes.medium) {
                Text(previewText, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
            }
            Text("Unterschrift des Gastes", fontWeight = FontWeight.SemiBold)
            Box(
                Modifier.fillMaxWidth().height(180.dp)
                    .background(Color.White, MaterialTheme.shapes.medium)
                    .border(1.dp, MaterialTheme.colorScheme.outline, MaterialTheme.shapes.medium)
                    .onSizeChanged { padSize = it }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { current = listOf(it) },
                            onDrag = { change, _ -> change.consume(); current = current + change.position },
                            onDragEnd = { if (current.size > 1) strokes.add(current); current = emptyList() },
                        )
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    (strokes + listOf(current)).forEach { pts ->
                        if (pts.size > 1) {
                            val p = Path().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
                            drawPath(p, Color.Black, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { strokes.clear() }) { Text("Unterschrift löschen") }
            }
            Button(
                enabled = name.isNotBlank() && ibanOk && strokes.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    val png = renderSignature(strokes.toList(), padSize)
                    vm.createMandate(name, iban, bic, png) { m ->
                        shareMandate(context, m, config)
                        onClose()
                    }
                },
            ) { Text("Mandat speichern") }
            Text("Nach dem Speichern kannst du dem Gast eine Kopie des Mandats als Bild schicken.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

private fun renderSignature(strokes: List<List<Offset>>, size: IntSize): ByteArray {
    val w = size.width.coerceAtLeast(10)
    val h = size.height.coerceAtLeast(10)
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = ACanvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.BLACK; style = Paint.Style.STROKE; strokeWidth = 5f
        strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    for (pts in strokes) {
        if (pts.size < 2) continue
        val p = APath().apply { moveTo(pts[0].x, pts[0].y); pts.drop(1).forEach { lineTo(it.x, it.y) } }
        c.drawPath(p, paint)
    }
    return ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}

/** Kopie des Mandats (Text, Kontodaten, Unterschrift) als PNG teilen. */
fun shareMandate(context: Context, m: Mandate, config: Config) {
    val text = SepaDebit.mandateText(Creditor(config.accountHolder, config.iban, config.bic, config.creditorId), m.reference) +
        "\n\nKontoinhaber: ${m.name}\nIBAN: ${GiroCode.normalizeIban(m.iban).chunked(4).joinToString(" ")}" +
        (if (m.bic.isNotBlank()) "\nBIC: ${m.bic}" else "") +
        "\nDatum: ${DateFormat.getDateTimeInstance().format(Date(m.signedAt))}\n\nUnterschrift:"
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.SANS_SERIF; textSize = 26f; color = android.graphics.Color.BLACK }
    val width = 900
    val wrapped = text.lines().flatMap { wrap(it, paint, width - 60f) }
    val sig = runCatching { BitmapFactory.decodeFile(m.signatureFile) }.getOrNull()
    val lineH = 34
    val height = 40 + wrapped.size * lineH + (sig?.let { it.height * (width - 60) / it.width + 20 } ?: 0) + 40
    val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val c = ACanvas(bmp)
    c.drawColor(android.graphics.Color.WHITE)
    var y = 40f + lineH
    for (l in wrapped) { c.drawText(l, 30f, y, paint); y += lineH }
    sig?.let {
        val h = it.height * (width - 60) / it.width
        c.drawBitmap(it, null, android.graphics.Rect(30, y.toInt(), width - 30, y.toInt() + h), null)
    }
    val dir = File(context.cacheDir, "belege").apply { mkdirs() }
    val f = File(dir, "Mandat_${m.reference}.png")
    f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    shareFile(context, f, "image/png")
}

private fun wrap(line: String, paint: Paint, max: Float): List<String> {
    if (line.isEmpty()) return listOf("")
    val out = mutableListOf<String>()
    var cur = ""
    for (word in line.split(" ")) {
        val next = if (cur.isEmpty()) word else "$cur $word"
        if (paint.measureText(next) > max && cur.isNotEmpty()) { out += cur; cur = word } else cur = next
    }
    if (cur.isNotEmpty()) out += cur
    return out
}
