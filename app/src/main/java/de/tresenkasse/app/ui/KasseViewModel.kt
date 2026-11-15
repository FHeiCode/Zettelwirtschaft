package de.tresenkasse.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.tresenkasse.app.TresenApp
import de.tresenkasse.app.data.*
import de.tresenkasse.app.fiscal.Line
import de.tresenkasse.app.fiscal.Vat
import de.tresenkasse.app.fiscal.aggregate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

/** Was gerade über der normalen Ansicht liegt. */
sealed interface Overlay {
    data class ShowReceipt(val receiptId: Long) : Overlay
    data class Transfer(val receiptId: Long) : Overlay
    data class TabDetail(val tabId: Long) : Overlay
    data object Products : Overlay
    data object Mandates : Overlay
}

class KasseViewModel(app: Application) : AndroidViewModel(app) {
    private val tresen = app as TresenApp
    private val dao = tresen.db.dao()
    val kasse = tresen.kasse
    val config: StateFlow<Config> = tresen.settings.config

    val products = dao.activeProducts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val allProducts = dao.allProducts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val openTabs = dao.openTabs().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val receipts = dao.recentReceipts().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val unclosed = dao.unclosedReceiptsFlow().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val closings = dao.closings().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val mandates = dao.mandates().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val openDebits = dao.openDebits().stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Warenkorb: Produkt-ID → Menge */
    private val _cart = MutableStateFlow<Map<Long, Long>>(emptyMap())
    val cart: StateFlow<Map<Long, Long>> = _cart

    val overlay = MutableStateFlow<Overlay?>(null)
    val busy = MutableStateFlow<String?>(null)
    val message = MutableStateFlow<String?>(null)

    fun cartLines(): List<Line> {
        val byId = allProducts.value.associateBy { it.id }
        return _cart.value.mapNotNull { (id, q) ->
            byId[id]?.let { Line(it.name, it.priceCents, q, Vat.of(it.vat)) }
        }
    }

    fun add(p: Product) { _cart.update { it + (p.id to (it[p.id] ?: 0) + 1) } }
    fun remove(p: Product) {
        _cart.update {
            val q = (it[p.id] ?: 0) - 1
            if (q <= 0) it - p.id else it + (p.id to q)
        }
    }
    fun clearCart() { _cart.value = emptyMap() }

    private fun run(label: String, block: suspend () -> Unit) {
        if (busy.value != null) return
        busy.value = label
        viewModelScope.launch {
            try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                message.value = e.message ?: e.javaClass.simpleName
            } finally { busy.value = null }
        }
    }

    // ----- Verkauf -----

    fun payCash(givenCents: Long?, tabId: Long? = null) = run("Wird signiert …") {
        val r = if (tabId != null) kasse.payTabCash(tabId, givenCents) else kasse.sellCash(cartLines(), givenCents)
        if (tabId == null) clearCart()
        overlay.value = Overlay.ShowReceipt(r.id)
    }

    fun startTransfer(tabId: Long? = null) = run("TSE-Transaktion wird gestartet …") {
        val r = if (tabId != null) kasse.startTabTransfer(tabId) else kasse.startTransfer(cartLines())
        overlay.value = Overlay.Transfer(r.id)
    }

    fun confirmTransfer(receiptId: Long, fromCart: Boolean) = run("Wird signiert …") {
        val r = kasse.confirmTransfer(receiptId)
        if (fromCart) clearCart()
        overlay.value = Overlay.ShowReceipt(r.id)
    }

    fun cancelTransfer(receiptId: Long) = run("Wird abgebrochen …") {
        kasse.cancelTransfer(receiptId)
        overlay.value = null
        message.value = "Zahlung abgebrochen"
    }

    fun payDebit(mandateId: Long, tabId: Long? = null) = run("Wird signiert …") {
        val r = if (tabId != null) kasse.payTabDebit(tabId, mandateId) else kasse.sellDebit(cartLines(), mandateId)
        if (tabId == null) clearCart()
        overlay.value = Overlay.ShowReceipt(r.id)
    }

    // ----- Lastschrift -----

    fun createMandate(name: String, iban: String, bic: String, png: ByteArray, onDone: (Mandate) -> Unit) = run("Mandat wird gespeichert …") {
        val m = kasse.createMandate(name, iban, bic, png, File(getApplication<Application>().filesDir, "mandate"))
        onDone(m)
    }

    fun revokeMandate(m: Mandate) = run("Wird widerrufen …") { kasse.revokeMandate(m) }

    fun exportDebits(date: java.time.LocalDate, onFile: (File) -> Unit) = run("SEPA-Datei wird erstellt …") {
        onFile(kasse.exportDebits(date, File(getApplication<Application>().filesDir, "sepa")))
    }

    // ----- Deckel -----

    fun bookToTab(tabId: Long?, newName: String) = run("Wird auf den Deckel gebucht …") {
        val lines = aggregate(cartLines())
        if (lines.isEmpty()) return@run
        val id = tabId ?: kasse.openTab(newName)
        kasse.addToTab(id, lines)
        clearCart()
        message.value = "Auf Deckel gebucht"
    }

    fun tabStornoOne(tabId: Long, line: Line) = run("Storno wird signiert …") {
        kasse.addToTab(tabId, listOf(line.copy(qty = -1)))
    }

    fun tabAddOne(tabId: Long, line: Line) = run("Wird gebucht …") {
        kasse.addToTab(tabId, listOf(line.copy(qty = 1)))
    }

    fun cancelTab(tabId: Long) = run("Deckel wird aufgelöst …") {
        kasse.cancelTab(tabId)
        overlay.value = null
    }

    fun tab(id: Long) = dao.tab(id)

    // ----- Belege -----

    suspend fun receipt(id: Long) = dao.receipt(id)

    fun storno(receiptId: Long) = run("Storno wird signiert …") {
        val r = kasse.storno(receiptId)
        overlay.value = Overlay.ShowReceipt(r.id)
    }

    // ----- Abschluss -----

    fun dayClosing() = run("Tagesabschluss …") {
        val c = kasse.dayClosing()
        message.value = when (c.state) {
            "COMPLETED" -> "Tagesabschluss übertragen"
            "PENDING" -> "Tagesabschluss übertragen, fiskaly prüft noch"
            "ERROR" -> "fiskaly meldet einen Fehler: ${c.error}"
            else -> c.error ?: "Abschluss lokal gespeichert, Übertragung später erneut versuchen"
        }
    }

    fun refreshClosing(id: String) = run("Wird abgefragt …") { kasse.refreshClosing(id) }

    fun export(fromSec: Long, toSec: Long, onFile: (File) -> Unit) = run("Export …") {
        val f = kasse.exportDsfinvk(fromSec, toSec, File(getApplication<Application>().cacheDir, "export")) { busy.value = it }
        onFile(f)
    }

    // ----- Einstellungen -----

    fun updateConfig(change: (Config) -> Config) = tresen.settings.update(change)

    fun setupTse() = run("TSE wird eingerichtet …") {
        kasse.setupTse { busy.value = it }
        message.value = "TSE ist eingerichtet"
    }

    fun saveProduct(p: Product) = viewModelScope.launch { dao.upsertProduct(p) }
    fun deleteProduct(p: Product) = viewModelScope.launch { dao.deleteProduct(p) }
}
