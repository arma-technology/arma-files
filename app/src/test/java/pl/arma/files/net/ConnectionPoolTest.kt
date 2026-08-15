package pl.arma.files.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class ConnectionPoolTest {

    private class FakeConnection(val serial: Int) {
        var alive = true
        var closed = false
    }

    private val account = RemoteAccount(
        id = "nas",
        type = RemoteType.FTP,
        label = "NAS",
        host = "192.168.0.10",
        port = 21,
        username = "artur"
    )

    private var created = 0
    private var clock = 0L
    private val closedConnections = mutableListOf<FakeConnection>()

    private fun pool(maxPerAccount: Int = 3, idleMillis: Long = 30_000) = ConnectionPool(
        maxPerAccount = maxPerAccount,
        idleMillis = idleMillis,
        now = { clock },
        create = { FakeConnection(++created) },
        validate = { it.alive },
        destroy = { it.closed = true; closedConnections += it }
    )

    @Test
    fun `drugie uzycie siega po polaczenie z puli zamiast tworzyc nowe`() {
        val pool = pool()
        val first = pool.use(account) { it }
        val second = pool.use(account) { it }
        assertSame("polaczenie powinno zostac odzyskane z puli", first, second)
        assertEquals(1, created)
    }

    @Test
    fun `martwe polaczenie jest odrzucane i zastepowane nowym`() {
        val pool = pool()
        val first = pool.use(account) { it }
        first.alive = false
        val second = pool.use(account) { it }
        assertNotSame(first, second)
        assertEquals(2, created)
        assertEquals("nieaktualne polaczenie musi zostac zamkniete", true, first.closed)
    }

    @Test
    fun `polaczenie po bledzie nie wraca do puli`() {
        val pool = pool()
        val broken = runCatching {
            pool.use(account) { connection ->
                throw IllegalStateException("zerwana sesja: ${connection.serial}")
            }
        }
        assertThrows(IllegalStateException::class.java) { broken.getOrThrow() }
        assertEquals(0, pool.idleCount(account.id))
        assertEquals(1, closedConnections.size)
    }

    @Test
    fun `pula nie rosnie ponad ustalony limit`() {
        val pool = pool(maxPerAccount = 2)
        // Zagniezdzone uzycia wymuszaja trzy rownoczesne polaczenia.
        pool.use(account) { a ->
            pool.use(account) { b ->
                pool.use(account) { c ->
                    assertNotSame(a, b); assertNotSame(b, c)
                }
            }
        }
        assertEquals(2, pool.idleCount(account.id))
        assertEquals("nadmiarowe polaczenie zamykamy zamiast trzymac", 1, closedConnections.size)
    }

    @Test
    fun `polaczenia bezczynne dluzej niz limit sa zamykane`() {
        val pool = pool(idleMillis = 1_000)
        pool.use(account) { }
        assertEquals(1, pool.idleCount(account.id))
        clock += 1_500
        pool.evictIdle()
        assertEquals(0, pool.idleCount(account.id))
        assertEquals(1, closedConnections.size)
    }

    @Test
    fun `zamkniecie konta czysci jego polaczenia`() {
        val pool = pool()
        pool.use(account) { }
        pool.closeAccount(account.id)
        assertEquals(0, pool.idleCount(account.id))
        assertEquals(1, closedConnections.size)
    }
}
