package com.expiation.reemanremote

/**
 * DEMO mode's robot safety app, inside [FakeRobot]: enough of the agent's HTTP calls (see
 * [RobotAgent]) to show every screen, and nothing more. A countdown, a timer, the phone's lease,
 * the e-stop and a person seen on demand. The real logic, and its tests, are in the agent's own
 * repository; its fake on a laptop is the one to test against.
 *
 * Time moves on whenever it is asked something (see [advance]). No org.json, like [FakeRobot].
 */
class FakeAgent(
    private val setLamps: (Boolean) -> Unit,
    private val estop: () -> Boolean,
) {
    private sealed interface Lamp {
        data object Off : Lamp
        data class Countdown(val endsAt: Long, val leftMs: Long) : Lamp
        data class On(val endsAt: Long) : Lamp
        data class Person(val leftMs: Long, val clearAt: Long) : Lamp
    }

    private var lamp: Lamp = Lamp.Off
    private var controller: String? = null
    private var countdownMs = 0L
    private var leaseAt = 0L
    private var lastOffReason: String? = null
    private var mask = 0
    private var maskUntil = 0L
    private var lastSeenAt: Long? = null
    private val decided = LinkedHashMap<String, Pair<Int, String>>()

    /** The safety app has stopped answering (DEMO controls). */
    var down = false

    val lampsOn: Boolean get() = lamp is Lamp.On

    /** A reply, or null for no answer at all (as a timeout). [now] in ms. */
    fun handle(method: String, path: String, json: String, now: Long): Pair<Int, String>? {
        if (down) return null
        advance(now)
        return when ("$method $path") {
            "GET ${RobotAgent.INFO_PATH}" -> 200 to """{"api":${RobotAgent.API},"agent":"demo","host":"${FakeRobot.HOSTNAME}","lampsUnlocked":true}"""
            "GET ${RobotAgent.STATE_PATH}" -> 200 to state(now)
            "POST ${RobotAgent.LAMP_ON_PATH}" -> lampOn(json, now)
            "POST ${RobotAgent.LEASE_PATH}" ->
                if (lamp != Lamp.Off && str(json, "controller") == controller) { leaseAt = now; 200 to OK } else 409 to NOT_OK
            "POST ${RobotAgent.STOP_PATH}", "POST ${RobotAgent.LAMP_OFF_PATH}" -> {
                off("Stopped from a phone: " + (str(json, "reason") ?: "no reason given"))
                200 to OK
            }
            else -> 404 to """{"ok":false,"reasons":["no such call"]}"""
        }
    }

    /** Someone walks past the front dome. Lamps off; back after a quiet spell and a new countdown. */
    fun personSeen(now: Long) {
        advance(now)
        mask = FRONT
        maskUntil = now + MASK_MS
        lastSeenAt = now
        when (val l = lamp) {
            is Lamp.Countdown -> lamp = Lamp.Person(l.leftMs, now + CLEAR_MS)
            is Lamp.On -> { setLamps(false); lamp = Lamp.Person((l.endsAt - now).coerceAtLeast(0), now + CLEAR_MS) }
            is Lamp.Person -> lamp = l.copy(clearAt = now + CLEAR_MS)
            Lamp.Off -> {}
        }
    }

    fun reset() {
        off(null)
        lastOffReason = null
        down = false
        mask = 0
        lastSeenAt = null
        decided.clear()
    }

    private fun lampOn(json: String, now: Long): Pair<Int, String> {
        val id = str(json, "requestId") ?: return 400 to NOT_OK
        decided[id]?.let { return it }
        val forMs = num(json, "forMs") ?: 0L
        val cd = num(json, "countdownMs") ?: 15_000L
        val reasons = buildList {
            if (estop()) add("Emergency stop pressed")
            if (forMs !in 1..MAX_MS) add("Longer than 30 minutes")
            if (cd < 5_000) add("Countdown shorter than 5 s")
            if (lamp != Lamp.Off) add("A run is already going")
        }
        val reply = if (reasons.isEmpty()) {
            controller = str(json, "controller")
            countdownMs = cd
            leaseAt = now
            lamp = Lamp.Countdown(now + cd, forMs)
            200 to """{"ok":true,"requestId":"$id","reasons":[]}"""
        } else {
            409 to reasons.joinToString(",", """{"ok":false,"requestId":"$id","reasons":[""", "]}") { "\"$it\"" }
        }
        decided[id] = reply
        while (decided.size > 50) decided.remove(decided.keys.first())
        return reply
    }

    private fun advance(now: Long) {
        if (now >= maskUntil) mask = 0
        if (lamp == Lamp.Off) return
        when {
            now - leaseAt > LEASE_MS -> return off("Lost the phone: lamps off")
            estop() -> return off("Emergency stop pressed")
        }
        when (val l = lamp) {
            is Lamp.Countdown -> if (now >= l.endsAt) { setLamps(true); lamp = Lamp.On(now + l.leftMs) }
            is Lamp.On -> if (now >= l.endsAt) off(RobotAgent.TIME_UP)
            is Lamp.Person -> if (now >= l.clearAt) lamp = Lamp.Countdown(now + countdownMs, l.leftMs)
            Lamp.Off -> {}
        }
    }

    private fun off(reason: String?) {
        setLamps(false)
        if (reason != null) lastOffReason = reason // as the real one: any OFF writes its reason, run or not
        lamp = Lamp.Off
        controller = null
    }

    private fun state(now: Long): String {
        val (word, left, cd) = when (val l = lamp) {
            Lamp.Off -> Triple("off", null, null)
            is Lamp.Countdown -> Triple("countdown", l.leftMs, (l.endsAt - now).coerceAtLeast(0))
            is Lamp.On -> Triple("on", (l.endsAt - now).coerceAtLeast(0), null)
            is Lamp.Person -> Triple("paused_person", l.leftMs, null)
        }
        val person = lamp is Lamp.Person
        fun q(s: String?) = s?.let { "\"$it\"" } ?: "null"
        return """{"api":${RobotAgent.API},"t":0,""" +
            """"lamp":{"state":"$word","leftMs":${left ?: "null"},"countdownMs":${cd ?: "null"},"confirmed":false,"lastOffReason":${q(lastOffReason)},"unlocked":true},""" +
            """"sensor":{"mode":"${if (lamp == Lamp.Off) "off" else "on"}","person":$person,"mask":$mask,"lastSeenAgoMs":${lastSeenAt?.let { now - it } ?: "null"},"healthy":true},""" +
            """"robot":{"estop":${estop()},"moving":false,"battery":null,"charging":null},""" +
            """"ros":{"serial":true,"hostname":"${FakeRobot.HOSTNAME}"},"controller":${q(controller)},""" +
            """"faults":[${listOfNotNull("\"ESTOP\"".takeIf { estop() }, "\"PERSON\"".takeIf { person }).joinToString(",")}]}"""
    }

    private fun str(json: String, key: String): String? = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
    private fun num(json: String, key: String): Long? = Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private companion object {
        const val OK = """{"ok":true}"""
        const val NOT_OK = """{"ok":false}"""
        const val LEASE_MS = 5_000L
        const val CLEAR_MS = 15_000L
        const val MASK_MS = 1_500L
        const val MAX_MS = 30 * 60_000L
        const val FRONT = 0x04
    }
}
