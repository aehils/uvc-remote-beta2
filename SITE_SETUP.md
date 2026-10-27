# Site setup notes

Things to arrange when the robot goes into a hospital (or any new site), so that staff can
connect from the app and keep the connection. Most of them come down to one conversation with the
site's IT team. Each note says what to ask for, and why.

Add to this file whenever something turns out to matter on site.

> **Keep site details out of this file.** This repository is public. Write each site's actual
> addresses, network names, passwords, MAC addresses and IT contacts in a private record, not here.

## The robot has two addresses

The robot has two computers on the Wi-Fi, and each has its own address:

| Computer | What it is | Does the app use it? |
|---|---|---|
| **ROS (navigation computer)** | Drives the robot and answers the app's commands (`/reeman/...`, `/cmd/...`) over plain http on port 80 | **Yes: this is the address the app needs** |
| **Android (head unit)** | The robot's own screen and its own app | No |

Both show on the robot's own screen. If you type the Android address into the app, it will never
connect. Check: `http://<ROS address>` in a phone's browser should load the robot's console.

## Network: ask IT for these

### 1. Fixed addresses for the robot (DHCP reservation)

**Ask for:** a DHCP reservation for **both** of the robot's computers (ROS and Android), each tied
to that computer's MAC address.

**Why:** without one, the router can give the robot a new address after a restart, after a lease
runs out, or when the network changes. Every new address costs staff a lost connection. The app
now searches the Wi-Fi for the robot when it goes missing, but that search is the fallback, not
the plan. With a fixed address the app connects straight away, every time.

It's a few minutes' work for IT. They'll need the MAC addresses: they're usually on the robot's
network settings screen, or IT can read them from the router once the robot is connected.

### 2. One Wi-Fi network for the robot, and only one saved on it

**Ask for:** a named Wi-Fi network for the robot. On the robot's own screen, save only that
network and delete every other saved network.

**Why:** a robot that knows several networks can switch to another one by itself, for example when
it drives past an access point with a stronger signal. A phone on the old network then can't reach
the robot at all, and no app can fix that. Staff would have to work out which network the robot
is on and move their phones to it.

### 3. The same network at every access point (roaming)

**Ask for:** the robot's Wi-Fi to be one network across the whole area it works in: the same name
at every access point, with addresses handed out from one place. Hospital-grade (enterprise) Wi-Fi
normally works this way. The access points should run in access-point (bridge) mode, not each as
its own router.

**Why:** then the robot keeps its address as it drives from one access point to the next. If each
access point is its own router, the robot gets a new address on a separate network at each one,
and only a phone on that same access point can reach it.

### 4. Phones and robot must be able to reach each other

**Ask for:**
- Staff phones on a network that can reach the robot. Simplest: the same network as the robot.
- **Client isolation (AP isolation) turned off** on that network. It stops devices on the same Wi-Fi
  from talking to each other, which blocks phone-to-robot traffic.
- A staff-only network, not the guest or public Wi-Fi.
- If IT keeps phones and robot on separate networks (VLANs), they must allow traffic from the
  phones to the robot's ROS address on **port 80** (http). In that case staff type the robot's
  fixed address into the app once: the app's automatic search only covers the phone's own network.

### 5. 2.4 GHz and 5 GHz

Separate network names for 2.4 GHz and 5 GHz are fine, **as long as both belong to the same
network** (same addresses, not one of them a guest network). The phone can be on 5 GHz while the
robot is on 2.4 GHz.

**Check:** which bands the robot's Wi-Fi supports. If it only does 2.4 GHz, it won't see a
5 GHz-only network at all.

**Check:** that there's good signal along the robot's routes, not just at the nurses' station. A
connection that drops mid-drive makes the robot carry on for about 1.2 s before it stops by itself.

## Phones

- With the current app, mobile data can stay on: the app talks to the robot over Wi-Fi even when
  Android prefers mobile data because the robot's Wi-Fi has no internet. Older builds needed
  mobile data off.
- Connect each phone once while standing next to the robot. The app learns which robot it's
  driving (by the robot's map in use) and can then find that robot again by itself if its
  address changes.

## The robot

- **The charging pile has to be saved on the map.** In the robot's own app, with the robot on its
  pile, save the pile's point. Without it, GO TO CHARGER has nowhere to go.
- **GO TO CHARGER asks for confirmation on the robot's own screen.** Someone at the robot has to
  press Confirm. Tell staff before they rely on it.
- **Auto-charge level.** The robot's own app sends it to charge at its `low_power` level (20% on
  the test robot). The app's tasks need 30% to start and 25% to carry on, so they stay above it.
- **The physical e-stop** has to be reachable whenever the robot is driven or the lamps are on.

## Before handing over: checklist

- [ ] Both robot computers have fixed addresses (DHCP reservations); ROS and Android addresses recorded privately
- [ ] Only the robot's network is saved on the robot
- [ ] `http://<ROS address>` loads the robot's console in a staff phone's browser
- [ ] The app connects (green light in the robot pill) from each staff phone
- [ ] The robot stays connected while it drives its routes, past every access point
- [ ] The charging pile is saved on the map, and GO TO CHARGER works (with Confirm on the robot)
- [ ] Staff know where the physical e-stop is
