# Robot Control — beta 2

Android remote for the Reeman Spark UVC robot: drive it, map its surroundings, send it to saved
points or the charger, and run UV disinfection. Kotlin, Jetpack Compose (Material 3), light and dark themes.
**Demo mode** runs the whole app against a simulated robot, so it can be developed and shown
with the robot switched off.

> The engineering handoffs (`REEMAN_HANDOFF.txt`, `REEMAN_HANDOFF_v2.txt`) are kept outside
> this repository on purpose: they describe unpatched weaknesses in the robot's network setup,
> and this repository is public.

## What it does

Two tabs, **Tasks** (GO TO CHARGER, the UV switch, the saved **disinfection tasks**, and
**Disinfection Points**, which opens its own page; the app opens here) and **Settings** (below),
and the **Map** page (the robot on its map, and the drive pad). A small pastel-blue **BETA**
sits centred at the very top of every page (the purple DEMO band under it in DEMO). The main page has no
title bar: the robot's readings (the e-stop badge and the battery ring, then the speed; on the Map
page, the map in use, whose name opens the **Maps** page) are the top of the page, pinned there on
every page, and **STOP** is the red key in the middle of the dock's bar at the bottom (a full-width
bar on the other pages). It is the only solid fill in the dock, and never narrower than 72dp.

The page is a step darker than its cards (in the dark theme the cards are a step lighter than the
page), so where each card ends reads at a glance.

