package com.expiation.reemanremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.Random
import java.util.zip.Inflater
import kotlin.math.abs

/** Drives the demo robot on a fake clock, checking it behaves like the real firmware. */
class FakeRobotTest {

    private var nowNs = 0L
    private val robot = FakeRobot({ nowNs }, Random(1), realistic = false)

    private fun advance(seconds: Double) {
        nowNs += (seconds * 1e9).toLong()
    }

    private fun get(path: String) = robot.handle("GET", path, null)!!.body
    private fun post(path: String, json: String) = robot.handle("POST", path, json)!!.body

    private fun num(json: String, key: String): Double {
        val m = Regex("\"$key\":\"?(-?[0-9.]+)").find(json)
        assertTrue("$key missing in $json", m != null)
        return m!!.groupValues[1].toDouble()
    }

    private fun vx() = num(get("/reeman/speed"), "vx")
    private fun vth() = num(get("/reeman/speed"), "vth")
    private fun pose(key: String) = num(get("/reeman/pose"), key)

    private fun forward() = assertEquals(FakeRobot.OK, post("/cmd/move", """{"distance":50,"direction":1,"speed":0.30}"""))

    @Test
    fun statusMatchesFirmwareShapes() {
        assertTrue(get("/reeman/current_version").contains("DEMO"))
        val base = get("/reeman/base_encode")
        assertEquals(87.0, num(base, "battery"), 0.0)
        assertEquals(1.0, num(base, "emergencyButton"), 0.0) // 1 = released
        assertEquals(0.0, vx(), 0.0)
    }

    @Test
    fun forwardStepHasLatencyRampCruiseAndStops() {
        forward()
        advance(0.1)
        assertEquals("still inside the ~200 ms latency", 0.0, vx(), 1e-9)
        advance(0.6)
        val ramping = vx()
        assertTrue(ramping > 0.05 && ramping < 0.3)
        advance(1.1) // 1.6 s after the wheels started: cruising
        assertEquals(0.3, vx(), 1e-6)
        advance(2.0)
        assertEquals(0.0, vx(), 1e-9)
        advance(0.3)
        assertEquals(0.5, pose("x"), 0.01)
    }

    private fun chargeFlag() = num(get("/reeman/base_encode"), "chargeFlag")
    private fun dock() = assertEquals(FakeRobot.OK, post("/cmd/charge", """{"type":0,"point":"charging_pile"}"""))

    @Test
    fun dockTurnsAwayFromThePileThenReversesOntoItAndCharges() {
        post("/cmd/turn", """{"direction":1,"angle":90,"speed":0.40}""") // face +y: the pile is to its right
        advance(10.0)
        dock()
        assertEquals(8.0, chargeFlag(), 0.0)
        advance(2.0)
        assertTrue("turning first", abs(vth()) > 0.1)
        advance(30.0)
        assertEquals(2.0, chargeFlag(), 0.0)
        assertEquals(-2.15, pose("x"), 0.01)
        assertEquals(0.0, pose("y"), 0.01)
        assertEquals("faces away from the pile", 0.0, pose("theta"), 0.01)

        val battery = num(get("/reeman/base_encode"), "battery")
        advance(10.0)
        assertEquals("charges on the pile", battery + 5, num(get("/reeman/base_encode"), "battery"), 1.0)

        forward()
        assertEquals("driving off leaves the pile", 0.0, chargeFlag(), 0.0)
    }

    @Test
    fun dockOutOfRangeNeverMoves() {
        forward()
        advance(5.0)
        dock()
        advance(3.0)
        assertEquals(0.0, vx(), 0.0)
        assertEquals(9.0, chargeFlag(), 0.0)
    }

    @Test
    fun cancelDockStopsTheRobot() {
        dock()
        advance(3.0)
        assertTrue(vx() < -0.05)
        assertEquals(FakeRobot.OK, post("/cmd/charge", """{"type":1,"point":"charging_pile"}"""))
        assertEquals(0.0, vx(), 0.0)
        assertEquals(0.0, chargeFlag(), 0.0)
    }

    private fun nav(point: String) = post("/cmd/nav_name", """{"point":"$point"}""")
    private fun planning() = !get("/reeman/global_plan").contains("\"007\"")

