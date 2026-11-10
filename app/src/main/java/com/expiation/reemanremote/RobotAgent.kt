package com.expiation.reemanremote

import org.json.JSONObject

/**
 * The Robot Agent, called the Robot Controller on screen: our own app on the robot's head unit. Once a robot has one, it alone
 * switches the UV lamps on: the phone asks, the agent decides, runs the countdown and the timer,
 * watches the person sensor, and switches the lamps off if this phone stops renewing its lease.
 *
 * It answers HTTP on [PORT] (see [RobotApi], which carries the requests: the same local-address
 * rule and the same Wi-Fi). This file is the phone's side of that contract: what the replies mean.
 */
object RobotAgent {

    const val PORT = 8765
    const val API = 1
    const val SERVICE_TYPE = "_robotagent._tcp"

    const val INFO_PATH = "/v1/info"
    const val STATE_PATH = "/v1/state"
    const val LAMP_ON_PATH = "/v1/lamp/on"
    const val LAMP_OFF_PATH = "/v1/lamp/off"
    const val LEASE_PATH = "/v1/lease"
    const val STOP_PATH = "/v1/stop"
    const val SETTINGS_PATH = "/v1/settings"
    const val VOICE_TEST_PATH = "/v1/voice/test"
    const val PAIR_PATH = "/v1/pair"
    const val TASK_START_PATH = "/v1/task/start"
    const val TASK_PAUSE_PATH = "/v1/task/pause"
    const val TASK_RESUME_PATH = "/v1/task/resume"
    const val TASK_END_PATH = "/v1/task/end"

    /** The agent's floor for [AgentStatus.voiceVolume] when an older one doesn't say. */
    const val VOICE_FLOOR = 30

    /** What a fake agent calls itself in /v1/info: the only kind DEMO mode will talk to. */
    const val FAKE_VERSION = "fake"

    /** `lastOffReason` when a run ended on its own time. */
    const val TIME_UP = "Time up"

    /** Faults that only block a run, not what the lamps can do: the agent leaves them out of a refusal. */
    private val NOT_BLOCKING = setOf("PERSON", "SENSOR_OFF", "SENSOR_NO_ANSWER")

    /** The agent's fault names (its `Fault` enum), in the words its own screen uses. */
    private val FAULT_TEXT = mapOf(
        "LOCKED" to "Lamps are locked on this robot",
        "SERIAL_DOWN" to "No data from the robot's base",
        "BOARD_SILENT" to "The power board has gone quiet",
        "NO_CORE_DATA" to "No status from the robot's base",
        "ESTOP" to "Emergency stop pressed",
        "BUMPER" to "Bumper pressed",
        "CLIFF" to "Drop sensor triggered",
        "MOVING" to "The robot is moving",
        "LOST_POSITION" to "The robot lost its position",
        "SENSOR_FAULT" to "A base sensor reports a fault",
        "SENSOR_NO_ANSWER" to "The person sensor didn't switch on",
        "SENSOR_OFF" to "The person sensor is off",
        "PERSON" to "Someone moved nearby",
    )

    /** The person sensor's four domes, one bit each, left and right as you face the robot's screen. */
    private val DOMES = listOf(0x04 to "Front", 0x02 to "Right", 0x01 to "Back", 0x08 to "Left")

    fun faultText(name: String): String = FAULT_TEXT[name] ?: name.lowercase().replace('_', ' ')

    /** "Front, Left" for the domes set in [mask]; empty when none is. */
    fun domes(mask: Int): String = DOMES.filter { mask and it.first != 0 }.joinToString(", ") { it.second }

    /** The agent's address as the app keeps it: its IPv4 address and port. */
    fun address(ip: String, port: Int = PORT) = "$ip:$port"

    fun parseInfo(body: String): AgentInfo? = runCatching {
        val o = JSONObject(body)
        AgentInfo(
            api = o.getInt("api"),
            version = o.optString("agent", "?"),
            host = o.optStringOrNull("host"),
            lampsUnlocked = o.optBoolean("lampsUnlocked", false),
            pairingRequired = o.optBoolean("pairingRequired", false),
        )
    }.getOrNull()

