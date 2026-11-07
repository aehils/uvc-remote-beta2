package com.expiation.reemanremote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the robot safety app's replies, and finding the right one on a network. */
class RobotAgentTest {

    /** What the agent's `AgentState.toJson()` writes, mid-run. */
    private val running = """{"api":1,"t":1793449020000,"lamp":{"state":"on","leftMs":84000,"countdownMs":null,"confirmed":false,""" +
        """"lastOffReason":null,"unlocked":true},"sensor":{"mode":"on","person":false,"mask":0,"lastSeenAgoMs":null,"healthy":true},""" +
        """"robot":{"estop":false,"moving":false,"battery":62,"charging":false},"ros":{"serial":true,"hostname":"wbot11u-250101-003-007"},""" +
        """"controller":"phone-1","faults":[]}"""

    @Test
    fun readsTheLiveState() {
        val s = RobotAgent.parseState(running)!!
        assertEquals(AgentLamp.ON, s.lamp)
        assertEquals(84_000L, s.leftMs)
        assertNull(s.countdownMs)
        assertEquals("phone-1", s.controller)
        assertEquals(62, s.battery)
        assertEquals(false, s.estop)
        assertTrue(s.unlocked)
    }

    @Test
    fun readsAPersonPauseAndFaults() {
        val s = RobotAgent.parseState(
            running.replace("\"state\":\"on\"", "\"state\":\"paused_person\"").replace("\"mask\":0", "\"mask\":12")
                .replace("\"faults\":[]", "\"faults\":[\"PERSON\",\"LOCKED\"]")
        )!!
        assertEquals(AgentLamp.PERSON, s.lamp)
        assertEquals("Front, Left", RobotAgent.domes(s.mask))
        // A person pauses a run; it doesn't refuse one. The lock does.
        assertEquals(listOf("Lamps are locked on this robot"), RobotAgent.blocking(s.faults))
    }

    @Test
    fun anUnknownLampWordIsOff() {
        assertEquals(AgentLamp.OFF, RobotAgent.parseState(running.replace("\"state\":\"on\"", "\"state\":\"dimmed\""))!!.lamp)
        assertNull(RobotAgent.parseState("not json"))
    }

    @Test
    fun readsInfoAndRefusals() {
        val i = RobotAgent.parseInfo("""{"api":1,"agent":"0.1","host":null,"lampsUnlocked":false}""")!!
        assertEquals(1, i.api)
        assertNull(i.host)
        assertFalse(i.fake)
        assertEquals(
            listOf("Emergency stop pressed", "The robot is moving"),
            RobotAgent.parseReasons("""{"ok":false,"requestId":"r","reasons":["Emergency stop pressed","The robot is moving"]}"""),
        )
    }

    @Test
    fun domesNameEachBit() {
        assertEquals("Back", RobotAgent.domes(0x01))
        assertEquals("Right", RobotAgent.domes(0x02))
        assertEquals("", RobotAgent.domes(0))
        assertEquals("Front, Right, Back, Left", RobotAgent.domes(0x0F))
    }

    // ------------------------------------------------------------------ finding it

    private val robot = "wbot11u-250101-003-007"
    private fun info(host: String?, version: String = "0.1") = AgentInfo(1, version, host, true)

    @Test
    fun onlyThisRobotsAgentIsTaken() {
        assertTrue(AgentFinder.accepts(info(robot), robot, demo = false))
        assertFalse("another robot's", AgentFinder.accepts(info("wbot11u-250101-003-008"), robot, demo = false))
        assertFalse("hostname not read yet", AgentFinder.accepts(info(null), robot, demo = false))
        assertFalse("a fake, for a real robot", AgentFinder.accepts(info(robot, RobotAgent.FAKE_VERSION), robot, demo = false))
        // DEMO takes only a fake, so it can never reach a real robot's lamps.
        assertTrue(AgentFinder.accepts(info(null, RobotAgent.FAKE_VERSION), null, demo = true))
        assertFalse(AgentFinder.accepts(info(robot), robot, demo = true))
    }

    @Test
    fun triesTheLastAddressThenNsdThenTheNetwork() = runBlocking {
        val asked = mutableListOf<String>()
        val agents = mapOf("10.0.0.9:8765" to info(robot), "10.0.0.5:8765" to info("other"))
        val found = AgentFinder.find(
            robot, demo = false, last = "10.0.0.7:8765",
            nsd = { listOf(NsdHit("10.0.0.5:8765", "other"), NsdHit("10.0.0.6:8765", null)) },
            scan = { listOf("10.0.0.7:8765", "10.0.0.8:8765", "10.0.0.9:8765") },
            info = { a -> synchronized(asked) { asked += a }; agents[a] },
        )
        assertEquals("10.0.0.9:8765", found?.address)
        assertEquals("last address first", "10.0.0.7:8765", asked.first())
        assertFalse("an NSD hit naming another robot isn't asked", "10.0.0.5:8765" in asked)
        assertTrue("one with no host is", "10.0.0.6:8765" in asked)
        assertEquals("the last address isn't asked twice", 1, asked.count { it == "10.0.0.7:8765" })
    }

    @Test
    fun noHostnameNoSearch() = runBlocking {
        assertNull(AgentFinder.find(null, demo = false, last = null, nsd = { error("not asked") }, scan = { error("not asked") }, info = { error("not asked") }))
    }
}
