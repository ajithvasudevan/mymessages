# MyMessages

A single Android app that unifies four things on **one** MQTT connection and one phone:

1. a full **default SMS/MMS messaging app** (Android Telephony provider, `ROLE_SMS`);
2. a **notification overlay** that pops new notifications full-screen over the lock screen;
3. an **MQTT-driven lock-screen dashboard** (What's On / Door Camera / Music / Home Assistant),
   woken and fed by Home Assistant;
4. a **Trip Tracker GPS feed** that streams location while a trip is active.

Package `dev.ajithv.mymessages`. Forked from **LockDash** (itself a sibling of
[BellCam](https://github.com/ajithvasudevan/bellcam)): the dashboard + overlay halves are
LockDash's, the SMS/MMS and Trip Tracker halves are new. The app is intentionally *dumb* about
Home Assistant — it holds no HA token; HA pushes the dashboard data and performs any action a
screen requests.

Broker `172.20.1.100:1883` (creds in the gitignored `app/secrets.properties`, seeded from
`app/secrets.properties.example`). Values in that file become `BuildConfig` fields at build time;
the base MQTT topic is **`lockdash`** (kept from the fork so the existing HA glue works unchanged).

---

## Build (on europa)

```
cd ~/mymessages
JAVA_HOME=~/jdk17 ~/gradle-8.7/bin/gradle :app:assembleDebug
adb -s <serial> install -r -g app/build/outputs/apk/debug/app-debug.apk
```

`install -g` grants the SMS runtime-permission group. On OnePlus/OxygenOS the appop-style grants
can't be set over adb — enable them once in the UI: **default SMS app** (in-app "Set as default"
button → `RoleManager` `ROLE_SMS`), **Notification access** (for the overlay listener), **Usage
access** (foreground-app gate), **Display over other apps**, **Allow pop-up from background**,
**Battery = Unrestricted**, and notifications. Toolchain: gradle 8.7, JDK 17, compileSdk 34,
minSdk 27.

---

## 1. SMS/MMS (default app)

Uses Android's own Telephony provider (no private store), so it only works while it is the default
SMS app.

- **Conversation list** with a **Threads / Received** toggle (thread list vs. flat inbox feed), a
  debounced **search** across all messages (plain substring, **regex**, or **sender**-scoped), a
  display cache for instant paint, and unread styling (theme-aware colours; read only on open/tap).
- **Thread view** — in/out bubbles, SMS + MMS including inline MMS images, an SMS reply box, a
  per-thread Block/Unblock button. **Pinch-to-zoom** font, size persisted and shared with the list.
- **Compose** — new message + `SENDTO`/`SEND` handler, with a chip/pill recipient picker backed by
  a contacts autocomplete; multiple recipients supported.
- **Blocking & filtering** — three independent layers, all hidden from the browse views:
  app-side sender list (handles alphanumeric short codes like `AD-ICICIT-S`), Android's system
  `BlockedNumberContract` (also blocks calls), and user **regex** text filters. Blocked messages
  still land in the provider (viewable) but raise no notification/overlay.
- **Long-press delete** of a conversation, a message, or a bubble (removed from the provider).
- Incoming SMS writes to the provider, raises a shade notification, and feeds the overlay. A
  blocked-SMS stats line on the home screen counts what's being hidden.

MMS is **display-only** (already-received MMS is rendered). Inbound MMS *download* and outbound MMS
*send* are not implemented (they need the hidden `com.google.android.mms` PDU lib + a real SIM);
replies are SMS text.

---

## 2. Notification overlay

`NotifListener` (a `NotificationListenerService`, needs the one-time **Notification access** grant)
watches the phone's notifications and, **only while the master switch is on**, pops `NotifActivity`
full-screen over the lock screen for each new **non-persistent** notification (ongoing /
group-summary / non-clearable ones skipped), showing **app · sender · text** in a large font.
Telegram arrives via the HA **telegram-userbot** over MQTT (the phone's own Telegram capture is
suppressed), so a group chat shows the sender name.

- **Settings** (home screen, stored in `Prefs`): font size, sound (any ringtone/notification/alarm
  tone) with an independent volume, background + text colour (HSV `ColorWheelView`), auto-close.
- **History** — a rolling 100 notifications, paged, each with its received date/time; recorded
  regardless of the master switch (the switch/filters gate only the pop-up).
- **Filters** — long-press the message area for a menu to suppress by **app / category / sender /
  text** (substring); plus a separate substring/regex notification-filter list.
- **Images** — attached images render edge-to-edge and re-fit on rotation; tap for a matrix-driven
  full-screen zoom/pan viewer.
- **Foreground gate** — when unlocked, an auto-arriving notification pops **only over the home
  screen or the app's own UI**, never on top of another app in use (needs **Usage access**);
  locked always pops. User-opened views and the doorbell camera are never gated.

---

## 3. MQTT lock-screen dashboard

`DashService` holds **one** MQTT connection and **one** subscription — `lockdash/#` — and routes by
topic suffix. HA publishes a `show` message; the app wakes the phone and opens the matching screen
over the lock screen.

| Topic | Dir | Payload | Meaning |
| --- | --- | --- | --- |
| `lockdash/show` | HA → app | JSON `{"screen": "...", ...}` | Wake + open/refresh the screen. |
| `lockdash/dismiss` | HA → app | any | Close the current screen. |
| `lockdash/request` | app → HA | `"<screen>"` | Ask HA to push a screen (opened directly). |
| `lockdash/cmd` | app → HA | JSON `{"action": "...", ...}` | A control from a screen (e.g. `toggle`, music vol). |
| `lockdash/control` | HA → app | `disable`/`enable` | Kill-switch (see below). |

**Screens:**

- **`whatson`** — a table of devices (`{name, entity_id, domain, on, power}`), each row a name +
  live power + a HA-style toggle. Toggling publishes `lockdash/cmd {"action":"toggle",…}`; HA
  performs it and re-pushes `show`. The device set is frozen for the session.
- **`camera`** — full-screen libVLC RTSP over the lock screen (the doorbell by default, from
  `secrets.properties` `rtsp_url`); starts muted, auto-closes after `dismiss_ms` / ✕ / `dismiss`.
  Supports two-way talk via a go2rtc backchannel.
- **`music`** — a Now Playing overlay with transport + **Pick & Play** (album/playlist → track),
  reusing the Music Assistant backend; the phone's hardware volume keys drive the active speaker
  while it's showing.
- **Home Assistant** — a WebView onto `ha_url`.

### Kill-switch (`lockdash/control`)
Publish `disable` (or `off/0/false`) to fully disable the app: the persistent notification is
hidden, overlays suppressed, the UI blocked — the service stays alive (MQTT connected) to receive
`enable` (or `on/1/true`). State persists across reboots. HA drives it from
**Overview → System**.

---

## 4. Trip Tracker GPS feed

While a trip is active (controls on the home screen: mode + a **battery-saver** switch + start/stop),
`TripTracker` samples GPS and records each fix, then:

- **publishes** it over MQTT (topic `trips`) for the Home Assistant Trip Tracker add-on; and
- **broadcasts** it directly, on-device, to the Trip Tracker app
  (`dev.ajithv.triptracker.LIVE_POINT`, explicit + signature-gated) for outage-proof live rendering,
  independent of connectivity.

A **durable offline buffer** (RAM → flash) holds fixes through network outages and replays them
oldest-first. Sampling is mode-aware (time / distance / hybrid). **Battery saver** drops to a
low-power, coarse location request while stopped and switches back to 1 Hz high-accuracy GPS the
moment motion resumes. An **adaptive outlier gate** drops a fix that jumps farther than
`max(50 m, recentSpeed · dt · K)` from the last recorded point (speed-scaled, so trains/planes are
fine), skipped across real data gaps.

---

## Persistent notification

A foreground-service notification with six one-tap shortcut buttons on two rows — **What's On ·
Door Cam · HA** and **Music · Messages · Notifs** — rendered as a custom `RemoteViews` on a `LOW`
channel so the buttons show without expanding. (A foreground service always shows at least a
status-bar icon + a collapsed shade row; the home screen's "service notification" toggle stops the
service to remove it, which also stops the always-on overlay/MQTT.)

## Launcher entry points

MainActivity (three tabs: SMS / Notifications / Dashboard) plus `activity-alias` launcher icons for
**SMS Conversations**, the **Notification overlay**, **What's On**, **Door Camera**, and **Music**.

## Adding a dashboard screen

1. New `Activity`, add a branch in `DashService` dispatch + an `ACTION_OPEN_*`.
2. Add a button in `MainActivity` / an `activity-alias` if it should open directly.
3. HA: a script that publishes `lockdash/show` with the new `screen`, and a `lockdash/request` map.
