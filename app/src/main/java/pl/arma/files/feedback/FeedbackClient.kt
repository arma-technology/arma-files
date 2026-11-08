package pl.arma.files.feedback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

// Wysyłka zgłoszenia do serwera pośredniczącego (feedback-server/), który
// zakłada zgłoszenie na GitHubie. Token GitHuba jest tylko na serwerze —
// w aplikacji, której kod jest publiczny, nie da się go ukryć.
class FeedbackClient(
    private val endpoint: String,
    private val clientVersion: String,
    private val http: OkHttpClient = defaultClient
) {
    suspend fun send(report: FeedbackReport): FeedbackResult = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(endpoint)
            .header("X-Arma-Client", "arma-files/$clientVersion")
            .post(report.toJson().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            http.newCall(request).execute().use { res ->
                parseFeedbackResponse(
                    status = res.code,
                    errorHeader = res.header("X-Error-Code"),
                    retryAfter = res.header("Retry-After"),
                    body = res.body?.string().orEmpty().take(8192)
                )
            }
        } catch (_: IOException) {
            FeedbackResult.Unavailable
        } catch (_: IllegalArgumentException) {
            // Zły adres w konfiguracji kompilacji.
            FeedbackResult.Unavailable
        }
    }

    companion object {
        private val defaultClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .callTimeout(45, TimeUnit.SECONDS)
                .build()
        }
    }
}