    @Test
    fun positionListsTheSavedPoints() {
        val body = get("/reeman/position")
        assertTrue(body, body.startsWith("""{"waypoints":[{"name":"Bed 1","type":"delivery","pose":{"x":1.6000,"y":-1.2000"""))
        assertTrue(body, body.contains(""""name":"charging_pile""""))
    }

    @Test
    fun navTurnsDrivesAndSettlesOnThePointsHeading() {
        assertTrue("no plan while idle", !planning())
        assertEquals(FakeRobot.OK, nav("Bed 1"))
        advance(1.0)
        assertTrue("turning to face the point first", abs(vth()) > 0.1)
        assertTrue(planning())
        advance(40.0)
        assertEquals(1.6, pose("x"), 0.02)
        assertEquals(-1.2, pose("y"), 0.02)
        assertEquals(-Math.PI / 2, pose("theta"), 0.02)
        assertTrue("plan gone on arrival", !planning())
    }

    @Test
    fun cancelGoalStopsANavigation() {
        nav("Bed 2")
        advance(5.0)
        assertTrue(planning())
        assertEquals(FakeRobot.OK, post("/cmd/cancel_goal", "{}"))
        assertEquals(0.0, vx(), 0.0)
        assertEquals(0.0, vth(), 0.0)
        assertTrue(!planning())
    }

    @Test
    fun setPointSavesAPointTheRobotCanGoTo() {
        assertEquals(FakeRobot.OK, post("/cmd/position", """{"name":"Door","type":"delivery","pose":{"x":0.8,"y":-0.4,"theta":0.0}}"""))
        assertTrue(get("/reeman/position").contains(""""name":"Door","type":"delivery","pose":{"x":0.8000,"y":-0.4000"""))
        assertTrue("type must be one the API lists",
            post("/cmd/position", """{"name":"X","type":"disinfect","pose":{"x":0,"y":0,"theta":0}}""").contains("004"))
        nav("Door")
        advance(30.0)
        assertEquals(0.8, pose("x"), 0.02)
        assertEquals(-0.4, pose("y"), 0.02)

        robot.reset()
        assertTrue("reset restores the demo points", !get("/reeman/position").contains("Door"))
    }

    @Test
    fun stopBodyOnlyPausesANavigationLikeTheRealRobot() {
        nav("Bed 2")
        advance(4.0)
        assertTrue(abs(vx()) + abs(vth()) > 0.05)
        post("/cmd/move", """{"distance":0,"direction":1,"speed":0}""")
        assertEquals(0.0, vx(), 1e-9)
        assertEquals(0.0, vth(), 1e-9)
        advance(3.0)
        assertTrue("carried on after the stop body", abs(vx()) + abs(vth()) > 0.05)
        post("/cmd/cancel_goal", "{}")
        advance(3.0)
        assertEquals("cancel_goal ends it", 0.0, abs(vx()) + abs(vth()), 1e-9)
    }

    @Test
    fun chargeType2NavigatesToThePileThenDocks() {
        assertEquals(FakeRobot.OK, post("/cmd/charge", """{"type":2,"point":"charging_pile"}"""))
        advance(2.0)
        assertTrue("a trip with a plan", planning())
        advance(60.0)
        assertEquals(2.0, chargeFlag(), 0.0)
        assertEquals(-2.15, pose("x"), 0.02)
        assertEquals(0.0, pose("y"), 0.02)
        assertTrue(!planning())
    }

    @Test
    fun chargeCancelEndsAChargeTrip() {
        robot.goChargeOnItsOwn()
        advance(4.0)
        post("/cmd/charge", """{"type":1,"point":"charging_pile"}""")
        advance(5.0)
        assertEquals(0.0, abs(vx()) + abs(vth()), 1e-9)
        assertEquals(0.0, chargeFlag(), 0.0)
    }

    @Test
    fun navToAnUnknownPointIsRejected() {
        assertTrue(nav("Bed 9").contains("\"error_code\":\"004\""))
        assertTrue(!planning())
    }

    @Test
    fun backwardStepMovesBackward() {
        post("/cmd/move", """{"distance":50,"direction":0,"speed":0.20}""")
        advance(1.5)
        assertTrue(vx() < -0.05)
        advance(5.0)
        assertEquals(-0.5, pose("x"), 0.01)
    }

    @Test
    fun turnsGoTheRightWay() {
        post("/cmd/turn", """{"direction":1,"angle":90,"speed":0.40}""")
        advance(1.5)
        assertTrue("left is anticlockwise", vth() > 0.1)
        advance(10.0)
        assertEquals(Math.PI / 2, pose("theta"), 0.01)

        post("/cmd/turn", """{"direction":0,"angle":180,"speed":0.40}""")
        advance(1.5)
        assertTrue("right is clockwise", vth() < -0.1)
        advance(15.0)
        assertEquals(-Math.PI / 2, pose("theta"), 0.01)
    }

    @Test
    fun stopBodyIsAHardStop() {
        forward()
        advance(1.5)
        assertTrue(vx() > 0.1)
        assertEquals(FakeRobot.OK, post("/cmd/move", """{"distance":0,"direction":1,"speed":0}"""))
        assertEquals(0.0, vx(), 1e-9)
        advance(1.0)
        assertEquals(0.0, vx(), 1e-9)
    }

    @Test
    fun estopBlocksMotionAndStopsARunningStep() {
        forward()
        advance(1.5)
        robot.setEstopPressed(true)
        assertEquals(0.0, vx(), 1e-9)
        assertEquals(0.0, num(get("/reeman/base_encode"), "emergencyButton"), 0.0)
        forward()
        advance(1.5)
        assertEquals("accepted but never moves", 0.0, vx(), 1e-9)
        robot.setEstopPressed(false)
        forward()
        advance(1.5)
        assertTrue(vx() > 0.1)
    }

    @Test
    fun blockedStepNeverStartsButTheNextOneDoes() {
        robot.blockNextStep()
        forward()
        advance(1.5)
        assertEquals(0.0, vx(), 1e-9)
        forward()
        advance(1.5)
        assertTrue(vx() > 0.1)
    }

    @Test
    fun wallStopsTheRobotShort() {
        repeat(5) {
            forward()
            advance(5.0)
        }
        // Wall at x = 2.5, robot radius 0.3: the fifth step can't complete.
        val x = pose("x")
        assertTrue("stopped short at $x", x > 2.15 && x <= 2.2)
        forward()
        advance(1.5)
        assertEquals("already against the wall", 0.0, vx(), 1e-9)
    }

    @Test
    fun wifiDropReturnsNoReply() {
        robot.isOffline = true
        assertNull(robot.handle("GET", "/reeman/speed", null))
        robot.isOffline = false
        assertEquals(200, robot.handle("GET", "/reeman/speed", null)!!.code)
    }

    @Test
    fun errorsUseFirmwareCodes() {
        val r = robot.handle("GET", "/robot/get_mode", null)!!
        assertEquals(404, r.code)
        assertTrue(r.body.contains("\"error_code\":\"009\""))
        assertTrue(
            "more than 180 degrees is rejected",
            post("/cmd/turn", """{"direction":1,"angle":270,"speed":0.4}""").contains("004"),
        )
    }

    @Test
    fun speedStreamingIsInstantAndTheWatchdogStopsItHard() {
        post("/cmd/speed", """{"vx":0.3,"vth":0}""")
        advance(0.1)
        assertEquals("still inside the ~200 ms latency", 0.0, vx(), 1e-9)
        advance(0.15)
        assertEquals("no ramp of its own", 0.3, vx(), 1e-9)
        post("/cmd/speed", """{"vx":0.2,"vth":0}""")
        advance(0.25)
        assertEquals(0.2, vx(), 1e-9)
        advance(1.0)
        assertEquals("runs on without commands", 0.2, vx(), 1e-9)
        advance(0.2)
        assertEquals("the watchdog: a hard stop ~1.16 s after the last command", 0.0, vx(), 1e-9)
        post("/cmd/speed", """{"vx":0,"vth":0.4}""")
        advance(0.3)
        assertEquals(0.4, vth(), 1e-9)
        post("/cmd/move", """{"distance":0,"direction":1,"speed":0}""")
        assertEquals("a stop body ends it", 0.0, vth(), 1e-9)
    }

    // ------------------------------------------------------------------ maps

    /** An 8-bit greyscale image; [at] is the value at column x, row y (row 0 at the top). */
    private class Grey(val width: Int, val height: Int, private val px: ByteArray) {
        fun at(x: Int, y: Int) = px[y * width + x].toInt() and 0xff
    }

    /**
     * Decodes the map's PNG by hand (javax.imageio isn't on Android's class path), so the
     * simulator's encoder is checked by a separate reader: signature, chunks, unfiltered rows.
     */
    private fun png(body: String): Grey {
        val b64 = Regex("base64,([A-Za-z0-9+/=]+)").find(body)!!.groupValues[1]
        val buf = ByteBuffer.wrap(Base64.getDecoder().decode(b64))
        val sig = ByteArray(8).also { buf.get(it) }
        assertTrue("PNG signature", sig.contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)))
        var w = 0
        var h = 0
        val idat = ByteArrayOutputStream()
        while (buf.hasRemaining()) {
            val len = buf.int
            val type = String(ByteArray(4).also { buf.get(it) }, Charsets.US_ASCII)
            val data = ByteArray(len).also { buf.get(it) }
            buf.int // CRC
            when (type) {
                "IHDR" -> {
                    val d = ByteBuffer.wrap(data)
                    w = d.int
                    h = d.int
                    assertEquals("8-bit", 8, d.get().toInt())
                    assertEquals("greyscale", 0, d.get().toInt())
                }
                "IDAT" -> idat.write(data)
            }
        }
        val raw = ByteArray((w + 1) * h)
        Inflater().apply {
            setInput(idat.toByteArray())
            assertEquals(raw.size, inflate(raw))
            end()
        }
        val px = ByteArray(w * h)
        for (row in 0 until h) {
            assertEquals("filter none", 0, raw[row * (w + 1)].toInt())
            System.arraycopy(raw, row * (w + 1) + 1, px, row * w, w)
        }
        return Grey(w, h, px)
    }

