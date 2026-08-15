package pl.arma.files.net

/**
 * Pula połączeń per konto.
 *
 * Wcześniej każda operacja FTP zestawiała nowe połączenie — logowanie,
 * operacja, wylogowanie — więc katalog ze zdjęciami oznaczał tyle logowań,
 * ile miniatur. Tu połączenia są wypożyczane i zwracane.
 *
 * Klienci FTP i SFTP nie znoszą równoległego użycia w obrębie jednej sesji,
 * dlatego pula nie współdzieli jednego połączenia: wypożyczone znika z listy
 * dostępnych, a gdy zabraknie wolnych, powstaje nowe — do [maxPerAccount].
 */
class ConnectionPool<T>(
    private val maxPerAccount: Int = 3,
    private val idleMillis: Long = 30_000,
    private val now: () -> Long = System::currentTimeMillis,
    private val create: (RemoteAccount) -> T,
    private val validate: (T) -> Boolean,
    private val destroy: (T) -> Unit
) {
    private class Pooled<T>(val value: T, var idleSince: Long)

    private val idle = HashMap<String, ArrayDeque<Pooled<T>>>()
    private val borrowed = HashMap<String, Int>()
    private val lock = Any()

    fun <R> use(account: RemoteAccount, block: (T) -> R): R {
        val connection = borrow(account)
        var broken = false
        try {
            return block(connection)
        } catch (error: Throwable) {
            // Po błędzie nie wiadomo, w jakim stanie jest sesja — bezpieczniej
            // ją zamknąć niż oddać do puli i zepsuć następną operację.
            broken = true
            throw error
        } finally {
            release(account.id, connection, broken)
        }
    }

    private fun borrow(account: RemoteAccount): T {
        evictIdle()
        while (true) {
            val candidate = synchronized(lock) {
                val queue = idle[account.id]
                val pooled = queue?.removeLastOrNull()
                if (pooled != null) borrowed[account.id] = (borrowed[account.id] ?: 0) + 1
                pooled
            } ?: break
            if (validate(candidate.value)) return candidate.value
            synchronized(lock) { borrowed[account.id] = (borrowed[account.id] ?: 1) - 1 }
            runCatching { destroy(candidate.value) }
        }
        synchronized(lock) { borrowed[account.id] = (borrowed[account.id] ?: 0) + 1 }
        return runCatching { create(account) }.getOrElse { error ->
            synchronized(lock) { borrowed[account.id] = (borrowed[account.id] ?: 1) - 1 }
            throw error
        }
    }

    private fun release(accountId: String, connection: T, broken: Boolean) {
        val keep = synchronized(lock) {
            borrowed[accountId] = (borrowed[accountId] ?: 1) - 1
            val queue = idle.getOrPut(accountId) { ArrayDeque() }
            if (broken || queue.size >= maxPerAccount) {
                false
            } else {
                queue.addLast(Pooled(connection, now()))
                true
            }
        }
        if (!keep) runCatching { destroy(connection) }
    }

    fun evictIdle() {
        val deadline = now() - idleMillis
        val stale = synchronized(lock) {
            val collected = mutableListOf<T>()
            idle.values.forEach { queue ->
                while (queue.isNotEmpty() && queue.first().idleSince < deadline) {
                    collected += queue.removeFirst().value
                }
            }
            collected
        }
        stale.forEach { runCatching { destroy(it) } }
    }

    /** Zamyka wszystko dla jednego konta — po zmianie hasła albo usunięciu. */
    fun closeAccount(accountId: String) {
        val stale = synchronized(lock) {
            idle.remove(accountId)?.map { it.value }.orEmpty()
        }
        stale.forEach { runCatching { destroy(it) } }
    }

    fun closeAll() {
        val stale = synchronized(lock) {
            val all = idle.values.flatten().map { it.value }
            idle.clear()
            all
        }
        stale.forEach { runCatching { destroy(it) } }
    }

    /** Liczba wolnych połączeń — na potrzeby testów. */
    fun idleCount(accountId: String): Int = synchronized(lock) { idle[accountId]?.size ?: 0 }
}
