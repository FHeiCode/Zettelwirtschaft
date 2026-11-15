package de.tresenkasse.app.fiscal

import java.math.BigDecimal
import java.math.RoundingMode

/** Steuersätze, wie fiskaly SIGN DE sie erwartet, mit DSFinV-K-Schlüssel. */
enum class Vat(val api: String, val percent: Int, val dsfinvkId: Int, val label: String) {
    NORMAL("NORMAL", 19, 1, "19 %"),
    REDUCED("REDUCED_1", 7, 2, "7 %");

    companion object {
        fun of(api: String): Vat = entries.firstOrNull { it.api == api || it.name == api } ?: NORMAL
    }
}

/** Ein Posten (Getränk, Speise) mit Menge. Beträge immer in Cent. */
data class Line(val text: String, val unitCents: Long, val qty: Long, val vat: Vat) {
    val totalCents: Long get() = unitCents * qty
}

/** Brutto, Netto und Steuer für einen Steuersatz. */
data class VatAmount(val vat: Vat, val grossCents: Long) {
    val netCents: Long get() = netOf(grossCents, vat)
    val taxCents: Long get() = grossCents - netCents
}

/** Netto aus Brutto, kaufmännisch gerundet (auch für negative Beträge symmetrisch). */
fun netOf(grossCents: Long, vat: Vat): Long {
    val net = BigDecimal(grossCents).multiply(BigDecimal(100))
        .divide(BigDecimal(100 + vat.percent), 0, RoundingMode.HALF_UP)
    return net.toLong()
}

fun amountsPerVat(lines: List<Line>): List<VatAmount> =
    lines.groupBy { it.vat }
        .map { (vat, l) -> VatAmount(vat, l.sumOf { it.totalCents }) }
        .filter { it.grossCents != 0L }
        .sortedBy { it.vat.ordinal }

/** Gleiche Posten zusammenfassen und Posten mit Menge 0 weglassen (z. B. nach Storno auf dem Deckel). */
fun aggregate(lines: List<Line>): List<Line> =
    lines.groupBy { Triple(it.text, it.unitCents, it.vat) }
        .map { (k, l) -> Line(k.first, k.second, l.sumOf { it.qty }, k.third) }
        .filter { it.qty != 0L }

/** "12.50" bzw. "-3.00" für die APIs. */
fun apiDecimal(cents: Long): String {
    val sign = if (cents < 0) "-" else ""
    val a = kotlin.math.abs(cents)
    return "%s%d.%02d".format(java.util.Locale.ROOT, sign, a / 100, a % 100)
}

fun bigDecimal(cents: Long): BigDecimal = BigDecimal(cents).movePointLeft(2)

/** "12,50 €" für die Anzeige. */
fun euro(cents: Long): String {
    val sign = if (cents < 0) "−" else ""
    val a = kotlin.math.abs(cents)
    val euros = a / 100
    val grouped = euros.toString().reversed().chunked(3).joinToString(".").reversed()
    return "%s%s,%02d €".format(java.util.Locale.ROOT, sign, grouped, a % 100)
}

/** Eingabe wie "3,50", "3.5" oder "3" in Cent; null bei ungültiger Eingabe. */
fun parseEuro(input: String): Long? {
    val s = input.trim().replace("€", "").replace(" ", "").replace(',', '.')
    if (!Regex("^\\d{1,6}(\\.\\d{1,2})?$").matches(s)) return null
    return BigDecimal(s).movePointRight(2).setScale(0, RoundingMode.UNNECESSARY).toLong()
}
