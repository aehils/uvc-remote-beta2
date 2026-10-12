# Robot Control — beta 2

Android remote for the Reeman Spark robot. Every motion is a pre-planned step
(`/cmd/move` or `/cmd/turn`), so the robot plans its own gentle braking, except
holding a d-pad arrow. That streams velocity (`/cmd/speed`), the path that produced the
hard stops (see `REEMAN_HANDOFF.txt`, sections 5–7), so the app ramps the speed itself,
up and down, in small steps (see **Hold-to-drive**).

Beta 2 starts from beta 0.1 (which drove the real robot successfully) and adds
**demo mode**, so the app can be developed and shown with the robot switched off.
It is written in Kotlin with a Jetpack Compose (Material 3) screen, in light and dark themes.

> The engineering handoffs (`REEMAN_HANDOFF.txt`, `REEMAN_HANDOFF_v2.txt`) are kept
> outside this repository on purpose: they describe unpatched weaknesses in the
> robot's network setup, and this repository is public.

## What it does

The screen has four tabs: **Remote** (the drive pad and the robot address),
**Tasks** (a list of tasks: **Disinfection Points** opens its own page), **Map** (a placeholder, not in this
build yet), and **Activity** (the log). The robot's readings (e-stop badge, speed, battery) are pinned
under the title on every tab, and **STOP is the red button in the centre of the nav bar**,
so it is on screen on every tab and on the DEMO controls page.

| Button | Command sent | Speed |
|---|---|---|
| Forward (hold) | `/cmd/speed` `{"vx":…,"vth":0}` every 50 ms while held, ramping 0 → 0.3 m/s | 0.3 m/s |
| Back (hold) | `/cmd/speed` ramping 0 → −0.2 m/s | 0.2 m/s |
| Left / Right (hold) | `/cmd/speed` `{"vx":0,"vth":…}` ramping 0 → ±0.4 rad/s | 0.4 rad/s |
| Letting go of an arrow | `/cmd/speed` ramping back down to 0, in 0.02 m/s (0.04 rad/s) steps, then one more 0 | — |
| Turn 180° (tap) | `/cmd/turn` `{"direction":1,"angle":180,"speed":0.40}` | 0.4 rad/s |
| GO TO CHARGER (Tasks tab) | `/reeman/position` + `/reeman/pose`, then `/cmd/charge` `{"type":0,…}` (pile close) or `{"type":2,…}` (pile far) | set by the firmware |
| GO (Disinfection Points page) | `/cmd/nav_name` `{"point":"<name>"}` | set by the firmware |
| START DISINFECTION (Disinfection Points page) | after the start delay, `/cmd/external_power_supply` `{"type":"24v","operation":"1"}`; at the end `"operation":"0"` | doesn't move the robot |
| Add point where the robot is | `/reeman/pose`, then `/cmd/position` `{"name":"<name>","type":"delivery","pose":{"x","y","theta"}}` | doesn't move the robot |
| STOP | stop body, `/cmd/cancel_goal`, `/cmd/charge` `{"type":1}`, the other stop body: every time | always active |

**GO TO CHARGER** (Tasks tab) gets the robot onto the charging pile from anywhere. It first
measures, on the map, how far the robot is from the pile's saved point (`charging_pile` in
`/reeman/position`, against `/reeman/pose`):
- **Within 1.5 m:** it docks straight away with the robot's own docking routine
  (`/cmd/charge` type 0), which finds the pile and reverses onto it. If that finds no pile
  (the robot never moves, or reports "no charging pile found"), it drives there instead.
- **Further, or either position unknown:** it drives to the pile on the robot's own route,
  then docks (`/cmd/charge` type 2). The lock holds while the robot has a plan, and allows
  ~5 s of stillness between arriving and docking.

Docking is never tried from far away, since what the real routine does then is unknown.
1.5 m is a guess (`DOCK_NEAR_M`): the routine's real range was never measured. It has the
same rules as the drive buttons. STOP while
it is still measuring sends nothing. When it finishes, the log says what the robot reports
(charging, or no pile found). That reading is normally stale, but the docking motion
refreshes it. Docking speed is the firmware's, not the app's caps below.

