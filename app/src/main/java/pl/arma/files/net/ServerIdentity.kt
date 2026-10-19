package pl.arma.files.net

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Serwer przedstawił się innym kluczem SSH albo certyfikatem TLS niż przy
 * pierwszym połączeniu. Może to być reinstalacja NAS-a, ale też podszycie
 * się pod serwer — decyduje użytkownik, widząc oba odciski.
 */
class ServerIdentityChange(
    val kind: Kind,
    /** Adres w postaci, pod którą zapamiętano tożsamość, np. `[nas]:22`. */
    val server: String,
    val oldFingerprint: String?,
    val newFingerprint: String,
    /** Zapamiętuje nową tożsamość zamiast starej. */
    private val accept: () -> Unit
) {
    enum class Kind { SSH_KEY, TLS_CERTIFICATE }

    fun trust() = accept()
}

/** Wyjątek niosący [ServerIdentityChange] — SSH rzuca go z weryfikatora, TLS z menedżera zaufania. */
interface IdentityChangeCarrier {
    val change: ServerIdentityChange
}

/**
 * Ostatnia zmiana tożsamości czekająca na decyzję. Zgłaszają ją backendy
 * niezależnie od tego, kto łączył się z serwerem (przeglądanie, kopiowanie,
 * synchronizacja offline), a odbiera ekran, który pokazuje pytanie.
 */
object ServerIdentityAlerts {
    private val _pending = MutableStateFlow<ServerIdentityChange?>(null)
    val pending: StateFlow<ServerIdentityChange?> = _pending.asStateFlow()

    /** Szuka zmiany tożsamości w łańcuchu przyczyn i zgłasza ją. */
    fun reportIfPresent(error: Throwable) {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < 10) {
            if (current is IdentityChangeCarrier) {
                // Ta sama zmiana zgłoszona drugi raz (np. przez równoległe
                // zapytania) nie podmienia pytania, na które użytkownik patrzy.
                if (_pending.value?.server != current.change.server) _pending.value = current.change
                return
            }
            current = current.cause
        }
    }

    /** Czy błąd to zmiana tożsamości — wtedy zamiast komunikatu o błędzie jest pytanie. */
    fun isIdentityChange(error: Throwable): Boolean {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth++ < 10) {
            if (current is IdentityChangeCarrier) return true
            current = current.cause
        }
        return false
    }

    fun dismiss() {
        _pending.value = null
    }
}
