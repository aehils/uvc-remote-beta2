package com.expiation.reemanremote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlin.random.Random

/**
 * Asks the network where `<hostname>.local` is. The robot's navigation computer answers to its
 * hostname this way (multicast DNS), so the app can find it in a moment instead of asking every
 * address. Only on the phone's own network, and only where the network lets multicast through.
 *
 * The question goes out from an ordinary port, so the robot replies straight to the phone
 * (a "legacy unicast" query): no multicast lock or extra permission is needed.
 */
object Mdns {

    private val GROUP: InetAddress = InetAddress.getByName("224.0.0.251")
    private const val PORT = 5353
    private const val TYPE_A = 1
    private const val CLASS_IN = 1

    /**
     * The IPv4 address [hostname].local answers with, or null. [bind] puts the socket on the
     * phone's Wi-Fi (Android sends it elsewhere otherwise when mobile data is on).
     */
    suspend fun resolve(hostname: String, bind: (DatagramSocket) -> Unit = {}, waitMs: Int = 700, tries: Int = 2): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                DatagramSocket().use { socket ->
                    bind(socket)
                    socket.soTimeout = waitMs
                    val name = "$hostname.local"
                    repeat(tries) {
                        val id = Random.nextInt(1, 0xFFFF)
                        val q = query(name, id)
                        socket.send(DatagramPacket(q, q.size, GROUP, PORT))
                        val buf = ByteArray(1500)
                        try {
                            while (true) {
                                val p = DatagramPacket(buf, buf.size)
                                socket.receive(p)
                                answer(buf.copyOf(p.length), name)?.let { return@runCatching it }
                            }
                        } catch (_: SocketTimeoutException) {
                            // no answer this time: ask again
                        }
                    }
                    null
                }
            }.getOrNull()
        }

    /** A DNS question for [name]'s IPv4 address. */
    fun query(name: String, id: Int = 0): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v ushr 8 and 0xFF); out.write(v and 0xFF) }
        u16(id); u16(0); u16(1); u16(0); u16(0); u16(0) // id, flags, 1 question, no records
        for (label in name.trimEnd('.').split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        u16(TYPE_A); u16(CLASS_IN)
        return out.toByteArray()
    }

    /** The IPv4 address a DNS reply gives for [name], from any of its records; null if none. */
    fun answer(packet: ByteArray, name: String): String? = runCatching {
        fun u16(at: Int) = (packet[at].toInt() and 0xFF shl 8) or (packet[at + 1].toInt() and 0xFF)
        if (packet.size < 12 || packet[2].toInt() and 0x80 == 0) return null // not a reply
        val questions = u16(4)
        val records = u16(6) + u16(8) + u16(10)
        var at = 12
        repeat(questions) { at = readName(packet, at).second + 4 }
        repeat(records) {
            val (owner, end) = readName(packet, at)
            val type = u16(end)
            val length = u16(end + 8)
            val data = end + 10
            if (type == TYPE_A && length == 4 && owner.equals(name.trimEnd('.'), ignoreCase = true)) {
                return (0 until 4).joinToString(".") { (packet[data + it].toInt() and 0xFF).toString() }
            }
            at = data + length
        }
        null
    }.getOrNull()

    /** The name at [start] (following compression pointers), and where the record goes on after it. */
    private fun readName(packet: ByteArray, start: Int): Pair<String, Int> {
        val labels = mutableListOf<String>()
        var at = start
        var after = -1
        var jumps = 0
        while (true) {
            val len = packet[at].toInt() and 0xFF
            when {
                len == 0 -> { at++; break }
                len and 0xC0 == 0xC0 -> {
                    if (++jumps > 16) error("pointer loop")
                    if (after < 0) after = at + 2
                    at = (len and 0x3F shl 8) or (packet[at + 1].toInt() and 0xFF)
                }
                else -> {
                    labels += String(packet, at + 1, len, Charsets.UTF_8)
                    at += 1 + len
                }
            }
        }
        return labels.joinToString(".") to (if (after >= 0) after else at)
    }
}