**Tasks → Disinfection Points.** Tap **Disinfection Points** on the Tasks tab to open its
page (STOP and the robot's readings stay on screen there). It lists the points saved on the
robot's map (`/reeman/position`), except the charging pile, which has GO TO CHARGER. **GO**
sends the robot there with `/cmd/nav_name`: the robot plans its own route and speed. It only
drives there; **START DISINFECTION** (below) switches on the UV lamps. It has the same rules as
the drive buttons.
During a trip the app also reads `/reeman/global_plan` and `/reeman/pose` every ~1.5 s:
- The robot can stop on its way (waiting for a person to pass), so being still only ends
  the trip once the robot reports no plan (`007`). Until then the buttons stay locked.
  STOP still works at any time and ends the trip.
- When the trip ends, the log says how far from the point the robot stopped: "arrived"
  within 0.3 m, otherwise "stopped X m from the point".
- Points are read when the page opens (and with **Refresh**). Switching between DEMO and
  live forgets them.

**Add point where the robot is** saves the robot's current spot, and the way it faces, as a
new point: the app reads `/reeman/pose`, then sends `/cmd/position` (as the Web API says to)
and reloads the list. A robot sent there later turns to face the same way. The robot must be
connected and standing still, with no step running. Names must be new: setting an existing name would move that point.
Points are renamed or deleted in the robot's own app.

**UV disinfection** is the card at the top of the Disinfection Points page. **START
DISINFECTION** asks you to confirm the room is clear, counts down the start delay (time to
leave the room), switches the UV lamps on for the set time, then off. **START NOW** (next to
CANCEL during the countdown) skips the rest of the countdown when the room is already clear. It disinfects where the
robot stands. The card says which point that is: the one the robot arrived at (within 0.3 m),
or the one it is found standing on when the points are read; otherwise "isn't at a saved
point". The **tune** button sets the time (1–30 min) and the start delay (15, 30 or 60 s;
default 15 s, and there is no 0 s since START NOW does that); they are saved on the phone. While the lamps are on, or about to come on, a red banner with the
time left is pinned on every page, and the drive buttons, GO and GO TO CHARGER are locked.

The lamps are switched with the power board's external supply (`/cmd/external_power_supply`,
from the Web API), now the **24 V** one. On the first live test (2026-10-06) the lamps did not
come on with the 36 V supply (the manual's Debug page names a "36V external supply"); that they
are on 24 V is still a GUESS until a test shows them light. The robot
can't report whether that supply is on, so the timer is the app's, and the app switches the
lamps off (or cancels the countdown) on any of:
- the set time running out, **LAMPS OFF** / **CANCEL** on the card, or **STOP**;
- the e-stop pressed, the robot moving (on its own or pushed), or the connection lost;
- leaving the app (home button, screen off, switching apps), since the timer can't run in the
  background.

OFF is retried every 1.5 s until the robot accepts it. Until then the banner reads **UV LAMPS
MAY STILL BE ON**, everything that moves the robot stays locked, and the app won't switch
robot or mode (OFF has to go to the same robot). ON and OFF are sent one at a time, in order,
so an OFF can't overtake an ON still in flight. STOP sends OFF only while this app has the
lamps on (or owes an OFF), not every time like `/cmd/cancel_goal`, until the 24 V guess is
confirmed. See "First UV test" below.

This relies on the loaded map matching the building (the robot's map was replaced after
beta 0.1, whose map was from another site). Still to confirm on the real robot (marked
`GUESS` in the code): what `/reeman/global_plan` returns during a trip (the Web API documents
`{"coordinates":[...]}`), which point `type` the robot's own app uses (new points are saved
as `delivery`), the reply to an unknown point, and that `/cmd/cancel_goal` stops a trip.

**STOP ends any task, whoever started it.** On 2026-10-05 the robot set off to charge by
itself; STOP on the phone stopped it, but it carried on right after. The stop bodies only
pause a navigation, and STOP used to cancel only tasks this app had started. Now every STOP:
- brakes, then cancels any navigation (`/cmd/cancel_goal`) and any docking (`/cmd/charge`
  type 1), even if the app didn't start them;
