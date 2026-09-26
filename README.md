# LeapBox Prototype 04

An Android phone prototype for a Leapmotor C10. LeapBox tries to replace the QDLink *phone* app: it talks to the car's built-in QDLink receiver over USB (Android Open Accessory) and sends the car its **own** 1920 × 882 dashboard instead of a mirror of the phone screen. The phone stays free for other apps and may lock.

How it works:

- `SecondScreenService` creates a virtual display, draws the LeapBox dashboard on it, and encodes it to H.264 with the phone's hardware encoder. A partial wake lock keeps the CPU running; the phone screen is not kept on.
- `QdLinkUsbClient` opens the car's USB accessory, detects the QDLink protocol (v2 `5A5A` or legacy v1 `!BIN`), answers the v2 handshake (`CAR_INFO` → `PHONE_INFO`, video support, landscape mode), sends video when the car asks for it (`VIDEO_CTRL`), answers heartbeats, and forwards the car's touch events back to the dashboard.
- `MainActivity` is the control panel, live status and diagnostic log.

The QDLink protocol handling was worked out from the existing QDLink phone app and has **not yet completed a session with a real C10**. Only the v2 protocol is implemented; v1 is detected and logged.

## Testing in the car

1. Force-stop or disable the QDLink phone app so it cannot take the USB connection.
2. Open LeapBox and press **Start LeapBox car desktop**.
3. Plug in the C10 USB cable and open QDLink on the car. LeapBox keeps watching USB until the car's accessory appears, then opens it. If Android asks which app to use for the accessory, pick LeapBox. If it asks for USB permission, allow it.
4. Watch the status card. `accessory mode ON` means the car switched the phone into accessory mode. `Accessory found: …` gives the exact strings the car reports.
5. Press **Copy diagnostic log** and share the log. It records USB state changes, the accessory strings, the car's first reply bytes, and every handshake command sent or received.

What the log tells you:

| Log shows | Meaning |
| --- | --- |
| `USB cable not connected` | Phone does not see the cable (try another cable/port). |
| `USB cable connected, accessory mode off` for more than a few seconds | The car never switched the phone into accessory mode, so the car is waiting for something else (e.g. Bluetooth pairing via QDLink first). |
| `Android refused to open …` | Another app (usually the QDLink phone app) owns the accessory. |
| `waiting for car reply` with no reply | The probe LeapBox sends is not what the car expects. |
| `car → …` JSON lines | Handshake progress; compare against what the car expects. |

## Apps on the car (Shizuku)

Android does not let a normal app open other apps (Waze, YouTube, Maps, Spotify) on a screen it created, and the C10 sends touch as a touchscreen that Android routes to the phone's own screen. With [Shizuku](https://github.com/RikkaApps/Shizuku), LeapBox runs a small helper (`ShellService`) as the shell user, the same way scrcpy's `--new-display` works:

- it creates a trusted, always-unlocked car display that other apps may open on;
- it opens the chosen app there (`am start --display`);
- it reads the car's touchscreen from `/dev/input`, disables it for the phone, and injects the touches into the car display.

Setup: install Shizuku, pair it with Wireless debugging once, and tap **Start** (again after each phone restart). In LeapBox tap **Connect Shizuku** and allow, then **Start LeapBox car desktop**. The car dashboard shows tiles for installed apps; a small **LEAPBOX** button on the car returns to the dashboard. Without Shizuku, LeapBox falls back to its own display (dashboard only).

## Build

- A GitHub Actions workflow builds a debug APK on every push to `main` or `claude/**` branches. Open the latest **Build LeapBox Android APK** run in the **Actions** tab and download the **LeapBox-Prototype-APK** artifact.
- Locally: Android Studio with Android SDK 35 and JDK 17 (Android Gradle Plugin 8.7.3, Gradle 8.9). No Gradle wrapper is included.
- `downloads/LeapBox-Prototype-01.apk` is an old Prototype 01 build without the USB bridge; do not use it to test this version.

## Boundaries

- Android may refuse to launch other apps (such as Waze) on the virtual display; the Waze button is a diagnostic only.
- The foreground service is user-started and user-stoppable and releases the display, encoder, USB accessory and wake lock when stopped.
- Package `com.leapbox.prototype`. It neither bundles nor modifies QDLink.
