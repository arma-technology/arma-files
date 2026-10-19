package pl.arma.files.net

import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Backendy na prawdziwych serwerach. Uruchamiane ręcznie, gdy serwery stoją:
 * ARMA_NET_TEST=1 ./gradlew :app:testDebugUnitTest --tests '*LiveServersTest'
 * Konta i porty — patrz [accounts]; każde można wyłączyć, pomijając zmienną.
 */
class LiveServersTest {

    private fun env(name: String) = System.getenv(name)

    private val accounts: List<Pair<RemoteBackend, RemoteAccount>>
        get() = buildList {
            fun account(type: RemoteType, port: Int, root: String = "", share: String = "", secure: Boolean = false) =
                RemoteAccount(
                    id = "t" + type.name.lowercase() + port, type = type, label = type.name, host = "127.0.0.1",
                    port = port, username = "test", password = "haslo", rootPath = root, share = share, secure = secure
                )
            env("ARMA_FTP")?.let { add(FtpBackend() to account(RemoteType.FTP, it.toInt(), root = env("ARMA_FTP_ROOT") ?: "")) }
            env("ARMA_FTPS")?.let { add(FtpBackend() to account(RemoteType.FTP, it.toInt(), secure = true)) }
            env("ARMA_SFTP")?.let { add(SftpBackend() to account(RemoteType.SFTP, it.toInt(), root = env("ARMA_SFTP_ROOT") ?: "")) }
            env("ARMA_SMB")?.let { add(SmbBackend() to account(RemoteType.SMB, it.toInt(), share = "dane")) }
            env("ARMA_DAV")?.let { add(WebDavBackend() to account(RemoteType.WEBDAV, it.toInt())) }
        }

    private fun time(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    /** Konto SMB bez udziału: korzeń to lista udziałów, a pierwszy człon ścieżki — udział. */
    @Test
    fun udzialySmbBezNazwyUdzialu() {
        assumeTrue(env("ARMA_NET_TEST") == "1")
        // Serwer z udziałem i podkatalogiem; domyślnie kontener testowy.
        val host = env("ARMA_SMB_SHARES_HOST") ?: "127.0.0.1"
        val port = env("ARMA_SMB_SHARES_PORT")?.toInt() ?: env("ARMA_SMB")?.toInt() ?: return
        val account = RemoteAccount(
            id = "shares", type = RemoteType.SMB, label = "SMB", host = host, port = port,
            username = env("ARMA_SMB_SHARES_USER") ?: "test", password = env("ARMA_SMB_SHARES_PASS") ?: "haslo", share = ""
        )
        val expected = env("ARMA_SMB_SHARES_EXPECT") ?: "dane"
        val backend = SmbBackend()
        val shares = backend.list(account, "/")
        println("  udziały: ${shares.map { it.name }}")
        assert(shares.any { it.name == expected && it.isDirectory }) { shares.toString() }
        assert(shares.none { it.name.endsWith("$") })
        val inside = backend.list(account, "/$expected")
        println("  w $expected: ${inside.take(5).map { it.name }}")
        assert(inside.isNotEmpty())
        val folder = inside.first { it.isDirectory }
        assert(backend.info(account, "/$expected/${folder.name}")?.isDirectory == true)
    }

    /** Powiadomienie SMB przychodzi, gdy ktoś inny doda plik do obserwowanego folderu. */
    @Test
    fun obserwacjaSmb() {
        assumeTrue(env("ARMA_NET_TEST") == "1")
        val port = env("ARMA_SMB")?.toInt() ?: return
        val account = RemoteAccount(
            id = "watch", type = RemoteType.SMB, label = "SMB", host = "127.0.0.1",
            port = port, username = "test", password = "haslo", share = "dane"
        )
        val backend = SmbBackend()
        val writer = SmbBackend()
        val name = "/sub/obserwacja-${System.nanoTime()}.txt"
        backend.watch(account, "/sub").use { watch ->
            val done = java.util.concurrent.CompletableFuture.runAsync { watch.await() }
            Thread.sleep(500)
            writer.openOutput(account, name).use { it.write("x".toByteArray()) }
            done.get(10, java.util.concurrent.TimeUnit.SECONDS)
            println("  powiadomienie SMB: ok")
        }
        writer.delete(account, name, false)
    }

    @Test
    fun scenariusz() {
        assumeTrue(env("ARMA_NET_TEST") == "1")
        val payload = Random(1).nextBytes(2_000_000)
        for ((backend, account) in accounts) {
            println("== ${account.type}${if (account.secure) "S" else ""}:${account.port}")
            val pool = java.util.concurrent.Executors.newCachedThreadPool { Thread(it).apply { isDaemon = true } }
            fun step(name: String, block: () -> String) {
                val future = pool.submit<String> {
                    var text = ""
                    val ms = time { text = block() }
                    "ok ${ms}ms $text"
                }
                val result = runCatching { future.get(60, java.util.concurrent.TimeUnit.SECONDS) }
                    .getOrElse {
                        val cause = (it as? java.util.concurrent.ExecutionException)?.cause ?: it
                        if (cause is java.util.concurrent.TimeoutException) "ZAWIESZONE >60 s"
                        else "BŁĄD ${cause::class.simpleName}: ${cause.message}"
                    }
                println("  $name: $result")
            }
            step("list /") { backend.list(account, "/").joinToString { (if (it.isDirectory) "[d]" else "") + it.name + ":" + it.size } }
            step("info dir") { backend.info(account, "/sub").toString() }
            step("info link") { backend.info(account, "/link-do-sub").toString() }
            step("list link") { backend.list(account, "/link-do-sub").joinToString { it.name } }
            step("czytaj polskie") { backend.openInput(account, "/żółw.txt").use { it.readBytes().decodeToString().trim() } }
            step("mkdir") { backend.createFolder(account, "/nowy ąę"); "" }
            step("zapis 2MB") {
                backend.openOutput(account, "/nowy ąę/plik.bin").use { out ->
                    var off = 0
                    while (off < payload.size) {
                        val n = minOf(64 * 1024, payload.size - off)
                        out.write(payload, off, n); off += n
                    }
                }
                ""
            }
            step("odczyt 2MB") {
                val back = backend.openInput(account, "/nowy ąę/plik.bin").use { input ->
                    val buf = ByteArray(64 * 1024)
                    val out = java.io.ByteArrayOutputStream()
                    while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    out.toByteArray()
                }
                if (back.contentEquals(payload)) "zgodny" else "NIEZGODNY ${back.size}"
            }
            step("info pliku") { backend.info(account, "/nowy ąę/plik.bin").toString() }
            step("rename") { backend.move(account, "/nowy ąę/plik.bin", "/nowy ąę/inny.bin").toString() }
            step("delete plik") { backend.delete(account, "/nowy ąę/inny.bin", false).toString() }
            step("delete dir") { backend.delete(account, "/nowy ąę", true).toString() }
            step("10x list (pula)") { repeat(10) { backend.list(account, "/sub") }; "" }
            step("10x openInput (miniatury)") { repeat(10) { backend.openInput(account, "/żółw.txt").use { it.readBytes() } }; "" }
            step("brak pliku") { backend.openInput(account, "/nie-ma.txt").use { it.read() }.toString() }
        }
    }
}
