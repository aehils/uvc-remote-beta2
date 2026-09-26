# Robot Control — beta 2

Android remote for the Reeman Spark robot. Fixed-step driving only: every button
sends one pre-planned step (`/cmd/move` or `/cmd/turn`), so the robot plans its own
gentle braking. The app never streams velocity (`/cmd/speed`), which is the path
that produced the hard stops (see `REEMAN_HANDOFF.txt`, sections 5–7).

Beta 2 starts from beta 0.1 (which drove the real robot successfully) and adds
**demo mode**, so the app can be developed and shown with the robot switched off.
It is written in Kotlin with a Jetpack Compose (Material 3) screen, in light and dark themes.

> The engineering handoffs (`REEMAN_HANDOFF.txt`, `REEMAN_HANDOFF_v2.txt`) are kept
> outside this repository on purpose: they describe unpatched weaknesses in the
> robot's network setup, and this repository is public.

## What it does

| Button | Command sent | Speed |
|---|---|---|
| Forward 0.5 m | `/cmd/move` `{"distance":50,"direction":1,"speed":0.30}` | 0.3 m/s |
| Back 0.5 m | `/cmd/move` `{"distance":50,"direction":0,"speed":0.20}` | 0.2 m/s |
| Left 90° | `/cmd/turn` `{"direction":1,"angle":90,"speed":0.40}` | 0.4 rad/s |
| Right 90° | `/cmd/turn` `{"direction":0,"angle":90,"speed":0.40}` | 0.4 rad/s |
| Turn 180° | `/cmd/turn` `{"direction":1,"angle":180,"speed":0.40}` | 0.4 rad/s |
| STOP | `/cmd/move` + `/cmd/turn` stop bodies | always active |

Safety behaviour:
- Drive buttons only work when: connected, e-stop released, **Drive enabled** switched on, and no step is running.
- One step at a time: buttons lock until the robot's measured speed has been ~0 for about a second.
- Leaving the app (home button, screen off, switching apps) switches Drive off.
- Speeds are capped in code (`MAX_LINEAR` 0.3 m/s, `MAX_ANGULAR` 0.5 rad/s in `DriveController.kt`).
- STOP is a hard stop. Use it for emergencies, not for routine stopping (steps end on their own).
- The physical e-stop remains the real backstop.

## Demo mode

Tap the **DEMO** pill at the top right (its dot is filled when demo mode is on) and
use the **Turn on DEMO** button in the menu. The app then talks to a simulated robot built into the
app instead of the real one:

- A purple **DEMO MODE IS ON** strip stays pinned to the top of the screen, the status line
  reads *CONNECTED*, and every log line starts with `[DEMO]`.
- **Nothing is sent over the network.** Demo requests are answered inside the app
  (`FakeRobot.kt`), so demo mode is safe to use while on the robot's Wi-Fi.
- All the real app logic runs unchanged: polling, the drive interlocks, the step lock,
  STOP. Only the robot at the other end is replaced.
- The DEMO menu's switch button is disabled (the pill still shows ON) while a step is running.
- The emulator starts in demo mode until you choose a mode yourself, because the
  emulator shares the Mac's network and could otherwise reach the real robot.
- You can't switch mode while a step is running, and switching always turns
  **Drive enabled** off.

The simulated robot mimics the real firmware: the same paths and JSON, the same
error codes (`009`, `004`), ~200 ms command latency, and the measured acceleration
profile. It sits in a 5 m × 4 m room with a box obstacle, and stops short at walls.

The **DEMO controls** page simulates events that are hard to set up on the real robot.
Open it with the purple sliders button at the top right of the DEMO menu (shown only
while DEMO is active). STOP and the robot readings stay on screen there, so you can
watch each event take effect. The first three are switches; the last two are buttons.

| Control | Simulates | What the app should do |
|---|---|---|
| E-stop pressed | physical e-stop | **E** badge turns solid red, driving blocked; pressing mid-step stops the robot |
| Wi-Fi dropped | lost connection | *NOT CONNECTED* after ~3 failed polls, driving blocked |
| Obstacle ahead | obstacle in the way | next step accepted but never starts → "no motion seen" |
| Battery −10% | draining battery | battery figure turns amber at 20%, red at 10% (wraps back to 100%) |
| Reset robot | — | robot back to the room centre, faults cleared |

### Demo mode limitations

