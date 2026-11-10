package com.expiation.reemanremote

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Activity log keeps a day of lines, and they outlive the app (a new ActivityLog on the same file). */
class ActivityLogTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun keepsFarMoreThanFourteenLinesNewestFirst() {
        val log = ActivityLog()
        repeat(500) { log.add("line $it") }
        assertEquals(500, log.all().size)
        assertEquals("line 499", log.all().first())
        assertEquals(listOf("line 499", "line 498"), log.newest(2))
    }

    @Test
    fun linesSurviveTheAppClosingAndOnlyTheNewestAreKept() {
        val f = tmp.newFile("activity_log.txt")
        val first = ActivityLog(f, keep = 300)
        repeat(1_000) { first.add("line $it") }
        val reopened = ActivityLog(f, keep = 300)
        assertEquals(300, reopened.all().size)
        assertEquals("line 999", reopened.all().first())
        assertEquals("line 700", reopened.all().last())
        reopened.add("after restart")
        assertEquals("after restart", ActivityLog.readNewestFirst(f, 300).first())
    }

    @Test
    fun noFileYetReadsAsEmpty() {
        assertEquals(emptyList<String>(), ActivityLog.readNewestFirst(tmp.root.resolve("missing.txt")))
    }
}
