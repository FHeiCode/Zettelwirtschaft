package de.zettelwirtschaft.kasse.data

import android.content.Context
import androidx.room.*
import de.zettelwirtschaft.kasse.fiscal.Line
import de.zettelwirtschaft.kasse.fiscal.Vat
import kotlinx.coroutines.flow.Flow

@Entity
data class Product(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val priceCents: Long,
    val vat: String = Vat.NORMAL.api,
    val sort: Int = 0,
    val active: Boolean = true,
)

/** Ein Deckel für einen Gast oder Tisch. */
@Entity
data class Tab(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val openedAt: Long,
    val closedAt: Long? = null,
    /** OPEN, PAID oder CANCELLED */
    val state: String = "OPEN",
)

/** Ein Posten auf einem Deckel. Storno eines Postens = gleicher Posten mit negativer Menge. */
@Entity(indices = [Index("tabId")])
data class TabLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val tabId: Long,
    val text: String,
    val unitCents: Long,
    val qty: Long,
    val vat: String,
    val createdAt: Long,
    /** TSE-Transaktion der Bestellung (Bestellung-V1). */
    val orderTxId: String,
    val orderTxNumber: Long? = null,
    val tseError: String? = null,
) {
    fun toLine() = Line(text, unitCents, qty, Vat.of(vat))
}

/**
 * Ein Beleg. kind: SALE (Verkauf), STORNO (Rückbuchung eines Verkaufs), ABORT (abgebrochen),
 * PENDING (Überweisung läuft, TSE-Transaktion offen).
 */
@Entity
data class Receipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Laufende Belegnummer der Kasse (für Anzeige und DSFinV-K). */
    val number: Long,
    val kind: String,
    /** CASH oder NON_CASH, bei ABORT leer. */
    val payment: String?,
    val totalCents: Long,
    val givenCents: Long? = null,
    val tabId: Long? = null,
    val tabName: String? = null,
    val stornoOfId: Long? = null,
    val stornoOfNumber: Long? = null,
    val stornoOfTxId: String? = null,
    val stornoedById: Long? = null,
    val createdAt: Long,
    // TSE-Daten (fiskaly SIGN DE)
    val txId: String,
    val txNumber: Long? = null,
    val timeStart: Long? = null,
    val timeEnd: Long? = null,
    val signatureCounter: Long? = null,
    val signature: String? = null,
    val qrCodeData: String? = null,
    val tssSerial: String? = null,
    val clientSerial: String? = null,
    val tseError: String? = null,
    /** Tagesabschluss, in dem der Beleg enthalten ist. */
    val closingId: String? = null,
    /** Bezahlt per SEPA-Lastschrift mit diesem Mandat. */
    val mandateId: Long? = null,
    val mandateRef: String? = null,
    /** Zeitpunkt, zu dem die Lastschrift in eine SEPA-Datei geschrieben wurde. */
    val debitExportedAt: Long? = null,
)

/** SEPA-Lastschriftmandat (CORE, wiederkehrend) eines Stammgasts. */
@Entity
data class Mandate(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val reference: String,
    val name: String,
    val iban: String,
    val bic: String = "",
    val signedAt: Long,
    /** Unterschrift als PNG im App-Speicher */
    val signatureFile: String,
    val active: Boolean = true,
)

@Entity(indices = [Index("receiptId")])
data class ReceiptLine(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val receiptId: Long,
    val text: String,
    val unitCents: Long,
    val qty: Long,
    val vat: String,
) {
    fun toLine() = Line(text, unitCents, qty, Vat.of(vat))
}

/** Tagesabschluss (Z-Bon), entspricht einem DSFinV-K-Kassenabschluss. */
@Entity
data class Closing(
    @PrimaryKey val id: String,
    val exportId: Long,
    val businessDate: String,
    val createdAt: Long,
    val receiptCount: Int,
    val totalCents: Long,
    val cashCents: Long,
    val nonCashCents: Long,
    val normalGrossCents: Long,
    val reducedGrossCents: Long,
    val stornoCount: Int,
    /** LOCAL (nicht übertragen), PENDING, COMPLETED, ERROR */
    val state: String,
    val error: String? = null,
)

data class ReceiptWithLines(
    @Embedded val receipt: Receipt,
    @Relation(parentColumn = "id", entityColumn = "receiptId") val lines: List<ReceiptLine>,
)

data class TabWithLines(
    @Embedded val tab: Tab,
    @Relation(parentColumn = "id", entityColumn = "tabId") val lines: List<TabLine>,
)

@Dao
interface KasseDao {
    // Produkte
    @Query("SELECT * FROM Product WHERE active = 1 ORDER BY sort, name")
    fun activeProducts(): Flow<List<Product>>

    @Query("SELECT * FROM Product ORDER BY sort, name")
    fun allProducts(): Flow<List<Product>>

