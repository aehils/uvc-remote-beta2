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
import kotlin.math.hypot
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
    /** Set for a navigation to a saved point (see [DriveController.goTo]). */
    val goal: Waypoint? = null,
    /** Sent if this dock finds no pile (see [DriveController.goToCharger]). */
    val fallback: Step? = null,
)

data class Pose(val x: Double, val y: Double, val theta: Double)

/** A point saved on the robot's map (/reeman/position). [pose] is null if the robot sent none. */
data class Waypoint(val name: String, val type: String, val pose: Pose?)

/** The disinfection points read from the robot: its saved points, without the charging pile. */
sealed interface Points {
    data object NotLoaded : Points
    data object Loading : Points
    data class Loaded(val list: List<Waypoint>) : Points
    data class Failed(val error: String) : Points
}

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

data class DemoStatus(
    val estopPressed: Boolean,
    val offline: Boolean,
    val blockPending: Boolean,
    val navigating: Boolean,
    /** Running any motion or task of its own. */
    val robotBusy: Boolean,
)

/** Everything the screen shows. Immutable; a new one is published on every change. */
data class RemoteState(
    val demo: Boolean,
    val host: String,
    val link: Link,
    val version: String?,
    val battery: Int?,
    val vx: Double,
    val vth: Double,
    val phase: StepPhase,
    val points: Points,
    val addingPoint: Boolean,
    /** The outcome of the last "add point here", shown on the points page. */
    val pointMessage: String?,
    /** STOP was sent again and again but the robot keeps moving: use the physical e-stop. */
    val runaway: Boolean,
    val demoStatus: DemoStatus?,
    val log: List<String>,
) {
    val connected: Boolean get() = link is Link.Connected
    val estop: EStop? get() = (link as? Link.Connected)?.estop
    val busy: Boolean get() = phase != StepPhase.Idle
    val running: Step? get() = when (phase) {
        StepPhase.Idle -> null
        is StepPhase.Stepping -> phase.step
        is StepPhase.Stopping -> phase.step
    }
    val busyLabel: String? get() = running?.label

    val still: Boolean get() = abs(vx) <= DriveController.MOVING_VX && abs(vth) <= DriveController.MOVING_VTH

    /** Saving the robot's spot as a point: connected, standing still, and the existing names known. */
    val canAddPoint: Boolean get() = connected && !busy && still && points is Points.Loaded && !addingPoint

    /** The drive interlock: connected, e-stop known and released, no step running, robot still. */
    val canDrive: Boolean get() = estop == EStop.RELEASED && !busy && still
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
    private var phase: StepPhase = StepPhase.Idle
    private var points: Points = Points.NotLoaded
    private var addingPoint = false
    private var pointMessage: String? = null

    // Read only while navigating to a point. planActive: true = the robot has a plan (still on
    // its way, even if it is waiting), false = no plan (007), null = not known.
    private var planActive: Boolean? = null
    private var navPose: Pose? = null

    private var pollJob: Job? = null
    private var connectJob: Job? = null
    private var pointsJob: Job? = null

    /** Watching the robot after STOP (see [guardStop]). */
    private data class StopGuard(val pressedAt: Long, val until: Long, val lastSent: Long, val resends: Int)
    private var stopGuard: StopGuard? = null
    private var runaway = false
    private var ownMotionNoted = false
    private var addJob: Job? = null
    private var chargerJob: Job? = null

    private val logLines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    // ------------------------------------------------------------------ lifecycle

    fun resume() {
        if (pollJob == null) pollJob = scope.launch { pollLoop() }
    }

    /** Leaving the app stops polling. A running step finishes by itself. */
    fun pause() {
        pollJob?.cancel()
        pollJob = null
        chargerJob?.cancel()
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
        pointsJob?.cancel()
        addJob?.cancel()
        chargerJob?.cancel()
        points = Points.NotLoaded // the other robot has its own map and points
        addingPoint = false
        pointMessage = null
        stopGuard = null
        runaway = false
        ownMotionNoted = false
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
            val nav = withBase && snapshot().running?.goal != null
            val plan = if (nav) a.get(PLAN_PATH) else null
            val pose = if (nav) a.get("/reeman/pose") else null
            onPoll(s, b, plan, pose)
            delay((POLL_MS - (now() - started)).coerceAtLeast(0))
        }
    }

    private fun onPoll(s: RobotApi.Result, b: RobotApi.Result?, plan: RobotApi.Result?, pose: RobotApi.Result?) {
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

        if (plan != null) {
            // The API documents {"coordinates":[...]} during a trip; only 007 has been seen on the real robot.
            planActive = when (errorCode(plan.body)) {
                null -> if (plan.ok) true else null
                NO_PLAN -> false
                else -> null
            }
        }
        if (pose != null && pose.ok) {
            runCatching {
                val o = JSONObject(pose.body)
                navPose = Pose(o.getDouble("x"), o.getDouble("y"), o.optDouble("theta", 0.0))
            }
        }

        trackStep(speedValid)
        guardStop(speedValid)
        noticeOwnMotion(speedValid)
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
     * GO TO CHARGER: docks straight away if the charging pile is close, otherwise drives to it
     * and docks. "Close" is measured on the map: the robot's pose (/reeman/pose) against the
     * pile's saved point (/reeman/position), within [DOCK_NEAR_M].
     *  - close: /cmd/charge type 0, the docking routine. If that finds no pile (no motion, or
     *    chargeFlag 9), it falls back to driving there (see [trackStep]).
     *  - far, or either position unknown: /cmd/charge type 2, drive to the pile, then dock.
     * Docking is never tried from far away: what the real routine does then is unknown.
     */
    fun goToCharger() {
        if (!snapshot().canDrive || chargerJob?.isActive == true) return
        log("Go to charger: checking how far the charging pile is …")
        publish()
        val a = api
        chargerJob = scope.launch {
            val all = a.get(POINTS_PATH).takeIf { it.ok }?.let { r -> runCatching { parseWaypoints(r.body) }.getOrNull() }
            val pile = all?.firstOrNull { isPile(it) }?.pose
            val here = a.get("/reeman/pose").takeIf { it.ok }?.let { r ->
                runCatching { JSONObject(r.body).let { Pose(it.getDouble("x"), it.getDouble("y"), 0.0) } }.getOrNull()
            }
            val d = if (pile != null && here != null) hypot(here.x - pile.x, here.y - pile.y) else null
            // The robot may have moved or been e-stopped while the app was asking.
            if (!snapshot().canDrive) {
                log("Go to charger: not sent (e-stop pressed, or the robot is moving)")
                publish()
                return@launch
            }
            val drive = chargeNavStep()
            when {
                d == null -> {
                    log("Go to charger: couldn't tell where the pile is, so driving there first")
                    send(drive)
                }
                d <= DOCK_NEAR_M -> {
                    log(fmt("Go to charger: pile %.1f m away, docking straight away", d))
                    // At the pile already: a failed dock means "already docked", not "too far".
                    send(dockStep(fallback = if (d > AT_PILE_M) drive else null))
                }
                else -> {
                    log(fmt("Go to charger: pile %.1f m away, driving there first", d))
                    send(drive)
                }
            }
        }
    }

    /** The firmware's own docking routine (/cmd/charge type 0): finds the pile nearby and reverses onto it. */
    private fun dockStep(fallback: Step?) = Step(
        "Go to charger (docking)", DOCK_PATH, DOCK_START, isTurn = false,
        estimateMs = 30_000,     // GUESS: never timed on the real robot
        stillPolls = 10,         // ~3 s: the routine may pause between aligning and reversing
        noMotionMs = 8_000,
        capMs = 120_000,
        stallHint = "Already docked, or no charging pile within range?",
        fallback = fallback,
    )

    /**
     * /cmd/charge type 2: navigate to the pile's point, then dock. The lock holds while the robot
     * has a plan, and a pause between arriving and docking is allowed for.
     */
    private fun chargeNavStep() = Step(
        "Go to charger", DOCK_PATH, CHARGE_NAV, isTurn = false,
        estimateMs = 90_000,     // unknown: depends on the route
        stillPolls = 17,         // ~5 s: GUESS, room for a pause between the trip and docking
        noMotionMs = 15_000,
        capMs = 15 * 60_000,
        stallHint = "Already on the pile, or no route to it?",
        goal = Waypoint(PILE_POINT, "charge", null),
    )

    /** Reads the robot's saved points (/reeman/position) for the Tasks tab. Read-only: safe at any time. */
    fun loadPoints() {
        if (points == Points.Loading) return
        points = Points.Loading
        publish()
        val a = api
        pointsJob?.cancel()
        pointsJob = scope.launch {
            val r = a.get(POINTS_PATH)
            val rejected = if (r.ok) robotError(r.body) else null
            val loaded = if (r.ok && rejected == null) runCatching { parsePoints(r.body) }.getOrNull() else null
            points = when {
                loaded != null -> Points.Loaded(loaded)
                !r.ok -> Points.Failed(r.error ?: "no answer")
                rejected != null -> Points.Failed("robot error $rejected")
                else -> Points.Failed("unexpected reply: ${r.body.take(80)}")
            }
            log(
                when (val p = points) {
                    is Points.Loaded -> "Loaded ${p.list.size} disinfection point(s)"
                    is Points.Failed -> "Couldn't load points: ${p.error}"
                    else -> "Points: ?"
                }
            )
            publish()
        }
    }

    /**
     * Saves the spot the robot is standing on as a new point (/cmd/position), as the API asks:
     * read the pose first, then set the point with it. Doesn't move the robot, but the robot must
     * be still so the pose (5 Hz) is where it really stands.
     * Refuses a name already in use, since setting it again would move that point.
     */
    fun addPointHere(name: String) {
        val s = snapshot()
        val clean = name.trim()
        val loaded = (s.points as? Points.Loaded)?.list
        val problem = when {
            s.addingPoint -> return
            !s.connected -> "Not connected to the robot."
            s.busy || !s.still -> "Wait for the robot to stand still."
            loaded == null -> "Load the points first."
            clean.isEmpty() -> "Give the point a name."
            clean.length > MAX_POINT_NAME -> "Use a name of $MAX_POINT_NAME characters or fewer."
            clean == PILE_POINT || loaded.any { it.name == clean } -> "A point called \"$clean\" already exists."
            else -> null
        }
        if (problem != null) {
            pointMessage = problem
            publish()
            return
        }

        addingPoint = true
        pointMessage = "Saving \"$clean\" …"
        publish()
        val a = api
        addJob = scope.launch {
            val p = a.get("/reeman/pose")
            val pose = if (p.ok) runCatching {
                val o = JSONObject(p.body)
                Pose(o.getDouble("x"), o.getDouble("y"), o.optDouble("theta", 0.0))
            }.getOrNull() else null
            val result = if (pose == null) {
                "Couldn't read where the robot is: ${p.error ?: "unexpected reply ${p.body.take(80)}"}"
            } else {
                val body = JSONObject()
                    .put("name", clean)
                    .put("type", NEW_POINT_TYPE)
                    .put("pose", JSONObject().put("x", round3(pose.x)).put("y", round3(pose.y)).put("theta", round3(pose.theta)))
                    .toString()
                log("Add point: sending $ADD_POINT_PATH $body")
                val r = a.post(ADD_POINT_PATH, body)
                val rejected = if (r.ok) robotError(r.body) else null
                when {
                    !r.ok -> "Couldn't add \"$clean\": ${r.error}"
                    rejected != null -> "Robot rejected \"$clean\": $rejected"
                    else -> String.format(Locale.US, "Added \"%s\" at x %.2f, y %.2f", clean, pose.x, pose.y)
                }
            }
            log(result)
            pointMessage = result
            addingPoint = false
            publish()
            loadPoints() // shows the new point, and confirms the robot kept it
        }
    }

    /**
     * Sends the robot to a saved point (/cmd/nav_name). The robot plans its own route and
     * speed. It moves the robot, so it takes the same interlock and step lock as the d-pad;
     * the lock also holds while the robot still has a plan (see [trackStep]).
     */
    fun goTo(point: Waypoint) {
        if (!snapshot().canDrive) return
        planActive = null
        navPose = null
        send(
            Step(
                "Go to ${point.name}", NAV_PATH, JSONObject().put("point", point.name).toString(), isTurn = false,
                estimateMs = 60_000,     // unknown: depends on the route
                stillPolls = 10,         // ~3 s, as for docking
                noMotionMs = 15_000,     // GUESS: route planning before the first motion
                capMs = 15 * 60_000,
                stallHint = "Already at the point, or no route to it?",
                goal = point,
            )
        )
    }

    private fun send(step: Step) {
        // Lock before sending, so a double-tap can't send a second step.
        val t = now()
        stopGuard = null // a new command from the user: motion is expected now
        if (step.goal != null) {
            planActive = null
            navPose = null
        }
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
     * Always sends, in every phase, and cancels everything the robot may be doing, whoever
     * started it (see [sendStop]). Then watches the robot for [STOP_GUARD_MS] and sends STOP
     * again if it moves (see [guardStop]).
     */
    fun stop() {
        val p = phase
        val turnFirst = snapshot().running?.isTurn ?: (abs(vth) > MOVING_VTH && abs(vx) <= MOVING_VX)
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
        chargerJob?.cancel() // a GO TO CHARGER still deciding must not send after STOP
        stopGuard = StopGuard(pressedAt = t, until = t + STOP_GUARD_MS, lastSent = t, resends = 0)
        runaway = false
        publish()
        sendStop(turnFirst, "STOP")
    }

    /**
     * Brakes with the stop bodies (the robot's motion type first), and cancels any navigation
     * (/cmd/cancel_goal) and any docking (/cmd/charge type 1), not only ones this app started.
     * The robot can start a task on its own (it went to charge by itself on 2026-10-05), and
     * the stop bodies alone only paused that trip: it carried on right after.
     */
    private fun sendStop(turnFirst: Boolean, label: String, report: Boolean = true) {
        val a = api
        scope.launch {
            val first = if (turnFirst) a.post("/cmd/turn", TURN_STOP) else a.post("/cmd/move", MOVE_STOP)
            val nav = a.post(CANCEL_NAV_PATH, "{}")
            // GUESS: assumed harmless while charging on the pile (not tried on the real robot).
            val dock = a.post(DOCK_PATH, DOCK_CANCEL)
            val second = if (turnFirst) a.post("/cmd/move", MOVE_STOP) else a.post("/cmd/turn", TURN_STOP)
            if (report || !(first.ok || second.ok)) log(
                "$label: " + if (first.ok || second.ok) {
                    "sent" + if (!nav.ok || !dock.ok) " (cancel ${if (!nav.ok) "navigation" else "docking"} failed: " +
                        "${(if (!nav.ok) nav else dock).error})" else ""
                } else "FAILED ${first.error}. USE THE PHYSICAL E-STOP"
            )
            publish()
        }
    }

    /**
     * After STOP, makes sure the robot stays stopped: if it moves again (or never stops) during
     * the guard, the full STOP is sent again, every [STOP_RESEND_GAP_MS] while it moves. After
     * [STOP_RESENDS] tries the screen says to use the physical e-stop (and STOP keeps being
     * sent). Sending any new command ends the guard.
     */
    private fun guardStop(speedValid: Boolean) {
        val g = stopGuard ?: return
        val t = now()
        if (t > g.until) {
            stopGuard = null
            return
        }
        if (!speedValid) return
        val moving = abs(vx) > MOVING_VX || abs(vth) > MOVING_VTH
        if (!moving) {
            if (runaway) {
                runaway = false
                log("Robot has stopped")
            }
            return
        }
        if (t - g.pressedAt < STOP_SETTLE_MS || t - g.lastSent < STOP_RESEND_GAP_MS) return
        val n = g.resends + 1
        stopGuard = g.copy(until = t + STOP_GUARD_MS, lastSent = t, resends = n) // keep watching while it moves
        if (n <= STOP_RESENDS) log("Robot moving after STOP: sending STOP again ($n)")
        if (n >= STOP_RESENDS && !runaway) {
            runaway = true
            log("ROBOT STILL MOVING AFTER STOP. USE THE PHYSICAL E-STOP")
        }
        sendStop(turnFirst = abs(vth) > MOVING_VTH && abs(vx) <= MOVING_VX, "STOP (again)", report = n <= STOP_RESENDS)
    }

    /** Notes, once each time, motion this app didn't ask for (for example the robot going to charge by itself). */
    private fun noticeOwnMotion(speedValid: Boolean) {
        if (!speedValid) return
        val moving = abs(vx) > MOVING_VX || abs(vth) > MOVING_VTH
        if (moving && phase == StepPhase.Idle && stopGuard == null && !ownMotionNoted) {
            ownMotionNoted = true
            log("Robot is moving, but not by this app. Press STOP to stop it.")
        }
        if (!moving) ownMotionNoted = false
    }

    // ------------------------------------------------------------------ step lock

    /**
     * A step is over when motion was seen and the robot has been still for
     * [STILL_POLLS_TO_FINISH] polls. Fallbacks: no motion by the no-motion deadline,
     * or the hard cap. A navigation can pause on its way (waiting for a person to pass),
     * so stillness only ends it once the robot has no plan; after STOP, stillness is enough.
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
        val onTheWay = !stopping && step.goal != null && planActive == true
        val message = when {
            tr.sawMotion && tr.stillPolls >= step.stillPolls && !onTheWay -> when {
                stopping -> "${step.label}: stopped"
                step.path == DOCK_PATH -> "${step.label}: done" + dockReport()
                step.goal != null -> "${step.label}: " + navReport(step.goal)
                else -> "${step.label}: done"
            }
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

        // A dock that found no pile (never moved, or reports chargeFlag 9): drive there instead.
        // Not after STOP or a timeout, and only if driving is still allowed.
        val fallback = step.fallback
        val noPile = !tr.sawMotion || chargeFlag == CHARGE_NOT_FOUND
        if (message != null && fallback != null && !stopping && noPile && t <= tr.hardCap) {
            if (snapshot().canDrive) {
                log("Go to charger: couldn't dock from here, driving to the pile first")
                send(fallback)
            } else {
                log("Go to charger: couldn't dock from here, and driving to the pile is blocked")
            }
        }
    }

    /** chargeFlag is stale while the robot sits still, but motion refreshes it, so after a dock it is worth a line. */
    private fun dockReport() = ". Robot reports: " + when (chargeFlag) {
        2 -> "charging at the pile."
        8 -> "still connecting to the pile."
        CHARGE_NOT_FOUND -> "no charging pile found."
        null -> "no charging status."
        else -> "not charging (chargeFlag $chargeFlag). Check it is on the pile."
    }

    /** Where the robot stopped, measured against the point's saved pose (both map frame). */
    private fun navReport(goal: Waypoint): String {
        val at = navPose
        val to = goal.pose
        if (at == null || to == null) return "done"
        val d = hypot(at.x - to.x, at.y - to.y)
        return if (d <= ARRIVED_M) String.format(Locale.US, "arrived (%.2f m from the point)", d)
        else String.format(Locale.US, "stopped %.2f m from the point. Blocked on the way?", d)
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

    fun demoChargeOnItsOwn() {
        demoRobot.goChargeOnItsOwn()
        log("Simulated: the robot sets off to charge by itself")
        publish()
    }

    fun demoPersonInTheWay() {
        demoRobot.personInTheWay(PERSON_WAIT_S)
        log("Simulated person in the way: the robot waits ${PERSON_WAIT_S.toInt()} s, then carries on")
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
        phase = phase,
        points = points,
        addingPoint = addingPoint,
        pointMessage = pointMessage,
        runaway = runaway,
        demoStatus =if (demo) DemoStatus(demoRobot.isEstopPressed, demoRobot.isOffline, demoRobot.isBlockNextPending, demoRobot.isNavigating, demoRobot.isBusy) else null,
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
        // type 0 = dock with a pile close by, 1 = cancel docking, 2 = navigate to the pile's
        // point, then dock. The robot's pile point is named "charging_pile".
        private const val DOCK_START = """{"type":0,"point":"charging_pile"}"""
        private const val DOCK_CANCEL = """{"type":1,"point":"charging_pile"}"""
        private const val CHARGE_NAV = """{"type":2,"point":"charging_pile"}"""
        private const val CHARGE_NOT_FOUND = 9          // chargeFlag: no pile found while docking
        // GO TO CHARGER docks straight away within this distance of the pile's point, else drives
        // there first. GUESS: the docking routine's real range was never measured; kept short.
        const val DOCK_NEAR_M = 1.5
        private const val AT_PILE_M = 0.3               // this close, the robot is already at the pile

        // ---- After STOP ----
        const val STOP_GUARD_MS = 10_000L      // watch the robot this long after the last STOP sent
        const val STOP_SETTLE_MS = 2_500L      // time to brake before motion counts as "moving again"
        const val STOP_RESEND_GAP_MS = 1_500L
        const val STOP_RESENDS = 3

        // ---- Navigation to saved points (REEMAN SLAM WEB API) ----
        const val NAV_PATH = "/cmd/nav_name"            // {"point":"<name>"}
        private const val CANCEL_NAV_PATH = "/cmd/cancel_goal"
        private const val POINTS_PATH = "/reeman/position"
        private const val PLAN_PATH = "/reeman/global_plan"
        private const val NO_PLAN = "007"               // "Can't get plan data": not navigating
        private const val PILE_POINT = "charging_pile"
        const val ARRIVED_M = 0.3                       // GUESS: how close counts as arrived
        private const val ADD_POINT_PATH = "/cmd/position"
        // The API allows delivery, normal (route point), production and charge. A disinfection
        // point is a destination, so "delivery". GUESS: what the robot's own app uses is unknown.
        const val NEW_POINT_TYPE = "delivery"
        const val MAX_POINT_NAME = 32
        private const val PERSON_WAIT_S = 5.0           // DEMO: how long a person blocks the way

        /**
         * {"waypoints":[{name,type,pose{x,y,theta}}]}, without the charging pile, which has
         * its own button. The pile is matched by name, and by its API type "charge" in case it
         * was renamed.
         */
        private fun parsePoints(body: String): List<Waypoint> = parseWaypoints(body).filterNot { isPile(it) }

        /** Every saved point, the pile included. */
        private fun parseWaypoints(body: String): List<Waypoint> {
            val list = JSONObject(body).optJSONArray("waypoints") ?: return emptyList() // none saved: may be null
            return (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                val pose = o.optJSONObject("pose")?.let {
                    runCatching { Pose(it.getDouble("x"), it.getDouble("y"), it.optDouble("theta", 0.0)) }.getOrNull()
                }
                Waypoint(o.getString("name"), o.optString("type"), pose)
            }
        }

        private fun isPile(w: Waypoint) = w.name == PILE_POINT || "charg" in w.type.lowercase(Locale.US)

        private fun fmt(format: String, vararg args: Any) = String.format(Locale.US, format, *args)

        private fun round3(v: Double) = Math.round(v * 1000) / 1000.0

        private fun errorCode(body: String): String? =
            runCatching { JSONObject(body).optString("error_code").ifEmpty { null } }.getOrNull()

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
