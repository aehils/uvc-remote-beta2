package com.expiation.reemanremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** The demo RobotApi must answer from the simulator, never from the network. */
public class RobotApiDemoTest {

    @Test
    public void demoAnswersInProcess() {
        // Host "demo" doesn't resolve, so this only succeeds if no socket is opened.
        RobotApi api = RobotApi.demo(new FakeRobot());
        assertTrue(api.isDemo());
        RobotApi.Result r = api.get("/reeman/current_version");
        assertTrue(r.error, r.ok);
        assertTrue(r.body.contains("DEMO"));
    }

    @Test
    public void robotErrorsComeBackLikeTheRealClientReportsThem() {
        RobotApi.Result r = RobotApi.demo(new FakeRobot()).get("/robot/get_mode");
        assertFalse(r.ok);
        assertEquals(404, r.httpCode);
        assertTrue(r.error.startsWith("HTTP 404 "));
    }

    @Test
    public void wifiDropTimesOutAfterTheConnectTimeout() {
        FakeRobot fake = new FakeRobot();
        fake.setOffline(true);
        long start = System.nanoTime();
        RobotApi.Result r = RobotApi.demo(fake).get("/reeman/speed");
        long ms = (System.nanoTime() - start) / 1_000_000;
        assertFalse(r.ok);
        assertTrue(r.error.contains("simulated Wi-Fi drop"));
        assertTrue("waited " + ms + " ms", ms >= 1400); // GET connect timeout is 1500 ms
    }

    @Test
    public void liveClientIsNotDemo() {
        assertFalse(new RobotApi("192.168.1.228").isDemo());
    }
}
