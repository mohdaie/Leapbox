package com.leapbox.prototype;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import android.view.Surface;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Shizuku user service. It runs in its own process as the shell user, which may create a trusted
 * display that hosts other apps, launch apps onto it, and read and inject touch input. Apps cannot
 * do any of this themselves. Uses the same hidden APIs as scrcpy's --new-display.
 */
public final class ShellService extends Binder {
    static final String DESCRIPTOR = "com.leapbox.prototype.IShellService";
    static final int CREATE_DISPLAY = FIRST_CALL_TRANSACTION;
    static final int RELEASE_DISPLAY = FIRST_CALL_TRANSACTION + 1;
    static final int LAUNCH = FIRST_CALL_TRANSACTION + 2;
    static final int START_TOUCH = FIRST_CALL_TRANSACTION + 3;
    static final int STOP_TOUCH = FIRST_CALL_TRANSACTION + 4;
    static final int KEY = FIRST_CALL_TRANSACTION + 5;
    static final int STATUS = FIRST_CALL_TRANSACTION + 6;
    /** Shizuku's reserved "destroy" call (AIDL method id 16777114). */
    static final int DESTROY = FIRST_CALL_TRANSACTION + 16777114;

    private static final String SHELL_PACKAGE = "com.android.shell";
    // Hidden VirtualDisplay flags (DisplayManager) used by scrcpy for app-hosting displays.
    /** Gives the display an input viewport, so a touchscreen can be linked to it. */
    private static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int FLAG_TRUSTED = 1 << 10;
    private static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int FLAG_OWN_FOCUS = 1 << 14;

    private final Context context;
    private VirtualDisplay display;
    /** Invisible display the car touchscreen is linked to, so neither phone nor car gets raw touches. */
    private VirtualDisplay sink;
    private ImageReader sinkReader;
    /** One forwarder per car input device (USB and Bluetooth), keyed by device descriptor. */
    private final java.util.Map<String, TouchForwarder> touches = new java.util.LinkedHashMap<>();

    public ShellService() { this(null); }

    public ShellService(Context context) {
        this.context = context != null ? context : systemContext();
    }

