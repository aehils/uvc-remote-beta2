package com.expiation.reemanremote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sign

// ---------------------------------------------------------------------- state

enum class EStop { PRESSED, RELEASED }

/** Is the robot answering? Kept separate from [StepPhase]: a step keeps being tracked if the link drops. */
sealed interface Link {
    data class Disconnected(val lastError: String?) : Link

    /** Answering polls. [estop] is null until the first /reeman/base_encode after (re)connecting. */
    data class Connected(val estop: EStop?) : Link
}

/**
 * A d-pad button. [label] names its fixed step (see [DriveController.drive]); [word] names it when
 * held down (see [DriveController.hold]). Turn 180° is a fixed step only.
 */
enum class Move(val label: String, val word: String) {
    FORWARD("Forward 0.5 m", "Forward"), BACK("Back 0.5 m", "Back"), LEFT("Left 90°", "Left"),
    RIGHT("Right 90°", "Right"), AROUND("Turn 180°", "Turn 180°");

    val holdable: Boolean get() = this != AROUND
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
    /** Sent once this step has ended where it should: lining up, then docking (see [DriveController.goToCharger]). */
    val then: Step? = null,
    /** Set for a held d-pad arrow (see [DriveController.hold]). */
    val held: Move? = null,
)

data class Pose(val x: Double, val y: Double, val theta: Double)

/** A speed for /cmd/speed: forward m/s (negative = back), turn rad/s (positive = left). */
data class Velocity(val vx: Double, val vth: Double)

/** A point saved on the robot's map (/reeman/position). [pose] is null if the robot sent none. */
data class Waypoint(val name: String, val type: String, val pose: Pose?)

/**
 * The points saved on the robot's map: the disinfection points in [Loaded.list], the charging
 * pile apart (it has its own button, and is never a disinfection point).
 */
sealed interface Points {
    data object NotLoaded : Points
    data object Loading : Points
    data class Loaded(val list: List<Waypoint>, val pile: Waypoint? = null) : Points
    data class Failed(val error: String) : Points
}

/** A virtual wall on the map (/reeman/restrict_layer): a line the robot never crosses. Map frame, metres. */
data class Wall(val x1: Double, val y1: Double, val x2: Double, val y2: Double) {
    val length: Double get() = hypot(x2 - x1, y2 - y1)
}

/** What the robot is doing with its map (/reeman/get_mode). */
enum class RobotMode(val code: Int) {
    /** Scanning a new map. */
    MAPPING(1),
    NAVIGATION(2),
    /** Scanning more of the map in use (the API's "incremental mapping"). */
    EXTENDING(3);

    companion object {
        fun of(code: Int) = entries.firstOrNull { it.code == code }
    }
}

/**
 * A map image (/reeman/map): a PNG (base64), [resolution] metres per pixel, and the map-frame
 * position of its bottom-left corner. GUESS: the ROS map_server convention (pixel row 0 is the
 * top edge, the origin is the bottom-left corner); not yet checked against the real robot.
 */
data class MapImage(
    val width: Int,
    val height: Int,
    val resolution: Double,
    val originX: Double,
    val originY: Double,
    val png: String,
) {
    /** Has a scale and an origin, so the robot and points can be placed on it. The list's thumbnails don't. */
    val placeable: Boolean get() = resolution > 0

    fun pixelX(x: Double) = (x - originX) / resolution
    fun pixelY(y: Double) = height - (y - originY) / resolution
    fun mapX(px: Double) = originX + px * resolution
    fun mapY(py: Double) = originY + (height - py) * resolution
}

/** A map saved on the robot. [id] is the robot's unique name for it, [alias] the name people gave it. */
data class SavedMap(val id: String, val alias: String, val thumbnail: MapImage? = null) {
    val label: String get() = alias.ifBlank { "Untitled map" }
}

/** The maps saved on the robot (/reeman/history_map). */
sealed interface Maps {
    data object NotLoaded : Maps
    data object Loading : Maps
    data class Loaded(val list: List<SavedMap>) : Maps
    data class Failed(val error: String) : Maps
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

    /**
     * A d-pad arrow is held down: the app streams the robot's speed, ramping up to the arrow's
     * speed (see [DriveController.hold]). Letting go turns it into [Stepping] while the speed
     * ramps back down to zero and the robot comes to rest.
     */
    data class Holding(val move: Move, val step: Step, val track: Tracking, val since: Long) : StepPhase

    data class Stepping(val step: Step, val track: Tracking) : StepPhase

    /** STOP was pressed during a step; waiting (with shortened deadlines) for the robot to be still. */
    data class Stopping(val step: Step, val track: Tracking) : StepPhase
}

/** How long the UV lamps stay on, and how long after START they come on. Set on the points page. */
data class UvSettings(val minutes: Int = 5, val delaySeconds: Int = 15) {
    fun coerced() = UvSettings(
        minutes.coerceIn(1, DriveController.MAX_UV_MINUTES),
        delaySeconds.coerceIn(DriveController.MIN_UV_DELAY_S, DriveController.MAX_UV_DELAY_S),
    )
}

/**
 * A UV disinfection run (see [DriveController.startDisinfection]). [point] is the saved point
 * the robot stands on, or null if it isn't at one.
 */
sealed interface Uv {
    val point: String?

    /** Lamps still off; they come on at [lampsOnAt] unless the run is cancelled. */
    data class Countdown(override val point: String?, val lampsOnAt: Long, val forMs: Long) : Uv

    /** ON sent; waiting for the robot to accept it. */
    data class Starting(override val point: String?, val forMs: Long) : Uv

    /** The robot accepted ON at [since]; OFF is sent at [until]. */
    data class On(override val point: String?, val since: Long, val until: Long) : Uv
}

data class DemoStatus(
    val estopPressed: Boolean,
    val offline: Boolean,
    val blockPending: Boolean,
    val navigating: Boolean,
    /** Running any motion or task of its own. */
    val robotBusy: Boolean,
    /** The simulated UV lamps (the external supply in [DriveController.UV_SUPPLY]) are on. */
    val uvOn: Boolean,
)

/** Everything the screen shows. Immutable; a new one is published on every change. */
data class RemoteState(
    val demo: Boolean,
    val host: String,
    val link: Link,
    /** The app is looking for the robot on the phone's Wi-Fi (see [DriveController.findRobot]). */
    val searching: Boolean = false,
    /** Robots a search found that the app won't connect to by itself: staff pick one. */
    val foundRobots: List<FoundRobot> = emptyList(),
    /** What the last search came to, while the robot isn't answering. */
    val searchNote: String? = null,
    /** The robot's hostname (unique to it): how the app recognises it anywhere. Null until learnt. */
    val robotName: String? = null,
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
    /** The saved point the robot is standing on (it arrived there, or was found there), if any. */
    val atPoint: Waypoint?,
    val uv: Uv?,
    /** Time left in the countdown, or with the lamps on. */
    val uvLeftMs: Long?,
    val uvSettings: UvSettings,
    /** Under this between stops a task ends and goes to charge (see [DriveController.setLowBattery]). */
    val lowBattery: Int = DriveController.DEFAULT_LOW_BATTERY,
    /** The speed picked in Settings, in m/s; null: [DriveController.DEFAULT_RUNNING_SPEED]. */
    val runningSpeed: Double? = null,
    /** The app sent OFF but the robot hasn't accepted it yet: the lamps may still be on. */
    val lampsOffOwed: Boolean,
    val demoStatus: DemoStatus?,
    val log: List<String>,
    /** null until read, or if the firmware doesn't say. */
    val mode: RobotMode? = null,
    /** The map the robot navigates on (no image: see [mapImage]). */
    val currentMap: SavedMap? = null,
    /** The map in use, or while mapping, the scan so far. Read while a map is on screen. */
    val mapImage: MapImage? = null,
    /** Why [mapImage] couldn't be read, if it couldn't. */
    val mapProblem: String? = null,
    /** Where the robot believes it is on the map. Read while a map is on screen. */
    val robotPose: Pose? = null,
    val maps: Maps = Maps.NotLoaded,
    /** A map command on its way ("Saving the map …"). */
    val mapStep: String? = null,
    /** The outcome of the last map command, shown with the map. */
    val mapMessage: String? = null,
    /** The last map moved to the robot's bin, so it can be brought back. */
    val trashed: SavedMap? = null,
    /** The virtual walls on the map in use; null until read. */
    val walls: List<Wall>? = null,
    /** Points marked while mapping, saved onto the map once it is saved (see [DriveController.saveMap]). */
    val pendingPoints: List<Waypoint> = emptyList(),
    /** Virtual walls drawn while mapping, saved the same way. */
    val pendingWalls: List<Wall> = emptyList(),
    /**
     * Charging on its pile, as far as the app can tell: GO TO CHARGER becomes UNDOCK. From
     * chargeFlag, which can be stale, so it is dropped once the robot has been taken off the pile
     * (see [DriveController.undock]).
     */
    val docked: Boolean = false,
    /**
     * The robot's own complaint, if it has one: a trip it refused or that failed, or docking that
     * found no pile (see [DriveController.dismissAlert]). Shown on every page until dismissed.
     */
    val robotAlert: String? = null,
    /** The saved disinfection tasks (see [DriveController.saveTask]). */
    val tasks: List<DisinfectionTask> = emptyList(),
    /** The task under way, if any (see [DriveController.startTask]). */
    val taskRun: TaskRun? = null,
    /** How the last task ended, until dismissed or the next one starts. */
    val taskReport: TaskReport? = null,
) {
    val connected: Boolean get() = link is Link.Connected
    val estop: EStop? get() = (link as? Link.Connected)?.estop
    val busy: Boolean get() = phase != StepPhase.Idle
    val running: Step? get() = when (phase) {
        StepPhase.Idle -> null
        is StepPhase.Holding -> phase.step
        is StepPhase.Stepping -> phase.step
        is StepPhase.Stopping -> phase.step
    }
    val busyLabel: String? get() = running?.label

    /** The d-pad arrow being held down, if any. */
    val holding: Move? get() = (phase as? StepPhase.Holding)?.move

    /** Leaving the pile (see [DriveController.undock]). */
    val undocking: Boolean get() = running?.label == DriveController.UNDOCK_LABEL

    val still: Boolean get() = abs(vx) <= DriveController.MOVING_VX && abs(vth) <= DriveController.MOVING_VTH

    /** A routine or the lamps under way, or OFF still owed: what carries on when the app is left. */
    val runUnderWay: Boolean get() = taskRun != null || uv != null || lampsOffOwed

    /** Doing something STOP could end: moving, or a run under way. */
    val active: Boolean get() = busy || !still || runUnderWay

    /** What can't wait for someone to come back to the app: lamps that won't go off. */
    val needsSomeone: String? get() = if (lampsOffOwed && uv == null) "UV lamps may still be on" else null

    /** The battery a task needs to start: a margin over [lowBattery], so it doesn't end at its first stop. */
    val taskMinBattery: Int get() = lowBattery + DriveController.TASK_START_MARGIN

    /** Deleting a saved point: on the robot's map, with nothing running that could be using it. */
    val canDeletePoint: Boolean
        get() = connected && !busy && !mapping && taskRun == null && mapStep == null && !addingPoint && points is Points.Loaded

    /** Pausing the task under way; not in the moment its lamps are going off, on the way to the next stop. */
    val canPauseTask: Boolean get() = taskRun.let { it != null && it.stage != TaskStage.Paused && it.stage != TaskStage.LampsOff && it.stage != TaskStage.Docking }

    /** Carrying on after a pause takes what any trip takes. */
    val canResumeTask: Boolean get() = taskRun?.stage == TaskStage.Paused && canTravel

    /** Saving the robot's spot as a point: connected, standing still, on a map, and the existing names known. */
    val canAddPoint: Boolean get() = connected && !busy && still && !mapping && points is Points.Loaded && !addingPoint

    /**
     * The drive interlock: connected, e-stop known and released, no step running, robot still,
     * and the UV lamps off (no run, and no OFF still owed).
     */
    val canDrive: Boolean get() = estop == EStop.RELEASED && !busy && still && uv == null && !lampsOffOwed

    /**
     * Holding [move] down: the drive interlock, or, just after letting go of an arrow (the robot
     * still slowing down), pressing one again: the speed ramps from where it is to the new one.
     */
    fun canHold(move: Move): Boolean {
        if (!move.holdable) return false
        if (canDriveByHand) return true
        val p = phase
        return p is StepPhase.Stepping && p.step.held != null &&
            estop == EStop.RELEASED && uv == null && !lampsOffOwed
    }

    /**
     * The d-pad: the drive interlock, and off the charging pile. Docked, UNDOCK goes first, so
     * the robot never pulls away with charging still on.
     */
    val canDriveByHand: Boolean get() = canDrive && !docked

    /** Starting UV disinfection takes the same interlock as a trip. */
    val canDisinfect: Boolean get() = canNavigate

    /** The robot is scanning a map (new, or extending the one in use). */
    val mapping: Boolean get() = mode == RobotMode.MAPPING || mode == RobotMode.EXTENDING

    /**
     * Trips the robot plans on its map: the drive interlock, and the robot navigating on a map
     * rather than scanning one. Driving by hand works either way.
     */
    val canTravel: Boolean get() = canDrive && !mapping

    /**
     * GO, GO TO CHARGER and the UV buttons: [canTravel], and no task running. A task sends its
     * own trips and runs; the user's would fight it. STOP and the d-pad are never held back by one.
     */
    val canNavigate: Boolean get() = canTravel && taskRun == null

    /** Why no task can start now (the robot, not the task), or null. */
    val taskBlocker: String? get() = when {
        !connected -> "Connect to the robot first."
        taskRun != null -> "Another task is running."
        mapping -> "The robot is mapping. Save the map first."
        points !is Points.Loaded -> "Load the robot's points first."
        battery != null && battery < taskMinBattery -> "Battery too low to start ($battery%)."
        estop == EStop.PRESSED -> "The e-stop is pressed."
        !canNavigate -> "The robot must be still, with the lamps off."
        else -> null
    }

    /** What in [task] keeps it from starting on this map (no points, or points the map doesn't have), or null. */
    fun taskFault(task: DisinfectionTask): String? {
        if (task.stops.isEmpty()) return "Add a point to the task first."
        val loaded = (points as? Points.Loaded)?.list ?: return null
        val missing = task.stops.map { it.point }.distinct().filter { p -> loaded.none { it.name == p } }
        return when (missing.size) {
            0 -> null
            1 -> "${missing.single()} isn't on this map. Take it out of the routine."
            else -> "${missing.joinToString()} aren't on this map. Take them out of the routine."
        }
    }

    /** Why [task] can't start now, or null if it can. [DriveController.startTask] refuses with it. */
    fun taskProblem(task: DisinfectionTask): String? = taskFault(task) ?: taskBlocker

    /** START DISINFECTION's route over every point on the map (see [DisinfectionTask.route]). */
    val route: DisinfectionTask get() = DisinfectionTask.route((points as? Points.Loaded)?.list.orEmpty(), uvSettings)

    /** Why START DISINFECTION can't start now, or null. [DriveController.startRoute] refuses with it. */
    val routeProblem: String? get() = taskBlocker
        ?: if (route.stops.isEmpty()) "No points are saved on this robot's map yet." else null

    /**
     * Map commands (mode, save, switch, position): connected, nothing running, the robot standing
     * still, the lamps off, and one command at a time. None of them moves the robot.
     */
    val canChangeMap: Boolean get() = connected && !busy && still && uv == null && !lampsOffOwed && mapStep == null

    /**
     * Marking a point, the charging pile or a wall on the map: connected, the robot still, nothing
     * else on its way. While mapping they wait in the app until the map is saved.
     */
    val canMark: Boolean get() = connected && !busy && still && !addingPoint && mapStep == null &&
        (mapping || points is Points.Loaded)

    /** Starting a map, switching maps and setting the position: not while a scan is in progress. */
    val canStartMapping: Boolean get() = canChangeMap && !mapping
}

// ---------------------------------------------------------------------- controller

