# LeapBox Prototype 01

This is an Android phone prototype for a Leapmotor C10 owner with the separate QDLink app already installed. It has two deliberately distinct modes:

1. **QDLink mirror:** open QDLink, start its existing USB connection, return to LeapBox, and tap Waze. This mirrors the phone screen on the car, so whatever the phone displays is also shown on the car. QDLink itself keeps the phone awake during that session.
2. **Separate-screen lab:** start a 1280 × 720 public virtual display containing a LeapBox dashboard. Its pixels go into a hardware H.264 encoder; a foreground service holds Android's `FULL_WAKE_LOCK`. The phone screen remains usable. Tap Waze in the lab to *request* that Android launch Waze on the virtual display. The diagnostics say whether the phone reports support, whether Android allows the request, and how many frames the virtual display encodes. After a successful request, confirm visually where Waze actually appears.

**The lab's video is not transmitted to the C10.** QDLink has no public API for another app to inject a virtual display stream. Completing a one-app USB connection requires implementing and testing QDLink's car-side handshake, message framing, touch input, and video transport on the actual C10. The existing APK was inspected to guide that future work, but is not bundled, patched, or redistributed here.

## Build and run

- **Phone-only route:** put this project's *contents* at the root of a new GitHub repository named `leapbox`. Its included workflow builds a debug APK on every push to `main`. In the repository's **Actions** tab, open the latest successful **Build LeapBox Android APK** run and download the **LeapBox-Prototype-APK** artifact on your phone. Extract the APK from that artifact and install it.
- Open this folder as a project in Android Studio with Android SDK 35 and JDK 17. Android Gradle Plugin 8.7.3 needs Gradle 8.9. A Gradle wrapper and compiled APK are **not** included because the current workspace has no Android SDK or Gradle installation and cannot reach the Android SDK download servers.
- Sync dependencies and run the `app` configuration on an Android 10+ phone. For an APK, use **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
- Install Waze and your working QDLink APK on the phone. For the first mode, connect the USB cable, start mirroring in QDLink, then return to LeapBox.
- For the second mode, press **Start second screen + full wake lock**, check the frame counter and display ID, then press **Try Waze on second screen**. Press **Return to LeapBox desktop** or **Stop second screen and release wake lock** when finished.
- With the car's USB cable connected, press **Inspect connected car USB**. The app reports the detected accessory manufacturer, model and version without interfering with QDLink's connection.

## Boundaries of this prototype

- It makes no claim that a C10 accepts the separate stream yet; the local H.264 counter only checks that the phone generated frames.
- A virtual display is not automatically a physical car display. Android may decline third-party activity launches or put Waze on the primary phone screen. Runtime diagnostics show the request result, but only a real device visual test confirms placement.
- A full wake lock keeps the display powered. The low-brightness control changes only the LeapBox phone window and may be overridden by the device. It never pretends the phone has been turned off.
- The foreground service is user-started and user-stoppable; it releases its virtual display, encoder and wake lock when stopped.
- The package identifier is `com.leapbox.prototype`. It neither replaces nor modifies QDLink.
