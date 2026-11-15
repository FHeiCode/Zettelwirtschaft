package de.tresenkasse.app.data

import de.tresenkasse.app.fiscal.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Alle Kassenvorgänge. Jeder Vorgang wird von der TSE signiert. Ist die TSE nicht erreichbar,
 * läuft die Kasse weiter und der Beleg trägt den Vermerk „TSE nicht erreichbar“
 * (nachträgliches Signieren ist nicht erlaubt).
 */
class Kasse(
    private val dao: KasseDao,
    val settings: Settings,
    private val appVersion: String,
) {
    private val sign = SignDe(settings)
    private val dsfinvk = Dsfinvk(settings)
    private val lock = Mutex()

    class NotReady(message: String) : Exception(message)

    private fun requireReady() {
        val c = settings.current
        if (!c.businessComplete) throw NotReady("Bitte zuerst Name und Anschrift des Betriebs in den Einstellungen eintragen.")
        if (!c.tseReady) throw NotReady("Bitte zuerst die TSE in den Einstellungen einrichten.")
    }

    // ---------- TSE einrichten ----------

    suspend fun setupTse(progress: (String) -> Unit) {
        sign.setup(progress)
        progress("Kasse wird für den DSFinV-K-Export angemeldet …")
        dsfinvk.registerCashRegister(appVersion)
        progress("Fertig")
    }

    // ---------- Verkauf ----------

    /** Sofortverkauf oder Deckel bezahlen, bar. */
    suspend fun sellCash(lines: List<Line>, givenCents: Long?, tab: Tab? = null): Receipt =
        sell(lines, "CASH", givenCents, tab)

    private suspend fun sell(lines: List<Line>, payment: String, givenCents: Long?, tab: Tab?, mandate: Mandate? = null): Receipt = lock.withLock {
        requireReady()
        if (mandate != null && !mandate.active) throw NotReady("Dieses Mandat ist widerrufen.")
        val items = aggregate(lines)
        require(items.isNotEmpty()) { "Keine Posten" }
        val txId = UUID.randomUUID().toString()
        var error: String? = null
        val res = try {
            sign.start(txId)
            sign.finish(txId, SignDe.receiptSchema("RECEIPT", items, payment))
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = tseErrorText(e); null
        }
        val r = saveReceipt("SALE", payment, items, givenCents, tab, txId, res, error, mandate)
        if (tab != null) dao.updateTab(tab.copy(state = "PAID", closedAt = System.currentTimeMillis()))
        r
    }

    /**
     * Überweisung per GiroCode: Die TSE-Transaktion wird jetzt gestartet und erst abgeschlossen,
     * wenn der Zahlungseingang bestätigt oder die Zahlung abgebrochen wird.
     */
    suspend fun startTransfer(lines: List<Line>, tab: Tab? = null): Receipt = lock.withLock {
        requireReady()
        if (!settings.current.giroReady) throw NotReady("Bitte zuerst Kontoinhaber und IBAN in den Einstellungen eintragen.")
        val items = aggregate(lines)
        require(items.isNotEmpty()) { "Keine Posten" }
        val txId = UUID.randomUUID().toString()
        var error: String? = null
        val res = try { sign.start(txId) } catch (e: CancellationException) { throw e } catch (e: Exception) {
            error = tseErrorText(e); null
        }
        saveReceipt("PENDING", "NON_CASH", items, null, tab, txId, res, error)
    }

    suspend fun confirmTransfer(receiptId: Long): Receipt = lock.withLock {
        val rw = dao.receipt(receiptId) ?: error("Beleg fehlt")
        val r = rw.receipt
        check(r.kind == "PENDING") { "Diese Zahlung ist schon abgeschlossen" }
        val lines = rw.lines.map { it.toLine() }
        var error = r.tseError
        var res: TxResult? = null
        if (error == null) {
            try {
                res = sign.finish(r.txId, SignDe.receiptSchema("RECEIPT", lines, "NON_CASH"))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = tseErrorText(e) }
        }
        val done = applyTx(r.copy(kind = "SALE"), res, error)
        dao.updateReceipt(done)
        r.tabId?.let { id -> dao.tabNow(id)?.tab?.let { dao.updateTab(it.copy(state = "PAID", closedAt = System.currentTimeMillis())) } }
        done
    }

    /** Überweisung abgebrochen: TSE-Transaktion als Belegabbruch beenden, Deckel bleibt offen. */
    suspend fun cancelTransfer(receiptId: Long) = lock.withLock {
        val r = dao.receipt(receiptId)?.receipt ?: return@withLock
        if (r.kind != "PENDING") return@withLock
        var error = r.tseError
        var res: TxResult? = null
        if (error == null) {
            try {
                res = sign.finish(r.txId, SignDe.receiptSchema("ABORT", emptyList(), null))
            } catch (e: CancellationException) { throw e } catch (e: Exception) { error = tseErrorText(e) }
        }
        dao.updateReceipt(applyTx(r.copy(kind = "ABORT", payment = null), res, error))
    }

    /** Beim Start: Überweisungen, die nie bestätigt wurden (App beendet), als abgebrochen abschließen. */
    suspend fun abortStalePending() {
        for (r in dao.pendingReceipts()) runCatching { cancelTransfer(r.id) }
    }

    // ---------- Deckel ----------

    suspend fun openTab(name: String): Long =
        dao.insertTab(Tab(name = name.trim().ifBlank { "Deckel" }, openedAt = System.currentTimeMillis()))

    /** Posten auf den Deckel buchen (oder mit negativer Menge stornieren) – als TSE-Bestellung. */
    suspend fun addToTab(tabId: Long, lines: List<Line>) = lock.withLock {
        requireReady()
        val items = lines.filter { it.qty != 0L }
        if (items.isEmpty()) return@withLock
        val txId = UUID.randomUUID().toString()
        var error: String? = null
        val res = try {
            sign.start(txId)
            sign.finish(txId, SignDe.orderSchema(items))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = tseErrorText(e); null }
        val now = System.currentTimeMillis()
        dao.insertTabLines(items.map {
            TabLine(tabId = tabId, text = it.text, unitCents = it.unitCents, qty = it.qty, vat = it.vat.api,
                createdAt = now, orderTxId = txId, orderTxNumber = res?.number, tseError = error)
        })
    }

    /** Deckel auflösen: alle Posten stornieren und schließen. */
    suspend fun cancelTab(tabId: Long) {
        val tw = dao.tabNow(tabId) ?: return
        val open = aggregate(tw.lines.map { it.toLine() })
        if (open.isNotEmpty()) addToTab(tabId, open.map { it.copy(qty = -it.qty) })
        dao.updateTab(tw.tab.copy(state = "CANCELLED", closedAt = System.currentTimeMillis()))
    }

    suspend fun payTabCash(tabId: Long, givenCents: Long?): Receipt {
        val tw = dao.tabNow(tabId) ?: error("Deckel fehlt")
        return sell(tw.lines.map { it.toLine() }, "CASH", givenCents, tw.tab)
    }

    suspend fun startTabTransfer(tabId: Long): Receipt {
        val tw = dao.tabNow(tabId) ?: error("Deckel fehlt")
        return startTransfer(tw.lines.map { it.toLine() }, tw.tab)
    }

    // ---------- Lastschrift ----------

    suspend fun createMandate(name: String, iban: String, bic: String, signaturePng: ByteArray, dir: File): Mandate {
        val c = settings.current
        if (!c.debitReady) throw NotReady("Bitte zuerst Konto und Gläubiger-ID in den Einstellungen eintragen.")
        require(name.isNotBlank()) { "Name fehlt" }
        require(GiroCode.isValidIban(iban)) { "IBAN ist ungültig" }
        dir.mkdirs()
        val ref = "TK" + System.currentTimeMillis().toString(36).uppercase()
        val f = File(dir, "$ref.png").apply { writeBytes(signaturePng) }
        val m = Mandate(reference = ref, name = name.trim(), iban = GiroCode.normalizeIban(iban), bic = bic.trim().uppercase(),
            signedAt = System.currentTimeMillis(), signatureFile = f.absolutePath)
        return m.copy(id = dao.insertMandate(m))
    }

    suspend fun revokeMandate(m: Mandate) = dao.updateMandate(m.copy(active = false))

    suspend fun sellDebit(lines: List<Line>, mandateId: Long): Receipt {
        val m = dao.mandate(mandateId) ?: error("Mandat fehlt")
        return sell(lines, "NON_CASH", null, null, m)
    }

    suspend fun payTabDebit(tabId: Long, mandateId: Long): Receipt {
        val tw = dao.tabNow(tabId) ?: error("Deckel fehlt")
        val m = dao.mandate(mandateId) ?: error("Mandat fehlt")
        return sell(tw.lines.map { it.toLine() }, "NON_CASH", null, tw.tab, m)
    }

    /** Alle offenen Lastschriften in eine pain.008-Datei schreiben und als eingereicht markieren. */
    suspend fun exportDebits(collectionDate: LocalDate, dir: File): File = lock.withLock {
        val c = settings.current
        if (!c.debitReady) throw NotReady("Bitte zuerst Konto und Gläubiger-ID in den Einstellungen eintragen.")
        val open = dao.openDebitsNow()
        if (open.isEmpty()) throw NotReady("Keine offenen Lastschriften.")
        val zone = ZoneId.of("Europe/Berlin")
        val debits = open.map { r ->
            val m = dao.mandate(r.mandateId!!) ?: error("Mandat zu Beleg ${r.number} fehlt")
            Debit(
                endToEndId = "TK-${r.number}",
                cents = r.totalCents,
                mandateRef = m.reference,
                mandateSignedOn = java.time.Instant.ofEpochMilli(m.signedAt).atZone(zone).toLocalDate(),
                debtorName = m.name, debtorIban = m.iban, debtorBic = m.bic,
                remittance = "${c.businessName} Beleg ${r.number} vom " +
                    java.time.Instant.ofEpochMilli(r.createdAt).atZone(zone).toLocalDate().let { "%02d.%02d.%d".format(it.dayOfMonth, it.monthValue, it.year) },
            )
        }
        val now = java.time.LocalDateTime.now(zone).withNano(0)
        val xml = SepaDebit.pain008(
            messageId = "TK" + System.currentTimeMillis().toString(36).uppercase(),
            createdAt = now.toString().let { if (it.length == 16) "$it:00" else it },
            collectionDate = collectionDate,
            creditor = Creditor(c.accountHolder, c.iban, c.bic, c.creditorId),
            debits = debits,
        )
        dir.mkdirs()
        val f = File(dir, "Lastschriften_${collectionDate}.xml").apply { writeText(xml) }
        dao.markDebitsExported(open.map { it.id }, System.currentTimeMillis())
        f
    }

    // ---------- Storno ----------

    /** Abgeschlossenen Beleg stornieren: neuer Beleg mit negativen Beträgen, der auf den alten verweist. */
    suspend fun storno(receiptId: Long): Receipt = lock.withLock {
        requireReady()
        val rw = dao.receipt(receiptId) ?: error("Beleg fehlt")
        val orig = rw.receipt
        check(orig.kind == "SALE") { "Nur Verkäufe können storniert werden" }
        check(orig.stornoedById == null) { "Dieser Beleg ist schon storniert" }
        val lines = rw.lines.map { it.toLine().copy(qty = -it.qty) }
        val txId = UUID.randomUUID().toString()
        var error: String? = null
        val res = try {
            sign.start(txId)
            sign.finish(txId, SignDe.receiptSchema("RECEIPT", lines, orig.payment))
        } catch (e: CancellationException) { throw e } catch (e: Exception) { error = tseErrorText(e); null }
        val number = dao.maxReceiptNumber() + 1
        val base = Receipt(
            number = number, kind = "STORNO", payment = orig.payment, totalCents = lines.sumOf { it.totalCents },
            tabId = orig.tabId, tabName = orig.tabName, stornoOfId = orig.id, stornoOfNumber = orig.number,
            stornoOfTxId = orig.txId.takeIf { orig.tseError == null && orig.txNumber != null },
            mandateId = orig.mandateId, mandateRef = orig.mandateRef,
            createdAt = System.currentTimeMillis(), txId = txId,
        )
        val r = applyTx(base, res, error)
        val id = dao.insertReceipt(r)
        dao.insertReceiptLines(lines.map { ReceiptLine(receiptId = id, text = it.text, unitCents = it.unitCents, qty = it.qty, vat = it.vat.api) })
        dao.updateReceipt(orig.copy(stornoedById = id))
        r.copy(id = id)
    }

    // ---------- Tagesabschluss ----------

    /** Z-Bon: alle Belege seit dem letzten Abschluss zusammenfassen und an fiskaly übertragen. */
    suspend fun dayClosing(): Closing = lock.withLock {
        val c = settings.current
        if (!c.tseReady) throw NotReady("Bitte zuerst die TSE in den Einstellungen einrichten.")
        val receipts = dao.unclosedReceipts()
        val id = UUID.randomUUID().toString()
        val exportId = dao.maxClosingExportId() + 1
        val date = LocalDate.now(ZoneId.of("Europe/Berlin")).toString()
        val sales = receipts.filter { it.receipt.kind == "SALE" || it.receipt.kind == "STORNO" }
        val allLines = sales.flatMap { it.lines.map { l -> l.toLine() } }
        val perVat = amountsPerVat(allLines).associate { it.vat to it.grossCents }
        val closing = Closing(
            id = id, exportId = exportId, businessDate = date, createdAt = System.currentTimeMillis(),
            receiptCount = receipts.size,
            totalCents = sales.sumOf { it.receipt.totalCents },
            cashCents = sales.filter { it.receipt.payment == "CASH" }.sumOf { it.receipt.totalCents },
            nonCashCents = sales.filter { it.receipt.payment == "NON_CASH" }.sumOf { it.receipt.totalCents },
            normalGrossCents = perVat[Vat.NORMAL] ?: 0L, reducedGrossCents = perVat[Vat.REDUCED] ?: 0L,
            stornoCount = receipts.count { it.receipt.kind == "STORNO" },
            state = "LOCAL",
        )
        dao.insertClosing(closing)
        dao.assignClosing(receipts.map { it.receipt.id }, id)
        uploadClosingLocked(closing)
    }

    /** Abschluss (erneut) übertragen bzw. Zustand bei fiskaly abfragen. */
    suspend fun refreshClosing(id: String): Closing = lock.withLock {
        val cl = dao.closing(id) ?: error("Abschluss fehlt")
        if (cl.state == "LOCAL" || (cl.state == "ERROR" && cl.error?.startsWith("Übertragung") == true)) uploadClosingLocked(cl)
        else pollState(cl)
    }

    private suspend fun uploadClosingLocked(cl: Closing): Closing {
        val c = settings.current
        val updated = try {
            if (!c.cashRegisterRegistered) dsfinvk.registerCashRegister(appVersion)
            val body = ClosingBuilder.build(cl.exportId, cl.businessDate, c.clientId,
                dao.receiptsOfClosing(cl.id), System.currentTimeMillis() / 1000)
            dsfinvk.insertClosing(cl.id, body)
            cl.copy(state = "PENDING", error = null)
        } catch (e: CancellationException) { throw e } catch (e: Exception) {
            cl.copy(state = "LOCAL", error = "Übertragung fehlgeschlagen: ${e.message}")
        }
        dao.updateClosing(updated)
        return if (updated.state == "PENDING") pollState(updated) else updated
    }

    private suspend fun pollState(cl: Closing): Closing {
        val updated = try {
            val (state, err) = dsfinvk.closingState(cl.id)
            cl.copy(state = state, error = err)
        } catch (e: CancellationException) { throw e } catch (e: Exception) { cl }
        dao.updateClosing(updated)
        return updated
    }

    suspend fun exportDsfinvk(fromSec: Long, toSec: Long, dir: File, progress: (String) -> Unit): File {
        dir.mkdirs()
        val f = File(dir, "DSFinV-K_${LocalDate.now()}.zip")
        return dsfinvk.export(fromSec, toSec, f, progress)
    }

    // ---------- intern ----------

    private suspend fun saveReceipt(
        kind: String, payment: String?, items: List<Line>, givenCents: Long?, tab: Tab?,
        txId: String, res: TxResult?, error: String?, mandate: Mandate? = null,
    ): Receipt {
        val number = dao.maxReceiptNumber() + 1
        val base = Receipt(
            number = number, kind = kind, payment = payment, totalCents = items.sumOf { it.totalCents },
            givenCents = givenCents, tabId = tab?.id, tabName = tab?.name,
            createdAt = System.currentTimeMillis(), txId = txId,
            mandateId = mandate?.id, mandateRef = mandate?.reference,
        )
        val r = applyTx(base, res, error)
        val id = dao.insertReceipt(r)
        dao.insertReceiptLines(items.map { ReceiptLine(receiptId = id, text = it.text, unitCents = it.unitCents, qty = it.qty, vat = it.vat.api) })
        return r.copy(id = id)
    }

    private fun applyTx(r: Receipt, res: TxResult?, error: String?): Receipt {
        val c = settings.current
        if (res == null) return r.copy(tseError = error ?: r.tseError, tssSerial = c.tssSerial, clientSerial = c.clientSerial)
        return r.copy(
            txNumber = res.number, timeStart = res.timeStart, timeEnd = res.timeEnd,
            signatureCounter = res.signatureCounter, signature = res.signature, qrCodeData = res.qrCodeData,
            tssSerial = res.tssSerial ?: c.tssSerial, clientSerial = res.clientSerial ?: c.clientSerial,
            tseError = error,
        )
    }

    private fun tseErrorText(e: Exception): String = when (e) {
        is FiskalyException -> "TSE-Fehler: ${e.message}"
        is java.io.IOException -> "TSE nicht erreichbar"
        else -> "TSE-Fehler: ${e.message ?: e.javaClass.simpleName}"
    }
}