- then watches the robot for 10 s. If it moves again (or never stops), STOP is sent again,
  every 1.5 s while it moves. After 3 tries a red **ROBOT STILL MOVING AFTER STOP. USE THE
  PHYSICAL E-STOP** banner appears (and STOP keeps being sent). Sending a new command
  yourself ends the watch.

The log also says when the robot moves without the app having asked ("Robot is moving, but
not by this app"), STOP pulses whenever the robot moves, and the drive buttons stay locked
until it is still. Still to confirm on the real robot: that `/cmd/charge` type 1 does nothing
while the robot sits charging on the pile (STOP now sends it every time).

## Hold-to-drive

The four arrows drive for as long as they are held. Let go and the robot eases to a stop in
under a second: about 20 cm from 0.3 m/s, about 10° turning. Turn 180° in the middle is still
a single fixed step.

**How.** While an arrow is held, the app sends `/cmd/speed` every 50 ms. The speed climbs from
0 to the arrow's speed in small steps (0.02 m/s, or 0.04 rad/s, per command: 0.4 m/s², or
0.8 rad/s²), and on letting go it falls back to 0 the same way. The robot applies each speed
at once, with no smoothing of its own (the jog test: 0 to 0.35 m/s in 20 ms), so this ramp is
what keeps the motion smooth: the app never jumps the speed while the robot moves. The ramp
moves on only when the robot accepts a command, so a slow or lost request delays it rather
than making a bigger step. STOP is the exception: an instant 0, for emergencies.

**Why not the alternatives.**
- *A bare 0 on letting go, or just stopping the commands:* both hard stops (7–20 m/s² and
  5.6 m/s², handoff section 6), the second after ~1.2 s more at full speed.
- *Halving the speed every tick:* the biggest step is the first one, at the moment of letting
  go (from 0.3 m/s, a 0.15 m/s drop at once: 3–7 m/s²). A straight-line ramp spreads it evenly.
- *Planned steps sent again and again while held* (tried 2026-10-07, dropped 2026-10-08): the
  robot restarts a step from rest each time a new one arrives, so it shuffled along, and only
  sped up on the last step, after letting go. Slow to answer and hard to predict.

**The weak spot: the network.** The ramp only works while commands get through. If they stop
mid-drive (Wi-Fi lost, phone frozen or switched off), the robot carries on at its last speed
for ~1.2 s, then its watchdog stops it **hard**. Nothing in the app can soften that; the speed
caps (0.3 m/s, 0.2 back) keep it small. The jog test found ~0.5 m/s "jerks noticeably" but
does not threaten toppling. Drive with good Wi-Fi, and keep the physical e-stop in reach.

**The rules:**
- A hold starts only on a fresh press, with the same interlock as the other drive buttons.
  While one arrow is held, the others do nothing. While the robot is still slowing down after
  letting go, pressing any arrow carries on from the current speed (pressing Back after
  Forward ramps through zero).
- The screen reports the held button every 0.1 s. If it goes quiet for 0.4 s (screen frozen,
  button gone mid-press), the app treats the arrow as let go. Sliding your finger off the
  button does not: only lifting it lets go (a resting thumb drifts, and the page around the pad
  scrolls; on the first real test that drift ended every hold after a moment).
- The hold ends by itself (the speed ramps down, and you must let go and press again) on:
  STOP, the e-stop, lost connection, three failed commands in a row, the robot stopping by
  itself while held (an obstacle: the app never keeps pushing into one, and drops to 0 at once
  since the robot has stopped already), or 30 s of holding.
- If the robot is still moving 5 s after letting go, the app sends STOP.
- STOP while holding sends 0 first, and again once any speed command still on its way has
  landed, so a late one can't set the robot off again.

