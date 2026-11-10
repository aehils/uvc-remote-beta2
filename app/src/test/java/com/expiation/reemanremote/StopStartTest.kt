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
 * The failure of 2026-11-10: phone-run routines ended at their first point without lighting. The
 * robot was still settling after its trip, the lamp start was refused or cancelled, and one failed
 * start ended the whole routine. And STOP / OFF waited behind a slow lamp ON. DEMO robot, virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopStartTest {

    private fun TestScope.ready(): DriveController {
        val fake = FakeRobot({ testScheduler.currentTime * 1_000_000 }, Random(1), realistic = true, withAgent = true)
        val c = DriveController(backgroundScope, RobotApi("192.0.2.1"), fake, demo = true, now = { testScheduler.currentTime }, phoneId = "phone-test")
        c.resume()
        advanceTimeBy(2000)
        c.loadPoints()
        waitFor(c) { it.points is Points.Loaded }
        return c
    }

    private fun TestScope.waitFor(c: DriveController, maxMs: Long = 120_000, cond: (RemoteState) -> Boolean) {
        val start = currentTime
        while (!cond(c.state.value)) {
            check(currentTime - start < maxMs) { "timed out\n" + log(c) }
            advanceTimeBy(50)
        }
    }

    private fun log(c: DriveController) = c.state.value.log.reversed().joinToString("\n")
    private fun agent(c: DriveController) = c.demoRobot.agent!!
    private fun TestScope.ns(ms: Long) = (currentTime + ms) * 1_000_000

    private fun TestScope.oneStop(c: DriveController): String {
        val a = (c.state.value.points as Points.Loaded).list.first().name
        c.saveTask(DisinfectionTask("", "Ward", listOf(TaskStop(a, 1)), delaySeconds = 15, whenDone = WhenDone.STAY))
        c.startTask(c.state.value.tasks.single().id)
        return a
    }

    @Test
    fun aRobotSettlingAfterItsTripStillGetsItsLamps() = runTest {
        val c = ready()
        oneStop(c)
        waitFor(c) { it.taskRun?.stage == TaskStage.Travelling }
        waitFor(c) { it.preparing }
        assertEquals("Preparing task", com.expiation.reemanremote.ui.rightNow(c.state.value).headline)
        c.demoRobot.twitchUntil = ns(4_000) // turns a little on the spot for 4 s
        waitFor(c, maxMs = 60_000) { it.uv is Uv.On || it.taskRun == null }
        assertTrue(log(c), c.state.value.uv is Uv.On)
        waitFor(c, maxMs = 120_000) { it.taskRun == null }
        assertTrue(log(c), c.state.value.taskReport!!.good)
    }

    @Test
    fun aTwitchInTheCountdownDoesNotEndTheRun() = runTest {
        val c = ready()
        oneStop(c)
        waitFor(c, maxMs = 60_000) { it.uv is Uv.Countdown }
        c.demoRobot.twitchUntil = ns(1_000)
        waitFor(c, maxMs = 60_000) { it.uv is Uv.On || it.taskRun == null }
        assertTrue(log(c), c.state.value.uv is Uv.On)
    }

    @Test
    fun aRefusedLampStartIsTriedAgainNotTheEndOfTheRoutine() = runTest {
        val c = ready()
        agent(c).refuseLampOns = 1
        oneStop(c)
        waitFor(c, maxMs = 90_000) { it.uv is Uv.On || it.taskRun == null }
        assertTrue(log(c), c.state.value.uv is Uv.On)
        assertTrue(log(c), "UV didn't start" in log(c))
    }

    @Test
    fun aStopThatNeverLightsIsLeftOutAndReported() = runTest {
        val c = ready()
        agent(c).refuseLampOns = 1_000
        val a = oneStop(c)
        waitFor(c, maxMs = 200_000) { it.taskRun == null }
        val r = c.state.value.taskReport!!
        assertEquals(log(c), listOf(a), r.results.map { it.point })
        assertFalse(r.good)
        assertFalse(c.demoRobot.isUvOn)
    }

    @Test
    fun offIsNotHeldUpBehindASlowLampOn() = runTest {
        val c = ready()
        waitFor(c) { it.canDisinfect }
        agent(c).lampOnDelayMs = 15_000
        c.setUvSettings(UvSettings(minutes = 1, delaySeconds = 15))
        c.startDisinfection()
        waitFor(c) { it.uv is Uv.Starting }
        advanceTimeBy(500)
        c.stopDisinfection()
        val asked = currentTime
        waitFor(c, maxMs = 20_000) { !it.lampsOffOwed }
        assertTrue("OFF settled in ${currentTime - asked} ms\n" + log(c), currentTime - asked < 3_000)
        advanceTimeBy(30_000)
        assertFalse("a late ON never lights after OFF\n" + log(c), c.demoRobot.isUvOn)
    }
}
