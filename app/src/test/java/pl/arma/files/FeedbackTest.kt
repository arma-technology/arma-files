package pl.arma.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.arma.files.feedback.DeviceInfo
import pl.arma.files.feedback.FeedbackReport
import pl.arma.files.feedback.FeedbackResult
import pl.arma.files.feedback.FeedbackType
import pl.arma.files.feedback.parseFeedbackResponse
import java.net.URLDecoder

class FeedbackTest {
    private val device = DeviceInfo("0.11.0 (22)", "15 (SDK 35)", "motorola edge 40", "pl-PL")
    private val report = FeedbackReport(
        FeedbackType.BUG,
        "  Podgląd \"PDF\" się zamyka ",
        "Po otwarciu pliku\nz SMB wraca do listy.\t\\koniec",
        " tester@example.com ",
        device
    )

    @Test
    fun jsonIsEscapedAndTrimmed() {
        val json = report.toJson()
        assertEquals(
            "{\"type\":\"bug\",\"title\":\"Podgląd \\\"PDF\\\" się zamyka\"," +
                "\"description\":\"Po otwarciu pliku\\nz SMB wraca do listy.\\t\\\\koniec\"," +
                "\"email\":\"tester@example.com\"," +
                "\"device\":{\"appVersion\":\"0.11.0 (22)\",\"android\":\"15 (SDK 35)\"," +
                "\"model\":\"motorola edge 40\",\"language\":\"pl-PL\"}}",
            json
        )
    }

    @Test
    fun jsonOmitsEmptyEmailAndDevice() {
        val json = report.copy(email = "", device = null, type = FeedbackType.FEATURE).toJson()
        assertFalse(json.contains("email"))
        assertFalse(json.contains("device"))
        assertTrue(json.startsWith("{\"type\":\"feature\""))
    }

    @Test
    fun validationMatchesServerLimits() {
        assertTrue(report.isValid)
        assertFalse(report.copy(title = " ab ").titleValid)
        assertFalse(report.copy(title = "x".repeat(121)).titleValid)
        assertFalse(report.copy(description = "za mało").descriptionValid)
        assertFalse(report.copy(email = "nie-email").emailValid)
        assertTrue(report.copy(email = "  ").emailValid)
    }

    @Test
    fun githubUrlHasTextButNoEmail() {
        val url = report.githubNewIssueUrl("https://github.com/arma-technology/arma-files/")
        assertTrue(url.startsWith("https://github.com/arma-technology/arma-files/issues/new?title="))
        assertTrue(url.contains("&labels=bug&"))
        assertFalse(url.contains("+"))
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("Podgląd \"PDF\" się zamyka"))
        assertTrue(decoded.contains("Device: motorola edge 40"))
        assertFalse(decoded.contains("tester@example.com"))
        assertTrue(report.copy(type = FeedbackType.FEATURE).githubNewIssueUrl("https://x").contains("labels=enhancement"))
    }

    @Test
    fun modelNameAvoidsRepeatedManufacturer() {
        assertEquals("motorola edge 40", DeviceInfo.modelName("motorola", "motorola edge 40"))
        assertEquals("Google Pixel 8", DeviceInfo.modelName("Google", "Pixel 8"))
        assertEquals("SM-S911B", DeviceInfo.modelName("", "SM-S911B"))
    }

    @Test
    fun parsesServerResponses() {
        assertEquals(
            FeedbackResult.Sent(12, "https://github.com/arma-technology/arma-files/issues/12"),
            parseFeedbackResponse(201, null, null, "{\"ok\":true,\"number\":12,\"url\":\"https://github.com/arma-technology/arma-files/issues/12\"}")
        )
        // Hosting podmienił ciało na HTML — kod z nagłówka.
        assertEquals(FeedbackResult.RateLimited(3600), parseFeedbackResponse(429, "rate_limited", "3600", "<html>429</html>"))
        assertEquals(FeedbackResult.Invalid("email"), parseFeedbackResponse(400, "invalid", null, "{\"error\":\"invalid\",\"field\":\"email\"}"))
        assertEquals(FeedbackResult.Invalid(null), parseFeedbackResponse(400, "invalid", null, "<html>400</html>"))
        assertEquals(FeedbackResult.Unavailable, parseFeedbackResponse(502, "upstream", null, ""))
        assertEquals(FeedbackResult.Unavailable, parseFeedbackResponse(500, null, null, "<html>"))
        // Sukces bez numeru albo z obcym adresem nie udaje wysłanego zgłoszenia.
        assertEquals(FeedbackResult.Unavailable, parseFeedbackResponse(201, null, null, "{\"ok\":true}"))
        assertEquals(FeedbackResult.Unavailable, parseFeedbackResponse(201, null, null, "{\"number\":1,\"url\":\"https://evil.example/x\"}"))
    }
}