Safety behaviour:
- Drive buttons (and GO, GO TO CHARGER and START DISINFECTION) only work when: connected, e-stop released, no step running, the robot standing still, and the UV lamps off.
- The **Ping** icon next to the address is the connection light: green when connected, orange when not.
- One step at a time: buttons lock until the robot's measured speed has been ~0 for about a second (after letting go of an arrow, too).
- STOP pulses with a red ring while a step is running.
- Speeds are capped in code (`MAX_LINEAR` 0.3 m/s, `MAX_ANGULAR` 0.5 rad/s in `DriveController.kt`).
- STOP is a hard stop. Use it for emergencies, not for routine stopping (steps end on their own; to stop driving, let go of the arrow).
- The UV lamps go off at the end of the set time, on STOP, the e-stop, the robot moving, lost connection, or leaving the app.
- The physical e-stop remains the real backstop.

## Demo mode

Tap the **DEMO** pill at the top right (its dot is filled when demo mode is on) and
use the **Turn on DEMO** button in the menu. The app then talks to a simulated robot built into the
app instead of the real one:

- A purple **DEMO MODE IS ON** strip stays pinned to the top of the screen, the Ping icon
  turns green, and every log line starts with `[DEMO]`.
- **Nothing is sent over the network.** Demo requests are answered inside the app
  (`FakeRobot.kt`), so demo mode is safe to use while on the robot's Wi-Fi.
- All the real app logic runs unchanged: polling, the drive interlocks, the step lock,
  STOP. Only the robot at the other end is replaced.
- The DEMO menu's switch button is disabled (the pill still shows ON) while a step is running.
- The emulator starts in demo mode until you choose a mode yourself, because the
  emulator shares the Mac's network and could otherwise reach the real robot.
- You can't switch mode while a step is running.

The simulated robot mimics the real firmware: the same paths and JSON, the same
error codes (`009`, `004`), ~200 ms command latency, and the measured acceleration
profile. It sits in a 5 m × 4 m room with a box obstacle, and stops short at walls.

The **DEMO controls** page simulates events that are hard to set up on the real robot.
Open it with the purple sliders button at the top right of the DEMO menu (shown only
while DEMO is active). STOP and the robot readings stay on screen there, so you can
watch each event take effect. The first three are switches; the rest are buttons.

| Control | Simulates | What the app should do |
|---|---|---|
| E-stop pressed | physical e-stop | **E** badge turns solid red, driving blocked; pressing mid-step stops the robot |
| Wi-Fi dropped | lost connection | Ping icon turns orange after ~3 failed polls, driving blocked |
| Obstacle ahead | obstacle in the way | next step accepted but never starts → "no motion seen" |
| Robot goes to charge by itself | the robot starting a trip on its own (only while it is still) | "moving, but not by this app" in the log; STOP ends the trip for good |
| Person in the way | a trip held up on its way (only during a trip) | robot waits 5 s then carries on; the trip stays locked the whole time |
| Battery −10% | draining battery | battery figure turns amber at 20%, red at 10% (wraps back to 100%) |
| Reset robot | — | robot back to the room centre, faults cleared, added points removed |

### Demo mode limitations

The simulator covers fixed-step driving, velocity streaming (`/cmd/speed`, with its ~200 ms
latency, no ramp of its own, and the ~1.16 s watchdog), docking, trips to saved points and to the pile
(`/cmd/charge` type 2), and the external power supplies (the DEMO controls page shows whether
the simulated UV lamps are on). As on the real robot, a stop body only pauses a trip for ~1 s;
`/cmd/cancel_goal` or `/cmd/charge` type 1 ends it. Its charging pile is against the
middle of the west wall, right behind the start position, and it can be seen from 2.5 m
(a guess). The start is 2.15 m from the pile, so GO TO CHARGER from there drives first;
two steps back bring it within 1.5 m, and it docks straight away. On the pile the battery climbs 1% every 2 s. Its room has three saved points
(Bed 1, Bed 2, Sink area) and the pile; points you add are kept until Reset robot. A trip turns to face the point, drives a straight
line and turns to the point's heading. It does not plan around obstacles: a wall or the box
in the way stops it short. These are answered with error `004`
("Not simulated in DEMO mode"): navigation to
coordinates (`/cmd/nav`), relocalisation, maps and
restricted layers, speed limits, mode changes and shutdown. The room, obstacle and
motion profile are a simple model, not the robot's real map or sensors.

