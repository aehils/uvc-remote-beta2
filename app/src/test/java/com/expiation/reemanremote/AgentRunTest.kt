package com.expiation.reemanremote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * UV runs through the Robot Controller, end to end against the DEMO robot and its in-app fake
 * agent, in virtual time: the agent's countdown and timer, the lease, fail closed, person seen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentRunTest {

    private fun TestScope.ready(): DriveController {
        val fake = FakeRobot({ testScheduler.currentTime * 1_000_000 }, Random(1), realistic = true, withAgent = true)
        val c = DriveController(backgroundScope, RobotApi("192.0.2.1"), fake, demo = true, now = { testScheduler.currentTime }, phoneId = "phone-test")
        c.setUvSettings(UvSettings(minutes = 1, delaySeconds = 15))
        c.resume()
        advanceTimeBy(2000)
        assertTrue(log(c), c.state.value.canDisinfect)
        return c
    }

    private fun TestScope.waitFor(c: DriveController, maxMs: Long = 120_000, cond: (RemoteState) -> Boolean): Long {
        val start = currentTime
        while (!cond(c.state.value)) {
            check(currentTime - start < maxMs) { "timed out\n" + log(c) }
            advanceTimeBy(50)
        }
        return currentTime - start
    }

    private fun log(c: DriveController) = c.state.value.log.joinToString("\n")
    private fun logged(c: DriveController, text: String) = c.state.value.log.any { text in it }
    private fun agentState(c: DriveController) = RobotAgent.parseState(c.demoRobot.handle("GET", RobotAgent.STATE_PATH, null)!!.body)!!
    private fun lit(c: DriveController) = c.demoRobot.isUvOn

    @Test
    fun aRunCountsDownLightsAndEndsOnTheAgentsTimer() = runTest {
        val c = ready()
        assertNotNull(c.state.value.agent)
        c.startDisinfection()
        waitFor(c) { it.uv is Uv.Countdown && it.uvByAgent }
        assertFalse(lit(c))
        assertEquals("the agent's run is this phone's", "phone-test", agentState(c).controller)

        // The agent's countdown, then its timer: 15 s, then 60 s of lamps.
        val toOn = waitFor(c) { it.uv is Uv.On }
        assertTrue("countdown took $toOn ms", toOn in 13_000..16_000)
        assertTrue(lit(c))
        val left = c.state.value.uvLeftMs!!
        assertTrue("left $left", left in 55_000..60_000)

        // Longer than the agent's 5 s lease: the phone's renewals keep it going.
        val onFor = waitFor(c) { it.uv == null }
        assertTrue("on for $onFor ms", onFor in 57_000..61_000)
        assertFalse(lit(c))
        assertTrue(log(c), logged(c, "done: UV lamps off"))
        assertEquals(RobotAgent.TIME_UP, agentState(c).lastOffReason)
    }

    @Test
    fun theAgentEndsTheRunWhenThePhoneGoesQuiet() = runTest {
        val c = ready()
        c.switchLampsOn(5)
        waitFor(c) { it.uv is Uv.On }
        assertTrue(lit(c))
        // The phone's Wi-Fi goes: no state, no lease. The phone gives up first, then the agent.
        c.demoToggleWifi()
        val gone = currentTime
        waitFor(c, maxMs = 6_000) { it.uv == null }
        // What the agent did meanwhile, asked past the dropped Wi-Fi (as with curl from a laptop).
        fun behind() = RobotAgent.parseState(c.demoRobot.agent!!.handle("GET", RobotAgent.STATE_PATH, "", currentTime)!!.second)!!
        while (behind().lamp != AgentLamp.OFF) {
            assertTrue("the agent kept the lamps on", currentTime - gone < 5_500)
            advanceTimeBy(100)
        }
        assertFalse(lit(c))
        assertEquals("Lost the phone: lamps off", behind().lastOffReason)
        // Back on Wi-Fi, the phone still sends the OFF it owes: it can't know what happened meanwhile.
        c.demoToggleWifi()
        waitFor(c, maxMs = 5_000) { !it.lampsOffOwed }
    }

    @Test
    fun theUvSwitchStillCountsDownThroughTheAgent() = runTest {
        val c = ready()
        c.switchLampsOn(1)
        waitFor(c) { it.uv is Uv.Countdown && it.uvByAgent }
        val took = waitFor(c) { it.uv is Uv.On }
        assertTrue("took $took", took in 3_000..6_000)
    }

    @Test
    fun failsClosedWhenTheAgentIsDown() = runTest {
        val c = ready()
        c.loadPoints()
        waitFor(c) { it.points is Points.Loaded }
        c.demoToggleAgent()
        waitFor(c, maxMs = 6_000) { !it.canDisinfect }
        assertEquals(DriveController.CANT_REACH_AGENT, c.state.value.uvProblem)
        assertEquals(DriveController.CANT_REACH_AGENT, c.state.value.routeProblem)
        // Neither way in lights them, and nothing goes straight to the robot instead.
        c.startDisinfection()
        c.switchLampsOn(5)
        advanceTimeBy(20_000)
        assertNull(c.state.value.uv)
        assertFalse(lit(c))
        // A route test keeps its lamps off, so it needs no Robot Controller.
        assertNull(c.state.value.routeTestProblem)
        c.demoToggleAgent()
        waitFor(c, maxMs = 3_000) { it.canDisinfect }
    }

    @Test
    fun aRobotSeenWithAnAgentRefusesUvWithoutOne() = runTest {
        // Live, never connected: still remembered as a robot with a safety app.
        val c = DriveController(
            backgroundScope, RobotApi("192.0.2.1"), FakeRobot(), demo = false, now = { testScheduler.currentTime },
            robotName = "wbot11u-250101-003-007", knownAgents = mapOf("wbot11u-250101-003-007" to "192.0.2.50:8765"),
        )
        assertEquals(DriveController.CANT_REACH_AGENT, c.state.value.uvProblem)
        assertEquals("192.0.2.50:8765", c.state.value.agent?.address)
        // A robot never seen with one: as before.
        val other = DriveController(backgroundScope, RobotApi("192.0.2.1"), FakeRobot(), demo = false, now = { testScheduler.currentTime }, robotName = "x-250101-003-008")
        assertNull(other.state.value.uvProblem)
        assertNull(other.state.value.agent)
    }

    @Test
    fun stopDuringTheCountdownEndsTheAgentsRun() = runTest {
        val c = ready()
        c.startDisinfection()
        waitFor(c) { it.uv is Uv.Countdown && it.uvByAgent }
        advanceTimeBy(3_000)
        c.stop()
        advanceTimeBy(30_000)
        assertNull(c.state.value.uv)
        assertFalse(lit(c))
        assertEquals(AgentLamp.OFF, agentState(c).lamp)
    }

    @Test
    fun startNowLeavesTheAgentsCountdownAlone() = runTest {
        val c = ready()
        c.startDisinfection()
        waitFor(c) { it.uv is Uv.Countdown && it.uvByAgent }
        c.startDisinfectionNow()
        advanceTimeBy(5_000)
        assertTrue(c.state.value.uv is Uv.Countdown)
        assertFalse(lit(c))
    }

    @Test
    fun aRefusalShowsTheAgentsReasons() = runTest {
        val c = ready()
        // Pressed on the robot a moment before the phone has read it.
        c.demoRobot.setEstopPressed(true)
        c.startDisinfection()
        waitFor(c, maxMs = 3_000) { it.robotAlert != null }
        assertEquals("UV refused by the robot: Emergency stop pressed", c.state.value.robotAlert)
        assertNull(c.state.value.uv)
        assertFalse(lit(c))
    }

    @Test
    fun movementPausesTheLampsAndTheyComeBackAfterACountdown() = runTest {
        val c = ready()
        c.setUvSettings(UvSettings(minutes = 1, delaySeconds = 15))
        c.startDisinfection()
        waitFor(c) { it.uv is Uv.On }
        advanceTimeBy(10_000)
        c.demoMovementNearRobot()
        waitFor(c, maxMs = 2_000) { it.uv is Uv.PersonSeen }
        val seen = c.state.value.uv as Uv.PersonSeen
        assertFalse(lit(c))
        assertEquals("Front", RobotAgent.domes(seen.domes))
        assertTrue("left ${seen.leftMs}", seen.leftMs in 48_000..51_000)
        assertTrue(log(c), logged(c, "movement detected (Front)"))
        // A quiet 15 s, then the full countdown again, then the rest of the time.
        waitFor(c, maxMs = 20_000) { it.uv is Uv.Countdown }
        waitFor(c, maxMs = 20_000) { it.uv is Uv.On }
        assertTrue(lit(c))
        waitFor(c, maxMs = 60_000) { it.uv == null }
        assertTrue(log(c), logged(c, "done: UV lamps off"))
    }

    @Test
    fun aTaskReportCountsMovementAtEachStop() = runTest {
        val c = ready()
        c.loadPoints()
        waitFor(c) { it.points is Points.Loaded }
        val first = (c.state.value.points as Points.Loaded).list.first().name
        c.saveTask(DisinfectionTask("", "Ward", listOf(TaskStop(first, 1)), delaySeconds = 15, whenDone = WhenDone.STAY))
        c.startTask(c.state.value.tasks.single().id)
        waitFor(c) { it.uv is Uv.On }
        c.demoMovementNearRobot()
        waitFor(c, maxMs = 2_000) { it.uv is Uv.PersonSeen }
        waitFor(c, maxMs = 120_000) { it.taskReport != null }
        val r = c.state.value.taskReport!!
        assertTrue(log(c), r.good)
        assertEquals(1, r.results.single().personPauses)
        assertEquals(StopOutcome.DONE, r.results.single().outcome)
        assertTrue("uv ${r.results.single().uvMs}", r.results.single().uvMs in 59_000..61_000)
        // Kept with the report.
        assertEquals(1, TaskReport.listFromJson(TaskReport.listToJson(listOf(r))).single().results.single().personPauses)
    }

    @Test
    fun pausingATaskKeepsTheTimeTheAgentHadGiven() = runTest {
        val c = ready()
        c.loadPoints()
        waitFor(c) { it.points is Points.Loaded }
        val first = (c.state.value.points as Points.Loaded).list.first().name
        c.saveTask(DisinfectionTask("", "Ward", listOf(TaskStop(first, 1)), delaySeconds = 15, whenDone = WhenDone.STAY))
        c.startTask(c.state.value.tasks.single().id)
        waitFor(c) { it.uv is Uv.On }
        advanceTimeBy(20_000)
        c.pauseTask()
        waitFor(c, maxMs = 3_000) { it.taskRun?.stage == TaskStage.Paused }
        val run = c.state.value.taskRun!!
        assertTrue("given ${run.uvDoneMs}", run.uvDoneMs in 19_000..21_500)
        assertTrue("left ${run.uvLeftMs}", run.uvLeftMs!! in 38_500..41_000)
        advanceTimeBy(5_000)
        assertFalse(lit(c))
        assertEquals(AgentLamp.OFF, agentState(c).lamp)
    }

    @Test
    fun voiceVolumeIsKeptOnTheRobotNeverSilentAndNeverTestedOverAWarning() = runTest {
        val c = ready()
        waitFor(c) { it.agent?.status?.voiceVolume == 60 }
        c.setVoiceVolume(40)
        waitFor(c) { it.agent?.status?.voiceVolume == 40 }
        assertEquals("on the robot, not only the phone", 40, agentState(c).voiceVolume)
        c.setVoiceVolume(0)
        waitFor(c) { it.agent?.status?.voiceVolume == RobotAgent.VOICE_FLOOR }
        c.setVoiceVolume(50, thenTest = true)
        waitFor(c) { logged(c, "Voice test at 50%") }
        c.switchLampsOn(5)
        waitFor(c) { it.uv is Uv.On }
        c.setVoiceVolume(50, thenTest = true)
        waitFor(c) { logged(c, "Voice test refused: Not while the lamps are running") }
    }

    @Test
    fun pairingIsTypingTheCodeAndWrongTriesCostNothing() = runTest {
        val c = ready()
        val fake = c.demoRobot.agent!!
        fake.pairingRequired = true
        c.switchLampsOn(5)
        waitFor(c) { logged(c, "Pair this phone with the robot first") }
        assertFalse(lit(c))

        c.askPairCode()
        waitFor(c) { it.agent?.pairStep == PairStep.CodeShown }
        val code = fake.shownCode!!
        val wrong = if (code == "000000") "000001" else "000000"
        repeat(3) {
            c.sendPairCode(wrong)
            waitFor(c) { it.agent?.pairStep is PairStep.Problem }
            assertEquals("Wrong code: check the robot's screen", (c.state.value.agent!!.pairStep as PairStep.Problem).text)
        }
        assertEquals("unlimited tries: the code stands", code, fake.shownCode)
        c.sendPairCode(code)
        waitFor(c) { it.agent?.pairStep == PairStep.Paired }
        assertTrue(c.state.value.agent!!.paired)
        assertFalse(c.state.value.agent!!.needsPairing)
        assertFalse("the token is never logged", log(c).contains("demo-token"))

        c.switchLampsOn(5)
        waitFor(c) { it.uv is Uv.On }
        assertTrue(lit(c))
    }

    @Test
    fun anExpiredCodeSaysSoAndRefreshGivesANewOneAtOnce() = runTest {
        val c = ready()
        val fake = c.demoRobot.agent!!
        c.askPairCode()
        waitFor(c) { it.agent?.pairStep == PairStep.CodeShown }
        val code = fake.shownCode!!
        advanceTimeBy(5 * 60_000L + 1_000)
        c.sendPairCode(code)
        waitFor(c) { it.agent?.pairStep == PairStep.Expired }
        c.askPairCode()
        waitFor(c) { it.agent?.pairStep == PairStep.CodeShown }
        c.sendPairCode(fake.shownCode!!)
        waitFor(c) { it.agent?.pairStep == PairStep.Paired }
    }
}
