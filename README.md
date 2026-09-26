# LeapBox 0.5.2: car mode for the Leapmotor C10

LeapBox shows your Android phone on the C10's screen through the car's built-in QDLink receiver, **without the QDLink phone app**. It talks to the car over USB (Android Open Accessory), completes the QDLink v2 handshake itself, and streams the phone screen as H.264.

While car mode runs:

- the phone screen is **mirrored** to the car (1920 × 882); the C10's touchscreen controls the phone directly, as with QDLink;
- the phone is **locked to landscape**, which matches the car screen, so the picture fills it and touches line up;
- the phone is **dimmed to minimum brightness** (toggle on the car home screen) and kept awake, since casting stops when the phone locks;
- LeapBox shows a **car home screen** (warm dark theme, serif clock and greeting) with large tiles for the apps you choose: tap **Edit apps** to add or remove any installed app and **↑** to reorder. Until you choose, it shows Waze, Google Maps, YouTube, YouTube Music, Spotify, WhatsApp and Phone when installed;
- a floating **LB** button on top of every other app returns to the LeapBox home screen (it hides while the home screen itself is showing).

Rotation and brightness are restored when car mode stops.

## Use

1. Once: in LeapBox allow **Modify system settings** (landscape + dim) and **Display over other apps** (LB button, and a tiny animation that keeps video flowing when the screen is static). Force-stop or disable the QDLink phone app.
2. In the car: open QDLink on the car screen, plug in the USB cable, open LeapBox and tap **START CAR MODE**. In Android's pop-up choose **Entire screen**, then **Start**.
3. Pick an app on the car screen. Tap **LB** to come back. **Stop** on the home screen or in the notification ends car mode.

Android asks for casting permission each time car mode starts; an app cannot skip that.

## Code

- `CarService` — foreground media-projection service: mirrors the screen into a 1920 × 882 H.264 encoder, sends frames through `QdLinkUsbClient`, holds a dim screen wake lock, shows the LB button, applies and restores `PhoneTweaks`.
- `QdLinkUsbClient` — the phone side of QDLink over USB: accessory discovery and permission, protocol detection (v2 `5A5A`, legacy v1 `!BIN` detected only), `CAR_INFO`/`PHONE_INFO` handshake, video packets, heartbeats.
- `MainActivity` — setup screen (permissions, start, diagnostic log), the landscape car home screen and the app picker.
- `HomeApps` — which apps the home screen shows, in order (saved on the phone).
- `PhoneTweaks` — landscape lock and brightness via system settings, with save/restore.
- `Diag` — one copyable diagnostic log.
- `Ui` — the warm colour palette and view helpers.

## Build

GitHub Actions builds a debug APK on every push to `main` or `claude/**`: open the latest **Build LeapBox Android APK** run and download the **LeapBox-Prototype-APK** artifact. Locally: Android Studio, Android SDK 35, JDK 17, Android Gradle Plugin 8.7.3 with Gradle 8.9 (no wrapper included).

## Limits

- Apps that only run in portrait (some messaging apps) appear narrow on the car and touches will not line up while they are open.
- Minimum brightness is dim but not fully black on Samsung OLED screens.
- Netflix, Astro GO, Disney+ and other DRM-protected video appear black on the car (sound still plays): these apps block screen capture, and Android enforces that for every mirroring method.
- Package `com.leapbox.prototype`. It neither bundles nor modifies QDLink.
