package com.expiation.reemanremote

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.Random
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Demo mode: a simulated Reeman navigation computer that lives inside the app.
 *
 * It answers the same paths with the same JSON shapes as firmware RSNF1-v5.1.12_01 and v5.1.20_01
 * (REEMAN_HANDOFF.txt section 4), so DriveController's polling, step lock and STOP
 * logic run unchanged, and nothing is sent over the network.
 *
 * Motion follows the measured /cmd/move behaviour (section 5): ~200 ms command
 * latency, then a trapezoid with accel/decel of about 0.36 * speed^0.40 m/s^2.
 * Velocity streaming (/cmd/speed) follows the jog test (section 6): ~200 ms latency, then the
 * wheels take the new speed at once (no ramp), and a watchdog hard-stops the robot ~1.16 s
 * after the last command.
 * Maps are just enough to show the map screens in DEMO: one fixed picture of the room, a list of
 * maps, and map commands that are accepted. Nothing about mapping itself is simulated.
 * Anything marked GUESS was never measured on the real robot.
 *
 * No org.json, so it runs on a plain JVM.
 *
 * @param clock monotonic time in nanoseconds
 * @param realistic adds request latency and speed noise; off for deterministic tests
 */
class FakeRobot(
    private val clock: () -> Long = System::nanoTime,
    private val rng: Random = Random(),
    private val realistic: Boolean = true,
) {

    class Reply(val code: Int, val body: String, val latencyMs: Long)

    /** What /reeman/hostname answers (see [RobotName]); tests give fakes different ones. */
    var hostname = HOSTNAME

    /** One planned step: ramp up, cruise, ramp down, like the firmware's own planner. */
    private class Motion(
        val turn: Boolean,
        val sign: Double,   // +1 forward / anticlockwise, -1 backward / clockwise
        amount: Double,
        speed: Double,
        val accel: Double,
        val startNs: Long,
    ) {
        val peak: Double    // m/s or rad/s actually reached
        val tCruise: Double
        val tRamp: Double
        val tEnd: Double    // seconds after startNs

        init {
            val rampAmount = speed * speed / accel // ramp up + ramp down at full speed
            if (rampAmount >= amount) {
                peak = sqrt(amount * accel)       // too short to reach full speed
                tCruise = 0.0
            } else {
                peak = speed
                tCruise = (amount - rampAmount) / speed
            }
            tRamp = peak / accel
            tEnd = 2 * tRamp + tCruise
        }

        fun speedAt(nowNs: Long): Double {
            val t = (nowNs - startNs) / 1e9
            return when {
                t <= 0 || t >= tEnd -> 0.0
                t < tRamp -> accel * t
                t < tRamp + tCruise -> peak
                else -> accel * (tEnd - t)
            }
        }

        fun finished(nowNs: Long) = (nowNs - startNs) / 1e9 >= tEnd
    }

    private var lastNs = 0L
    private var x = 0.0
    private var y = 0.0
    private var theta = 0.0
    private var motion: Motion? = null
    // Legs still to run after [motion], each planned when it starts (from where the robot is then):
    // docking's reverse after its turn, a navigation's drive and final turn.
    private val legs = ArrayDeque<(Long) -> Motion>()
    private var navGoal: SavedPoint? = null // set while navigating
    private var navThen: ((Long) -> Motion)? = null // what follows the navigation: docking, for charge type 2
    // The maps saved on the robot, the one in use, and its bin. Points belong to a map.
    private class SimMap(val id: String, var alias: String, val grid: ByteArray, val points: MutableList<SavedPoint>) {
        var walls = """{"waypoints":[]}""" // kept as sent: DEMO only echoes it back
    }
    private val maps = mutableListOf<SimMap>()
    private val bin = mutableListOf<SimMap>()
    private lateinit var current: SimMap
    private val points: MutableList<SavedPoint> get() = current.points // saved on the map in use
    private var mode = MODE_NAVIGATION
    private var mapCount = 0
    private var chargeFlag = CHARGE_NONE

    /** What /reeman/nav_status answers, set by tests; null answers as the idle robot does. */
    var navStatus: String? = null
    private var battery = 0.0
    private var estopPressed = false
    private var offline = false
    private var blockNext = false
    private var power24 = false     // the power board's external supplies
    private var power36 = false
    // Velocity streaming (/cmd/speed): the speed the wheels follow now, commands still inside their
    // latency (applied at [JogCmd.atNs]), and when the last one took effect (for the watchdog).
    private class JogCmd(val atNs: Long, val vx: Double, val vth: Double)
    private var jogVx = 0.0
    private var jogVth = 0.0
    private val jogQueue = ArrayDeque<JogCmd>()
    private var jogLastNs = 0L
    private var poseX = 0.0
    private var poseY = 0.0
    private var poseTheta = 0.0
    private var poseNs = 0L

    init {
        reset()
    }

    // ------------------------------------------------------------------ requests

    /** Answers one HTTP request. Returns null when a Wi-Fi drop is being simulated. */
    @Synchronized
    fun handle(method: String, path: String, json: String?): Reply? {
        if (offline) return null
        val now = clock()
        advance(now)

        val body = when (method) {
            "GET" -> get(path, now)
            "POST" -> post(path, json.orEmpty(), now)
            else -> null
        }
        val latency = if (realistic) 15L + rng.nextInt(30) else 0L
        return if (body == null) Reply(404, error("009", "Request path incorrect."), latency)
        else Reply(200, body, latency)
    }

    private fun get(path: String, now: Long): String? = when (path) {
        "/reeman/current_version" -> """{"version":"$VERSION"}"""
        "/reeman/hostname" -> """{"hostname":"$hostname"}"""
        "/reeman/base_encode" -> fmt(
            """{"battery":%d,"chargeFlag":%d,"emergencyButton":%d}""",
            batteryPercent(), chargeFlag, if (estopPressed) 0 else 1,
        )
        "/reeman/speed" -> {
            val m = motion
            val v = if (m == null) 0.0 else m.sign * m.speedAt(now)
            val turning = m?.turn == true
            fmt(
                """{"vx":%.4f,"vth":%.4f}""",
                (if (turning) 0.0 else v) + jogVx + noise(), (if (turning) v else 0.0) + jogVth + noise(),
            )
        }
        "/reeman/pose" -> {
            if (now - poseNs >= POSE_PERIOD_NS) {
                poseX = x
                poseY = y
                poseTheta = theta
                poseNs = now
            }
            fmt("""{"x":%.4f,"y":%.4f,"theta":%.4f}""", poseX, poseY, poseTheta)
        }
        "/reeman/position" -> points.joinToString(",", """{"waypoints":[""", "]}") {
            fmt("""{"name":"%s","type":"%s","pose":{"x":%.4f,"y":%.4f,"theta":%.4f}}""", it.name, it.type, it.x, it.y, it.theta)
        }
        "/reeman/nav_status" -> navStatus ?: error("004", "Internal service error") // what the real robot says while idle
        // The API's documented shape (points ~30 cm apart); here just where the robot is.
        "/reeman/global_plan" -> if (navGoal != null) fmt("""{"coordinates":[[%.3f,%.3f]]}""", x, y)
        else error("007", "Can't get plan data")
        "/reeman/get_mode" -> """{"mode":$mode}"""
        "/reeman/restrict_layer" -> current.walls
        "/reeman/current_map" -> """{"name":"${current.id}","alias":"${current.alias}"}"""
        "/reeman/map" -> mapJson(current.grid, GRID_RES)
        // Each map with a small image of it; the real robot sends these with resolution 0.
        "/reeman/history_map" -> maps.joinToString(",", """{"maps":[""", "]}") {
            """{"name":"${it.id}","alias":"${it.alias}","map":${mapJson(it.grid, 0.0)}}"""
        }
        else -> null
    }

    private fun post(path: String, json: String, now: Long): String? = when (path) {
        "/cmd/move" -> move(json, now)
        "/cmd/speed" -> speed(json, now)
        "/cmd/turn" -> turn(json, now)
        "/cmd/charge" -> charge(json, now)
        "/cmd/nav_name" -> navName(json, now)
        "/cmd/position" -> setPoint(json)
        "/cmd/external_power_supply" -> externalPower(json)
        "/cmd/cancel_goal" -> {
            if (navGoal != null) halt()
            OK
        }
        "/cmd/set_mode" -> setMode(json)
        "/cmd/save_map" -> saveMap()
        "/cmd/apply_map" -> applyMap(json)
        "/cmd/rename_map" -> {
            val m = maps.firstOrNull { it.id == str(json, "name") }
            val alias = str(json, "alias")
            if (m == null || alias == null) error("004", "Internal service error") else {
                m.alias = alias
                OK
            }
        }
        "/cmd/trash_map" -> {
            val m = maps.firstOrNull { it.id == str(json, "name") }
            when {
                m == null -> error("004", "Internal service error")
                m === current -> error("004", "The current map cannot be moved to the trash") // as documented; GUESS: the reply
                else -> {
                    maps.remove(m)
                    bin.add(m)
                    OK
                }
            }
        }
        "/cmd/restore_map" -> {
            val m = bin.firstOrNull { it.id == str(json, "name") }
            if (m == null) error("004", "Internal service error") else {
                bin.remove(m)
                maps.add(m)
                OK
            }
        }
        "/cmd/restrict_layer" -> {
            current.walls = json
            OK
        }
        "/cmd/reloc_pose" -> if (mode == MODE_NAVIGATION) OK else error("004", "Internal service error") // accepted, nothing moves
        else -> null
    }

    // ------------------------------------------------------------------ maps

    /** {"mode":1} new map, {"mode":3} extend the map in use, {"mode":2} back to navigating. */
    private fun setMode(json: String): String {
        val m = num(json, "mode")?.toInt()
        if (m != MODE_MAPPING && m != MODE_NAVIGATION && m != MODE_EXTENDING) return error("004", "Internal service error")
        mode = m
        return OK
    }

    /** A new map is saved as a copy of the room, with no name or points, and comes into use. */
    private fun saveMap(): String {
        if (mode == MODE_NAVIGATION) return error("004", "Internal service error")
        if (mode == MODE_MAPPING) {
            current = SimMap(newMapId(), "", roomGrid(), mutableListOf())
            maps.add(current)
        }
        return OK
    }

    private fun applyMap(json: String): String {
        val m = maps.firstOrNull { it.id == str(json, "name") } ?: return error("004", "Internal service error")
        if (mode != MODE_NAVIGATION) return error("004", "Internal service error")
        current = m
        return OK
    }

    private fun newMapId(): String {
        mapCount++
        return String.format(Locale.US, "%032x", 0x5eed0000L + mapCount)
    }

    private fun move(json: String, now: Long): String {
        val distance = num(json, "distance")
        val direction = num(json, "direction")
        val speed = num(json, "speed")
        if (distance == null || direction == null || speed == null) return error("004", "Internal service error")
        if (distance == 0.0 || speed == 0.0) {
            stopBody()
            return OK
        }
        if (distance < 0 || speed < 0) return error("004", "Internal service error")
        val v = min(speed, MAX_FIRMWARE_LINEAR)
        start(false, if (direction == 1.0) 1.0 else -1.0, distance / 100.0, v, 0.36 * v.pow(0.40), now)
        return OK
    }

    /**
     * {"vx":m/s,"vth":rad/s}: drive at this speed until told otherwise. Takes effect after the
     * command latency, at once (the jog test: 0 to 0.35 m/s within 20 ms; to 0 within 20-70 ms).
     * GUESS: a jog replaces a planned step or navigation, and the stop bodies (and an obstacle)
     * end a jog. With the e-stop pressed it is accepted but the wheels never turn.
     */
    private fun speed(json: String, now: Long): String {
        val vx = num(json, "vx")
        val vth = num(json, "vth")
        if (vx == null || vth == null) return error("004", "Internal service error")
        if (motion != null || navGoal != null) halt()
        if (!estopPressed) {
            jogQueue.addLast(JogCmd(now + CMD_LATENCY_NS, vx.coerceIn(-MAX_FIRMWARE_LINEAR, MAX_FIRMWARE_LINEAR), vth))
            chargeFlag = CHARGE_NONE
        }
        return OK
    }

    private fun turn(json: String, now: Long): String {
        val direction = num(json, "direction")
        val angle = num(json, "angle")
        val speed = num(json, "speed")
        if (direction == null || angle == null || speed == null) return error("004", "Internal service error")
        if (angle == 0.0 || speed == 0.0) {
            stopBody()
            return OK
        }
        // SLAM 3.0 docs limit angle to [-180, 180]; assume the firmware rejects more.
        if (abs(angle) > 180 || speed < 0) return error("004", "Internal service error")
        val sign = (if (direction == 1.0) 1.0 else -1.0) * angle.sign
        start(true, sign, Math.toRadians(abs(angle)), speed, ANGULAR_ACCEL, now)
        return OK
    }

    /**
     * The stop bodies are a hard stop. But during a navigation they only pause it: the robot
     * brakes, then carries on, as the real robot did when it went to charge by itself
     * (2026-10-05). Only /cmd/cancel_goal (or /cmd/charge type 1) ends a navigation.
     */
    private fun stopBody() {
        if (navGoal != null) pauseNav(STOP_BODY_PAUSE_S) else halt()
    }

    /**
     * type 0: dock with the pile if it is within [DOCK_RANGE]: turn to face away from it, then
     * reverse onto it. GUESS: the real routine and its range were never watched; this is the
     * shape the manual describes. Out of range the command is still accepted but the robot
     * never moves and chargeFlag reads 9, as the firmware documents. type 1 cancels docking, and on
     * the pile ends charging (UNDOCK relies on that): chargeFlag reads 0 then, though the robot
     * hasn't moved. STOP sends it too.
     */
    private fun charge(json: String, now: Long): String {
        return when (num(json, "type")) {
            0.0 -> {
                if (chargeFlag == CHARGE_DOCKED) return OK // already on the pile
                val dx = x - DOCK_X
                val dy = y - DOCK_Y
                val distance = hypot(dx, dy)
                if (distance > DOCK_RANGE) {
                    halt()
                    chargeFlag = CHARGE_NOT_FOUND
                    return OK
                }
                val turn = wrap(atan2(dy, dx) - theta)
                val started = startLegs(
                    { t -> Motion(true, turn.sign, abs(turn), DOCK_TURN_SPEED, ANGULAR_ACCEL, t) },
                    { t -> Motion(false, -1.0, distance, DOCK_SPEED, 0.36 * DOCK_SPEED.pow(0.40), t) },
                    now = now,
                )
                if (started) chargeFlag = CHARGE_DOCKING
                OK
            }
            1.0 -> {
                halt()
                if (chargeFlag == CHARGE_DOCKED) chargeFlag = CHARGE_NONE // still on the pile, not charging
                OK
            }
            2.0 -> {
                if (mode != MODE_NAVIGATION) return error("004", "Internal service error") // GUESS, as for trips
                goCharge(now)
                OK
            }
            null -> error("004", "Internal service error")
            else -> error("004", "Internal service error")
        }
    }

    /**
     * type 2: navigate to a spot just in front of the pile, facing away from it, then reverse
     * onto it. GUESS: the shape of the real routine was never watched.
     */
    private fun goCharge(now: Long) {
        if (chargeFlag == CHARGE_DOCKED) return // already on the pile
        val approach = SavedPoint(PILE_POINT, "charge", DOCK_X + DOCK_APPROACH, DOCK_Y, 0.0)
        if (!startLegs(*navLegs(approach), now = now)) return
        navGoal = approach
        navThen = { t ->
            navGoal = null // the trip is over; docking is not a navigation (no plan)
            navThen = null
            chargeFlag = CHARGE_DOCKING
            Motion(false, -1.0, hypot(x - DOCK_X, y - DOCK_Y), DOCK_SPEED, 0.36 * DOCK_SPEED.pow(0.40), t)
        }
        legs.addLast { t -> navThen!!(t) }
    }

    /**
     * Navigate to a saved point: turn to face it, drive straight there, turn to the point's
     * heading. GUESS: the real robot plans a route around obstacles and picks its own speed;
     * this one drives a straight line, so the demo points are placed with clear lines between
     * them, and a wall or the box in the way stops it short (and ends the navigation).
     */
    private fun navName(json: String, now: Long): String {
        if (mode != MODE_NAVIGATION) return error("004", "Internal service error") // GUESS: no trips while mapping
        val goal = points.firstOrNull { it.name == str(json, "point") }
            ?: return error("004", "Internal service error") // GUESS: the real reply to an unknown point
        if (startLegs(*navLegs(goal), now = now)) navGoal = goal
        return OK
    }

    /**
     * Saves a point, as /cmd/position documents: {"name","type","pose":{"x","y","theta"}}, type
     * one of [POINT_TYPES]. A name already in use is replaced (GUESS: never tried on the robot).
     */
    private fun setPoint(json: String): String {
        val name = str(json, "name")
        val type = str(json, "type")
        val x = num(json, "x")
        val y = num(json, "y")
        val theta = num(json, "theta")
        if (name.isNullOrEmpty() || type == null || type !in POINT_TYPES || x == null || y == null || theta == null) {
            return error("004", "Internal service error")
        }
        points.removeAll { it.name == name }
        points.add(SavedPoint(name, type, x, y, theta))
        return OK
    }

    private fun navLegs(goal: SavedPoint): Array<(Long) -> Motion> {
        val face = { t: Long -> turnTo(atan2(goal.y - y, goal.x - x), t) }
        val drive = { t: Long -> Motion(false, 1.0, hypot(goal.x - x, goal.y - y), NAV_SPEED, 0.36 * NAV_SPEED.pow(0.40), t) }
        val settle = { t: Long -> turnTo(goal.theta, t) }
        return if (hypot(goal.x - x, goal.y - y) > 0.05) arrayOf(face, drive, settle) else arrayOf(settle)
    }

    private fun turnTo(heading: Double, t: Long): Motion {
        val d = wrap(heading - theta)
        return Motion(true, d.sign, abs(d), NAV_TURN_SPEED, ANGULAR_ACCEL, t)
    }

    /** A navigation waits [seconds] where it is, keeping its plan, then carries on. */
    private fun pauseNav(seconds: Double) {
        val goal = navGoal ?: return
        // sign 0: a "leg" that lasts [seconds] and never moves the robot.
        motion = Motion(false, 0.0, seconds, 1.0, 1e9, clock())
        legs.clear()
        legs.addAll(navLegs(goal))
        navThen?.let { then -> legs.addLast { t -> then(t) } }
    }

    /** Hard stop. Abandons a dock or a navigation in progress, but a robot already on the pile stays on it. */
    private fun halt() {
        motion = null
        legs.clear()
        jogVx = 0.0
        jogVth = 0.0
        jogQueue.clear()
        navGoal = null
        navThen = null
        if (chargeFlag == CHARGE_DOCKING) chargeFlag = CHARGE_NONE
    }

    private fun start(turn: Boolean, sign: Double, amount: Double, speed: Double, accel: Double, now: Long): Boolean =
        startLegs({ t -> Motion(turn, sign, amount, speed, accel, t) }, now = now)

    /**
     * A new command replaces the running one outright, from rest. Seen on the real robot
     * (2026-10-08): a step sent again every 0.25 s restarted each time, so the robot shuffled. With the e-stop pressed or an obstacle simulated the
     * command is accepted but the wheels never turn (also a GUESS).
     */
    private fun startLegs(vararg plan: (Long) -> Motion, now: Long): Boolean {
        halt()
        chargeFlag = CHARGE_NONE // any move takes it off the pile
        if (estopPressed || blockNext) {
            blockNext = false
            return false
        }
        motion = plan.first()(now + CMD_LATENCY_NS)
        legs.addAll(plan.drop(1))
        return true
    }

    // ------------------------------------------------------------------ physics

    private fun advance(now: Long) {
        if (chargeFlag == CHARGE_DOCKED) {
            battery = min(100.0, battery + (now - lastNs) / 1e9 / CHARGING_SECONDS_PER_PERCENT)
        }
        var t = lastNs
        while (t < now) {
            val next = min(now, t + STEP_NS)
            while (jogQueue.isNotEmpty() && jogQueue.first().atNs <= next) {
                val c = jogQueue.removeFirst()
                jogVx = c.vx
                jogVth = c.vth
                jogLastNs = c.atNs
            }
            val jogging = jogVx != 0.0 || jogVth != 0.0
            if (jogging && next - jogLastNs > JOG_WATCHDOG_NS) {
                halt() // the watchdog: no command for too long, then a hard stop (section 6)
                t = next
                continue
            }
            val m = motion
            if (m == null && !jogging) {
                if (jogQueue.isEmpty()) break
                t = next
                continue
            }
            val dt = (next - t) / 1e9
            val v = m?.let { it.sign * it.speedAt((t + next) / 2) } ?: 0.0
            val turning = m?.turn == true
            val forward = (if (turning) 0.0 else v) + jogVx
            theta = wrap(theta + ((if (turning) v else 0.0) + jogVth) * dt)
            if (forward != 0.0) {
                val nx = x + forward * dt * cos(theta)
                val ny = y + forward * dt * sin(theta)
                if (collides(nx, ny)) {
                    halt() // obstacle brake: stops short, like the real safety brake
                } else {
                    x = nx
                    y = ny
                }
            }
            if (v != 0.0 || jogging) battery = maxOf(0.0, battery - dt / MOVING_SECONDS_PER_PERCENT)
            if (motion?.finished(next) == true) {
                motion = legs.removeFirstOrNull()?.invoke(next)
                if (motion == null) {
                    if (chargeFlag == CHARGE_DOCKING) chargeFlag = CHARGE_DOCKED
                    navGoal = null
                }
            }
            t = next
        }
        lastNs = now
    }

    /**
     * Switches the power board's external supply, as the API documents:
     * {"type":"24v"|"36v"|"24v36","operation":"0"|"1"}. GUESS: the reply to a bad body.
     */
    private fun externalPower(json: String): String {
        val on = when (str(json, "operation")) {
            "1" -> true
            "0" -> false
            else -> return error("004", "Internal service error")
        }
        when (str(json, "type")) {
            "24v" -> power24 = on
            "36v" -> power36 = on
            "24v36" -> { power24 = on; power36 = on }
            else -> return error("004", "Internal service error")
        }
        return OK
    }

    // ------------------------------------------------------------------ demo controls

    /** The UV lamps: whichever supply DriveController.UV_SUPPLY says they are on. */
    @get:Synchronized
    val isUvOn: Boolean get() = when (DriveController.UV_SUPPLY) {
        "24v" -> power24
        "36v" -> power36
        else -> power24 && power36
    }

    @get:Synchronized
    val isEstopPressed: Boolean get() = estopPressed

    @Synchronized
    fun setEstopPressed(pressed: Boolean) {
        advance(clock())
        estopPressed = pressed
        if (pressed) halt() // the e-stop cuts the motors
    }

    @get:Synchronized @set:Synchronized
    var isOffline: Boolean
        get() = offline
        set(value) {
            offline = value
        }

    /** The next step is accepted but never starts, like a path blocked by an obstacle. */
    @Synchronized
    fun blockNextStep() {
        blockNext = true
    }

    @get:Synchronized
    val isBlockNextPending: Boolean get() = blockNext

    @get:Synchronized
    val isNavigating: Boolean get() = navGoal != null

    /**
     * A person steps into the path of a navigation: the robot brakes, waits [seconds] where it
     * is (keeping its plan), then carries on to the point. No effect unless navigating.
     * GUESS: the real robot's waiting behaviour was never watched.
     */
    @Synchronized
    fun personInTheWay(seconds: Double) {
        advance(clock())
        pauseNav(seconds)
    }

    /** As if the robot (or its own app) started a trip to the pile: /cmd/charge type 2, not from the app. */
    @Synchronized
    fun goChargeOnItsOwn() {
        val now = clock()
        advance(now)
        goCharge(now)
    }

    @get:Synchronized
    val isBusy: Boolean get() = motion != null || jogVx != 0.0 || jogVth != 0.0

    /** Knocks 10% off the battery; wraps back to full below zero. */
    @Synchronized
    fun drainBattery() {
        battery = if (battery >= 10) battery - 10 else 100.0
    }

    @Synchronized
    fun batteryPercent(): Int = ceil(battery).toInt()

    /** Back to the centre of the room, full-ish battery, no faults. */
    @Synchronized
    fun reset() {
        lastNs = clock()
        x = 0.0; y = 0.0; theta = 0.0
        poseX = 0.0; poseY = 0.0; poseTheta = 0.0
        poseNs = lastNs - POSE_PERIOD_NS
        motion = null
        legs.clear()
        jogVx = 0.0
        jogVth = 0.0
        jogQueue.clear()
        navGoal = null
        navThen = null
        maps.clear()
        bin.clear()
        mapCount = 0
        current = SimMap(newMapId(), "Demo room", roomGrid(), DEFAULT_POINTS.toMutableList())
        maps.add(current)
        maps.add(SimMap(newMapId(), "Old scan", roomGrid(), mutableListOf())) // something to switch to
        mode = MODE_NAVIGATION
        chargeFlag = CHARGE_NONE
        battery = 87.0
        estopPressed = false
        offline = false
        blockNext = false
        power24 = false
        power36 = false
    }

    private fun noise() = if (realistic) (rng.nextDouble() * 2 - 1) * SPEED_NOISE else 0.0

    companion object {
        const val VERSION = "DEMO-SIM (mimics RSNF1-v5.1.20_01)"
        const val HOSTNAME = "demo-000000-000-001"

        // ---- Motion model ----
        private const val CMD_LATENCY_NS = 200_000_000L  // measured ~200 ms command-to-wheel
        private const val JOG_WATCHDOG_NS = 1_160_000_000L // measured: runs on this long after the last /cmd/speed
        private const val ANGULAR_ACCEL = 0.5            // rad/s^2, GUESS: turn ramps never measured
        private const val MAX_FIRMWARE_LINEAR = 1.0      // top of the /cmd/max_speed range
        private const val ROBOT_RADIUS = 0.30            // m, GUESS
        private const val SPEED_NOISE = 0.004            // encoder jitter, well under the app's "moving" thresholds
        private const val POSE_PERIOD_NS = 200_000_000L  // real pose only updates at ~5 Hz
        private const val STEP_NS = 10_000_000L          // integration step
        private const val MOVING_SECONDS_PER_PERCENT = 20.0 // battery drain while moving, sped up for demos
        private const val CHARGING_SECONDS_PER_PERCENT = 2.0 // battery gain on the pile, sped up for demos

        // ---- Docking. chargeFlag values from /reeman/base_encode (SLAM Web API). ----
        private const val CHARGE_NONE = 0                // "other values: not charging"
        private const val CHARGE_DOCKED = 2              // charging at the pile
        private const val CHARGE_DOCKING = 8             // connecting to the pile
        private const val CHARGE_NOT_FOUND = 9           // no pile found while docking
        private const val DOCK_RANGE = 2.5               // m, GUESS: how far away the pile can be seen
        private const val DOCK_SPEED = 0.15              // m/s reversing onto the pile, GUESS
        private const val DOCK_TURN_SPEED = 0.4          // rad/s, GUESS

        // ---- Simulated room, metres, map frame. Robot starts in the centre facing +x. ----
        private const val ROOM_X = 2.5                   // half-widths: a 5 m x 4 m room
        private const val ROOM_Y = 2.0
        private val BOX = doubleArrayOf(1.2, 0.8, 1.8, 1.4) // obstacle: minX, minY, maxX, maxY
        // Charging pile against the middle of the west wall, right behind the start position.
        // DOCK_X/Y is where the robot's centre sits when it is on the pile.
        private const val DOCK_X = -ROOM_X + ROBOT_RADIUS + 0.05
        private const val DOCK_Y = 0.0

        // ---- Navigation (/cmd/nav_name). Speeds are the firmware's choice on the real robot. ----
        private const val NAV_SPEED = 0.3                // m/s, GUESS
        private const val NAV_TURN_SPEED = 0.4           // rad/s, GUESS
        private const val STOP_BODY_PAUSE_S = 1.0        // a stop body pauses a navigation this long, GUESS
        private const val DOCK_APPROACH = 0.6            // m in front of the pile where charge type 2 starts docking, GUESS
        private const val PILE_POINT = "charging_pile"

        private class SavedPoint(val name: String, val type: String, val x: Double, val y: Double, val theta: Double)

        // Saved points, map frame. Straight lines between any two (and from the start) clear
        // the box. Types are from the API; which ones the robot's own app uses is unknown.
        private val POINT_TYPES = setOf("delivery", "normal", "production", "charge")
        private val DEFAULT_POINTS = listOf(
            SavedPoint("Bed 1", "delivery", 1.6, -1.2, -Math.PI / 2),
            SavedPoint("Bed 2", "delivery", -1.4, 1.2, Math.PI / 2),
            SavedPoint("Sink area", "delivery", -1.4, -1.3, Math.PI),
            SavedPoint("charging_pile", "charge", DOCK_X, DOCK_Y, 0.0),
        )

        // ---- Maps: an occupancy grid over the room, row 0 at the top (north), as a ROS map image. ----
        private const val MODE_MAPPING = 1
        private const val MODE_NAVIGATION = 2
        private const val MODE_EXTENDING = 3
        private const val GRID_RES = 0.05                // m per pixel, as on the real robot
        private const val GRID_X0 = -ROOM_X - 0.25       // map-frame corner of the bottom-left pixel
        private const val GRID_Y0 = -ROOM_Y - 0.25
        private val GRID_W = Math.round((2 * ROOM_X + 0.5) / GRID_RES).toInt()
        private val GRID_H = Math.round((2 * ROOM_Y + 0.5) / GRID_RES).toInt()
        private const val WALL = 0.1                     // m
        private val UNKNOWN = 205.toByte()               // map_server's grey, white and black
        private val FREE = 254.toByte()
        private val OCCUPIED = 0.toByte()

        /** Is this map-frame spot a wall or the box? */
        private fun solid(px: Double, py: Double): Boolean {
            if (abs(px) >= ROOM_X || abs(py) >= ROOM_Y) return abs(px) < ROOM_X + WALL && abs(py) < ROOM_Y + WALL
            return px >= BOX[0] && px <= BOX[2] && py >= BOX[1] && py <= BOX[3]
        }

        /** The whole room as a finished map: free inside, walls and the box's sides occupied. */
        private fun roomGrid(): ByteArray {
            val g = ByteArray(GRID_W * GRID_H) { UNKNOWN }
            for (row in 0 until GRID_H) for (col in 0 until GRID_W) {
                val px = GRID_X0 + (col + 0.5) * GRID_RES
                val py = GRID_Y0 + (GRID_H - row - 0.5) * GRID_RES
                val inBox = solid(px, py) && abs(px) < ROOM_X && abs(py) < ROOM_Y
                val boxEdge = inBox && (px - BOX[0] < GRID_RES || BOX[2] - px < GRID_RES || py - BOX[1] < GRID_RES || BOX[3] - py < GRID_RES)
                g[row * GRID_W + col] = when {
                    inBox -> if (boxEdge) OCCUPIED else UNKNOWN // the lidar never sees inside the box
                    solid(px, py) -> OCCUPIED
                    abs(px) < ROOM_X && abs(py) < ROOM_Y -> FREE
                    else -> UNKNOWN
                }
            }
            return g
        }

        /** /reeman/map's shape: size, metres per pixel, the bottom-left corner, and a PNG data URL. */
        private fun mapJson(grid: ByteArray, resolution: Double) = fmt(
            """{"width":%d,"height":%d,"resolution":%s,"origin_x":%s,"origin_y":%s,"image_url":"data:image/png;base64,%s"}""",
            GRID_W, GRID_H, resolution.toString(),
            if (resolution == 0.0) "0" else GRID_X0.toString(), if (resolution == 0.0) "0" else GRID_Y0.toString(),
            base64(png(GRID_W, GRID_H, grid)),
        )

        /** An 8-bit greyscale PNG. Written by hand: android.graphics isn't on the test JVM. */
        private fun png(w: Int, h: Int, grey: ByteArray): ByteArray {
            val raw = ByteArrayOutputStream()
            for (row in 0 until h) {
                raw.write(0) // filter: none
                raw.write(grey, row * w, w)
            }
            val deflater = Deflater()
            deflater.setInput(raw.toByteArray())
            deflater.finish()
            val idat = ByteArrayOutputStream()
            val buf = ByteArray(8192)
            while (!deflater.finished()) idat.write(buf, 0, deflater.deflate(buf))
            deflater.end()
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            fun int(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
            fun chunk(type: String, data: ByteArray) {
                val t = type.toByteArray(Charsets.US_ASCII)
                out.write(int(data.size))
                out.write(t)
                out.write(data)
                out.write(int(CRC32().apply { update(t); update(data) }.value.toInt()))
            }
            chunk("IHDR", int(w) + int(h) + byteArrayOf(8, 0, 0, 0, 0)) // 8-bit greyscale
            chunk("IDAT", idat.toByteArray())
            chunk("IEND", ByteArray(0))
            return out.toByteArray()
        }

        /** java.util.Base64 needs Android 8; the app runs on 7. */
        private fun base64(bytes: ByteArray): String {
            val abc = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
            val sb = StringBuilder((bytes.size + 2) / 3 * 4)
            for (i in bytes.indices step 3) {
                val n = (bytes[i].toInt() and 0xff shl 16) or
                    ((if (i + 1 < bytes.size) bytes[i + 1].toInt() and 0xff else 0) shl 8) or
                    (if (i + 2 < bytes.size) bytes[i + 2].toInt() and 0xff else 0)
                sb.append(abc[n shr 18 and 63]).append(abc[n shr 12 and 63])
                sb.append(if (i + 1 < bytes.size) abc[n shr 6 and 63] else '=')
                sb.append(if (i + 2 < bytes.size) abc[n and 63] else '=')
            }
            return sb.toString()
        }

        // The success reply of /cmd/nav_name, /cmd/cancel_goal and /cmd/external_power_supply (seen on
        // v5.1.20); assumed for the rest. The app only checks it isn't an error.
        const val OK = """{"status":"success"}"""

        private fun collides(px: Double, py: Double): Boolean {
            val r = ROBOT_RADIUS
            if (abs(px) > ROOM_X - r || abs(py) > ROOM_Y - r) return true
            return px > BOX[0] - r && px < BOX[2] + r && py > BOX[1] - r && py < BOX[3] + r
        }

        private fun wrap(a: Double): Double {
            var r = a
            while (r > Math.PI) r -= 2 * Math.PI
            while (r <= -Math.PI) r += 2 * Math.PI
            return r
        }

        private fun error(code: String, message: String) = """{"error":"$message","error_code":"$code"}"""

        private fun fmt(format: String, vararg args: Any) = String.format(Locale.US, format, *args)

        /** Reads a number from a flat JSON body, quoted or not. Enough for the app's own commands. */
        private fun num(json: String, key: String): Double? =
            Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)")
                .find(json)?.groupValues?.get(1)?.toDouble()

        /** Reads a string from a flat JSON body (no escapes: enough for point names). */
        private fun str(json: String, key: String): String? =
            Regex("\"" + Regex.escape(key) + "\"\\s*:\\s*\"([^\"]*)\"").find(json)?.groupValues?.get(1)
    }
}
