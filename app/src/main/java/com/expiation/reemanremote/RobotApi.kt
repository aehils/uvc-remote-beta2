package com.expiation.reemanremote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Minimal HTTP client for the Reeman navigation computer (firmware RSNF1-v5.1.20_01).
 *
 * Live requests run on Dispatchers.IO. A demo instance (see [demo]) answers from an
 * in-app [FakeRobot] and never opens a socket; its latency is a coroutine delay, so
 * tests can run it in virtual time.
 */
class RobotApi private constructor(host: String, private val fake: FakeRobot?) {

    data class Result(val ok: Boolean, val httpCode: Int, val body: String, val error: String?)

    constructor(host: String) : this(host, null)

    @Volatile
    var host: String = host

    val isDemo: Boolean get() = fake != null

    suspend fun get(path: String): Result = request("GET", path, null, 1500, 2000)

    suspend fun post(path: String, json: String): Result = request("POST", path, json, 2000, 4000)

    /**
     * For the speed stream (/cmd/speed, 20 a second): gives up fast, so one stalled request can't
     * hold the ramp up. The robot stops by itself if they stop arriving.
     */
    suspend fun postQuick(path: String, json: String): Result = request("POST", path, json, 400, 600)

    /** For finding the robot on the network (see [RobotFinder]): most addresses asked are empty. */
    suspend fun probe(path: String): Result = request("GET", path, null, 300, 800)

    private suspend fun request(method: String, path: String, json: String?, connectMs: Int, readMs: Int): Result {
        fake?.let { return simulate(it, method, path, json, connectMs) }
        if (!isLocalAddress(host)) return Result(false, -1, "", NOT_LOCAL)
        return withContext(Dispatchers.IO) { blockingRequest(method, path, json, connectMs, readMs) }
    }

    private suspend fun simulate(fake: FakeRobot, method: String, path: String, json: String?, connectMs: Int): Result {
        val r = fake.handle(method, path, json)
        // A dropped connection costs the same wait as the real timeout would.
        delay(r?.latencyMs ?: connectMs.toLong())
        if (r == null) return Result(false, -1, "", "timed out: robot not answering (DEMO: simulated Wi-Fi drop)")
        return toResult(r.code, r.body)
    }

    private fun blockingRequest(method: String, path: String, json: String?, connectMs: Int, readMs: Int): Result {
        var c: HttpURLConnection? = null
        return try {
            c = URL("http://$host$path").openConnection() as HttpURLConnection
            c.requestMethod = method
            c.connectTimeout = connectMs
            c.readTimeout = readMs
            c.useCaches = false
            if (json != null) {
                val bytes = json.toByteArray(Charsets.UTF_8)
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(bytes.size)
                c.outputStream.use { it.write(bytes) }
            }
            val code = c.responseCode
            val stream = if (code >= 400) c.errorStream else c.inputStream
            val body = stream?.use { it.readBytes().toString(Charsets.UTF_8).trim() }.orEmpty()
            toResult(code, body)
        } catch (e: IOException) {
            Result(false, -1, "", describe(e))
        } finally {
            c?.disconnect()
        }
    }

    companion object {
        fun demo(fake: FakeRobot) = RobotApi("demo", fake)

        const val NOT_LOCAL = "not a local network address: enter the robot's IP, like 192.168.1.228"

        /**
         * Plain http is only sent to an IPv4 address on a local network (an optional :port is
         * allowed), so a mistyped address can't send robot commands across the internet.
         */
        fun isLocalAddress(host: String): Boolean {
            val port = host.substringAfter(':', "")
            if (port.isNotEmpty() && port.toIntOrNull() !in 1..65535) return false
            val octets = host.substringBefore(':').split('.')
                .map { if (it.length in 1..3 && it.all(Char::isDigit)) it.toInt() else return false }
            if (octets.size != 4 || octets.any { it > 255 }) return false
            val (a, b) = octets
            return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168) || (a == 169 && b == 254)
        }

        private fun toResult(code: Int, body: String): Result {
            val ok = code in 200..299
            return Result(ok, code, body, if (ok) null else "HTTP $code $body")
        }

        private fun describe(e: IOException): String {
            val m = e.message ?: e.javaClass.simpleName
            return when {
                "CLEARTEXT" in m || "network security policy" in m ->
                    "Android blocked plain http to this address (see network_security_config.xml)."
                e is SocketTimeoutException -> "timed out: robot not answering"
                e is ConnectException || e is NoRouteToHostException ->
                    "can't reach robot: is the phone on the robot's network, and is this its address?"
                else -> m
            }
        }
    }
}
