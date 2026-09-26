package com.leapbox.prototype;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.hardware.input.InputManager;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Independent LeapBox car display. The phone's visible UI is never captured.
 */
public final class SecondScreenService extends Service {
    static final String ACTION_START = "com.leapbox.prototype.START";
    static final String ACTION_STOP = "com.leapbox.prototype.STOP";
    static final String ACTION_ACCESSORY = "com.leapbox.prototype.ACCESSORY_ATTACHED";
    private static final String CHANNEL = "leapbox_display";
    private static final int NOTIFICATION = 21;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 882;
    static final int FPS = 24;
    /** 1.5× scale: 1280×588 dp on the car. The phone's own density (~3×) left only ~300 dp of height. */
    private static final int CAR_DENSITY = 240;

    final class LocalBinder extends Binder {
        SecondScreenService getService() { return SecondScreenService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private volatile String status = "Not started";
    private volatile boolean encoding;
    private MediaCodec encoder;
    private Surface encoderSurface;
    private VirtualDisplay virtualDisplay;
    private CarDashboardPresentation dashboard;
    private PowerManager.WakeLock backgroundWakeLock;
    private Thread drainThread;
    private QdLinkUsbClient qdLink;
    private volatile byte[] codecConfig;
    private volatile long lastTouchDownTime;
    private final ExecutorService shellCalls = Executors.newSingleThreadExecutor();
    private ShellLink shell;
    /** Id of the Shizuku-created, app-hosting car display; -1 while LeapBox's own display is used. */
    private volatile int shellDisplayId = -1;
    private CarHomeButton homeButton;
    private volatile String touchDeviceName;
    private InputManager.InputDeviceListener inputListener;

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopPrototype();
            return START_NOT_STICKY;
        }
        if (virtualDisplay == null) {
            startVisibleService();
            try {
                createScreen();
                startCarBridge();
                startShell();
            } catch (Exception | LinkageError error) {
                status = "Display failed: " + error.getClass().getSimpleName() + ": " + error.getMessage();
                releaseScreen();
                stopSelf();
            }
        } else if (qdLink == null) {
            startCarBridge();
            startShell();
        } else if (intent != null && ACTION_ACCESSORY.equals(intent.getAction())) {
            qdLink.accessoryAttached();
        }
        return START_STICKY;
    }

