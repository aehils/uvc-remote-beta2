package com.expiation.reemanremote

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plain http goes to any local network address the robot is given, and nowhere else. */
class RobotApiAddressTest {

    @Test
    fun localNetworkAddressesAreAllowed() {
        listOf("192.168.1.228", "192.168.0.105", "10.0.0.7", "172.16.4.2", "172.31.255.254", "169.254.10.1", "192.168.1.228:8080")
            .forEach { assertTrue(it, RobotApi.isLocalAddress(it)) }
    }

    @Test
    fun everythingElseIsRefused() {
        listOf("8.8.8.8", "172.32.0.1", "172.15.0.1", "192.169.1.1", "192.168.1", "192.168.1.256", "192.168.1.1.1",
            "robot.example.com", "", "192.168.1.228:0", "192.168.1.228:99999", "192.168.1.228:x", "1.2.3.4:5:6", "192.168.+1.2")
            .forEach { assertFalse(it, RobotApi.isLocalAddress(it)) }
    }

    @Test
    fun aPublicAddressIsNeverContacted() = runTest {
        val r = RobotApi("8.8.8.8").get("/reeman/current_version")
        assertFalse(r.ok)
        assertEquals(RobotApi.NOT_LOCAL, r.error)
    }
}