Things the simulator does **not** know about the real robot (marked `GUESS` in
`FakeRobot.kt`): turn acceleration, what happens to a command sent with the e-stop
pressed, whether `/cmd/speed` cancels a step or a navigation, and whether the stop bodies end a
`/cmd/speed` drive (both assumed yes), the success response body, and
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
e-stop, Wi-Fi loss, hold-to-drive (the speed only ever changes at the ramp's rate, both
ways), docking, trips to points and to the pile, adding points, and STOP ending
a task the robot started by itself.

## Network

- Phone must be on the same Wi-Fi as the robot (192.168.1.x). Turn **mobile data off** while testing.
- Quick check: open `http://192.168.1.228` in the phone's Chrome. If the robot console loads, the app can reach it.
- Plain http is allowed **only** for `192.168.1.228` (`app/src/main/res/xml/network_security_config.xml`).
  If the robot's address changes, edit that file and rebuild; better, have IT reserve the address on the router.

## First drive test (real robot)

1. Robot off the charging dock, clear floor ~1 m on every side, you can reach the physical e-stop.
2. Open the app → the Ping icon should be green, battery shown, grey **E** badge (e-stop released).
3. **Hold** Left for 2 s, let go → it should start turning within about half a second, then
   ease to a stop within ~10°. Then Right, Back, Forward the same way, and Turn 180° (a tap).
4. Hold Forward, then let go: note how far it runs on (should be ~20 cm) and whether the
   mast rocks, starting or stopping.
5. Test STOP once while holding Left (a hard stop, at the slow turning speed).

Note anything odd from the **Activity** tab (long-press to select and copy).

## First UV test (real robot)

Which supply the lamps are on is still a guess: 36 V was tried first (2026-10-06) and the lamps
did not come on; the app now uses 24 V. UV-C burns skin and eyes. Plan this test before running it:

1. Nobody in the room, door closed and signed. Watch from outside (through a window, or with
   the lamps shielded), or have the lamps disconnected for the first try and check the 24 V
   supply with a meter instead. Wear the manual's PPE if anyone has to be near.
2. Set the time to **1 min** and the delay to **30 s** (tune button), then **START
   DISINFECTION** and leave.
3. Confirm the lamps come on after the delay, and off after 1 minute. Check the log shows
   "robot accepted OFF".
4. Repeat, pressing **STOP** while the lamps are on. Then once more, pressing the physical
   e-stop: note whether the e-stop alone cuts the lamps.
5. If the lamps don't come on, the 24 V guess is wrong too: try `24v36` (both supplies) in
   `UV_SUPPLY` in `DriveController.kt` and repeat from 1, or check the lamp wiring.
6. Once they work, try **START NOW** during the countdown: the lamps should come on straight away.

### Still to confirm on the real robot (from beta 0.1)

STOP pressed mid-step, the e-stop-pressed banner, behaviour when Wi-Fi drops, and
low battery. These can now be rehearsed in demo mode first.

## Files

All under `app/src/main/java/com/expiation/reemanremote/`:

- `DriveController.kt`: **every drive rule lives here**: polling, interlocks, the step lock, hold-to-drive, STOP, trips to points, UV disinfection, demo switching.
  Plain Kotlin (no Android), with the state as explicit types: `Link` (Disconnected / Connected + e-stop),
  `StepPhase` (Idle / Holding / Stepping / Stopping) and `Uv` (Countdown / Starting / On).
- `RobotApi.kt`: HTTP calls to the robot (or to the simulator in demo mode)
- `FakeRobot.kt`: the simulated robot used by demo mode
- `RemoteViewModel.kt`: keeps the controller alive across screen recreation; saves the address, demo choice and disinfection settings
- `MainActivity.kt`: hosts the screen; leaving the app pauses polling and switches the UV lamps off
- `ui/RemoteScreen.kt`: the Compose screen. It only draws `RemoteState` and forwards taps.
  Open it in Android Studio's Split/Design view to see the previews.
- `ui/Theme.kt`: colours for light and dark themes, including the status colours

Also: `app/src/main/res/xml/network_security_config.xml` holds the http exception for the robot's address.
Tests are in `app/src/test/java/com/expiation/reemanremote/`.
