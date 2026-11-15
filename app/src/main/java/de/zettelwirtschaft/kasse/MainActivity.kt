package de.zettelwirtschaft.kasse

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.zettelwirtschaft.kasse.ui.*

class MainActivity : ComponentActivity() {
    private val vm: KasseViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { KassenTheme { Root(vm) } }
    }
}

private enum class Page(val title: String, val icon: ImageVector) {
    Kasse("Kasse", Icons.Default.PointOfSale),
    Deckel("Deckel", Icons.Default.Receipt),
    Belege("Belege", Icons.AutoMirrored.Filled.ReceiptLong),
    Abschluss("Abschluss", Icons.Default.Summarize),
    Einstellungen("Einstellungen", Icons.Default.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Root(vm: KasseViewModel) {
    var page by rememberSaveable { mutableStateOf(Page.Kasse) }
    val overlay by vm.overlay.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val config by vm.config.collectAsStateWithLifecycle()
    val snack = remember { SnackbarHostState() }

    // Ohne Overlay als Snackbar, sonst als Dialog (eine Snackbar läge hinter dem Overlay)
    LaunchedEffect(message, overlay) {
        if (overlay == null) message?.let { vm.message.value = null; snack.showSnackbar(it, withDismissAction = true) }
    }
    // Ohne Betrieb und TSE geht nichts: dann gleich zu den Einstellungen
    LaunchedEffect(Unit) { if (!config.tseReady || !config.businessComplete) page = Page.Einstellungen }

    Scaffold(
        topBar = { TopAppBar(title = { Text(if (page == Page.Kasse) "Zettelwirtschaft" else page.title) }) },
        bottomBar = {
            NavigationBar {
                Page.entries.forEach { p ->
                    NavigationBarItem(
                        selected = page == p, onClick = { page = p },
                        icon = { Icon(p.icon, null) },
                        label = { Text(p.title, maxLines = 1, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (page) {
                Page.Kasse -> KasseScreen(vm)
                Page.Deckel -> TabsScreen(vm)
                Page.Belege -> ReceiptsScreen(vm)
                Page.Abschluss -> ClosingScreen(vm)
                Page.Einstellungen -> SettingsScreen(vm)
            }
        }
    }

    overlay?.let { o ->
        // Eine laufende Überweisung darf nicht einfach weggewischt werden
        BackHandler(enabled = o !is Overlay.Transfer) { vm.overlay.value = null }
        Dialog(
            onDismissRequest = { if (o !is Overlay.Transfer) vm.overlay.value = null },
            properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false),
        ) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                when (o) {
                    is Overlay.ShowReceipt -> ReceiptView(vm, o.receiptId)
                    is Overlay.Transfer -> TransferView(vm, o.receiptId)
                    is Overlay.TabDetail -> TabDetail(vm, o.tabId)
                    Overlay.Products -> ProductsEditor(vm)
                    Overlay.Mandates -> MandatesEditor(vm)
                }
            }
        }
    }

    if (overlay != null) message?.let { m ->
        AlertDialog(
            onDismissRequest = { vm.message.value = null },
            confirmButton = { TextButton(onClick = { vm.message.value = null }) { Text("OK") } },
            text = { Text(m) },
        )
    }

    busy?.let { label ->
        Dialog(onDismissRequest = {}) {
            Surface(shape = MaterialTheme.shapes.large, tonalElevation = 6.dp) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(label)
                }
            }
        }
    }
}
