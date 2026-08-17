package pl.arma.files.net

import android.content.Context
import android.content.Intent
import android.net.Uri
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

/** Wynik logowania: token odświeżania i adres konta do pokazania na liście. */
data class GoogleSession(val refreshToken: String, val email: String)

/**
 * Logowanie do Google przez przeglądarkę systemową (Custom Tabs) z PKCE.
 *
 * Aplikacja mobilna jest klientem publicznym — nie ma sekretu klienta, więc
 * zabezpieczeniem wymiany kodu na token jest PKCE. Zgodnie z RFC 8252 logowanie
 * odbywa się w przeglądarce, a nie w WebView wewnątrz aplikacji: dzięki temu
 * hasło nigdy nie przechodzi przez naszą apkę, a użytkownik widzi prawdziwy
 * adres Google.
 */
class GoogleSignIn(private val clientId: String, private val redirectScheme: String) {

    val available: Boolean get() = clientId.isNotBlank()

    private val serviceConfig = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
        Uri.parse("https://oauth2.googleapis.com/token")
    )

    fun authorizationIntent(context: Context): Intent {
        val request = AuthorizationRequest.Builder(
            serviceConfig,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse("$redirectScheme:/oauth2redirect")
        )
            .setScopes(DRIVE_SCOPE, EMAIL_SCOPE)
            // Bez tego Google nie wyda tokenu odświeżania, więc każda sesja
            // kończyłaby się po godzinie i wymagała ponownego logowania.
            .setAdditionalParameters(mapOf("access_type" to "offline", "prompt" to "consent"))
            .build()
        return AuthorizationService(context).getAuthorizationRequestIntent(request)
    }

    /** Zamienia odpowiedź z przeglądarki na token odświeżania. */
    suspend fun completeSignIn(context: Context, data: Intent): Result<GoogleSession> {
        val response = AuthorizationResponse.fromIntent(data)
        val failure = AuthorizationException.fromIntent(data)
        if (response == null) {
            return Result.failure(
                IllegalStateException(failure?.errorDescription ?: "Logowanie zostało przerwane.")
            )
        }

        val service = AuthorizationService(context)
        val tokenResponse = suspendCoroutine { continuation ->
            service.performTokenRequest(response.createTokenExchangeRequest()) { result, error ->
                continuation.resume(result to error)
            }
        }
        service.dispose()

        val (tokens, error) = tokenResponse
        val refresh = tokens?.refreshToken
        return when {
            refresh.isNullOrBlank() -> Result.failure(
                IllegalStateException(
                    error?.errorDescription
                        ?: "Google nie zwrócił tokenu odświeżania. Cofnij dostęp aplikacji na " +
                        "koncie Google i zaloguj się ponownie."
                )
            )
            else -> Result.success(
                GoogleSession(
                    refreshToken = refresh,
                    email = tokens.accessToken?.let(::emailFor).orEmpty()
                )
            )
        }
    }

    /** Adres konta służy tylko do rozróżnienia kont na liście połączeń. */
    private fun emailFor(accessToken: String): String = runCatching {
        val request = Request.Builder()
            .url("https://www.googleapis.com/oauth2/v3/userinfo")
            .header("Authorization", "Bearer $accessToken")
            .build()
        OkHttpClient().newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@runCatching ""
            JSONObject(response.body?.string().orEmpty()).optString("email")
        }
    }.getOrDefault("")

    private companion object {
        /**
         * Pełny dostęp do dysku. To zakres zastrzeżony — projekt w trybie
         * testowym wpuszcza tylko konta z listy testowej, a publiczny wymaga
         * weryfikacji Google.
         */
        const val DRIVE_SCOPE = "https://www.googleapis.com/auth/drive"
        const val EMAIL_SCOPE = "email"
    }
}
