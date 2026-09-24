package com.expiation.reemanremote;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Demo mode: a simulated Reeman navigation computer that lives inside the app.
 *
 * It answers the same paths with the same JSON shapes as firmware RSNF1-v5.1.12_01
 * (REEMAN_HANDOFF.txt section 4), so MainActivity's polling, step lock and STOP
 * logic run unchanged, and nothing is sent over the network.
 *
 * Motion follows the measured /cmd/move behaviour (section 5): ~200 ms command
 * latency, then a trapezoid with accel/decel of about 0.36 * speed^0.40 m/s^2.
 * Anything marked GUESS was never measured on the real robot.
 *
 * Pure Java (no org.json) so it can be exercised on a plain JVM.
 */
final class FakeRobot {

    static final class Reply {
        final int code;
        final String body;
        final long latencyMs;

        Reply(int code, String body, long latencyMs) {
            this.code = code;
            this.body = body;
            this.latencyMs = latencyMs;
        }
    }

    static final String VERSION = "DEMO-SIM (mimics RSNF1-v5.1.12_01)";

    // ---- Motion model ----
    private static final long CMD_LATENCY_NS = 200_000_000L;  // measured ~200 ms command-to-wheel
    private static final double ANGULAR_ACCEL = 0.5;         // rad/s^2, GUESS: turn ramps never measured
    private static final double MAX_FIRMWARE_LINEAR = 1.0;   // top of the /cmd/max_speed range
    private static final double ROBOT_RADIUS = 0.30;         // m, GUESS
    private static final double SPEED_NOISE = 0.004;         // encoder jitter, well under the app's "moving" thresholds
    private static final long POSE_PERIOD_NS = 200_000_000L; // real pose only updates at ~5 Hz
    private static final long STEP_NS = 10_000_000L;         // integration step
    private static final double MOVING_SECONDS_PER_PERCENT = 20; // battery drain while moving, sped up for demos

    // ---- Simulated room, metres, map frame. Robot starts in the centre facing +x. ----
    private static final double ROOM_X = 2.5, ROOM_Y = 2.0;          // half-widths: a 5 m x 4 m room
    private static final double[] BOX = {1.2, 0.8, 1.8, 1.4};        // obstacle: minX, minY, maxX, maxY
    private static final double[][] WALLS = {
            {-ROOM_X, -ROOM_Y, ROOM_X, -ROOM_Y}, {ROOM_X, -ROOM_Y, ROOM_X, ROOM_Y},
            {ROOM_X, ROOM_Y, -ROOM_X, ROOM_Y}, {-ROOM_X, ROOM_Y, -ROOM_X, -ROOM_Y},
            {BOX[0], BOX[1], BOX[2], BOX[1]}, {BOX[2], BOX[1], BOX[2], BOX[3]},
            {BOX[2], BOX[3], BOX[0], BOX[3]}, {BOX[0], BOX[3], BOX[0], BOX[1]},
    };
    private static final int LASER_BEAMS = 180;  // every 2 degrees, GUESS
    private static final double LASER_RANGE = 8.0;

    // Real commands this simulator doesn't model. They answer 004 rather than 009
    // so a demo never suggests a real route doesn't exist.
    private static final List<String> UNSIMULATED_CMDS = Arrays.asList(
            "/cmd/speed", "/cmd/nav_name", "/cmd/nav", "/cmd/cancel_goal", "/cmd/max_speed",
            "/cmd/charge", "/cmd/reloc_pose", "/cmd/reloc_absolute", "/cmd/set_mode",
            "/cmd/save_map", "/cmd/apply_map", "/cmd/position", "/cmd/restrict_layer",
            "/cmd/shutdown", "/cmd/external_power_supply");

    // GUESS: the real success body was never recorded; the app only checks it isn't an error.
    private static final String OK = "{}";

    /** One planned step: ramp up, cruise, ramp down, like the firmware's own planner. */
    private static final class Motion {
        final boolean turn;
        final double sign;   // +1 forward / anticlockwise, -1 backward / clockwise
        final double peak;   // m/s or rad/s actually reached
        final double accel;
        final double tRamp, tCruise, tEnd; // seconds after startNs
        final long startNs;

        Motion(boolean turn, double sign, double amount, double speed, double accel, long startNs) {
            this.turn = turn;
            this.sign = sign;
            this.accel = accel;
            this.startNs = startNs;
            double rampAmount = speed * speed / accel; // ramp up + ramp down at full speed
            if (rampAmount >= amount) {
                peak = Math.sqrt(amount * accel);      // too short to reach full speed
                tCruise = 0;
            } else {
                peak = speed;
                tCruise = (amount - rampAmount) / speed;
            }
            tRamp = peak / accel;
            tEnd = 2 * tRamp + tCruise;
        }

        double speedAt(long nowNs) {
            double t = (nowNs - startNs) / 1e9;
            if (t <= 0 || t >= tEnd) return 0;
            if (t < tRamp) return accel * t;
            if (t < tRamp + tCruise) return peak;
            return accel * (tEnd - t);
        }