The simulator only covers fixed-step driving. These are answered with error `004`
("Not simulated in DEMO mode"): velocity streaming (`/cmd/speed`), navigation
(`/cmd/nav`, `/cmd/nav_name`, `/cmd/cancel_goal`), charging, relocalisation, maps and
restricted layers, speed limits, mode changes and shutdown. The room, obstacle and
motion profile are a simple model, not the robot's real map or sensors.

Things the simulator does **not** know about the real robot (marked `GUESS` in
`FakeRobot.kt`): turn acceleration, what happens to a command sent with the e-stop
pressed, how a new step pre-empts a running one, the success response body, and
whether `/reeman/laser` points are in map or robot frame. Confirm these on the real
robot before relying on them.

## Build and install (Android Studio)

1. **Open the project:** Android Studio → *Open* → select this repository's folder (the one containing `settings.gradle`) → *Trust Project*.
2. **Wait for sync.** The first sync downloads Gradle and the Android build tools (several minutes; bottom status bar shows progress).
   - If it offers to install a missing SDK platform (Android 15 / API 35), accept.
   - If it offers to upgrade the Android Gradle Plugin, you can skip it for now.
3. **Prepare the phone (once):**
   - Settings → About phone → tap **Build number** 7 times → Developer options unlocked.
   - Settings → System → Developer options → turn on **USB debugging**.
   - Plug the phone into the Mac and tap **Allow** on the phone's prompt.
4. **Run:** pick the phone in the device dropdown at the top of Android Studio, press the green ▶ **Run**. The app installs and opens.

To get an installable file instead: *Build → Build App Bundle(s) / APK(s) → Build APK(s)* → click **locate** in the notification.
The file is `app/build/outputs/apk/debug/app-debug.apk`. Send it to any Android phone and open it (allow "install unknown apps").

From a terminal (needs a JDK 17–21, e.g. `export JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home`):

```bash
./gradlew testDebugUnitTest assembleDebug
```

The unit tests (`app/src/test/`) run in virtual time against the simulated robot. They cover
the simulator itself and the drive rules end to end: interlocks, the step lock, STOP,
e-stop and Wi-Fi loss.

## Network

- Phone must be on the same Wi-Fi as the robot (192.168.1.x). Turn **mobile data off** while testing.
- Quick check: open `http://192.168.1.228` in the phone's Chrome. If the robot console loads, the app can reach it.
- Plain http is allowed **only** for `192.168.1.228` (`app/src/main/res/xml/network_security_config.xml`).
  If the robot's address changes, edit that file and rebuild; better, have IT reserve the address on the router.

## First drive test (real robot)

1. Robot off the charging dock, clear floor ~1 m on every side, you can reach the physical e-stop.
2. Open the app → status should read *CONNECTED*, battery shown, grey **E** badge (e-stop released).
3. Switch on **Drive enabled** → tap **Left 90°** → confirm it turns left and the buttons unlock after it stops.
4. Then Right 90°, Forward 0.5 m, Back 0.5 m, Turn 180°.
5. Test STOP once mid-turn at the slow speed.

Note anything odd from the **Activity** log at the bottom of the screen (long-press to select and copy).

### Still to confirm on the real robot (from beta 0.1)

STOP pressed mid-step, the e-stop-pressed banner, behaviour when Wi-Fi drops, and
low battery. These can now be rehearsed in demo mode first.

## Files

All under `app/src/main/java/com/expiation/reemanremote/`:

- `DriveController.kt`: **every drive rule lives here**: polling, interlocks, the step lock, STOP, demo switching.
  Plain Kotlin (no Android), with the state as explicit types: `Link` (Disconnected / Connected + e-stop)
  and `StepPhase` (Idle / Stepping / Stopping).
- `RobotApi.kt`: HTTP calls to the robot (or to the simulator in demo mode)
- `FakeRobot.kt`: the simulated robot used by demo mode
- `RemoteViewModel.kt`: keeps the controller alive across screen recreation; saves the address and demo choice
- `MainActivity.kt`: hosts the screen; leaving the app pauses polling and switches Drive off
- `ui/RemoteScreen.kt`: the Compose screen. It only draws `RemoteState` and forwards taps.
  Open it in Android Studio's Split/Design view to see the previews.
- `ui/Theme.kt`: colours for light and dark themes, including the status colours

Also: `app/src/main/res/xml/network_security_config.xml` holds the http exception for the robot's address.
Tests are in `app/src/test/java/com/expiation/reemanremote/`.