    @Override protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) {
        if (code == INTERFACE_TRANSACTION) {
            reply.writeString(DESCRIPTOR);
            return true;
        }
        if (code == DESTROY) {
            releaseAll();
            System.exit(0);
            return true;
        }
        data.enforceInterface(DESCRIPTOR);
        String result;
        try {
            switch (code) {
                case CREATE_DISPLAY:
                    Surface surface = Surface.CREATOR.createFromParcel(data);
                    result = createDisplay(surface, data.readInt(), data.readInt(), data.readInt());
                    break;
                case RELEASE_DISPLAY:
                    releaseAll();
                    result = "released";
                    break;
                case LAUNCH:
                    result = launch(data.readInt(), data.readString());
                    break;
                case START_TOUCH:
                    result = startTouch(data.readString(), data.readString(), data.readInt(),
                            data.readInt(), data.readInt(), data.readInt(), data.readInt(), data.readInt(),
                            data.readInt());
                    break;
                case STOP_TOUCH:
                    stopTouch();
                    result = "touch stopped";
                    break;
                case KEY:
                    result = exec("input", "-d", Integer.toString(data.readInt()),
                            "keyevent", Integer.toString(data.readInt()));
                    break;
                case STATUS:
                    synchronized (touches) {
                        if (touches.isEmpty()) {
                            result = "touch: OFF";
                        } else {
                            StringBuilder all = new StringBuilder();
                            for (TouchForwarder forwarder : touches.values()) {
                                if (all.length() > 0) all.append(" ; ");
                                all.append(forwarder.status());
                            }
                            result = all.toString();
                        }
                    }
                    break;
                default:
                    return false;
            }
        } catch (Throwable error) {
            result = "error: " + error;
        }
        reply.writeNoException();
        reply.writeString(result);
        return true;
    }

    /** Returns "display:<id>" on success. */
    private String createDisplay(Surface surface, int width, int height, int dpi) throws Exception {
        releaseAll();
        DisplayManager manager = shellDisplayManager();
        int base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | FLAG_SUPPORTS_TOUCH;
        int[] attempts = {
                base | FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP | FLAG_ALWAYS_UNLOCKED | FLAG_OWN_FOCUS,
                base | FLAG_TRUSTED | FLAG_OWN_DISPLAY_GROUP,
                base | FLAG_TRUSTED,
        };
        StringBuilder failures = new StringBuilder();
        for (int flags : attempts) {
            try {
                display = manager.createVirtualDisplay("LeapBox car", width, height, dpi, surface, flags);
                if (display != null) {
                    return "display:" + display.getDisplay().getDisplayId()
                            + " flags=0x" + Integer.toHexString(flags)
                            + (failures.length() > 0 ? " (after: " + failures + ")" : "");
                }
            } catch (RuntimeException error) {
                if (failures.length() > 0) failures.append("; ");
                failures.append("0x").append(Integer.toHexString(flags)).append(' ').append(error);
            }
        }
        return "error: no trusted display: " + failures;
    }

    /** A DisplayManager whose calls identify as the shell package, matching this process's uid. */
    private DisplayManager shellDisplayManager() throws Exception {
        Context shell = new ContextWrapper(context) {
            @Override public String getPackageName() { return SHELL_PACKAGE; }
            @Override public String getOpPackageName() { return SHELL_PACKAGE; }
            @Override public Context getApplicationContext() { return this; }
        };
        Constructor<DisplayManager> constructor = DisplayManager.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        return constructor.newInstance(shell);
    }

    private String launch(int displayId, String component) throws Exception {
        return exec("am", "start", "--display", Integer.toString(displayId),
                "--windowingMode", "1", "-f", "0x10000000", "-n", component);
    }

    /**
     * mode 0: Android links the touchscreen to the car display and scales touches itself.
     * mode 1: the touchscreen is linked to an invisible sink display; LeapBox reads the raw
     *         touches, scales them to the car display and injects them.
     */
    private String startTouch(String deviceName, String descriptor, int vendor, int product, int deviceId,
                              int displayId, int width, int height, int mode) throws Exception {
        String key = descriptor == null ? deviceName + deviceId : descriptor;
        TouchForwarder previous;
        synchronized (touches) { previous = touches.remove(key); }
        if (previous != null) previous.stop();
        VirtualDisplay target = display;
        if (mode == 1) {
            if (sink == null) {
                sinkReader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2);
                sink = shellDisplayManager().createVirtualDisplay("LeapBox touch sink", 64, 64, 160,
                        sinkReader.getSurface(), DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                                | FLAG_SUPPORTS_TOUCH);
            }
            target = sink;
        }
        String uniqueId = null;
        int targetId = -1;
        if (target != null) {
            targetId = target.getDisplay().getDisplayId();
            try {
                uniqueId = (String) android.view.Display.class.getMethod("getUniqueId")
                        .invoke(target.getDisplay());
            } catch (ReflectiveOperationException ignored) { }
        }
        TouchForwarder forwarder = new TouchForwarder(deviceName, descriptor, uniqueId, targetId, mode == 1,
                vendor, product, deviceId, displayId, width, height);
        String result = forwarder.start();
        synchronized (touches) { touches.put(key, forwarder); }
        return result;
    }

    private void stopTouch() {
        java.util.List<TouchForwarder> all;
        synchronized (touches) {
            all = new java.util.ArrayList<>(touches.values());
            touches.clear();
        }
        for (TouchForwarder forwarder : all) forwarder.stop();
    }

    private void releaseAll() {
        stopTouch();
        if (sink != null) {
            sink.release();
            sink = null;
        }
        if (sinkReader != null) {
            sinkReader.close();
            sinkReader = null;
        }
        if (display != null) {
            display.release();
            display = null;
        }
    }

    static String exec(String... command) throws IOException, InterruptedException {
        java.lang.Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        StringBuilder out = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (out.length() > 0) out.append(" | ");
                out.append(line.trim());
            }
        }
        if (!process.waitFor(10, TimeUnit.SECONDS)) process.destroy();
        return out.length() == 0 ? "ok" : out.toString();
    }

    private static Context systemContext() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Object thread = activityThread.getMethod("systemMain").invoke(null);
            return (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("No context for shell service", error);
        }
    }

    /**
     * Reads the car's touchscreen straight from /dev/input (the shell user may), turns it into
     * touches on the car display, and disables the device for the phone so taps stop landing on
     * the phone screen.
     */
    private static final class TouchForwarder {
        private static final int EV_SYN = 0, EV_KEY = 1, EV_ABS = 3;
        private static final int ABS_X = 0x00, ABS_Y = 0x01, ABS_MT_SLOT = 0x2f;
        private static final int ABS_MT_POSITION_X = 0x35, ABS_MT_POSITION_Y = 0x36;
        private static final int ABS_MT_TRACKING_ID = 0x39;
        private static final int BTN_LEFT = 0x110, BTN_TOUCH = 0x14a;

        private final String deviceName;
        private final String descriptor;
        private final String displayUniqueId;
        private final int linkDisplayId;
        private final boolean injectMode;
        private volatile boolean linked;
        private volatile boolean forwarding;
        private String linkMethod = "";
        private String location;
        private final StringBuilder samples = new StringBuilder();
        private final int vendor;
        private final int product;
        private final int deviceId;
        private final int displayId;
        private final int width;
        private final int height;
        private volatile boolean running;
        private volatile boolean disabled;
        private volatile long events;
        private volatile long injected;
        private volatile String lastError = "";
        private Thread thread;
        private InputStream input;
        private String path;
        private int minX, maxX = 1, minY, maxY = 1;

        TouchForwarder(String deviceName, String descriptor, String displayUniqueId, int linkDisplayId,
                       boolean injectMode, int vendor, int product, int deviceId, int displayId,
                       int width, int height) {
            this.deviceName = deviceName;
            this.descriptor = descriptor;
            this.displayUniqueId = displayUniqueId;
            this.linkDisplayId = linkDisplayId;
            this.injectMode = injectMode;
            this.vendor = vendor;
            this.product = product;
            this.deviceId = deviceId;
            this.displayId = displayId;
            this.width = width;
            this.height = height;
        }

        String start() throws Exception {
            // Preferred: let Android itself route the car touchscreen to the car display, as it
            // does for head-unit touchscreens. Android then scales touches and the phone ignores them.
            boolean found = findDevice();
            String link = linkToDisplay();
            String ranges = " raw x " + minX + ".." + maxX + " y " + minY + ".." + maxY;
            if (found) {
                if (maxX <= 1) maxX = width - 1;
                if (maxY <= 1) maxY = height - 1;
                input = new FileInputStream(path);
                running = true;
                thread = new Thread(this::readLoop, "LeapBox-touch");
                thread.start();
            }
            if (!injectMode && linked) {
                return "touch: ANDROID mode, linked to car display (" + link + ")" + ranges;
            }
            if (!found) {
                return String.format(Locale.US, "error: link %s; no /dev/input device \"%s\" %04x:%04x",
                        link, deviceName, vendor, product);
            }
            forwarding = true;
            if (!linked) disabled = setDeviceEnabled(false);
            return "touch: LEAPBOX mode, forwarding " + path + ranges
                    + (linked ? ", raw touches parked on sink display (" + link + ")"
                              : ", sink link failed (" + link + ")"
                                + (disabled ? ", phone input disabled" : ", phone input NOT disabled: " + lastError));
        }

        /** Associates the input device with the car display; returns a short result text. */
        private String linkToDisplay() {
            if (displayUniqueId == null) return "no display id";
            try {
                Object service = inputService();
                Class<?> type = service.getClass();
                try {
                    // Android 15+
                    type.getMethod("addUniqueIdAssociationByDescriptor", String.class, String.class)
                            .invoke(service, descriptor, displayUniqueId);
                    linkMethod = "descriptor";
                } catch (NoSuchMethodException older) {
                    if (location == null || location.isEmpty()) return "no input port for older Android";
                    try {
                        type.getMethod("addUniqueIdAssociationByPort", String.class, String.class)
                                .invoke(service, location, displayUniqueId);
                    } catch (NoSuchMethodException android14) {
                        type.getMethod("addUniqueIdAssociation", String.class, String.class)
                                .invoke(service, location, displayUniqueId);
                    }
                    linkMethod = "port " + location;
                }
                Thread.sleep(400);
                int associated = associatedDisplay();
                linked = associated == linkDisplayId;
                return "by " + linkMethod + " → device now on display " + associated;
            } catch (ReflectiveOperationException | RuntimeException | InterruptedException error) {
                return describeError(error);
            }
        }

        private void unlink() {
            if (!linked) return;
            linked = false;
            try {
                Object service = inputService();
                Class<?> type = service.getClass();
                if ("descriptor".equals(linkMethod)) {
                    type.getMethod("removeUniqueIdAssociationByDescriptor", String.class).invoke(service, descriptor);
                } else {
                    try {
                        type.getMethod("removeUniqueIdAssociationByPort", String.class).invoke(service, location);
                    } catch (NoSuchMethodException android14) {
                        type.getMethod("removeUniqueIdAssociation", String.class).invoke(service, location);
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException error) {
                lastError = "unlink: " + describeError(error);
            }
        }

        /** The display Android currently routes this device to (hidden InputDevice API). */
        private int associatedDisplay() {
            try {
                Object manager;
                try {
                    manager = Class.forName("android.hardware.input.InputManagerGlobal")
                            .getMethod("getInstance").invoke(null);
                } catch (ClassNotFoundException oldAndroid) {
                    manager = Class.forName("android.hardware.input.InputManager")
                            .getMethod("getInstance").invoke(null);
                }
                InputDevice device = (InputDevice) manager.getClass().getMethod("getInputDevice", int.class)
                        .invoke(manager, deviceId);
                if (device == null) return -2;
                return (Integer) InputDevice.class.getMethod("getAssociatedDisplayId").invoke(device);
            } catch (ReflectiveOperationException | RuntimeException error) {
                return -3;
            }
        }

        private static Object inputService() throws ReflectiveOperationException {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "input");
            return Class.forName("android.hardware.input.IInputManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
        }

        private static String describeError(Throwable error) {
            Throwable cause = error instanceof java.lang.reflect.InvocationTargetException
                    && error.getCause() != null ? error.getCause() : error;
            return cause.getClass().getSimpleName() + ": " + cause.getMessage();
        }

        void stop() {
            running = false;
            try { if (input != null) input.close(); } catch (IOException ignored) { }
            if (disabled) setDeviceEnabled(true);
            disabled = false;
            unlink();
        }

        String status() {
            return String.format(Locale.US, "touch: %s %s x..%d y..%d events=%d injected=%d%s%s%s",
                    forwarding ? "LEAPBOX" : linked ? "ANDROID" : "OFF", path,
                    maxX, maxY, events, injected, disabled ? " phone-input-off" : "",
                    lastError.isEmpty() ? "" : " last error: " + lastError,
                    samples.length() == 0 ? "" : " first: " + samples);
        }

        /**
         * Parses `getevent -il` for the device path and its coordinate ranges. The C10's touch
         * device has an empty name, so vendor and product ids decide when they are known.
         */
        private boolean findDevice() throws Exception {
            String dump = exec("getevent", "-il");
            // Prefer the device with these ids that reports touch coordinates, then any with these ids,
            // then any device with this name.
            return findDevice(dump, true, true) || findDevice(dump, true, false)
                    || findDevice(dump, false, true) || findDevice(dump, false, false);
        }

        private boolean findDevice(String dump, boolean useIds, boolean needRanges) {
            path = null;
            location = null;
            minX = 0;
            minY = 0;
            maxX = 1;
            maxY = 1;
            String current = null;
            int devVendor = -1, devProduct = -1;
            boolean match = false;
            for (String raw : dump.split(" \\| ")) {
                String line = raw.trim();
                if (line.startsWith("ABS (0003):")) line = line.substring("ABS (0003):".length()).trim();
                if (line.startsWith("add device")) {
                    if (path != null && needRanges && maxX <= 1) path = null;
                    if (path != null) break;
                    int slash = line.indexOf('/');
                    current = slash >= 0 ? line.substring(slash) : null;
                    devVendor = -1;
                    devProduct = -1;
                    match = false;
                } else if (line.startsWith("vendor")) {
                    devVendor = hex(line.substring("vendor".length()));
                } else if (line.startsWith("product")) {
                    devProduct = hex(line.substring("product".length()));
                } else if (line.startsWith("name:")) {
                    boolean ids = !useIds || (vendor == 0 && product == 0)
                            || (devVendor == vendor && devProduct == product);
                    match = path == null && ids && line.contains("\"" + deviceName + "\"");
                    if (match) {
                        path = current;
                        maxX = 1;
                        maxY = 1;
                    }
                } else if (match && line.startsWith("location:")) {
                    int open = line.indexOf('"');
                    int close = line.lastIndexOf('"');
                    location = open >= 0 && close > open ? line.substring(open + 1, close) : "";
                } else if (match) {
                    if (line.startsWith("ABS_MT_POSITION_X") || (line.startsWith("ABS_X") && maxX == 1)) {
                        minX = rangeValue(line, "min");
                        maxX = rangeValue(line, "max");
                    } else if (line.startsWith("ABS_MT_POSITION_Y") || (line.startsWith("ABS_Y") && maxY == 1)) {
                        minY = rangeValue(line, "min");
                        maxY = rangeValue(line, "max");
                    }
                }
            }
            if (path != null && needRanges && maxX <= 1) path = null;
            return path != null;
        }

        private static int hex(String value) {
            try { return Integer.parseInt(value.trim(), 16); }
            catch (NumberFormatException error) { return -1; }
        }

        private static int rangeValue(String line, String key) {
            int at = line.indexOf(key + " ");
            if (at < 0) return 0;
            int start = at + key.length() + 1;
            int end = start;
            while (end < line.length() && (Character.isDigit(line.charAt(end)) || line.charAt(end) == '-')) end++;
            try { return Integer.parseInt(line.substring(start, end)); }
            catch (NumberFormatException error) { return 0; }
        }

        private void readLoop() {
            int size = Process.is64Bit() ? 24 : 16;
            byte[] buffer = new byte[size];
            ByteBuffer event = ByteBuffer.wrap(buffer).order(ByteOrder.nativeOrder());
            boolean down = false, wasDown = false;
            int slot = 0;
            float x = 0, y = 0, lastX = -1, lastY = -1;
            long downTime = 0;
            try {
                while (running) {
                    int read = 0;
                    while (read < size) {
                        int n = input.read(buffer, read, size - read);
                        if (n < 0) return;
                        read += n;
                    }
                    int type = event.getShort(size - 8) & 0xffff;
                    int code = event.getShort(size - 6) & 0xffff;
                    int value = event.getInt(size - 4);
                    events++;
                    if (events <= 12 && type != EV_SYN) {
                        samples.append(Integer.toHexString(type)).append('/')
                                .append(Integer.toHexString(code)).append('=').append(value).append(' ');
                    }
                    // Unknown or too-small ranges: learn them from the values the car actually sends.
                    if (type == EV_ABS && (code == ABS_MT_POSITION_X || code == ABS_X) && value > maxX) maxX = value;
                    if (type == EV_ABS && (code == ABS_MT_POSITION_Y || code == ABS_Y) && value > maxY) maxY = value;
                    if (type == EV_ABS) {
                        if (code == ABS_MT_SLOT) slot = value;
                        else if (slot != 0) continue;
                        else if (code == ABS_MT_TRACKING_ID) down = value != -1;
                        else if (code == ABS_MT_POSITION_X || code == ABS_X) x = scale(value, minX, maxX, width);
                        else if (code == ABS_MT_POSITION_Y || code == ABS_Y) y = scale(value, minY, maxY, height);
                    } else if (type == EV_KEY && (code == BTN_TOUCH || code == BTN_LEFT)) {
                        down = value != 0;
                    } else if (type == EV_SYN && code == 0) {
                        long now = SystemClock.uptimeMillis();
                        if (!forwarding) {
                            wasDown = down;
                            continue;
                        }
                        if (down && !wasDown) {
                            downTime = now;
                            inject(downTime, now, MotionEvent.ACTION_DOWN, x, y);
                        } else if (down && (x != lastX || y != lastY)) {
                            inject(downTime, now, MotionEvent.ACTION_MOVE, x, y);
                        } else if (!down && wasDown) {
                            inject(downTime, now, MotionEvent.ACTION_UP, x, y);
                        }
                        wasDown = down;
                        lastX = x;
                        lastY = y;
                    }
                }
            } catch (IOException error) {
                if (running) lastError = error.toString();
            }
        }

        private static float scale(int value, int min, int max, int size) {
            float span = Math.max(1, max - min);
            return Math.max(0, Math.min(size - 1, (value - min) * (size - 1) / span));
        }

        private void inject(long downTime, long now, int action, float x, float y) {
            MotionEvent motion = MotionEvent.obtain(downTime, now, action, x, y, 0);
            motion.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            try {
                MotionEvent.class.getMethod("setDisplayId", int.class).invoke(motion, displayId);
                Object manager;
                try {
                    manager = Class.forName("android.hardware.input.InputManagerGlobal")
                            .getMethod("getInstance").invoke(null);
                } catch (ClassNotFoundException oldAndroid) {
                    manager = Class.forName("android.hardware.input.InputManager")
                            .getMethod("getInstance").invoke(null);
                }
                Method inject = manager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
                inject.invoke(manager, motion, 0);
                injected++;
            } catch (ReflectiveOperationException | RuntimeException error) {
                lastError = "inject: " + error;
            } finally {
                motion.recycle();
            }
        }

        private boolean setDeviceEnabled(boolean enabled) {
            try {
                IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                        .getMethod("getService", String.class).invoke(null, "input");
                Object service = Class.forName("android.hardware.input.IInputManager$Stub")
                        .getMethod("asInterface", IBinder.class).invoke(null, binder);
                service.getClass().getMethod(enabled ? "enableInputDevice" : "disableInputDevice", int.class)
                        .invoke(service, deviceId);
                return true;
            } catch (ReflectiveOperationException | RuntimeException error) {
                lastError = (enabled ? "enable: " : "disable: ") + describeError(error);
                return false;
            }
        }
    }
}
