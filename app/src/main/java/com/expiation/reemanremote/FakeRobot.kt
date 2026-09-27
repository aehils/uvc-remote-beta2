package com.expiation.reemanremote

import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Demo mode: a simulated Reeman navigation computer that lives inside the app.
 *
 * It answers the same paths with the same JSON shapes as firmware RSNF1-v5.1.12_01
 * (REEMAN_HANDOFF.txt section 4), so DriveController's polling, step lock and STOP
 * logic run unchanged, and nothing is sent over the network.
 *
 * Motion follows the measured /cmd/move behaviour (section 5): ~200 ms command
 * latency, then a trapezoid with accel/decel of about 0.36 * speed^0.40 m/s^2.
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
    private var battery = 0.0
    private var estopPressed = false
    private var offline = false
    private var blockNext = false
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
        "/reeman/hostname" -> """{"hostname":"demo-robot"}"""
        "/reeman/get_mode" -> """{"mode":2}"""
        "/reeman/base_encode" -> fmt(
            """{"battery":%d,"chargeFlag":0,"emergencyButton":%d}""",
            batteryPercent(), if (estopPressed) 0 else 1,
        )
        "/reeman/speed" -> {
            val m = motion
            val v = if (m == null) 0.0 else m.sign * m.speedAt(now)
            val turning = m?.turn == true
            fmt("""{"vx":%.4f,"vth":%.4f}""", (if (turning) 0.0 else v) + noise(), (if (turning) v else 0.0) + noise())
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
        "/reeman/laser" -> laser()
        "/reeman/nav_status" -> error("004", "Internal service error") // what the real robot says while idle
        "/reeman/global_plan" -> error("007", "Can't get plan data")
        else -> null
    }

    private fun post(path: String, json: String, now: Long): String? = when (path) {
        "/cmd/move" -> move(json, now)
        "/cmd/turn" -> turn(json, now)
        in UNSIMULATED_CMDS -> error("004", "Not simulated in DEMO mode")
        else -> null
    }

    private fun move(json: String, now: Long): String {
        val distance = num(json, "distance")
        val direction = num(json, "direction")
        val speed = num(json, "speed")
        if (distance == null || direction == null || speed == null) return error("004", "Internal service error")
        if (distance == 0.0 || speed == 0.0) {
            motion = null // the stop body: a hard stop, as on the real robot
            return OK
        }
        if (distance < 0 || speed < 0) return error("004", "Internal service error")
        val v = min(speed, MAX_FIRMWARE_LINEAR)
        start(false, if (direction == 1.0) 1.0 else -1.0, distance / 100.0, v, 0.36 * v.pow(0.40), now)
        return OK
    }

    private fun turn(json: String, now: Long): String {
        val direction = num(json, "direction")
        val angle = num(json, "angle")
        val speed = num(json, "speed")
        if (direction == null || angle == null || speed == null) return error("004", "Internal service error")
        if (angle == 0.0 || speed == 0.0) {
            motion = null
            return OK
        }
        // SLAM 3.0 docs limit angle to [-180, 180]; assume the firmware rejects more.
        if (abs(angle) > 180 || speed < 0) return error("004", "Internal service error")
        val sign = (if (direction == 1.0) 1.0 else -1.0) * angle.sign
        start(true, sign, Math.toRadians(abs(angle)), speed, ANGULAR_ACCEL, now)
        return OK
    }

    /**
     * A new step replaces the running one outright (GUESS: the firmware's pre-emption
     * behaviour is unmeasured). With the e-stop pressed or an obstacle simulated the
     * step is accepted but the wheels never turn (also a GUESS).
     */
    private fun start(turn: Boolean, sign: Double, amount: Double, speed: Double, accel: Double, now: Long) {
        if (estopPressed || blockNext) {
            blockNext = false
            motion = null
            return
        }
        motion = Motion(turn, sign, amount, speed, accel, now + CMD_LATENCY_NS)
    }

    // ------------------------------------------------------------------ physics

    private fun advance(now: Long) {
        var t = lastNs
        while (t < now) {
            val m = motion ?: break
            val next = min(now, t + STEP_NS)
            val v = m.sign * m.speedAt((t + next) / 2)
            val dt = (next - t) / 1e9
            if (m.turn) {
                theta = wrap(theta + v * dt)
            } else {
                val nx = x + v * dt * cos(theta)
                val ny = y + v * dt * sin(theta)
                if (collides(nx, ny)) {
                    motion = null // obstacle brake: stops short, like the real safety brake
                } else {
                    x = nx
                    y = ny
                }
            }
            if (v != 0.0) battery = maxOf(0.0, battery - dt / MOVING_SECONDS_PER_PERCENT)
            if (motion?.finished(next) == true) motion = null
            t = next
        }
        lastNs = now
    }

    /** Lidar hits as [[x,y],...] in metres. GUESS: map frame; confirm on the real robot. */
    private fun laser(): String {
        val points = (0 until LASER_BEAMS).mapNotNull { i ->
            val a = theta + 2 * Math.PI * i / LASER_BEAMS
            val dx = cos(a)
            val dy = sin(a)
            val best = WALLS.minOf { rayHit(x, y, dx, dy, it) }.coerceAtMost(LASER_RANGE)
            if (best >= LASER_RANGE) null else fmt("[%.3f,%.3f]", x + dx * best, y + dy * best)
        }
        return points.joinToString(",", """{"coordinates":[""", "]}")
    }

    // ------------------------------------------------------------------ demo controls

    @get:Synchronized
    val isEstopPressed: Boolean get() = estopPressed

    @Synchronized
    fun setEstopPressed(pressed: Boolean) {
        advance(clock())
        estopPressed = pressed
        if (pressed) motion = null // the e-stop cuts the motors
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
        battery = 87.0
        estopPressed = false
        offline = false
        blockNext = false
    }

    private fun noise() = if (realistic) (rng.nextDouble() * 2 - 1) * SPEED_NOISE else 0.0

    companion object {
        const val VERSION = "DEMO-SIM (mimics RSNF1-v5.1.12_01)"

        // ---- Motion model ----
        private const val CMD_LATENCY_NS = 200_000_000L  // measured ~200 ms command-to-wheel
        private const val ANGULAR_ACCEL = 0.5            // rad/s^2, GUESS: turn ramps never measured
        private const val MAX_FIRMWARE_LINEAR = 1.0      // top of the /cmd/max_speed range
        private const val ROBOT_RADIUS = 0.30            // m, GUESS
        private const val SPEED_NOISE = 0.004            // encoder jitter, well under the app's "moving" thresholds
        private const val POSE_PERIOD_NS = 200_000_000L  // real pose only updates at ~5 Hz
        private const val STEP_NS = 10_000_000L          // integration step
        private const val MOVING_SECONDS_PER_PERCENT = 20.0 // battery drain while moving, sped up for demos

        // ---- Simulated room, metres, map frame. Robot starts in the centre facing +x. ----
        private const val ROOM_X = 2.5                   // half-widths: a 5 m x 4 m room
        private const val ROOM_Y = 2.0
        private val BOX = doubleArrayOf(1.2, 0.8, 1.8, 1.4) // obstacle: minX, minY, maxX, maxY
        private val WALLS = arrayOf(
            doubleArrayOf(-ROOM_X, -ROOM_Y, ROOM_X, -ROOM_Y), doubleArrayOf(ROOM_X, -ROOM_Y, ROOM_X, ROOM_Y),
            doubleArrayOf(ROOM_X, ROOM_Y, -ROOM_X, ROOM_Y), doubleArrayOf(-ROOM_X, ROOM_Y, -ROOM_X, -ROOM_Y),
            doubleArrayOf(BOX[0], BOX[1], BOX[2], BOX[1]), doubleArrayOf(BOX[2], BOX[1], BOX[2], BOX[3]),
            doubleArrayOf(BOX[2], BOX[3], BOX[0], BOX[3]), doubleArrayOf(BOX[0], BOX[3], BOX[0], BOX[1]),
        )
        private const val LASER_BEAMS = 180  // every 2 degrees, GUESS
        private const val LASER_RANGE = 8.0

        // Real commands this simulator doesn't model. They answer 004 rather than 009
        // so a demo never suggests a real route doesn't exist.
        private val UNSIMULATED_CMDS = setOf(
            "/cmd/speed", "/cmd/nav_name", "/cmd/nav", "/cmd/cancel_goal", "/cmd/max_speed",
            "/cmd/charge", "/cmd/reloc_pose", "/cmd/reloc_absolute", "/cmd/set_mode",
            "/cmd/save_map", "/cmd/apply_map", "/cmd/position", "/cmd/restrict_layer",
            "/cmd/shutdown", "/cmd/external_power_supply",
        )

        // GUESS: the real success body was never recorded; the app only checks it isn't an error.
        private const val OK = "{}"

        private fun collides(px: Double, py: Double): Boolean {
            val r = ROBOT_RADIUS
            if (abs(px) > ROOM_X - r || abs(py) > ROOM_Y - r) return true
            return px > BOX[0] - r && px < BOX[2] + r && py > BOX[1] - r && py < BOX[3] + r
        }

        /** Distance along the ray to segment w = {ax, ay, bx, by}, or infinity. */
        private fun rayHit(ox: Double, oy: Double, dx: Double, dy: Double, w: DoubleArray): Double {
            val ex = w[2] - w[0]
            val ey = w[3] - w[1]
            val den = dx * ey - dy * ex
            if (abs(den) < 1e-12) return Double.POSITIVE_INFINITY
            val wx = w[0] - ox
            val wy = w[1] - oy
            val t = (wx * ey - wy * ex) / den
            val u = (wx * dy - wy * dx) / den
            return if (t > 0 && u >= 0 && u <= 1) t else Double.POSITIVE_INFINITY
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
    }
}
