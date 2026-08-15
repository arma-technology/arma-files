package pl.arma.files.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

data class DiscoveredHost(val address: String, val name: String?)

/**
 * Wyszukiwanie serwerów plików w sieci lokalnej.
 *
 * Zamiast rozgłoszeń NetBIOS, które w wielu sieciach są odfiltrowane, po
 * prostu próbujemy otworzyć port na każdym adresie z podsieci. Skanowanie
 * ogranicza się do sieci nie większych niż /22 — przy większych trwałoby
 * zbyt długo, a i tak nie tak wygląda typowy dom czy biuro.
 */
object LanScanner {

    const val SMB_PORT = 445
    private const val MAX_HOSTS = 1024
    private const val PARALLELISM = 64

    /**
     * Adresy do sprawdzenia dla danego adresu IPv4 i długości prefiksu.
     * Pomija adres sieci, rozgłoszeniowy i adres samego urządzenia.
     */
    fun subnetHosts(ip: String, prefixLength: Int): List<String> {
        val octets = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4 || octets.any { it !in 0..255 }) return emptyList()
        if (prefixLength !in 8..30) return emptyList()

        val address = octets.fold(0L) { acc, octet -> (acc shl 8) or octet.toLong() }
        val mask = (0xFFFFFFFFL shl (32 - prefixLength)) and 0xFFFFFFFFL
        val network = address and mask
        val broadcast = network or mask.inv().and(0xFFFFFFFFL)
        if (broadcast - network - 1 > MAX_HOSTS) return emptyList()

        return ((network + 1) until broadcast)
            .filter { it != address }
            .map { value ->
                listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value shr shift) and 0xFF).toString() }
            }
    }

    /** Adres IPv4 urządzenia w sieci lokalnej wraz z długością prefiksu. */
    fun localAddress(): Pair<String, Int>? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { networkInterface -> networkInterface.interfaceAddresses }
            .firstNotNullOfOrNull { interfaceAddress ->
                val address = interfaceAddress.address
                val host = address?.hostAddress
                if (address is java.net.Inet4Address && host != null) {
                    host to interfaceAddress.networkPrefixLength.toInt()
                } else {
                    null
                }
            }
    }.getOrNull()

    /**
     * Skanuje podsieć w poszukiwaniu otwartego portu SMB. [timeoutMillis]
     * dotyczy pojedynczego adresu — przy 64 równoległych próbach cała sieć /24
     * schodzi w kilka sekund.
     */
    suspend fun findSmbHosts(timeoutMillis: Int = 300): List<DiscoveredHost> {
        val local = localAddress() ?: return emptyList()
        val candidates = subnetHosts(local.first, local.second)
        if (candidates.isEmpty()) return emptyList()

        return withContext(Dispatchers.IO) {
            candidates.chunked(PARALLELISM).flatMap { chunk ->
                coroutineScope {
                    chunk.map { host ->
                        async { if (isOpen(host, SMB_PORT, timeoutMillis)) probe(host) else null }
                    }.awaitAll().filterNotNull()
                }
            }
        }
    }

    private fun isOpen(host: String, port: Int, timeoutMillis: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            true
        }
    }.getOrDefault(false)

    private fun probe(host: String): DiscoveredHost {
        // Odwrotne DNS bywa nieskonfigurowane; wtedy zostaje sam adres.
        val name = runCatching { InetAddress.getByName(host).canonicalHostName }
            .getOrNull()
            ?.takeIf { it != host }
        return DiscoveredHost(host, name)
    }
}
