package com.leapbox.prototype;

import android.content.Context;
import android.content.ContextWrapper;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
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
    private static final int FLAG_TRUSTED = 1 << 10;
    private static final int FLAG_OWN_DISPLAY_GROUP = 1 << 11;
    private static final int FLAG_ALWAYS_UNLOCKED = 1 << 12;
    private static final int FLAG_OWN_FOCUS = 1 << 14;

    private final Context context;
    private VirtualDisplay display;
    private volatile TouchForwarder touch;

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
                    result = startTouch(data.readString(), data.readInt(), data.readInt(),
                            data.readInt(), data.readInt());
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
                    TouchForwarder forwarder = touch;
                    result = forwarder == null ? "touch: off" : forwarder.status();
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
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
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

    private String startTouch(String deviceName, int deviceId, int displayId, int width, int height)
            throws Exception {
        stopTouch();
        TouchForwarder forwarder = new TouchForwarder(deviceName, deviceId, displayId, width, height);
        String result = forwarder.start();
        touch = forwarder;
        return result;
    }

    private void stopTouch() {
        TouchForwarder forwarder = touch;
        touch = null;
        if (forwarder != null) forwarder.stop();
    }

    private void releaseAll() {
        stopTouch();
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

        TouchForwarder(String deviceName, int deviceId, int displayId, int width, int height) {
            this.deviceName = deviceName;
            this.deviceId = deviceId;
            this.displayId = displayId;
            this.width = width;
            this.height = height;
        }

        String start() throws Exception {
            if (!findDevice()) return "error: no /dev/input device named \"" + deviceName + "\"";
            input = new FileInputStream(path);
            running = true;
            thread = new Thread(this::readLoop, "LeapBox-touch");
            thread.start();
            disabled = setDeviceEnabled(false);
            return "touch: " + path + " x " + minX + ".." + maxX + " y " + minY + ".." + maxY
                    + (disabled ? ", phone input disabled" : ", phone input NOT disabled: " + lastError);
        }

        void stop() {
            running = false;
            try { if (input != null) input.close(); } catch (IOException ignored) { }
            if (disabled) setDeviceEnabled(true);
            disabled = false;
        }

        String status() {
            return String.format(Locale.US, "touch: %s events=%d injected=%d%s%s", path, events,
                    injected, disabled ? " phone-input-off" : "",
                    lastError.isEmpty() ? "" : " last error: " + lastError);
        }

        /** Parses `getevent -pl` for the device path and its coordinate ranges. */
        private boolean findDevice() throws Exception {
            String dump = exec("getevent", "-pl");
            String current = null;
            boolean match = false;
            for (String raw : dump.split(" \\| ")) {
                String line = raw.trim();
                if (line.startsWith("add device")) {
                    int slash = line.indexOf('/');
                    current = slash >= 0 ? line.substring(slash) : null;
                    match = false;
                } else if (line.startsWith("name:")) {
                    match = line.contains("\"" + deviceName + "\"");
                    if (match) path = current;
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
            return path != null;
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
                lastError = (enabled ? "enable: " : "disable: ") + error;
                return false;
            }
        }
    }
}