        boolean finished(long nowNs) {
            return (nowNs - startNs) / 1e9 >= tEnd;
        }
    }

    private final LongSupplier clock;
    private final Random rng;
    private final boolean realistic; // latency + speed noise; off for deterministic tests

    private long lastNs;
    private double x, y, theta;
    private Motion motion;
    private double battery;
    private boolean estopPressed, offline, blockNext;
    private double poseX, poseY, poseTheta;
    private long poseNs;

    FakeRobot() {
        this(System::nanoTime, new Random(), true);
    }

    FakeRobot(LongSupplier clock, Random rng, boolean realistic) {
        this.clock = clock;
        this.rng = rng;
        this.realistic = realistic;
        reset();
    }

    // ------------------------------------------------------------------ requests

    /** Answers one HTTP request. Returns null when a Wi-Fi drop is being simulated. */
    synchronized Reply handle(String method, String path, String json) {
        if (offline) return null;
        long now = clock.getAsLong();
        advance(now);

        String body = null;
        if ("GET".equals(method)) body = get(path, now);
        else if ("POST".equals(method)) body = post(path, json == null ? "" : json, now);

        long latency = realistic ? 15 + rng.nextInt(30) : 0;
        if (body == null) return new Reply(404, error("009", "Request path incorrect."), latency);
        return new Reply(200, body, latency);
    }

    private String get(String path, long now) {
        switch (path) {
            case "/reeman/current_version":
                return "{\"version\":\"" + VERSION + "\"}";
            case "/reeman/hostname":
                return "{\"hostname\":\"demo-robot\"}";
            case "/reeman/get_mode":
                return "{\"mode\":2}";
            case "/reeman/base_encode":
                return fmt("{\"battery\":%d,\"chargeFlag\":0,\"emergencyButton\":%d}",
                        batteryPercent(), estopPressed ? 0 : 1);
            case "/reeman/speed": {
                double v = motion == null ? 0 : motion.sign * motion.speedAt(now);
                boolean turning = motion != null && motion.turn;
                return fmt("{\"vx\":%.4f,\"vth\":%.4f}",
                        (turning ? 0 : v) + noise(), (turning ? v : 0) + noise());
            }
            case "/reeman/pose":
                if (now - poseNs >= POSE_PERIOD_NS) {
                    poseX = x;
                    poseY = y;
                    poseTheta = theta;
                    poseNs = now;
                }
                return fmt("{\"x\":%.4f,\"y\":%.4f,\"theta\":%.4f}", poseX, poseY, poseTheta);
            case "/reeman/laser":
                return laser();
            case "/reeman/nav_status":
                return error("004", "Internal service error"); // what the real robot says while idle
            case "/reeman/global_plan":
                return error("007", "Can't get plan data");
            default:
                return null;
        }
    }

    private String post(String path, String json, long now) {
        switch (path) {
            case "/cmd/move":
                return move(json, now);
            case "/cmd/turn":
                return turn(json, now);
            default:
                if (UNSIMULATED_CMDS.contains(path)) {
                    return error("004", "Not simulated in demo mode");
                }
                return null;
        }
    }

    private String move(String json, long now) {
        Double distance = num(json, "distance"), direction = num(json, "direction"), speed = num(json, "speed");
        if (distance == null || direction == null || speed == null) return error("004", "Internal service error");
        if (distance == 0 || speed == 0) {
            motion = null; // the stop body: a hard stop, as on the real robot
            return OK;
        }
        if (distance < 0 || speed < 0) return error("004", "Internal service error");
        double v = Math.min(speed, MAX_FIRMWARE_LINEAR);
        start(false, direction == 1 ? 1 : -1, distance / 100.0, v, 0.36 * Math.pow(v, 0.40), now);
        return OK;
    }

    private String turn(String json, long now) {
        Double direction = num(json, "direction"), angle = num(json, "angle"), speed = num(json, "speed");
        if (direction == null || angle == null || speed == null) return error("004", "Internal service error");
        if (angle == 0 || speed == 0) {
            motion = null;
            return OK;
        }
        // SLAM 3.0 docs limit angle to [-180, 180]; assume the firmware rejects more.
        if (Math.abs(angle) > 180 || speed < 0) return error("004", "Internal service error");
        double sign = (direction == 1 ? 1 : -1) * Math.signum(angle);
        start(true, sign, Math.toRadians(Math.abs(angle)), speed, ANGULAR_ACCEL, now);
        return OK;
    }

    /**
     * A new step replaces the running one outright (GUESS: the firmware's pre-emption
     * behaviour is unmeasured). With the e-stop pressed or an obstacle simulated the
     * step is accepted but the wheels never turn (also a GUESS).
     */
    private void start(boolean turn, double sign, double amount, double speed, double accel, long now) {
        if (estopPressed || blockNext) {
            blockNext = false;
            motion = null;
            return;
        }
        motion = new Motion(turn, sign, amount, speed, accel, now + CMD_LATENCY_NS);
    }

