package com.expiation.reemanremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saving and reading back the disinfection tasks (kept in the app's preferences as JSON). */
class TasksTest {

    @Test
    fun reportsSurviveTheRoundTrip() {
        val task = DisinfectionTask("a", "Ward A", listOf(TaskStop("Bed 1", 5)), startMinute = 480)
        val reports = listOf(
            TaskReport(task, true, null, listOf(StopResult(1, "Bed 1", StopOutcome.DONE, 300_000)), at = 1000),
            TaskReport(task, false, "battery low", listOf(StopResult(1, "Bed 1", StopOutcome.NOT_REACHED)), at = 2000),
        )
        assertEquals(reports, TaskReport.listFromJson(TaskReport.listToJson(reports)))
        assertTrue(TaskReport.listFromJson("not json").isEmpty())
    }

    @Test
    fun aReportReadsPlainly() {
        val task = DisinfectionTask("a", "Ward A", listOf(TaskStop("Bed 1", 5), TaskStop("Bed 2", 5)))
        val r = TaskReport(task, false, "battery low", listOf(StopResult(1, "Bed 1", StopOutcome.DONE)))
        assertEquals("Ward A ended", r.title)
        assertEquals("1 of 2 points done · battery low", r.detail)
        assertFalse(r.good)
    }

    @Test
    fun tasksSurviveTheRoundTrip() {
        val tasks = listOf(
            DisinfectionTask("a", "Ward A", listOf(TaskStop("Bed 1", 5), TaskStop("Bed 2", 10)), 30, 2, WhenDone.CHARGER),
            DisinfectionTask("b", "Sink", listOf(TaskStop("Sink area", 1)), 60, 1, WhenDone.STAY, startMinute = 8 * 60 + 5,
                repeat = Repeat(2, RepeatUnit.WEEKS, setOf(0, 3), 20_000)),
        )
        assertEquals(tasks, DisinfectionTask.listFromJson(DisinfectionTask.listToJson(tasks)))
    }

    @Test
    fun whatCantBeReadIsDropped() {
        assertTrue(DisinfectionTask.listFromJson(null).isEmpty())
        assertTrue(DisinfectionTask.listFromJson("not json").isEmpty())
        val json = """[{"id":"a"},{"id":"b","name":"Ok","stops":[{"point":"Bed 1","minutes":5}],"when_done":"FLY"}]"""
        val read = DisinfectionTask.listFromJson(json)
        assertEquals(listOf("Ok"), read.map { it.name })
        assertEquals(WhenDone.CHARGER, read.single().whenDone)
    }

    @Test
    fun valuesAreKeptInRange() {
        val t = DisinfectionTask("a", "x".repeat(50), listOf(TaskStop("Bed 1", 0)), delaySeconds = 0, rounds = 99).coerced()
        assertEquals(DisinfectionTask.MAX_NAME, t.name.length)
        assertEquals(1, t.stops.single().minutes)
        assertEquals(DriveController.MIN_UV_DELAY_S, t.delaySeconds)
        assertEquals(DisinfectionTask.MAX_ROUNDS, t.rounds)
    }

    @Test
    fun uvMinutesCountEveryRound() {
        val t = DisinfectionTask("a", "A", listOf(TaskStop("Bed 1", 5), TaskStop("Bed 2", 10)), rounds = 3)
        assertEquals(45, t.uvMinutes)
    }

    @Test
    fun anExportedLogSaysWhichRobotItIsFrom() {
        val text = com.expiation.reemanremote.ui.activityLogText("UV 3-07", "v5.1.20", listOf("12:00:02 second", "12:00:01 first"), 0L)
        val lines = text.lines()
        assertTrue(lines[0], lines[0].startsWith("Activity log, UV 3-07, v5.1.20, exported "))
        assertEquals(listOf("", "12:00:02 second", "12:00:01 first"), lines.drop(1))
    }
}