    /** The grey value at a map-frame spot, read the way the app places it (row 0 at the top). */
    private fun cellAt(body: String, x: Double, y: Double): Int {
        val img = png(body)
        val res = num(body, "resolution")
        val col = ((x - num(body, "origin_x")) / res).toInt()
        val row = img.height - 1 - ((y - num(body, "origin_y")) / res).toInt()
        return img.at(col, row)
    }

    private fun mapIds() = Regex("\"name\":\"([0-9a-f]{32})\"").findAll(get("/reeman/history_map")).map { it.groupValues[1] }.toList()
    private fun currentId() = Regex("\"name\":\"([0-9a-f]{32})\"").find(get("/reeman/current_map"))!!.groupValues[1]

    @Test
    fun mapIsAPngOfTheRoomWithItsScaleAndOrigin() {
        val body = get("/reeman/map")
        val img = png(body)
        assertEquals(num(body, "width").toInt(), img.width)
        assertEquals(num(body, "height").toInt(), img.height)
        assertEquals(0.05, num(body, "resolution"), 1e-9)
        assertEquals("free where the robot starts", 254, cellAt(body, 0.0, 0.0))
        assertEquals("the east wall", 0, cellAt(body, 2.53, 0.0))
        assertEquals("the north wall", 0, cellAt(body, 0.0, 2.03))
        assertEquals("outside the room", 205, cellAt(body, 0.0, -2.22))
        assertTrue(get("/reeman/current_map").contains("\"alias\":\"Demo room\""))
        assertEquals(2, mapIds().size)
    }

