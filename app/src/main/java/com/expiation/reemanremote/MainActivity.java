package com.expiation.reemanremote;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Reeman Remote, beta.
 *
 * Design rule (see REEMAN_HANDOFF.txt sections 5-7): every motion is a fixed,
 * pre-planned step (/cmd/move or /cmd/turn). The robot's firmware plans the
 * whole trajectory, including its own gentle braking, so this app never
 * streams velocity (/cmd/speed) and cannot produce the jog hard-stop.
 *
 * Demo mode swaps the robot for an in-app simulator (FakeRobot) behind the same
 * RobotApi, so everything below runs unchanged and nothing leaves the phone.
 */
public class MainActivity extends Activity {

    // ---- Beta drive profile. Hard limits: nothing in the app goes above these. ----
    private static final int STEP_CM = 50;
    private static final double FORWARD_SPEED = 0.3;   // m/s
    private static final double BACKWARD_SPEED = 0.2;  // m/s (lidar sees less behind)
    private static final double TURN_SPEED = 0.4;      // rad/s (~23 deg/s)
    private static final double MAX_LINEAR = 0.3;      // m/s
    private static final double MAX_ANGULAR = 0.5;     // rad/s

    // ---- Connection / polling ----
    private static final String DEFAULT_HOST = "192.168.1.228";
    private static final String PREFS = "reeman_remote";
    private static final String KEY_HOST = "host";
    private static final String KEY_DEMO = "demo";
    private static final long POLL_MS = 300;           // /reeman/speed
    private static final int BASE_EVERY = 5;           // /reeman/base_encode every 5th poll (~1.5 s)
    private static final int FAILS_BEFORE_DISCONNECT = 3;

    // ---- "Is the robot still moving?" ----
    private static final double MOVING_VX = 0.02;      // m/s
    private static final double MOVING_VTH = 0.03;     // rad/s
    private static final int STILL_POLLS_TO_FINISH = 3; // ~0.9 s of stillness = step finished
    private static final long RAMP_ALLOWANCE_MS = 2500; // accel + decel + ~200 ms latency

    private static final String MOVE_STOP = "{\"distance\":0,\"direction\":1,\"speed\":0}";
    private static final String TURN_STOP = "{\"direction\":1,\"angle\":0,\"speed\":0}";

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService pollExec = Executors.newSingleThreadExecutor();
    private final ExecutorService cmdExec = Executors.newSingleThreadExecutor();
    private final ExecutorService stopExec = Executors.newSingleThreadExecutor();

    // api is whichever of liveApi / demoApi is selected. Background work captures it
    // at the moment it is queued, so a queued request can never switch robots.
    private RobotApi api, liveApi, demoApi;
    private FakeRobot fakeRobot;
    private boolean demo = false;
    // Bumped whenever the target robot changes; poll results from an older one are dropped.
    private int session = 0;
    private SharedPreferences prefs;

    // Views
    private EditText hostInput;
    private TextView connText, batteryText, estopText, speedText, banner, busyText, logText, demoStrip;
    private Switch armSwitch, demoSwitch;
    private Button testBtn, btnForward, btnBack, btnLeft, btnRight, btnAround, stopBtn;
    private Button[] driveButtons;
    private View demoPanel;
    private Button demoEstopBtn, demoWifiBtn, demoBlockBtn, demoBatteryBtn;

    // Robot state (main thread only)
    private boolean connected = false;
    private int failCount = 0;
    private String lastError = null;
    private String version = null;
    private Integer battery = null;
    private Integer emergencyButton = null; // 1 = released, 0 = PRESSED (per firmware)
    private double vx = 0, vth = 0;

    // Operator state
    private boolean armed = false;

    // Step-in-progress lock
    private boolean busy = false;
    private boolean busyIsTurn = false;
    private String busyLabel = "";
    private long busyStart, busyNoMotionDeadline, busyHardCap;
    private boolean sawMotion = false;
    private int stillCount = 0;

    // Polling
    private boolean running = false;
    private boolean pollInFlight = false;
    private int tick = 0;

    private final ArrayDeque<String> logLines = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        String host = prefs.getString(KEY_HOST, DEFAULT_HOST);
        liveApi = new RobotApi(host);
        fakeRobot = new FakeRobot();
        demoApi = RobotApi.demo(fakeRobot);
        // Until a mode has been chosen, an emulator starts in demo: it shares the Mac's
        // network, so on the robot's Wi-Fi it could otherwise drive the real robot.
        boolean emulatorDefault = !prefs.contains(KEY_DEMO) && isEmulator();
        demo = prefs.getBoolean(KEY_DEMO, emulatorDefault);
        api = demo ? demoApi : liveApi;

