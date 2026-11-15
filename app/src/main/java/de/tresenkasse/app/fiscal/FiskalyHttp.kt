package de.tresenkasse.app.fiscal

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Fehlerantwort der fiskaly-API, z. B. {"status_code":400,"code":"E_...","message":"..."}. */
class FiskalyException(val status: Int, val code: String?, override val message: String) : IOException(message)

/**
 * Schlanker HTTP-Client für eine fiskaly-API (SIGN DE oder DSFinV-K).
 * Holt sich selbst ein Zugriffstoken und erneuert es kurz vor Ablauf.
 */
class FiskalyHttp(
    private val baseUrl: String,
    private val credentials: () -> Pair<String, String>,
    timeoutMs: () -> Int,
) {
    private val json = "application/json".toMediaType()
    private val fast = OkHttpClient.Builder()
        .callTimeout(timeoutMs().toLong().coerceIn(1500, 30_000), TimeUnit.MILLISECONDS)
        .build()
    // TSE anlegen/personalisieren und Exporte brauchen länger (fiskaly empfiehlt ≥ 30 s).
    private val slow = OkHttpClient.Builder().callTimeout(60, TimeUnit.SECONDS).build()

    private var token: String? = null
    private var tokenValidUntil = 0L
    private var tokenKey: String? = null

    private fun userAgent() = "Tresenkasse-Android"

    private fun ensureToken() {
        val (key, secret) = credentials()
        if (key.isBlank() || secret.isBlank()) throw FiskalyException(0, null, "Kein fiskaly-API-Key eingetragen")
        if (token != null && tokenKey == key && System.currentTimeMillis() < tokenValidUntil) return
        val body = JSONObject().put("api_key", key).put("api_secret", secret).toString()
        val req = Request.Builder().url("$baseUrl/auth").header("User-Agent", userAgent())
            .post(body.toRequestBody(json)).build()
        slow.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw parseError(resp.code, text)
            val o = JSONObject(text)
            token = o.getString("access_token")
            val expiresIn = o.optLong("access_token_expires_in", 3600)
            tokenValidUntil = System.currentTimeMillis() + (expiresIn - 300).coerceAtLeast(60) * 1000
            tokenKey = key
        }
    }

    suspend fun call(method: String, path: String, body: JSONObject? = null, long: Boolean = false): JSONObject =
        withContext(Dispatchers.IO) {
            ensureToken()
            val reqBody = body?.toString()?.toRequestBody(json)
                ?: if (method == "POST" || method == "PUT" || method == "PATCH") "".toRequestBody(json) else null
            val req = Request.Builder().url("$baseUrl$path")
                .header("Authorization", "Bearer $token")
                .header("User-Agent", userAgent())
                .method(method, reqBody)
                .build()
            (if (long) slow else fast).newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code == 401) token = null
                if (!resp.isSuccessful) throw parseError(resp.code, text)
                if (text.isBlank()) JSONObject() else runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            }
        }

    suspend fun download(path: String, target: File): File = withContext(Dispatchers.IO) {
        ensureToken()
        val req = Request.Builder().url("$baseUrl$path")
            .header("Authorization", "Bearer $token")
            .header("User-Agent", userAgent())
            .get().build()
        slow.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw parseError(resp.code, resp.body?.string().orEmpty())
            target.outputStream().use { out -> resp.body!!.byteStream().copyTo(out) }
        }
        target
    }

    private fun parseError(status: Int, text: String): FiskalyException {
        val o = runCatching { JSONObject(text) }.getOrNull()
        val code = o?.optString("code")?.takeIf { it.isNotBlank() }
        val msg = o?.optString("message")?.takeIf { it.isNotBlank() } ?: text.take(200).ifBlank { "HTTP $status" }
        return FiskalyException(status, code, if (code != null) "$msg ($code)" else msg)
    }
}
