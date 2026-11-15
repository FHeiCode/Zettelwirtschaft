package de.tresenkasse.app.fiscal

import de.tresenkasse.app.data.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.UUID

/** Antwort der TSE auf eine abgeschlossene Transaktion. */
data class TxResult(
    val number: Long,
    val timeStart: Long,
    val timeEnd: Long?,
    val signatureCounter: Long?,
    val signature: String?,
    val qrCodeData: String?,
    val tssSerial: String?,
    val clientSerial: String?,
)

/**
 * fiskaly SIGN DE (Cloud-TSE nach KassenSichV), API v2.
 * Ablauf und Felder nach https://workspace.fiskaly.com/countries/germany/getting-started/new-customers
 */
class SignDe(private val settings: Settings) {
    private val http = FiskalyHttp(
        "https://kassensichv-middleware.fiskaly.com/api/v2",
        { settings.current.apiKey to settings.current.apiSecret },
        { settings.current.timeoutMs },
    )

    /**
     * Richtet TSE und Kassen-Client ein. Kann nach einem Abbruch einfach erneut aufgerufen werden
     * und macht dort weiter, wo es aufgehört hat.
     */
    suspend fun setup(progress: (String) -> Unit) {
        var c = settings.current
        if (c.tssId.isBlank()) {
            progress("TSE wird angelegt …")
            val id = UUID.randomUUID().toString()
            val r = http.call("PUT", "/tss/$id", JSONObject().put("description", "Tresenkasse"), long = true)
            settings.update { it.copy(tssId = id, adminPuk = r.optString("admin_puk"), adminPin = "", clientId = "", clientSerial = "", cashRegisterRegistered = false) }
            c = settings.current
        }
        val tssPath = "/tss/${c.tssId}"
        var tss = http.call("GET", tssPath, long = true)
        if (tss.optString("state") == "CREATED") {
            progress("TSE wird bereitgestellt …")
            tss = http.call("PATCH", tssPath, JSONObject().put("state", "UNINITIALIZED"), long = true)
        }
        if (tss.optString("state") == "UNINITIALIZED") {
            if (c.adminPin.isBlank()) {
                progress("Admin-PIN wird gesetzt …")
                val pin = randomPin()
                http.call("PATCH", "$tssPath/admin", JSONObject().put("admin_puk", c.adminPuk).put("new_admin_pin", pin), long = true)
                settings.update { it.copy(adminPin = pin) }
                c = settings.current
            }
            progress("TSE wird initialisiert …")
            adminAuth(c.tssId, c.adminPin)
            tss = http.call("PATCH", tssPath, JSONObject().put("state", "INITIALIZED"), long = true)
        }
        when (val state = tss.optString("state")) {
            "INITIALIZED" -> {}
            else -> throw FiskalyException(0, null, "TSE ist im Zustand $state und kann nicht signieren")
        }
        settings.update { it.copy(tssSerial = tss.optString("serial_number", it.tssSerial)) }

        if (c.clientId.isBlank()) {
            progress("Kasse wird an der TSE angemeldet …")
            adminAuth(c.tssId, c.adminPin)
            val cid = UUID.randomUUID().toString()
            val serial = "TRESEN-" + cid.take(8).uppercase()
            val r = http.call("PUT", "$tssPath/client/$cid", JSONObject().put("serial_number", serial), long = true)
            settings.update { it.copy(clientId = cid, clientSerial = r.optString("serial_number", serial)) }
        }
        runCatching { http.call("POST", "$tssPath/admin/logout", long = true) }
    }

    private suspend fun adminAuth(tssId: String, pin: String) {
        http.call("POST", "/tss/$tssId/admin/auth", JSONObject().put("admin_pin", pin), long = true)
    }

    /** Transaktion starten (Revision 1, ohne Daten). */
    suspend fun start(txId: String): TxResult {
        val c = settings.current
        val r = http.call("PUT", "/tss/${c.tssId}/tx/$txId?tx_revision=1",
            JSONObject().put("state", "ACTIVE").put("client_id", c.clientId))
        return parse(r)
    }

    /** Transaktion mit Daten abschließen. */
    suspend fun finish(txId: String, schema: JSONObject, revision: Int = 2): TxResult {
        val c = settings.current
        val r = http.call("PUT", "/tss/${c.tssId}/tx/$txId?tx_revision=$revision",
            JSONObject().put("state", "FINISHED").put("client_id", c.clientId).put("schema", schema))
        return parse(r)
    }

    companion object {
        /** Kassenbeleg-V1: Verkauf, Storno (negative Beträge) oder Abbruch. */
        fun receiptSchema(receiptType: String, lines: List<Line>, payment: String?): JSONObject {
            val perVat = JSONArray()
            val amounts = amountsPerVat(lines)
            if (amounts.isEmpty()) {
                perVat.put(JSONObject().put("vat_rate", Vat.NORMAL.api).put("amount", "0.00"))
            } else for (a in amounts) {
                perVat.put(JSONObject().put("vat_rate", a.vat.api).put("amount", apiDecimal(a.grossCents)))
            }
            val receipt = JSONObject().put("receipt_type", receiptType).put("amounts_per_vat_rate", perVat)
            if (payment != null) {
                val total = lines.sumOf { it.totalCents }
                receipt.put("amounts_per_payment_type", JSONArray().put(
                    JSONObject().put("payment_type", payment).put("amount", apiDecimal(total))
                ))
            }
            return JSONObject().put("standard_v1", JSONObject().put("receipt", receipt))
        }

        /** Bestellung-V1: Posten, die auf einen Deckel gehen (Storno = negative Menge). */
        fun orderSchema(lines: List<Line>): JSONObject {
            val items = JSONArray()
            for (l in lines) items.put(
                JSONObject().put("quantity", l.qty.toString()).put("text", l.text.take(255))
                    .put("price_per_unit", apiDecimal(l.unitCents))
            )
            return JSONObject().put("standard_v1", JSONObject().put("order", JSONObject().put("line_items", items)))
        }

        fun parse(r: JSONObject): TxResult {
            val sig = r.optJSONObject("signature")
            return TxResult(
                number = r.optLong("number"),
                timeStart = r.optLong("time_start"),
                timeEnd = if (r.has("time_end") && !r.isNull("time_end")) r.optLong("time_end") else null,
                // counter kommt laut fiskaly mal als Zahl, mal als Text
                signatureCounter = sig?.opt("counter")?.toString()?.toLongOrNull(),
                signature = sig?.optString("value")?.takeIf { it.isNotBlank() },
                qrCodeData = r.optString("qr_code_data").takeIf { it.isNotBlank() },
                tssSerial = r.optString("tss_serial_number").takeIf { it.isNotBlank() },
                clientSerial = r.optString("client_serial_number").takeIf { it.isNotBlank() },
            )
        }

        private fun randomPin(): String {
            val rnd = SecureRandom()
            return (1..10).map { rnd.nextInt(10) }.joinToString("")
        }
    }
}