    // ------------------------------------------------------------------ physics

    private void advance(long now) {
        long t = lastNs;
        while (t < now && motion != null) {
            long next = Math.min(now, t + STEP_NS);
            double v = motion.sign * motion.speedAt((t + next) / 2);
            double dt = (next - t) / 1e9;
            if (motion.turn) {
                theta = wrap(theta + v * dt);
            } else {
                double nx = x + v * dt * Math.cos(theta);
                double ny = y + v * dt * Math.sin(theta);
                if (collides(nx, ny)) {
                    motion = null; // obstacle brake: stops short, like the real safety brake
                } else {
                    x = nx;
                    y = ny;
                }
            }
            if (v != 0) battery = Math.max(0, battery - dt / MOVING_SECONDS_PER_PERCENT);
            if (motion != null && motion.finished(next)) motion = null;
            t = next;
        }
        lastNs = now;
    }

    private static boolean collides(double px, double py) {
        double r = ROBOT_RADIUS;
        if (Math.abs(px) > ROOM_X - r || Math.abs(py) > ROOM_Y - r) return true;
        return px > BOX[0] - r && px < BOX[2] + r && py > BOX[1] - r && py < BOX[3] + r;
    }

    /** Lidar hits as [[x,y],...] in metres. GUESS: map frame; confirm on the real robot. */
    private String laser() {
        StringBuilder sb = new StringBuilder("{\"coordinates\":[");
        boolean first = true;
        for (int i = 0; i < LASER_BEAMS; i++) {
            double a = theta + 2 * Math.PI * i / LASER_BEAMS;
            double dx = Math.cos(a), dy = Math.sin(a);
            double best = LASER_RANGE;
            for (double[] w : WALLS) best = Math.min(best, rayHit(x, y, dx, dy, w));
            if (best >= LASER_RANGE) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append(fmt("[%.3f,%.3f]", x + dx * best, y + dy * best));
        }
        return sb.append("]}").toString();
    }

    /** Distance along the ray to segment w = {ax, ay, bx, by}, or infinity. */
    private static double rayHit(double ox, double oy, double dx, double dy, double[] w) {
        double ex = w[2] - w[0], ey = w[3] - w[1];
        double den = dx * ey - dy * ex;
        if (Math.abs(den) < 1e-12) return Double.POSITIVE_INFINITY;
        double wx = w[0] - ox, wy = w[1] - oy;
        double t = (wx * ey - wy * ex) / den;
        double u = (wx * dy - wy * dx) / den;
        return (t > 0 && u >= 0 && u <= 1) ? t : Double.POSITIVE_INFINITY;
    }

    // ------------------------------------------------------------------ demo controls

    synchronized void setEstopPressed(boolean pressed) {
        advance(clock.getAsLong());
        estopPressed = pressed;
        if (pressed) motion = null; // the e-stop cuts the motors
    }

    synchronized boolean isEstopPressed() {
        return estopPressed;
    }

    synchronized void setOffline(boolean off) {
        offline = off;
    }

    synchronized boolean isOffline() {
        return offline;
    }

    /** The next step is accepted but never starts, like a path blocked by an obstacle. */
    synchronized void blockNextStep() {
        blockNext = true;
    }

    synchronized boolean isBlockNextPending() {
        return blockNext;
    }

    /** Knocks 10% off the battery; wraps back to full below zero. */
    synchronized void drainBattery() {
        battery = battery >= 10 ? battery - 10 : 100;
    }

    synchronized int batteryPercent() {
        return (int) Math.ceil(battery);
    }

    /** Back to the centre of the room, full-ish battery, no faults. */
    synchronized void reset() {
        lastNs = clock.getAsLong();
        x = y = theta = 0;
        poseX = poseY = poseTheta = 0;
        poseNs = lastNs - POSE_PERIOD_NS;
        motion = null;
        battery = 87;
        estopPressed = offline = blockNext = false;
    }

    // ------------------------------------------------------------------ helpers

    private double noise() {
        return realistic ? (rng.nextDouble() * 2 - 1) * SPEED_NOISE : 0;
    }

    private static double wrap(double a) {
        while (a > Math.PI) a -= 2 * Math.PI;
        while (a <= -Math.PI) a += 2 * Math.PI;
        return a;
    }

    private static String error(String code, String message) {
        return "{\"error\":\"" + message + "\",\"error_code\":\"" + code + "\"}";
    }

    private static String fmt(String format, Object... args) {
        return String.format(Locale.US, format, args);
    }

    /** Reads a number from a flat JSON body, quoted or not. Enough for the app's own commands. */
    private static Double num(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"?(-?\\d+(?:\\.\\d+)?)")
                .matcher(json);
        return m.find() ? Double.valueOf(m.group(1)) : null;
    }
}
