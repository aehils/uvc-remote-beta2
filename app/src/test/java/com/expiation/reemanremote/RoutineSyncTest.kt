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
 * This phone's routines go to the Robot Controller (`POST /v1/routines/replace`) when it connects
 * and after each change, so the robot's own screen can start them; never just because the lists
 * differ. Against the DEMO robot's in-app fake agent, in virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoutineSyncTest {

    private fun TestScope.ready(): Pair<DriveController, FakeAgent> {
        val fake = FakeRobot({ testScheduler.currentTime * 1_000_000 }, Random(1), realistic = true, withAgent = true)
        val c = DriveController(backgroundScope, RobotApi("192.0.2.1"), fake, demo = true, now = { testScheduler.currentTime }, phoneId = "phone-test")
        c.resume()
        advanceTimeBy(2000)
        return c to fake.agent!!
    }

    private fun TestScope.waitFor(c: DriveController, maxMs: Long = 60_000, cond: () -> Boolean) {
        val start = currentTime
        while (!cond()) {
            check(currentTime - start < maxMs) { "timed out\n" + c.state.value.log.joinToString("\n") }
            advanceTimeBy(50)
        }
    }

    private fun sends(c: DriveController) = c.state.value.log.count { "Routines on the robot" in it }
    private val ward = DisinfectionTask("", "Ward", listOf(TaskStop("Point 1", 1)), whenDone = WhenDone.STAY)

    @Test
    fun aConnectAndEachChangeSendTheWholeListWithItsFingerprint() = runTest {
        val (c, agent) = ready()
        waitFor(c) { agent.routinesRev == RobotAgent.routinesRev(c.state.value.tasks) }
        c.saveTask(ward)
        waitFor(c) { agent.routinesCount == 1 }
        assertEquals(RobotAgent.routinesRev(c.state.value.tasks), agent.routinesRev)
        c.deleteTask(c.state.value.tasks.single().id)
        waitFor(c) { agent.routinesCount == 0 }
    }

    @Test
    fun backOnARobotThatHasThisListAlreadySendsNothing() = runTest {
        val (c, agent) = ready()
        c.saveTask(ward)
        waitFor(c) { agent.routinesCount == 1 }
        val before = sends(c)
        agent.down = true
        waitFor(c) { c.state.value.agent?.reachable == false }
        agent.down = false
        waitFor(c) { c.state.value.agent?.reachable == true }
        advanceTimeBy(5_000)
        assertEquals("the robot already had this phone's list", before, sends(c))
    }

    @Test
    fun anotherPhonesListIsNotOverwrittenWhileThisOneStaysConnected() = runTest {
        val (c, agent) = ready()
        c.saveTask(ward)
        waitFor(c) { agent.routinesCount == 1 }
        // Another phone connects and hands over its own set.
        agent.routinesCount = 3
        agent.routinesRev = "another-phone"
        advanceTimeBy(10_000)
        assertEquals(3, agent.routinesCount)
        assertEquals("another-phone", agent.routinesRev)
    }

    @Test
    fun aMapChangeOnTheRobotHoldsBackDrivingAndMapChanges() = runTest {
        val (c, _) = ready()
        val idle = c.state.value
        assertTrue(idle.canDrive)
        assertTrue(idle.canChangeMap)
        val changing = idle.copy(mapChanging = true)
        assertFalse(changing.canDrive)
        assertFalse(changing.canTravel)
        assertFalse(changing.canChangeMap)
    }
}