    @Query("SELECT COUNT(*) FROM Product")
    suspend fun productCount(): Int

    @Upsert suspend fun upsertProduct(p: Product): Long
    @Delete suspend fun deleteProduct(p: Product)

    // Deckel
    @Transaction
    @Query("SELECT * FROM Tab WHERE state = 'OPEN' ORDER BY openedAt")
    fun openTabs(): Flow<List<TabWithLines>>

    @Transaction
    @Query("SELECT * FROM Tab WHERE id = :id")
    fun tab(id: Long): Flow<TabWithLines?>

    @Transaction
    @Query("SELECT * FROM Tab WHERE id = :id")
    suspend fun tabNow(id: Long): TabWithLines?

    @Insert suspend fun insertTab(t: Tab): Long
    @Update suspend fun updateTab(t: Tab)
    @Insert suspend fun insertTabLines(l: List<TabLine>)

    // Belege
    @Query("SELECT COALESCE(MAX(number), 0) FROM Receipt")
    suspend fun maxReceiptNumber(): Long

    @Insert suspend fun insertReceipt(r: Receipt): Long
    @Update suspend fun updateReceipt(r: Receipt)
    @Insert suspend fun insertReceiptLines(l: List<ReceiptLine>)

    @Transaction
    @Query("SELECT * FROM Receipt WHERE kind != 'PENDING' ORDER BY number DESC LIMIT 300")
    fun recentReceipts(): Flow<List<ReceiptWithLines>>

    @Transaction
    @Query("SELECT * FROM Receipt WHERE id = :id")
    suspend fun receipt(id: Long): ReceiptWithLines?

    @Query("SELECT * FROM Receipt WHERE kind = 'PENDING'")
    suspend fun pendingReceipts(): List<Receipt>

    @Transaction
    @Query("SELECT * FROM Receipt WHERE closingId IS NULL AND kind != 'PENDING' ORDER BY number")
    suspend fun unclosedReceipts(): List<ReceiptWithLines>

    @Transaction
    @Query("SELECT * FROM Receipt WHERE closingId IS NULL AND kind != 'PENDING' ORDER BY number")
    fun unclosedReceiptsFlow(): Flow<List<ReceiptWithLines>>

    @Query("UPDATE Receipt SET closingId = :closingId WHERE id IN (:ids)")
    suspend fun assignClosing(ids: List<Long>, closingId: String)

    // Lastschrift
    @Query("SELECT * FROM Mandate ORDER BY active DESC, name")
    fun mandates(): Flow<List<Mandate>>

    @Query("SELECT * FROM Mandate WHERE id = :id")
    suspend fun mandate(id: Long): Mandate?

    @Query("SELECT COUNT(*) FROM Mandate")
    suspend fun mandateCount(): Int

    @Insert suspend fun insertMandate(m: Mandate): Long
    @Update suspend fun updateMandate(m: Mandate)

    /** Offene Lastschriften: Verkäufe mit Mandat, nicht storniert, noch nicht in einer SEPA-Datei. */
    @Query("SELECT * FROM Receipt WHERE mandateId IS NOT NULL AND kind = 'SALE' AND stornoedById IS NULL AND debitExportedAt IS NULL ORDER BY number")
    fun openDebits(): Flow<List<Receipt>>

    @Query("SELECT * FROM Receipt WHERE mandateId IS NOT NULL AND kind = 'SALE' AND stornoedById IS NULL AND debitExportedAt IS NULL ORDER BY number")
    suspend fun openDebitsNow(): List<Receipt>

    @Query("UPDATE Receipt SET debitExportedAt = :at WHERE id IN (:ids)")
    suspend fun markDebitsExported(ids: List<Long>, at: Long)

    // Tagesabschlüsse
    @Query("SELECT * FROM Closing ORDER BY exportId DESC")
    fun closings(): Flow<List<Closing>>

    @Query("SELECT COALESCE(MAX(exportId), 0) FROM Closing")
    suspend fun maxClosingExportId(): Long

    @Query("SELECT * FROM Closing WHERE id = :id")
    suspend fun closing(id: String): Closing?

    @Insert suspend fun insertClosing(c: Closing)
    @Update suspend fun updateClosing(c: Closing)

    @Transaction
    @Query("SELECT * FROM Receipt WHERE closingId = :closingId ORDER BY number")
    suspend fun receiptsOfClosing(closingId: String): List<ReceiptWithLines>
}

@Database(
    entities = [Product::class, Tab::class, TabLine::class, Receipt::class, ReceiptLine::class, Closing::class, Mandate::class],
    version = 1,
    exportSchema = false,
)
abstract class KasseDb : RoomDatabase() {
    abstract fun dao(): KasseDao

    companion object {
        fun create(context: Context): KasseDb =
            Room.databaseBuilder(context, KasseDb::class.java, "zettelwirtschaft-kasse.db").build()
    }
}
