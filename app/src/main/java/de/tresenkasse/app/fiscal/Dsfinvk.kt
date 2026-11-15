package de.tresenkasse.app.fiscal

import de.tresenkasse.app.data.ReceiptWithLines
import de.tresenkasse.app.data.Settings
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * fiskaly DSFinV-K: Tagesabschlüsse übertragen und den Export für Betriebsprüfung/Steuerberater holen.
 * Felder nach https://workspace.fiskaly.com/api/dsfinvk/
 */
class Dsfinvk(private val settings: Settings) {
    private val http = FiskalyHttp(
        "https://dsfinvk.fiskaly.com/api/v1",
        { settings.current.apiKey to settings.current.apiSecret },
        { 30_000 },
    )

    suspend fun registerCashRegister(appVersion: String) {
        val c = settings.current
        val body = JSONObject()
            .put("cash_register_type", JSONObject().put("type", "MASTER").put("tss_id", c.tssId))
            .put("brand", "Tresenkasse")
            .put("model", "Android")
            .put("software", JSONObject().put("brand", "Tresenkasse").put("version", appVersion.take(50)))
            .put("base_currency_code", "EUR")
        http.call("PUT", "/cash_registers/${c.clientId}", body, long = true)
        settings.update { it.copy(cashRegisterRegistered = true) }
    }

    suspend fun insertClosing(closingId: String, body: JSONObject) {
        http.call("PUT", "/cash_point_closings/$closingId", body, long = true)
    }

    /** Liefert (Zustand, Fehlertext) des Abschlusses: PENDING, COMPLETED oder ERROR. */
    suspend fun closingState(closingId: String): Pair<String, String?> {
        val r = http.call("GET", "/cash_point_closings/$closingId", long = true)
        val err = r.optJSONObject("error")?.optString("message") ?: r.optString("error").takeIf { it.isNotBlank() }
        return r.optString("state", "PENDING") to err
    }

    /** Startet einen Export (ZIP) über einen Zeitraum, wartet darauf und lädt ihn herunter. */
    suspend fun export(fromSec: Long, toSec: Long, target: File, progress: (String) -> Unit): File {
        val id = UUID.randomUUID().toString()
        val body = JSONObject().put("start_date", fromSec).put("end_date", toSec)
            .put("client_id", settings.current.clientId).put("format", "zip")
        progress("Export wird angefordert …")
        http.call("PUT", "/exports/$id", body, long = true)
        val started = System.currentTimeMillis()
        while (true) {
            val r = http.call("GET", "/exports/$id", long = true)
            when (val s = r.optString("state")) {
                "COMPLETED" -> break
                "ERROR", "CANCELLED", "EXPIRED", "DELETED" ->
                    throw FiskalyException(0, null, "Export fehlgeschlagen ($s): " + (r.opt("error")?.toString() ?: ""))
                else -> progress("Export wird erstellt ($s) …")
            }
            if (System.currentTimeMillis() - started > 10 * 60_000) throw FiskalyException(0, null, "Export dauert zu lange, bitte später erneut versuchen")
            delay(4000)
        }
        progress("Export wird heruntergeladen …")
        return http.download("/exports/$id/download", target)
    }
}

/** Baut den Kassenabschluss (DSFinV-K) aus den Belegen. Reine Funktion, damit sie testbar ist. */
object ClosingBuilder {

