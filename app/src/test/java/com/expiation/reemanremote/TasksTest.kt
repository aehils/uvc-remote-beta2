package com.expiation.reemanremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Saving and reading back the disinfection tasks (kept in the app's preferences as JSON). */
class TasksTest {

    @Test
    fun tasksSurviveTheRoundTrip() {
        val tasks = listOf(
            DisinfectionTask("a", "Ward A", listOf(TaskStop("Bed 1", 5), TaskStop("Bed 2", 10)), 30, 2, WhenDone.CHARGER),
            DisinfectionTask("b", "Sink", listOf(TaskStop("Sink area", 1)), 60, 1, WhenDone.STAY),
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
}