    @Test
    fun savingAScanAddsANewMapInUse() {
        val before = currentId()
        post("/cmd/set_mode", """{"mode":1}""")
        assertEquals(1.0, num(get("/reeman/get_mode"), "mode"), 0.0)
        assertEquals(FakeRobot.OK, post("/cmd/save_map", "{}"))
        post("/cmd/set_mode", """{"mode":2}""")
        assertTrue(currentId() != before)
        assertEquals(3, mapIds().size)
        assertEquals("""{"waypoints":[]}""", get("/reeman/position"))
    }

    @Test
    fun mapsCanBeRenamedBinnedAndRestoredButNotTheOneInUse() {
        val (room, old) = mapIds()
        assertTrue(post("/cmd/trash_map", """{"name":"$room"}""").contains("error_code"))
        assertEquals(FakeRobot.OK, post("/cmd/rename_map", """{"name":"$old","alias":"Ward 3"}"""))
        assertTrue(get("/reeman/history_map").contains("\"alias\":\"Ward 3\""))
        assertEquals(FakeRobot.OK, post("/cmd/trash_map", """{"name":"$old"}"""))
        assertEquals(listOf(room), mapIds())
        assertEquals(FakeRobot.OK, post("/cmd/restore_map", """{"name":"$old"}"""))
        assertEquals(2, mapIds().size)
    }

    @Test
    fun batteryDrainWrapsToFull() {
        repeat(8) { robot.drainBattery() }
        assertEquals(7, robot.batteryPercent())
        robot.drainBattery()
        assertEquals(100, robot.batteryPercent())
    }
}
