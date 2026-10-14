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
import kotlin.math.abs
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

    /** Connected and e-stop read: ready to drive. */
    private fun TestScope.ready(): DriveController {
        val c = controller()
        c.resume()
        advanceTimeBy(2000)
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
        assertTrue("ready to drive", s.canDrive)
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
        c.drive(Move.FORWARD)
        assertEquals("not connected", StepPhase.Idle, c.state.value.phase)

        c.resume()
        advanceTimeBy(2000)
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

    private fun chargeFlag(c: DriveController) =
        Regex("\"chargeFlag\":(\\d+)").find(c.demoRobot.handle("GET", "/reeman/base_encode", null)!!.body)!!.groupValues[1].toInt()

    /** Ready to drive, 1.15 m from the pile (the demo starts 2.15 m from it). */
    private fun TestScope.nearThePile(): DriveController {
        val c = ready()
        repeat(2) {
            c.drive(Move.BACK)
            waitFor(c) { it.phase == StepPhase.Idle && it.canDrive }
        }
        return c
    }

    @Test
    fun goToChargerDocksStraightAwayWhenThePileIsClose() = runTest {
        val c = nearThePile()
        c.goToCharger()
        waitFor(c) { it.phase is StepPhase.Stepping }
        assertTrue(log(c), logged(c, "m away, docking straight away"))
        assertTrue(log(c), logged(c, "sending /cmd/charge {\"type\":0"))
        c.drive(Move.FORWARD) // locked while docking
        assertEquals(3, c.state.value.log.count { "sending" in it })

        waitFor(c, maxMs = 40_000) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "Go to charger (docking): done. Robot reports: charging at the pile."))
        assertFalse(log(c), logged(c, "type\":2"))
        assertEquals("on the pile against the west wall", -2.15, robotX(c), 0.02)
        assertEquals(2, chargeFlag(c))
    }

    @Test
    fun goToChargerDrivesThereFirstWhenThePileIsFar() = runTest {
        val c = ready() // 2.15 m from the pile
        c.goToCharger()
        waitFor(c) { it.phase is StepPhase.Stepping }
        assertTrue(log(c), logged(c, "m away, driving there first"))
        assertTrue(log(c), logged(c, "Go to charger: sending /cmd/charge {\"type\":2,\"point\":\"charging_pile\"}"))
        waitFor(c, maxMs = 90_000) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "Go to charger: done. Robot reports: charging at the pile."))
        assertEquals(-2.15, robotX(c), 0.02)
    }

    @Test
    fun goToChargerDrivesThereIfDockingFindsNoPile() = runTest {
        val c = nearThePile()
        c.demoBlockNextStep() // the dock is accepted but never starts, as with no pile in range
        c.goToCharger()
        waitFor(c, maxMs = 90_000) { logged(c, "Go to charger: done") }
        assertTrue(log(c), logged(c, "Go to charger (docking): no motion seen"))
        assertTrue(log(c), logged(c, "couldn't dock from here, driving to the pile first"))
        assertTrue(log(c), logged(c, "Robot reports: charging at the pile."))
        assertEquals(-2.15, robotX(c), 0.02)
    }

    @Test
    fun goToChargerOnThePileDoesNotDriveOff() = runTest {
        val c = nearThePile()
        c.goToCharger()
        waitFor(c, maxMs = 40_000) { logged(c, "charging at the pile") && it.phase == StepPhase.Idle }
        c.goToCharger() // again, already docked
        waitFor(c, maxMs = 20_000) { logged(c, "no motion seen") }
        assertTrue(log(c), logged(c, "Already docked, or no charging pile within range?"))
        advanceTimeBy(5000)
        assertFalse(log(c), logged(c, "driving to the pile first"))
        assertEquals(2, chargeFlag(c))
    }

    @Test
    fun goToChargerNeedsTheInterlock() = runTest {
        val c = ready()
        c.demoToggleEstop()
        waitFor(c) { it.estop == EStop.PRESSED }
        c.goToCharger()
        advanceTimeBy(2000)
        assertEquals("e-stop pressed", StepPhase.Idle, c.state.value.phase)
        assertFalse(logged(c, "/cmd/charge"))
    }

    @Test
    fun stopWhileGoToChargerIsDecidingSendsNothing() = runTest {
        val c = ready()
        c.goToCharger()
        c.stop() // before the app has finished asking where the pile is
        advanceTimeBy(10_000)
        assertFalse(log(c), logged(c, "sending /cmd/charge"))
        assertEquals(StepPhase.Idle, c.state.value.phase)
    }

    @Test
    fun stopMidDockCancelsDockingAndDoesNotFallBack() = runTest {
        val c = nearThePile()
        c.goToCharger()
        advanceTimeBy(4000)
        assertTrue(abs(c.state.value.vx) + abs(c.state.value.vth) > 0.05)
        c.stop()
        waitFor(c) { it.phase == StepPhase.Idle }
        advanceTimeBy(5000)
        assertTrue(log(c), logged(c, "STOP: sent"))
        assertTrue(log(c), logged(c, "Go to charger (docking): stopped"))
        assertFalse(log(c), logged(c, "driving to the pile first"))
        assertTrue("stopped short of the pile", robotX(c) > -2.0)
        assertEquals("not docking any more", 0, chargeFlag(c))
    }

    private fun robotPose(c: DriveController, key: String) =
        Regex("\"$key\":(-?[0-9.]+)").find(c.demoRobot.handle("GET", "/reeman/pose", null)!!.body)!!.groupValues[1].toDouble()

    /** Ready to drive, with the points read. */
    private fun TestScope.withPoints(): Pair<DriveController, List<Waypoint>> {
        val c = ready()
        c.loadPoints()
        waitFor(c) { it.points is Points.Loaded }
        return c to (c.state.value.points as Points.Loaded).list
    }

    @Test
    fun loadsThePointsWithoutTheChargingPile() = runTest {
        val (c, points) = withPoints()
        assertEquals(listOf("Bed 1", "Bed 2", "Sink area"), points.map { it.name })
        assertEquals(Pose(1.6, -1.2, -1.5708), points[0].pose)
        assertTrue(log(c), logged(c, "Loaded 3 disinfection point(s)"))
    }

    @Test
    fun goToDrivesToThePointAndReportsArrival() = runTest {
        val (c, points) = withPoints()
        c.goTo(points[0])
        assertEquals(points[0], c.state.value.running?.goal)
        assertTrue(log(c), logged(c, "Go to Bed 1: sending /cmd/nav_name {\"point\":\"Bed 1\"}"))
        c.drive(Move.FORWARD) // locked while navigating
        assertEquals(1, c.state.value.log.count { "sending" in it })

        waitFor(c, maxMs = 60_000) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "Go to Bed 1: arrived (0.0"))
        assertEquals(1.6, robotPose(c, "x"), 0.02)
        assertEquals(-1.2, robotPose(c, "y"), 0.02)
    }

    @Test
    fun tripStaysLockedWhileTheRobotWaitsOnItsWay() = runTest {
        val (c, points) = withPoints()
        c.goTo(points[0])
        advanceTimeBy(5000) // driving towards the point
        assertTrue(c.state.value.demoStatus!!.navigating)
        c.demoPersonInTheWay() // still for 5 s: longer than the ~3 s that would end a step
        advanceTimeBy(4500)
        assertEquals("waiting", 0.0, c.state.value.vx, 0.02)
        assertTrue("still locked while the robot has a plan\n" + log(c), c.state.value.phase is StepPhase.Stepping)

        waitFor(c, maxMs = 60_000) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "Go to Bed 1: arrived"))
    }

    @Test
    fun addPointHereSavesTheSpotAndTheRobotCanGoBackToIt() = runTest {
        val (c, _) = withPoints()
        c.drive(Move.FORWARD)
        waitFor(c) { it.phase == StepPhase.Idle }

        c.addPointHere("  Bed 4 ")
        assertTrue(c.state.value.addingPoint)
        waitFor(c) { !it.addingPoint && (it.points as? Points.Loaded)?.list?.size == 4 }
        val added = (c.state.value.points as Points.Loaded).list.single { it.name == "Bed 4" }
        assertEquals(DriveController.NEW_POINT_TYPE, added.type)
        assertEquals(0.5, added.pose!!.x, 0.02)
        assertEquals(0.0, added.pose!!.y, 0.02)
        assertEquals("Added \"Bed 4\" at x 0.50, y 0.00", c.state.value.pointMessage)
        val sent = c.state.value.log.single { "Add point: sending /cmd/position" in it }
        assertTrue(sent, "\"name\":\"Bed 4\"" in sent && "\"type\":\"delivery\"" in sent && "\"pose\":{" in sent)

        c.drive(Move.BACK)
        waitFor(c) { it.phase == StepPhase.Idle }
        c.goTo(added)
        waitFor(c, maxMs = 60_000) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "Go to Bed 4: arrived"))
        assertEquals(0.5, robotX(c), 0.02)
    }

    @Test
    fun addPointHereRefusesTakenNamesAndAMovingRobot() = runTest {
        val (c, _) = withPoints()
        c.addPointHere("Bed 1")
        assertEquals("A point called \"Bed 1\" already exists.", c.state.value.pointMessage)
        c.addPointHere("charging_pile")
        assertEquals("A point called \"charging_pile\" already exists.", c.state.value.pointMessage)
        c.addPointHere(" ")
        assertEquals("Give the point a name.", c.state.value.pointMessage)

        c.drive(Move.FORWARD)
        advanceTimeBy(1500)
        assertFalse(c.state.value.canAddPoint)
        c.addPointHere("Mid-step")
        assertEquals("Wait for the robot to stand still.", c.state.value.pointMessage)
        assertFalse(c.state.value.addingPoint)
        assertFalse(logged(c, "/cmd/position"))
    }

    @Test
    fun goToNeedsTheInterlock() = runTest {
        val (c, points) = withPoints()
        c.demoToggleEstop()
        waitFor(c) { it.estop == EStop.PRESSED }
        c.goTo(points[0])
        assertEquals("e-stop pressed", StepPhase.Idle, c.state.value.phase)
    }

    @Test
    fun stopMidTripCancelsTheGoal() = runTest {
        val (c, points) = withPoints()
        c.goTo(points[1])
        advanceTimeBy(6000)
        c.stop()
        waitFor(c) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "STOP: sent"))
        assertTrue(log(c), logged(c, "Go to Bed 2: stopped"))
        assertTrue("stopped short of the point", robotPose(c, "y") < 0.8)
        advanceTimeBy(5000)
        assertEquals("the robot does not carry on", 0.0, c.state.value.vx, 0.02)
    }

    @Test
    fun blockedTripReportsNoMotion() = runTest {
        val (c, points) = withPoints()
        c.demoBlockNextStep()
        c.goTo(points[2])
        val took = waitFor(c, maxMs = 30_000) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "Go to Sink area: no motion seen. Already at the point, or no route to it?"))
        assertTrue("took $took ms", took in 15_000..15_700)
    }

    @Test
    fun switchingRobotForgetsThePoints() = runTest {
        val (c, _) = withPoints()
        c.setDemo(false)
        assertEquals(Points.NotLoaded, c.state.value.points)
    }

    private fun moving(c: DriveController) = c.state.value.let { abs(it.vx) > 0.02 || abs(it.vth) > 0.03 }

    /** The robot stays still for [ms] (checked every poll). */
    private fun TestScope.staysStill(c: DriveController, ms: Long) {
        val end = currentTime + ms
        while (currentTime < end) {
            advanceTimeBy(300)
            assertFalse("moved at ${currentTime}\n" + log(c), moving(c))
        }
    }

    @Test
    fun stopEndsATaskTheRobotStartedOnItsOwn() = runTest {
        val c = ready()
        c.demoChargeOnItsOwn() // the incident: the robot sets off to charge by itself
        advanceTimeBy(4000)
        assertTrue(moving(c))
        assertTrue(log(c), logged(c, "Robot is moving, but not by this app"))
        assertFalse("no driving while the robot moves by itself", c.state.value.canDrive)

        c.stop()
        advanceTimeBy(2500)
        staysStill(c, 12_000) // the old STOP only paused it: it carried on ~1 s later
        assertTrue(log(c), logged(c, "STOP: sent"))
        assertFalse(log(c), logged(c, "sending STOP again"))
        assertEquals("not docking", 0, chargeFlag(c))
    }

    @Test
    fun stopIsSentAgainIfTheRobotCarriesOn() = runTest {
        val c = ready()
        c.demoChargeOnItsOwn()
        advanceTimeBy(4000)
        c.stop()
        advanceTimeBy(3000)
        c.demoRobot.goChargeOnItsOwn() // something on the robot starts the trip again
        waitFor(c) { logged(c, "Robot moving after STOP: sending STOP again (1)") }
        advanceTimeBy(2500)
        staysStill(c, 5000)
        assertFalse(c.state.value.runaway)
    }

    @Test
    fun robotThatWontStopRaisesTheAlarm() = runTest {
        val c = ready()
        c.demoChargeOnItsOwn()
        advanceTimeBy(4000)
        c.stop()
        val start = currentTime
        while (!c.state.value.runaway) { // keeps restarting, as if the robot ignored every cancel
            check(currentTime - start < 20_000) { "no alarm\n" + log(c) }
            c.demoRobot.goChargeOnItsOwn()
            advanceTimeBy(500)
        }
        assertTrue(log(c), logged(c, "USE THE PHYSICAL E-STOP"))

        waitFor(c) { !it.runaway } // it stops restarting: the next STOP holds
        assertTrue(log(c), logged(c, "Robot has stopped"))
    }

    @Test
    fun aNewCommandAfterStopIsNotTreatedAsTheRobotCarryingOn() = runTest {
        val c = ready()
        c.drive(Move.FORWARD)
        advanceTimeBy(1500)
        c.stop()
        waitFor(c) { it.phase == StepPhase.Idle && it.canDrive }
        c.drive(Move.FORWARD) // within the guard: the user's own step
        waitFor(c) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "Forward 0.5 m: done"))
        assertFalse(log(c), logged(c, "sending STOP again"))
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
    fun pauseStopsPolling() = runTest {
        val c = ready()
        c.pause()
        c.demoToggleEstop()
        advanceTimeBy(3000)
        assertEquals("no polls while paused", EStop.RELEASED, c.state.value.estop)
    }

    @Test
    fun demoLogLinesAreTagged() = runTest {
        val c = ready()
        assertTrue(c.state.value.log.all { "[DEMO]" in it })
    }

    // ------------------------------------------------------------------ UV disinfection

    private fun uvOn(c: DriveController) = c.demoRobot.isUvOn

    /** Ready, with the lamps on (countdown skipped). */
    private fun TestScope.lampsOn(minutes: Int = 5): DriveController {
        val c = ready()
        c.setUvSettings(UvSettings(minutes))
        c.startDisinfection()
        c.startDisinfectionNow()
        waitFor(c) { it.uv is Uv.On }
        assertTrue(uvOn(c))
        return c
    }

    @Test
    fun disinfectionCountsDownThenRunsForTheSetTime() = runTest {
        val c = ready()
        c.setUvSettings(UvSettings(minutes = 1, delaySeconds = 15))
        c.startDisinfection()
        assertTrue(c.state.value.uv is Uv.Countdown)
        assertFalse(c.state.value.canDrive)

        advanceTimeBy(14_000)
        assertFalse("not before the delay", uvOn(c))
        val tookOn = waitFor(c) { it.uv is Uv.On }
        assertTrue(uvOn(c))
        assertTrue("took $tookOn ms", tookOn < 2_000)

        c.drive(Move.FORWARD) // locked while the lamps are on
        assertEquals(StepPhase.Idle, c.state.value.phase)

        val ran = waitFor(c, maxMs = 70_000) { it.uv == null }
        assertTrue("ran $ran ms", ran in 59_000..61_000)
        waitFor(c) { !it.lampsOffOwed }
        assertFalse(uvOn(c))
        assertTrue(log(c), logged(c, "Disinfection done: UV lamps off"))
        assertTrue(c.state.value.canDrive)
    }

    @Test
    fun cancelDuringTheCountdownNeverSwitchesTheLampsOn() = runTest {
        val c = ready()
        c.setUvSettings(UvSettings(minutes = 1, delaySeconds = 15))
        c.startDisinfection()
        advanceTimeBy(3_000)
        c.stopDisinfection()
        advanceTimeBy(20_000)
        assertFalse(uvOn(c))
        assertEquals(null, c.state.value.uv)
        assertFalse("nothing sent", logged(c, DriveController.UV_PATH))
        assertTrue(log(c), logged(c, "cancelled before the lamps came on"))
    }

    @Test
    fun startNowSkipsTheCountdown() = runTest {
        val c = ready()
        c.startDisinfectionNow() // no countdown running: does nothing
        assertEquals(null, c.state.value.uv)

        c.setUvSettings(UvSettings(minutes = 1, delaySeconds = 60))
        c.startDisinfection()
        advanceTimeBy(2_000)
        assertFalse(uvOn(c))
        c.startDisinfectionNow()
        val took = waitFor(c) { it.uv is Uv.On }
        assertTrue("took $took ms", took < 1_000)
        assertTrue(uvOn(c))
        assertTrue(log(c), logged(c, "countdown skipped"))

        val ran = waitFor(c, maxMs = 70_000) { it.uv == null } // the full time, from START NOW
        assertTrue("ran $ran ms", ran in 59_000..61_000)
        waitFor(c) { !it.lampsOffOwed }
        assertFalse(uvOn(c))
    }

    @Test
    fun stopSwitchesTheLampsOff() = runTest {
        val c = lampsOn()
        c.stop()
        waitFor(c) { !it.lampsOffOwed }
        assertFalse(uvOn(c))
        assertEquals(null, c.state.value.uv)
        assertTrue(log(c), logged(c, "STOP pressed. UV lamps off"))
    }

    @Test
    fun stopSwitchesOffLampsThisAppDidNotSwitchOn() = runTest {
        val c = ready()
        c.demoRobot.handle("POST", DriveController.UV_PATH, """{"type":"24v","operation":"1"}""") // the robot's own app
        assertTrue(uvOn(c))
        c.stop()
        advanceTimeBy(2_000)
        assertFalse(uvOn(c))
    }

    @Test
    fun stopWhileOnIsInFlightStillEndsWithTheLampsOff() = runTest {
        val c = ready()
        c.startDisinfection()
        c.startDisinfectionNow()
        assertTrue(c.state.value.uv is Uv.Starting)
        c.stop() // before the robot has answered the ON
        advanceTimeBy(2_000)
        assertFalse("OFF must not overtake ON", uvOn(c))
        assertEquals(null, c.state.value.uv)
        assertFalse(c.state.value.lampsOffOwed)
    }

    @Test
    fun leavingTheAppSwitchesTheLampsOff() = runTest {
        val c = lampsOn()
        c.pause()
        advanceTimeBy(2_000)
        assertFalse(uvOn(c))
        assertTrue(log(c), logged(c, "app left"))
    }

    @Test
    fun estopEndsTheRun() = runTest {
        val c = lampsOn()
        c.demoToggleEstop()
        waitFor(c) { it.uv == null && !it.lampsOffOwed }
        assertFalse(uvOn(c))
        assertTrue(log(c), logged(c, "e-stop pressed"))
    }

    @Test
    fun robotMovingEndsTheRun() = runTest {
        val c = lampsOn()
        c.demoChargeOnItsOwn()
        waitFor(c) { it.uv == null && !it.lampsOffOwed }
        assertFalse(uvOn(c))
        assertTrue(log(c), logged(c, "the robot moved"))
    }

    @Test
    fun offIsRetriedUntilTheRobotAcceptsIt() = runTest {
        val c = lampsOn()
        c.demoToggleWifi()
        waitFor(c) { it.uv == null }
        assertTrue(log(c), logged(c, "lost connection"))
        assertTrue("owed while unreachable", c.state.value.lampsOffOwed)
        assertTrue("the robot never heard OFF", uvOn(c))
        advanceTimeBy(5_000)
        assertTrue(c.state.value.lampsOffOwed)
        assertEquals(1, c.state.value.log.count { "THE LAMPS MAY STILL BE ON" in it })

        c.demoToggleWifi()
        waitFor(c) { !it.lampsOffOwed }
        assertFalse(uvOn(c))
        assertTrue(log(c), logged(c, "robot accepted OFF"))
    }

    @Test
    fun noModeSwitchWhileTheLampsMayBeOn() = runTest {
        val c = lampsOn()
        assertFalse(c.setDemo(false))
        assertTrue(c.state.value.demo)
        assertTrue(logged(c, "Switch the UV lamps off before changing robot"))
    }

    @Test
    fun arrivingAtAPointNamesTheRun() = runTest {
        val (c, points) = withPoints()
        assertEquals("starts in the room centre", null, c.state.value.atPoint)
        c.goTo(points[0])
        waitFor(c, maxMs = 60_000) { it.phase == StepPhase.Idle }
        assertEquals(log(c), points[0], c.state.value.atPoint)

        c.setUvSettings(UvSettings(minutes = 1))
        c.startDisinfection()
        c.startDisinfectionNow()
        assertEquals("Bed 1", c.state.value.uv?.point)
        waitFor(c) { it.uv is Uv.On }
        assertTrue(log(c), logged(c, "UV lamps ON for 1 min at Bed 1"))
    }

    // ------------------------------------------------------------------ hold-to-drive

    private fun vxNow(c: DriveController) =
        Regex("\"vx\":(-?[0-9.]+)").find(c.demoRobot.handle("GET", "/reeman/speed", null)!!.body)!!.groupValues[1].toDouble()

    /** Keeps [move] held, the way the screen reports it, for [ms]; returns the robot's speed every 100 ms. */
    private fun TestScope.keepHolding(c: DriveController, move: Move, ms: Long): List<Double> {
        val speeds = mutableListOf<Double>()
        repeat((ms / DriveController.HOLD_ALIVE_MS).toInt()) {
            advanceTimeBy(DriveController.HOLD_ALIVE_MS)
            c.holdAlive(move)
            speeds += vxNow(c)
        }
        return speeds
    }

    /** The fastest the speed fell (or, [up], rose) over any 0.2 s, in m/s² (0.2 s, so encoder noise doesn't count). */
    private fun hardest(speeds: List<Double>, up: Boolean = false) =
        speeds.windowed(3).maxOf { (if (up) it[2] - it[0] else it[0] - it[2]) / 0.2 }

    @Test
    fun holdRampsUpDrivesThenEasesToAStopWhenLetGo() = runTest {
        val c = ready()
        c.hold(Move.FORWARD)
        assertEquals(Move.FORWARD, c.state.value.holding)
        val speeds = keepHolding(c, Move.FORWARD, 3000).toMutableList()
        assertTrue("moving within 0.5 s: $speeds", speeds[4] > 0.05)
        assertTrue("at full speed within ~1 s: $speeds", speeds.drop(12).all { it > 0.28 })
        val x = robotX(c)

        c.release(Move.FORWARD)
        assertTrue(c.state.value.phase is StepPhase.Stepping)
        assertFalse("locked until still", c.state.value.canDrive)
        val released = speeds.size
        while (c.state.value.phase != StepPhase.Idle) {
            advanceTimeBy(100)
            speeds += vxNow(c)
        }
        assertTrue(log(c), logged(c, "Forward (held): let go after 3.0 s"))
        assertTrue(log(c), logged(c, "Forward (held): done"))
        assertTrue("stopped within ~1.1 s: $speeds", speeds.drop(released + 11).all { abs(it) < 0.02 })
        val ranOn = robotX(c) - x
        assertTrue("ran on $ranOn m", ranOn in 0.12..0.25)
        // Never a jump: the speed only ever changed at the app's ramp (0.4 m/s²), both ways.
        assertTrue("braked at ${hardest(speeds)} m/s²", hardest(speeds) < 0.5)
        assertTrue("sped up at ${hardest(speeds, up = true)} m/s²", hardest(speeds, up = true) < 0.5)
        assertFalse("letting go is not a STOP", logged(c, "STOP"))
        assertTrue(c.state.value.canDrive)
    }

    @Test
    fun aTapBarelyMovesTheRobot() = runTest {
        val c = ready()
        c.hold(Move.FORWARD)
        advanceTimeBy(80)
        c.release(Move.FORWARD)
        waitFor(c) { it.phase == StepPhase.Idle }
        assertTrue("moved ${robotX(c)} m", robotX(c) < 0.03)
    }

    @Test
    fun holdingTurnsAndBacksUp() = runTest {
        val c = ready()
        c.hold(Move.LEFT)
        keepHolding(c, Move.LEFT, 2000)
        assertEquals(DriveController.TURN_SPEED, c.state.value.vth, 0.02)
        c.release(Move.LEFT)
        waitFor(c) { it.phase == StepPhase.Idle }
        c.hold(Move.BACK)
        keepHolding(c, Move.BACK, 2000)
        assertEquals(-DriveController.BACKWARD_SPEED, c.state.value.vx, 0.02)
        c.release(Move.BACK)
        waitFor(c) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "Back (held): done"))
    }

    @Test
    fun aButtonThatStopsReportingCountsAsLetGo() = runTest {
        val c = ready()
        c.hold(Move.FORWARD) // and then nothing: the screen froze, say
        advanceTimeBy(600)
        assertTrue(log(c), logged(c, "lost track of the button"))
        assertTrue(c.state.value.phase is StepPhase.Stepping)
        waitFor(c) { it.phase == StepPhase.Idle }
        assertTrue("0.4 s of ramp up, then down: ${robotX(c)} m", robotX(c) < 0.12)
    }

    @Test
    fun onlyTheHeldArrowCounts() = runTest {
        val c = ready()
        c.hold(Move.FORWARD)
        c.hold(Move.LEFT) // a second finger
        c.release(Move.LEFT)
        c.holdAlive(Move.LEFT)
        assertEquals(Move.FORWARD, c.state.value.holding)
        c.release(Move.FORWARD)
        assertEquals(null, c.state.value.holding)
    }

    @Test
    fun stopWhileHeldStopsForGoodEvenWithTheFingerStillDown() = runTest {
        val c = ready()
        c.hold(Move.FORWARD)
        keepHolding(c, Move.FORWARD, 1500)
        c.stop()
        assertTrue(c.state.value.phase is StepPhase.Stopping)
        keepHolding(c, Move.FORWARD, 4000) // still pressed: must not drive again
        waitFor(c) { it.phase == StepPhase.Idle }
        assertTrue(log(c), logged(c, "Forward (held): stopped"))
        assertEquals("one press, one drive", 1, c.state.value.log.count { "driving at" in it })
        val x = robotX(c)
        keepHolding(c, Move.FORWARD, 2000)
        assertEquals("still stopped", x, robotX(c), 0.001)
        c.release(Move.FORWARD)
    }

    @Test
    fun holdEndsWhenTheRobotStopsByItself() = runTest {
        val c = ready()
        c.hold(Move.FORWARD) // towards the east wall, ~2.2 m ahead
        var held = 0L
        while (c.state.value.holding != null) {
            check(held < 15_000) { log(c) }
            keepHolding(c, Move.FORWARD, 500)
            held += 500
        }
        assertTrue(log(c), logged(c, "the robot stopped while held. Obstacle in the way?"))
        keepHolding(c, Move.FORWARD, 3000) // still pressed: no more pushing into the wall
        assertEquals(StepPhase.Idle, c.state.value.phase)
        assertEquals("not moving", 0.0, vxNow(c), 0.01)
        c.release(Move.FORWARD)
    }

    @Test
    fun wifiLostWhileHeldTheRobotsWatchdogStopsIt() = runTest {
        val c = ready()
        c.hold(Move.FORWARD)
        keepHolding(c, Move.FORWARD, 2000)
        val x = robotX(c)
        c.demoToggleWifi()
        repeat(60) { // 6 s, finger still down; nothing reaches the robot
            advanceTimeBy(100)
            c.holdAlive(Move.FORWARD)
        }
        assertEquals(null, c.state.value.holding)
        assertTrue(log(c), logged(c, "Slowing to a stop; let go and press again"))
        c.demoToggleWifi()
        // The known weak spot: ~1.2 s more at full speed, then the watchdog's hard stop.
        val ranOn = robotX(c) - x
        assertTrue("ran on $ranOn m", ranOn <= 0.3 * 1.4 + 0.05)
        keepHolding(c, Move.FORWARD, 2000)
        assertEquals("doesn't set off again when Wi-Fi comes back", 0.0, vxNow(c), 0.01)
        c.release(Move.FORWARD)
    }

    @Test
    fun eStopWhileHeldEndsTheHold() = runTest {
        val c = ready()
        c.hold(Move.FORWARD)
        keepHolding(c, Move.FORWARD, 1000)
        c.demoToggleEstop()
        keepHolding(c, Move.FORWARD, 2000)
        assertEquals(null, c.state.value.holding)
        assertTrue(log(c), logged(c, "Forward (held): e-stop pressed"))
    }

    @Test
    fun pressingAgainWhileSlowingCarriesOnFromTheCurrentSpeed() = runTest {
        val c = ready()
        c.hold(Move.FORWARD)
        keepHolding(c, Move.FORWARD, 2000)
        c.release(Move.FORWARD)
        advanceTimeBy(300)
        assertTrue(c.state.value.canHold(Move.FORWARD))
        c.hold(Move.FORWARD)
        assertTrue(log(c), logged(c, "pressed while slowing"))
        val speeds = keepHolding(c, Move.FORWARD, 1500)
        assertTrue("no dip: $speeds", speeds.all { it > 0.12 })
        c.release(Move.FORWARD)
    }

    @Test
    fun forwardThenBackRampsThroughZero() = runTest {
        val c = ready()
        c.hold(Move.FORWARD)
        val speeds = keepHolding(c, Move.FORWARD, 2000).toMutableList()
        c.release(Move.FORWARD)
        advanceTimeBy(100)
        c.hold(Move.BACK)
        speeds += keepHolding(c, Move.BACK, 2500)
        assertEquals(-DriveController.BACKWARD_SPEED, speeds.last(), 0.02)
        assertTrue("braked at ${hardest(speeds)} m/s²", hardest(speeds) < 0.5)
        c.release(Move.BACK)
    }

    @Test
    fun oneHoldLastsThirtySecondsAtMost() = runTest {
        val c = ready()
        c.hold(Move.LEFT) // turning on the spot, so no wall ends it first
        keepHolding(c, Move.LEFT, DriveController.HOLD_MAX_MS + 1000)
        assertEquals(null, c.state.value.holding)
        assertTrue(log(c), logged(c, "Left (held): held for 30 s"))
        c.release(Move.LEFT)
    }

    @Test
    fun stillMovingLongAfterLetGoSendsStop() = runTest {
        val c = ready()
        c.hold(Move.FORWARD)
        keepHolding(c, Move.FORWARD, 1000)
        c.release(Move.FORWARD)
        advanceTimeBy(1200) // the speed has ramped down; the lock waits for stillness
        // A drive command reaching the robot late, say: it runs on.
        c.demoRobot.handle("POST", "/cmd/move", """{"distance":300,"direction":1,"speed":0.30}""")
        waitFor(c) { it.phase is StepPhase.Stopping }
        assertTrue(log(c), logged(c, "still moving 5 s after it was let go"))
        assertTrue(log(c), logged(c, "STOP sent by the app"))
        waitFor(c) { it.phase == StepPhase.Idle }
        assertTrue("stopped short of 3 m: ${robotX(c)}", robotX(c) < 2.0)
    }

    @Test
    fun uvSettingsAreKeptInRange() = runTest {
        val c = controller()
        c.setUvSettings(UvSettings(minutes = 0, delaySeconds = 0)) // say, 0 s saved by an older build
        assertEquals(UvSettings(1, DriveController.MIN_UV_DELAY_S), c.state.value.uvSettings)
        c.setUvSettings(UvSettings(minutes = 999, delaySeconds = 999))
        assertEquals(UvSettings(DriveController.MAX_UV_MINUTES, DriveController.MAX_UV_DELAY_S), c.state.value.uvSettings)
    }
}
