package com.expiation.reemanremote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The demo RobotApi must answer from the simulator, never from the network. */
@OptIn(ExperimentalCoroutinesApi::class)
class RobotApiDemoTest {

    @Test
    fun demoAnswersInProcess() = runTest {
        // Host "demo" doesn't resolve, so this only succeeds if no socket is opened.
        val api = RobotApi.demo(FakeRobot())
        assertTrue(api.isDemo)
        val r = api.get("/reeman/current_version")
        assertTrue(r.error, r.ok)
        assertTrue(r.body.contains("DEMO"))
    }

    @Test
    fun robotErrorsComeBackLikeTheRealClientReportsThem() = runTest {
        val r = RobotApi.demo(FakeRobot()).get("/robot/get_mode")
        assertFalse(r.ok)
        assertEquals(404, r.httpCode)
        assertTrue(r.error!!.startsWith("HTTP 404 "))
    }

    @Test
    fun wifiDropTimesOutAfterTheConnectTimeout() = runTest {
        val fake = FakeRobot().apply { isOffline = true }
        val r = RobotApi.demo(fake).get("/reeman/speed")
        assertFalse(r.ok)
        assertTrue(r.error!!.contains("simulated Wi-Fi drop"))
        assertEquals("GET connect timeout", 1500L, currentTime)
    }

    @Test
    fun liveClientIsNotDemo() {
        assertFalse(RobotApi("192.168.1.228").isDemo)
    }
}
