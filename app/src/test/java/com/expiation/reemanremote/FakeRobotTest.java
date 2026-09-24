package com.expiation.reemanremote;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.Before;
import org.junit.Test;

/** Drives the demo robot on a fake clock, checking it behaves like the real firmware. */
public class FakeRobotTest {

    private long nowNs;
    private FakeRobot robot;

    @Before
    public void setUp() {
        nowNs = 0;
        robot = new FakeRobot(() -> nowNs, new Random(1), false);
    }

    private void advance(double seconds) {
        nowNs += (long) (seconds * 1e9);
    }

    private String get(String path) {
        return robot.handle("GET", path, null).body;
    }

    private String post(String path, String json) {
        return robot.handle("POST", path, json).body;
    }

    private static double num(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"?(-?[0-9.]+)").matcher(json);
        assertTrue(key + " missing in " + json, m.find());
        return Double.parseDouble(m.group(1));
    }

    private double vx() {
        return num(get("/reeman/speed"), "vx");
    }

    private double vth() {
        return num(get("/reeman/speed"), "vth");
    }

    private void forward() {
        assertEquals("{}", post("/cmd/move", "{\"distance\":50,\"direction\":1,\"speed\":0.30}"));
    }

    @Test
    public void statusMatchesFirmwareShapes() {
        assertTrue(get("/reeman/current_version").contains("DEMO"));
        String base = get("/reeman/base_encode");
        assertEquals(87, num(base, "battery"), 0);
        assertEquals(1, num(base, "emergencyButton"), 0); // 1 = released
        assertEquals(0, vx(), 0);
    }

    @Test
    public void forwardStepHasLatencyRampCruiseAndStops() {
        forward();
        advance(0.1);
        assertEquals("still inside the ~200 ms latency", 0, vx(), 1e-9);
        advance(0.6);
        double ramping = vx();
        assertTrue(ramping > 0.05 && ramping < 0.3);
        advance(1.1); // 1.6 s after the wheels started: cruising
        assertEquals(0.3, vx(), 1e-6);
        advance(2.0);
        assertEquals(0, vx(), 1e-9);
        advance(0.3);
        assertEquals(0.5, num(get("/reeman/pose"), "x"), 0.01);
    }

    @Test
    public void backwardStepMovesBackward() {
        post("/cmd/move", "{\"distance\":50,\"direction\":0,\"speed\":0.20}");
        advance(1.5);
        assertTrue(vx() < -0.05);
        advance(5);
        assertEquals(-0.5, num(get("/reeman/pose"), "x"), 0.01);
    }

    @Test
    public void turnsGoTheRightWay() {
        post("/cmd/turn", "{\"direction\":1,\"angle\":90,\"speed\":0.40}");
        advance(1.5);
        assertTrue("left is anticlockwise", vth() > 0.1);
        advance(10);
        assertEquals(Math.PI / 2, num(get("/reeman/pose"), "theta"), 0.01);

        post("/cmd/turn", "{\"direction\":0,\"angle\":180,\"speed\":0.40}");
        advance(1.5);
        assertTrue("right is clockwise", vth() < -0.1);
        advance(15);
        assertEquals(-Math.PI / 2, num(get("/reeman/pose"), "theta"), 0.01);
    }

    @Test
    public void stopBodyIsAHardStop() {
        forward();
        advance(1.5);
        assertTrue(vx() > 0.1);
        assertEquals("{}", post("/cmd/move", "{\"distance\":0,\"direction\":1,\"speed\":0}"));
        assertEquals(0, vx(), 1e-9);
        advance(1);
        assertEquals(0, vx(), 1e-9);
    }

    @Test
    public void estopBlocksMotionAndStopsARunningStep() {
        forward();
        advance(1.5);
        robot.setEstopPressed(true);
        assertEquals(0, vx(), 1e-9);
        assertEquals(0, num(get("/reeman/base_encode"), "emergencyButton"), 0);
        forward();
        advance(1.5);
        assertEquals("accepted but never moves", 0, vx(), 1e-9);
        robot.setEstopPressed(false);
        forward();
        advance(1.5);
        assertTrue(vx() > 0.1);
    }

    @Test
    public void blockedStepNeverStartsButTheNextOneDoes() {
        robot.blockNextStep();
        forward();
        advance(1.5);
        assertEquals(0, vx(), 1e-9);
        forward();
        advance(1.5);
        assertTrue(vx() > 0.1);
    }

    @Test
    public void wallStopsTheRobotShort() {
        for (int i = 0; i < 5; i++) {
            forward();
            advance(5);
        }
        // Wall at x = 2.5, robot radius 0.3: the fifth step can't complete.
        double x = num(get("/reeman/pose"), "x");
        assertTrue("stopped short at " + x, x > 2.15 && x <= 2.2);
        forward();
        advance(1.5);
        assertEquals("already against the wall", 0, vx(), 1e-9);
    }

    @Test
    public void wifiDropReturnsNoReply() {
        robot.setOffline(true);
        assertNull(robot.handle("GET", "/reeman/speed", null));
        robot.setOffline(false);
        assertEquals(200, robot.handle("GET", "/reeman/speed", null).code);
    }

    @Test
    public void errorsUseFirmwareCodes() {
        FakeRobot.Reply r = robot.handle("GET", "/robot/get_mode", null);
        assertEquals(404, r.code);
        assertTrue(r.body.contains("\"error_code\":\"009\""));
        assertTrue(post("/cmd/speed", "{\"vx\":0.2,\"vth\":0}").contains("\"error_code\":\"004\""));
        assertTrue("more than 180 degrees is rejected",
                post("/cmd/turn", "{\"direction\":1,\"angle\":270,\"speed\":0.4}").contains("004"));
    }

    @Test
    public void batteryDrainWrapsToFull() {
        for (int i = 0; i < 8; i++) robot.drainBattery();
        assertEquals(7, robot.batteryPercent());
        robot.drainBattery();
        assertEquals(100, robot.batteryPercent());
    }

    @Test
    public void laserSeesTheWallAhead() {
        String laser = get("/reeman/laser");
        assertTrue(laser.startsWith("{\"coordinates\":[[2.500,0.000]"));
    }
}
