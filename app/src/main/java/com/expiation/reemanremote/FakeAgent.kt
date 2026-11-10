package com.expiation.reemanremote

/**
 * DEMO mode's Robot Controller, inside [FakeRobot]: enough of the agent's HTTP calls (see
 * [RobotAgent]) to show every screen, and nothing more. A countdown, a timer, the phone's lease,
 * the e-stop, a person seen on demand (spec R9's rules), a weak head-unit Wi-Fi and a robot
 * about to go to charge, on demand, and routines run on the robot (a few seconds a trip, no
 * driving: the DEMO robot stays put). The real logic, and its tests, are in the agent's own
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

    /** Routines run on the robot (its lock file; DEMO controls). Off: the phone runs them, as before. */
    var routinesUnlocked = false

    /** The routines a phone handed over (`POST /v1/routines/replace`): how many, and their fingerprint. */
    /** Tests: the next this many lamp ONs are refused as the real one does while the robot settles. */
    var refuseLampOns = 0

    /** Tests: lamp ON answers this slowly (a weak head-unit link). */
    var lampOnDelayMs = 0L

    var routinesCount = 0
    var routinesRev: String? = null

    private class Routine(
        val task: DisinfectionTask,
        var controller: String,
        var leaseAt: Long,
        var round: Int = 1,
        var index: Int = 0,
        var stage: String = "travelling",
        var stageUntil: Long = 0,
        var uvLeftMs: Long? = null,
        var awaySince: Long? = null,
        var awayMs: Long = 0,
        val results: MutableList<StopResult> = mutableListOf(),
    ) {
        val stop: TaskStop get() = task.stops[index]
        val total: Int get() = task.stops.size * task.rounds
        val number: Int get() = (round - 1) * task.stops.size + index + 1
    }

    private var routine: Routine? = null
    private var lastReport: String? = null
    private var routineLamp: String? = null // the requestId of the lamp run the routine started

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
            "POST ${RobotAgent.LEASE_PATH}" -> {
                val who = str(json, "controller")
                val r = routine
                var ok = false
                if (r != null && (who == r.controller || r.awaySince != null) && who != null) {
                    r.awaySince?.let { r.awayMs += now - it }
                    r.awaySince = null
                    r.controller = who
                    r.leaseAt = now
                    ok = true
                }
                if (lamp != Lamp.Off && who == controller) { leaseAt = now; ok = true }
                if (ok) 200 to OK else 409 to NOT_OK
            }
            "POST ${RobotAgent.TASK_START_PATH}" -> startRoutine(json, now)
            "POST ${RobotAgent.ROUTINES_REPLACE_PATH}" -> runCatching { org.json.JSONObject(json) }.getOrNull()?.takeIf { it.has("routines") }?.let { o ->
                routinesCount = o.getJSONArray("routines").length()
                routinesRev = o.optString("rev").ifBlank { null }
                200 to """{"ok":true,"count":$routinesCount,"rev":${routinesRev?.let { "\"$it\"" } ?: "null"}}"""
            } ?: (400 to """{"ok":false,"reasons":["routines is missing or can't be read"]}""")
            "POST ${RobotAgent.TASK_PAUSE_PATH}" -> 200 to """{"ok":true,"paused":${pauseRoutine(now)}}"""
            "POST ${RobotAgent.TASK_RESUME_PATH}" -> resumeRoutine(str(json, "controller"), now)
            "POST ${RobotAgent.TASK_END_PATH}" -> { endRoutine("Task ended from the app", now); 200 to OK }
            "POST ${RobotAgent.SETTINGS_PATH}" -> {
                num(json, "voiceVolume")?.let { voiceVolume = it.toInt().coerceIn(RobotAgent.VOICE_FLOOR, 100) }
                num(json, "lowBattery")?.let { lowBattery = it.toInt().coerceIn(LOW_BATTERY_FLOOR, 70) }
                200 to """{"ok":true,"reasons":[],"lowBattery":$lowBattery,"voiceVolume":$voiceVolume}"""
            }
            "POST ${RobotAgent.VOICE_TEST_PATH}" ->
                if (lamp != Lamp.Off) 409 to """{"ok":false,"reasons":["Not while the lamps are running"]}"""
                else { voiceTests++; 200 to """{"ok":true,"reasons":[]}""" }
            "POST ${RobotAgent.STOP_PATH}" -> {
                off("Stopped from a phone: " + (str(json, "reason") ?: "no reason given"))
                endRoutine("STOP pressed", now)
                200 to OK
            }
            "POST ${RobotAgent.LAMP_OFF_PATH}" -> {
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
        routinesUnlocked = false
        routine = null
        routineLamp = null
        lastReport = null
        decided.clear()
    }

    private fun lampOn(json: String, now: Long): Pair<Int, String> {
        val id = str(json, "requestId") ?: return 400 to NOT_OK
        decided[id]?.let { return it }
        val forMs = num(json, "forMs") ?: 0L
        val cd = num(json, "countdownMs") ?: 15_000L
        val reasons = buildList {
            if (refuseLampOns > 0) { refuseLampOns--; add("The robot is moving") }
            if (estop()) add("Emergency stop pressed")
            if (forMs !in 1..MAX_MS) add("Longer than 30 minutes")
            if (cd < 5_000) add("Countdown shorter than 5 s")
            if (lamp != Lamp.Off || routine != null) add("A run is already going")
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
        tickRoutine(now)
        if (lamp == Lamp.Off) return
        val forRoutine = routineLamp != null && routine != null
        when {
            // A routine's lamps carry on without the phone (R24, the switch on); a phone's run doesn't.
            !forRoutine && now - leaseAt > LEASE_MS -> return off("Lost the phone: lamps off")
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
            """"task":${routineJson(now)},"lastReport":${lastReport ?: "null"},""" +
            """"routines":{"unlocked":$routinesUnlocked,"phoneAwayCarriesOn":true,"count":$routinesCount,"rev":${q(routinesRev)}},""" +
            """"faults":[${listOfNotNull("\"ESTOP\"".takeIf { estop() }, "\"PERSON\"".takeIf { person }).joinToString(",")}]}"""
    }

    // ---- routines on the robot: a trip takes TRIP_MS, then the stop's countdown and lamps.

    private fun startRoutine(json: String, now: Long): Pair<Int, String> {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return 400 to NOT_OK
        val id = o.optString("requestId")
        decided[id]?.let { return it }
        val task = runCatching { DisinfectionTask.fromJson(o.getJSONObject("task")) }.getOrNull()
        val reasons = buildList {
            if (!routinesUnlocked) add("Routines are locked on this robot")
            if (task == null || task.stops.isEmpty()) add("No such routine on this robot")
            if (routine != null || lamp != Lamp.Off) add("A run is already going")
            if (estop()) add("Emergency stop pressed")
        }
        val reply = if (reasons.isEmpty()) {
            routine = Routine(task!!, o.optString("controller"), leaseAt = now, stageUntil = now + TRIP_MS)
            200 to """{"ok":true,"requestId":"$id","reasons":[]}"""
        } else 409 to reasons.joinToString(",", """{"ok":false,"requestId":"$id","reasons":[""", "]}") { "\"$it\"" }
        decided[id] = reply
        return reply
    }

    private fun tickRoutine(now: Long) {
        val r = routine ?: return
        if (r.awaySince == null && now - r.leaseAt > LEASE_MS) r.awaySince = r.leaseAt
        when (r.stage) {
            "travelling" -> if (now >= r.stageUntil) {
                if (r.task.lampsOff) { r.results += StopResult(r.round, r.stop.point, StopOutcome.DONE); return nextStop(r, now) }
                val forMs = r.uvLeftMs ?: (r.stop.minutes * 60_000L)
                r.stage = "disinfecting"
                routineLamp = "routine-$now"
                controller = r.controller
                lamp = Lamp.Countdown(now + r.task.delaySeconds * 1000L, forMs)
                seenInRunAt = null
            }
            "disinfecting" -> if (lamp == Lamp.Off) {
                routineLamp = null
                if (lastOffReason == RobotAgent.TIME_UP) {
                    r.results += StopResult(r.round, r.stop.point, StopOutcome.DONE, r.uvLeftMs ?: (r.stop.minutes * 60_000L))
                    r.uvLeftMs = null
                    nextStop(r, now)
                } else endRoutine(lastOffReason ?: "lamps off", now)
            }
        }
    }

    private fun nextStop(r: Routine, now: Long) {
        r.index++
        if (r.index >= r.task.stops.size) { r.index = 0; r.round++ }
        if (r.round > r.task.rounds) { r.round = r.task.rounds; r.index = r.task.stops.size - 1; return finishRoutine(r, now, completed = true, reason = null) }
        r.stage = "travelling"
        r.stageUntil = now + TRIP_MS
    }

    private fun pauseRoutine(now: Long): Boolean {
        val r = routine ?: return false
        if (r.stage == "paused") return false
        if (r.stage == "disinfecting") {
            r.uvLeftMs = when (val l = lamp) {
                is Lamp.Countdown -> l.leftMs
                is Lamp.On -> (l.endsAt - now).coerceAtLeast(0)
                is Lamp.Person -> l.leftMs
                Lamp.Off -> r.uvLeftMs
            }
            routineLamp = null
            off("Task paused")
        }
        r.stage = "paused"
        return true
    }

    private fun resumeRoutine(who: String?, now: Long): Pair<Int, String> {
        val r = routine ?: return 409 to """{"ok":false,"reasons":["No routine is running."]}"""
        if (r.stage != "paused") return 409 to """{"ok":false,"reasons":["The routine isn't paused."]}"""
        if (who != r.controller || r.awaySince != null) return 409 to """{"ok":false,"reasons":["Not this phone's routine."]}"""
        r.stage = "travelling"
        r.stageUntil = now + TRIP_MS
        return 200 to OK
    }

    private fun endRoutine(reason: String, now: Long) {
        val r = routine ?: return
        if (routineLamp != null) { routineLamp = null; off(reason) }
        finishRoutine(r, now, completed = false, reason = reason)
    }

    private fun finishRoutine(r: Routine, now: Long, completed: Boolean, reason: String?) {
        routine = null
        val away = r.awayMs + (r.awaySince?.let { now - it } ?: 0)
        val report = TaskReport(r.task, completed, reason, r.results.toList(), System.currentTimeMillis(), phoneAwayMs = away)
        lastReport = TaskReport.listToJson(listOf(report)).removePrefix("[").removeSuffix("]")
    }

    private fun routineJson(now: Long): String {
        val r = routine ?: return "null"
        val left = if (r.stage == "disinfecting") when (val l = lamp) {
            is Lamp.Countdown -> l.leftMs
            is Lamp.On -> (l.endsAt - now).coerceAtLeast(0)
            is Lamp.Person -> l.leftMs
            Lamp.Off -> null
        } else r.uvLeftMs
        val results = r.results.joinToString(",") {
            """{"round":${it.round},"point":"${it.point}","outcome":"${it.outcome.name}","uv_ms":${it.uvMs},"person_pauses":${it.personPauses}}"""
        }
        val away = r.awayMs + (r.awaySince?.let { now - it } ?: 0)
        return """{"id":"${r.task.id}","name":"${r.task.name}","stage":"${r.stage}","problem":null,"round":${r.round},"rounds":${r.task.rounds},""" +
            """"stop":"${r.stop.point}","number":${r.number},"total":${r.total},"uvLeftMs":${left ?: "null"},"lampsOff":${r.task.lampsOff},""" +
            """"controller":"${r.controller}","phoneAway":${r.awaySince != null},"phoneAwayMs":$away,"results":[$results],"events":[]}"""
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
        const val TRIP_MS = 4_000L            // DEMO: a trip between points
        const val MASK_MS = 1_500L
        const val MAX_MS = 30 * 60_000L
        const val FRONT = 0x04
        const val CODE_MS = 5 * 60_000L
        val NEEDS_TOKEN = setOf("POST ${RobotAgent.LAMP_ON_PATH}", "POST ${RobotAgent.LEASE_PATH}",
            "POST ${RobotAgent.SETTINGS_PATH}", "POST ${RobotAgent.VOICE_TEST_PATH}")
    }
}
