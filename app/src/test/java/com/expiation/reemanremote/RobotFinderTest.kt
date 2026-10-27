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
        val hosts = RobotFinder.candidates("192.168.0.20", 24, listOf("192.168.0.228"))
        assertEquals("192.168.0.228", hosts.first())
        assertEquals(253, hosts.size) // .1 to .254, not the phone
        assertFalse("192.168.0.20" in hosts)
        assertFalse("192.168.0.0" in hosts || "192.168.0.255" in hosts)
    }

    @Test
    fun aBigNetworkIsSearchedAroundThePhoneAndTheLastAddress() {
        val hosts = RobotFinder.candidates("10.4.7.9", 16, listOf("10.4.200.31"))
        assertEquals("10.4.200.31", hosts.first())
        assertEquals(254 + 253, hosts.size)
        assertTrue("10.4.7.1" in hosts && "10.4.200.254" in hosts)
        assertFalse("10.4.8.1" in hosts)
    }

    @Test
    fun aSlash22IsSearchedWhole() {
        val hosts = RobotFinder.candidates("172.16.5.3", 22, emptyList())
        assertEquals(1021, hosts.size)
        assertEquals("172.16.4.1", hosts.first())
        assertEquals("172.16.7.254", hosts.last())
    }

    @Test
    fun anAddressOffTheLocalNetworkIsNeverSearched() {
        assertTrue(RobotFinder.candidates("8.8.8.8", 24, emptyList()).isEmpty())
        assertEquals(253, RobotFinder.candidates("192.168.1.5", 24, listOf("8.8.4.4")).size)
    }

    @Test
    fun everyAddressTheRobotHadIsAskedFirstEvenOnAnotherNetwork() {
        val hosts = RobotFinder.candidates("192.168.0.20", 24, listOf("192.168.0.40", "10.20.30.40", "192.168.1.228"))
        assertEquals(listOf("192.168.0.40", "10.20.30.40", "192.168.1.228"), hosts.take(3))
        assertEquals(253 + 2, hosts.size) // the phone's /24 (the most recent address is in it), plus the other two
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
        assertEquals(FakeRobot.HOSTNAME, robot.hostname)
        // Nothing listening there: not a robot (and no socket is opened for a public address).
        assertNull(RobotFinder.identify(RobotApi("192.0.2.5")))
    }

    // ---- DriveController: following the robot ----

    private val mine = "wbot11u-250101-003-007"
    private fun found(host: String, hostname: String?) = FoundRobot(host, "v5", hostname)

    private fun TestScope.controller(
        robotName: String?,
        demo: Boolean = false,
        live: RobotApi = RobotApi("192.0.2.1"), // TEST-NET: not local, so no socket is ever opened
        search: suspend (String, String?) -> SearchResult,
    ): DriveController {
        val fake = FakeRobot({ testScheduler.currentTime * 1_000_000 }, Random(1), realistic = true)
        return DriveController(
            backgroundScope, live, fake, demo, now = { testScheduler.currentTime },
            search = search, robotName = robotName,
        )
    }

    /** A live robot answering from a simulated one with [hostname]. */
    private fun TestScope.robotNamed(hostname: String) =
        RobotApi.demo(FakeRobot({ testScheduler.currentTime * 1_000_000 }, Random(2), realistic = false).also { it.hostname = hostname })

    private fun log(c: DriveController) = c.state.value.log.joinToString("\n")

    @Test
    fun followsItsRobotToANewAddress() = runTest {
        var asked: String? = null
        val c = controller(mine) { _, name -> asked = name; SearchResult(254, listOf(found("192.0.2.77", mine))) }
        c.resume()
        advanceTimeBy(2000)
        val s = c.state.value
        assertEquals(mine, asked) // asked for by name
        assertEquals(log(c), "192.0.2.77", s.host)
        assertTrue(s.foundRobots.isEmpty())
        assertTrue(log(c), s.log.any { "UV 3-07 found at a new address" in it })
    }

    @Test
    fun anotherRobotIsOnlyListed() = runTest {
        val other = found("192.0.2.80", "wbot11u-250101-003-008")
        val c = controller(mine) { _, _ -> SearchResult(254, listOf(other)) }
        c.resume()
        advanceTimeBy(2000)
        val s = c.state.value
        assertEquals("192.0.2.1", s.host)
        assertEquals(listOf(other), s.foundRobots)
        assertEquals("UV 3-07 not found. Other robots here", s.searchNote)
    }

    @Test
    fun aRobotIsNeverFollowedUntilTheAppKnowsItsName() = runTest {
        val c = controller(robotName = null) { _, _ -> SearchResult(254, listOf(found("192.0.2.77", mine))) }
        c.resume()
        advanceTimeBy(2000)
        assertEquals("192.0.2.1", c.state.value.host)
        assertEquals(1, c.state.value.foundRobots.size)
        assertEquals("Robots on this Wi-Fi", c.state.value.searchNote)
    }

    @Test
    fun pickingAFoundRobotDrivesItAndLearnsItsName() = runTest {
        val c = controller(robotName = null) { _, _ -> SearchResult(254, listOf(found("192.0.2.77", mine))) }
        c.resume()
        advanceTimeBy(2000)
        c.pickRobot(c.state.value.foundRobots.single())
        assertEquals("192.0.2.77", c.state.value.host)
        assertEquals(mine, c.state.value.robotName)
        assertTrue(c.state.value.foundRobots.isEmpty())
    }

    @Test
    fun learnsTheNameOfTheRobotItConnectsTo() = runTest {
        val c = controller(robotName = null, live = robotNamed(mine)) { _, _ -> SearchResult(0, emptyList()) }
        c.resume()
        advanceTimeBy(3000)
        assertTrue(log(c), c.state.value.connected)
        assertEquals(mine, c.state.value.robotName)
    }

    @Test
    fun anotherRobotAtTheAddressIsNotConnectedTo() = runTest {
        var searches = 0
        val c = controller(mine, live = robotNamed("wbot11u-250101-003-008")) { _, _ -> searches++; SearchResult(254, emptyList()) }
        c.resume()
        advanceTimeBy(3000)
        val s = c.state.value
        assertFalse(log(c), s.connected)
        assertEquals(mine, s.robotName) // still the robot staff chose
        assertTrue(log(c), s.log.any { "UV 3-08 answers at this address, not UV 3-07" in it })
        assertEquals(1, searches) // and it looks for its own
        advanceTimeBy(DriveController.SEARCH_EVERY_MS)
        assertEquals(2, searches)
    }

    @Test
    fun itsOwnRobotAtTheAddressConnects() = runTest {
        val c = controller(mine, live = robotNamed(mine)) { _, _ -> SearchResult(0, emptyList()) }
        c.resume()
        advanceTimeBy(3000)
        assertTrue(log(c), c.state.value.connected)
        assertTrue(log(c), c.state.value.log.any { "Connected to UV 3-07" in it })
    }

    @Test
    fun twoRobotsWithTheSameNameAreListed() = runTest {
        val c = controller(mine) { _, _ -> SearchResult(254, listOf(found("192.0.2.77", mine), found("192.0.2.78", mine))) }
        c.resume()
        advanceTimeBy(2000)
        assertEquals("192.0.2.1", c.state.value.host)
    }

    @Test
    fun searchesAgainWhileTheRobotIsMissingButNotConstantly() = runTest {
        var searches = 0
        val c = controller(mine) { _, _ -> searches++; delay(1500); SearchResult(254, emptyList()) }
        c.resume()
        advanceTimeBy(1000)
        assertTrue("shows it is looking", c.state.value.searching)
        advanceTimeBy(2000)
        assertFalse(c.state.value.searching)
        assertEquals(1, searches)
        assertEquals("UV 3-07 not found on this Wi-Fi", c.state.value.searchNote)
        advanceTimeBy(DriveController.SEARCH_EVERY_MS)
        assertEquals(2, searches)
        // Logged once, not on every search.
        assertEquals(1, c.state.value.log.count { "not found on this Wi-Fi" in it })
    }

    @Test
    fun saysWhenThePhoneIsNotOnWifi() = runTest {
        val c = controller(mine) { _, _ -> SearchResult(0, emptyList()) }
        c.resume()
        advanceTimeBy(2000)
        assertEquals("The phone isn't on Wi-Fi", c.state.value.searchNote)
    }

    @Test
    fun aWifiChangeSearchesAtOnce() = runTest {
        var searches = 0
        val c = controller(mine) { _, _ -> searches++; SearchResult(254, emptyList()) }
        c.resume()
        advanceTimeBy(2000)
        c.networkChanged()
        advanceTimeBy(10)
        assertEquals(2, searches)
    }

    @Test
    fun theDemoRobotIsNeverSearchedForNorTakenForTheRealOne() = runTest {
        var searches = 0
        val c = controller(mine, demo = true) { _, _ -> searches++; SearchResult(254, emptyList()) }
        c.resume()
        c.demoToggleWifi()
        advanceTimeBy(30_000)
        c.findRobot()
        advanceTimeBy(10)
        assertEquals(0, searches)
        assertEquals(mine, c.state.value.robotName)
    }

    @Test
    fun typingAnotherAddressForgetsTheRobotsName() = runTest {
        val c = controller(mine) { _, _ -> SearchResult(254, emptyList()) }
        c.testConnection("192.0.2.50")
        assertNull(c.state.value.robotName)
    }

    // ---- RobotName ----

    @Test
    fun aRobotIsCalledByItsLastTwoNumbers() {
        assertEquals("UV 3-07", RobotName.short("wbot11u-250101-003-007"))
        assertEquals("UV 12-145", RobotName.short("wbot11u-250101-012-145"))
        assertEquals("UV 0-01", RobotName.short(FakeRobot.HOSTNAME))
    }

    @Test
    fun anotherKindOfHostnameIsShownAsWellAsPossible() {
        assertEquals("UV 42", RobotName.short("robot-42"))
        assertEquals("robot-robot", RobotName.short("robot-robot")) // the API's own example
    }

    // ---- Mdns ----

    @Test
    fun mdnsReadsTheAddressFromTheRobotsReply() {
        // Shaped like the test robot's reply to `dig @224.0.0.251 -p 5353 <hostname>.local` (2026-10-27).
        val q = Mdns.query("wbot11u-250101-003-007.local", id = 0x298B)
        val reply = q.copyOf().also {
            it[2] = 0x84.toByte() // a reply, authoritative
            it[7] = 1             // one answer
        } + byteArrayOf(0xC0.toByte(), 12, 0, 1, 0, 1, 0, 0, 0, 10, 0, 4, 192.toByte(), 168.toByte(), 0, 40)
        assertEquals("192.168.0.40", Mdns.answer(reply, "wbot11u-250101-003-007.local"))
        assertEquals("192.168.0.40", Mdns.answer(reply, "WBOT11U-250101-003-007.local."))
        assertNull(Mdns.answer(reply, "wbot11u-250101-003-008.local"))
        assertNull(Mdns.answer(q, "wbot11u-250101-003-007.local")) // a question, not a reply
        assertNull(Mdns.answer(reply.copyOf(20), "wbot11u-250101-003-007.local")) // cut short
    }

    @Test
    fun mdnsQuestionIsAPlainDnsQuery() {
        val q = Mdns.query("a.local", id = 1)
        assertEquals(listOf(0, 1, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 1, 'a'.code, 5, 'l'.code, 'o'.code, 'c'.code, 'a'.code, 'l'.code, 0, 0, 1, 0, 1),
            q.map { it.toInt() and 0xFF })
    }
}