    fun parseState(body: String): AgentStatus? = runCatching {
        val o = JSONObject(body)
        val lamp = o.getJSONObject("lamp")
        val sensor = o.optJSONObject("sensor") ?: JSONObject()
        val robot = o.optJSONObject("robot") ?: JSONObject()
        val faults = o.optJSONArray("faults")
        val voice = o.optJSONObject("voice") ?: JSONObject()
        // An agent from before E10 has no "weak" in "wifi" (or no "wifi"): nothing to show.
        val wifi = o.optJSONObject("wifi")?.takeIf { it.has("weak") }
        val charge = o.optJSONObject("charge")
        val task = o.optJSONObject("task")
        val routines = o.optJSONObject("routines")
        AgentStatus(
            api = o.optInt("api", 0),
            lamp = AgentLamp.of(lamp.getString("state")),
            leftMs = lamp.optLongOrNull("leftMs"),
            countdownMs = lamp.optLongOrNull("countdownMs"),
            lastOffReason = lamp.optStringOrNull("lastOffReason"),
            unlocked = lamp.optBoolean("unlocked", false),
            sensorMode = sensor.optString("mode", "off"),
            person = sensor.optBoolean("person", false),
            mask = sensor.optInt("mask", 0),
            lastSeenAgoMs = sensor.optLongOrNull("lastSeenAgoMs"),
            sensorHealthy = sensor.optBoolean("healthy", true),
            estop = robot.optBooleanOrNull("estop"),
            moving = robot.optBoolean("moving", false),
            battery = robot.optIntOrNull("battery"),
            controller = o.optStringOrNull("controller"),
            faults = if (faults == null) emptyList() else (0 until faults.length()).map { faults.getString(it) },
            voiceVolume = voice.optIntOrNull("volume"),
            voiceFloor = voice.optIntOrNull("floor"),
            wifi = wifi?.let { HeadWifi(it.optIntOrNull("rssi"), it.optIntOrNull("mhz"), it.optBoolean("weak", false)) },
            charge = charge?.optStringOrNull("state")?.let { AgentCharge(it, charge.optStringOrNull("reason"), charge.optIntOrNull("lowBattery")) },
            task = task?.let(::parseTask),
            lastReport = o.optJSONObject("lastReport")?.let { TaskReport.listFromJson("[$it]").firstOrNull() },
            routinesUnlocked = routines?.optBoolean("unlocked", false),
            phoneAwayCarriesOn = routines?.optBoolean("phoneAwayCarriesOn", false) ?: false,
        )
    }.getOrNull()

    /** `task` in /v1/state: the routine the robot is running. Null if it can't be read. */
    private fun parseTask(o: JSONObject): AgentTask? = runCatching {
        val results = o.optJSONArray("results")
        AgentTask(
            id = o.optString("id", ""),
            name = o.optString("name", "Routine"),
            stage = o.getString("stage"),
            problem = o.optStringOrNull("problem"),
            round = o.optInt("round", 1),
            rounds = o.optInt("rounds", 1),
            stop = o.optString("stop", ""),
            number = o.optInt("number", 1),
            total = o.optInt("total", 1),
            uvLeftMs = o.optLongOrNull("uvLeftMs"),
            lampsOff = o.optBoolean("lampsOff", false),
            controller = o.optStringOrNull("controller"),
            phoneAway = o.optBoolean("phoneAway", false),
            phoneAwayMs = o.optLong("phoneAwayMs", 0),
            results = if (results == null) emptyList() else (0 until results.length()).mapNotNull { i ->
                runCatching {
                    val r = results.getJSONObject(i)
                    StopResult(r.getInt("round"), r.getString("point"), StopOutcome.valueOf(r.getString("outcome")), r.optLong("uv_ms", 0), r.optInt("person_pauses", 0))
                }.getOrNull()
            },
        )
    }.getOrNull()