**The readings.** The **E** badge is the e-stop: solid red when pressed, grey otherwise (never
green: released isn't a "go"). It also shows motion, since a pressed e-stop and a moving robot
can't happen together: while a command runs (a step, a held arrow, a trip, docking) its border
pulses quickly in amber, and if the robot moves with none running (pushed, or a task of its own)
the border stays amber. The **battery** is a ring the same size: the figure in the middle, and a
green arc from the top, clockwise, that drains with the charge; orange at 20% and below, red at 10%.

**The connection** is set into the line under the readings, on every page: the rule runs from the
left edge to it and on to the right. In small capitals, CONNECTED in green, or CONNECTING …,
SEARCHING … or NOT CONNECTED in orange: the colour says it. The robot's name, with a green or
orange dot, is first in Settings. Not connected, the readings and the controls
are greyed out too. Tapping the line opens Settings, where the robot is found, picked or its
address typed in.

**The dock.** On the left, in a container of its own, **Map** opens the Map page; then the bar:
**Tasks**, **STOP**, **Settings**. Every button is the same size and shape, and none is filled but
STOP. The open tab has an accent bar on the dock's top edge above it, the edge toward the page
(nearly the button's width, its lower corners rounded like a tab's), and its icon turns from
outline to filled, its label bold, both in the accent. The Map page has no tabs and no edge (the
bar is part of the card): there Drive shows it's on by its filled icon and accent label, and by
the pad itself, rising above it.
On the Map page **Map** becomes **Exit**, in orange, an arrow leaving a door to the left (back to
the tab it was opened from; Tasks unless you were on Settings, and the phone's back does the same).
The page is one piece: the map card (the map, and the pad when it's open) runs straight down into
the bar, with the bottom left cut out for Exit, so the card looks laid over the bar that was there.
The bar and Exit stay exactly where the dock is on the tabs. Either side of STOP the bar carries the
map's own buttons, one each side so STOP stays in the middle:

| On the Map page | Left of STOP | Right of STOP |
|---|---|---|
| Looking (and mapping) | **Drive** (the pad, under the map; its icon filled while it's open) | **Edit** (menu) |
| Setting a position, drawing or removing a wall | **Cancel** | **Set here**, **Add wall** or **Remove** |

While mapping, **Save** and **Discard** are on the map's *Scanning* badge.

**Driving from anywhere.** There is one drive pad. On the Map page the dock's **Drive** button puts
it under the map; elsewhere (the Points, Maps, DEMO and Settings pages) a **DRIVE**
handle sits just above the dock or STOP: tap it, or flick it up, to pull the pad up under whatever
is on screen, and tap or flick down to put it away. Only the handle takes that gesture, so a held
arrow keeps its press. Closed, the handle still says what the robot is doing. The Tasks and
Settings tabs have no pad. Starting a map pulls it up by itself.

**Settings** is grouped by subject, each row opening a page or a dialog:
- **Robot**, first: the robot the app talks to (its name, like `UV 3-07`, or DEMO) with a connection
  light, green answering, orange not. While it isn't answering: the robots the app found, to
  pick from, **Find robots**, and an address entry (folded away). Then **Robot information**: its
  full ID, address and firmware; battery, charger and e-stop; mode, map and points.
- **Records: Activity log** (its row shows the latest line). The log as a page of its own,
  newest first, selectable for copying.
- **Disinfection: Disinfection timing**, the UV time and the exit delay (as the tune button on the
  Disinfection Points page).
- **DEMO**: the **DEMO mode** switch, and, in DEMO, **DEMO controls**.
- **Mapping: Maps**, the Maps page.

While the robot isn't answering, the app looks for it and follows it to a new address by itself
(see Network). Staff pick robots by name, in **Settings › Robot**.

| Button | Command sent | Speed |
|---|---|---|
| Forward / Back (hold) | `/cmd/speed` `{"vx":…,"vth":0}` every 50 ms while held, ramping up and back down | 0.3 / 0.2 m/s |
| Left / Right (hold) | `/cmd/speed` `{"vx":0,"vth":…}`, the same way | 0.4 rad/s |
| An arrow, tapped | a zero `/cmd/speed`, then `/cmd/move` 5 cm or `/cmd/turn` 5° | 0.15 m/s / 0.3 rad/s |
| Turn 180° (tap) | `/cmd/turn` `{"direction":1,"angle":180,"speed":0.40}` | 0.4 rad/s |
| GO TO CHARGER | `/reeman/position` + `/reeman/pose`, then `/cmd/charge` type 0 (pile close) or type 2 (pile far) | the firmware's |
| GO (a saved point) | `/cmd/nav_name` `{"point":"<name>"}` | the firmware's |
| Add point where the robot is | `/reeman/pose`, then `/cmd/position` `{"name","type":"delivery","pose":{x,y,theta}}` | doesn't move |
| START DISINFECTION | after the start delay, `/cmd/external_power_supply` `{"type":"24v","operation":"1"}`; at the end `"0"` | doesn't move |
| START (a task) | per stop: `/cmd/nav_name`, then the lamps as START DISINFECTION; OFF accepted before the next trip | the firmware's |
| New map / Extend this map | `/cmd/set_mode` `{"mode":1}` / `{"mode":3}` | doesn't move; you drive |
| Save map | `/cmd/save_map`, `/cmd/set_mode` `{"mode":2}`, then `/cmd/rename_map` with the name | doesn't move |
| Discard | `/cmd/set_mode` `{"mode":2}` | doesn't move |
| Use this map / Rename / Delete / Undo | `/cmd/apply_map`, `/cmd/rename_map`, `/cmd/trash_map`, `/cmd/restore_map` | doesn't move |
| Set position | `/cmd/reloc_pose` `{"x","y","theta"}` | doesn't move |
| STOP | stop body, `/cmd/cancel_goal`, `/cmd/charge` type 1 (not while charging on the pile), the other stop body, UV lamps OFF | always active |

## Safety rules

- **The drive interlock.** Everything that moves the robot (the pad, GO, GO TO CHARGER) and
  START DISINFECTION only works when: connected, e-stop released, nothing running, the robot
  standing still, and the UV lamps off. Speeds are capped in code (`MAX_LINEAR` 0.3 m/s,
  `MAX_ANGULAR` 0.5 rad/s in `DriveController.kt`).
- **One thing at a time.** After a command the buttons lock until the robot's measured speed
  has been ~0 for about a second (or, on a trip, until the robot has no route left).
- **STOP ends everything, whoever started it.** On 2026-10-05 the robot set off to charge by
  itself; STOP's stop bodies only paused that trip. So every STOP brakes, cancels any
  navigation and docking, and switches the UV lamps off, then watches the robot for 10 s and
  sends STOP again every 1.5 s if it moves. After 3 tries a red **USE THE PHYSICAL E-STOP**
  banner appears. STOP is a hard stop: for emergencies. Steps end on their own; to stop
  driving, let go of the arrow. **STOP never ends charging:** while the robot sits still,
  charging on its pile, the docking cancel isn't sent (on the pile it ends charging; UNDOCK uses
  it for that). A dock under way, or a trip to the charger the robot started itself, is still
  cancelled.
- **On the charging pile the d-pad is locked** (greyed out, and the pad's corner says *On the
  charger. Undock to drive*); its middle is **UNDOCK**, which ends charging, pauses, then pulls
  15 cm forward off the pile. "On the pile" is latched when the robot reports charging there, and
  only cleared by proof it left: UNDOCK's pull-away moving it, UNDOCK finding it far from the pile
  on the map, or a docking reading. Charging ending alone (UNDOCK's first command, a full
  battery) never unlocks the arrows. In late October 2026 an arrow pressed by accident on the
  pile drove the robot off it; the likely cause is the old rule, which only counted the robot
  as docked while it was charging (and STOP then ended charging).
- **A task owns the robot while it runs.** GO, GO TO CHARGER, START DISINFECTION and the UV
  switch's ON are locked; STOP, the d-pad (only usable while the task waits, lamps off) and
  switching the lamps off are not. Switching the lamps off, STOP and the e-stop all end the task.
- **Mapping is its own job.** While the robot scans a map, GO, GO TO CHARGER, START
  DISINFECTION and adding points are locked (the Map page's readings say "Mapping", and the Tasks
  tab says why); the pad and STOP still work. Map commands never move the robot, but they need it connected,
  standing still, the lamps off, and one at a time. The map in use can't be deleted.
- **The log notes motion the app didn't ask for** ("Robot is moving, but not by this app"),
  and STOP pulses while anything moves.
- **The physical e-stop is the real backstop.**

## Hold-to-drive

The arrows drive while held. Let go and the robot stops within about a third of a second
(~10 cm from 0.3 m/s, ~7° turning). A **tap** (let go within 0.25 s) is a **nudge**: 5 cm, or 5°
turning, every time.

**How.** While an arrow is held the app sends `/cmd/speed` every 50 ms. From rest the first
command is 0.05 m/s (0.1 rad/s): less barely turns the wheels and only delays the start. Then it
speeds up 0.02 m/s (0.04 rad/s) per command, a 0.4 m/s² (0.8 rad/s²) ramp. Letting go slows it
twice as fast (0.8 m/s², 1.6 rad/s²) and, once at 0.05 m/s (0.1 rad/s) or less, straight to 0.
On 2026-10-14 the old, symmetric ramp made short presses run on well past where the finger
lifted: every command reaches the wheels ~0.2 s late, so most of a short press's movement came
after it.

**Taps.** A tap's speed is still tiny when the finger lifts, so instead of a ramp it gets a
zero, then a planned 5 cm move (or 5° turn) that the robot measures itself (`NUDGE_CM`,
`NUDGE_DEG` in `DriveController.kt`). Pressing again during a nudge takes over at once. The robot applies each speed at once, with no ramp of its own, so the app's ramp is
what keeps it smooth. The ramp only moves on when the robot accepts a command, so a slow request
delays it rather than making a bigger step. STOP is the exception: an instant 0.

**Why not the alternatives.** A bare 0 on release brakes at 7–20 m/s²; just stopping the
commands runs on ~1.2 s, then the robot's watchdog stops it at 5.6 m/s². Re-sending planned
`/cmd/move` steps while held (tried 2026-10-08) made the robot restart from rest each time, so
it shuffled.

**The weak spot is the network.** If commands stop mid-drive (Wi-Fi lost, phone frozen), the
robot carries on ~1.2 s, then stops hard. The speed caps keep that small. Drive on good Wi-Fi.

**The rules.**
- Only lifting the finger lets go. Sliding off the button doesn't (a resting thumb drifts, and
  on the first real test the scrolling page took the touch over).
- The screen reports the held button every 0.1 s; 0.4 s of silence counts as let go.
- A hold ends by itself (ramping down; press again to carry on) on: STOP, the e-stop, lost
  connection, 3 failed commands in a row, the robot stopping by itself (an obstacle: the app
  drops to 0 at once and never keeps pushing), or 30 s of holding.
- Pressing an arrow while the robot is still slowing carries on from the current speed.
- Still moving 5 s after letting go: the app sends STOP.

## Tasks

**GO TO CHARGER** measures, on the map, how far the robot is from the pile's saved point
(`charging_pile` in `/reeman/position`). Within 1.5 m (`DOCK_NEAR_M`) it docks with the
robot's own routine (`/cmd/charge` type 0), falling back to driving there if that finds no
pile. Further away, or if either position is unknown, it drives there and docks (type 2).
Docking is never tried from far away. When it finishes, the log says what the robot reports.
The pile is found by name or by its type (`charge`), and the command uses the name it has on the
map in use, since the robot's own app may not call it `charging_pile`. On a map with no pile,
nothing is sent and the log says to mark one.

**Disinfection Points** lists the robot's saved points (except the pile). **GO** sends the robot
to one with `/cmd/nav_name`; it plans its own route and speed. During a trip the app also reads
`/reeman/global_plan` and `/reeman/pose`: the robot may pause on its way (a person passing), so
the trip only ends once the robot reports no plan (`007`), and the log then says how far from
the point it stopped ("arrived" within 0.3 m). **Add point where the robot is** saves the
robot's spot and heading as a new point; names must be new, since saving an existing name would
move that point. Rename or delete points in the robot's own app.

**UV disinfection** is the card at the top of the Disinfection Points page. **START
DISINFECTION** asks you to confirm the room is clear, counts down the start delay, switches the
lamps on for the set time, then off. **START NOW** skips the countdown. The **tune** button sets
the time (1–30 min) and delay (15, 30 or 60 s); both are saved on the phone. While a run is on,
a red banner with the time left is pinned on every page and everything that moves the robot is
locked.

The lamps are on the power board's 24 V external supply. The robot can't report whether it is
on, so the timer is the app's, and the app switches the lamps off on: the time running out,
**LAMPS OFF** / **CANCEL**, STOP, the e-stop, the robot moving, lost connection, or leaving the
app (the timer can't run in the background). OFF is retried every 1.5 s until the robot accepts
it; until then the banner reads **UV LAMPS MAY STILL BE ON**, nothing that moves the robot
works, and the app won't switch robot or mode. ON and OFF go out one at a time, in order, so an
OFF can never overtake an ON.

**Disinfection tasks** work like the fixed-point tasks in the robot's own app. A task is a list
of saved points in order, each with its UV time (1–30 min), plus the countdown before the lamps
(15, 30 or 60 s), the number of rounds (1–10), and what to do at the end (**Go to charger** or
**Stay**). Tasks are saved on the phone, by point name, under **Disinfection tasks** on the
Tasks tab; **+ New task** and the tune button open the editor. **START** asks you to confirm the
rooms are clear, then for each stop:

1. the robot goes to the point with `/cmd/nav_name`, undocking first if it is on its pile. If it
   is already standing on the point (say, round 2 of a one-point task), there is no trip.
2. the same UV run as START DISINFECTION: countdown, lamps on for the stop's time, then OFF.
3. the next trip only starts once the robot has **accepted OFF**. The lamps are never on while
   it drives.

If the robot can't reach a point (blocked, refused, or stopped short), the task **waits** with the
lamps off: **TRY AGAIN**, **SKIP** that point, or **END TASK**. The d-pad works meanwhile, to free
the robot. The task ends early on: STOP, END TASK, the e-stop, switching the lamps off, the
robot moving or the connection dropping while the lamps are on or counting down, or leaving the
app. Under 30% battery a task won't start; under 25% between stops it ends and the robot goes to
its charger (even with **Stay**). Both sit above the robot's own app's auto-charge level (20% on
this robot), which would otherwise take the robot away mid-task. Whatever happens, a card then says how the task went, point by
point: the UV time that ran, or skipped, not reached, or stopped partway. "Disinfected" means the
planned time ran there, nothing more: the app can't measure dose.

**The robot's own app** (`com.reeman.robot.disinfection` v4.0.7, read 2026-10-16) runs one route
over every numbered point in order, with one stay time for all of them, a 10 s countdown before
the task, a single run or a loop for a set total time, the lamps **at the points**, **all the way**
or **off** (a route test), and at the end the charger or the first point. Tasks can be manual or
scheduled (a time and weekdays). While a task runs it pauses when its human sensor sees someone
and resumes once nobody is seen; ending a task asks for a password on its screen.

What it has that this doesn't: **human detection** and **voice prompts** both live on the robot's
head unit, reached over its serial link, with no HTTP command (that app also turns detection
off on its home screen, so it is off while this app runs tasks); and
**scheduled** tasks would need the app to run in the background, which it deliberately doesn't (the
lamps go off when you leave it). Keep the phone on the Tasks tab, on good Wi-Fi, for the whole task.

## Maps

**The Map page** shows the map the robot navigates on, its saved points, and the robot (a disc with
a pointer the way it faces). Pinch to zoom, drag to look around, **Fit** to see it all. The
robot's pose (`/reeman/pose`) is read twice a second and the image (`/reeman/map`, a PNG) when it
changes, but only while the page is open, and in a loop of their own, so the speed and e-stop reads
never wait behind a big image. The robot's mode (`/reeman/get_mode`) and map in use
(`/reeman/current_map`) are read every ~3 s whatever is on screen.

The map shows the disinfection points (dots), the **charging pile** (a green square) and the
**virtual walls** (red lines). Its buttons are in the dock (see "The dock"), so the map has the
page. **Edit** opens a menu: **Set position** (see below), then:
- **Disinfection point here**: saves where the robot stands, as on the Disinfection Points page.
- **Charging pile here**: with the robot docked on its pile; saves `charging_pile` (type
  `charge`), replacing any pile already marked. GO TO CHARGER drives to it.
- **Virtual wall**: drag from one end to the other, then **Add wall**. A line the robot won't cross.
- **Remove a virtual wall**: tap it, then **Remove**.

Walls are read from `/reeman/restrict_layer` and written with `/cmd/restrict_layer`, which
replaces the whole set, so the app reads the walls, adds or removes one, and sends them all back.
**Set position**: tap where the robot really is, drag the way it faces, then **Set here**. Not
offered while mapping, when **Save** and **Discard** sit on the map's *Scanning* badge. What a map command did shows on the map for a few seconds (tap to hide),
and stays in the activity log. The map's name in the readings opens the Maps page.
  Needed after switching maps away from the pile (see below).

**Mapping.** On the **Maps** page, **+ New map** starts a fresh scan and **Extend this map** adds
to the map in use (the robot must know where it is on it first). Either way the app goes to the
Map page with the drive pad up: drive slowly round the whole area, come back past where you
started so the map closes up, and watch the scan grow (refreshed every 2 s). The add menu works
while mapping too (the charging pile, points, walls): the robot can't store them on a map that
isn't saved yet, so they wait in the app (drawn dashed) and are written onto the map straight
after it is saved, and only if the new map is the one in use. Discarding drops them. Then save
(asks for a name) or discard. If the app is left mid-scan, the robot keeps mapping and the app
shows it on return.

**The Maps page** lists the robot's maps with a picture of each, the one in use first; an
untitled map shows the start of its id, and the Activity log lists every map's name and id. Its menu:
**Use this map** (the robot then assumes it is on that map's charging pile, as the API documents,
so set its position unless it is), **Rename**, and **Delete** (to the robot's bin, kept 30 days;
**Undo** brings it back). Points belong to a map, so switching maps switches points.

## Status: what is known, and what isn't

Checked against the real robot (address `192.168.1.228`, firmware **RSNF1-v5.1.20_01** since an
over-the-air update before 2026-10-12; earlier tests were on v5.1.12_01).

**Verified on the robot**
- Status reads: `/reeman/current_version`, `/reeman/speed`, `/reeman/pose`,
  `/reeman/base_encode` (`emergencyButton` 0 = pressed; `chargeFlag` is stale while the robot
  sits still). `/reeman/global_plan` answers `007` with no route; `/reeman/nav_status` answers
  `004` when idle.
- Fixed steps (`/cmd/move`, `/cmd/turn`) drive it, with the firmware's own gentle ramps.
- The stop bodies are a hard stop, but only pause a navigation.
- **`/cmd/charge` brings up a confirmation on the robot's own screen** (2026-10-14): the robot's
  tablet app asks before it acts, so GO TO CHARGER needs someone at the robot to press Confirm.
  On that first try the robot still didn't move after Confirm; cause not yet known (see GO TO
  CHARGER under Tasks: the pile's name on the map is the first suspect).
- `/cmd/speed` is applied at once with no ramp, and the robot stops itself ~1.16 s after the
  last one (the jog test). Re-sent planned steps restart from rest (2026-10-08).
- **UV lamps** (2026-10-12, v5.1.20): `{"type":"24v","operation":"1"}` lit them, `"0"` switched
  them off; the reply is `{"status": "success"}`. On v5.1.12 the same command did nothing.
- Requests from a laptop on the robot's Wi-Fi: median ~10 ms, slowest ~100 ms.

**Built, not yet tried on the robot**
- Hold-to-drive (this version). See "First drive test".
- A UV run from the phone (timer, LAMPS OFF, STOP, e-stop). See "UV test".
- GO TO CHARGER, both ways. **Note:** on 2026-10-12 the robot's map had no `charging_pile`
  point (only "Point 1" and "Point 2"), so GO TO CHARGER can't measure the distance and will
  ask the robot to drive to a point it doesn't have. Save the pile's point first (in the robot's
  own app, with the robot on the pile).
- A trip to a point (`/cmd/nav_name`): what `/reeman/global_plan` returns on the way, and
  whether `/cmd/cancel_goal` ends it.
- **Disinfection tasks** (all of it). They're built from the pieces above, so they inherit their
  open questions. See "First task test".
- **All of Maps and mapping.** The robot couldn't be reached while it was built. The reads come
  from the 2026-09-23 probe on v5.1.12: the documented `/robot/get_mode`, `/robot/map` and
  `/robot/history_map` answered 404, and the same reads answered under `/reeman/` (mode 2; map
  263 × 519 px at 0.05 m with an origin and a PNG data URL; maps with small pictures). The
  commands (`/cmd/set_mode`, `save_map`, `apply_map`, `rename_map`, `trash_map`, `restore_map`,
  `reloc_pose`) are as documented and have never been sent. See "First mapping test".

**Guesses** (marked `GUESS` in the code)
- How close counts as arrived (0.3 m), and how far away docking can find the pile (1.5 m).
- The task battery margins (30% to start, 25% to carry on), kept above the robot's own app's
  auto-charge level (`low_power`, 20% on this robot).
- That a robot sitting on its pile reports charging (`chargeFlag` 2) at least once after the app
  connects. That reading is how the app knows it's on the pile; one docked with a full battery
  that never reads 2 wouldn't lock the d-pad.
- Which point `type` the robot's own app uses (the app saves `delivery`).
- Whether the e-stop alone cuts the lamps.
- How the map image lines up with the robot's coordinates: the app assumes the ROS map_server
  way (pixel row 0 is the top edge, `origin_x/y` the bottom-left corner). If the robot shows
  mirrored or off its map, this is why.
- That a newly saved map comes into use straight away. The app only names it if the map in use
  changed, so it never renames the old map by mistake.
- That a pose read while mapping is in the frame of the map that gets saved, so points and walls
  marked during a scan land in the right place. Writing walls (`/cmd/restrict_layer`) has never
  been tried; reading them has.
- That `/cmd/reloc_pose` (not `reloc_absolute`) lets the robot refine a set position with its
  laser; and what the robot does with trips, docking or a map switch while mapping (the app
  doesn't send them).
- In the simulator: turn acceleration, docking and navigation shapes, and what a command does
  with the e-stop pressed (see `FakeRobot.kt`).

## Real-robot tests

**First drive test.** Robot off the pile, ~1 m clear all round, physical e-stop in reach.
1. Open the app: a green CONNECTED in the line under the readings, the battery ring shows a figure, grey **E** badge.
2. **Hold** Left for 2 s, let go: it should start turning within ~0.5 s, then ease to a stop
   within ~10°. Then Right, Back, Forward, and Turn 180° (a tap).
3. Hold Forward and let go: note how far it runs on (~20 cm) and whether the mast rocks.
4. Press STOP once while holding Left (a hard stop, at turning speed).

**UV test.** UV-C burns skin and eyes: nobody in the room, door closed, watch from outside.
1. Set **1 min** and **30 s** (tune button), **START DISINFECTION**, leave. Lamps on after the
   delay, off after a minute; the log says "robot accepted OFF".
2. Repeat, ending with **LAMPS OFF**, then **STOP**, then the physical e-stop (note whether
   the e-stop alone cuts the lamps).
3. **START NOW** during the countdown: the lamps come on straight away.

**First task test.** As the UV test: nobody on the route, doors closed, watch from outside.
1. Make a task of two points, **1 min** each, **15 s**, 1 round, **Stay**. START, leave.
2. Watch it go to point 1, count down, light, switch off, and only then drive to point 2. The
   card at the end should read 2 of 2.
3. Run it again, and press the physical e-stop on the way to point 2: the task should end,
   with point 1 done and point 2 not reached.
4. Then with **Go to charger**, from off the pile (remember the Confirm on the robot's screen).

**First mapping test.** Robot off the pile, room clear, physical e-stop in reach.
1. Read-only first, from a laptop on the robot's Wi-Fi (nothing here moves or changes the robot):

   ```bash
   for p in get_mode current_map history_map map pose; do echo "== $p"; curl -s -m 3 http://192.168.1.228/reeman/$p | cut -c1-300; echo; done
   ```

   Expect `{"mode":2}`, the map's name, a list of maps, and a map with `resolution`, `origin_x/y`
   and `image_url`. If these answer 404 on v5.1.20, stop: the paths moved.
2. Open the Map page: the robot should sit on the map where it really is, pointing the right way,
   with the points where they belong. Hold Forward: it should move forward on the map.
3. Maps → **+ New map**: the green MAPPING banner, the pad up. Drive a small loop; the scan
   should grow every 2 s. Note whether walls line up as you go.
4. **Save map** as "Test 1". The log should say "Map saved as Test 1"; check the robot's own app
   shows it too. Then **Use this map** to go back to the old one, with the robot on its pile.
5. Try **Set position** once with the robot off the pile, and **Delete** then **Undo** on "Test 1".

Copy anything odd from **Settings › Activity log** (long-press to select).

## Demo mode

Switch on **Settings › DEMO mode**. The app then talks to a simulated robot
inside the app (`FakeRobot.kt`): **nothing is sent over the network**, so it is safe on the
robot's Wi-Fi. A purple strip stays pinned at the top and every log line starts with `[DEMO]`.
All the app's own logic runs unchanged. Mode can't be switched while something runs or the
lamps may be on. The emulator starts in demo mode until you choose, since it shares the Mac's
network and could otherwise reach the real robot.

The simulated robot answers the same paths and JSON as the firmware, with ~200 ms command
latency, the measured acceleration, the `/cmd/speed` watchdog, and stop bodies that only pause
a trip. It sits in a 5 m × 4 m room with a box obstacle, three saved points and a charging pile
2.15 m behind the start (back up ~0.7 m to be within docking range). Trips drive straight lines
and stop short at walls; it doesn't plan around obstacles.

It has two maps, "Demo room" (with the points) and "Old scan" (no points), both the same fixed
picture of the room: enough to check the map screens. Map commands are accepted, but mapping
isn't simulated (the picture doesn't grow, and Set position changes nothing).

The **DEMO controls** page (sliders button in the DEMO menu) simulates events that are hard to
set up on the real robot:

| Control | What the app should do |
|---|---|
| E-stop pressed | **E** badge solid red, driving blocked; mid-step, the robot stops |
| Wi-Fi dropped | NOT CONNECTED, orange, in the line under the readings after ~3 failed polls; driving blocked |
| Obstacle ahead | next step accepted but never starts: "no motion seen" |
| Robot goes to charge by itself | "moving, but not by this app"; STOP ends the trip for good |
| Person in the way (during a trip) | robot waits 5 s then carries on; the trip stays locked |
| Battery −10% | the battery ring orange at 20%, red at 10% |
| Reset robot | back to the room centre, faults cleared, added points and maps removed |

## Build and install

**Android Studio:** *Open* this folder → *Trust Project* → wait for sync (accept a missing
SDK platform if offered). On the phone, enable Developer options (tap **Build number** 7 times)
and **USB debugging**, plug it in, allow the prompt, then press **Run**. For an installable file:
*Build → Build APK(s)*; it lands in `app/build/outputs/apk/debug/app-debug.apk`.

**Terminal** (needs JDK 17–21; Android Studio's bundled JDK 25 is too new):

```bash
JAVA_HOME=~/Library/Java/JavaVirtualMachines/jbr-21.0.11/Contents/Home ./gradlew testDebugUnitTest assembleDebug
```

The unit tests run in virtual time against the simulated robot and cover the simulator and the
drive rules end to end: interlocks, the step lock, STOP, e-stop, Wi-Fi loss, hold-to-drive
(the speed only changes at the ramp's rate), docking, trips, adding points, UV runs, and maps
(mapping locks trips but not the pad, saving names the new map, switching maps).

## Network

Setting up a new site (fixed addresses, the robot's Wi-Fi, what to ask IT for): see
[`SITE_SETUP.md`](SITE_SETUP.md).

**Finding the robot.** Staff shouldn't need to know the robot's address. Each robot's navigation
computer has a hostname unique to it (`/reeman/hostname`, like `wbot11u-250101-003-007`), which
the app learns on connecting and keeps. It shows it short (see `RobotName`): the last two numbers,
`UV 3-07`. When the robot stops answering where it was, the app looks for it by itself (every 20 s
while it's missing, and at once when the phone changes Wi-Fi), in this order:

1. **By name.** The robot answers to `<hostname>.local` (multicast DNS) on its network: one
   question, an answer in milliseconds. Only on the phone's own network, and only where the
   network passes multicast (some enterprise Wi-Fi doesn't).
2. **Every address it has had** (the last six), even on another network IT routes to.
3. **The phone's whole network** (up to a /22; on bigger networks the /24 around the phone and
   around the last address), each address asked for `/reeman/current_version` and
   `/reeman/hostname`, ~1–5 s in all.

| Found | The app |
|---|---|
| **This robot** (its hostname) | follows it to its new address without asking, and logs it |
| Other robots | lists them by name in **Settings › Robot**; staff tap theirs |
| Nothing | says so there |

A hospital can have several robots, so the app never moves to a robot it can't recognise without
a tap: driving or lighting the wrong one has to be deliberate. For the same reason, a robot
answering at the saved address is checked by its hostname before the app counts it as connected:
the router may have given that address to another robot. **Find robots** in the menu searches on
demand. **Enter address** still takes an address (for a robot on a routed network the app can't
search).

**Mobile data can stay on.** The app sends everything over Wi-Fi, even when Android prefers mobile
data because the robot's Wi-Fi has no internet.

**What the app can't fix.** The phone can only reach the robot if they're on the same network.
If the robot joins a network the phone isn't on (another hospital Wi-Fi it has saved), no app can
reach it until the phone joins that network too (the app then finds it within seconds) or IT
routes between the two. So, for the robot's Wi-Fi:
- **Save one Wi-Fi network on the robot** (from its own screen) and forget all the others, so it
  can't switch networks on its own.
- That network should be **one network across every access point**: the same name everywhere,
  handing out addresses from one place (an enterprise Wi-Fi does this). Walking between access
  points then keeps the robot's address. If each access point is its own router, the robot gets
  a new address on a separate network at each one, and only a phone on that same access point
  can reach it.
- Not a guest network, and with client/AP isolation off (it blocks phone-to-robot traffic).
  2.4 GHz and 5 GHz under different names are fine if both are that same network.
- Ask IT to **reserve the robot's address**: the search is the fallback, not the plan.

**Plain http** goes to **local network addresses only** (10.x, 172.16–31.x, 192.168.x,
169.254.x; `RobotApi.isLocalAddress`), never across the internet. Quick check from the phone's
browser: `http://<robot address>` should load the robot's console.

## Files

Under `app/src/main/java/com/expiation/reemanremote/`:

- `DriveController.kt`: **every rule lives here**: polling, interlocks, the step lock,
  hold-to-drive, STOP, tasks, UV runs, maps and mapping, demo switching. Plain Kotlin (no Android), with the state
  as explicit types: `Link`, `StepPhase` (Idle / Holding / Stepping / Stopping) and `Uv`
  (Countdown / Starting / On).
- `RobotApi.kt`: HTTP to the robot, or to the simulator in demo mode.
- `RobotFinder.kt`: searches the phone's Wi-Fi network for robots (see Network).
- `FakeRobot.kt`: the simulated robot (DEMO mode).
- `RemoteViewModel.kt`: keeps the controller across screen recreation; follows the phone's Wi-Fi
  (sends over it, and searches it); saves the address, the robot's map, demo
  choice and UV settings.
- `MainActivity.kt`: hosts the screen; leaving the app pauses polling and switches the lamps off.
- `ui/RemoteScreen.kt`: the Compose screen. It only draws `RemoteState` and forwards taps.
  The tabs, the drive sheet, the readings and banners, the Tasks, Points and Settings pages.
- `ui/NavDock.kt`: the dock at the bottom of the main page: the Map tile, the tab or map buttons, and STOP.
- `ui/MapScreen.kt`: the Map page (map view, set position, save/discard, and its dock buttons) and the Maps page.
- `ui/Theme.kt`: colours, including the status colours.

Tests are in `app/src/test/java/com/expiation/reemanremote/`.