        hostInput = findViewById(R.id.hostInput);
        connText = findViewById(R.id.connText);
        batteryText = findViewById(R.id.batteryText);
        estopText = findViewById(R.id.estopText);
        speedText = findViewById(R.id.speedText);
        banner = findViewById(R.id.banner);
        busyText = findViewById(R.id.busyText);
        logText = findViewById(R.id.logText);
        armSwitch = findViewById(R.id.armSwitch);
        btnForward = findViewById(R.id.btnForward);
        btnBack = findViewById(R.id.btnBack);
        btnLeft = findViewById(R.id.btnLeft);
        btnRight = findViewById(R.id.btnRight);
        btnAround = findViewById(R.id.btnAround);
        stopBtn = findViewById(R.id.stopBtn);
        driveButtons = new Button[]{btnForward, btnBack, btnLeft, btnRight, btnAround};
        testBtn = findViewById(R.id.testBtn);
        demoSwitch = findViewById(R.id.demoSwitch);
        demoStrip = findViewById(R.id.demoStrip);
        demoPanel = findViewById(R.id.demoPanel);
        demoEstopBtn = findViewById(R.id.demoEstopBtn);
        demoWifiBtn = findViewById(R.id.demoWifiBtn);
        demoBlockBtn = findViewById(R.id.demoBlockBtn);
        demoBatteryBtn = findViewById(R.id.demoBatteryBtn);

        hostInput.setText(host);

        testBtn.setOnClickListener(v -> testConnection());

        demoSwitch.setChecked(demo);
        demoSwitch.setOnCheckedChangeListener((b, isChecked) -> setDemo(isChecked));

        demoEstopBtn.setOnClickListener(v -> {
            boolean press = !fakeRobot.isEstopPressed();
            fakeRobot.setEstopPressed(press);
            log("Simulated e-stop " + (press ? "pressed" : "released"));
            refreshUi();
        });
        demoWifiBtn.setOnClickListener(v -> {
            boolean drop = !fakeRobot.isOffline();
            fakeRobot.setOffline(drop);
            log(drop ? "Simulated Wi-Fi drop" : "Simulated Wi-Fi restored");
            refreshUi();
        });
        demoBlockBtn.setOnClickListener(v -> {
            fakeRobot.blockNextStep();
            log("Simulated obstacle: the next step will not start");
            refreshUi();
        });
        demoBatteryBtn.setOnClickListener(v -> {
            fakeRobot.drainBattery();
            log("Simulated battery now " + fakeRobot.batteryPercent() + "%");
            refreshUi();
        });
        findViewById(R.id.demoResetBtn).setOnClickListener(v -> {
            if (busy) {
                log("Wait for the current step to finish before resetting the demo robot.");
                return;
            }
            fakeRobot.reset();
            log("Demo robot reset: centre of the room, no faults");
            refreshUi();
        });

        armSwitch.setOnCheckedChangeListener((b, isChecked) -> {
            if (armed == isChecked) return;
            armed = isChecked;
            log(armed ? "Drive enabled" : "Drive disabled");
            refreshUi();
        });

        btnForward.setOnClickListener(v -> driveStraight(true));
        btnBack.setOnClickListener(v -> driveStraight(false));
        btnLeft.setOnClickListener(v -> turn(true, 90, "Left 90°"));
        btnRight.setOnClickListener(v -> turn(false, 90, "Right 90°"));
        btnAround.setOnClickListener(v -> turn(true, 180, "Turn 180°"));
        stopBtn.setOnClickListener(v -> sendStop());

