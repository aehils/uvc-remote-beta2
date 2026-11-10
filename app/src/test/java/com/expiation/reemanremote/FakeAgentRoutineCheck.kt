package com.expiation.reemanremote

import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The phone's side of routines run on the robot (Phase 2), against the agent's own fake on a
 * laptop: the start request the phone builds, and the `task` and `lastReport` it reads back.
 * Skipped unless AGENT_URL is set. Run the fake with its simulated map (`AGENT_NAV=1`), then:
 *   AGENT_URL=<laptop's LAN address>:8765 ./gradlew testDebugUnitTest --tests '*FakeAgentRoutineCheck' -i
 * A route test (lamps off), so nothing waits on lamp time. Never point it at a real robot.
 */
class FakeAgentRoutineCheck {

    private val address: String? = System.getenv("AGENT_URL")

    private fun say(line: String) = println("[routine check] $line")

    /** The phone's list, in the phone's own JSON, is what the robot keeps for its screen, with its fingerprint. */
    @Test
    fun thePhonesRoutinesReachTheRobotAndItsFingerprintShows() = runBlocking {
        assumeTrue("AGENT_URL not set", address != null)
        val api = RobotApi(address!!)
        val tasks = listOf(
            DisinfectionTask("check-a", "Ward A", listOf(TaskStop("Bed 1", 2), TaskStop("Bed 2", 1)), delaySeconds = 30, rounds = 2, whenDone = WhenDone.STAY),
            DisinfectionTask("check-b", "Ward B", listOf(TaskStop("Bed 2", 1))),
        )
        val rev = RobotAgent.routinesRev(tasks)
        val r = api.post(RobotAgent.ROUTINES_REPLACE_PATH, RobotAgent.routinesReplaceJson(tasks, rev))
        say("replace: ${r.httpCode} ${r.body}")
        assertTrue(r.body, r.ok)
        val s = RobotAgent.parseState(api.get(RobotAgent.STATE_PATH).body)!!
        assertTrue(s.routinesSync)
        assertEquals(rev, s.routinesRev)
        val back = DisinfectionTask.listFromJson(org.json.JSONObject(api.get("/v1/routines").body).getJSONArray("routines").toString())
        assertEquals(tasks.map { it.coerced() }, back)
        assertTrue(api.post(RobotAgent.ROUTINES_REPLACE_PATH, RobotAgent.routinesReplaceJson(emptyList(), RobotAgent.routinesRev(emptyList()))).ok)
    }

    @Test
    fun aRoutineStartsOnTheRobotIsFollowedAndEndsWithAReport() = runBlocking {
        assumeTrue("AGENT_URL not set", address != null)
        val api = RobotApi(address!!)
        suspend fun state(): AgentStatus = RobotAgent.parseState(api.get(RobotAgent.STATE_PATH).body)!!

        val before = state()
        assertEquals("start the fake with AGENT_NAV=1", true, before.routinesUnlocked)
        assertTrue(before.phoneAwayCarriesOn)

        // The fake's simulated map, as the phone would send its own.
        val points = listOf(Waypoint("Bed 1", "normal", Pose(3.0, 2.0, 0.0)), Waypoint("Bed 2", "normal", Pose(3.0, 4.0, 0.0)))
        val task = DisinfectionTask.routeTest(points)
        val r = api.post(RobotAgent.TASK_START_PATH, RobotAgent.taskStartJson(task, points, "charging_pile", "check-${System.currentTimeMillis()}", "phone-check"))
        say("start: ${r.httpCode} ${r.body}")
        assertTrue(r.body, r.ok)

        var seen: AgentTask? = null
        for (i in 0 until 50) {
            api.post(RobotAgent.LEASE_PATH, RobotAgent.controllerJson("phone-check"))
            seen = state().task
            if (seen != null) break
            delay(200)
        }
        assertNotNull("the robot reports the routine", seen)
        say("task: $seen")
        assertEquals(task.name, seen!!.name)
        assertEquals("phone-check", seen.controller)
        assertEquals(2, seen.total)
        assertFalse(seen.phoneAway)
        assertTrue(seen.stage, seen.stage in setOf("checking", "travelling", "retrying", "disinfecting", "lamps_off", "docking", "paused"))

        // STOP, as END on the phone sends it: the routine ends and its report reads back.
        assertTrue(api.post(RobotAgent.STOP_PATH, RobotAgent.reasonJson("routine check")).ok)
        var after = state()
        for (i in 0 until 50) {
            if (after.task == null) break
            delay(200)
            after = state()
        }
        assertEquals(null, after.task)
        val report = after.lastReport
        assertNotNull("lastReport parses", report)
        say("report: ${report!!.title}: ${report.detail}")
        assertEquals(task.name, report.task.name)
        assertFalse(report.completed)
    }
}
