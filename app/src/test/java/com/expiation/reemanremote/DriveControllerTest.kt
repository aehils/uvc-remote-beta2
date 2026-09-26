package com.expiation.reemanremote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The drive rules, end to end against the demo robot, in virtual time:
 * polling, interlocks, the step lock, STOP and connection loss.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveControllerTest {

    private fun TestScope.controller(demo: Boolean = true): DriveController {
        // Realistic latency and speed noise, seeded so runs repeat exactly.
        val fake = FakeRobot({ testScheduler.currentTime * 1_000_000 }, Random(1), realistic = true)
        // The live address is TEST-NET; these tests never switch to it.
        return DriveController(backgroundScope, RobotApi("192.0.2.1"), fake, demo, now = { testScheduler.currentTime })
    }

    /** Connected, e-stop read, armed: ready to drive. */
    private fun TestScope.ready(): DriveController {
        val c = controller()
        c.resume()
        advanceTimeBy(2000)
        c.setArmed(true)
        assertTrue(log(c), c.state.value.canDrive)
        return c
    }

    private fun TestScope.waitFor(c: DriveController, maxMs: Long = 15_000, cond: (RemoteState) -> Boolean): Long {
        val start = currentTime
        while (!cond(c.state.value)) {
            check(currentTime - start < maxMs) { "timed out\n" + log(c) }
            advanceTimeBy(50)
        }
        return currentTime - start
    }

    private fun log(c: DriveController) = c.state.value.log.joinToString("\n")
    private fun logged(c: DriveController, text: String) = c.state.value.log.any { text in it }
    private fun robotX(c: DriveController) =
        Regex("\"x\":(-?[0-9.]+)").find(c.demoRobot.handle("GET", "/reeman/pose", null)!!.body)!!.groupValues[1].toDouble()

    @Test
    fun connectsAndReadsStatus() = runTest {
        val c = controller()
        c.resume()
        advanceTimeBy(2000)
        val s = c.state.value
        assertEquals(Link.Connected(EStop.RELEASED), s.link)
        assertEquals(87, s.battery)
        assertFalse("not armed yet", s.canDrive)
    }

    @Test
    fun stepLocksThenFinishesWhenTheRobotIsStill() = runTest {
        val c = ready()
        c.drive(Move.FORWARD)
        assertTrue(c.state.value.phase is StepPhase.Stepping)
        c.drive(Move.FORWARD) // double tap: ignored by the lock
        assertEquals(1, c.state.value.log.count { "sending" in it })

        val took = waitFor(c) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "Forward 0.5 m: done"))
        assertTrue("took $took ms", took in 3000..6000)
        assertEquals(0.5, robotX(c), 0.02)
        assertTrue(c.state.value.canDrive)
    }

    @Test
    fun turnRunsToDone() = runTest {
        val c = ready()
        c.drive(Move.RIGHT)
        waitFor(c) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "Right 90°: done"))
    }

    @Test
    fun interlocksBlockDriving() = runTest {
        val c = controller()
        c.resume()
        advanceTimeBy(2000)
        c.drive(Move.FORWARD)
        assertEquals("not armed", StepPhase.Idle, c.state.value.phase)

        c.setArmed(true)
        c.demoToggleEstop()
        waitFor(c) { it.estop == EStop.PRESSED }
        assertFalse(c.state.value.canDrive)
        c.drive(Move.FORWARD)
        assertEquals("e-stop pressed", StepPhase.Idle, c.state.value.phase)
    }

    @Test
    fun blockedStepReportsNoMotion() = runTest {
        val c = ready()
        c.demoBlockNextStep()
        c.drive(Move.FORWARD)
        val took = waitFor(c) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "no motion seen"))
        // estimate (0.5 m / 0.3 m/s = 1.67 s, + 2.5 s ramp allowance) + 2 s grace = 6.17 s, plus a poll
        assertTrue("took $took ms", took in 6100..6800)
    }

    @Test
    fun stopMidStepStopsTheRobotAndReleasesTheLock() = runTest {
        val c = ready()
        c.drive(Move.FORWARD)
        advanceTimeBy(1500)
        assertTrue(c.state.value.vx > 0.1)
        c.stop()
        assertTrue(c.state.value.phase is StepPhase.Stopping)
        waitFor(c) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "STOP: sent"))
        assertTrue(log(c), logged(c, "Forward 0.5 m: stopped"))
        assertTrue("stopped short", robotX(c) < 0.4)
    }

    @Test
    fun estopMidStepIsNoticed() = runTest {
        val c = ready()
        c.drive(Move.FORWARD)
        advanceTimeBy(1500)
        c.demoToggleEstop()
        waitFor(c) { it.phase == StepPhase.Idle && it.estop == EStop.PRESSED }
        assertTrue(log(c), logged(c, "E-stop PRESSED"))
        assertFalse(c.state.value.canDrive)
    }

    @Test
    fun wifiDropDisconnectsAndRecovers() = runTest {
        val c = ready()
        c.demoToggleWifi()
        waitFor(c) { it.link is Link.Disconnected }
        assertTrue(log(c), logged(c, "Lost connection"))
        assertFalse(c.state.value.canDrive)

        c.demoToggleWifi()
        waitFor(c) { it.connected }
        assertEquals("e-stop must be re-read after reconnecting", null, c.state.value.estop)
        waitFor(c) { it.canDrive }
    }

    @Test
    fun modeSwitchIsRefusedMidStep() = runTest {
        val c = ready()
        c.drive(Move.LEFT)
        assertFalse(c.setDemo(false))
        assertTrue(c.state.value.demo)
        assertTrue(logged(c, "Wait for the current step"))
    }

    @Test
    fun modeSwitchDisarms() = runTest {
        val c = controller(demo = false) // never resumed, so nothing is sent to the live address
        c.setArmed(true)
        assertTrue(c.setDemo(true))
        assertTrue(c.state.value.demo)
        assertFalse(c.state.value.armed)
    }

    @Test
    fun pauseDisarmsAndStopsPolling() = runTest {
        val c = ready()
        c.pause()
        assertFalse(c.state.value.armed)
        c.demoToggleEstop()
        advanceTimeBy(3000)
        assertEquals("no polls while paused", EStop.RELEASED, c.state.value.estop)
    }

    @Test
    fun demoLogLinesAreTagged() = runTest {
        val c = ready()
        assertTrue(c.state.value.log.all { "[DEMO]" in it })
    }
}