    /**
     * POST /v1/task/start: [task] run on the robot, by the map's [points] (the robot can't read the
     * map) and its [pile] (null: none), as this phone ([controller]).
     */
    fun taskStartJson(task: DisinfectionTask, points: List<Waypoint>, pile: String?, requestId: String, controller: String): String =
        JSONObject()
            .put("task", DisinfectionTask.toJson(task))
            .put("points", org.json.JSONArray().apply {
                points.forEach { p -> put(JSONObject().put("name", p.name).put("x", p.pose?.x ?: JSONObject.NULL).put("y", p.pose?.y ?: JSONObject.NULL)) }
            })
            .put("pile", pile ?: JSONObject.NULL)
            .put("requestId", requestId)
            .put("controller", controller)
            .toString()

    /** The reasons in a refusal (409), fit to show; the whole body if it has none. */
    fun parseReasons(body: String): List<String> = runCatching {
        val a = JSONObject(body).getJSONArray("reasons")
        (0 until a.length()).map { a.getString(it) }
    }.getOrNull()?.takeIf { it.isNotEmpty() } ?: listOf(body.ifBlank { "no reason given" })

    fun lampOnJson(forMs: Long, countdownMs: Long, requestId: String, controller: String): String =
        JSONObject().put("forMs", forMs).put("countdownMs", countdownMs).put("requestId", requestId).put("controller", controller).toString()

    fun controllerJson(controller: String): String = JSONObject().put("controller", controller).toString()

    /** POST /v1/settings with only the voice's volume; [controller] so it's allowed during this phone's routine. */
    fun voiceVolumeJson(percent: Int, controller: String): String =
        JSONObject().put("voiceVolume", percent).put("controller", controller).toString()

    /** POST /v1/settings with only the low-battery level (R14: the robot goes to charge at it). */
    fun lowBatteryJson(percent: Int, controller: String): String =
        JSONObject().put("lowBattery", percent).put("controller", controller).toString()

    /** The level the agent settled on (it floors it), from a /v1/settings reply. */
    fun parseLowBattery(body: String): Int? = runCatching { JSONObject(body).getInt("lowBattery") }.getOrNull()

    /** The robot's auto-charge (R14), in words, or null when there's nothing to say. */
    fun chargeText(c: AgentCharge): String? = when (c.state) {
        "ok" -> null
        "going" -> "Going to charge"
        "locked" -> "Locked on this robot"
        "snoozed" -> "Snoozed"
        else -> c.reason ?: c.state
    }

    /** The head unit's signal: "-77 dBm · 5 GHz", "Unknown" when it can't read it. */
    fun wifiText(w: HeadWifi): String {
        val band = w.mhz?.let { if (it >= 5_000) "5 GHz" else "2.4 GHz" }
        return listOfNotNull(w.rssi?.let { "$it dBm" }, band, "weak".takeIf { w.weak }).joinToString(" · ").ifEmpty { "Unknown" }
    }

    /** The volume the agent settled on (it floors it), from a /v1/settings reply. */
    fun parseVoiceVolume(body: String): Int? = runCatching { JSONObject(body).getInt("voiceVolume") }.getOrNull()

    fun pairCodeJson(code: String): String = JSONObject().put("code", code.filter(Char::isDigit)).toString()

    /** The token in a `/v1/pair` reply, or null. Never logged. */
    fun parseToken(body: String): String? = runCatching { JSONObject(body).getString("token") }.getOrNull()?.takeIf { it.isNotBlank() }

    /** A `/v1/pair` refusal because the code ran out (or none is on the screen): offer a new one. */
    fun codeExpired(body: String): Boolean = runCatching { JSONObject(body).optBoolean("expired", false) }.getOrDefault(false)

    fun reasonJson(reason: String): String = JSONObject().put("reason", reason).toString()

    /** Hard faults now, in words, the ones that would refuse a run. */
    fun blocking(faults: List<String>): List<String> = faults.filter { it !in NOT_BLOCKING }.map(::faultText)

    private fun JSONObject.optStringOrNull(k: String): String? = if (!has(k) || isNull(k)) null else getString(k)
    private fun JSONObject.optLongOrNull(k: String): Long? = if (!has(k) || isNull(k)) null else getLong(k)
    private fun JSONObject.optIntOrNull(k: String): Int? = if (!has(k) || isNull(k)) null else getInt(k)
    private fun JSONObject.optBooleanOrNull(k: String): Boolean? = if (!has(k) || isNull(k)) null else getBoolean(k)
}

