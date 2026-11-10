package com.expiation.reemanremote

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import kotlin.coroutines.resume

/**
 * Browses the phone's Wi-Fi for Robot Controllers (`_robotagent._tcp`, see [RobotAgent]) with
 * Android's NSD, and resolves each to its address and its TXT `host` (the robot it is on).
 * [AgentFinder] decides which, if any, is this robot's.
 */
class AgentNsd(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    /** What answered within [waitMs]; empty if NSD isn't available or nothing did. */
    suspend fun browse(waitMs: Long = BROWSE_MS): List<NsdHit> {
        val manager = nsd ?: return emptyList()
        val found = mutableListOf<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) {
                synchronized(found) { if (found.none { it.serviceName == s.serviceName }) found += s }
            }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, code: Int) {}
            override fun onStopDiscoveryFailed(type: String, code: Int) {}
        }
        if (runCatching { manager.discoverServices(RobotAgent.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }.isFailure) return emptyList()
        try {
            delay(waitMs)
        } finally {
            runCatching { manager.stopServiceDiscovery(listener) }
        }
        // One resolve at a time: older Androids refuse a second while one is going.
        return synchronized(found) { found.toList() }.take(MAX_RESOLVE).mapNotNull { resolve(manager, it) }
    }

    @Suppress("DEPRECATION") // resolveService: its replacement needs Android 14
    private suspend fun resolve(manager: NsdManager, s: NsdServiceInfo): NsdHit? = withTimeoutOrNull(RESOLVE_MS) {
        suspendCancellableCoroutine { cont ->
            val l = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, code: Int) {
                    if (cont.isActive) cont.resume(null)
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    val ip = (info.host as? Inet4Address)?.hostAddress
                    val host = info.attributes["host"]?.toString(Charsets.UTF_8)?.takeIf { it.isNotBlank() }
                    if (cont.isActive) cont.resume(ip?.let { NsdHit(RobotAgent.address(it, info.port), host) })
                }
            }
            runCatching { manager.resolveService(s, l) }.onFailure { if (cont.isActive) cont.resume(null) }
        }
    }

    private companion object {
        const val BROWSE_MS = 2_000L
        const val RESOLVE_MS = 2_000L
        const val MAX_RESOLVE = 6
    }
}
