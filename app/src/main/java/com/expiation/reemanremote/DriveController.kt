package com.expiation.reemanremote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min

// ---------------------------------------------------------------------- state

enum class EStop { PRESSED, RELEASED }

/** Is the robot answering? Kept separate from [StepPhase]: a step keeps being tracked if the link drops. */
sealed interface Link {
    data class Disconnected(val lastError: String?) : Link

    /** Answering polls. [estop] is null until the first /reeman/base_encode after (re)connecting. */
    data class Connected(val estop: EStop?) : Link
}

enum class Move(val label: String) {
    FORWARD("Forward 0.5 m"), BACK("Back 0.5 m"), LEFT("Left 90°"), RIGHT("Right 90°"), AROUND("Turn 180°")
}

/**
 * One fixed command and how the step lock tracks it. The defaults suit a d-pad step;
 * docking overrides them (see [DriveController.dock]).
 *
 * @param stillPolls polls (~0.3 s each) of stillness, after motion, that mean the step is over
 * @param noMotionMs how long to wait for any motion before giving up
 * @param capMs hard cap, in case the robot never settles
 */
data class Step(
    val label: String,
    val path: String,
    val json: String,
    val isTurn: Boolean,
    val estimateMs: Long,
    val stillPolls: Int = DriveController.STILL_POLLS_TO_FINISH,
    val noMotionMs: Long = estimateMs + 2000,
    val capMs: Long = estimateMs + 12000,
    val stallHint: String = "Obstacle in the way, or command ignored?",
)

/** How the step lock decides a step is over (see [DriveController.trackStep]). */
data class Tracking(
    val noMotionDeadline: Long,
    val hardCap: Long,
    val sawMotion: Boolean = false,
    val stillPolls: Int = 0,
)

/** The step lock. Only [StepPhase.Idle] accepts a new step; STOP is accepted in every phase. */
sealed interface StepPhase {
    data object Idle : StepPhase
    data class Stepping(val step: Step, val track: Tracking) : StepPhase

    /** STOP was pressed during a step; waiting (with shortened deadlines) for the robot to be still. */
    data class Stopping(val step: Step, val track: Tracking) : StepPhase
}

data class DemoStatus(val estopPressed: Boolean, val offline: Boolean, val blockPending: Boolean)

/** Everything the screen shows. Immutable; a new one is published on every change. */
data class RemoteState(
    val demo: Boolean,
    val host: String,
    val link: Link,
    val version: String?,
    val battery: Int?,
    val vx: Double,
    val vth: Double,
    val armed: Boolean,
    val phase: StepPhase,
    val demoStatus: DemoStatus?,
    val log: List<String>,
) {
    val connected: Boolean get() = link is Link.Connected
    val estop: EStop? get() = (link as? Link.Connected)?.estop
    val busy: Boolean get() = phase != StepPhase.Idle
    val busyLabel: String? get() = when (phase) {
        StepPhase.Idle -> null
        is StepPhase.Stepping -> phase.step.label
        is StepPhase.Stopping -> phase.step.label
    }

    /** The drive interlock: connected, e-stop known and released, armed, no step running. */
    val canDrive: Boolean get() = estop == EStop.RELEASED && armed && !busy
}

// ---------------------------------------------------------------------- controller

/**
 * Talks to the robot (or the demo robot) and enforces the drive rules
 * (REEMAN_HANDOFF.txt sections 5-7):
 *  - every motion is a fixed, pre-planned step (/cmd/move or /cmd/turn); never /cmd/speed
 *  - one step at a time; nothing but STOP is sent while a step runs
 *  - STOP is a hard stop, for emergencies; steps end on their own
 *
 * Plain Kotlin with no Android dependencies, so it is unit tested on the JVM.
 * Call everything from [scope]'s thread (the main thread in the app).
 *
 * @param now monotonic milliseconds
 */
