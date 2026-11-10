package com.expiation.reemanremote

import org.json.JSONObject

/**
 * The Robot Agent: our own safety app on the robot's head unit. Once a robot has one, it alone
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
        )
    }.getOrNull()

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

    /** Something moved: lamps off until all is clear, then a full countdown again. */
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
)

/**
 * The robot's safety app as the screens see it (see [RemoteState.agent]). Null there for a robot
 * that has never been seen with one: the phone switches the lamps itself, as before.
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
