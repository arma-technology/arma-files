package pl.arma.files.net

import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Wymiana tokenu odświeżania na token dostępu.
 *
 * Token dostępu żyje około godziny, więc trzymamy go w pamięci i odnawiamy
 * dopiero przed wygaśnięciem — inaczej każda operacja na plikach zaczynałaby
 * się od dodatkowego żądania do Google.
 *
 * Aplikacja mobilna jest klientem publicznym: nie ma sekretu klienta, a
 * bezpieczeństwo zapewnia PKCE przy logowaniu.
 */
class DriveTokens(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
    private val now: () -> Long = System::currentTimeMillis
) {
    private class Entry(val accessToken: String, val expiresAt: Long)

    private val cache = HashMap<String, Entry>()
    private val lock = Any()

    fun accessToken(accountId: String, clientId: String, refreshToken: String): String {
        synchronized(lock) {
            cache[accountId]?.let { entry ->
                if (entry.expiresAt > now() + SAFETY_MARGIN_MS) return entry.accessToken
            }
        }
        val fresh = refresh(clientId, refreshToken)
        synchronized(lock) { cache[accountId] = fresh }
        return fresh.accessToken
    }

    fun forget(accountId: String) {
        synchronized(lock) { cache.remove(accountId) }
    }

    private fun refresh(clientId: String, refreshToken: String): Entry {
        if (clientId.isBlank()) throw IOException("Google Drive: brak Client ID w tej wersji aplikacji.")
        if (refreshToken.isBlank()) throw IOException("Google Drive: zaloguj się ponownie.")

        val body = FormBody.Builder()
            .add("client_id", clientId)
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
            .build()
        val request = Request.Builder().url(TOKEN_ENDPOINT).post(body).build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Cofnięcie zgody albo wygaśnięcie tokenu w trybie testowym
                // wygląda tak samo — użytkownik musi zalogować się ponownie.
                val reason = runCatching { JSONObject(text).optString("error") }.getOrNull()
                throw IOException(
                    if (reason == "invalid_grant") {
                        "Google Drive: sesja wygasła, zaloguj się ponownie."
                    } else {
                        "Google Drive: nie udało się odnowić dostępu ($reason)."
                    }
                )
            }
            val json = JSONObject(text)
            val token = json.optString("access_token")
            if (token.isBlank()) throw IOException("Google Drive: odpowiedź bez tokenu dostępu.")
            val lifetime = json.optLong("expires_in", 3600L) * 1000L
            return Entry(token, now() + lifetime)
        }
    }

    private companion object {
        const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

        /** Odnawiamy minutę przed czasem, żeby żądanie nie trafiło w wygaśnięcie. */
        const val SAFETY_MARGIN_MS = 60_000L
    }
}