class DriveController(
    private val scope: CoroutineScope,
    private val liveApi: RobotApi,
    val demoRobot: FakeRobot,
    demo: Boolean,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val demoApi = RobotApi.demo(demoRobot)
    private var demo = demo
    private val api: RobotApi get() = if (demo) demoApi else liveApi

    private var link: Link = Link.Disconnected(null)
    private var failCount = 0
    private var version: String? = null
    private var battery: Int? = null
    private var chargeFlag: Int? = null
    private var vx = 0.0
    private var vth = 0.0
    private var armed = false
    private var phase: StepPhase = StepPhase.Idle

    private var pollJob: Job? = null
    private var connectJob: Job? = null

    private val logLines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    // ------------------------------------------------------------------ lifecycle

    fun resume() {
        if (pollJob == null) pollJob = scope.launch { pollLoop() }
    }

    /** Leaving the app stops polling and always disarms. A running step finishes by itself. */
    fun pause() {
        pollJob?.cancel()
        pollJob = null
        setArmed(false)
    }

    fun setArmed(on: Boolean) {
        if (armed == on) return
        armed = on
        log(if (on) "Drive enabled" else "Drive disabled")
        publish()
    }

    /** Returns false (and changes nothing) if a step is running. */
    fun setDemo(on: Boolean): Boolean {
        if (on == demo) return true
        if (phase != StepPhase.Idle) {
            // STOP goes to the selected robot, so never switch away from one that is moving.
            log("Wait for the current step to finish before switching mode.")
            publish()
            return false
        }
        setArmed(false) // a mode change always disarms
        demo = on
        switchRobot()
        log(
            if (on) "DEMO MODE ON: simulated robot, nothing is sent to the real robot"
            else "DEMO mode OFF: live robot at ${liveApi.host}"
        )
        testConnection(null)
        return true
    }

    /**
     * Checks the robot answers. In live mode [hostText] (if given) becomes the robot address;
     * returns the cleaned-up address so the caller can save it.
     */
    fun testConnection(hostText: String?): String? {
        var saved: String? = null
        if (!demo && hostText != null) {
            val host = hostText.trim().ifEmpty { DEFAULT_HOST }
                .replaceFirst(Regex("^https?://"), "").replace(Regex("/+$"), "")
            if (host != liveApi.host) {
                liveApi.host = host
                switchRobot()
            }
            saved = host
        }
        val a = api
        log("Testing ${if (demo) "DEMO robot" else a.host} …")
        publish()
        connectJob?.cancel()
        connectJob = scope.launch {
            val r = a.get("/reeman/current_version")
            if (r.ok) {
                version = runCatching { JSONObject(r.body).optString("version", r.body) }.getOrDefault(r.body)
                log("Robot answered: firmware $version")
            } else {
                if (link is Link.Disconnected) link = Link.Disconnected(r.error)
                log("Test failed: ${r.error}")
            }
            publish()
        }
        return saved
    }

    /** Forget everything about the old robot. Cancelling the jobs drops any answers still in flight. */
    private fun switchRobot() {
        connectJob?.cancel()
        link = Link.Disconnected(null)
        failCount = 0
        version = null
        battery = null
        chargeFlag = null
        vx = 0.0
        vth = 0.0
        if (pollJob != null) {
            pollJob?.cancel()
            pollJob = scope.launch { pollLoop() }
        }
        publish()
    }

    // ------------------------------------------------------------------ polling

    private suspend fun CoroutineScope.pollLoop() {
        var tick = 0
        while (isActive) {
            val started = now()
            val a = api
            val withBase = tick++ % BASE_EVERY == 0
            val s = a.get("/reeman/speed")
            val b = if (withBase) a.get("/reeman/base_encode") else null
            onPoll(s, b)
            delay((POLL_MS - (now() - started)).coerceAtLeast(0))
        }
    }

    private fun onPoll(s: RobotApi.Result, b: RobotApi.Result?) {
        var speedValid = false
        if (s.ok) {
            runCatching {
                val o = JSONObject(s.body)
                vx = o.optDouble("vx", 0.0)
                vth = o.optDouble("vth", 0.0)
                speedValid = true
            } // unexpected body: keep previous values
            if (link is Link.Disconnected) {
                link = Link.Connected(estop = null)
                log("Connected")
            }
            failCount = 0
        } else {
            failCount++
            when (link) {
                is Link.Connected -> if (failCount >= FAILS_BEFORE_DISCONNECT) {
                    link = Link.Disconnected(s.error)
                    log("Lost connection: ${s.error}")
                }
                is Link.Disconnected -> link = Link.Disconnected(s.error)
            }
        }

        if (b != null && b.ok) {
            runCatching {
                val o = JSONObject(b.body)
                if (o.has("battery")) battery = o.getInt("battery")
                if (o.has("chargeFlag")) chargeFlag = o.getInt("chargeFlag")
                val l = link
                if (o.has("emergencyButton") && l is Link.Connected) {
                    val e = if (o.getInt("emergencyButton") == 0) EStop.PRESSED else EStop.RELEASED
                    if (l.estop != null && l.estop != e) {
                        log(if (e == EStop.PRESSED) "E-stop PRESSED" else "E-stop released")
                    }
                    link = Link.Connected(e)
                }
                // chargeFlag is stale on this firmware, so it is never shown as live state; only
                // reported once a dock finishes, after motion has refreshed it (see dockReport).
            }
        }

        trackStep(speedValid)
        publish()
    }

    // ------------------------------------------------------------------ commands

    fun drive(move: Move) {
        if (!snapshot().canDrive) return
        val step = when (move) {
            Move.FORWARD, Move.BACK -> {
                val forward = move == Move.FORWARD
                val speed = min(if (forward) FORWARD_SPEED else BACKWARD_SPEED, MAX_LINEAR)
                Step(
                    move.label, "/cmd/move",
                    String.format(Locale.US, """{"distance":%d,"direction":%d,"speed":%.2f}""",
                        STEP_CM, if (forward) 1 else 0, speed),
                    isTurn = false,
                    estimateMs = (STEP_CM / 100.0 / speed * 1000).toLong() + RAMP_ALLOWANCE_MS,
                )
            }
            Move.LEFT, Move.RIGHT, Move.AROUND -> {
                val degrees = if (move == Move.AROUND) 180 else 90
                val speed = min(TURN_SPEED, MAX_ANGULAR)
                Step(
                    move.label, "/cmd/turn",
                    String.format(Locale.US, """{"direction":%d,"angle":%d,"speed":%.2f}""",
                        if (move == Move.RIGHT) 0 else 1, degrees, speed),
                    isTurn = true,
                    estimateMs = (Math.toRadians(degrees.toDouble()) / speed * 1000).toLong() + RAMP_ALLOWANCE_MS,
                )
            }
        }

        send(step)
    }

    /**
     * Starts the firmware's own docking routine (/cmd/charge type 0): the robot finds the
     * charging pile nearby and reverses onto it. It moves the robot, so it takes the same
     * interlock and step lock as the d-pad. The firmware picks the docking speed.
     */
    fun dock() {
        if (!snapshot().canDrive) return
        send(
            Step(
                "Dock charger", DOCK_PATH, DOCK_START, isTurn = false,
                estimateMs = 30_000,     // GUESS: never timed on the real robot
                stillPolls = 10,         // ~3 s: the routine may pause between aligning and reversing
                noMotionMs = 8_000,
                capMs = 120_000,
                stallHint = "Already docked, or no charging pile within range?",
            )
        )
    }

    private fun send(step: Step) {
        // Lock before sending, so a double-tap can't send a second step.
        val t = now()
        phase = StepPhase.Stepping(step, Tracking(t + step.noMotionMs, t + step.capMs))
        log("${step.label}: sending ${step.path} ${step.json}")
        publish()

        val a = api
        scope.launch {
            val r = a.post(step.path, step.json)
            val rejected = if (r.ok) robotError(r.body) else null
            when {
                !r.ok -> finishStep(step, "${step.label}: FAILED ${r.error}")
                rejected != null -> finishStep(step, "${step.label}: robot rejected: $rejected")
                else -> log("${step.label}: accepted ${r.body}")
            }
            publish()
        }
    }

    /**
     * Always sends, in every phase. Sends both stop bodies, the running step's type first.
     * During a dock it cancels docking first: the firmware's routine may not obey the stop bodies.
     */
    fun stop() {
        val p = phase
        val running = (p as? StepPhase.Stepping)?.step ?: (p as? StepPhase.Stopping)?.step
        val turnFirst = running?.isTurn ?: false
        val docking = running?.path == DOCK_PATH
        log("STOP pressed")
        val t = now()
        fun shorten(tr: Tracking) = tr.copy(
            noMotionDeadline = min(tr.noMotionDeadline, t + 4000),
            hardCap = min(tr.hardCap, t + 6000),
        )
        phase = when (p) {
            StepPhase.Idle -> p
            is StepPhase.Stepping -> StepPhase.Stopping(p.step, shorten(p.track))
            is StepPhase.Stopping -> p.copy(track = shorten(p.track))
        }
        publish()

        val a = api
        scope.launch {
            val cancel = if (docking) a.post(DOCK_PATH, DOCK_CANCEL) else null
            val first = if (turnFirst) a.post("/cmd/turn", TURN_STOP) else a.post("/cmd/move", MOVE_STOP)
            val second = if (turnFirst) a.post("/cmd/move", MOVE_STOP) else a.post("/cmd/turn", TURN_STOP)
            log(
                "STOP: " + if (first.ok || second.ok || cancel?.ok == true) "sent"
                else "FAILED ${first.error}. USE THE PHYSICAL E-STOP"
            )
            publish()
        }
    }

    // ------------------------------------------------------------------ step lock

    /**
     * A step is over when motion was seen and the robot has been still for
     * [STILL_POLLS_TO_FINISH] polls. Fallbacks: no motion by the no-motion deadline,
     * or the hard cap.
     */
    private fun trackStep(speedValid: Boolean) {
        val p = phase
        val (step, before) = when (p) {
            StepPhase.Idle -> return
            is StepPhase.Stepping -> p.step to p.track
            is StepPhase.Stopping -> p.step to p.track
        }
        var tr = before
        if (speedValid) {
            val moving = abs(vx) > MOVING_VX || abs(vth) > MOVING_VTH
            tr = when {
                moving -> tr.copy(sawMotion = true, stillPolls = 0)
                tr.sawMotion -> tr.copy(stillPolls = tr.stillPolls + 1)
                else -> tr
            }
        }
        val t = now()
        val stopping = p is StepPhase.Stopping
        val message = when {
            tr.sawMotion && tr.stillPolls >= step.stillPolls ->
                if (stopping) "${step.label}: stopped"
                else "${step.label}: done" + if (step.path == DOCK_PATH) dockReport() else ""
            !tr.sawMotion && t > tr.noMotionDeadline ->
                if (stopping) "${step.label}: stopped before it moved"
                else "${step.label}: no motion seen. ${step.stallHint}"
            t > tr.hardCap -> "${step.label}: timed out waiting for the robot to stop"
            else -> null
        }
        phase = when {
            message != null -> {
                log(message)
                StepPhase.Idle
            }
            p is StepPhase.Stepping -> p.copy(track = tr)
            else -> (p as StepPhase.Stopping).copy(track = tr)
        }
    }

    /** chargeFlag is stale while the robot sits still, but motion refreshes it, so after a dock it is worth a line. */
    private fun dockReport() = ". Robot reports: " + when (chargeFlag) {
        2 -> "charging at the pile."
        8 -> "still connecting to the pile."
        9 -> "no charging pile found."
        null -> "no charging status."
        else -> "not charging (chargeFlag $chargeFlag). Check it is on the pile."
    }

    /** Ends [step] early, unless the lock has already moved on from it. */
    private fun finishStep(step: Step, message: String) {
        val current = when (val p = phase) {
            StepPhase.Idle -> null
            is StepPhase.Stepping -> p.step
            is StepPhase.Stopping -> p.step
        }
        log(message)
        if (current === step) phase = StepPhase.Idle
    }

    // ------------------------------------------------------------------ demo controls

    fun demoToggleEstop() {
        val press = !demoRobot.isEstopPressed
        demoRobot.setEstopPressed(press)
        log("Simulated e-stop ${if (press) "pressed" else "released"}")
        publish()
    }

    fun demoToggleWifi() {
        demoRobot.isOffline = !demoRobot.isOffline
        log(if (demoRobot.isOffline) "Simulated Wi-Fi drop" else "Simulated Wi-Fi restored")
        publish()
    }

    fun demoBlockNextStep() {
        demoRobot.blockNextStep()
        log("Simulated obstacle: the next step will not start")
        publish()
    }

    fun demoDrainBattery() {
        demoRobot.drainBattery()
        log("Simulated battery now ${demoRobot.batteryPercent()}%")
        publish()
    }

    fun demoReset() {
        if (phase != StepPhase.Idle) {
            log("Wait for the current step to finish before resetting the DEMO robot.")
        } else {
            demoRobot.reset()
            log("DEMO robot reset: centre of the room, no faults")
        }
        publish()
    }

    // ------------------------------------------------------------------ output

    /** Adds a line to the activity log (tagged [DEMO] in demo mode). */
    fun note(line: String) {
        log(line)
        publish()
    }

    private fun log(line: String) {
        logLines.addFirst("${clock.format(Date())}  ${if (demo) "[DEMO] " else ""}$line")
        while (logLines.size > LOG_LINES) logLines.removeLast()
    }

    private fun publish() {
        _state.value = snapshot()
    }

    private fun snapshot() = RemoteState(
        demo = demo,
        host = liveApi.host,
        link = link,
        version = version,
        battery = battery,
        vx = vx,
        vth = vth,
        armed = armed,
        phase = phase,
        demoStatus = if (demo) DemoStatus(demoRobot.isEstopPressed, demoRobot.isOffline, demoRobot.isBlockNextPending) else null,
        log = logLines.toList(),
    )

    companion object {
        const val DEFAULT_HOST = "192.168.1.228"

        // ---- Beta drive profile. Hard limits: nothing in the app goes above these. ----
        const val STEP_CM = 50
        const val FORWARD_SPEED = 0.3   // m/s
        const val BACKWARD_SPEED = 0.2  // m/s (lidar sees less behind)
        const val TURN_SPEED = 0.4      // rad/s (~23 deg/s)
        const val MAX_LINEAR = 0.3      // m/s
        const val MAX_ANGULAR = 0.5     // rad/s

        // ---- Polling ----
        const val POLL_MS = 300L        // /reeman/speed
        const val BASE_EVERY = 5        // /reeman/base_encode every 5th poll (~1.5 s)
        const val FAILS_BEFORE_DISCONNECT = 3

        // ---- "Is the robot still moving?" ----
        const val MOVING_VX = 0.02      // m/s
        const val MOVING_VTH = 0.03     // rad/s
        const val STILL_POLLS_TO_FINISH = 3 // ~0.9 s of stillness = step finished
        const val RAMP_ALLOWANCE_MS = 2500L // accel + decel + ~200 ms latency

        private const val LOG_LINES = 14
        private const val MOVE_STOP = """{"distance":0,"direction":1,"speed":0}"""
        private const val TURN_STOP = """{"direction":1,"angle":0,"speed":0}"""

        // ---- Docking (REEMAN SLAM WEB API, "Navigation charging") ----
        const val DOCK_PATH = "/cmd/charge"
        // type 0 = dock with a pile close by, 1 = cancel docking. (2 = drive to the pile
        // first: not used, the loaded map is from another building.) The robot's pile point is
        // named "charging_pile".
        private const val DOCK_START = """{"type":0,"point":"charging_pile"}"""
        private const val DOCK_CANCEL = """{"type":1,"point":"charging_pile"}"""

        /** The firmware reports errors as {"error":"...","error_code":"009"}. */
        private fun robotError(body: String): String? {
            if (body.isEmpty()) return null
            return runCatching {
                val o = JSONObject(body)
                if (o.has("error_code") || o.has("error")) {
                    o.optString("error_code", "?") + " " + o.optString("error", "")
                } else null
            }.getOrNull()
        }
    }
}
