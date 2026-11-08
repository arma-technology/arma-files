package pl.arma.files.feedback

import java.net.URLEncoder

// Zgłoszenie błędu albo pomysłu wysyłane z karty „O aplikacji”.
// Logika bez Androida, żeby dało się ją sprawdzić zwykłym testem JVM.

enum class FeedbackType(val wire: String, val githubLabel: String) {
    BUG("bug", "bug"),
    FEATURE("feature", "enhancement")
}

data class DeviceInfo(
    val appVersion: String,
    val android: String,
    val model: String,
    val language: String
) {
    fun lines(): List<String> = listOf(
        "App: $appVersion",
        "Android: $android",
        "Device: $model",
        "Language: $language"
    )

    companion object {
        // „motorola” + „motorola edge 40” → „motorola edge 40”, a nie
        // „motorola motorola edge 40”; „Google” + „Pixel 8” → „Google Pixel 8”.
        fun modelName(manufacturer: String, model: String): String {
            val m = model.trim()
            val maker = manufacturer.trim()
            return if (maker.isEmpty() || m.startsWith(maker, ignoreCase = true)) m else "$maker $m"
        }
    }
}

data class FeedbackReport(
    val type: FeedbackType,
    val title: String,
    val description: String,
    val email: String,
    val device: DeviceInfo?
) {
    val trimmedTitle get() = title.trim()
    val trimmedDescription get() = description.trim()
    val trimmedEmail get() = email.trim()

    // Te same granice co na serwerze (feedback-server/src/validate.js).
    val titleValid get() = trimmedTitle.length in TITLE_MIN..TITLE_MAX
    val descriptionValid get() = trimmedDescription.length in DESCRIPTION_MIN..DESCRIPTION_MAX
    val emailValid get() = trimmedEmail.isEmpty() || EMAIL_RE.matches(trimmedEmail)
    val isValid get() = titleValid && descriptionValid && emailValid

    fun toJson(): String = buildString {
        append('{')
        append("\"type\":").append(jsonString(type.wire))
        append(",\"title\":").append(jsonString(trimmedTitle))
        append(",\"description\":").append(jsonString(trimmedDescription))
        if (trimmedEmail.isNotEmpty()) append(",\"email\":").append(jsonString(trimmedEmail))
        device?.let {
            append(",\"device\":{")
            append("\"appVersion\":").append(jsonString(it.appVersion))
            append(",\"android\":").append(jsonString(it.android))
            append(",\"model\":").append(jsonString(it.model))
            append(",\"language\":").append(jsonString(it.language))
            append('}')
        }
        append('}')
    }

    // Formularz nowego zgłoszenia na GitHubie z wpisaną treścią — gdy serwer
    // zgłoszeń nie jest ustawiony albo nie odpowiada. E-mail tu NIE trafia:
    // zgłoszenie na GitHubie jest publiczne, a autora i tak widać z konta.
    fun githubNewIssueUrl(sourceUrl: String): String {
        val body = buildString {
            append(trimmedDescription)
            device?.let {
                append("\n\n---\n")
                append(it.lines().joinToString(" · "))
            }
        }
        return sourceUrl.trimEnd('/') + "/issues/new" +
            "?title=" + encode(trimmedTitle) +
            "&labels=" + encode(type.githubLabel) +
            "&body=" + encode(body.take(GITHUB_BODY_MAX))
    }

    companion object {
        const val TITLE_MIN = 3
        const val TITLE_MAX = 120
        const val DESCRIPTION_MIN = 10
        const val DESCRIPTION_MAX = 5000
        // Adres z całą treścią w parametrach nie może być dowolnie długi;
        // GitHub i przeglądarki obcinają bardzo długie adresy.
        private const val GITHUB_BODY_MAX = 4000
        private val EMAIL_RE = Regex("^[^\\s@<>()\",;:]+@[^\\s@<>()\",;:]+\\.[^\\s@<>()\",;:]{2,}$")

        private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    }
}

sealed interface FeedbackResult {
    data class Sent(val number: Int, val url: String) : FeedbackResult
    data class RateLimited(val retryAfterSec: Int?) : FeedbackResult
    data class Invalid(val field: String?) : FeedbackResult
    // Brak sieci, serwer nie odpowiada albo GitHub zawiódł — wtedy proponujemy
    // otwarcie zgłoszenia na GitHubie z tą samą treścią.
    data object Unavailable : FeedbackResult
}

// Odczyt odpowiedzi serwera. Kod błędu bierzemy najpierw z nagłówka, bo
// hosting potrafi podmienić ciało odpowiedzi 4xx na własną stronę HTML.
fun parseFeedbackResponse(status: Int, errorHeader: String?, retryAfter: String?, body: String): FeedbackResult {
    if (status == 201) {
        val number = Regex("\"number\"\\s*:\\s*(\\d+)").find(body)?.groupValues?.get(1)?.toIntOrNull()
        val url = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
        return if (number != null && url != null && url.startsWith("https://github.com/")) {
            FeedbackResult.Sent(number, url.replace("\\/", "/"))
        } else {
            // 201 bez numeru dostaje tylko bot z wypełnioną pułapką; aplikacja tu
            // nie trafi, ale nie udajemy sukcesu bez linku do zgłoszenia.
            FeedbackResult.Unavailable
        }
    }
    val code = errorHeader ?: Regex("\"error\"\\s*:\\s*\"([a-z_]+)\"").find(body)?.groupValues?.get(1)
    return when {
        status == 429 || code == "rate_limited" -> FeedbackResult.RateLimited(retryAfter?.trim()?.toIntOrNull())
        status == 400 && code == "invalid" ->
            FeedbackResult.Invalid(Regex("\"field\"\\s*:\\s*\"([a-z]+)\"").find(body)?.groupValues?.get(1))
        else -> FeedbackResult.Unavailable
    }
}

internal fun jsonString(s: String): String = buildString {
    append('"')
    for (c in s) {
        when (c) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c < ' ') append(String.format("\\u%04x", c.code)) else append(c)
        }
    }
    append('"')
}
