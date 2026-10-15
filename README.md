# LockDash

A generic, MQTT-driven **lock-screen dashboard** Android app (sibling of
[BellCam](https://github.com/ajithvasudevan/bellcam)). Home Assistant publishes an MQTT
message and the app wakes the phone and opens the matching screen **over the lock screen**.
The app is intentionally *dumb*: it holds no HA token — HA pushes the data and performs any
actions the screen requests.

Package `dev.ajithv.lockdash`. Broker `172.20.1.100:1883` (creds in gitignored
`app/secrets.properties`, seeded from `secrets.properties.example`).

## MQTT contract (base topic `lockdash`)

The service holds **one** MQTT connection and **one** subscription — `lockdash/#` — and
routes by topic suffix. (BellCam's camera is folded in here as the `camera` screen, so a phone
needs only this one app / one notification.)

| Topic | Dir | Payload | Meaning |
| --- | --- | --- | --- |
| `lockdash/show` | HA → app | JSON `{"screen": "...", ...}` | Wake + open/refresh the screen. |
| `lockdash/dismiss` | HA → app | any | Close the current screen. |
| `lockdash/request` | app → HA | `"<screen>"` | Ask HA to push a screen (used when a screen is opened directly). |
| `lockdash/cmd` | app → HA | JSON `{"action": "...", ...}` | A control from a screen (e.g. `toggle`). |

### Screen: `camera`
`lockdash/show` payload `{ "screen": "camera" }` (optionally `"rtsp": "...", "title": "..."`);
with no URL it plays the baked doorbell RTSP (`secrets.properties` `rtsp_url`). Full-screen
libVLC feed over the lock screen, starts muted, auto-closes after `dismiss_ms` / on ✕ /
on `lockdash/dismiss`.

### Screen: `whatson`
`lockdash/show` payload:
```json
{ "screen": "whatson", "title": "What's On",
  "devices": [ { "name": "Kitchen Light", "entity_id": "switch.kitchen_light",
                 "domain": "switch", "on": true, "power": "12 W" } ] }
```
A table of devices, each row = name + live power + a HA-style toggle. Toggling publishes
`lockdash/cmd {"action":"toggle","entity_id":...,"domain":...}`; HA performs it and re-pushes
`lockdash/show`. The device set is frozen for the session, so a device switched off keeps its row.

### Screen: `notif` (new, r26)
Not MQTT-driven. `NotifListener` (a `NotificationListenerService`, needs the one-time
**Notification access** grant) watches the phone's notifications and, **only while the main-UI
master switch is on**, pops `NotifActivity` full-screen over the lock screen for every new
**non-persistent** notification (ongoing / group-summary / non-clearable ones are skipped),
showing **app · sender · text** in a large font. Everything is tuned on the main screen and stored
in `SharedPreferences` (`Prefs`): **font size**, **sound** (any ringtone/notification/alarm tone),
**sound volume** (independent of the system volume — the tone briefly maxes `STREAM_ALARM` and is
scaled by the player's own gain, restored after), **background** and **text colour**. A **Test
pop-up** button previews it. Auto-closes after 30 s / tap / `lockdash/dismiss`. Work-profile
notifications arrive automatically (a personal-profile listener receives its managed profiles'
notifications — same as the HA Companion app); no extra permission is needed for them.

The listener routes to `DashService.ACTION_OPEN_NOTIF`, reusing the shared wake-screen + launch path.
Colours are picked with an HSV **`ColorWheelView`** (hue/saturation wheel + brightness slider, r27).
The app name shown is the **friendly** name (label → work-profile `LauncherApps` → prettified package,
never a raw dotted id, r27). A **"Blacklist" checkbox** on the overlay suppresses that **app +
category** combination for good (r28), or by **sender** — the notification title, i.e. the contact/person
within the app (r38); manage/undo the unified list (per-row ✕ + "Clear all") in the main UI. Overlay
**A−/A+** buttons (r39) adjust the shared font size (main-UI slider reflects it); colours stay main-UI-only.
A rolling **100-notification history** (r29) is paged with **◀/▶** arrows and each item shows its
received **date/time**. The persistent service notification has a **5th "Notifications" shortcut**
(r30) that opens the overlay on the last notification silently. **Grouped** notifications are shown
**un-grouped, in chronological order** (group summaries skipped; children sorted by received time).
History **records regardless of the master switch** (the switch/blacklist gate only the pop-up). The
overlay closes **only via ✕** (tapping elsewhere doesn't); the bottom bar shows ◀ `2/9` ▶ (position/
total) on the left and the Blacklist toggle on the right. A main-UI **Notifications** button (5th, under
the four screen buttons) opens the overlay on the last notification to page history.

## MQTT kill-switch (`lockdash/control`)
Publish `disable` (or `off/0/false`) to **`lockdash/control`** to fully disable the app: the persistent
notification is hidden, all overlays are suppressed, and the main UI is blocked — the service stays alive
(MQTT connected) to receive `enable` (or `on/1/true`), which restores everything. State persists across
reboots. HA drives it from **Overview → System → LockDash** (Disable/Enable buttons).

## Launcher entry points
Home-screen icons (activity-aliases): **What's On**, **Door Camera**, **Music**, **Notifications**.
Long-pressing the main **LockDash** icon shows **app shortcuts** for all five screens (What's On / Door
Cam / Music / Home / Notifs) — static shortcuts in `res/xml/shortcuts.xml`, each targeting an exported
alias (the dash activities are `exported=false`).

## Adding a screen
1. New `Activity` (e.g. `FooActivity`), add a branch in `DashService.dispatch`.
2. Add a button in `MainActivity` / a launcher `activity-alias` if it should be openable directly.
3. HA: a script that publishes `lockdash/show` with the new `screen`, and a `lockdash/request` mapping.

## Build (on europa)
```
cd ~/lock-dash && ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=$HOME/jdk17 \
  PATH=$HOME/jdk17/bin:$PATH ~/gradle-8.7/bin/gradle assembleDebug
adb -s <serial> install -r -g app/build/outputs/apk/debug/app-debug.apk
```
On OnePlus/OxygenOS, grant in the UI (adb can't set appops there):
**Allow pop-up from background**, **Display over other apps**, **Battery = Unrestricted**, notifications.
