package de.tresenkasse.app.fiscal

import de.tresenkasse.app.data.Config
import de.tresenkasse.app.data.ReceiptWithLines
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Belegtext mit den Pflichtangaben nach § 6 KassenSichV:
 * Betrieb, Datum/Uhrzeit (Start/Ende), Menge und Art, Transaktionsnummer, Entgelt und Steuer je Satz,
 * Seriennummern von Kasse und TSE, Signaturzähler und Prüfwert.
 */
object ReceiptText {
    private val zone = ZoneId.of("Europe/Berlin")
    private val dt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss").withZone(zone)

    fun time(sec: Long?): String = sec?.let { dt.format(Instant.ofEpochSecond(it)) } ?: "–"

    fun title(kind: String) = when (kind) {
        "STORNO" -> "STORNO-BELEG"
        "ABORT" -> "ABGEBROCHEN"
        else -> "BELEG"
    }

    fun build(rw: ReceiptWithLines, config: Config): String {
        val r = rw.receipt
        val lines = rw.lines.map { it.toLine() }
        val sb = StringBuilder()
        sb.appendLine(config.businessName)
        config.businessAddress.lines().filter { it.isNotBlank() }.forEach { sb.appendLine(it.trim()) }
        if (config.taxNumber.isNotBlank()) sb.appendLine("St.-Nr./USt-IdNr.: ${config.taxNumber}")
        sb.appendLine()
        sb.appendLine("${title(r.kind)} Nr. ${r.number}")
        if (r.stornoOfNumber != null) sb.appendLine("Storno zu Beleg Nr. ${r.stornoOfNumber}")
        if (r.tabName != null) sb.appendLine("Deckel: ${r.tabName}")
        sb.appendLine("Datum: ${time(r.timeEnd ?: (r.createdAt / 1000))}")
        sb.appendLine()
        for (l in lines) {
            sb.appendLine("${l.qty} x ${l.text}")
            sb.appendLine("   je ${euro(l.unitCents)}  =  ${euro(l.totalCents)}  (${l.vat.label})")
        }
        sb.appendLine("------------------------------")
        sb.appendLine("SUMME  ${euro(lines.sumOf { it.totalCents })}")
        for (a in amountsPerVat(lines)) {
            sb.appendLine("  ${a.vat.label} MwSt: ${euro(a.taxCents)}  (Netto ${euro(a.netCents)}, Brutto ${euro(a.grossCents)})")
        }
        when (r.payment) {
            "CASH" -> {
                sb.appendLine("Bezahlt: bar")
                r.givenCents?.let { g ->
                    sb.appendLine("Gegeben: ${euro(g)}")
                    sb.appendLine("Rückgeld: ${euro(g - r.totalCents)}")
                }
            }
            "NON_CASH" -> if (r.mandateRef != null) {
                sb.appendLine("Bezahlt: SEPA-Lastschrift")
                sb.appendLine("Mandatsreferenz: ${r.mandateRef}")
                sb.appendLine("Gläubiger-ID: ${config.creditorId}")
                if (r.kind == "SALE") sb.appendLine("Vorabankündigung: Der Betrag wird frühestens in 2 Bankarbeitstagen von deinem Konto abgebucht.")
                else sb.appendLine("Wird nicht abgebucht bzw. erstattet.")
            } else sb.appendLine("Bezahlt: Überweisung")
        }
        sb.appendLine()
        sb.appendLine("TSE")
        if (r.tseError != null) sb.appendLine("!! ${r.tseError} – Beleg ohne Signatur")
        sb.appendLine("Kassen-Seriennr.: ${r.clientSerial ?: "–"}")
        sb.appendLine("TSE-Seriennr.: ${r.tssSerial ?: "–"}")
        sb.appendLine("Transaktion: ${r.txNumber ?: "–"}")
        sb.appendLine("Start: ${time(r.timeStart)}")
        sb.appendLine("Ende: ${time(r.timeEnd)}")
        sb.appendLine("Signaturzähler: ${r.signatureCounter ?: "–"}")
        sb.appendLine("Signatur: ${r.signature ?: "–"}")
        sb.appendLine("Zeitformat: unixTime, Zeitzone Europe/Berlin")
        return sb.toString().trimEnd()
    }
}