/** GET /v1/info. [host] is the robot's ROS hostname, as the agent read it over the serial line; null until it has. */
data class AgentInfo(val api: Int, val version: String, val host: String?, val lampsUnlocked: Boolean, val pairingRequired: Boolean = false) {
    val fake: Boolean get() = version == RobotAgent.FAKE_VERSION
}

/** What the agent's lamps are doing (`lamp.state`). */
enum class AgentLamp {
    OFF, COUNTDOWN, ON,

    /** Something moved: lamps held off until all is quiet (spec R9), then on, or after a short spoken warning. */
    PERSON;

    companion object {
        fun of(s: String) = when (s) {
            "countdown" -> COUNTDOWN
            "on" -> ON
            "paused_person" -> PERSON
            else -> OFF
        }
    }
}

/**
 * GET /v1/state. [leftMs] is the lamp time still to run (in the countdown too); [countdownMs] the
 * countdown left. [controller] is the phone whose run it is; null when off, or started on the robot.
 */
data class AgentStatus(
    val api: Int,
    val lamp: AgentLamp,
    val leftMs: Long?,
    val countdownMs: Long?,
    val lastOffReason: String?,
    val unlocked: Boolean,
    val sensorMode: String,
    val person: Boolean,
    val mask: Int,
    val lastSeenAgoMs: Long?,
    val sensorHealthy: Boolean,
    val estop: Boolean?,
    val moving: Boolean,
    val battery: Int?,
    val controller: String?,
    val faults: List<String>,
    /** The spoken warnings' loudness, percent, and the agent's floor under it. Null from an agent without the setting. */
    val voiceVolume: Int? = null,
    val voiceFloor: Int? = null,
    /** The head unit's Wi-Fi signal (E10); null from an agent that doesn't report it. */
    val wifi: HeadWifi? = null,
    /** The robot's auto-charge (R14); null from an agent without it. */
    val charge: AgentCharge? = null,
    /** The routine the robot is running (Phase 2), whichever phone started it; null when none. */
    val task: AgentTask? = null,
    /** How the robot's last routine went; null when none has ended, or from an agent without routines. */
    val lastReport: TaskReport? = null,
    /** Routines may run on this robot (its lock file); null from an agent without routines. */
    val routinesUnlocked: Boolean? = null,
    /** R28: a routine carries on when its phone goes away. */
    val phoneAwayCarriesOn: Boolean = false,
)

/**
 * `task` in /v1/state: a routine run by the robot itself. [stage] is the agent's word (`checking`,
 * `travelling`, `disinfecting`, `lamps_off`, `retrying`, `paused`, `docking`); [stop] the point it's
 * at or going to, [number] of [total] across all rounds. [controller]: the phone holding its lease;
 * [phoneAway]: that phone has gone quiet and the routine carries on without it.
 */
data class AgentTask(
    val id: String,
    val name: String,
    val stage: String,
    val problem: String?,
    val round: Int,
    val rounds: Int,
    val stop: String,
    val number: Int,
    val total: Int,
    val uvLeftMs: Long?,
    val lampsOff: Boolean,
    val controller: String?,
    val phoneAway: Boolean,
    val phoneAwayMs: Long,
    val results: List<StopResult>,
) {
    val taskStage: TaskStage get() = when (stage) {
        "travelling" -> TaskStage.Travelling
        "disinfecting" -> TaskStage.Disinfecting
        "lamps_off" -> TaskStage.LampsOff
        "retrying" -> TaskStage.Retrying(problem ?: "Can't reach $stop", 0)
        "paused" -> TaskStage.Paused
        "docking" -> TaskStage.Docking
        else -> TaskStage.Checking
    }
}

/** `wifi` in /v1/state: the head unit's signal. [rssi] in dBm, null when unknown; [weak] as the agent judges it. */
data class HeadWifi(val rssi: Int?, val mhz: Int?, val weak: Boolean)

