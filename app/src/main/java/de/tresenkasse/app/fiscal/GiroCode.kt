package de.tresenkasse.app.fiscal

import java.math.BigInteger

/**
 * GiroCode / EPC-QR-Code (EPC069-12, Version 002) für eine SEPA-Überweisung.
 * Banking-Apps lesen daraus Empfänger, IBAN, Betrag und Verwendungszweck.
 */
object GiroCode {

    fun payload(name: String, iban: String, bic: String, cents: Long, reference: String): String {
        require(cents in 1..99_999_999_999L) { "Betrag außerhalb des erlaubten Bereichs" }
        val lines = listOf(
            "BCD",
            "002",
            "1",                     // UTF-8
            "SCT",
            bic.trim().uppercase(),  // in Version 002 optional
            clean(name).take(70),
            normalizeIban(iban),
            "EUR" + apiDecimal(cents),
            "",                      // Purpose-Code
            "",                      // strukturierte Referenz
            clean(reference).take(140),
        )
        return lines.joinToString("\n")
    }

    fun normalizeIban(iban: String): String = iban.replace(" ", "").uppercase()

    /** Prüfziffer nach ISO 13616 (Modulo 97). */
    fun isValidIban(iban: String): Boolean {
        val s = normalizeIban(iban)
        if (!Regex("^[A-Z]{2}\\d{2}[A-Z0-9]{11,30}$").matches(s)) return false
        if (s.startsWith("DE") && s.length != 22) return false
        val rearranged = s.drop(4) + s.take(4)
        val digits = buildString {
            for (c in rearranged) if (c.isDigit()) append(c) else append(c - 'A' + 10)
        }
        return BigInteger(digits).mod(BigInteger.valueOf(97)) == BigInteger.ONE
    }

    private fun clean(s: String) = s.replace("\n", " ").replace("\r", " ").trim()
}
