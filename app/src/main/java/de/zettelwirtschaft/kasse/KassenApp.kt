package de.zettelwirtschaft.kasse

import android.app.Application
import de.zettelwirtschaft.kasse.data.Kasse
import de.zettelwirtschaft.kasse.data.KasseDb
import de.zettelwirtschaft.kasse.data.Product
import de.zettelwirtschaft.kasse.data.Settings
import de.zettelwirtschaft.kasse.fiscal.Vat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class KassenApp : Application() {
    lateinit var db: KasseDb
    lateinit var settings: Settings
    lateinit var kasse: Kasse
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        db = KasseDb.create(this)
        settings = Settings(this)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"
        kasse = Kasse(db.dao(), settings, version ?: "?")
        scope.launch {
            val dao = db.dao()
            if (dao.productCount() == 0) {
                // Beispielkarte, in den Einstellungen änderbar
                listOf(
                    Product(name = "Pils 0,3", priceCents = 350, sort = 1),
                    Product(name = "Pils 0,5", priceCents = 480, sort = 2),
                    Product(name = "Weizen 0,5", priceCents = 520, sort = 3),
                    Product(name = "Radler 0,5", priceCents = 480, sort = 4),
                    Product(name = "Cola 0,3", priceCents = 300, sort = 5),
                    Product(name = "Wasser 0,3", priceCents = 250, sort = 6),
                    Product(name = "Schnaps 2 cl", priceCents = 300, sort = 7),
                    Product(name = "Brezel", priceCents = 250, vat = Vat.REDUCED.api, sort = 8),
                ).forEach { dao.upsertProduct(it) }
            }
            kasse.abortStalePending()
        }
    }
}