    private void startVisibleService() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "LeapBox car display", NotificationManager.IMPORTANCE_LOW));
        }
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("LeapBox car display is active")
                .setContentText("Phone remains independent while C10 projection runs")
                .setSmallIcon(R.drawable.ic_leapbox)
                .setContentIntent(pending)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIFICATION, notification);
        }
    }

    @SuppressWarnings("deprecation")
    private void createScreen() throws Exception {
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        backgroundWakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LeapBox:CarDisplay");
        backgroundWakeLock.setReferenceCounted(false);
        backgroundWakeLock.acquire();

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        // A mostly static dashboard otherwise yields almost no frames, leaving the car without
        // a picture after connecting or requesting a key frame.
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / FPS);
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.start();

        createOwnDisplay();
        showDashboard();
        encoding = true;
        drainThread = new Thread(this::drainFrames, "LeapBox-H264-output");
        drainThread.start();
        status = "LeapBox desktop ready on display #" + displayId();
    }

    /** LeapBox's own display: shows the dashboard, but Android lets no other app open on it. */
    private void createOwnDisplay() {
        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        int displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        virtualDisplay = displayManager.createVirtualDisplay("LeapBox car desktop", WIDTH, HEIGHT,
                CAR_DENSITY, encoderSurface, displayFlags);
        if (virtualDisplay == null) throw new IllegalStateException("Virtual display unavailable");
    }

    // ---- Shizuku: app-hosting car display, app launching and car touch redirection ----

    private void startShell() {
        if (shell == null) {
            shell = new ShellLink(this, new ShellLink.Listener() {
                @Override public void onShellConnected() { mainHandler.post(() -> upgradeDisplay()); }
                @Override public void onShellDisconnected() { mainHandler.post(() -> downgradeDisplay()); }
            });
        }
        Diag.log(ShellLink.state(this));
        shell.bind();
        watchInputDevices();
    }

    /** Called when the user grants Shizuku permission while LeapBox is running. */
    void connectShell() {
        if (encoderSurface == null) return;
        startShell();
    }

    boolean hasShell() { return shellDisplayId >= 0; }

    /** Moves the car picture onto a Shizuku-created display that other apps may open on. */
    private void upgradeDisplay() {
        ShellLink link = shell;
        if (link == null || encoderSurface == null || shellDisplayId >= 0) return;
        dismissCarWindows();
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        shellCalls.execute(() -> {
            int id = link.createDisplay(encoderSurface, WIDTH, HEIGHT, CAR_DENSITY);
            mainHandler.post(() -> {
                if (encoderSurface == null) return;
                if (id < 0) {
                    Diag.log("Shizuku: falling back to LeapBox's own display");
                    createOwnDisplay();
                } else {
                    shellDisplayId = id;
                    status = "Shizuku car display #" + id + " ready for apps";
                }
                showDashboard();
                requestSyncFrame();
                if (id >= 0) startTouchForwarding();
            });
        });
    }

    private void downgradeDisplay() {
        if (shellDisplayId < 0 || encoderSurface == null) return;
        shellDisplayId = -1;
        touchDeviceName = null;
        dismissCarWindows();
        createOwnDisplay();
        showDashboard();
        requestSyncFrame();
    }

    /** Car touches arrive as an external touchscreen on the phone (Bluetooth or USB HID). */
    private void watchInputDevices() {
        if (inputListener != null) return;
        InputManager input = (InputManager) getSystemService(INPUT_SERVICE);
        inputListener = new InputManager.InputDeviceListener() {
            @Override public void onInputDeviceAdded(int deviceId) {
                InputDevice device = InputDevice.getDevice(deviceId);
                if (device != null && device.isExternal()) {
                    Diag.log("Input device added: " + describe(device));
                    startTouchForwarding();
                }
            }
            @Override public void onInputDeviceRemoved(int deviceId) {
                Diag.log("Input device removed: #" + deviceId);
            }
            @Override public void onInputDeviceChanged(int deviceId) { }
        };
        input.registerInputDeviceListener(inputListener, mainHandler);
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device != null && device.isExternal()) Diag.log("External input device: " + describe(device));
        }
    }

    private static String describe(InputDevice device) {
        return String.format(java.util.Locale.US, "\"%s\" #%d %04x:%04x sources=0x%x",
                device.getName(), device.getId(), device.getVendorId(), device.getProductId(),
                device.getSources());
    }

    private static InputDevice findCarTouchDevice() {
        InputDevice mouse = null;
        for (int id : InputDevice.getDeviceIds()) {
            InputDevice device = InputDevice.getDevice(id);
            if (device == null || device.isVirtual() || !device.isExternal()) continue;
            if (device.supportsSource(InputDevice.SOURCE_TOUCHSCREEN)) return device;
            if (mouse == null && device.supportsSource(InputDevice.SOURCE_MOUSE)) mouse = device;
        }
        return mouse;
    }

    private void startTouchForwarding() {
        ShellLink link = shell;
        int id = shellDisplayId;
        if (link == null || id < 0) return;
        InputDevice car = findCarTouchDevice();
        if (car == null) {
            Diag.log("Car touch: no external touchscreen yet; it appears when the car's touch link connects");
            return;
        }
        if (car.getName().equals(touchDeviceName) && link.touchStatus().startsWith("touch: /")) return;
        touchDeviceName = car.getName();
        String name = car.getName();
        int deviceId = car.getId();
        int vendor = car.getVendorId();
        int product = car.getProductId();
        Diag.log("Car touch device: " + describe(car));
        shellCalls.execute(() -> {
            String result = link.startTouch(name, vendor, product, deviceId, id, WIDTH, HEIGHT);
            Diag.log("Car touch → car display: " + result);
            if (result.startsWith("error")) touchDeviceName = null;
        });
    }

    /** Opens an installed app on the car display (needs Shizuku). Returns a message for the car. */
    String openOnCar(String packageName, String label) {
        ShellLink link = shell;
        int id = shellDisplayId;
        if (link == null || id < 0) {
            String state = ShellLink.state(this);
            Diag.log("Open " + label + " on car: not possible; " + state
                    + (link != null && ShellLink.permitted() ? " (helper reconnecting)" : ""));
            if (link != null) link.bind();
            return label + " needs Shizuku · " + state;
        }
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        ComponentName component = launch == null ? null : launch.getComponent();
        if (component == null) return label + " is not installed on the phone";
        CarDashboardPresentation previous = dashboard;
        dashboard = null;
        if (previous != null) previous.dismiss();
        showHomeButton();
        status = label + " on the car display";
        shellCalls.execute(() -> Diag.log("Open " + label + " on car #" + id + ": "
                + link.launch(id, component.flattenToShortString())));
        return "Opening " + label + "…";
    }

    /** Sends BACK to the app on the car display. */
    String carBack() {
        ShellLink link = shell;
        int id = shellDisplayId;
        if (link == null || id < 0) return "Needs Shizuku";
        shellCalls.execute(() -> Diag.log("Car BACK: " + link.key(id, KeyEvent.KEYCODE_BACK)));
        return "Back sent to the car app";
    }

    String shellState() {
        String state = ShellLink.state(this);
        if (shellDisplayId < 0) return state;
        ShellLink link = shell;
        return state + " · app display #" + shellDisplayId
                + (link == null ? "" : " · " + link.touchStatus());
    }

    private Display currentDisplay() {
        if (shellDisplayId >= 0) {
            DisplayManager displays = (DisplayManager) getSystemService(DISPLAY_SERVICE);
            return displays.getDisplay(shellDisplayId);
        }
        return virtualDisplay == null ? null : virtualDisplay.getDisplay();
    }

    private void showHomeButton() {
        Display display = currentDisplay();
        if (display == null || homeButton != null) return;
        homeButton = new CarHomeButton(this, display, this::showDashboard);
        try {
            homeButton.show();
        } catch (RuntimeException error) {
            Diag.log("Car home button failed: " + error);
            homeButton = null;
        }
    }

    private void dismissCarWindows() {
        if (dashboard != null) {
            dashboard.dismiss();
            dashboard = null;
        }
        if (homeButton != null) {
            homeButton.dismiss();
            homeButton = null;
        }
    }

    private void startCarBridge() {
        if (qdLink != null) return;
        qdLink = new QdLinkUsbClient(this, new QdLinkUsbClient.Listener() {
            @Override public void onStatusChanged() { }

            @Override public void onVideoRequested() {
                requestSyncFrame();
            }

            @Override public void onKeyFrameRequested() {
                requestSyncFrame();
            }

            @Override public void onCarTouch(float x, float y, int action, int carWidth, int carHeight) {
                // Arrives on the USB reader thread; views may only be touched on the main thread.
                mainHandler.post(() -> dispatchCarTouch(x, y, action, carWidth, carHeight));
            }
        });
        qdLink.start();
    }

    private void drainFrames() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (encoding) {
            try {
                int index = encoder.dequeueOutputBuffer(info, 100_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    cacheCodecConfig(encoder.getOutputFormat());
                    continue;
                }
                if (index >= 0) {
                    if (info.size > 0) {
                        ByteBuffer buffer = encoder.getOutputBuffer(index);
                        if (buffer != null) {
                            buffer.position(info.offset);
                            buffer.limit(info.offset + info.size);
                            byte[] data = new byte[info.size];
                            buffer.get(data);
                            boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            if (config) {
                                codecConfig = data;
                            } else {
                                frames.incrementAndGet();
                                bytes.addAndGet(info.size);
                                QdLinkUsbClient bridge = qdLink;
                                if (bridge != null) {
                                    // Car decoders commonly need SPS/PPS in front of every IDR frame.
                                    bridge.sendVideo(key ? withCodecConfig(data) : data,
                                            WIDTH, HEIGHT, FPS, key);
                                }
                            }
                        }
                    }
                    encoder.releaseOutputBuffer(index, false);
                }
            } catch (IllegalStateException error) {
                status = "Encoder stopped: " + error.getMessage();
                return;
            }
        }
    }

    private void cacheCodecConfig(MediaFormat outputFormat) {
        ByteBuffer sps = outputFormat.getByteBuffer("csd-0");
        ByteBuffer pps = outputFormat.getByteBuffer("csd-1");
        if (sps == null && pps == null) return;
        byte[] spsBytes = copyRemaining(sps);
        byte[] ppsBytes = copyRemaining(pps);
        byte[] combined = new byte[spsBytes.length + ppsBytes.length];
        System.arraycopy(spsBytes, 0, combined, 0, spsBytes.length);
        System.arraycopy(ppsBytes, 0, combined, spsBytes.length, ppsBytes.length);
        codecConfig = combined;
    }

    private static byte[] copyRemaining(ByteBuffer source) {
        if (source == null) return new byte[0];
        ByteBuffer copy = source.duplicate();
        byte[] bytes = new byte[copy.remaining()];
        copy.get(bytes);
        return bytes;
    }

    private byte[] withCodecConfig(byte[] keyFrame) {
        byte[] config = codecConfig;
        if (config == null || config.length == 0) return keyFrame;
        byte[] combined = new byte[config.length + keyFrame.length];
        System.arraycopy(config, 0, combined, 0, config.length);
        System.arraycopy(keyFrame, 0, combined, config.length, keyFrame.length);
        return combined;
    }

    private void requestSyncFrame() {
        MediaCodec codec = encoder;
        if (codec == null) return;
        try {
            Bundle params = new Bundle();
            params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
            codec.setParameters(params);
        } catch (IllegalStateException ignored) { }
    }

    private void dispatchCarTouch(float carX, float carY, int action,
                                  int sourceWidth, int sourceHeight) {
        CarDashboardPresentation presentation = dashboard;
        if (presentation == null) return;
        float x = carX * WIDTH / Math.max(1f, sourceWidth);
        float y = carY * HEIGHT / Math.max(1f, sourceHeight);
        long now = SystemClock.uptimeMillis();
        if (action == MotionEvent.ACTION_DOWN || lastTouchDownTime == 0) lastTouchDownTime = now;
        MotionEvent event = MotionEvent.obtain(lastTouchDownTime, now, action, x, y, 0);
        presentation.dispatchCarTouch(event);
        event.recycle();
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) lastTouchDownTime = 0;
    }

    boolean isReady() { return virtualDisplay != null || shellDisplayId >= 0; }
    int displayId() {
        if (shellDisplayId >= 0) return shellDisplayId;
        return virtualDisplay == null ? -1 : virtualDisplay.getDisplay().getDisplayId();
    }
    boolean isAwake() { return backgroundWakeLock != null && backgroundWakeLock.isHeld(); }
    long framesEncoded() { return frames.get(); }
    long bytesEncoded() { return bytes.get(); }
    String status() { return status; }
    String carStatus() { return qdLink == null ? "QDLink USB: not started" : qdLink.status(); }
    boolean carConnected() { return qdLink != null && qdLink.isConnected(); }
    boolean carPlaying() { return qdLink != null && qdLink.isPlaying(); }
    String carProtocol() { return qdLink == null ? "unknown" : qdLink.protocol(); }
    int carWidth() { return qdLink == null ? 0 : qdLink.carWidth(); }
    int carHeight() { return qdLink == null ? 0 : qdLink.carHeight(); }
    long carFramesSent() { return qdLink == null ? 0 : qdLink.videoFramesSent(); }
    long carKeyFrames() { return qdLink == null ? 0 : qdLink.keyFramesSent(); }
    long carTouchEvents() { return qdLink == null ? 0 : qdLink.touchEventsReceived(); }
    String usbState() { return qdLink == null ? "" : qdLink.usbState(); }
    String carLog() { return Diag.text(); }

    void reconnectCar() {
        if (qdLink == null) startCarBridge();
        else qdLink.reconnect();
    }

    void stopPrototype() {
        releaseScreen();
        status = "Stopped; background wake lock released";
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    /** Shows the LeapBox desktop on the car (covering any app opened there). */
    void showDashboard() {
        Display display = currentDisplay();
        if (display == null || dashboard != null) return;
        if (homeButton != null) {
            homeButton.dismiss();
            homeButton = null;
        }
        dashboard = new CarDashboardPresentation(this, display, this);
        dashboard.show();
        status = "LeapBox desktop on display #" + displayId();
    }

    private void releaseScreen() {
        encoding = false;
        if (qdLink != null) {
            qdLink.stop();
            qdLink = null;
        }
        if (inputListener != null) {
            ((InputManager) getSystemService(INPUT_SERVICE)).unregisterInputDeviceListener(inputListener);
            inputListener = null;
        }
        dismissCarWindows();
        if (shell != null) {
            ShellLink link = shell;
            shell = null;
            shellDisplayId = -1;
            touchDeviceName = null;
            link.unbind();
        }
        if (virtualDisplay != null) {
            virtualDisplay.release();
            virtualDisplay = null;
        }
        if (drainThread != null && drainThread != Thread.currentThread()) {
            try { drainThread.join(300); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); }
        }
        drainThread = null;
        if (encoder != null) {
            try { encoder.stop(); } catch (IllegalStateException ignored) { }
            encoder.release();
            encoder = null;
        }
        if (encoderSurface != null) {
            encoderSurface.release();
            encoderSurface = null;
        }
        codecConfig = null;
        if (backgroundWakeLock != null) {
            if (backgroundWakeLock.isHeld()) backgroundWakeLock.release();
            backgroundWakeLock = null;
        }
    }

    @Override public void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        releaseScreen();
        shellCalls.shutdownNow();
        super.onDestroy();
    }
}
