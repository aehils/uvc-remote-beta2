package com.expiation.reemanremote

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/** When a routine with a time of day comes round, and is asked about only once that day. */
class RoutineScheduleTest {

    private val monday = RoutineSchedule.epochDay(at(2026, Calendar.OCTOBER, 26, 12, 0))
    private val stops = listOf(TaskStop("Bed 1", 5))
    private val morning = DisinfectionTask("m", "Morning", stops, startMinute = 8 * 60)
    private val anyTime = DisinfectionTask("a", "Any time", stops)

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int) = Calendar.getInstance().apply {
        set(year, month, day, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun daysAreCountedOnThePhonesCalendar() {
        assertEquals(0, RoutineSchedule.weekday(monday))
        assertEquals("Mon 26 Oct", RoutineSchedule.dateText(monday))
        assertEquals(monday + 1, RoutineSchedule.epochDay(at(2026, Calendar.OCTOBER, 27, 0, 0)))
        assertEquals(monday, RoutineSchedule.epochDay(at(2026, Calendar.OCTOBER, 26, 23, 59)))
    }

    @Test
    fun theTimeReadsAsAClock() {
        assertEquals("08:05", RoutineSchedule.timeText(8 * 60 + 5))
        assertEquals("23:59", RoutineSchedule.timeText(24 * 60 - 1))
    }

    @Test
    fun aNewScheduleStartsTodayIfItsTimeIsStillToCome() {
        val tenAm = at(2026, Calendar.OCTOBER, 26, 10, 0)
        assertEquals(monday, RoutineSchedule.firstDay(11 * 60, tenAm))
        assertEquals(monday + 1, RoutineSchedule.firstDay(10 * 60, tenAm))
    }

    @Test
    fun everyOtherDayCountsFromItsStart() {
        val r = Repeat(2, RepeatUnit.DAYS, startDay = monday)
        assertFalse(r.occursOn(monday - 2))
        assertTrue(r.occursOn(monday))
        assertFalse(r.occursOn(monday + 1))
        assertTrue(r.occursOn(monday + 2))
    }

    @Test
    fun everyTwoWeeksOnItsWeekdays() {
        val r = Repeat(2, RepeatUnit.WEEKS, setOf(0, 3), startDay = monday + 1) // from the Tuesday
        assertFalse(r.occursOn(monday)) // before the start, in the same week
        assertTrue(r.occursOn(monday + 3)) // Thursday
        assertFalse(r.occursOn(monday + 4)) // Friday
        assertFalse(r.occursOn(monday + 7)) // the week off
        assertTrue(r.occursOn(monday + 14))
    }

    @Test
    fun nextIsLaterTodayOrTheNextDayItComesRound() {
        val daily = morning.copy(repeat = Repeat(startDay = monday))
        assertEquals(at(2026, Calendar.OCTOBER, 26, 8, 0), RoutineSchedule.nextAt(daily, at(2026, Calendar.OCTOBER, 26, 7, 0)))
        assertEquals(at(2026, Calendar.OCTOBER, 27, 8, 0), RoutineSchedule.nextAt(daily, at(2026, Calendar.OCTOBER, 26, 8, 0)))

        val thursdays = morning.copy(repeat = Repeat(1, RepeatUnit.WEEKS, setOf(3), monday))
        assertEquals(at(2026, Calendar.OCTOBER, 29, 8, 0), RoutineSchedule.nextAt(thursdays, at(2026, Calendar.OCTOBER, 26, 9, 0)))

        val notYet = morning.copy(repeat = Repeat(startDay = monday + 30))
        assertEquals(at(2026, Calendar.NOVEMBER, 25, 8, 0), RoutineSchedule.nextAt(notYet, at(2026, Calendar.OCTOBER, 26, 9, 0)))

        assertNull(RoutineSchedule.nextAt(anyTime, at(2026, Calendar.OCTOBER, 26, 9, 0)))
    }

    @Test
    fun dueFromItsTimeUntilTheGraceRunsOut() {
        val tasks = listOf(anyTime, morning)
        assertNull(RoutineSchedule.due(tasks, 8 * 60 - 1, monday, emptyMap()))
        assertEquals(morning, RoutineSchedule.due(tasks, 8 * 60, monday, emptyMap()))
        assertEquals(morning, RoutineSchedule.due(tasks, 8 * 60 + RoutineSchedule.GRACE_MINUTES - 1, monday, emptyMap()))
        assertNull(RoutineSchedule.due(tasks, 8 * 60 + RoutineSchedule.GRACE_MINUTES, monday, emptyMap()))
    }

    @Test
    fun notDueOnADayItDoesntComeRound() {
        val other = morning.copy(repeat = Repeat(2, RepeatUnit.DAYS, startDay = monday))
        assertEquals(other, RoutineSchedule.due(listOf(other), 8 * 60, monday, emptyMap()))
        assertNull(RoutineSchedule.due(listOf(other), 8 * 60, monday + 1, emptyMap()))
    }

    @Test
    fun askedOnceADay() {
        assertNull(RoutineSchedule.due(listOf(morning), 8 * 60, monday, mapOf("m" to monday)))
        assertEquals(morning, RoutineSchedule.due(listOf(morning), 8 * 60, monday + 1, mapOf("m" to monday)))
    }

    @Test
    fun theScheduleReadsPlainly() {
        assertEquals("Every day", Repeat().text)
        assertEquals("Every 2 days", Repeat(2).text)
        assertEquals("Every week on Mon, Thu", Repeat(1, RepeatUnit.WEEKS, setOf(3, 0)).text)
        assertEquals("Every 2 weeks on Sun", Repeat(2, RepeatUnit.WEEKS, setOf(6)).text)
    }

    @Test
    fun aWeeklyScheduleAlwaysHasADay() {
        assertEquals(setOf(0), Repeat(1, RepeatUnit.WEEKS, emptySet(), monday).coerced().weekdays)
        assertEquals(Repeat.MAX_WEEKS, Repeat(99, RepeatUnit.WEEKS, setOf(0)).coerced().every)
    }

    @Test
    fun anOldRoutineRepeatsEveryDay() {
        val json = """[{"id":"a","name":"A","stops":[{"point":"Bed 1","minutes":5}],"start_minute":480}]"""
        val t = DisinfectionTask.listFromJson(json).single()
        assertEquals(480, t.startMinute)
        assertEquals(Repeat(), t.repeat)
    }
}
