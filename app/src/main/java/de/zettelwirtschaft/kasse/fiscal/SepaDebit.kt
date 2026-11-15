package de.zettelwirtschaft.kasse.fiscal

import java.math.BigInteger
import java.time.LocalDate

/** Eine Lastschrift für die SEPA-Datei. */
data class Debit(
    val endToEndId: String,
    val cents: Long,
    val mandateRef: String,
    val mandateSignedOn: LocalDate,
    val debtorName: String,
    val debtorIban: String,
    val debtorBic: String,
    val remittance: String,
)

data class Creditor(val name: String, val iban: String, val bic: String, val creditorId: String)

/**
 * SEPA-Basislastschrift (CORE, wiederkehrend) als pain.008.001.08 – das Format, das deutsche Banken
 * im Online-Banking zum Hochladen annehmen.
 */
object SepaDebit {

    fun mandateText(creditor: Creditor, reference: String): String =
        "SEPA-Lastschriftmandat\n" +
            "Zahlungsempfänger: ${creditor.name}\n" +
            "Gläubiger-Identifikationsnummer: ${creditor.creditorId}\n" +
            "Mandatsreferenz: $reference\n\n" +
            "Ich ermächtige ${creditor.name}, Zahlungen von meinem Konto mittels Lastschrift einzuziehen. " +
            "Zugleich weise ich mein Kreditinstitut an, die von ${creditor.name} auf mein Konto gezogenen " +
            "Lastschriften einzulösen.\n" +
            "Hinweis: Ich kann innerhalb von acht Wochen, beginnend mit dem Belastungsdatum, die Erstattung des " +
            "belasteten Betrages verlangen. Es gelten dabei die mit meinem Kreditinstitut vereinbarten Bedingungen.\n" +
            "Die Vorabankündigung (Pre-Notification) erfolgt über den Kassenbeleg; die Frist dafür wird auf einen Tag verkürzt.\n" +
            "Es handelt sich um ein Mandat für wiederkehrende Zahlungen."

    /** Gläubiger-ID, z. B. DE98ZZZ09999999999 (Prüfziffer wie bei der IBAN, ohne Geschäftsbereichskennung). */
    fun isValidCreditorId(id: String): Boolean {
        val s = id.replace(" ", "").uppercase()
        if (!Regex("^[A-Z]{2}\\d{2}[A-Z0-9]{3}[A-Z0-9]{1,28}$").matches(s)) return false
        if (s.startsWith("DE") && s.length != 18) return false
        val national = s.substring(7)
        val check = s.substring(2, 4).toInt()
        val digits = (national + s.substring(0, 2) + "00").map { c -> if (c.isDigit()) "$c" else "${c - 'A' + 10}" }.joinToString("")
        val expected = 98 - BigInteger(digits).mod(BigInteger.valueOf(97)).toInt()
        return expected == check
    }

    fun pain008(
        messageId: String,
        createdAt: String,            // ISO, z. B. 2026-11-15T21:00:00
        collectionDate: LocalDate,
        creditor: Creditor,
        debits: List<Debit>,
    ): String {
        require(debits.isNotEmpty()) { "Keine Lastschriften" }
        val sum = apiDecimal(debits.sumOf { it.cents })
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""").append('\n')
        sb.append("""<Document xmlns="urn:iso:std:iso:20022:tech:xsd:pain.008.001.08" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance">""")
        sb.append("<CstmrDrctDbtInitn>")
        sb.append("<GrpHdr>")
        sb.tag("MsgId", id35(messageId)).tag("CreDtTm", createdAt).tag("NbOfTxs", debits.size.toString()).tag("CtrlSum", sum)
        sb.append("<InitgPty>").tag("Nm", text(creditor.name, 70)).append("</InitgPty>")
        sb.append("</GrpHdr>")
        sb.append("<PmtInf>")
        sb.tag("PmtInfId", id35("$messageId-1")).tag("PmtMtd", "DD").tag("BtchBookg", "true")
            .tag("NbOfTxs", debits.size.toString()).tag("CtrlSum", sum)
        sb.append("<PmtTpInf><SvcLvl><Cd>SEPA</Cd></SvcLvl><LclInstrm><Cd>CORE</Cd></LclInstrm><SeqTp>RCUR</SeqTp></PmtTpInf>")
        sb.tag("ReqdColltnDt", collectionDate.toString())
        sb.append("<Cdtr>").tag("Nm", text(creditor.name, 70)).append("</Cdtr>")
        sb.append("<CdtrAcct><Id>").tag("IBAN", GiroCode.normalizeIban(creditor.iban)).append("</Id></CdtrAcct>")
        sb.agent("CdtrAgt", creditor.bic)
        sb.tag("ChrgBr", "SLEV")
        sb.append("<CdtrSchmeId><Id><PrvtId><Othr>").tag("Id", creditor.creditorId.replace(" ", "").uppercase())
            .append("<SchmeNm><Prtry>SEPA</Prtry></SchmeNm></Othr></PrvtId></Id></CdtrSchmeId>")
        for (d in debits) {
            sb.append("<DrctDbtTxInf>")
            sb.append("<PmtId>").tag("EndToEndId", id35(d.endToEndId)).append("</PmtId>")
            sb.append("""<InstdAmt Ccy="EUR">""").append(apiDecimal(d.cents)).append("</InstdAmt>")
            sb.append("<DrctDbtTx><MndtRltdInf>").tag("MndtId", id35(d.mandateRef))
                .tag("DtOfSgntr", d.mandateSignedOn.toString()).append("</MndtRltdInf></DrctDbtTx>")
            sb.agent("DbtrAgt", d.debtorBic)
            sb.append("<Dbtr>").tag("Nm", text(d.debtorName, 70)).append("</Dbtr>")
            sb.append("<DbtrAcct><Id>").tag("IBAN", GiroCode.normalizeIban(d.debtorIban)).append("</Id></DbtrAcct>")
            sb.append("<RmtInf>").tag("Ustrd", text(d.remittance, 140)).append("</RmtInf>")
            sb.append("</DrctDbtTxInf>")
        }
        sb.append("</PmtInf></CstmrDrctDbtInitn></Document>\n")
        return sb.toString()
    }

    private fun StringBuilder.tag(name: String, value: String): StringBuilder =
        append('<').append(name).append('>').append(escape(value)).append("</").append(name).append('>')

    private fun StringBuilder.agent(name: String, bic: String): StringBuilder {
        val b = bic.replace(" ", "").uppercase()
        append('<').append(name).append("><FinInstnId>")
        if (b.isNotBlank()) tag("BICFI", b) else append("<Othr><Id>NOTPROVIDED</Id></Othr>")
        return append("</FinInstnId></").append(name).append('>')
    }

    /** Erlaubte Zeichen für Referenzen (SEPA-Zeichensatz ohne Leerzeichen am Anfang). */
    private fun id35(s: String) = s.replace(Regex("[^A-Za-z0-9+?/\\-:().,']"), "").take(35).ifBlank { "NOTPROVIDED" }

    /** Freitext: Zeilenumbrüche raus; deutsche Umlaute und ß nehmen deutsche Banken an. */
    private fun text(s: String, max: Int) =
        s.replace(Regex("[\\r\\n\\t]"), " ").replace(Regex("[^A-Za-z0-9ÄÖÜäöüß +?/\\-:().,'&]"), "").trim().take(max)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&apos;")
}
