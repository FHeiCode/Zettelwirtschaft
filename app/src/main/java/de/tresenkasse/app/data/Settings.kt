package de.tresenkasse.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class Config(
    // Betrieb (Pflichtangaben auf dem Beleg)
    val businessName: String = "",
    val businessAddress: String = "",
    val taxNumber: String = "",
    // Überweisung (GiroCode)
    val accountHolder: String = "",
    val iban: String = "",
    val bic: String = "",
    val creditorId: String = "",
    // fiskaly
    val apiKey: String = "",
    val apiSecret: String = "",
    val tssId: String = "",
    val tssSerial: String = "",
    val clientId: String = "",
    val clientSerial: String = "",
    val adminPin: String = "",
    val adminPuk: String = "",
    val cashRegisterRegistered: Boolean = false,
    val timeoutMs: Int = 5000,
) {
    val hasCredentials get() = apiKey.isNotBlank() && apiSecret.isNotBlank()
    val tseReady get() = hasCredentials && tssId.isNotBlank() && clientId.isNotBlank()
    val businessComplete get() = businessName.isNotBlank() && businessAddress.isNotBlank()
    val giroReady get() = accountHolder.isNotBlank() && iban.isNotBlank()
    val debitReady get() = giroReady && creditorId.isNotBlank()
}

/** Einstellungen im privaten App-Speicher (nicht in Backups, siehe Manifest). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("tresenkasse", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    val config: StateFlow<Config> = _config
    val current: Config get() = _config.value

    private fun read() = Config(
        businessName = prefs.getString("businessName", "")!!,
        businessAddress = prefs.getString("businessAddress", "")!!,
        taxNumber = prefs.getString("taxNumber", "")!!,
        accountHolder = prefs.getString("accountHolder", "")!!,
        iban = prefs.getString("iban", "")!!,
        bic = prefs.getString("bic", "")!!,
        creditorId = prefs.getString("creditorId", "")!!,
        apiKey = prefs.getString("apiKey", "")!!,
        apiSecret = prefs.getString("apiSecret", "")!!,
        tssId = prefs.getString("tssId", "")!!,
        tssSerial = prefs.getString("tssSerial", "")!!,
        clientId = prefs.getString("clientId", "")!!,
        clientSerial = prefs.getString("clientSerial", "")!!,
        adminPin = prefs.getString("adminPin", "")!!,
        adminPuk = prefs.getString("adminPuk", "")!!,
        cashRegisterRegistered = prefs.getBoolean("cashRegisterRegistered", false),
        timeoutMs = prefs.getInt("timeoutMs", 5000),
    )

    @Synchronized
    fun update(change: (Config) -> Config) {
        val c = change(_config.value)
        prefs.edit()
            .putString("businessName", c.businessName)
            .putString("businessAddress", c.businessAddress)
            .putString("taxNumber", c.taxNumber)
            .putString("accountHolder", c.accountHolder)
            .putString("iban", c.iban)
            .putString("bic", c.bic)
            .putString("creditorId", c.creditorId)
            .putString("apiKey", c.apiKey)
            .putString("apiSecret", c.apiSecret)
            .putString("tssId", c.tssId)
            .putString("tssSerial", c.tssSerial)
            .putString("clientId", c.clientId)
            .putString("clientSerial", c.clientSerial)
            .putString("adminPin", c.adminPin)
            .putString("adminPuk", c.adminPuk)
            .putBoolean("cashRegisterRegistered", c.cashRegisterRegistered)
            .putInt("timeoutMs", c.timeoutMs)
            .commit()
        _config.value = c
    }
}
