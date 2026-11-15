package de.zettelwirtschaft.kasse

import de.zettelwirtschaft.kasse.data.Receipt
import de.zettelwirtschaft.kasse.data.ReceiptLine
import de.zettelwirtschaft.kasse.data.ReceiptWithLines
import de.zettelwirtschaft.kasse.fiscal.*
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class LogicTest {

    // org.json in der Android-Variante kennt kein getBigDecimal
    private fun dec(o: org.json.JSONObject, key: String) = java.math.BigDecimal(o.get(key).toString())


    @Test fun money() {
        assertEquals("12.50", apiDecimal(1250))
        assertEquals("-3.05", apiDecimal(-305))
        assertEquals("0.00", apiDecimal(0))
        assertEquals("1.234,50 €", euro(123450))
        assertEquals(350L, parseEuro("3,50"))
        assertEquals(350L, parseEuro("3.5"))
        assertEquals(300L, parseEuro("3"))
        assertNull(parseEuro("3,505"))
        assertNull(parseEuro("abc"))
        // 4,80 brutto bei 19 %: netto 4,03, Steuer 0,77
        assertEquals(403L, netOf(480, Vat.NORMAL))
        assertEquals(-403L, netOf(-480, Vat.NORMAL))
        assertEquals(234L, netOf(250, Vat.REDUCED))
    }

    @Test fun aggregateDropsCancelled() {
        val l = aggregate(listOf(
            Line("Pils", 350, 2, Vat.NORMAL), Line("Pils", 350, -1, Vat.NORMAL),
            Line("Cola", 300, 1, Vat.NORMAL), Line("Cola", 300, -1, Vat.NORMAL),
        ))
        assertEquals(listOf(Line("Pils", 350, 1, Vat.NORMAL)), l)
    }

    @Test fun giroCode() {
        assertTrue(GiroCode.isValidIban("DE89 3704 0044 0532 0130 00"))
        assertFalse(GiroCode.isValidIban("DE89370400440532013001"))
        val p = GiroCode.payload("Zur Linde", "DE89 3704 0044 0532 0130 00", "", 1250, "Beleg 7")
        assertEquals(listOf("BCD", "002", "1", "SCT", "", "Zur Linde", "DE89370400440532013000", "EUR12.50", "", "", "Beleg 7"), p.split("\n"))
    }

    @Test fun creditorId() {
        assertTrue(SepaDebit.isValidCreditorId("DE98ZZZ09999999999"))
        assertFalse(SepaDebit.isValidCreditorId("DE97ZZZ09999999999"))
    }

    @Test fun pain008() {
        val xml = SepaDebit.pain008(
            "MSG1", "2026-11-15T21:00:00", LocalDate.of(2026, 11, 18),
            Creditor("Zur Linde & Co", "DE89370400440532013000", "", "DE98ZZZ09999999999"),
            listOf(Debit("ZW-1", 1250, "TKABC", LocalDate.of(2026, 11, 1), "Max Müller", "DE89370400440532013000", "", "Beleg 1")),
        )
        assertTrue(xml.contains("urn:iso:std:iso:20022:tech:xsd:pain.008.001.08"))
        assertTrue(xml.contains("<CtrlSum>12.50</CtrlSum>"))
        assertTrue(xml.contains("<Nm>Zur Linde &amp; Co</Nm>"))
        assertTrue(xml.contains("<Nm>Max Müller</Nm>"))
        assertTrue(xml.contains("<Othr><Id>NOTPROVIDED</Id></Othr>"))
        assertTrue(xml.contains("<SeqTp>RCUR</SeqTp>"))
        assertTrue(xml.contains("<ReqdColltnDt>2026-11-18</ReqdColltnDt>"))
    }

    @Test fun receiptSchema() {
        val s = SignDe.receiptSchema("RECEIPT", listOf(Line("Pils", 350, 2, Vat.NORMAL), Line("Brezel", 250, 1, Vat.REDUCED)), "CASH")
        val r = s.getJSONObject("standard_v1").getJSONObject("receipt")
        assertEquals("RECEIPT", r.getString("receipt_type"))
        val v = r.getJSONArray("amounts_per_vat_rate")
        assertEquals("NORMAL", v.getJSONObject(0).getString("vat_rate"))
        assertEquals("7.00", v.getJSONObject(0).getString("amount"))
        assertEquals("REDUCED_1", v.getJSONObject(1).getString("vat_rate"))
        assertEquals("9.50", r.getJSONArray("amounts_per_payment_type").getJSONObject(0).getString("amount"))
        val o = SignDe.orderSchema(listOf(Line("Pils", 350, -1, Vat.NORMAL)))
            .getJSONObject("standard_v1").getJSONObject("order").getJSONArray("line_items").getJSONObject(0)
        assertEquals("-1", o.getString("quantity"))
        assertEquals("3.50", o.getString("price_per_unit"))
    }

    @Test fun closing() {
        fun rw(id: Long, kind: String, pay: String?, lines: List<ReceiptLine>, err: String? = null) = ReceiptWithLines(
            Receipt(id = id, number = id, kind = kind, payment = pay, totalCents = lines.sumOf { it.unitCents * it.qty },
                createdAt = 1_700_000_000_000, txId = "tx$id", txNumber = if (err == null) id else null, timeStart = 1_700_000_000,
                timeEnd = 1_700_000_001, tseError = err,
                stornoOfTxId = if (kind == "STORNO") "tx1" else null),
            lines,
        )
        val pils = ReceiptLine(receiptId = 0, text = "Pils", unitCents = 350, qty = 2, vat = "NORMAL")
        val brezel = ReceiptLine(receiptId = 0, text = "Brezel", unitCents = 250, qty = 1, vat = "REDUCED_1")
        val list = listOf(
            rw(1, "SALE", "CASH", listOf(pils, brezel)),
            rw(2, "SALE", "NON_CASH", listOf(pils)),
            rw(3, "STORNO", "CASH", listOf(pils.copy(qty = -2), brezel.copy(qty = -1))),
            rw(4, "ABORT", null, emptyList()),
            rw(5, "SALE", "CASH", listOf(pils), err = "TSE nicht erreichbar"),
        )
        val j = ClosingBuilder.build(3, "2026-11-15", "client", list, 1_700_000_100)
        val pay = j.getJSONObject("cash_statement").getJSONObject("payment")
        // bar: 9,50 − 9,50 + 7,00 = 7,00; unbar 7,00
        assertEquals(0, dec(pay, "cash_amount").compareTo(java.math.BigDecimal("7.00")))
        assertEquals(0, dec(pay, "full_amount").compareTo(java.math.BigDecimal("14.00")))
        val txs = j.getJSONArray("transactions")
        assertEquals(5, txs.length())
        assertTrue(txs.getJSONObject(2).getJSONObject("head").getBoolean("storno"))
        assertEquals("AVBelegabbruch", txs.getJSONObject(3).getJSONObject("head").getString("type"))
        assertTrue(txs.getJSONObject(4).getJSONObject("security").has("error_message"))
        val umsatz = j.getJSONObject("cash_statement").getJSONArray("business_cases").getJSONObject(0).getJSONArray("amounts_per_vat_id")
        var gross = java.math.BigDecimal.ZERO
        for (i in 0 until umsatz.length()) {
            val a = umsatz.getJSONObject(i)
            assertEquals(0, dec(a, "incl_vat").compareTo(dec(a, "excl_vat").add(dec(a, "vat"))))
            gross = gross.add(dec(a, "incl_vat"))
        }
        assertEquals(0, gross.compareTo(java.math.BigDecimal("14.00")))
    }
}
