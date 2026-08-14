package pl.arma.files.ops

import android.net.Uri
import pl.arma.files.FileItem
import java.io.InputStream
import java.io.OutputStream

/**
 * Minimum, jakiego silnik operacji potrzebuje od warstwy plików.
 * Wydzielone z `FileRepository` po to, żeby w testach podstawić implementację
 * w pamięci i sprawdzić konflikty, pauzę czy anulowanie bez prawdziwego dysku.
 */
interface FileOps {
    suspend fun listChildren(parent: Uri): List<FileItem>
    suspend fun createFolder(parent: Uri, name: String): Uri?
    suspend fun createFile(parent: Uri, name: String, mime: String): Uri?
    suspend fun delete(item: FileItem): Boolean
    fun openInput(uri: Uri): InputStream
    fun openOutput(uri: Uri): OutputStream

    /**
     * Przeniesienie przez zmianę nazwy, bez przepisywania bajtów — możliwe tylko
     * wtedy, gdy źródło i cel leżą na tym samym nośniku. Zwraca null, gdy się nie
     * da; wtedy silnik kopiuje i kasuje źródło.
     */
    suspend fun fastMove(item: FileItem, targetParent: Uri, newName: String): Uri?
}
