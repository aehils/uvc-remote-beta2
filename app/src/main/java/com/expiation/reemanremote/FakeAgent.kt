package com.expiation.reemanremote

/**
 * DEMO mode's Robot Controller, inside [FakeRobot]: enough of the agent's HTTP calls (see
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
        /** [held]: held at the end of a countdown, on once quiet; else after the lamps were on, a short warning first. */
        data class Person(val leftMs: Long, val clearAt: Long, val held: Boolean) : Lamp
    }

    private var lamp: Lamp = Lamp.Off
    private var controller: String? = null
    private var leaseAt = 0L
    private var lastOffReason: String? = null
    private var mask = 0
    private var maskUntil = 0L
    private var lastSeenAt: Long? = null
    private var seenInRunAt: Long? = null // the sensor is on only from the countdown to the end of a run
    private val decided = LinkedHashMap<String, Pair<Int, String>>()
    private var voiceVolume = 60
    private var lowBattery = 25

    /** The head unit's Wi-Fi is weak (DEMO controls): -78 dBm on 5 GHz. */
    var weakWifi = false

    /** The robot is about to set off for its charger by itself (DEMO controls). */
    var chargeWarning = false


    /** How many times the voice test was asked for and allowed: the DEMO robot has no voice. */
    var voiceTests = 0; private set

    /** Pairing switched on, as `agent pairing on` does on a robot: starting things needs a token. */
    var pairingRequired = false

    /** What the DEMO robot's screen would show: the pairing code, while it lasts. */
    var shownCode: String? = null; private set
    private var codeUntil = 0L
    private val tokens = HashSet<String>()

    /** The Robot Controller has stopped answering (DEMO controls). */
    var down = false

    val lampsOn: Boolean get() = lamp is Lamp.On

    /** A reply, or null for no answer at all (as a timeout). [now] in ms. */
    fun handle(method: String, path: String, json: String, now: Long, token: String? = null): Pair<Int, String>? {
        if (down) return null
        advance(now)
        if (shownCode != null && now >= codeUntil) shownCode = null
        val call = "$method $path"
        if (pairingRequired && call in NEEDS_TOKEN && token !in tokens)
            return 401 to """{"ok":false,"reasons":["Pair this phone with the robot first"]}"""
        return when (call) {
            "GET ${RobotAgent.INFO_PATH}" -> 200 to """{"api":${RobotAgent.API},"agent":"demo","host":"${FakeRobot.HOSTNAME}","lampsUnlocked":true,"pairingRequired":$pairingRequired}"""
            "POST ${RobotAgent.PAIR_PATH}" -> pair(str(json, "code"), now)
            "GET ${RobotAgent.STATE_PATH}" -> 200 to state(now)
            "POST ${RobotAgent.LAMP_ON_PATH}" -> lampOn(json, now)
            "POST ${RobotAgent.LEASE_PATH}" ->
                if (lamp != Lamp.Off && str(json, "controller") == controller) { leaseAt = now; 200 to OK } else 409 to NOT_OK
            "POST ${RobotAgent.SETTINGS_PATH}" -> {
                num(json, "voiceVolume")?.let { voiceVolume = it.toInt().coerceIn(RobotAgent.VOICE_FLOOR, 100) }
                num(json, "lowBattery")?.let { lowBattery = it.toInt().coerceIn(LOW_BATTERY_FLOOR, 70) }
                200 to """{"ok":true,"reasons":[],"lowBattery":$lowBattery,"voiceVolume":$voiceVolume}"""
            }
            "POST ${RobotAgent.VOICE_TEST_PATH}" ->
                if (lamp != Lamp.Off) 409 to """{"ok":false,"reasons":["Not while the lamps are running"]}"""
                else { voiceTests++; 200 to """{"ok":true,"reasons":[]}""" }
            "POST ${RobotAgent.STOP_PATH}", "POST ${RobotAgent.LAMP_OFF_PATH}" -> {
                off("Stopped from a phone: " + (str(json, "reason") ?: "no reason given"))
                200 to OK
            }
            else -> 404 to """{"ok":false,"reasons":["no such call"]}"""
        }
    }

    /**
     * Someone walks past the front dome (spec R9). In a countdown: it runs on, and at its end the
     * lamps wait for [QUIET_MS] with nothing moving. Lamps on: off at once, back after [CLEAR_MS]
     * quiet and a [RESUME_WARNING_MS] warning countdown. Never the full countdown again.
     */
    fun personSeen(now: Long) {
        advance(now)
        mask = FRONT
        maskUntil = now + MASK_MS
        lastSeenAt = now
        when (val l = lamp) {
            is Lamp.Countdown -> seenInRunAt = now
            is Lamp.On -> { setLamps(false); seenInRunAt = now; lamp = Lamp.Person((l.endsAt - now).coerceAtLeast(0), now + CLEAR_MS, held = false) }
            is Lamp.Person -> { seenInRunAt = now; lamp = l.copy(clearAt = now + if (l.held) QUIET_MS else CLEAR_MS) }
            Lamp.Off -> {}
        }
    }

    fun reset() {
        off(null)
        lastOffReason = null
        down = false
        mask = 0
        lastSeenAt = null
        seenInRunAt = null
        weakWifi = false
        chargeWarning = false
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
            leaseAt = now
            seenInRunAt = null
            lamp = Lamp.Countdown(now + cd, forMs)
            200 to """{"ok":true,"requestId":"$id","reasons":[]}"""
        } else {
            409 to reasons.joinToString(",", """{"ok":false,"requestId":"$id","reasons":[""", "]}") { "\"$it\"" }
        }
        decided[id] = reply
        while (decided.size > 50) decided.remove(decided.keys.first())
        return reply
    }

    private fun pair(code: String?, now: Long): Pair<Int, String> {
        if (code == null) {
            if (lamp != Lamp.Off) return 409 to """{"ok":false,"reasons":["Pair when the robot isn't running UV"]}"""
            if (shownCode == null) { shownCode = "%06d".format((now * 7919 + 104_729) % 1_000_000); codeUntil = now + CODE_MS }
            return 200 to """{"ok":true,"showing":true,"forMs":${codeUntil - now}}"""
        }
        val c = shownCode ?: return 403 to """{"ok":false,"expired":true,"reasons":["This code has expired"]}"""
        if (code.trim() != c) return 403 to """{"ok":false,"expired":false,"reasons":["Wrong code: check the robot's screen"]}"""
        shownCode = null
        val t = "demo-token-${tokens.size + 1}"
        tokens += t
        return 200 to """{"ok":true,"token":"$t"}"""
    }

    private fun advance(now: Long) {
        if (now >= maskUntil) mask = 0
        if (lamp == Lamp.Off) return
        when {
            now - leaseAt > LEASE_MS -> return off("Lost the phone: lamps off")
            estop() -> return off("Emergency stop pressed")
        }
        when (val l = lamp) {
            is Lamp.Countdown -> if (now >= l.endsAt) {
                val seen = seenInRunAt
                if (seen != null && now - seen < QUIET_MS) lamp = Lamp.Person(l.leftMs, seen + QUIET_MS, held = true)
                else { setLamps(true); lamp = Lamp.On(now + l.leftMs) }
            }
            is Lamp.On -> if (now >= l.endsAt) off(RobotAgent.TIME_UP)
            is Lamp.Person -> if (now >= l.clearAt) {
                if (l.held) { setLamps(true); lamp = Lamp.On(now + l.leftMs) }
                else lamp = Lamp.Countdown(now + RESUME_WARNING_MS, l.leftMs)
            }
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
            """"voice":{"volume":$voiceVolume,"floor":${RobotAgent.VOICE_FLOOR}},""" +
            """"wifi":{"state":"idle","rssi":${if (weakWifi) -78 else -52},"mhz":5180,"weak":$weakWifi},""" +
            """"charge":{"state":"${if (chargeWarning) "warning" else "ok"}","reason":${q(if (chargeWarning) "Battery low: going to charge" else null)},""" +
            """"lastResult":null,"warningMs":${if (chargeWarning) 30_000 else "null"},"lowBattery":$lowBattery,"unlocked":true},""" +
            """"faults":[${listOfNotNull("\"ESTOP\"".takeIf { estop() }, "\"PERSON\"".takeIf { person }).joinToString(",")}]}"""
    }

    private fun str(json: String, key: String): String? = Regex("\"$key\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
    private fun num(json: String, key: String): Long? = Regex("\"$key\"\\s*:\\s*(\\d+)").find(json)?.groupValues?.get(1)?.toLongOrNull()

    private companion object {
        const val OK = """{"ok":true}"""
        const val NOT_OK = """{"ok":false}"""
        const val LEASE_MS = 5_000L
        const val CLEAR_MS = 15_000L          // quiet before the lamps come back (Tuning.sensorHoldMs)
        const val QUIET_MS = 10_000L          // quiet at the end of a countdown (Tuning.countdownQuietMs)
        const val RESUME_WARNING_MS = 10_000L // the warning before they come back (Tuning.resumeWarningMs)
        const val LOW_BATTERY_FLOOR = 20
        const val MASK_MS = 1_500L
        const val MAX_MS = 30 * 60_000L
        const val FRONT = 0x04
        const val CODE_MS = 5 * 60_000L
        val NEEDS_TOKEN = setOf("POST ${RobotAgent.LAMP_ON_PATH}", "POST ${RobotAgent.LEASE_PATH}",
            "POST ${RobotAgent.SETTINGS_PATH}", "POST ${RobotAgent.VOICE_TEST_PATH}")
    }
}