/**
 * `charge` in /v1/state. [state]: `ok`, `needed`, `locked`, `warning` (about to set off), `going`,
 * `failed`, `snoozed`. [reason] fit to show; [lowBattery] the level it goes at.
 */
data class AgentCharge(val state: String, val reason: String?, val lowBattery: Int?) {
    /** The robot is about to drive to its charger by itself, or is on its way. */
    val leaving: Boolean get() = state == "warning" || state == "going"
}

/**
 * The Robot Controller as the screens see it (see [RemoteState.agent]). Null there only before
 * the phone knows which robot it's on: every robot's lamps come on through its Robot Controller alone.
 */
data class AgentView(
    /** Where it answers, "192.168.0.50:8765"; null while not found. */
    val address: String?,
    val info: AgentInfo?,
    /** The last state read; null until one has been. */
    val status: AgentStatus?,
    /** Answering now. */
    val reachable: Boolean,
    /** The app is looking for it. */
    val searching: Boolean,
    /** Not answering yet, and the phone hasn't given up: "Connecting to Robot Controller". Else, not answering: Retry. */
    val connecting: Boolean = false,
    /** The in-app fake of DEMO mode, not one on the network. */
    val inApp: Boolean = false,
    /** This phone holds a pairing token for it. */
    val paired: Boolean = false,
    /** Where pairing is up to (see [DriveController.askPairCode]). */
    val pairStep: PairStep = PairStep.Idle,
) {
    /** It wants a token and this phone has none: UV and settings would be refused. */
    val needsPairing: Boolean get() = info?.pairingRequired == true && !paired
}

/** Pairing this phone with the Robot Controller, as the pairing dialog shows it. */
sealed interface PairStep {
    data object Idle : PairStep
    /** Asking the robot to show a code. */
    data object Asking : PairStep
    /** The code is on the robot's screen: type it in. */
    data object CodeShown : PairStep
    data object Sending : PairStep
    /** "This code has expired", with Refresh. */
    data object Expired : PairStep
    data object Paired : PairStep
    data class Problem(val text: String) : PairStep
}

/** A Robot Controller found on the network, at [address] ("ip:port"). */
data class FoundAgent(val address: String, val info: AgentInfo)

/** One `_robotagent._tcp` service the phone's NSD resolved. [host] is its TXT record: missing if the agent started before ROS answered. */
data class NsdHit(val address: String, val host: String?)

/**
 * Finds a robot's Robot Controller: at its last known address first (one quick question), then by NSD
 * (`_robotagent._tcp`), then by asking every address on the phone's network. Whatever answers
 * must say, in /v1/info, that it is on this robot ([hostname], the navigation computer's own
 * name), so an agent can never be taken for another robot's. DEMO mode takes only a fake agent
 * (see [RobotAgent.FAKE_VERSION]), so a phone in DEMO can never reach a real robot's lamps.
 */
object AgentFinder {

    fun accepts(info: AgentInfo, hostname: String?, demo: Boolean): Boolean =
        if (demo) info.fake else !info.fake && hostname != null && info.host == hostname

    suspend fun find(
        hostname: String?,
        demo: Boolean,
        last: String?,
        nsd: suspend () -> List<NsdHit>,
        scan: () -> List<String>,
        info: suspend (address: String) -> AgentInfo?,
    ): FoundAgent? {
        if (!demo && hostname == null) return null
        suspend fun check(address: String): FoundAgent? =
            info(address)?.takeIf { accepts(it, hostname, demo) }?.let { FoundAgent(address, it) }
        last?.let { a -> check(a)?.let { return it } }
        // A TXT host naming another robot needn't be asked; a missing one must be.
        for (hit in nsd()) {
            if (hit.address == last || (!demo && hit.host != null && hit.host != hostname)) continue
            check(hit.address)?.let { return it }
        }
        val found = java.util.concurrent.ConcurrentHashMap<String, FoundAgent>()
        val hosts = scan().filter { it != last }
        val first = RobotFinder.find(hosts) { a ->
            check(a)?.let { found[a] = it; FoundRobot(a, it.info.version, it.info.host) }
        }.firstOrNull() ?: return null
        return found[first.host]
    }
}
