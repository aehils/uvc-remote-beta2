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
 * Minimal HTTP client for the Reeman navigation computer (firmware RSNF1-v5.1.12_01).
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

    private suspend fun request(method: String, path: String, json: String?, connectMs: Int, readMs: Int): Result {
        fake?.let { return simulate(it, method, path, json, connectMs) }
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

        private fun toResult(code: Int, body: String): Result {
            val ok = code in 200..299
            return Result(ok, code, body, if (ok) null else "HTTP $code $body")
        }

        private fun describe(e: IOException): String {
            val m = e.message ?: e.javaClass.simpleName
            return when {
                "CLEARTEXT" in m || "network security policy" in m ->
                    "Android blocked plain http to this address. The app only allows 192.168.1.228 (see README)."
                e is SocketTimeoutException -> "timed out: robot not answering"
                e is ConnectException || e is NoRouteToHostException ->
                    "can't reach robot: is the phone on the robot's Wi-Fi?"
                else -> m
            }
        }
    }
}
