package com.expiation.reemanremote

import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ResourceBoundsTest {
    @Test fun discoveryReusesWorkersAndPreservesCandidateOrder() = runTest {
        val workers = HashSet<Job>()
        val hosts = (1..1022).map { "host-$it" }
        val results = RobotFinder.find(hosts, parallel = 8) { host ->
            workers += currentCoroutineContext().job
            delay(if (host == "host-1") 10 else 1)
            FoundRobot(host, "v", null)
        }
        assertEquals(hosts, results.map { it.host })
        assertEquals("Discovery must not create one coroutine per address", 8, workers.size)
    }

    @Test fun stoppingDiscoveryCancelsAllInFlightProbes() = runTest {
        var active = 0
        var finished = 0
        val job = launch {
            RobotFinder.find((1..100).map { "$it" }, parallel = 4) {
                active++
                try { delay(10_000); null } finally { active--; finished++ }
            }
        }
        testScheduler.runCurrent()
        assertEquals(4, active)
        job.cancel()
        job.join()
        assertEquals(0, active)
        assertEquals(4, finished)
    }

    @Test fun oldStateRetainsItsLogWhenNewEventsArrive() = runTest {
        val c = DriveController(backgroundScope, RobotApi("192.0.2.1"), FakeRobot(), true)
        c.note("first event")
        val before = c.state.value
        val saved = before.log.toList()
        c.setUvSettings(UvSettings(minutes = 2, delaySeconds = 15))
        assertEquals(saved, c.state.value.log)
        c.note("second event")
        assertEquals(saved, before.log)
        assertTrue(c.state.value.log.first().endsWith("second event"))
        assertEquals(2, c.state.value.uvSettings.minutes)
    }
}
