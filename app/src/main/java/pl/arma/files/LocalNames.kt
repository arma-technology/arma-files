package pl.arma.files

import java.io.File
import java.io.IOException

/**
 * Nazwy, które zamieniają się w pliki w telefonie. Nazwę wpisaną przez
 * użytkownika sprawdza model widoku, ale nazwy z serwera (FTP, SFTP, SMB,
 * WebDAV) przychodzą prosto z listingu — „../../x” od złośliwego albo
 * zepsutego serwera wyszłoby poza folder docelowy.
 */
internal object LocalNames {
    fun isSafe(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it == '\u0000' }

    /** Plik [name] w [parent]; wyjątek, gdy nazwa wyprowadziłaby poza ten folder. */
    fun child(parent: File, name: String): File {
        if (!isSafe(name)) throw IOException(text(R.string.err_name_forbidden, name))
        return File(parent, name)
    }
}
