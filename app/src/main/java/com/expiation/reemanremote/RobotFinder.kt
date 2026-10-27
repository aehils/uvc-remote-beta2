package com.expiation.reemanremote

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/** A robot that answered on the network: enough to recognise it, or for staff to pick it. */
data class FoundRobot(val host: String, val version: String, val hostname: String?) {
    /** What staff call it (see [RobotName]); its address if it doesn't say its hostname. */
    val name: String get() = hostname?.let(RobotName::short) ?: host
}

/** What a search of the phone's network turned up. [checked] is 0 when the phone isn't on Wi-Fi. */
data class SearchResult(val checked: Int, val robots: List<FoundRobot>)

/**
 * Finds robots on the phone's Wi-Fi when the one in use stops answering at its address (a new
 * router, or another access point handing out addresses): every address on the phone's network
 * is asked for /reeman/current_version, and those that answer like a robot for their hostname.
 * The hostname is how the app recognises its robot again (see [DriveController]).
 */
object RobotFinder {

    /** Networks bigger than this (a /22) are only searched around the phone and the last address. */
    private const val MAX_HOSTS = 1022

    /**
     * The addresses to ask, the robot's known ones first ([known], most recent first; they may be
     * on another, routed network), then the whole of the phone's network when it is a /22 or
     * smaller, otherwise the /24 around the phone, plus the /24 around the most recent address.
     */
    fun candidates(phoneIp: String, prefix: Int, known: List<String>): List<String> {
        val phone = toInt(phoneIp) ?: return emptyList()
        val local = known.filter(RobotApi::isLocalAddress).mapNotNull { toInt(it.substringBefore(':')) }
        val hosts = LinkedHashSet<Int>(local)
        val size = if (prefix in 22..30) 1L shl (32 - prefix) else 256L
        if (size - 2 <= MAX_HOSTS) hosts += range(phone, size) else hosts += range(phone, 256)
        local.firstOrNull()?.let { hosts += range(it, 256) }
        hosts -= phone
        return hosts.map(::toIp).filter(RobotApi::isLocalAddress)
    }

    /** Asks every host at once, [parallel] at a time; returns the robots in [hosts] order. */
    suspend fun find(hosts: List<String>, parallel: Int = 64, probe: suspend (String) -> FoundRobot?): List<FoundRobot> {
        val gate = Semaphore(parallel)
        return coroutineScope {
            hosts.map { h -> async { gate.withPermit { probe(h) } } }.awaitAll().filterNotNull()
        }
    }

    /** Is a robot at [api]'s address? Quick timeouts: most addresses on a network are empty. */
    suspend fun identify(api: RobotApi): FoundRobot? {
        val v = api.probe("/reeman/current_version")
        if (!v.ok) return null
        val version = runCatching { JSONObject(v.body).getString("version") }.getOrNull() ?: return null
        val h = api.probe(DriveController.HOSTNAME_PATH)
        return FoundRobot(api.host, version, if (h.ok) DriveController.parseHostname(h.body) else null)
    }

    /** The usable addresses of the [size]-address block holding [ip] (not its first and last). */
    private fun range(ip: Int, size: Long): List<Int> {
        val start = (ip.toLong() and 0xFFFFFFFFL) / size * size
        return (1 until size - 1).map { (start + it).toInt() }
    }

    private fun toInt(ip: String): Int? {
        val o = ip.split('.').map { it.toIntOrNull() ?: return null }
        if (o.size != 4 || o.any { it !in 0..255 }) return null
        return (o[0] shl 24) or (o[1] shl 16) or (o[2] shl 8) or o[3]
    }

    private fun toIp(i: Int) = "${i ushr 24 and 255}.${i ushr 16 and 255}.${i ushr 8 and 255}.${i and 255}"
}