        if (emulatorDefault) log("Emulator detected: starting in demo mode");
        log(demo ? "App started in DEMO MODE (simulated robot)" : "App started. Robot address " + host);
        refreshUi();
        testConnection();
    }

    private static boolean isEmulator() {
        return Build.FINGERPRINT.startsWith("generic") || Build.FINGERPRINT.contains("emulator")
                || Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish")
                || Build.PRODUCT.contains("sdk");
    }

    // ------------------------------------------------------------------ demo mode

    private void setDemo(boolean on) {
        if (on == demo) return;
        if (busy) {
            // STOP goes to the selected robot, so never switch away from one that is moving.
            demoSwitch.setChecked(demo); // re-enters with on == demo and returns
            log("Wait for the current step to finish before switching mode.");
            return;
        }
        armSwitch.setChecked(false); // a mode change always disarms
        demo = on;
        prefs.edit().putBoolean(KEY_DEMO, on).apply();
        api = on ? demoApi : liveApi;
        session++;
        resetRobotState();
        log(on ? "DEMO MODE ON: simulated robot, nothing is sent to the real robot"
                : "Demo mode OFF: live robot at " + liveApi.getHost());
        refreshUi();
        testConnection();
    }

    private void resetRobotState() {
        connected = false;
        failCount = 0;
        lastError = null;
        version = null;
        battery = null;
        emergencyButton = null;
        vx = vth = 0;
    }

    @Override
    protected void onResume() {
        super.onResume();
        running = true;
        ui.removeCallbacks(pollRunnable);
        ui.post(pollRunnable);
    }

    @Override
    protected void onPause() {
        super.onPause();
        running = false;
        ui.removeCallbacks(pollRunnable);
        // Safety: leaving the app always disarms. A step already running finishes by itself.
        armSwitch.setChecked(false);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        pollExec.shutdownNow();
        cmdExec.shutdownNow();
        stopExec.shutdownNow();
    }

    // ------------------------------------------------------------------ polling

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            if (!pollInFlight) {
                pollInFlight = true;
                final boolean withBase = (tick++ % BASE_EVERY) == 0;
                final RobotApi a = api;
                final int sess = session;
                pollExec.execute(() -> {
                    RobotApi.Result s = a.get("/reeman/speed");
                    RobotApi.Result b = withBase ? a.get("/reeman/base_encode") : null;
                    ui.post(() -> onPoll(sess, s, b));
                });
            }
            ui.postDelayed(this, POLL_MS);
        }
    };

    private void onPoll(int sess, RobotApi.Result s, RobotApi.Result b) {
        pollInFlight = false;
        if (sess != session) return; // answered by the robot we just switched away from
        boolean speedValid = false;

        if (s.ok) {
            try {
                JSONObject o = new JSONObject(s.body);
                vx = o.optDouble("vx", 0);
                vth = o.optDouble("vth", 0);
                speedValid = true;
            } catch (Exception ignored) {
                // unexpected body; keep previous values
            }
            if (!connected) log("Connected");
            connected = true;
            failCount = 0;
            lastError = null;
        } else {
            failCount++;
            lastError = s.error;
            if (connected && failCount >= FAILS_BEFORE_DISCONNECT) {
                connected = false;
                log("Lost connection: " + s.error);
            }
        }

        if (b != null && b.ok) {
            try {
                JSONObject o = new JSONObject(b.body);
                if (o.has("battery")) battery = o.getInt("battery");
                if (o.has("emergencyButton")) {
                    Integer prev = emergencyButton;
                    emergencyButton = o.getInt("emergencyButton");
                    if (prev != null && !prev.equals(emergencyButton)) {
                        log(emergencyButton == 0 ? "E-stop PRESSED" : "E-stop released");
                    }
                }
                // chargeFlag deliberately ignored: it is stale on this firmware.
            } catch (Exception ignored) {
            }
        }

        updateBusy(speedValid);
        refreshUi();
    }

    // ------------------------------------------------------------------ commands

    private void driveStraight(boolean forward) {
        double speed = Math.min(forward ? FORWARD_SPEED : BACKWARD_SPEED, MAX_LINEAR);
        String json = String.format(Locale.US,
                "{\"distance\":%d,\"direction\":%d,\"speed\":%.2f}",
                STEP_CM, forward ? 1 : 0, speed);
        long estimateMs = (long) (STEP_CM / 100.0 / speed * 1000) + RAMP_ALLOWANCE_MS;
        startStep(forward ? "Forward 0.5 m" : "Back 0.5 m", "/cmd/move", json, estimateMs, false);
    }

    private void turn(boolean left, int degrees, String label) {
        double speed = Math.min(TURN_SPEED, MAX_ANGULAR);
        String json = String.format(Locale.US,
                "{\"direction\":%d,\"angle\":%d,\"speed\":%.2f}",
                left ? 1 : 0, degrees, speed);
        long estimateMs = (long) (Math.toRadians(degrees) / speed * 1000) + RAMP_ALLOWANCE_MS;
        startStep(label, "/cmd/turn", json, estimateMs, true);
    }

    private boolean canDrive() {
        return connected && armed && !busy
                && emergencyButton != null && emergencyButton == 1;
    }

    private void startStep(String label, String path, String json, long estimateMs, boolean isTurn) {
        if (!canDrive()) return;

        // Lock immediately on the UI thread so a double-tap can't send a second step.
        long now = SystemClock.elapsedRealtime();
        busy = true;
        busyIsTurn = isTurn;
        busyLabel = label;
        busyStart = now;
        busyNoMotionDeadline = now + estimateMs + 2000;
        busyHardCap = now + estimateMs + 12000;
        sawMotion = false;
        stillCount = 0;
        log(label + ": sending " + path + " " + json);
        refreshUi();

        final RobotApi a = api;
        cmdExec.execute(() -> {
            RobotApi.Result r = a.post(path, json);
            ui.post(() -> {
                if (!r.ok) {
                    log(label + ": FAILED " + r.error);
                    finishBusy(null);
                    return;
                }
                String rejected = robotError(r.body);
                if (rejected != null) {
                    log(label + ": robot rejected: " + rejected);
                    finishBusy(null);
                    return;
                }
                log(label + ": accepted " + (r.body.isEmpty() ? "" : r.body));
            });
        });
    }

    /** The firmware reports errors as {"error":"...","error_code":"009"}. */
    private static String robotError(String body) {
        if (body == null || body.isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(body);
            if (o.has("error_code") || o.has("error")) {
                return o.optString("error_code", "?") + " " + o.optString("error", "");
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private void sendStop() {
        final boolean turnFirst = busy && busyIsTurn;
        log("STOP pressed");
        if (busy) {
            long now = SystemClock.elapsedRealtime();
            busyNoMotionDeadline = Math.min(busyNoMotionDeadline, now + 4000);
            busyHardCap = Math.min(busyHardCap, now + 6000);
        }
        final RobotApi target = api;
        stopExec.execute(() -> {
            RobotApi.Result a = turnFirst ? target.post("/cmd/turn", TURN_STOP) : target.post("/cmd/move", MOVE_STOP);
            RobotApi.Result b = turnFirst ? target.post("/cmd/move", MOVE_STOP) : target.post("/cmd/turn", TURN_STOP);
            ui.post(() -> log("STOP: " + ((a.ok || b.ok) ? "sent" : "FAILED " + a.error
                    + ". USE THE PHYSICAL E-STOP")));
        });
    }

    // ------------------------------------------------------------------ step lock

    private void updateBusy(boolean speedValid) {
        if (!busy) return;
        long now = SystemClock.elapsedRealtime();

        if (speedValid) {
            boolean moving = Math.abs(vx) > MOVING_VX || Math.abs(vth) > MOVING_VTH;
            if (moving) {
                sawMotion = true;
                stillCount = 0;
            } else if (sawMotion) {
                stillCount++;
            }
        }

        if (sawMotion && stillCount >= STILL_POLLS_TO_FINISH) {
            finishBusy(busyLabel + ": done");
        } else if (!sawMotion && now > busyNoMotionDeadline) {
            finishBusy(busyLabel + ": no motion seen. Obstacle in the way, or command ignored?");
        } else if (now > busyHardCap) {
            finishBusy(busyLabel + ": timed out waiting for the robot to stop");
        }
    }

    private void finishBusy(String message) {
        busy = false;
        if (message != null) log(message);
        refreshUi();
    }

    // ------------------------------------------------------------------ connection test

    private void testConnection() {
        if (!demo) {
            String host = hostInput.getText().toString().trim();
            if (host.isEmpty()) host = DEFAULT_HOST;
            host = host.replaceFirst("^https?://", "").replaceAll("/+$", "");
            hostInput.setText(host);
            prefs.edit().putString(KEY_HOST, host).apply();
            if (!host.equals(liveApi.getHost())) {
                session++;
                resetRobotState();
            }
            liveApi.setHost(host);
        }
        hideKeyboard();

        final RobotApi a = api;
        final int sess = session;
        log("Testing " + (demo ? "demo robot" : a.getHost()) + " …");
        pollExec.execute(() -> {
            RobotApi.Result r = a.get("/reeman/current_version");
            ui.post(() -> {
                if (sess != session) return;
                if (r.ok) {
                    try {
                        version = new JSONObject(r.body).optString("version", r.body);
                    } catch (Exception e) {
                        version = r.body;
                    }
                    log("Robot answered: firmware " + version);
                } else {
                    lastError = r.error;
                    log("Test failed: " + r.error);
                }
                refreshUi();
            });
        });
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(hostInput.getWindowToken(), 0);
        hostInput.clearFocus();
    }

    // ------------------------------------------------------------------ UI

    private void refreshUi() {
        demoStrip.setVisibility(demo ? View.VISIBLE : View.GONE);
        demoPanel.setVisibility(demo ? View.VISIBLE : View.GONE);
        hostInput.setEnabled(!demo);
        hostInput.setAlpha(demo ? 0.4f : 1f);
        demoSwitch.setEnabled(!busy);
        if (demo) {
            demoEstopBtn.setText(fakeRobot.isEstopPressed() ? "Release e-stop" : "Press e-stop");
            demoWifiBtn.setText(fakeRobot.isOffline() ? "Restore Wi-Fi" : "Drop Wi-Fi");
            demoBlockBtn.setText(fakeRobot.isBlockNextPending() ? "Obstacle set ✓" : "Block next step");
        }

        if (connected) {
            connText.setText((demo ? "● Connected to DEMO robot (simulated)" : "● Connected to " + api.getHost())
                    + (version != null && !demo ? "  ·  " + version : ""));
            connText.setTextColor(Color.parseColor("#15803D"));
        } else {
            connText.setText("○ Not connected" + (lastError != null ? ": " + lastError : ""));
            connText.setTextColor(Color.parseColor("#B91C1C"));
        }

        batteryText.setText("Battery\n" + (battery == null ? "—" : battery + "%"));

        if (emergencyButton == null) {
            estopText.setText("E-stop\n—");
            estopText.setBackgroundColor(Color.WHITE);
            estopText.setTextColor(Color.parseColor("#111827"));
        } else if (emergencyButton == 0) {
            estopText.setText("E-stop\nPRESSED");
            estopText.setBackgroundColor(Color.parseColor("#DC2626"));
            estopText.setTextColor(Color.WHITE);
        } else {
            estopText.setText("E-stop\nReleased");
            estopText.setBackgroundColor(Color.WHITE);
            estopText.setTextColor(Color.parseColor("#111827"));
        }

        if (connected) {
            speedText.setText(String.format(Locale.US, "Speed\n%.2f m/s · %.0f°/s",
                    vx, Math.toDegrees(vth)));
        } else {
            speedText.setText("Speed\n—");
        }

        String warn = null;
        String warnColor = "#B91C1C";
        if (!connected && demo) {
            warn = fakeRobot.isOffline()
                    ? "Demo robot unreachable: simulated Wi-Fi drop. Tap \"Restore Wi-Fi\" below."
                    : "Connecting to the demo robot…";
        } else if (!connected) {
            warn = "Not connected. Check the phone is on the robot's Wi-Fi (mobile data off), then tap Test.";
        } else if (emergencyButton == null) {
            warn = "Reading robot status…";
            warnColor = "#6B7280";
        } else if (emergencyButton == 0) {
            warn = "Emergency stop is PRESSED on the robot. Release it to drive.";
        } else if (!armed) {
            warn = "Drive is off. Clear the area, then switch on \"Drive enabled\".";
            warnColor = "#374151";
        }
        if (warn != null) {
            banner.setText(warn);
            banner.setBackgroundColor(Color.parseColor(warnColor));
            banner.setVisibility(View.VISIBLE);
        } else {
            banner.setVisibility(View.GONE);
        }

        if (busy) {
            busyText.setText("Moving: " + busyLabel + " …");
            busyText.setVisibility(View.VISIBLE);
        } else {
            busyText.setVisibility(View.GONE);
        }

        boolean can = canDrive();
        for (Button btn : driveButtons) {
            btn.setEnabled(can);
            btn.setAlpha(can ? 1f : 0.35f);
        }
    }

    private void log(String line) {
        logLines.addFirst(clock.format(new Date()) + "  " + (demo ? "[DEMO] " : "") + line);
        while (logLines.size() > 14) logLines.removeLast();
        StringBuilder sb = new StringBuilder();
        for (String l : logLines) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(l);
        }
        logText.setText(sb.toString());
    }
}