/**
 * Talks to the robot (or the demo robot) and enforces the drive rules
 * (REEMAN_HANDOFF.txt sections 5-7):
 *  - every motion is a fixed, pre-planned step (/cmd/move or /cmd/turn), except holding a d-pad
 *    arrow, which streams velocity (/cmd/speed) on the app's own ramp, both ways (see [hold])
 *  - one step at a time; nothing but STOP (or the held arrow's speed stream) is sent while a step runs
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
    /**
     * Searches the phone's network for robots (see [RobotFinder]), asking first for the one with
     * this hostname when it is known; null: never search.
     */
    private val search: (suspend (lastHost: String, hostname: String?) -> SearchResult)? = null,
    robotName: String? = null,
) {
    private val demoApi = RobotApi.demo(demoRobot)
    private var demo = demo
    private val api: RobotApi get() = if (demo) demoApi else liveApi

    private var link: Link = Link.Disconnected(null)
    private var failCount = 0
    private var version: String? = null
    private var battery: Int? = null
    private var chargeFlag: Int? = null
    // Charging was ended (or the robot found far from its pile) since chargeFlag last changed, so
    // its "docked" reading is old. A new reading, or a dock, trusts it again.
    private var pileLeft = false
    // On the pile: what locks the d-pad. Latched by a "charging on the pile" reading, and only
    // cleared by evidence the robot isn't sitting on it (UNDOCK's pull-away moving it, UNDOCK
    // finding it far from the pile, or a docking reading), never by charging ending: UNDOCK's
    // first command ends charging with the robot still on its pile, and a full battery may too.
    // Then the arrows must stay locked.
    private var onPile = false
    // GO pressed while docked: the trip goes out once the robot is off its pile (see undockThen).
    private var goAfterUndock: Waypoint? = null
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

    // Finding the robot again when it stops answering at its address (see findRobot), and making
    // sure the robot answering is this one (see checkRobot): an address can pass to another robot.
    private var robotName = robotName
    private var checkedHost: String? = null // the address whose robot is known to be this one
    private var strangerAt: String? = null  // another robot answers at this address
    private var checkJob: Job? = null
    private var searchJob: Job? = null
    private var searchedAt = Long.MIN_VALUE / 2
    private var foundRobots: List<FoundRobot> = emptyList()
    private var searchNote: String? = null
    private var pointsJob: Job? = null

    /** Watching the robot after STOP (see [guardStop]). */
    private data class StopGuard(val pressedAt: Long, val until: Long, val lastSent: Long, val resends: Int)
    private var stopGuard: StopGuard? = null
    private var runaway = false
    private var ownMotionNoted = false
    private var addJob: Job? = null
    private var chargerJob: Job? = null
    // The pile this GO TO CHARGER is going to, and whether it has already fallen back to lining up.
    private var chargePile: Waypoint? = null
    private var chargeRecovered = false
    private var recoveringUntil = 0L

    // ---- The robot's own complaints (/reeman/nav_status and chargeFlag 9), shown as an alert.
    private var robotAlert: String? = null
    private var navKey: String? = null // the last report read, to tell a new one from the same again
    private var navSeen = false        // read one since connecting: later ones are news
    private var navFresh = false       // a trip was sent since: the next report is news even if unchanged
    private var navSentAt = 0L

    // Hold-to-drive (see [hold]): the speed stream, the speed it ramps towards, the last speed the
    // robot accepted, the last sign of life from the button, STOP pressed (the stream ends), drop to
    // zero at once (the robot has stopped already), failures in a row, and the last command sent.
    private var driveJob: Job? = null
    private var target = ZERO
    private var sent = ZERO
    private var holdAliveAt = 0L
    private var jogStop = false
    private var jumpToZero = false
    private var jogFailures = 0
    private var lastJogAt = Long.MIN_VALUE / 2
    // A tap's nudge (see [release]): sent once the speed stream has put the robot back to zero.
    private var nudgeNext: Step? = null

    private var atPoint: Waypoint? = null
    private var uv: Uv? = null
    private var uvSettings = UvSettings()
    private var lowBattery = DEFAULT_LOW_BATTERY
    private var runningSpeed: Double? = null
    private var runningSpeedOwed = false
    private var runningSpeedSentAt = Long.MIN_VALUE / 2
    private var lampsOffOwed = false
    private var lampsOffInFlight = false
    private var lampsOffFailures = 0
    private var lampsOffLastSent = 0L
    // Lamp commands go out one at a time, in order: an OFF sent while an ON is still in flight
    // must not reach the robot first (live requests run on separate IO threads).
    private val lampLock = Mutex()
    /** How the last UV run ended (see [endDisinfection]): a task moves on only from one that ran its time. */
    private data class UvEnd(val completed: Boolean, val onMs: Long, val reason: String)
    private var uvEnded: UvEnd? = null

    // Disinfection tasks (see [startTask]): the saved ones, the one under way, the last one's report.
    private var tasks: List<DisinfectionTask> = emptyList()
    private var taskRun: TaskRun? = null
    private var taskReport: TaskReport? = null
    private var leftApp = false
    private var taskJob: Job? = null
    private var dockIdleAt: Long? = null

    // Maps (see [mapLoop]): the robot's mode and map in use, the image and pose while a map is on
    // screen, the saved maps, and one map command at a time.
    private var mapJob: Job? = null
    private var mapCommandJob: Job? = null
    private var mapsJob: Job? = null
    private var mode: RobotMode? = null
    private var modeStale = true
    private var currentMap: SavedMap? = null
    private var mapBefore: String? = null // the map in use when mapping started
    private var mapImage: MapImage? = null
    private var mapProblem: String? = null
    private var robotPose: Pose? = null
    private var mapVisible = false
    private var wantImage = true
    private var imageAt = Long.MIN_VALUE / 2
    private var maps: Maps = Maps.NotLoaded
    private var mapStep: String? = null
    private var mapMessage: String? = null
    private var trashed: SavedMap? = null
    private var walls: List<Wall>? = null
    private var pendingPoints: List<Waypoint> = emptyList()
    private var pendingWalls: List<Wall> = emptyList()

    private val logLines = ArrayDeque<String>()
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    private val _state = MutableStateFlow(snapshot())
    val state: StateFlow<RemoteState> = _state.asStateFlow()

    // ------------------------------------------------------------------ lifecycle

    fun resume() {
        leftApp = false
        if (pollJob == null) pollJob = scope.launch { pollLoop() }
        if (mapJob == null) mapJob = scope.launch { mapLoop() }
    }

    /**
     * [RunService] is keeping the app alive, so a run can carry on after [pause]. Set by
     * [RobotSession] just before it, and only while the notification with STOP is showing.
     */
    var keepRunningInBackground = false

    /**
     * Leaving the app: a held arrow counts as let go, the map and any search stop, and a step
     * already running finishes by itself. A run under way carries on while
     * [keepRunningInBackground], polling until it has ended and OFF is accepted. Otherwise polling
     * stops here and the run ends, lamps off: nothing else would be keeping their time.
     */
    fun pause() {
        leftApp = true
        searchJob?.cancel()
        mapJob?.cancel()
        mapJob = null
        (phase as? StepPhase.Holding)?.let { endHold(it, "${it.step.label}: app left, robot slowing to a stop") }
        if (keepRunningInBackground && snapshot().runUnderWay) {
            log("App left: the run carries on")
            publish()
            return
        }
        pollJob?.cancel()
        pollJob = null
        chargerJob?.cancel()
        dropUndockPlan()
        // Polling stops, so a task can't be followed: it ends here (a trip under way finishes by itself).
        endTask("app left")
        endDisinfection("app left")
        publish()
    }

    /** Ends the run now, lamps off, for when the app is closed for good. Polling goes on until OFF is accepted. */
    fun endRun(reason: String) {
        endTask(reason)
        endDisinfection(reason)
        publish()
    }

    /** Returns false (and changes nothing) if a step is running or the UV lamps may be on. */
    fun setDemo(on: Boolean): Boolean {
        if (on == demo) return true
        if (phase != StepPhase.Idle) {
            // STOP goes to the selected robot, so never switch away from one that is moving.
            log("Wait for the current step to finish before switching mode.")
            publish()
            return false
        }
        if (uvBlocksSwitch()) return false
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
                if (uvBlocksSwitch()) return null
                liveApi.host = host
                robotName = null // another robot, maybe: learnt again from it
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

    /** OFF goes to the selected robot, so never switch away from one whose lamps may be on, or that runs a task. */
    private fun uvBlocksSwitch(): Boolean {
        val problem = when {
            taskRun != null -> "End the task before changing robot."
            uv != null || lampsOffOwed -> "Switch the UV lamps off before changing robot."
            else -> return false
        }
        log(problem)
        publish()
        return true
    }

    /**
     * Staff picked [robot] from those a search found: the app drives it from now on, and knows
     * it by its hostname wherever it goes.
     */
    fun pickRobot(robot: FoundRobot) {
        if (demo) return
        if (robot.hostname != null && robot.hostname == robotName) {
            follow(robot)
            publish()
            return
        }
        if (robot.host != liveApi.host && uvBlocksSwitch()) return
        liveApi.host = robot.host
        switchRobot()
        robotName = robot.hostname
        log("Picked ${robot.name}")
        testConnection(null)
    }

    /**
     * Looks for the robot on the phone's Wi-Fi. Runs by itself while the robot isn't answering
     * (every [SEARCH_EVERY_MS]) and when the phone's Wi-Fi changes; staff can also ask for it.
     *
     * A robot found with this one's hostname is this robot at a new address (a new router, or
     * another access point): the app follows it without asking, which also lets a lamps-OFF still
     * owed reach it. Any other robot is only listed, for staff to pick: a hospital can have
     * several, and driving or lighting the wrong one must take a deliberate tap.
     */
    fun findRobot() {
        val s = search ?: return
        if (demo || searchJob?.isActive == true) return
        searchedAt = now()
        val from = liveApi.host
        searchJob = scope.launch {
            val r = s(from, robotName)
            searchJob = null
            onSearched(from, r)
            publish()
        }
        publish()
    }

    /** The phone joined, left or changed Wi-Fi: if the robot isn't answering, look for it now. */
    fun networkChanged() {
        if (link is Link.Disconnected) findRobot()
    }

    private fun onSearched(from: String, r: SearchResult) {
        // Switched robot meanwhile, or the robot answered again.
        if (demo || liveApi.host != from || link is Link.Connected) return
        val mine = robotName?.let { name -> r.robots.filter { it.hostname == name } }.orEmpty()
        if (mine.size == 1) {
            val robot = mine.single()
            if (robot.host == from) {
                strangerAt = null // it is back where it was: check it again
            } else {
                follow(robot)
                log("${robot.name} found at a new address. Reconnecting.")
            }
            return
        }
        val others = r.robots.filter { robotName == null || it.hostname != robotName }
        val ours = robotName?.let(RobotName::short)
        val note = when {
            r.checked == 0 -> "The phone isn't on Wi-Fi"
            others.isEmpty() -> if (ours != null) "$ours not found on this Wi-Fi" else "No robots on this Wi-Fi"
            ours == null -> "Robots on this Wi-Fi"
            else -> "$ours not found. Other robots here"
        }
        if (note != searchNote || others != foundRobots) {
            log(note + " (${r.checked} addresses checked)" + others.joinToString("") { "\n  ${it.name} at ${it.host}, ${it.version}" })
        }
        searchNote = note
        foundRobots = others
    }

    /** This robot is at [robot]'s address now: carry on there, keeping what is under way. */
    private fun follow(robot: FoundRobot) {
        liveApi.host = robot.host
        checkedHost = robot.host // a search has just read its hostname there
        strangerAt = null
        foundRobots = emptyList()
        searchNote = null
    }

    /**
     * Is the robot answering at the address this one? A new address from the router can pass to
     * another robot. Until it is known, the app doesn't count it as connected: no command goes to
     * a robot staff didn't choose. A robot that doesn't say its hostname is taken on trust.
     */
    private fun checkRobot() {
        if (checkJob?.isActive == true || strangerAt == liveApi.host) return
        val host = liveApi.host
        checkJob = scope.launch {
            val r = liveApi.get(HOSTNAME_PATH)
            if (liveApi.host != host || demo) return@launch
            val name = if (r.ok) parseHostname(r.body) else null
            when {
                r.httpCode < 0 -> return@launch // not reached: asked again on the next good poll
                name == null || robotName == null || name == robotName -> {
                    if (name != null && robotName == null) log("This robot is ${RobotName.short(name)} ($name)")
                    if (name != null) robotName = name
                    checkedHost = host
                }
                else -> {
                    strangerAt = host
                    val msg = "${RobotName.short(name)} answers at this address, not ${RobotName.short(robotName!!)}"
                    link = Link.Disconnected(msg)
                    log("$msg. Looking for ${RobotName.short(robotName!!)}.")
                    findRobot()
                }
            }
            publish()
        }
    }

    /** Forget everything about the old robot. Cancelling the jobs drops any answers still in flight. */
    private fun switchRobot() {
        connectJob?.cancel()
        searchJob?.cancel()
        checkJob?.cancel()
        checkedHost = null
        strangerAt = null
        foundRobots = emptyList()
        searchNote = null
        pointsJob?.cancel()
        addJob?.cancel()
        chargerJob?.cancel()
        dropUndockPlan()
        points = Points.NotLoaded // the other robot has its own map and points
        addingPoint = false
        pointMessage = null
        stopGuard = null
        runaway = false
        ownMotionNoted = false
        atPoint = null
        link = Link.Disconnected(null)
        failCount = 0
        version = null
        battery = null
        chargeFlag = null
        pileLeft = false
        onPile = false
        forgetNavStatus()
        robotAlert = null
        vx = 0.0
        vth = 0.0
        mapCommandJob?.cancel()
        mapsJob?.cancel()
        mode = null
        modeStale = true
        currentMap = null // the other robot has its own maps
        mapBefore = null
        mapImage = null
        mapProblem = null
        robotPose = null
        maps = Maps.NotLoaded
        mapStep = null
        mapMessage = null
        trashed = null
        walls = null
        pendingPoints = emptyList()
        pendingWalls = emptyList()
        refreshImage()
        if (pollJob != null) {
            pollJob?.cancel()
            pollJob = scope.launch { pollLoop() }
            mapJob?.cancel()
            mapJob = scope.launch { mapLoop() }
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
            val report = if (withBase) a.get(NAV_STATUS_PATH) else null
            onPoll(s, b, plan, pose, report)
            // Left running for a run that has now ended, and any trip to the charger after it.
            if (leftApp && snapshot().let { !it.runUnderWay && !it.busy } && chargerJob?.isActive != true) {
                pollJob = null
                return
            }
            delay((POLL_MS - (now() - started)).coerceAtLeast(0))
        }
    }

    private fun onPoll(s: RobotApi.Result, b: RobotApi.Result?, plan: RobotApi.Result?, pose: RobotApi.Result?, report: RobotApi.Result?) {
        var speedValid = false
        // A robot answers, but not yet known to be this one (see checkRobot): not connected.
        val unchecked = s.ok && !demo && checkedHost != liveApi.host
        if (unchecked) {
            checkRobot()
            if (strangerAt == liveApi.host && now() - searchedAt >= SEARCH_EVERY_MS) findRobot()
        } else if (s.ok) {
            runCatching {
                val o = JSONObject(s.body)
                vx = o.optDouble("vx", 0.0)
                vth = o.optDouble("vth", 0.0)
                speedValid = true
            } // unexpected body: keep previous values
            if (link is Link.Disconnected) {
                link = Link.Connected(estop = null)
                runningSpeedOwed = true
                forgetNavStatus() // what it reports first may be from before
                log(robotName?.let { "Connected to ${RobotName.short(it)}" } ?: "Connected")
                foundRobots = emptyList()
                searchNote = null
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
            if (failCount >= FAILS_BEFORE_DISCONNECT && now() - searchedAt >= SEARCH_EVERY_MS) findRobot()
        }

        if (!unchecked && b != null && b.ok) {
            runCatching {
                val o = JSONObject(b.body)
                if (o.has("battery")) battery = o.getInt("battery")
                if (o.has("chargeFlag")) {
                    val f = o.getInt("chargeFlag")
                    if (f != chargeFlag) pileLeft = false
                    // Turned 9 just now (not read as 9 on connecting): a dock has just failed.
                    if (f == CHARGE_NOT_FOUND && chargeFlag != null && chargeFlag != CHARGE_NOT_FOUND && now() >= recoveringUntil) {
                        raise(DOCK_ADVICE, "docking failed, chargeFlag $f: the robot couldn't find its charging pile")
                    }
                    chargeFlag = f
                    if (f == CHARGE_DOCKED && !pileLeft) onPile = true
                    // Docking, or failing to: not sitting on the pile (it may be driving onto it).
                    if (f == CHARGE_DOCKING || f == CHARGE_NOT_FOUND) onPile = false
                }
                val l = link
                if (o.has("emergencyButton") && l is Link.Connected) {
                    val e = if (o.getInt("emergencyButton") == 0) EStop.PRESSED else EStop.RELEASED
                    if (l.estop != null && l.estop != e) {
                        log(if (e == EStop.PRESSED) "E-stop PRESSED" else "E-stop released")
                    }
                    link = Link.Connected(e)
                }
                // chargeFlag is stale on this firmware, so it is never shown as live state; only
                // reported once a dock finishes, after motion has refreshed it (see dockReport),
                // and used to offer UNDOCK, checked against the map when pressed (see undock).
            }
        }

        if (!unchecked && plan != null) {
            // The API documents {"coordinates":[...]} during a trip; only 007 has been seen on the real robot.
            planActive = when (errorCode(plan.body)) {
                null -> if (plan.ok) true else null
                NO_PLAN -> false
                else -> null
            }
        }
        if (!unchecked && pose != null && pose.ok) {
            parsePose(pose.body)?.let { navPose = it }
        }
        if (!unchecked && report != null) onNavStatus(report)

        trackStep(speedValid)
        guardStop(speedValid)
        noticeOwnMotion(speedValid)
        tickDisinfection(speedValid)
        tickTask()
        tickRunningSpeed()
        publish()
    }

    // ------------------------------------------------------------------ commands

    fun drive(move: Move) {
        if (!snapshot().canDriveByHand) return
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

    // ------------------------------------------------------------------ hold-to-drive

    /**
     * [move]'s arrow was pressed down. The robot drives while it stays down (the screen calls
     * [holdAlive] every [HOLD_ALIVE_MS] while it does) and slows to a stop when it is let go
     * ([release]).
     *
     * It streams velocity (/cmd/speed) every [JOG_TICK_MS], on a straight-line ramp up to speed
     * and back down to zero, never more than one small stair per command ([JOG_ACCEL]·tick). The
     * robot applies each speed at once, with no ramp of its own, so the app's ramp is the only
     * thing between the driver and a hard stop (REEMAN_HANDOFF.txt section 6: a jump to 0 brakes
     * at 7-20 m/s²). Hence: never a bare jump to zero while the robot moves (STOP aside), and a
     * stalled request slows the ramp down rather than skipping a stair.
     *
     * The weak spot is the network. If commands stop arriving mid-drive (Wi-Fi lost, phone
     * frozen), the robot carries on for ~1.2 s, then its watchdog stops it hard. The speed caps
     * keep that stop small; nothing in the app can soften it.
     */
    fun hold(move: Move) {
        if (!snapshot().canHold(move)) return
        val p = phase
        val t = now()
        stopGuard = null // a new command from the user: motion is expected now
        atPoint = null
        val step = holdStep(move)
        val sawMotion = (p as? StepPhase.Stepping)?.track?.sawMotion ?: false
        phase = StepPhase.Holding(move, step, Tracking(t + HOLD_NO_MOTION_MS, t + HOLD_MAX_MS, sawMotion), since = t)
        holdAliveAt = t
        jogStop = false
        nudgeNext = null // pressed again before the last tap's nudge went out: this press wins
        target = holdVelocity(move)
        log(
            "${step.label}: " + (if (p is StepPhase.Stepping) "pressed while slowing, " else "") +
                fmt("driving at %s (/cmd/speed every %d ms while held)", speedText(target), JOG_TICK_MS)
        )
        publish()
        startDriving()
    }

    /** The held arrow is still down. Without this, the hold ends after [HOLD_ALIVE_TIMEOUT_MS]. */
    fun holdAlive(move: Move) {
        val h = phase as? StepPhase.Holding ?: return
        if (h.move == move) holdAliveAt = now()
    }

    /**
     * The held arrow was let go: the speed ramps down to zero. A tap (let go within [TAP_MS])
     * is a nudge instead: the stream drops to zero (the speed is still tiny), then a fixed small
     * step goes out ([NUDGE_CM] or [NUDGE_DEG]), planned by the robot, so a tap always moves it
     * the same short way rather than whatever the ramp had reached.
     */
    fun release(move: Move) {
        val h = phase as? StepPhase.Holding ?: return
        if (h.move != move) return
        val held = now() - h.since
        if (held < TAP_MS) {
            nudgeNext = nudgeStep(move)
            endHold(h, "${move.word}: tapped, nudging " + if (move == Move.FORWARD || move == Move.BACK) "$NUDGE_CM cm" else "$NUDGE_DEG°", stopNow = true)
        } else {
            endHold(h, fmt("%s: let go after %.1f s, slowing to a stop", h.step.label, held / 1000.0))
        }
        publish()
    }

    /** A tap's small fixed step. Marked as held, so pressing an arrow again takes over straight away. */
    private fun nudgeStep(move: Move): Step = when (move) {
        Move.FORWARD, Move.BACK -> Step(
            "${move.word} nudge", "/cmd/move",
            fmt("""{"distance":%d,"direction":%d,"speed":%.2f}""", NUDGE_CM, if (move == Move.FORWARD) 1 else 0, NUDGE_SPEED),
            isTurn = false,
            estimateMs = (NUDGE_CM / 100.0 / NUDGE_SPEED * 1000).toLong() + RAMP_ALLOWANCE_MS,
            held = move,
        )
        Move.LEFT, Move.RIGHT -> Step(
            "${move.word} nudge", "/cmd/turn",
            fmt("""{"direction":%d,"angle":%d,"speed":%.2f}""", if (move == Move.RIGHT) 0 else 1, NUDGE_DEG, NUDGE_TURN_SPEED),
            isTurn = true,
            estimateMs = (Math.toRadians(NUDGE_DEG.toDouble()) / NUDGE_TURN_SPEED * 1000).toLong() + RAMP_ALLOWANCE_MS,
            held = move,
        )
        Move.AROUND -> error("Turn 180° is a fixed step")
    }

    /**
     * The speed stream: every [JOG_TICK_MS], one stair from the last speed the robot accepted
     * towards [target], one request at a time. Runs until the robot has been sent zero
     * [JOG_FINAL_ZEROS] times with nothing more to do, or STOP.
     */
    private fun startDriving() {
        if (driveJob?.isActive == true) return
        driveJob = scope.launch {
            var zeros = 0
            while (isActive) {
                val started = now()
                val h = phase as? StepPhase.Holding
                if (h != null && started - holdAliveAt > HOLD_ALIVE_TIMEOUT_MS) {
                    // The screen stopped reporting the button (or froze): never drive on without it.
                    endHold(h, "${h.step.label}: lost track of the button, treating it as let go")
                    publish()
                }
                if (jogStop) break
                val next = if (jumpToZero) ZERO else ramp(sent, target)
                if (next == ZERO && sent == ZERO) {
                    if (++zeros > JOG_FINAL_ZEROS) break
                } else {
                    zeros = 0
                }
                val a = api
                val r = a.postQuick(SPEED_PATH, speedJson(next))
                lastJogAt = now()
                if (jogStop) {
                    // STOP was pressed while this was on its way: it may have reached the robot after STOP's zero.
                    if (next != ZERO) a.postQuick(SPEED_PATH, speedJson(ZERO))
                    break
                }
                val rejected = if (r.ok) robotError(r.body) else null
                if (r.ok && rejected == null) {
                    sent = next
                    if (next == ZERO) jumpToZero = false
                    jogFailures = 0
                    if (next == ZERO && nudgeNext != null) break // the tap's nudge goes next (below)
                } else {
                    onJogFailure(if (!r.ok) "FAILED ${r.error}" else "robot rejected: $rejected")
                    if (jogFailures >= JOG_GIVE_UP) {
                        log("Speed commands not getting through. The robot's own watchdog stops it ~1 s after the last one.")
                        sent = ZERO
                        target = ZERO
                        publish()
                        break
                    }
                }
                delay((JOG_TICK_MS - (now() - started)).coerceAtLeast(0))
            }
            sent = if (jogStop) ZERO else sent
            jogStop = false
            val nudge = nudgeNext
            nudgeNext = null
            // Only if nothing has happened since the tap: no STOP, no new press, no e-stop or lamps.
            val p = phase
            val s = snapshot()
            if (nudge != null && p is StepPhase.Stepping && p.step.held == nudge.held && p.step.path == SPEED_PATH &&
                s.estop == EStop.RELEASED && s.uv == null && !s.lampsOffOwed
            ) {
                send(nudge)
            }
            publish()
        }
    }

    /** A speed command that didn't get through: say so once, and end a hold that keeps failing. */
    private fun onJogFailure(why: String) {
        jogFailures++
        val h = phase as? StepPhase.Holding
        if (h != null && jogFailures >= JOG_FAILS) {
            endHold(h, "${h.step.label}: $why. Slowing to a stop; let go and press again")
        } else if (jogFailures == 1) {
            log("Speed command $why")
        }
        publish()
    }

    /**
     * Ends the hold: the speed ramps down to zero, and the step lock holds until the robot is
     * still. It sends STOP if the robot still moves [HOLD_COAST_MAX_MS] later (see [trackStep]).
     * [stopNow]: the robot has already stopped (an obstacle, the e-stop), so the stream drops to
     * zero at once rather than ramping down from a speed the robot isn't doing.
     */
    private fun endHold(h: StepPhase.Holding, message: String, stopNow: Boolean = false) {
        log(message)
        target = ZERO
        if (stopNow) jumpToZero = true
        val t = now()
        phase = StepPhase.Stepping(
            h.step, Tracking(t + HOLD_NO_MOTION_MS, t + HOLD_COAST_MAX_MS, sawMotion = h.track.sawMotion),
        )
    }

    private fun jogRecent() = now() - lastJogAt < JOG_RECENT_MS

    private fun holdStep(move: Move): Step {
        val turn = move == Move.LEFT || move == Move.RIGHT
        return Step(
            "${move.word} (held)", SPEED_PATH, speedJson(holdVelocity(move)),
            isTurn = turn, estimateMs = HOLD_NO_MOTION_MS, held = move,
        )
    }

    private fun holdVelocity(move: Move) = when (move) {
        Move.FORWARD -> Velocity(min(FORWARD_SPEED, MAX_LINEAR), 0.0)
        Move.BACK -> Velocity(-min(BACKWARD_SPEED, MAX_LINEAR), 0.0)
        Move.LEFT -> Velocity(0.0, min(TURN_SPEED, MAX_ANGULAR))
        Move.RIGHT -> Velocity(0.0, -min(TURN_SPEED, MAX_ANGULAR))
        Move.AROUND -> error("Turn 180° is a fixed step")
    }

    /**
     * GO TO CHARGER, by how far the pile is on the map: the robot's pose (/reeman/pose) against
     * the pile's saved point (/reeman/position).
     *  - on it ([AT_PILE_M]): /cmd/charge type 0, the docking routine (already docked: no motion).
     *  - near ([NEAR_PILE_M]): lines up first (see [lineUp]), then docks. From close by, the
     *    robot refuses type 2 (nav_status reason -1) and tries docking where it stands, which
     *    fails unless it happens to be lined up (its own log, 2026-10-15).
     *  - far, or either position unknown: /cmd/charge type 2, drive to the pile, then dock. If
     *    the robot refuses that anyway, the app lines up instead (see [lineUpAfterRefusal]).
     */
    fun goToCharger() {
        if (snapshot().canNavigate) charge()
    }

    /** [goToCharger] without the task check: a task ends with it. */
    private fun charge() {
        if (!snapshot().canTravel || chargerJob?.isActive == true) return
        log("Go to charger: checking how far the charging pile is …")
        publish()
        val a = api
        chargerJob = scope.launch {
            val all = readWaypoints(a)
            val pilePoint = all?.firstOrNull { isPile(it) }
            val pile = pilePoint?.pose
            val here = readPose(a)
            val d = if (pile != null && here != null) dist(here, pile) else null
            if (all != null && pilePoint == null) {
                // Sending charge type 2 then would ask for a point the map doesn't have.
                log(
                    "Go to charger: not sent. The robot's map in use has no charging pile. " +
                        "Mark it on the Map page (Add > Charging pile here) with the robot docked."
                )
                publish()
                return@launch
            }
            // The robot may have moved or been e-stopped while the app was asking.
            if (!snapshot().canTravel) {
                log("Go to charger: not sent (e-stop pressed, or the robot is moving)")
                publish()
                return@launch
            }
            // The pile by its name on this map: the robot's own app may not call it "charging_pile".
            val name = pilePoint?.name ?: PILE_POINT
            if (name != PILE_POINT) log("Go to charger: the pile on this map is called \"$name\"")
            val drive = chargeNavStep(name)
            chargePile = pilePoint
            chargeRecovered = false
            when {
                d == null -> {
                    log("Go to charger: couldn't tell where the pile is, so driving there first")
                    send(drive)
                }
                // At the pile already: a dock that doesn't move means "already docked".
                d <= AT_PILE_M -> {
                    log(fmt("Go to charger: pile %.1f m away, docking", d))
                    send(dockStep(name))
                }
                d <= NEAR_PILE_M -> {
                    log(fmt("Go to charger: pile %.1f m away, lining up in front of it first", d))
                    lineUp(a, pilePoint!!, here)
                }
                else -> {
                    log(fmt("Go to charger: pile %.1f m away, driving there first", d))
                    send(drive)
                }
            }
        }
    }

    /**
     * Drives to [APPROACH_POINT], [APPROACH_M] in front of the pile and facing away from it (a
     * docked robot faces away: docking reverses onto the pile), then docks from there. The spot
     * is a saved point so the robot plans its own way there; it is saved again only if the pile
     * has moved. Already there, lined up: docks straight away.
     */
    private suspend fun lineUp(a: RobotApi, pile: Waypoint, here: Pose?) {
        val p = pile.pose ?: return
        val spot = Pose(p.x + APPROACH_M * cos(p.theta), p.y + APPROACH_M * sin(p.theta), p.theta)
        val dock = dockStep(pile.name)
        if (here != null && dist(here, spot) <= ARRIVED_M && abs(turnBetween(here.theta, spot.theta)) <= LINED_UP_RAD) {
            if (!blockedForCharger()) {
                log("Go to charger: lined up in front of the pile, docking")
                send(dock)
            }
            return
        }
        val saved = readWaypoints(a)?.firstOrNull { it.name == APPROACH_POINT }?.pose
        if (saved == null || dist(saved, spot) > 0.02 || abs(turnBetween(saved.theta, spot.theta)) > 0.02) {
            val problem = savePoint(a, Waypoint(APPROACH_POINT, APPROACH_TYPE, spot))
            if (problem != null) {
                raise(DOCK_ADVICE, "couldn't save the spot in front of the pile: $problem")
                return
            }
        }
        if (blockedForCharger()) return
        send(
            Step(
                LINE_UP_LABEL, NAV_PATH, JSONObject().put("point", APPROACH_POINT).toString(), isTurn = false,
                estimateMs = 60_000,     // unknown: depends on the route
                stillPolls = 10,         // ~3 s, as for a trip
                noMotionMs = 15_000,
                capMs = 10 * 60_000,
                stallHint = "No route to the spot in front of the pile?",
                goal = Waypoint(APPROACH_POINT, APPROACH_TYPE, spot),
                then = dock,
            )
        )
    }

    /** The robot may have moved or been e-stopped while the app was asking. */
    private fun blockedForCharger(): Boolean {
        if (snapshot().canTravel) return false
        log("Go to charger: not sent (e-stop pressed, or the robot is moving)")
        publish()
        return true
    }

    /**
     * The robot refused GO TO CHARGER's trip (nav_status reason -1) and is trying to dock where it
     * stands instead. Ends that, waits for the robot to be still, and lines up. Once per press.
     */
    private fun lineUpAfterRefusal(step: Step) {
        val pile = chargePile
        if (pile?.pose == null) return raise(DOCK_ADVICE, "the robot wouldn't drive to ${step.goal?.name} from here")
        chargeRecovered = true
        recoveringUntil = now() + RECOVERY_QUIET_MS // the dock cancelled here may still report 9
        finishStep(step, "Go to charger: the robot wouldn't drive to the pile from here, so lining up in front of it first")
        val a = api
        chargerJob = scope.launch {
            val cancel = chargeJson(1, pile.name)
            log("Go to charger: ending the docking the robot started instead, sending $DOCK_PATH $cancel")
            publish()
            a.post(DOCK_PATH, cancel)
            val until = now() + 5_000
            while (!snapshot().still && now() < until) delay(POLL_MS)
            lineUp(a, pile, readPose(a))
            publish()
        }
    }

    /** The firmware's own docking routine (/cmd/charge type 0): finds the pile nearby and reverses onto it. */
    private fun dockStep(pile: String) = Step(
        "Go to charger (docking)", DOCK_PATH, chargeJson(0, pile), isTurn = false,
        estimateMs = 30_000,     // GUESS: never timed on the real robot
        stillPolls = 10,         // ~3 s: the routine may pause between aligning and reversing
        noMotionMs = 8_000,
        capMs = 120_000,
        stallHint = "Is the robot's own screen asking to confirm? Otherwise: already docked, or no charging pile within range?",
    )

    /**
     * /cmd/charge type 2: navigate to the pile's point, then dock. The lock holds while the robot
     * has a plan, and a pause between arriving and docking is allowed for.
     */
    private fun chargeNavStep(pile: String) = Step(
        "Go to charger", DOCK_PATH, chargeJson(2, pile), isTurn = false,
        estimateMs = 90_000,     // unknown: depends on the route
        stillPolls = 17,         // ~5 s: GUESS, room for a pause between the trip and docking
        noMotionMs = 15_000,
        capMs = 15 * 60_000,
        stallHint = "Is the robot's own screen asking to confirm? Otherwise: already on the pile, or no route to it?",
        goal = Waypoint(pile, "charge", null),
    )

    /**
     * UNDOCK (GO TO CHARGER while the robot is on its pile): leaves the pile the same clean way
     * every time, rather than driving straight off it.
     *  1. /cmd/charge type 1: ends charging (the serial SDK's dock:stop). GUESS: the same as
     *     "stop charging" in the robot's ros-aging app, which clicks somewhere near the wheels.
     *  2. a pause of [UNDOCK_SETTLE_MS] for the robot to let go of the pile.
     *  3. a short planned step forward, [UNDOCK_CM] at [UNDOCK_SPEED], on the robot's own gentle
     *     ramp. Docking reverses onto the pile, so forward is away from it.
     * chargeFlag can be stale, so if the map puts the robot well away from its pile, nothing is
     * sent and the button goes back to GO TO CHARGER.
     *
     * The d-pad is locked while docked (see [RemoteState.canDriveByHand]): driving by hand starts
     * with this, never with a pull on the pile.
     */
    fun undock() = undockThen(null)

    /** [undock], then GO to [goal] once the robot is off its pile: trips undock by themselves. */
    private fun undockThen(goal: Waypoint?) {
        val s = snapshot()
        if (!s.docked || !s.canDrive || chargerJob?.isActive == true) return
        goAfterUndock = goal
        log("Undock: checking the robot is at its pile …" + (goal?.let { " (then Go to ${it.name})" } ?: ""))
        publish()
        val a = api
        chargerJob = scope.launch {
            val pilePoint = readWaypoints(a)?.firstOrNull { isPile(it) }
            val pile = pilePoint?.pose
            val here = readPose(a)
            val d = if (pile != null && here != null) dist(here, pile) else null
            if (d != null && d > DOCK_NEAR_M) {
                pileLeft = true
                onPile = false
                log(fmt("Undock: not needed. The robot is %.1f m from its pile on the map, so it isn't docked", d))
                carryOn()
                publish()
                return@launch
            }
            if (!snapshot().canDrive) {
                log("Undock: not sent (e-stop pressed, or the robot is moving)")
                dropUndockPlan()
                publish()
                return@launch
            }
            val cancel = chargeJson(1, pilePoint?.name ?: PILE_POINT)
            send(undockStep()) { r ->
                log("Undock: ending charging, sending $DOCK_PATH $cancel")
                publish()
                val c = r.post(DOCK_PATH, cancel)
                val rejected = if (c.ok) robotError(c.body) else null
                when {
                    !c.ok || rejected != null -> {
                        dropUndockPlan()
                        if (!c.ok) "couldn't end charging (${c.error}), so it stayed on the pile"
                        else "the robot refused to end charging ($rejected), so it stayed on the pile"
                    }
                    else -> {
                        // The old "charging" reading no longer counts, but the robot is still on its
                        // pile, and the d-pad stays locked, until the pull-away has moved it.
                        pileLeft = true
                        log(fmt("Undock: charging ended, pulling away in %.1f s", UNDOCK_SETTLE_MS / 1000.0))
                        publish()
                        delay(UNDOCK_SETTLE_MS)
                        null
                    }
                }
            }
        }
    }

    /** The trip GO (or a task) asked for while docked, now the robot is off its pile. */
    private fun carryOn() {
        val goal = goAfterUndock ?: return
        goAfterUndock = null
        travel(goal)
    }

    private fun dropUndockPlan() {
        goAfterUndock = null
    }

    private fun undockStep() = Step(
        UNDOCK_LABEL, "/cmd/move",
        fmt("""{"distance":%d,"direction":1,"speed":%.2f}""", UNDOCK_CM, UNDOCK_SPEED),
        isTurn = false,
        estimateMs = UNDOCK_SETTLE_MS + (UNDOCK_CM / 100.0 / UNDOCK_SPEED * 1000).toLong() + RAMP_ALLOWANCE_MS,
        stallHint = "Did charging end? Check the robot's own screen, and that nothing is in front of it.",
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
            val all = if (r.ok && rejected == null) runCatching { parseWaypoints(r.body) }.getOrNull() else null
            val loaded = all?.filterNot { isPile(it) || it.name == APPROACH_POINT }
            // The map's virtual walls, read alongside. Not fatal if they can't be.
            walls = readWalls(a) ?: walls
            // Is the robot already standing on one of them (say, after the app was restarted)?
            if (loaded != null && atPoint == null && phase == StepPhase.Idle && snapshot().still) {
                val here = readPose(a)
                if (here != null) atPoint = loaded.firstOrNull { it.pose != null && dist(here, it.pose) <= ARRIVED_M }
            }
            points = when {
                loaded != null -> Points.Loaded(loaded, all.firstOrNull { isPile(it) })
                !r.ok -> Points.Failed(r.error ?: "no answer")
                rejected != null -> Points.Failed("robot error $rejected")
                else -> Points.Failed("unexpected reply: ${r.body.take(80)}")
            }
            log(
                when (val p = points) {
                    is Points.Loaded -> "Loaded ${p.list.size} disinfection point(s)" +
                        (if (p.pile != null) ", the charging pile" else ", no charging pile") +
                        (walls?.let { ", ${it.size} virtual wall(s)" } ?: "")
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
     * While mapping, the point waits in the app and is saved with the map (see [saveMap]).
     */
    fun addPointHere(name: String) = markHere(name.trim(), NEW_POINT_TYPE)

    /**
     * Marks the charging pile where the robot stands: done with the robot docked on its pile, so
     * the point is where the robot's centre sits on it (what GO TO CHARGER measures against).
     * Replaces any pile already marked.
     */
    fun addChargingPileHere() = markHere(PILE_POINT, PILE_TYPE)

    private fun markHere(clean: String, type: String) {
        val s = snapshot()
        val pile = type == PILE_TYPE
        val loaded = (s.points as? Points.Loaded)?.list
        // A new map starts with no points: only the ones marked during this scan count.
        val taken = pendingPoints.map { it.name } + if (s.mode == RobotMode.MAPPING) emptyList() else loaded.orEmpty().map { it.name }
        val problem = when {
            s.addingPoint -> return
            !s.connected -> "Not connected to the robot."
            s.busy || !s.still -> "Wait for the robot to stand still."
            s.mapStep != null -> "Wait for \"${s.mapStep}\" to finish."
            !s.mapping && loaded == null -> "Load the points first."
            clean.isEmpty() -> "Give the point a name."
            clean.length > MAX_POINT_NAME -> "Use a name of $MAX_POINT_NAME characters or fewer."
            !pile && (clean == PILE_POINT || clean == APPROACH_POINT || clean in taken) -> "A point called \"$clean\" already exists."
            else -> null
        }
        if (problem != null) {
            pointMessage = problem
            publish()
            return
        }
        val label = if (pile) "the charging pile" else "\"$clean\""

        addingPoint = true
        pointMessage = "Saving $label …"
        publish()
        val a = api
        val mapping = s.mapping
        addJob = scope.launch {
            val p = a.get(POSE_PATH)
            val pose = if (p.ok) parsePose(p.body) else null
            val result = when {
                pose == null -> failed("Couldn't read where the robot is", p.error ?: "unexpected reply ${p.body.take(80)}")
                mapping -> {
                    pendingPoints = pendingPoints.filterNot { it.name == clean } + Waypoint(clean, type, pose)
                    fmt("Marked %s at x %.2f, y %.2f. It's saved with the map.", label, pose.x, pose.y)
                }
                else -> savePoint(a, Waypoint(clean, type, pose))?.let { failed("Couldn't add $label", it) }
                    ?: fmt("Added %s at x %.2f, y %.2f", label, pose.x, pose.y)
            }
            log(result)
            pointMessage = result
            addingPoint = false
            publish()
            if (!mapping && pose != null) loadPoints() // shows the new point, and confirms the robot kept it
        }
    }

    /**
     * Deletes a saved point from the map in use, for good: the robot has no bin for points. The
     * body is a JSON array of names; the API's printed {"test"} and {"name":..} are both refused
     * (tried on the robot 2026-10-30, v5.1.20). Routines using it then show it as missing.
     */
    fun deletePoint(name: String) {
        val s = snapshot()
        if (!s.canDeletePoint) return
        pointMessage = "Deleting \"$name\" …"
        publish()
        val a = api
        scope.launch {
            val r = a.delete(ADD_POINT_PATH, JSONArray().put(name).toString())
            val rejected = if (r.ok) robotError(r.body) else null
            val problem = r.error ?: rejected
            if (problem == null) {
                pointMessage = "Deleted \"$name\""
                log(pointMessage!!)
                if (atPoint?.name == name) atPoint = null
                loadPoints()
            } else {
                pointMessage = failed("Couldn't delete \"$name\"", problem)
            }
            publish()
        }
    }

    /** /cmd/position for [w] (its pose is required). Null if the robot took it, else what went wrong. */
    private suspend fun savePoint(a: RobotApi, w: Waypoint): String? {
        val pose = w.pose ?: return "no position"
        val body = JSONObject()
            .put("name", w.name)
            .put("type", w.type)
            .put("pose", JSONObject().put("x", round3(pose.x)).put("y", round3(pose.y)).put("theta", round3(pose.theta)))
            .toString()
        log("Add point: sending $ADD_POINT_PATH $body")
        val r = a.post(ADD_POINT_PATH, body)
        val rejected = if (r.ok) robotError(r.body) else null
        return when {
            !r.ok -> r.error
            rejected != null -> "robot rejected it: $rejected"
            else -> null
        }
    }

    // ---- virtual walls (/reeman/restrict_layer, /cmd/restrict_layer: the whole set is sent each time)

    /** Adds a virtual wall to the map in use; while mapping, it waits and is saved with the map. */
    fun addWall(wall: Wall) {
        if (wall.length < MIN_WALL_M) {
            mapMessage = "Draw a longer wall."
            publish()
            return
        }
        if (snapshot().mapping) {
            pendingWalls = pendingWalls + wall
            mapMessage = fmt("Virtual wall marked (%.1f m). It's saved with the map.", wall.length)
            log(mapMessage!!)
            publish()
            return
        }
        runMapCommand("Adding a virtual wall …", mapProblemNow(mapping = false)) { a ->
            val now = readWalls(a) ?: return@runMapCommand "Couldn't read the map's virtual walls, so nothing was changed."
            writeWalls(a, now + wall)?.let { return@runMapCommand failed("Couldn't add the wall", it) }
            walls = now + wall
            fmt("Virtual wall added (%.1f m)", wall.length)
        }
    }

    /** Removes a virtual wall (one waiting to be saved, or one on the map in use). */
    fun removeWall(wall: Wall) {
        if (wall in pendingWalls) {
            pendingWalls = pendingWalls - wall
            mapMessage = "Virtual wall removed."
            publish()
            return
        }
        runMapCommand("Removing a virtual wall …", mapProblemNow(mapping = false)) { a ->
            val now = readWalls(a) ?: return@runMapCommand "Couldn't read the map's virtual walls, so nothing was changed."
            val left = now.filterNot { sameWall(it, wall) }
            if (left.size == now.size) return@runMapCommand "That wall is no longer on the map."
            writeWalls(a, left)?.let { return@runMapCommand failed("Couldn't remove the wall", it) }
            walls = left
            "Virtual wall removed"
        }
    }

    private suspend fun readWalls(a: RobotApi): List<Wall>? {
        val r = a.get(WALLS_PATH)
        if (!r.ok || robotError(r.body) != null) return null
        return runCatching { parseWalls(r.body) }.getOrNull()
    }

    private suspend fun writeWalls(a: RobotApi, list: List<Wall>): String? = mapCommand(a, SET_WALLS_PATH, wallsJson(list))

    /**
     * Writes what was marked during the scan onto the map now in use: the points, then the walls
     * (added to any the map already has). Returns a line for the outcome.
     */
    private suspend fun saveMarks(a: RobotApi): String {
        var failed = 0
        pendingPoints.forEach { if (savePoint(a, it) != null) failed++ }
        if (pendingWalls.isNotEmpty()) {
            val now = readWalls(a).orEmpty()
            if (writeWalls(a, now + pendingWalls) != null) failed += pendingWalls.size
        }
        val total = pendingPoints.size + pendingWalls.size
        return if (failed == 0) "$total marked spot(s) saved on it" else "$failed of $total marked spot(s) couldn't be saved"
    }

    /**
     * Sends the robot to a saved point (/cmd/nav_name). The robot plans its own route and
     * speed. It moves the robot, so it takes the same interlock and step lock as the d-pad;
     * the lock also holds while the robot still has a plan (see [trackStep]).
     */
    fun goTo(point: Waypoint) {
        if (snapshot().canNavigate) travel(point)
    }

    /** [goTo] without the task check: a task's own trips. */
    private fun travel(point: Waypoint) {
        val s = snapshot()
        if (!s.canTravel) return
        if (s.docked) return undockThen(point)
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

    /**
     * Locks the step lock on [step], then sends it. [before], if given, runs first under the same
     * lock (see [undock]); it returns null to go on, or what went wrong, which ends the step.
     * STOP pressed meanwhile, or the robot already moving, means the step itself is not sent.
     */
    private fun send(step: Step, before: (suspend (RobotApi) -> String?)? = null) {
        // Lock before sending, so a double-tap can't send a second step.
        val t = now()
        stopGuard = null // a new command from the user: motion is expected now
        atPoint = null
        if (step.goal != null) {
            planActive = null
            navPose = null
            // A trip: the robot's next report is about it, even if it reads the same as the last.
            navFresh = true
            navSentAt = t
        }
        if (step.goal != null || step.path == DOCK_PATH) robotAlert = null // trying again
        phase = StepPhase.Stepping(step, Tracking(t + step.noMotionMs, t + step.capMs))
        if (before == null) log("${step.label}: sending ${step.path} ${step.json}")
        publish()

        val a = api
        scope.launch {
            if (before != null) {
                val problem = before(a)
                if (problem != null) {
                    finishStep(step, "${step.label}: $problem")
                    publish()
                    return@launch
                }
                val p = phase
                if (p !is StepPhase.Stepping || p.step !== step) return@launch
                if (p.track.sawMotion) {
                    log("${step.label}: the robot is already moving, so ${step.path} wasn't sent")
                    publish()
                    return@launch
                }
                log("${step.label}: sending ${step.path} ${step.json}")
                publish()
            }
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
    fun stop() = halt("STOP pressed")

    /** [stop], logged as [reason]. [endRun] false leaves the task to whoever called (see [pauseTask]). */
    private fun halt(reason: String, endRun: Boolean = true) {
        val p = phase
        val turnFirst = snapshot().running?.isTurn ?: (abs(vth) > MOVING_VTH && abs(vx) <= MOVING_VX)
        log(reason)
        val t = now()
        val jog = jogRecent()
        if (driveJob?.isActive == true) jogStop = true // the stream ends; STOP's own zero goes out below
        target = ZERO
        nudgeNext = null
        fun shorten(tr: Tracking) = tr.copy(
            noMotionDeadline = min(tr.noMotionDeadline, t + 4000),
            hardCap = min(tr.hardCap, t + 6000),
        )
        phase = when (p) {
            StepPhase.Idle -> p
            is StepPhase.Holding -> StepPhase.Stopping(p.step, shorten(p.track))
            is StepPhase.Stepping -> StepPhase.Stopping(p.step, shorten(p.track))
            is StepPhase.Stopping -> p.copy(track = shorten(p.track))
        }
        chargerJob?.cancel() // a GO TO CHARGER (or undock) still deciding must not send after STOP
        dropUndockPlan()
        stopGuard = StopGuard(pressedAt = t, until = t + STOP_GUARD_MS, lastSent = t, resends = 0)
        runaway = false
        if (endRun) endTask(reason)
        endDisinfection(if (endRun) "STOP pressed" else reason)
        if (lampsOffOwed) sendLampsOff()
        publish()
        sendStop(turnFirst, "STOP", jog = jog)
    }

    /**
     * Brakes with the stop bodies (the robot's motion type first; a zero speed first of all if it
     * was driven by speed lately), cancels any navigation (/cmd/cancel_goal) and any docking
     * (/cmd/charge type 1), and switches the UV lamps off, not only what this app started.
     * The robot can start a task on its own (it went to charge by itself on 2026-10-05), and
     * the stop bodies alone only paused that trip: it carried on right after.
     *
     * STOP never ends charging: while the robot sits still, charging on its pile, the docking
     * cancel isn't sent (on the pile it ends charging, as UNDOCK uses it). Anything else, a dock
     * under way or a trip to the charger the robot started itself, is cancelled as ever.
     */
    private fun sendStop(turnFirst: Boolean, label: String, report: Boolean = true, jog: Boolean = false) {
        val a = api
        val charging = onPile && chargeFlag == CHARGE_DOCKED && abs(vx) <= MOVING_VX && abs(vth) <= MOVING_VTH
        scope.launch {
            // Driving by speed: zero it first. A hard stop, but STOP is for emergencies.
            if (jog) a.post(SPEED_PATH, speedJson(ZERO))
            val first = if (turnFirst) a.post("/cmd/turn", TURN_STOP) else a.post("/cmd/move", MOVE_STOP)
            val nav = a.post(CANCEL_NAV_PATH, "{}")
            val dock = if (charging) null else a.post(DOCK_PATH, DOCK_CANCEL)
            val second = if (turnFirst) a.post("/cmd/move", MOVE_STOP) else a.post("/cmd/turn", TURN_STOP)
            // The lamps too, even if this app didn't switch them on. Queued behind any ON in flight.
            val lamps = lampLock.withLock { a.post(UV_PATH, UV_OFF) }
            val failed = listOfNotNull("navigation" to nav, dock?.let { "docking" to it }, "lamps off" to lamps).firstOrNull { !it.second.ok }
            if (report || !(first.ok || second.ok)) log(
                "$label: " + if (first.ok || second.ok) {
                    "sent" + (failed?.let { (what, r) -> " ($what failed: ${r.error})" } ?: "")
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
        sendStop(
            turnFirst = abs(vth) > MOVING_VTH && abs(vx) <= MOVING_VX, "STOP (again)",
            report = n <= STOP_RESENDS, jog = jogRecent(),
        )
    }

    /** Notes, once each time, motion this app didn't ask for (for example the robot going to charge by itself). */
    private fun noticeOwnMotion(speedValid: Boolean) {
        if (!speedValid) return
        val moving = abs(vx) > MOVING_VX || abs(vth) > MOVING_VTH
        if (moving) atPoint = null
        if (moving && phase == StepPhase.Idle && stopGuard == null && !ownMotionNoted) {
            ownMotionNoted = true
            log("Robot is moving, but not by this app. Press STOP to stop it.")
        }
        if (!moving) ownMotionNoted = false
    }

    // ------------------------------------------------------------------ the robot's complaints

    /**
     * The robot's report on its latest trip (/reeman/nav_status), the same one its screen app
     * logs as nav_result{res reason goal dist mileage}. res 6 is the start of a trip: reason 0
     * started, anything else refused (see [refusal]); res 3 is the end: reason 0 arrived, else
     * failed. Only news counts: a report that changed, or the first one after the app sent a
     * trip (a refused trip reads the same each time). The first one after connecting may be
     * old, so it doesn't.
     */
    private fun onNavStatus(r: RobotApi.Result) {
        if (!r.ok || now() - navSentAt < NAV_SETTLE_MS) return // the robot may not have updated it yet
        val n = parseNavStatus(r.body) ?: return // no trip yet: the firmware answers with an error
        val news = (n.key != navKey && navSeen) || navFresh
        navKey = n.key
        navSeen = true
        navFresh = false
        if (!news) return
        val problem = when {
            n.res == NAV_START && n.reason != 0 -> refusalAdvice(n.reason, n.goal)
            n.res == NAV_END && n.reason != 0 -> "Couldn't get to ${n.goal}. Clear the way, then try again."
            else -> return
        }
        val detail = "nav_status res ${n.res} reason ${n.reason}, ${n.goal}" + if (n.res == NAV_START) ": ${refusal(n.reason)}" else ""
        val step = snapshot().running
        val refusedPile = n.res == NAV_START && abs(n.reason) == 1 &&
            step != null && step.path == DOCK_PATH && step.goal?.name == n.goal
        if (refusedPile && !chargeRecovered) lineUpAfterRefusal(step!!) else raise(problem, detail)
    }

    /**
     * Tells staff what to do ([advice], until dismissed). What went wrong, in the robot's own
     * terms, goes to the Activity log for whoever looks into it.
     */
    private fun raise(advice: String, detail: String) {
        robotAlert = advice
        log("Robot: $advice")
        logDetail(detail)
        publish()
    }

    private fun logDetail(detail: String) {
        log("  Detail: $detail. Battery $battery%, chargeFlag $chargeFlag, e-stop ${snapshot().estop}, mode $mode, " + fmt("vx %.2f, vth %.2f", vx, vth))
    }

    /** What failed, in full, for the Activity log; a line for staff saying what to do, to show. */
    private fun failed(what: String, detail: String): String {
        log("$what: $detail")
        return "$what. Check the robot is connected, then try again."
    }

    fun dismissAlert() {
        robotAlert = null
        publish()
    }

    private fun forgetNavStatus() {
        navKey = null
        navSeen = false
        navFresh = false
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
            is StepPhase.Holding -> return trackHold(p, speedValid)
            is StepPhase.Stepping -> p.step to p.track
            is StepPhase.Stopping -> p.step to p.track
        }
        val tr = track(before, speedValid)
        val t = now()

        // Still moving well after a held arrow was let go (a speed command that landed late, or a
        // ramp-down that isn't getting through): never let that run on.
        val moving = speedValid && (abs(vx) > MOVING_VX || abs(vth) > MOVING_VTH)
        if (p is StepPhase.Stepping && step.held != null && t > tr.hardCap && moving) {
            phase = p.copy(track = tr)
            log("${step.label}: still moving ${HOLD_COAST_MAX_MS / 1000} s after it was let go")
            halt("STOP sent by the app")
            return
        }

        val stopping = p is StepPhase.Stopping
        // After letting go of an arrow, done only once the speed stream has reached zero.
        val ramping = step.held != null && driveJob?.isActive == true && !stopping
        val onTheWay = !stopping && step.goal != null && planActive == true
        val message = when {
            tr.sawMotion && tr.stillPolls >= step.stillPolls && !onTheWay && !ramping -> when {
                stopping -> "${step.label}: stopped"
                step.path == DOCK_PATH -> "${step.label}: done" + dockReport()
                step.goal != null -> "${step.label}: " + navReport(step.goal)
                else -> "${step.label}: done"
            }
            !tr.sawMotion && t > tr.noMotionDeadline ->
                if (stopping) "${step.label}: stopped before it moved"
                else {
                    // Not when it's on the pile already: then not moving is the right answer.
                    if (step.path == DOCK_PATH && !onPile) raise(CONFIRM_ADVICE, "${step.label}: no motion seen")
                    "${step.label}: no motion seen. ${step.stallHint}"
                }
            t > tr.hardCap -> "${step.label}: timed out waiting for the robot to stop"
            else -> null
        }
        val goal = step.goal
        if (message != null && !stopping && goal != null && step.path == NAV_PATH && tr.sawMotion) {
            val at = navPose
            if (at != null && goal.pose != null && dist(at, goal.pose) <= ARRIVED_M) atPoint = goal
        }
        // Back on the pile: chargeFlag's "docked" counts again even if it never changed.
        if (message != null && !stopping && step.path == DOCK_PATH && tr.sawMotion) pileLeft = false
        phase = when {
            message != null -> {
                log(message)
                StepPhase.Idle
            }
            p is StepPhase.Stepping -> p.copy(track = tr)
            else -> (p as StepPhase.Stopping).copy(track = tr)
        }

        // Off the pile: the d-pad unlocks, and a trip GO asked for while docked goes next. Not
        // after STOP, a stall or a timeout: then the robot may still be on its pile, and stays locked.
        if (message != null && step.label == UNDOCK_LABEL) {
            if (!stopping && tr.sawMotion && t <= tr.hardCap) {
                onPile = false
                carryOn()
            } else {
                dropUndockPlan()
            }
        }

        // Lined up in front of the pile: dock. Not after STOP, a stall or a timeout, nor if the trip
        // ended short of the spot.
        val next = step.then
        if (message != null && next != null && !stopping && tr.sawMotion && t <= tr.hardCap) {
            when {
                goal != null && atPoint != goal -> raise(DOCK_ADVICE, "couldn't get to the spot in front of the pile")
                snapshot().canTravel -> send(next)
                else -> log("${next.label}: not sent (e-stop pressed, or the robot is moving)")
            }
        }
    }

    /** Counts this poll: motion seen, or one more still poll after it. */
    private fun track(tr: Tracking, speedValid: Boolean): Tracking {
        if (!speedValid) return tr
        val moving = abs(vx) > MOVING_VX || abs(vth) > MOVING_VTH
        return when {
            moving -> tr.copy(sawMotion = true, stillPolls = 0)
            tr.sawMotion -> tr.copy(stillPolls = tr.stillPolls + 1)
            else -> tr
        }
    }

    /**
     * While an arrow is held, lets go of it for the user when driving on would be wrong. The robot
     * stopping by itself (an obstacle) ends the hold too, so the app never keeps pushing it into
     * one; pressing again tries once more.
     */
    private fun trackHold(h: StepPhase.Holding, speedValid: Boolean) {
        val tr = track(h.track, speedValid)
        val cur = h.copy(track = tr)
        val label = h.step.label
        val t = now()
        when {
            link is Link.Disconnected -> endHold(cur, "$label: lost connection, robot slowing to a stop")
            (link as? Link.Connected)?.estop == EStop.PRESSED -> endHold(cur, "$label: e-stop pressed", stopNow = true)
            tr.sawMotion && tr.stillPolls >= h.step.stillPolls -> endHold(
                cur, "$label: the robot stopped while held. Obstacle in the way? Let go and press again", stopNow = true,
            )
            !tr.sawMotion && t > tr.noMotionDeadline -> endHold(cur, "$label: the robot hasn't moved", stopNow = true)
            t > tr.hardCap -> endHold(cur, "$label: held for ${HOLD_MAX_MS / 1000} s. Let go and press again to carry on")
            else -> phase = cur
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
        val d = dist(at, to)
        return if (d <= ARRIVED_M) String.format(Locale.US, "arrived (%.2f m from the point)", d)
        else String.format(Locale.US, "stopped %.2f m from the point. Blocked on the way?", d)
    }

    /** Ends [step] early, unless the lock has already moved on from it. */
    private fun finishStep(step: Step, message: String) {
        val current = when (val p = phase) {
            StepPhase.Idle -> null
            is StepPhase.Holding -> p.step
            is StepPhase.Stepping -> p.step
            is StepPhase.Stopping -> p.step
        }
        log(message)
        if (current === step) phase = StepPhase.Idle
    }

    // ------------------------------------------------------------------ UV disinfection

    /** Takes effect from the next run. */
    fun setUvSettings(settings: UvSettings) {
        uvSettings = settings.coerced()
        publish()
    }

    /**
     * Switches the UV lamps on where the robot stands, after the settings' start delay (time to
     * leave the room; [startDisinfectionNow] skips it), for the settings' duration, then off.
     * The lamps are an external supply of the power board (/cmd/external_power_supply; see
     * [UV_SUPPLY]). The robot can't report whether the
     * lamps are on, so the timer is the app's, and the run ends (lamps off) on STOP, the e-stop,
     * lost connection, the robot moving, or closing the app (see [pause]).
     */
    fun startDisinfection() {
        if (!snapshot().canDisinfect) return
        val point = atPoint?.name
        val set = uvSettings
        val where = point?.let { "at $it" } ?: "where the robot stands"
        uv = Uv.Countdown(point, now() + set.delaySeconds * 1000L, set.minutes * 60_000L)
        log("Disinfection $where: UV lamps on in ${set.delaySeconds} s, for ${set.minutes} min. Leave the room.")
        publish()
    }

    /** Ends the countdown early: the lamps come on now. Everything the countdown checks still applies. */
    fun startDisinfectionNow() {
        val run = uv as? Uv.Countdown ?: return
        log("Disinfection: countdown skipped, lamps on now")
        lampsOn(run.point, run.forMs)
        publish()
    }

    /**
     * The Tasks tab's UV switch: the lamps on now, with no countdown (the switch's own dialog is
     * the warning), for the [minutes] picked there. Same interlock and same run as
     * [startDisinfection], so everything that ends a run still switches them off.
     */
    fun switchLampsOn(minutes: Int) {
        if (!snapshot().canDisinfect) return
        val m = minutes.coerceIn(1, MAX_UV_MINUTES)
        log("UV lamps switched on from the app, off after $m min")
        lampsOn(atPoint?.name, m * 60_000L)
        publish()
    }

    /** Takes effect from the next time the battery is checked. */
    fun setLowBattery(percent: Int) {
        lowBattery = percent.coerceIn(MIN_LOW_BATTERY, MAX_LOW_BATTERY)
        log("Low battery level: $lowBattery%")
        publish()
    }

    /**
     * The speed the robot plans its own trips at (/cmd/max_speed), never over [MAX_RUNNING_SPEED].
     * Always sent, now, on every connection and at the start of every task, since the robot's own
     * app has its own setting, up to 0.8 m/s. Null: the default. The drive pad has its own, lower
     * limits ([MAX_LINEAR]).
     */
    fun setRunningSpeed(speed: Double?) {
        runningSpeed = speed?.coerceIn(MIN_RUNNING_SPEED, MAX_RUNNING_SPEED)
        runningSpeedOwed = true
        runningSpeedSentAt = Long.MIN_VALUE / 2
        publish()
    }

    private fun tickRunningSpeed() {
        val speed = runningSpeed ?: DEFAULT_RUNNING_SPEED
        val t = now()
        if (!runningSpeedOwed || link !is Link.Connected || t - runningSpeedSentAt < RUNNING_SPEED_RETRY_MS) return
        runningSpeedSentAt = t
        val a = api
        scope.launch {
            val r = a.post(RUNNING_SPEED_PATH, JSONObject().put("speed", speed).toString())
            val rejected = if (r.ok) robotError(r.body) else null
            if (r.ok && rejected == null) {
                if ((runningSpeed ?: DEFAULT_RUNNING_SPEED) == speed) runningSpeedOwed = false
                log(fmt("Running speed set to %.1f m/s", speed))
            } else {
                log("Running speed not set: ${r.error ?: rejected}")
            }
            publish()
        }
    }

    /** Cancels the countdown, or switches the lamps off early. */
    fun stopDisinfection() {
        endDisinfection("switched off from the app")
        publish()
    }

    private fun lampsOn(point: String?, forMs: Long) {
        uv = Uv.Starting(point, forMs)
        log("UV lamps: sending $UV_PATH $UV_ON")
        val a = api
        scope.launch {
            val r = lampLock.withLock { a.post(UV_PATH, UV_ON) }
            // Ended while ON was in flight: the OFF queued behind it has it covered.
            if (uv !is Uv.Starting) return@launch
            val rejected = if (r.ok) robotError(r.body) else null
            if (r.ok && rejected == null) {
                val t = now()
                uv = Uv.On(point, t, t + forMs)
                log("UV lamps ON for ${uvTimeText(forMs)}" + (point?.let { " at $it" } ?: ""))
            } else {
                // A timed-out ON may still have reached the robot, so switch off to be sure.
                endDisinfection("ON " + if (!r.ok) "FAILED ${r.error}" else "rejected: $rejected")
            }
            publish()
        }
    }

    /**
     * Ends the run, if any. If the lamps may be on, sends OFF, and keeps sending it (see
     * [tickDisinfection]) until the robot accepts it.
     */
    private fun endDisinfection(reason: String) {
        val run = uv ?: return
        uv = null
        val t = now()
        uvEnded = UvEnd(
            completed = run is Uv.On && t >= run.until,
            onMs = if (run is Uv.On) (min(t, run.until) - run.since).coerceAtLeast(0) else 0,
            reason = reason,
        )
        val where = run.point?.let { " at $it" } ?: ""
        if (run is Uv.Countdown) {
            log("Disinfection$where cancelled before the lamps came on ($reason)")
            return
        }
        log(
            when {
                run is Uv.On && t >= run.until -> "Disinfection$where done: UV lamps off"
                run is Uv.On -> "Disinfection$where ended after ${clockText(t - run.since)}: $reason. UV lamps off"
                else -> "Disinfection$where ended: $reason. UV lamps off"
            }
        )
        lampsOffOwed = true
        lampsOffFailures = 0
        sendLampsOff()
    }

    private fun sendLampsOff() {
        if (lampsOffInFlight) return
        lampsOffInFlight = true
        lampsOffLastSent = now()
        val a = api
        scope.launch {
            val r = lampLock.withLock { a.post(UV_PATH, UV_OFF) }
            lampsOffInFlight = false
            val rejected = if (r.ok) robotError(r.body) else null
            when {
                r.ok && rejected == null -> if (lampsOffOwed) {
                    lampsOffOwed = false
                    log("UV lamps: robot accepted OFF")
                }
                // One line, not one per retry. The banner stays up until OFF is accepted.
                lampsOffFailures++ == 0 -> log(
                    "UV lamps: OFF " + (if (!r.ok) "FAILED ${r.error}" else "rejected: $rejected") +
                        ". THE LAMPS MAY STILL BE ON. Retrying."
                )
            }
            publish()
        }
    }

    /** Runs on every poll: counts down, ends the run when it should, and retries an owed OFF. */
    private fun tickDisinfection(speedValid: Boolean) {
        val run = uv
        val t = now()
        if (run != null) {
            val moving = speedValid && (abs(vx) > MOVING_VX || abs(vth) > MOVING_VTH)
            when {
                link is Link.Disconnected -> endDisinfection("lost connection")
                (link as? Link.Connected)?.estop == EStop.PRESSED -> endDisinfection("e-stop pressed")
                moving -> endDisinfection("the robot moved")
                run is Uv.Countdown && t >= run.lampsOnAt -> lampsOn(run.point, run.forMs)
                run is Uv.On && t >= run.until -> endDisinfection("done")
            }
        }
        if (lampsOffOwed && link is Link.Connected && t - lampsOffLastSent >= LAMPS_OFF_RETRY_MS) sendLampsOff()
    }

    // ------------------------------------------------------------------ disinfection tasks

    /** The saved tasks, read back when the app starts. */
    fun setTasks(list: List<DisinfectionTask>) {
        tasks = list.map { it.coerced() }
        publish()
    }

    /**
     * Adds [task], or replaces the saved one with its id; a blank id is a new task. A task under
     * way keeps the copy it started with.
     */
    fun saveTask(task: DisinfectionTask) {
        val t = task.coerced().let { if (it.id.isBlank()) it.copy(id = UUID.randomUUID().toString()) else it }
        if (t.name.isEmpty() || t.stops.isEmpty()) return
        tasks = if (tasks.any { it.id == t.id }) tasks.map { if (it.id == t.id) t else it } else tasks + t
        log("Task \"${t.name}\" saved: ${t.stops.size} point(s), ${t.uvMinutes} min of UV")
        publish()
    }

    /** Not the task under way. */
    fun deleteTask(id: String) {
        if (taskRun?.task?.id == id) return
        val t = tasks.firstOrNull { it.id == id } ?: return
        tasks = tasks - t
        log("Task \"${t.name}\" deleted")
        publish()
    }

    /**
     * Runs a saved task, as the robot's own app does, one stop at a time, moved along by every
     * poll (see [tickTask]):
     *  1. to the stop's point with /cmd/nav_name, undocking first if the robot is on its pile;
     *     no trip if it is standing there already. Not reached: with the lamps off, it tries again
     *     for [TASK_RETRY_MS], then leaves the stop out, and the report names it (see [stopFailed]).
     *  2. the same UV run as START DISINFECTION: the task's countdown, then the lamps on for the
     *     stop's minutes, then off.
     *  3. on to the next stop only once the robot has accepted OFF: the lamps are never on while
     *     it drives.
     * Every stop, every round, then [WhenDone]. Whatever ends a UV run early (STOP, the e-stop,
     * the robot moving, lost connection, LAMPS OFF, closing the app) ends the task; so does the
     * e-stop at any time. Battery under the low level between stops ends it and sends the robot
     * to its charger. [pauseTask] stops it all until [resumeTask].
     */
    fun startTask(id: String) {
        val task = tasks.firstOrNull { it.id == id } ?: return
        snapshot().taskProblem(task)?.let {
            log("Task \"${task.name}\" not started: $it")
            publish()
            return
        }
        beginTask(task)
    }

    /** START DISINFECTION: every point on the map in turn, run like a saved task. */
    fun startRoute() {
        val s = snapshot()
        s.routeProblem?.let {
            log("Disinfection not started: $it")
            publish()
            return
        }
        beginTask(s.route)
    }

    /**
     * Every point on the map with the lamps off, then the charger: proves a route before any UV
     * runs on it. Run by the same engine as a task, so a point not reached is tried again and
     * left out, and PAUSE and STOP work; [startStopUv] never starts a lamp run for it.
     */
    fun startRouteTest() {
        val s = snapshot()
        s.routeProblem?.let {
            log("Route test not started: $it")
            publish()
            return
        }
        beginTask(DisinfectionTask.routeTest((s.points as Points.Loaded).list))
    }

    private fun beginTask(task: DisinfectionTask) {
        taskReport = null
        runningSpeedOwed = true
        taskRun = TaskRun(task, round = 1, index = 0, stage = TaskStage.Checking, results = emptyList())
        log(
            "Task \"${task.name}\" started: ${task.stops.size} point(s)" +
                (if (task.rounds > 1) " × ${task.rounds} rounds" else "") + if (task.lampsOff) ", lamps off" else ", ${task.uvMinutes} min of UV"
        )
        beginStop()
        publish()
    }

    /**
     * Holds the task where it is: the robot stops as for STOP, and the lamps go off with the stop's
     * UV time kept. The d-pad works meanwhile. On [resumeTask] the robot goes back to the stop if it
     * has to, and the full countdown runs again, since someone may have walked in.
     */
    fun pauseTask() {
        val s = snapshot()
        val run = taskRun ?: return
        if (!s.canPauseTask) return
        val t = now()
        var left = run.uvLeftMs
        var done = run.uvDoneMs
        if (run.stage == TaskStage.Disinfecting) {
            when (val u = uv) {
                is Uv.Countdown -> left = u.forMs
                is Uv.Starting -> left = u.forMs
                is Uv.On -> {
                    if (t >= u.until) return // its time is up: about to move on anyway
                    left = u.until - t
                    done += t - u.since
                }
                null -> return // its lamps have just gone off
            }
        }
        taskJob?.cancel()
        taskRun = run.copy(stage = TaskStage.Paused, retryUntil = null, uvLeftMs = left, uvDoneMs = done)
        forgetNavStatus() // the trip it cancels isn't a failure to report
        halt("Task paused", endRun = false)
    }

    fun resumeTask() {
        val run = taskRun ?: return
        if (!snapshot().canResumeTask) return
        log("Task resumed")
        taskRun = run.copy(stage = TaskStage.Checking)
        beginStop()
        publish()
    }

    /** Ends the task the way STOP does: the robot brakes, any trip is cancelled, the lamps go off. */
    fun endTaskNow() {
        if (taskRun != null) halt("Task ended from the app")
    }

    fun dismissTaskReport() {
        taskReport = null
        publish()
    }

    /** The current stop: low battery ends the task; else to the stop's point, unless the robot is on it. */
    private fun beginStop() {
        val run = taskRun ?: return
        val b = battery
        if (b != null && b < lowBattery) {
            endTask("battery low ($b%)")
            charge()
            return
        }
        val point = (points as? Points.Loaded)?.list?.firstOrNull { it.name == run.stop.point }
            ?: return giveUpOnStop("${run.stop.point} is no longer on the map")
        if (atPoint?.name == point.name) return startStopUv()
        // Standing on it already, though the app didn't see it arrive (say, the last task ended
        // here)? A trip there wouldn't move, and would read as "couldn't get there".
        val checking = run.copy(stage = TaskStage.Checking)
        taskRun = checking
        val a = api
        taskJob?.cancel()
        taskJob = scope.launch {
            val here = readPose(a)
            if (taskRun !== checking) return@launch // ended, or STOP, while asking
            val to = point.pose
            if (here != null && to != null && dist(here, to) <= ARRIVED_M && snapshot().still && !snapshot().busy) {
                atPoint = point
                startStopUv()
            } else {
                travel(point)
                val sent = phase != StepPhase.Idle || chargerJob?.isActive == true
                if (sent) taskRun = checking.copy(stage = TaskStage.Travelling)
                else stopFailed("Couldn't set off for ${point.name}")
            }
            publish()
        }
    }

    /** At the stop: the countdown starts, then [tickDisinfection] runs the lamps as for any run. */
    private fun startStopUv() {
        val run = taskRun ?: return
        if (!snapshot().canTravel) return stopFailed("Couldn't start the lamps at ${run.stop.point}")
        if (run.task.lampsOff) {
            log("Route test: reached ${run.stop.point} (${run.number} of ${run.total})")
            taskRun = run.copy(results = run.results + StopResult(run.round, run.stop.point, StopOutcome.DONE))
            return nextStop()
        }
        val stop = run.stop
        val forMs = run.uvLeftMs ?: (stop.minutes * 60_000L)
        uvEnded = null
        uv = Uv.Countdown(stop.point, now() + run.task.delaySeconds * 1000L, forMs)
        taskRun = run.copy(stage = TaskStage.Disinfecting, retryUntil = null)
        log("Task: at ${stop.point} (${run.number} of ${run.total}). UV lamps on in ${run.task.delaySeconds} s, for ${uvTimeText(forMs)}")
    }

    /** Runs on every poll, after [tickDisinfection]: moves the task on when the stop's stage is over. */
    private fun tickTask() {
        val run = taskRun ?: return
        if ((link as? Link.Connected)?.estop == EStop.PRESSED) return endTask("e-stop pressed")
        when (run.stage) {
            TaskStage.Checking, TaskStage.Paused -> Unit
            TaskStage.Docking -> {
                // A trip to the pile can pause between its parts, so it's over once idle a little while.
                if (phase != StepPhase.Idle || chargerJob?.isActive == true) {
                    dockIdleAt = null
                    return
                }
                val since = dockIdleAt ?: now().also { dockIdleAt = it }
                if (now() - since >= TEST_DOCK_SETTLE_MS) finishTask(run, if (onPile) null else TaskReport.NOT_DOCKED)
            }
            is TaskStage.Retrying -> {
                // Lost Wi-Fi says nothing about the point: its time starts again once the robot answers.
                if (link !is Link.Connected) {
                    taskRun = run.copy(stage = run.stage.copy(nextTryAt = now() + TASK_RETRY_GAP_MS), retryUntil = now() + TASK_RETRY_MS)
                    return
                }
                // Not while the d-pad is in use, or the robot is still slowing down.
                if (now() < run.stage.nextTryAt || phase != StepPhase.Idle || !snapshot().still) return
                if (now() >= (run.retryUntil ?: 0)) giveUpOnStop(run.stage.problem) else beginStop()
            }
            TaskStage.Travelling -> {
                // Still on its way, still undocking, or about to set off after undocking.
                if (phase != StepPhase.Idle || chargerJob?.isActive == true || goAfterUndock != null) return
                if (atPoint?.name == run.stop.point) startStopUv() else stopFailed("Couldn't get to ${run.stop.point}")
            }
            TaskStage.Disinfecting -> {
                if (uv != null) return
                val end = uvEnded
                if (end?.completed != true) return endTask(end?.reason ?: "the lamps went off")
                taskRun = run.copy(
                    stage = TaskStage.LampsOff,
                    results = run.results + StopResult(run.round, run.stop.point, StopOutcome.DONE, run.uvDoneMs + end.onMs),
                )
            }
            // OFF sent by endDisinfection, and resent until the robot accepts it.
            TaskStage.LampsOff -> if (!lampsOffOwed) nextStop()
        }
    }

    /** A trip failed: try it again soon, until the stop's time is up. Lamps off throughout. */
    private fun stopFailed(problem: String) {
        val run = taskRun ?: return
        val t = now()
        val until = run.retryUntil ?: (t + TASK_RETRY_MS).also {
            log("Task: $problem. Trying again for ${TASK_RETRY_MS / 1000} s")
            logDetail(problem)
        }
        taskRun = run.copy(stage = TaskStage.Retrying(problem, t + TASK_RETRY_GAP_MS), retryUntil = until)
    }

    /** Leaves the stop out and goes on; the report says it wasn't reached. */
    private fun giveUpOnStop(problem: String) {
        val run = taskRun ?: return
        log("Task: $problem. Going on without it")
        taskRun = run.copy(results = run.results + unfinished(run))
        nextStop()
    }

    /** What became of a stop left before its time ran: not reached, unless it had started there. */
    private fun unfinished(run: TaskRun) =
        if (run.uvLeftMs != null) StopResult(run.round, run.stop.point, StopOutcome.INTERRUPTED, run.uvDoneMs)
        else StopResult(run.round, run.stop.point, StopOutcome.NOT_REACHED)

    private fun nextStop() {
        val run = taskRun ?: return
        var round = run.round
        var index = run.index + 1
        if (index >= run.task.stops.size) {
            index = 0
            round++
        }
        if (round > run.task.rounds) return finishTask(run)
        taskRun = run.copy(round = round, index = index, stage = TaskStage.Checking, retryUntil = null, uvLeftMs = null, uvDoneMs = 0)
        beginStop()
    }

    /** The points are done. A route test goes on to try the charger, on its pile already or not. */
    private fun testDocking(run: TaskRun) {
        val docking = run.copy(stage = TaskStage.Docking)
        when {
            (points as? Points.Loaded)?.pile == null -> finishTask(docking, TaskReport.NO_PILE)
            onPile -> finishTask(docking)
            else -> {
                log("Route test: trying the charger")
                taskRun = docking
                dockIdleAt = null
                charge()
            }
        }
    }

    private fun finishTask(run: TaskRun, reason: String? = null) {
        if (run.task.lampsOff && run.stage != TaskStage.Docking) return testDocking(run)
        taskRun = null
        taskJob?.cancel()
        val report = TaskReport(run.task, completed = true, reason = reason, results = run.results, at = System.currentTimeMillis())
        taskReport = report
        log("Task \"${run.task.name}\" finished: ${report.done} of ${report.total} stop(s) " + (if (run.task.lampsOff) "reached" else "disinfected") + (reason?.let { ", $it" } ?: ""))
        when (run.task.whenDone) {
            WhenDone.CHARGER -> charge()
            WhenDone.START -> backToFirstPoint(run)
            WhenDone.STAY -> Unit
        }
    }

    /** To the task's first point, unless the robot is standing on it. */
    private fun backToFirstPoint(run: TaskRun) {
        val first = run.task.stops.first().point
        val point = (points as? Points.Loaded)?.list?.firstOrNull { it.name == first }
            ?: return log("Task: $first is no longer on the map, so the robot stays here")
        val a = api
        taskJob = scope.launch {
            val here = readPose(a)
            val to = point.pose
            if (here != null && to != null && dist(here, to) <= ARRIVED_M) atPoint = point
            else if (snapshot().canTravel) travel(point)
            else log("Task: couldn't set off back to $first: the robot isn't still")
            publish()
        }
    }

    /**
     * Ends the task under way, if any, and switches the lamps off if they may be on. The stop it
     * was at counts as interrupted (lamps on, or counting down) or not reached.
     */
    private fun endTask(reason: String) {
        val run = taskRun ?: return
        taskRun = null
        taskJob?.cancel()
        endDisinfection(reason)
        val here = when (run.stage) {
            TaskStage.Checking -> run.uvDoneMs.takeIf { it > 0 }?.let { unfinished(run) } // else never set off for it (low battery, or STOP before it moved)
            TaskStage.LampsOff, TaskStage.Docking -> null // already counted
            TaskStage.Disinfecting -> StopResult(run.round, run.stop.point, StopOutcome.INTERRUPTED, run.uvDoneMs + (uvEnded?.onMs ?: 0))
            TaskStage.Travelling, TaskStage.Paused, is TaskStage.Retrying -> unfinished(run)
        }
        logDetail("task ended at ${run.stage::class.simpleName}: $reason")
        val report = TaskReport(run.task, completed = false, reason = reason, results = run.results + listOfNotNull(here), at = System.currentTimeMillis())
        taskReport = report
        log("Task \"${run.task.name}\" ended: $reason. ${report.done} of ${report.total} stop(s) disinfected")
    }

    // ------------------------------------------------------------------ maps

    /** A map is on screen (or not). The robot's pose and the map image are only read while one is. */
    fun setMapVisible(visible: Boolean) {
        mapVisible = visible
        if (visible && mapImage == null) refreshImage()
    }

    /**
     * Every [MAP_TICK_MS]: the robot's mode and map in use every [MODE_EVERY] ticks, and while a
     * map is on screen, the robot's pose, plus the image when it changes (every [MAP_REFRESH_MS]
     * while mapping: the scan grows). A loop of its own, so a big image never holds up
     * [pollLoop]'s speed and e-stop reads.
     */
    private suspend fun CoroutineScope.mapLoop() {
        var tick = 0
        while (isActive) {
            val started = now()
            val a = api
            if (link is Link.Connected) {
                // Not while a map command runs: it sets the mode itself, and an answer read just
                // before the command landed would undo that.
                if ((tick % MODE_EVERY == 0 || modeStale) && mapStep == null) readMode(a)
                if (mapVisible) {
                    a.get(POSE_PATH).takeIf { it.ok }?.let { r -> parsePose(r.body)?.let { robotPose = it } }
                    if ((snapshot().mapping || wantImage) && started - imageAt >= MAP_REFRESH_MS) readImage(a)
                }
                publish()
            }
            tick++
            delay((MAP_TICK_MS - (now() - started)).coerceAtLeast(0))
        }
    }

    private suspend fun readMode(a: RobotApi) {
        modeStale = false
        val wasMapping = snapshot().mapping
        val m = a.get(MODE_PATH)
        if (m.ok) mode = parseMode(m.body) // null if this firmware doesn't say
        val c = a.get(CURRENT_MAP_PATH)
        val inUse = if (c.ok) parseCurrentMap(c.body) else null
        val before = currentMap
        if (inUse != null) {
            // Keep a name already known from the list if the robot sends none here.
            currentMap = if (inUse.alias.isBlank() && before?.id == inUse.id) before else inUse
            if (before == null) log("Map in use: ${inUse.label} (${inUse.id.take(8)})")
            if (before != null && before.id != inUse.id) {
                log("The robot's map in use is now ${inUse.label}")
                onMapChanged()
            }
        }
        val mapping = snapshot().mapping
        if (mapping != wasMapping) {
            log(if (mapping) "The robot is in mapping mode" else "The robot is navigating on its map")
            refreshImage()
        }
    }

    private var imageProblemLogged: String? = null

    private suspend fun readImage(a: RobotApi) {
        val r = a.get(MAP_PATH)
        imageAt = now()
        val rejected = if (r.ok) robotError(r.body) else null
        val image = if (r.ok && rejected == null) runCatching { parseMapImage(JSONObject(r.body)) }.getOrNull() else null
        if (image != null) {
            mapImage = image
            mapProblem = null
            wantImage = false
        } else {
            mapProblem = when {
                !r.ok -> "Couldn't read the map. Check the robot is connected."
                rejected != null -> "The robot has no map to show. Start one from the map's name above."
                else -> "Couldn't read the map. Try again in a moment."
            }
            if (mapProblem != imageProblemLogged) {
                imageProblemLogged = mapProblem
                log("Map image: ${r.error ?: rejected ?: "unexpected reply ${r.body.take(80)}"}")
            }
        }
    }

    /** Read the image again at the next tick. */
    private fun refreshImage() {
        wantImage = true
        imageAt = Long.MIN_VALUE / 2
    }

    /** Another map came into use: its image, and its own points. */
    private fun onMapChanged() {
        mapImage = null
        mapProblem = null
        refreshImage()
        pointsJob?.cancel()
        points = Points.NotLoaded
        walls = null
        atPoint = null
    }

    /** Reads the maps saved on the robot (/reeman/history_map). Read-only: safe at any time. */
    fun loadMaps() {
        if (maps == Maps.Loading) return
        maps = Maps.Loading
        publish()
        val a = api
        mapsJob?.cancel()
        mapsJob = scope.launch {
            val r = a.get(MAPS_PATH)
            val rejected = if (r.ok) robotError(r.body) else null
            val list = if (r.ok && rejected == null) runCatching { parseMaps(r.body) }.getOrNull() else null
            maps = when {
                list != null -> Maps.Loaded(list)
                !r.ok -> Maps.Failed(r.error ?: "no answer")
                rejected != null -> Maps.Failed("robot error $rejected")
                else -> Maps.Failed("unexpected reply: ${r.body.take(80)}")
            }
            // The map in use may come without its name (/reeman/current_map); the list may have it.
            val inUse = currentMap
            val listed = list?.firstOrNull { it.id == inUse?.id }
            if (inUse != null && inUse.alias.isBlank() && listed != null && listed.alias.isNotBlank()) {
                currentMap = inUse.copy(alias = listed.alias)
            }
            log(
                when (val m = maps) {
                    // Each map's name and the start of its id, so untitled ones can be told apart.
                    is Maps.Loaded -> "Loaded ${m.list.size} map(s): " +
                        m.list.joinToString { (if (it.alias.isBlank()) "untitled" else "\"${it.alias}\"") + " " + it.id.take(8) } +
                        when {
                            inUse == null -> ""
                            listed == null -> ". The map in use (${inUse.id.take(8)}) isn't in the list"
                            else -> ". In use: ${inUse.id.take(8)}"
                        }
                    is Maps.Failed -> "Couldn't load the maps: ${m.error}"
                    else -> "Maps: ?"
                }
            )
            publish()
        }
    }

    /**
     * Starts mapping (/cmd/set_mode): a new map from nothing, or [extend] the map in use (which
     * needs the robot to know where it is on it). The robot doesn't move by itself: it is driven
     * round by hand, then the scan is saved ([saveMap]) or dropped ([discardMapping]).
     */
    fun startMapping(extend: Boolean) {
        val target = currentMap
        val problem = mapProblemNow(mapping = false)
            ?: if (extend && target == null) "The map in use isn't known yet." else null
        runMapCommand(if (extend) "Extending ${target?.label} …" else "Starting a new map …", problem) { a ->
            val m = if (extend) RobotMode.EXTENDING else RobotMode.MAPPING
            mapCommand(a, SET_MODE_PATH, modeJson(m))?.let { return@runMapCommand failed("Couldn't start mapping", it) }
            mode = m
            mapBefore = currentMap?.id
            atPoint = null
            mapImage = null
            refreshImage()
            if (extend) "Extending ${target?.label}: drive the robot into the new area, then save."
            else "Mapping: drive the robot slowly round the whole area, then save."
        }
    }

    /**
     * Saves the scan (/cmd/save_map), goes back to navigating (/cmd/set_mode 2, as the API
     * asks), then names the map (/cmd/rename_map): a new map always, an extended one if [name]
     * changed. A new map is only named if the robot now reports a different map in use than
     * before mapping, so the old map is never renamed by mistake.
     */
    fun saveMap(name: String) {
        val clean = name.trim()
        val extending = mode == RobotMode.EXTENDING
        val problem = mapProblemNow(mapping = true)
            ?: if (clean.length > MAX_MAP_NAME) "Use a name of $MAX_MAP_NAME characters or fewer." else null
        runMapCommand("Saving the map …", problem) { a ->
            mapCommand(a, SAVE_MAP_PATH, "{}")?.let { return@runMapCommand failed("Couldn't save the map, and the robot is still mapping", it) }
            log("Map: saved")
            val nav = mapCommand(a, SET_MODE_PATH, modeJson(RobotMode.NAVIGATION))
            if (nav == null) mode = RobotMode.NAVIGATION
            onMapChanged()
            val inUse = a.get(CURRENT_MAP_PATH).takeIf { it.ok }?.let { parseCurrentMap(it.body) }
            if (inUse != null) currentMap = inUse
            val naming = when {
                clean.isEmpty() || inUse == null || clean == inUse.alias -> null
                !extending && inUse.id == mapBefore -> "the robot kept its old map in use, so the new one wasn't named. Find it in Maps"
                else -> mapCommand(a, RENAME_MAP_PATH, JSONObject().put("name", inUse.id).put("alias", clean).toString())
                    ?.let { "naming it failed: $it" }
                    ?: run {
                        currentMap = inUse.copy(alias = clean)
                        null
                    }
            }
            maps = Maps.NotLoaded
            // Marks go only onto the map that was scanned: the new one, once it is the one in use.
            val marks = when {
                pendingPoints.isEmpty() && pendingWalls.isEmpty() -> null
                !extending && (inUse == null || inUse.id == mapBefore) ->
                    "the ${pendingPoints.size + pendingWalls.size} marked spot(s) weren't saved, since the new map isn't in use"
                else -> saveMarks(a)
            }
            pendingPoints = emptyList()
            pendingWalls = emptyList()
            buildString {
                append("Map saved")
                currentMap?.let { append(" as ${it.label}") }
                if (naming != null) append("; $naming")
                if (marks != null) append("; $marks")
                if (nav != null) append(". Couldn't go back to navigating: $nav")
            }
        }
    }

    /** Leaves mapping without saving (/cmd/set_mode 2): the scan is dropped, the map in use unchanged. */
    fun discardMapping() {
        val extending = mode == RobotMode.EXTENDING
        runMapCommand("Leaving mapping …", mapProblemNow(mapping = true)) { a ->
            mapCommand(a, SET_MODE_PATH, modeJson(RobotMode.NAVIGATION))?.let { return@runMapCommand failed("Couldn't leave mapping", it) }
            mode = RobotMode.NAVIGATION
            mapImage = null
            refreshImage()
            pendingPoints = emptyList()
            pendingWalls = emptyList()
            if (extending) "Mapping stopped. ${currentMap?.label ?: "The map"} is as it was." else "Mapping stopped. The scan wasn't saved."
        }
    }

    /**
     * Switches the robot to [map] (/cmd/apply_map). The robot then assumes it stands on that map's
     * charging pile (as documented), so unless it does, its position must be set after.
     */
    fun useMap(map: SavedMap) {
        val problem = mapProblemNow(mapping = false)
            ?: if (map.id == currentMap?.id) "${map.label} is already in use." else null
        runMapCommand("Switching to ${map.label} …", problem) { a ->
            mapCommand(a, APPLY_MAP_PATH, JSONObject().put("name", map.id).toString())
                ?.let { return@runMapCommand failed("Couldn't switch to ${map.label}", it) }
            currentMap = map.copy(thumbnail = null)
            onMapChanged()
            "Now using ${map.label}. The robot assumes it's on its charging pile: if it isn't, set its position."
        }
    }

    fun renameMap(map: SavedMap, name: String) {
        val clean = name.trim()
        val problem = mapProblemNow(mapping = null) ?: when {
            clean.isEmpty() -> "Give the map a name."
            clean.length > MAX_MAP_NAME -> "Use a name of $MAX_MAP_NAME characters or fewer."
            else -> null
        }
        runMapCommand("Renaming ${map.label} …", problem) { a ->
            mapCommand(a, RENAME_MAP_PATH, JSONObject().put("name", map.id).put("alias", clean).toString())
                ?.let { return@runMapCommand failed("Couldn't rename ${map.label}", it) }
            if (currentMap?.id == map.id) currentMap = currentMap?.copy(alias = clean)
            (maps as? Maps.Loaded)?.let { m -> maps = Maps.Loaded(m.list.map { if (it.id == map.id) it.copy(alias = clean) else it }) }
            "Renamed ${map.label} to $clean"
        }
    }

    /**
     * Moves [map] to the robot's bin (/cmd/trash_map), where the robot keeps it 30 days;
     * [restoreMap] brings it back. Never the map in use (the robot refuses that too).
     */
    fun deleteMap(map: SavedMap) {
        val problem = mapProblemNow(mapping = null)
            ?: if (map.id == currentMap?.id) "Switch to another map before deleting ${map.label}." else null
        runMapCommand("Deleting ${map.label} …", problem) { a ->
            mapCommand(a, TRASH_MAP_PATH, JSONObject().put("name", map.id).toString())
                ?.let { return@runMapCommand failed("Couldn't delete ${map.label}", it) }
            trashed = map
            (maps as? Maps.Loaded)?.let { m -> maps = Maps.Loaded(m.list.filterNot { it.id == map.id }) }
            "Moved ${map.label} to the robot's bin. It stays there 30 days."
        }
    }

    /** Brings back the last map moved to the bin (/cmd/restore_map). */
    fun restoreMap() {
        val map = trashed ?: return
        runMapCommand("Restoring ${map.label} …", mapProblemNow(mapping = null)) { a ->
            mapCommand(a, RESTORE_MAP_PATH, JSONObject().put("name", map.id).toString())
                ?.let { return@runMapCommand failed("Couldn't restore ${map.label}", it) }
            trashed = null
            maps = Maps.NotLoaded
            "Restored ${map.label}"
        }
    }

    /**
     * Tells the robot where it stands on the map in use (/cmd/reloc_pose), for example after
     * switching maps away from the pile. GUESS: reloc_pose (rather than reloc_absolute) lets the
     * robot refine the position against its laser scan.
     */
    fun setPosition(pose: Pose) {
        runMapCommand("Setting the robot's position …", mapProblemNow(mapping = false)) { a ->
            val body = JSONObject().put("x", round3(pose.x)).put("y", round3(pose.y)).put("theta", round3(pose.theta)).toString()
            mapCommand(a, RELOC_PATH, body)?.let { return@runMapCommand failed("Couldn't set the position", it) }
            atPoint = null
            fmt("Position set: x %.2f, y %.2f, facing %.0f°", pose.x, pose.y, Math.toDegrees(pose.theta))
        }
    }

    /**
     * Why a map command can't run now, or null. [mapping]: true = only while the robot is
     * mapping, false = only while it isn't, null = either.
     */
    private fun mapProblemNow(mapping: Boolean?): String? {
        val s = snapshot()
        return when {
            s.mapStep != null -> "Wait for \"${s.mapStep}\" to finish."
            !s.connected -> "Connect to the robot first."
            s.busy || !s.still -> "Wait for the robot to stand still."
            s.uv != null || s.lampsOffOwed -> "Switch the UV lamps off first."
            mapping == true && !s.mapping -> "The robot isn't mapping."
            mapping == false && s.mapping -> "Save or discard the map being made first."
            else -> null
        }
    }

    /**
     * Runs one map command at a time, unless there is a [problem]. [step] shows while it runs;
     * what [block] returns is logged and shown with the map.
     */
    private fun runMapCommand(step: String, problem: String?, block: suspend (RobotApi) -> String) {
        if (problem != null) {
            mapMessage = problem
            publish()
            return
        }
        mapStep = step
        mapMessage = null
        log(step)
        publish()
        val a = api
        mapCommandJob = scope.launch {
            val result = block(a)
            log(result)
            mapMessage = result
            mapStep = null
            modeStale = true
            publish()
        }
    }

    /** POSTs one map command: null if the robot accepted it, else what went wrong. */
    private suspend fun mapCommand(a: RobotApi, path: String, json: String): String? {
        log("Map: sending $path $json")
        val r = a.post(path, json)
        val rejected = if (r.ok) robotError(r.body) else null
        return when {
            !r.ok -> "FAILED ${r.error}"
            rejected != null -> "robot rejected: $rejected"
            else -> null
        }
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
        } else if (taskRun != null) {
            log("End the task before resetting the DEMO robot.")
        } else if (uv != null || lampsOffOwed) {
            log("Switch the UV lamps off before resetting the DEMO robot.")
        } else {
            demoRobot.reset()
            modeStale = true
            refreshImage()
            maps = Maps.NotLoaded
            trashed = null
            log("DEMO robot reset: centre of the room, its own maps, no faults")
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
        searching = searchJob?.isActive == true,
        foundRobots = foundRobots,
        searchNote = searchNote,
        robotName = robotName,
        version = version,
        battery = battery,
        vx = vx,
        vth = vth,
        phase = phase,
        points = points,
        addingPoint = addingPoint,
        pointMessage = pointMessage,
        runaway = runaway,
        atPoint = atPoint,
        uv = uv,
        uvLeftMs = when (val u = uv) {
            is Uv.Countdown -> (u.lampsOnAt - now()).coerceAtLeast(0)
            is Uv.On -> (u.until - now()).coerceAtLeast(0)
            else -> null
        },
        uvSettings = uvSettings,
        lowBattery = lowBattery,
        runningSpeed = runningSpeed,
        lampsOffOwed = lampsOffOwed,
        demoStatus = if (demo) DemoStatus(
            demoRobot.isEstopPressed, demoRobot.isOffline, demoRobot.isBlockNextPending,
            demoRobot.isNavigating, demoRobot.isBusy, demoRobot.isUvOn,
        ) else null,
        log = logLines.toList(),
        mode = mode,
        currentMap = currentMap,
        mapImage = mapImage,
        mapProblem = mapProblem,
        robotPose = robotPose,
        maps = maps,
        mapStep = mapStep,
        mapMessage = mapMessage,
        trashed = trashed,
        walls = walls,
        pendingPoints = pendingPoints,
        pendingWalls = pendingWalls,
        docked = onPile,
        robotAlert = robotAlert,
        tasks = tasks,
        taskRun = taskRun,
        taskReport = taskReport,
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

        // ---- Hold-to-drive (see hold()): the speed stream and its ramp. Same speeds as the steps. ----
        // The ramp, both ways. Stopping from 0.3 m/s takes 0.75 s and ~11 cm (+ ~6 cm latency). The
        // firmware's own planned braking is ~0.2-0.3 m/s^2; a jump to zero is 7-20 m/s^2.
        const val JOG_ACCEL = 0.4           // m/s^2: 0.02 m/s per command
        const val JOG_ANGULAR_ACCEL = 0.8   // rad/s^2: 0.04 rad/s per command
        // Letting go slows down twice as fast as pressing speeds up (2026-10-14: at the same rate,
        // short presses ran on well past where the finger lifted). Still far gentler than a bare 0.
        const val JOG_BRAKE = 0.8           // m/s^2: from 0.3 m/s to rest in ~0.35 s
        const val JOG_ANGULAR_BRAKE = 1.6   // rad/s^2
        // From rest the first command is this, not one stair: a stair's 0.02 m/s barely turns the
        // wheels. Slowing down, from here the next command is zero.
        const val JOG_START_VX = 0.05       // m/s
        const val JOG_START_VTH = 0.1       // rad/s
        // A tap (let go sooner than TAP_MS) nudges by a fixed small step instead (see release()).
        const val TAP_MS = 250L
        const val NUDGE_CM = 5
        const val NUDGE_DEG = 5
        const val NUDGE_SPEED = 0.15        // m/s
        const val NUDGE_TURN_SPEED = 0.3    // rad/s
        const val JOG_TICK_MS = 50L         // a command every 50 ms (the robot's watchdog needs < ~1 s)
        const val JOG_FINAL_ZEROS = 1       // zeros sent again after the ramp reaches zero
        const val JOG_FAILS = 3             // failed commands in a row before a hold ends
        const val JOG_GIVE_UP = 8           // ...and before the stream stops trying
        const val HOLD_ALIVE_MS = 100L      // the screen reports a held arrow this often...
        const val HOLD_ALIVE_TIMEOUT_MS = 400L // ...and after this long without word, it counts as let go
        const val HOLD_NO_MOTION_MS = 2500L // no motion this long after a press (or a let-go): give up
        const val HOLD_MAX_MS = 30_000L     // one press drives this long at most
        const val HOLD_COAST_MAX_MS = 5000L // still moving this long after letting go: the app sends STOP
        const val SPEED_PATH = "/cmd/speed"
        private val ZERO = Velocity(0.0, 0.0)
        private const val JOG_RECENT_MS = 30_000L // STOP zeroes the speed if it was streamed this recently

        // ---- Polling ----
        const val POLL_MS = 300L        // /reeman/speed
        const val BASE_EVERY = 5        // /reeman/base_encode every 5th poll (~1.5 s)
        const val FAILS_BEFORE_DISCONNECT = 3
        const val SEARCH_EVERY_MS = 20_000L // while the robot isn't answering, look for it this often

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
        private const val DOCK_CANCEL = """{"type":1,"point":"charging_pile"}"""
        /** /cmd/charge with the pile's point name on the map in use. */
        private fun chargeJson(type: Int, pile: String) = JSONObject().put("type", type).put("point", pile).toString()
        private const val CHARGE_DOCKED = 2             // chargeFlag: charging on the pile
        private const val CHARGE_DOCKING = 8            // chargeFlag: connecting to the pile
        private const val CHARGE_NOT_FOUND = 9          // chargeFlag: no pile found while docking
        // UNDOCK only pulls away within this distance of the pile's point: farther, chargeFlag's
        // "docked" is stale. GUESS: kept short.
        const val DOCK_NEAR_M = 1.5
        private const val AT_PILE_M = 0.3               // this close, the robot is already at the pile
        // UNDOCK (see undock()). GUESS, all three: not yet tried on the real robot. Enough to clear
        // the pile's contacts, slower than a d-pad step.
        const val UNDOCK_CM = 15
        const val UNDOCK_SPEED = 0.15                   // m/s
        const val UNDOCK_SETTLE_MS = 1500L              // after ending charging, before moving
        const val UNDOCK_LABEL = "Undock"

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
        // GO TO CHARGER lines up at this saved point before docking (see lineUp). Hidden from the
        // points list. "normal" is the API's route point type.
        const val APPROACH_POINT = "charging_approach"
        private const val APPROACH_TYPE = "normal"
        // GUESS, both: the robot refused type 2 from close by and drove it from 6.5 m (2026-10-15),
        // and docked from 0.4 m in front of the pile.
        private const val NEAR_PILE_M = 3.0
        private const val APPROACH_M = 0.8
        private const val LINED_UP_RAD = 0.5            // ~30°: facing near enough away from the pile to dock
        const val LINE_UP_LABEL = "Go to charger (lining up)"
        // ---- The robot's report on its latest trip (see onNavStatus). ----
        private const val NAV_STATUS_PATH = "/reeman/nav_status"
        private const val NAV_START = 6
        private const val NAV_END = 3
        private const val NAV_SETTLE_MS = 1_000L        // the robot updated it within 0.5 s in its log
        private const val RECOVERY_QUIET_MS = 15_000L
        const val ARRIVED_M = 0.3                       // GUESS: how close counts as arrived
        private const val ADD_POINT_PATH = "/cmd/position"
        // The API allows delivery, normal (route point), production and charge. A disinfection
        // point is a destination, so "delivery". GUESS: what the robot's own app uses is unknown.
        const val NEW_POINT_TYPE = "delivery"
        const val MAX_POINT_NAME = 32
        private const val PERSON_WAIT_S = 5.0           // DEMO: how long a person blocks the way

        // ---- UV lamps (REEMAN SLAM WEB API, "Power board external power supply interface") ----
        // {"type":"24v"|"36v"|"24v36","operation":"1"|"0"}: switches the power board's external
        // supply on or off. There is no way to read it back.
        const val UV_PATH = "/cmd/external_power_supply"
        // The lamps are on the 24 V supply. Confirmed on the real robot 2026-10-12 (firmware
        // RSNF1-v5.1.20_01): ON lit them, OFF switched them off. Firmware v5.1.12 accepted the
        // same bodies but never switched the supply.
        const val UV_SUPPLY = "24v"
        private const val UV_ON = """{"type":"$UV_SUPPLY","operation":"1"}"""
        private const val UV_OFF = """{"type":"$UV_SUPPLY","operation":"0"}"""
        const val MAX_UV_MINUTES = 30
        // Every run starts with a countdown (START NOW skips it), so there is no 0 s delay.
        const val MIN_UV_DELAY_S = 15
        const val MAX_UV_DELAY_S = 120
        const val LAMPS_OFF_RETRY_MS = 1_500L
        // ---- Disinfection tasks (see startTask). The robot's own head-unit app, always running,
        // sends it to charge by itself at its "low_power" setting: 20% on this robot (its prefs,
        // read 2026-10-16). Both limits sit above that, so a task ends cleanly before that app
        // takes the robot away mid-task. The margins themselves are a GUESS. ----
        const val DEFAULT_LOW_BATTERY = 25  // % between stops: the task ends and the robot goes to charge
        const val MIN_LOW_BATTERY = 25      // settable from here up; lower and the robot's own app acts first
        const val MAX_LOW_BATTERY = 70
        const val TASK_START_MARGIN = 5     // % over that to start a task
        // A stop not reached is tried again every GAP for this long after the first failure, then left out.
        const val TASK_RETRY_MS = 30_000L
        const val TASK_RETRY_GAP_MS = 5_000L
        // /cmd/max_speed takes 0.3-1.0 m/s and the robot's own app offers up to 0.8; here nothing
        // goes over 0.5 (the user's limit, 2026-10-30). The robot's own setting was 0.6, so the
        // default can't be "leave it as it is": it's the limit.
        const val MIN_RUNNING_SPEED = 0.3
        const val MAX_RUNNING_SPEED = 0.5
        const val DEFAULT_RUNNING_SPEED = MAX_RUNNING_SPEED
        private const val RUNNING_SPEED_PATH = "/cmd/max_speed"
        private const val RUNNING_SPEED_RETRY_MS = 5_000L

        // ---- Maps (REEMAN SLAM WEB API, "map module"). The documented reads /robot/get_mode,
        // /robot/map and /robot/history_map answered 404 on this robot (probe 2026-09-23,
        // v5.1.12); the same reads answered under /reeman/. Not yet re-checked on v5.1.20. ----
        private const val MODE_PATH = "/reeman/get_mode"         // {"mode":1|2|3}
        private const val CURRENT_MAP_PATH = "/reeman/current_map" // {"name":id,"alias":"..."}
        // {"hostname":"wbot11u-250101-003-007"}, unique per robot (the API PDF misprints it /reemant/).
        const val HOSTNAME_PATH = "/reeman/hostname"
        private const val MAP_PATH = "/reeman/map"               // size, resolution, origin, PNG data URL
        private const val MAPS_PATH = "/reeman/history_map"      // {"maps":[{name,alias,map{...}}]}
        private const val SET_MODE_PATH = "/cmd/set_mode"
        private const val SAVE_MAP_PATH = "/cmd/save_map"
        private const val APPLY_MAP_PATH = "/cmd/apply_map"
        private const val RENAME_MAP_PATH = "/cmd/rename_map"
        private const val TRASH_MAP_PATH = "/cmd/trash_map"
        private const val RESTORE_MAP_PATH = "/cmd/restore_map"
        private const val RELOC_PATH = "/cmd/reloc_pose"
        private const val POSE_PATH = "/reeman/pose"
        const val MAP_TICK_MS = 500L        // the robot's pose updates at ~5 Hz
        const val MODE_EVERY = 6            // mode and map in use every ~3 s
        const val MAP_REFRESH_MS = 2000L    // the image, at most this often (while mapping)
        const val MAX_MAP_NAME = 32
        private const val BASE64_MARK = "base64,"
        // Virtual walls. The documented read (/reeman/restrictt_layer) answered 009 on this robot;
        // /reeman/restrict_layer answered (probe 2026-09-23). Writing sends the whole set.
        private const val WALLS_PATH = "/reeman/restrict_layer"
        private const val SET_WALLS_PATH = "/cmd/restrict_layer"
        const val MIN_WALL_M = 0.2
        private const val PILE_TYPE = "charge"

        /** {"waypoints":[{"pose":{"point1":{"x","y"},"point2":{"x","y"}}}]}; null "waypoints" = none. */
        private fun parseWalls(body: String): List<Wall> {
            val list = JSONObject(body).optJSONArray("waypoints") ?: return emptyList()
            return (0 until list.length()).map { i ->
                val pose = list.getJSONObject(i).getJSONObject("pose")
                val p1 = pose.getJSONObject("point1")
                val p2 = pose.getJSONObject("point2")
                Wall(p1.getDouble("x"), p1.getDouble("y"), p2.getDouble("x"), p2.getDouble("y"))
            }
        }

        private fun wallsJson(list: List<Wall>): String {
            val arr = org.json.JSONArray()
            list.forEach { w ->
                arr.put(
                    JSONObject().put(
                        "pose",
                        JSONObject()
                            .put("point1", JSONObject().put("x", round3(w.x1)).put("y", round3(w.y1)))
                            .put("point2", JSONObject().put("x", round3(w.x2)).put("y", round3(w.y2))),
                    )
                )
            }
            return JSONObject().put("waypoints", arr).toString()
        }

        /** The robot may round what it stores: match walls to the centimetre. */
        private fun sameWall(a: Wall, b: Wall) =
            abs(a.x1 - b.x1) < 0.01 && abs(a.y1 - b.y1) < 0.01 && abs(a.x2 - b.x2) < 0.01 && abs(a.y2 - b.y2) < 0.01

        private fun modeJson(m: RobotMode) = """{"mode":${m.code}}"""

        private fun parseMode(body: String): RobotMode? =
            runCatching { RobotMode.of(JSONObject(body).getInt("mode")) }.getOrNull()

        /** {"hostname":"..."}; null for an error reply. */
        fun parseHostname(body: String): String? =
            runCatching { JSONObject(body).getString("hostname").trim() }.getOrNull()?.takeIf { it.isNotEmpty() }

        /** {"name":id,"alias":"..."}; null for an error reply. */
        private fun parseCurrentMap(body: String): SavedMap? = runCatching {
            val o = JSONObject(body)
            SavedMap(o.getString("name"), o.optString("alias"))
        }.getOrNull()?.takeIf { it.id.isNotEmpty() }

        /** {"width","height","resolution","origin_x","origin_y","image_url":"data:image/png;base64,..."} */
        private fun parseMapImage(o: JSONObject): MapImage? {
            val url = o.optString("image_url")
            val at = url.indexOf(BASE64_MARK)
            if (at < 0) return null
            return MapImage(
                o.getInt("width"), o.getInt("height"), o.optDouble("resolution", 0.0),
                o.optDouble("origin_x", 0.0), o.optDouble("origin_y", 0.0), url.substring(at + BASE64_MARK.length),
            )
        }

        private fun parseMaps(body: String): List<SavedMap> {
            val list = JSONObject(body).optJSONArray("maps") ?: return emptyList()
            return (0 until list.length()).map { i ->
                val o = list.getJSONObject(i)
                val map = o.optJSONObject("map")
                // GUESS: the alias may sit with the image on some firmware.
                val alias = o.optString("alias").ifBlank { map?.optString("alias").orEmpty() }
                SavedMap(o.getString("name"), alias, map?.let { runCatching { parseMapImage(it) }.getOrNull() })
            }
        }

        /**
         * Every saved point, the pile included: {"waypoints":[{name,type,pose{x,y,theta}}]}. The pile
         * is matched by name, and by its API type "charge" in case it was renamed (see [isPile]).
         */
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

        /** One /reeman/nav_status report; [key] tells two reports apart. */
        private class NavStatus(val res: Int, val reason: Int, val goal: String, val key: String)

        private fun parseNavStatus(body: String): NavStatus? = runCatching {
            val o = JSONObject(body)
            if (!o.has("res")) return null
            val res = o.getInt("res")
            val reason = o.optInt("reason", 0)
            val goal = o.optString("goal")
            NavStatus(res, reason, goal, "$res/$reason/$goal/${o.opt("dist")}/${o.opt("mileage")}")
        }.getOrNull()

        /**
         * Why the robot refused a trip (nav_status res 6). From the Web API manual's list; the
         * robot sends them negative (-1 seen on 2026-10-15), so either sign is read.
         */
        private const val DOCK_ADVICE = "Couldn't dock. Drive the robot in front of its charger, then press GO TO CHARGER."
        private const val CONFIRM_ADVICE = "The robot didn't set off. If its screen is asking, press Confirm, then press GO TO CHARGER again."
        const val TEST_DOCK_SETTLE_MS = 3_000L

        /** A refused trip: what staff can do about it, not what the code means (that's [refusal], for the log). */
        private fun refusalAdvice(reason: Int, goal: String) = when (abs(reason)) {
            1 -> "Couldn't go to $goal: it's too close to the charger. Drive it away a little, then try again."
            2 -> "Couldn't go to $goal. Release the e-stop, then try again."
            3 -> "Couldn't go to $goal. Unplug the charging cable, then try again."
            4 -> "$goal isn't on this map. Add the point again, or choose another."
            5 -> DOCK_ADVICE
            6 -> "The robot has lost its place. Set its position on the Map page, then try again."
            else -> "Couldn't go to $goal. Clear the way, then try again. If it keeps happening, restart the robot."
        }

        private fun refusal(reason: Int) = when (abs(reason)) {
            1 -> "it went to dock where it stood instead. Too close to the pile?"
            2 -> "the e-stop is pressed"
            3 -> "it is charging from the adapter"
            4 -> "the point isn't on the map"
            5 -> "docking failed"
            6 -> "it doesn't know where it is on the map"
            7 -> "the route's points are too far apart"
            8 -> "the route wasn't found"
            9 -> "it couldn't read the point"
            else -> "code $reason"
        }

        /** The turn from heading [a] to heading [b], in -π..π. */
        private fun turnBetween(a: Double, b: Double): Double {
            var d = (b - a) % (2 * Math.PI)
            if (d > Math.PI) d -= 2 * Math.PI
            if (d < -Math.PI) d += 2 * Math.PI
            return d
        }

        private fun isPile(w: Waypoint) = w.name == PILE_POINT || "charg" in w.type.lowercase(Locale.US)

        private fun fmt(format: String, vararg args: Any) = String.format(Locale.US, format, *args)

        private fun speedJson(v: Velocity) = fmt("""{"vx":%.3f,"vth":%.3f}""", v.vx, v.vth)

        private fun speedText(v: Velocity) =
            if (v.vth != 0.0) fmt("%.2f rad/s", abs(v.vth)) else fmt("%.2f m/s", abs(v.vx))

        /** One stair of the ramp: from [from] towards [to], by at most one command's worth. */
        private fun ramp(from: Velocity, to: Velocity) = Velocity(
            stair(from.vx, to.vx, JOG_ACCEL, JOG_BRAKE, JOG_START_VX),
            stair(from.vth, to.vth, JOG_ANGULAR_ACCEL, JOG_ANGULAR_BRAKE, JOG_START_VTH),
        )

        /**
         * One axis of [ramp]. From rest it starts at [start] at once (slower barely turns the
         * wheels, and only delays the start); it speeds up at [up] and slows down at [down], the
         * quicker of the two, so letting go stops it sooner. At or below [start], slowing to a
         * stop goes straight to zero. Reversing slows to zero first, then starts the other way.
         */
        private fun stair(a: Double, b: Double, up: Double, down: Double, start: Double): Double {
            if (a == b) return b
            val tick = JOG_TICK_MS / 1000.0
            val away = a == 0.0 || (sign(a) == sign(b) && abs(b) > abs(a))
            if (away) return if (abs(a) < start) sign(b) * min(start, abs(b)) + 0.0 else toward(a, b, up * tick)
            val goal = if (b != 0.0 && sign(b) == sign(a)) b else 0.0
            val next = toward(a, goal, down * tick)
            return if (goal == 0.0 && abs(next) <= start) 0.0 else next
        }

        private fun toward(a: Double, b: Double, step: Double) =
            (if (abs(b - a) <= step + 1e-9) b else a + step * sign(b - a)) + 0.0 // + 0.0: never -0.0

        private fun round3(v: Double) = Math.round(v * 1000) / 1000.0

        private fun dist(a: Pose, b: Pose) = hypot(a.x - b.x, a.y - b.y)

        private fun parsePose(body: String): Pose? = runCatching {
            val o = JSONObject(body)
            Pose(o.getDouble("x"), o.getDouble("y"), o.optDouble("theta", 0.0))
        }.getOrNull()

        /** Every saved point, the pile included, or null if they couldn't be read. */
        private suspend fun readWaypoints(a: RobotApi): List<Waypoint>? =
            a.get(POINTS_PATH).takeIf { it.ok }?.let { r -> runCatching { parseWaypoints(r.body) }.getOrNull() }

        private suspend fun readPose(a: RobotApi): Pose? = a.get("/reeman/pose").takeIf { it.ok }?.let { parsePose(it.body) }

        /** 135_000 -> "2:15" */
        /** "5 min", or the clock's "1:30" for what a pause left. */
        private fun uvTimeText(ms: Long): String = if (ms % 60_000 == 0L) "${ms / 60_000} min" else clockText(ms)

        fun clockText(ms: Long): String {
            val s = (ms + 999) / 1000
            return String.format(Locale.US, "%d:%02d", s / 60, s % 60)
        }

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
