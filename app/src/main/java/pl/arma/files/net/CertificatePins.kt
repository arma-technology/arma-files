package pl.arma.files.net

import okhttp3.internal.tls.OkHostnameVerifier
import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Zaufanie do certyfikatów serwerów FTPS.
 *
 * Certyfikat wystawiony przez publiczny urząd i pasujący do nazwy serwera
 * przechodzi zwykłą weryfikacją. Domowe NAS-y mają jednak certyfikaty
 * wystawione same sobie i adres IP zamiast nazwy — taki certyfikat
 * zapamiętujemy przy pierwszym połączeniu (jak klucz serwera w SSH),
 * a inny certyfikat pod tym samym adresem odrzucamy jako możliwe podszycie.
 */
class CertificatePins(private val file: File?) {

    private val memory = HashMap<String, String>()

    private fun load(): MutableMap<String, String> = synchronized(this) {
        if (file == null || !file.exists()) return memory
        file.readLines()
            .mapNotNull { line -> line.split(' ').takeIf { it.size == 2 }?.let { it[0] to it[1] } }
            .toMap(memory)
    }

    private fun save(key: String, fingerprint: String) = synchronized(this) {
        memory[key] = fingerprint
        file?.let { target ->
            target.parentFile?.mkdirs()
            target.writeText(memory.entries.joinToString("") { "${it.key} ${it.value}\n" })
        }
    }

    fun trustManager(host: String, port: Int): X509TrustManager {
        val system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
        val key = "$host:$port"
        return object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) =
                throw CertificateException("Nie dotyczy klienta.")

            override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) {
                val leaf = chain.firstOrNull() ?: throw CertificateException("Serwer nie przedstawił certyfikatu.")
                val publiclyTrusted = runCatching { system.checkServerTrusted(chain, authType) }.isSuccess &&
                    OkHostnameVerifier.verify(host, leaf)
                if (publiclyTrusted) return
                val fingerprint = fingerprintOf(leaf)
                when (val known = load()[key]) {
                    null -> save(key, fingerprint)
                    fingerprint -> Unit
                    else -> throw CertificateException(
                        "Certyfikat serwera $key się zmienił (był ${known.take(23)}…, jest ${fingerprint.take(23)}…). " +
                            "Jeśli serwer nie dostał nowego certyfikatu, ktoś może podszywać się pod niego."
                    )
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    }

    companion object {
        fun fingerprintOf(certificate: X509Certificate): String =
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                .joinToString(":") { "%02X".format(it) }
    }
}
