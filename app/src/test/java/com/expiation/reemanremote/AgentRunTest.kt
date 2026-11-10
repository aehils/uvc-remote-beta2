package com.expiation.reemanremote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.expiation.reemanremote.ui.rightNow
import kotlinx.coroutines.delay
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
        assertEquals(DriveController.CONNECTING_AGENT, c.state.value.uvProblem)
        // Each read waits out its timeout, so giving up can take a little longer than the connecting time.
        waitFor(c, maxMs = DriveController.AGENT_CONNECT_MS + 5_000) { it.uvProblem == DriveController.CANT_REACH_AGENT }
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

    // ---- E1: never light without the agent, on any robot, the first run after an install included.

    /**
     * Live (not DEMO) against [robot] as the navigation computer. The Robot Controller is found by
     * [search] and reached at its address through [agents]; any other address doesn't answer.
     */
    private fun TestScope.live(
        robot: FakeRobot,
        agents: Map<String, FakeRobot> = emptyMap(),
        known: Map<String, String> = emptyMap(),
        search: suspend (String?, Boolean, String?) -> FoundAgent?,
    ): DriveController {
        val c = DriveController(
            backgroundScope, RobotApi.demo(robot), FakeRobot(), demo = false, now = { testScheduler.currentTime },
            phoneId = "phone-test", agentSearch = search, knownAgents = known,
            agentApi = { a -> RobotApi.demo(agents[a] ?: FakeRobot().apply { isOffline = true }) },
        )
        c.setUvSettings(UvSettings(minutes = 1, delaySeconds = 15))
        c.resume()
        return c
    }

    private fun clock(scope: TestScope): () -> Long = { scope.testScheduler.currentTime * 1_000_000 }
    private fun infoOf(robot: FakeRobot) = RobotAgent.parseInfo(robot.handle("GET", RobotAgent.INFO_PATH, null)!!.body)!!

    /** Runs [ms] on, failing at once if [robot]'s lamps ever come on. */
    private fun TestScope.neverLit(robot: FakeRobot, ms: Long) {
        val until = currentTime + ms
        while (currentTime < until) {
            assertFalse("lamps lit", robot.isUvOn)
            advanceTimeBy(50)
        }
    }

    @Test
    fun aFreshInstallWithNoSafetyAppNeverLightsAndOffersRetry() = runTest {
        val robot = FakeRobot(clock(this), Random(1), realistic = true) // no Robot Controller on it
        var searches = 0
        val c = live(robot) { _, _, _ -> searches++; delay(2_000); null }
        waitFor(c, maxMs = 5_000) { it.connected && it.agent != null }
        // Searching: "Connecting", and no way to start UV.
        assertTrue(c.state.value.agent!!.connecting)
        assertEquals(DriveController.CONNECTING_AGENT, c.state.value.uvProblem)
        assertEquals(DriveController.CONNECTING_AGENT, rightNow(c.state.value).headline)
        c.startDisinfection()
        c.switchLampsOn(5)
        assertNull(c.state.value.uv)
        // The search comes back empty: can't reach it, Retry.
        waitFor(c) { it.uvProblem == DriveController.CANT_REACH_AGENT }
        assertFalse(c.state.value.canDisinfect)
        assertTrue(rightNow(c.state.value).retry)
        c.startDisinfection()
        c.switchLampsOn(5)
        neverLit(robot, 30_000)
        assertNull(c.state.value.uv)
        assertFalse("no ON from the phone", logged(c, "UV lamps ON"))
        // Retry: connecting again, and a new search at once.
        val before = searches
        c.retryAgent()
        assertTrue(c.state.value.agent!!.connecting)
        advanceTimeBy(500)
        assertEquals(before + 1, searches)
    }

    @Test
    fun aSlowSearchShowsConnectingUntilTheSafetyAppIsFound() = runTest {
        val robot = FakeRobot(clock(this), Random(1), realistic = true, withAgent = true)
        val at = "192.0.2.60:8765" // TEST-NET: only ever reached through the fake
        val c = live(robot, agents = mapOf(at to robot)) { _, _, _ -> delay(30_000); FoundAgent(at, infoOf(robot)) }
        waitFor(c, maxMs = 5_000) { it.connected && it.agent != null }
        // Longer than the connecting time on its own: a search under way keeps it "Connecting".
        advanceTimeBy(DriveController.AGENT_CONNECT_MS + 5_000)
        assertTrue(c.state.value.agent!!.connecting)
        assertEquals(DriveController.CONNECTING_AGENT, c.state.value.uvProblem)
        c.startDisinfection()
        neverLit(robot, 2_000)
        assertNull(c.state.value.uv)
        waitFor(c, maxMs = 30_000) { it.canDisinfect }
        assertEquals(at, c.state.value.agent!!.address)
        c.startDisinfection()
        waitFor(c) { it.uv is Uv.Countdown && it.uvByAgent }
        assertEquals("phone-test", RobotAgent.parseState(robot.handle("GET", RobotAgent.STATE_PATH, null)!!.body)!!.controller)
    }

    @Test
    fun aSafetyAppAtANewAddressIsFoundAndUsed() = runTest {
        val robot = FakeRobot(clock(this), Random(1), realistic = true, withAgent = true)
        val old = "192.0.2.60:8765"
        val moved = "192.0.2.77:8765"
        var askedLast: String? = null
        val c = live(robot, agents = mapOf(moved to robot), known = mapOf(FakeRobot.HOSTNAME to old)) { _, _, last ->
            askedLast = last
            delay(1_000)
            FoundAgent(moved, infoOf(robot))
        }
        waitFor(c, maxMs = 10_000) { it.canDisinfect }
        assertEquals("the search tried the old address first", old, askedLast)
        assertEquals(moved, c.state.value.agent!!.address)
        c.switchLampsOn(1)
        waitFor(c) { it.uv is Uv.On }
        assertTrue(robot.isUvOn)
    }

    @Test
    fun losingTheSafetyAppMidRunStillGetsOffToTheRobot() = runTest {
        val c = ready()
        c.switchLampsOn(5)
        waitFor(c) { it.uv is Uv.On }
        assertTrue(lit(c))
        c.demoToggleAgent() // it stops answering, lamps on
        waitFor(c, maxMs = 6_000) { it.uv == null && !it.lampsOffOwed }
        assertFalse("OFF reached the navigation computer", lit(c))
        assertTrue(log(c), logged(c, "lost the Robot Controller"))
        assertTrue(log(c), logged(c, "robot accepted OFF (Robot Controller"))
        // Still down: lamps switched on some other way (the robot's own app) go off on STOP.
        c.demoRobot.handle("POST", DriveController.UV_PATH, """{"type":"24v","operation":"1"}""")
        assertTrue(lit(c))
        c.stop()
        advanceTimeBy(3_000)
        assertFalse(lit(c))
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
