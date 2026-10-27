package com.expiation.reemanremote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Finding the robot on the phone's Wi-Fi, and when the app follows it without asking. */
@OptIn(ExperimentalCoroutinesApi::class)
class RobotFinderTest {

    // ---- RobotFinder ----

    @Test
    fun searchesThePhonesWholeNetworkLastAddressFirst() {
        val hosts = RobotFinder.candidates("192.168.0.20", 24, "192.168.0.228")
        assertEquals("192.168.0.228", hosts.first())
        assertEquals(253, hosts.size) // .1 to .254, not the phone
        assertFalse("192.168.0.20" in hosts)
        assertFalse("192.168.0.0" in hosts || "192.168.0.255" in hosts)
    }

    @Test
    fun aBigNetworkIsSearchedAroundThePhoneAndTheLastAddress() {
        val hosts = RobotFinder.candidates("10.4.7.9", 16, "10.4.200.31")
        assertEquals("10.4.200.31", hosts.first())
        assertEquals(254 + 253, hosts.size)
        assertTrue("10.4.7.1" in hosts && "10.4.200.254" in hosts)
        assertFalse("10.4.8.1" in hosts)
    }

    @Test
    fun aSlash22IsSearchedWhole() {
        val hosts = RobotFinder.candidates("172.16.5.3", 22, null)
        assertEquals(1021, hosts.size)
        assertEquals("172.16.4.1", hosts.first())
        assertEquals("172.16.7.254", hosts.last())
    }

    @Test
    fun anAddressOffTheLocalNetworkIsNeverSearched() {
        assertTrue(RobotFinder.candidates("8.8.8.8", 24, null).isEmpty())
        assertEquals(253, RobotFinder.candidates("192.168.1.5", 24, "8.8.4.4").size)
    }

    @Test
    fun findAsksManyAtOnceAndKeepsTheOrder() = runTest {
        val hosts = (1..254).map { "192.168.1.$it" }
        var inFlight = 0
        var most = 0
        val found = RobotFinder.find(hosts, parallel = 64) { h ->
            inFlight++
            most = maxOf(most, inFlight)
            delay(300)
            inFlight--
            if (h.endsWith(".9") || h.endsWith(".200")) FoundRobot(h, "v", null) else null
        }
        assertEquals(listOf("192.168.1.9", "192.168.1.200"), found.map { it.host })
        assertEquals(64, most)
        assertEquals(1200, testScheduler.currentTime) // 254 hosts, 64 at a time, 300 ms each
    }

    @Test
    fun identifyRecognisesARobotByItsAnswers() = runTest {
        val robot = RobotFinder.identify(RobotApi.demo(FakeRobot()))
        assertTrue(robot!!.version.isNotEmpty())
        assertTrue(robot.map!!.id.isNotEmpty())
        // Nothing listening there: not a robot (and no socket is opened for a public address).
        assertNull(RobotFinder.identify(RobotApi("192.0.2.5")))
    }

    // ---- DriveController: following the robot ----

    private val ward = SavedMap("map-ward-3", "Ward 3")

    private fun TestScope.controller(robotMap: String?, demo: Boolean = false, search: suspend (String) -> SearchResult): DriveController {
        val fake = FakeRobot({ testScheduler.currentTime * 1_000_000 }, Random(1), realistic = true)
        // TEST-NET addresses: not local, so the live client never opens a socket.
        return DriveController(
            backgroundScope, RobotApi("192.0.2.1"), fake, demo, now = { testScheduler.currentTime },
            search = search, robotMap = robotMap,
        )
    }

    private fun log(c: DriveController) = c.state.value.log.joinToString("\n")

    @Test
    fun followsItsRobotToANewAddress() = runTest {
        val c = controller(ward.id) { SearchResult(254, listOf(FoundRobot("192.0.2.77", "v5", ward))) }
        c.resume()
        advanceTimeBy(2000)
        val s = c.state.value
        assertEquals(log(c), "192.0.2.77", s.host)
        assertTrue(s.foundRobots.isEmpty())
        assertTrue(log(c), s.log.any { "Robot found at 192.0.2.77 (it was at 192.0.2.1)" in it })
    }

    @Test
    fun anotherRobotIsOnlyListed() = runTest {
        val other = FoundRobot("192.0.2.80", "v5", SavedMap("map-theatre", "Theatre"))
        val c = controller(ward.id) { SearchResult(254, listOf(other)) }
        c.resume()
        advanceTimeBy(2000)
        val s = c.state.value
        assertEquals("192.0.2.1", s.host)
        assertEquals(listOf(other), s.foundRobots)
        assertTrue(s.searchNote!!.contains("Pick yours"))
    }

    @Test
    fun aRobotIsNeverFollowedUntilTheAppKnowsItsMap() = runTest {
        val c = controller(robotMap = null) { SearchResult(254, listOf(FoundRobot("192.0.2.77", "v5", ward))) }
        c.resume()
        advanceTimeBy(2000)
        assertEquals("192.0.2.1", c.state.value.host)
        assertEquals(1, c.state.value.foundRobots.size)
    }

    @Test
    fun twoRobotsOnTheSameMapAreListed() = runTest {
        val c = controller(ward.id) {
            SearchResult(254, listOf(FoundRobot("192.0.2.77", "v5", ward), FoundRobot("192.0.2.78", "v5", ward)))
        }
        c.resume()
        advanceTimeBy(2000)
        assertEquals("192.0.2.1", c.state.value.host)
        assertEquals(2, c.state.value.foundRobots.size)
    }

    @Test
    fun searchesAgainWhileTheRobotIsMissingButNotConstantly() = runTest {
        var searches = 0
        val c = controller(ward.id) { searches++; delay(1500); SearchResult(254, emptyList()) }
        c.resume()
        advanceTimeBy(1000)
        assertTrue("shows it is looking", c.state.value.searching)
        advanceTimeBy(2000)
        assertFalse(c.state.value.searching)
        assertEquals(1, searches)
        assertTrue(c.state.value.searchNote!!.contains("No robot answered"))
        advanceTimeBy(DriveController.SEARCH_EVERY_MS)
        assertEquals(2, searches)
        // Logged once, not on every search.
        assertEquals(1, c.state.value.log.count { "No robot answered" in it })
    }

    @Test
    fun saysWhenThePhoneIsNotOnWifi() = runTest {
        val c = controller(ward.id) { SearchResult(0, emptyList()) }
        c.resume()
        advanceTimeBy(2000)
        assertTrue(c.state.value.searchNote!!.contains("isn't on Wi-Fi"))
    }

    @Test
    fun aWifiChangeSearchesAtOnce() = runTest {
        var searches = 0
        val c = controller(ward.id) { searches++; SearchResult(254, emptyList()) }
        c.resume()
        advanceTimeBy(2000)
        c.networkChanged()
        advanceTimeBy(10)
        assertEquals(2, searches)
    }

    @Test
    fun theDemoRobotIsNeverSearchedFor() = runTest {
        var searches = 0
        val c = controller(ward.id, demo = true) { searches++; SearchResult(254, emptyList()) }
        c.resume()
        c.demoToggleWifi()
        advanceTimeBy(30_000)
        c.findRobot()
        advanceTimeBy(10)
        assertEquals(0, searches)
        assertEquals(ward.id, c.state.value.robotMap) // the demo robot's map isn't taken for the real one's
    }

    @Test
    fun typingAnotherAddressForgetsTheRobotsMap() = runTest {
        val c = controller(ward.id) { SearchResult(254, emptyList()) }
        c.testConnection("192.0.2.50")
        assertNull(c.state.value.robotMap)
    }
}
