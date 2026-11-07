package com.expiation.reemanremote

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * Not part of the normal test run: the phone's side against the agent repository's fake agent
 * on a laptop, over real HTTP, in real time (about two minutes). The DEMO robot drives; the
 * lamps go through the fake agent.
 *
 *   (agent repo)  ./gradlew -q :core:fakeAgent
 *   (this repo)   AGENT_URL=<laptop's LAN address>:8765 AGENT_PID=<its pid> ./gradlew testDebugUnitTest --tests '*FakeAgentCheck'
 *
 * AGENT_PID, the fake agent's process, is stopped at the end to check that UV is then refused.
 */
class FakeAgentCheck {

    private val address = System.getenv("AGENT_URL")
    private val thread = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private fun say(line: String) = println("[check] $line")

    private fun agentState(): AgentStatus? = runBlocking {
        RobotApi(address).get(RobotAgent.STATE_PATH).takeIf { it.ok }?.let { RobotAgent.parseState(it.body) }
    }

    private fun phone(scope: CoroutineScope) = DriveController(
        scope, RobotApi("192.0.2.1"), FakeRobot(), demo = true, phoneId = "phone-check",
        agentSearch = { host, demo, last ->
            AgentFinder.find(host, demo, last, nsd = { emptyList() }, scan = { listOf(address) }, info = { a ->
                RobotApi(a).probe(RobotAgent.INFO_PATH).takeIf { it.ok }?.let { RobotAgent.parseInfo(it.body) }
            })
        },
    )

    private suspend fun until(c: DriveController, maxMs: Long, what: String, cond: (RemoteState) -> Boolean): Long {
        val start = System.nanoTime()
        while (!withContext(thread) { cond(c.state.value) }) {
            check((System.nanoTime() - start) / 1_000_000 < maxMs) { "timed out waiting for: $what\n" + c.state.value.log.joinToString("\n") }
            delay(50)
        }
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun againstTheFakeAgent() = runBlocking {
        assumeTrue("AGENT_URL not set", address != null)
        val scope = CoroutineScope(SupervisorJob() + thread)
        val c = phone(scope)
        withContext(thread) { c.resume() }
        until(c, 10_000, "the fake agent found and ready") { it.agent?.reachable == true && it.canDisinfect }
        say("found the fake agent at ${c.state.value.agent?.address}, version ${c.state.value.agent?.info?.version}")

        // 1. A phone run counts down, lights, and ends on the agent's timer.
        withContext(thread) { c.setUvSettings(UvSettings(minutes = 1, delaySeconds = 15)); c.startDisinfection() }
        until(c, 3_000, "accepted") { it.uv is Uv.Countdown && it.uvByAgent }
        val countdown = until(c, 20_000, "lamps on") { it.uv is Uv.On }
        say("countdown ${countdown} ms, then the agent says: ${agentState()?.lamp}, ${agentState()?.leftMs} ms left")
        assertEquals(AgentLamp.ON, agentState()?.lamp)
        val on = until(c, 70_000, "the agent's timer ends it") { it.uv == null }
        val after = agentState()!!
        say("on for $on ms; agent: ${after.lamp}, lastOffReason \"${after.lastOffReason}\"")
        assertEquals(AgentLamp.OFF, after.lamp)
        assertEquals(RobotAgent.TIME_UP, after.lastOffReason)
        assertTrue(c.state.value.log.any { "done: UV lamps off" in it })

        // 2. The phone goes away mid-run: no more lease, and the agent ends it within 5 s.
        withContext(thread) { c.switchLampsOn(5) }
        until(c, 10_000, "lamps on again") { it.uv is Uv.On }
        val gone = System.nanoTime()
        scope.cancel() // the phone stops talking, as with its Wi-Fi off
        var s = agentState()!!
        while (s.lamp != AgentLamp.OFF) {
            check((System.nanoTime() - gone) / 1_000_000 < 8_000) { "the agent kept the lamps on" }
            delay(100)
            s = agentState()!!
        }
        val ended = (System.nanoTime() - gone) / 1_000_000
        say("phone gone: the agent ended the run after $ended ms, \"${s.lastOffReason}\"")
        assertEquals("Lost the phone: lamps off", s.lastOffReason)
        assertTrue("ended after $ended ms", ended <= 6_000)

        // 3. A robot seen with an agent refuses UV once the agent is gone.
        val scope2 = CoroutineScope(SupervisorJob() + thread)
        val c2 = phone(scope2)
        withContext(thread) { c2.resume() }
        until(c2, 10_000, "the fake agent found again") { it.agent?.reachable == true && it.canDisinfect }
        val pid = System.getenv("AGENT_PID")?.toLongOrNull()
        assumeTrue("AGENT_PID not set: not stopping the fake agent", pid != null)
        Runtime.getRuntime().exec(arrayOf("kill", pid.toString())).waitFor()
        val refused = until(c2, 8_000, "UV refused") { it.uvProblem != null }
        say("fake agent stopped: after $refused ms the phone says \"${c2.state.value.uvProblem}\"")
        assertEquals(DriveController.CANT_REACH_AGENT, c2.state.value.uvProblem)
        withContext(thread) { c2.startDisinfection(); c2.switchLampsOn(5) }
        delay(2_000)
        withContext(thread) {
            assertNull(c2.state.value.uv)
            assertTrue("DEMO robot lamps untouched", !c2.demoRobot.isUvOn)
        }
        scope2.cancel()
    }
}
