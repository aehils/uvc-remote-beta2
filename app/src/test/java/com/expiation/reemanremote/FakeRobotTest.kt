package com.expiation.reemanremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

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

    private fun forward() = assertEquals("{}", post("/cmd/move", """{"distance":50,"direction":1,"speed":0.30}"""))

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
        assertEquals("{}", post("/cmd/move", """{"distance":0,"direction":1,"speed":0}"""))
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
        assertTrue(post("/cmd/speed", """{"vx":0.2,"vth":0}""").contains("\"error_code\":\"004\""))
        assertTrue(
            "more than 180 degrees is rejected",
            post("/cmd/turn", """{"direction":1,"angle":270,"speed":0.4}""").contains("004"),
        )
    }

    @Test
    fun batteryDrainWrapsToFull() {
        repeat(8) { robot.drainBattery() }
        assertEquals(7, robot.batteryPercent())
        robot.drainBattery()
        assertEquals(100, robot.batteryPercent())
    }

    @Test
    fun laserSeesTheWallAhead() {
        assertTrue(get("/reeman/laser").startsWith("""{"coordinates":[[2.500,0.000]"""))
    }
}
