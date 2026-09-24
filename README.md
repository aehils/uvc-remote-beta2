# Reeman Remote — beta 0.1

Android remote for the Reeman Spark robot. Fixed-step driving only: every button
sends one pre-planned step (`/cmd/move` or `/cmd/turn`), so the robot plans its own
gentle braking. The app never streams velocity (`/cmd/speed`), which is the path
that produced the hard stops (see `../REEMAN_HANDOFF.txt`, sections 5–7).

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
- Speeds are capped in code (`MAX_LINEAR` 0.3 m/s, `MAX_ANGULAR` 0.5 rad/s in `MainActivity.java`).
- STOP is a hard stop. Use it for emergencies, not for routine stopping (steps end on their own).
- The physical e-stop remains the real backstop.

## Build and install (Android Studio)

1. **Open the project:** Android Studio → *Open* → select the `ReemanRemote` folder (this folder) → *Trust Project*.
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

## Network

- Phone must be on the same Wi-Fi as the robot (192.168.1.x). Turn **mobile data off** while testing.
- Quick check: open `http://192.168.1.228` in the phone's Chrome. If the robot console loads, the app can reach it.
- Plain http is allowed **only** for `192.168.1.228` (`app/src/main/res/xml/network_security_config.xml`).
  If the robot's address changes, edit that file and rebuild; better, have IT reserve the address on the router.

## First drive test

1. Robot off the charging dock, clear floor ~1 m on every side, you can reach the physical e-stop.
2. Open the app → status should read *Connected*, battery shown, E-stop *Released*.
3. Switch on **Drive enabled** → tap **Left 90°** → confirm it turns left and the buttons unlock after it stops.
4. Then Right 90°, Forward 0.5 m, Back 0.5 m, Turn 180°.
5. Test STOP once mid-turn at the slow speed.

Note anything odd from the **Activity** log at the bottom of the screen (long-press to select and copy).

## Files

- `app/src/main/java/com/expiation/reemanremote/MainActivity.java`: screen, safety rules, step lock
- `app/src/main/java/com/expiation/reemanremote/RobotApi.java`: HTTP calls to the robot
- `app/src/main/res/layout/activity_main.xml`: screen layout
- `app/src/main/res/xml/network_security_config.xml`: the http exception for the robot's address