    fun build(
        exportId: Long,
        businessDate: String,
        clientId: String,
        receipts: List<ReceiptWithLines>,
        nowSec: Long,
    ): JSONObject {
        val body = JSONObject()
            .put("client_id", clientId)
            .put("cash_point_closing_export_id", exportId)
        val head = JSONObject()
            .put("business_date", businessDate)
            .put("export_creation_date", nowSec)
        if (receipts.isEmpty()) {
            head.put("first_transaction_export_id", JSONObject.NULL)
                .put("last_transaction_export_id", JSONObject.NULL)
            return body.put("head", head)
        }
        head.put("first_transaction_export_id", receipts.first().receipt.number.toString())
            .put("last_transaction_export_id", receipts.last().receipt.number.toString())
        body.put("head", head)

        val txs = JSONArray()
        val umsatz = HashMap<Vat, Split>()
        var cash = 0L
        var nonCash = 0L
        for (rw in receipts) {
            val r = rw.receipt
            val lines = rw.lines.map { it.toLine() }
            val isAbort = r.kind == "ABORT"
            val isStorno = r.kind == "STORNO"
            val signed = r.tseError == null && r.txNumber != null

            val txHead = JSONObject()
                .put("tx_id", if (signed) r.txId else UUID.nameUUIDFromBytes((r.txId + ":unsigned").toByteArray()).toString())
                .put("transaction_export_id", r.number.toString())
                .put("closing_client_id", clientId)
                .put("type", if (isAbort) "AVBelegabbruch" else "Beleg")
                .put("storno", isStorno)
                .put("number", r.number)
                .put("timestamp_start", r.timeStart ?: (r.createdAt / 1000))
                .put("timestamp_end", r.timeEnd ?: r.timeStart ?: (r.createdAt / 1000))
                .put("user", JSONObject().put("user_export_id", "1").put("name", "Kasse"))
            if (isStorno && r.stornoOfTxId != null) {
                txHead.put("references", JSONArray().put(
                    JSONObject().put("type", "InterneTransaktion").put("tx_id", r.stornoOfTxId)
                ))
            }

            val data = JSONObject()
            val lineArr = JSONArray()
            val txSplit = HashMap<Vat, Split>()
            if (!isAbort) {
                lines.forEachIndexed { i, l ->
                    val s = Split.of(l.totalCents, l.vat)
                    txSplit.merge(l.vat, s, Split::plus)
                    umsatz.merge(l.vat, s, Split::plus)
                    lineArr.put(JSONObject()
                        .put("lineitem_export_id", "${r.number}-${i + 1}")
                        .put("storno", isStorno)
                        .put("text", l.text.take(255))
                        .put("in_house", true)
                        .put("business_case", JSONObject()
                            .put("type", "Umsatz")
                            .put("amounts_per_vat_id", JSONArray().put(s.json(l.vat, withIncl = false))))
                        .put("item", JSONObject()
                            .put("number", l.text.take(50))
                            .put("quantity", l.qty)
                            .put("price_per_unit", bigDecimal(l.unitCents))))
                }
            }
            val total = if (isAbort) 0L else lines.sumOf { it.totalCents }
            val payments = JSONArray()
            if (!isAbort && r.payment != null) {
                val type = if (r.payment == "CASH") "Bar" else "Unbar"
                if (r.payment == "CASH") cash += total else nonCash += total
                payments.put(JSONObject().put("type", type).put("currency_code", "EUR").put("amount", bigDecimal(total)))
            }
            data.put("full_amount_incl_vat", bigDecimal(total))
                .put("payment_types", payments)
                .put("amounts_per_vat_id", JSONArray().apply {
                    txSplit.toSortedMap().forEach { (v, s) -> put(s.json(v)) }
                })
                .put("lines", lineArr)

            val security = JSONObject()
            if (signed) security.put("tss_tx_id", r.txId)
            else security.put("error_message", (r.tseError ?: "TSE nicht erreichbar").take(255))

            txs.put(JSONObject().put("head", txHead).put("data", data).put("security", security))
        }

        val cases = JSONArray()
        if (umsatz.isNotEmpty()) {
            cases.put(JSONObject().put("type", "Umsatz").put("amounts_per_vat_id", JSONArray().apply {
                umsatz.toSortedMap().forEach { (v, s) -> put(s.json(v)) }
            }))
        }
        val paymentTypes = JSONArray()
        if (cash != 0L || nonCash == 0L) paymentTypes.put(JSONObject().put("type", "Bar").put("currency_code", "EUR").put("amount", bigDecimal(cash)))
        if (nonCash != 0L) paymentTypes.put(JSONObject().put("type", "Unbar").put("currency_code", "EUR").put("amount", bigDecimal(nonCash)))
        val payment = JSONObject()
            .put("full_amount", bigDecimal(cash + nonCash))
            .put("cash_amount", bigDecimal(cash))
            .put("cash_amounts_by_currency", JSONArray().put(JSONObject().put("currency_code", "EUR").put("amount", bigDecimal(cash))))
            .put("payment_types", paymentTypes)
        body.put("cash_statement", JSONObject().put("business_cases", cases).put("payment", payment))
        body.put("transactions", txs)
        return body
    }

    /** Brutto/Netto/Steuer in Cent; Summen werden aus den Einzelposten gebildet, damit alles aufgeht. */
    data class Split(val gross: Long, val net: Long) {
        val tax get() = gross - net
        operator fun plus(o: Split) = Split(gross + o.gross, net + o.net)
        fun json(v: Vat, withIncl: Boolean = true): JSONObject = JSONObject()
            .put("vat_definition_export_id", v.dsfinvkId)
            .apply { if (withIncl) put("incl_vat", bigDecimal(gross)) }
            .put("excl_vat", bigDecimal(net))
            .put("vat", bigDecimal(tax))

        companion object {
            fun of(gross: Long, v: Vat) = Split(gross, netOf(gross, v))
        }
    }
}
