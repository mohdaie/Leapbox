package com.leapbox.prototype;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Experimental phone-side QDLink USB endpoint.
 *
 * The C10 stays on its built-in QDLink receiver. LeapBox replaces the normal
 * phone-side mirroring app: it owns the Android Open Accessory file descriptor,
 * negotiates QDLink v2 (5A5A), sends H.264 from LeapBox's independent display,
 * and receives reverse-touch events from the car.
 */
final class QdLinkUsbClient {
    interface Listener {
        void onStatusChanged();
        void onVideoRequested();
        void onKeyFrameRequested();
        void onCarTouch(float x, float y, int action, int carWidth, int carHeight);
    }

    private static final String ACTION_USB_PERMISSION = "com.leapbox.prototype.USB_PERMISSION";
    /** Hidden system broadcast; its extras say whether the phone is in accessory mode. */
    private static final String ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE";
    private static final long RETRY_MS = 3000;
    private static final int LOG_LINES = 80;
    static final String VERSION = "0.3.1";
    private static final int USB_CHUNK = 512;
    private static final int MAX_MESSAGE = 8 * 1024 * 1024;

    private final Context context;
    private final UsbManager usbManager;
    private final Listener listener;
    private final Object writeLock = new Object();
    private final Object sessionLock = new Object();
    private final ArrayDeque<String> log = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong videoFrames = new AtomicLong();
    private final AtomicLong touchEvents = new AtomicLong();
    private final AtomicLong keyFrames = new AtomicLong();

    private ParcelFileDescriptor descriptor;
    private FileInputStream input;
    private FileOutputStream output;
    private Thread readerThread;
    private Thread heartbeatThread;
    private Thread watchThread;
    private BroadcastReceiver permissionReceiver;
    private BroadcastReceiver usbReceiver;
    private volatile boolean permissionPending;
    /** Set after a permission denial so the watcher does not re-prompt every few seconds. */
    private volatile boolean permissionDenied;
    private volatile String lastAccessories = "";
    private volatile long lastAttempt;
    private volatile String usbState = "USB cable: unknown";
    private volatile UsbAccessory accessory;
    private volatile String status = "QDLink USB: not started";
    private volatile String protocol = "unknown";
    private volatile boolean connected;
    private volatile boolean playing;
    private volatile int carWidth = 1920;
    private volatile int carHeight = 882;
    private volatile int fps = 24;
    private volatile int bitRate = 2_500_000;
    private volatile int frameInterval = 2;

    QdLinkUsbClient(Context context, Listener listener) {
        this.context = context.getApplicationContext();
        this.listener = listener;
        this.usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    void start() {
        if (running.getAndSet(true)) return;
        log("LeapBox " + VERSION + " on " + Build.MANUFACTURER + " " + Build.MODEL
                + " / Android " + Build.VERSION.RELEASE + " (API " + Build.VERSION.SDK_INT + ")");
        registerReceivers();
        connectBestAccessory(true);
        startWatcher();
    }

    void reconnect() {
        log("Manual reconnect requested");
        stopSessionOnly();
        permissionPending = false;
        permissionDenied = false;
        if (!running.get()) start();
        else connectBestAccessory(true);
    }

    /** Called when Android reports the car accessory attached (e.g. the app was launched by it). */
    void accessoryAttached() {
        log("Android delivered USB_ACCESSORY_ATTACHED");
        if (!connected) connectBestAccessory(true);
    }

    void stop() {
        running.set(false);
        stopSessionOnly();
        Thread watcher = watchThread;
        watchThread = null;
        if (watcher != null) watcher.interrupt();
        unregister(permissionReceiver);
        unregister(usbReceiver);
        permissionReceiver = null;
        usbReceiver = null;
        setStatus("QDLink USB: stopped");
    }

    private void unregister(BroadcastReceiver receiver) {
        if (receiver == null) return;
        try { context.unregisterReceiver(receiver); } catch (RuntimeException ignored) { }
    }

    boolean isConnected() { return connected; }
    boolean isPlaying() { return playing; }
    int carWidth() { return carWidth; }
    int carHeight() { return carHeight; }
    String protocol() { return protocol; }
    long videoFramesSent() { return videoFrames.get(); }
    long touchEventsReceived() { return touchEvents.get(); }
    long keyFramesSent() { return keyFrames.get(); }
    String status() { return status; }
    String usbState() { return usbState; }

    String logText() {
        synchronized (log) { return String.join("\n", log); }
    }

    boolean sendVideo(byte[] h264, int width, int height, int frameRate, boolean keyFrame) {
        if (!connected || !playing || output == null || h264 == null || h264.length == 0) {
            return false;
        }
        if (!"v2".equals(protocol)) return false;
        try {
            writePadded(buildVideoPacket(h264, width, height, frameRate));
            long sent = videoFrames.incrementAndGet();
            long keys = keyFrame ? keyFrames.incrementAndGet() : keyFrames.get();
            if (sent <= 3 || (keyFrame && keys <= 10)) {
                log("phone → video #" + sent + (keyFrame ? " KEY" : "") + " " + h264.length
                        + " B: " + hexPrefix(h264, 8));
            }
            return true;
        } catch (IOException error) {
            sessionFailed("video write failed", error);
            return false;
        }
    }

    /** Retries while LeapBox runs, so plugging in after pressing Start still connects. */
    private void startWatcher() {
        if (watchThread != null && watchThread.isAlive()) return;
        watchThread = new Thread(() -> {
            while (running.get()) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException interrupted) {
                    return;
                }
                if (!connected && !permissionPending && !permissionDenied
                        && SystemClock.elapsedRealtime() - lastAttempt >= RETRY_MS) {
                    connectBestAccessory(false);
                }
            }
        }, "LeapBox-USB-watch");
        watchThread.start();
    }

    private void connectBestAccessory(boolean verbose) {
        synchronized (sessionLock) {
            if (!running.get() || connected || descriptor != null) return;
            lastAttempt = SystemClock.elapsedRealtime();
            if (usbManager == null) {
                setStatus("QDLink USB: UsbManager unavailable");
                return;
            }
            UsbAccessory[] list = usbManager.getAccessoryList();
            if (list == null || list.length == 0) {
                String waiting = "QDLink USB: waiting for car accessory (" + usbState + ")";
                if (verbose || !waiting.equals(status)) setStatus(waiting);
                return;
            }
            StringBuilder found = new StringBuilder();
            for (UsbAccessory item : list) {
                if (found.length() > 0) found.append("; ");
                found.append("Accessory found: ").append(describeFully(item));
            }
            if (!found.toString().equals(lastAccessories)) {
                lastAccessories = found.toString();
                log(lastAccessories);
            }
            connectFrom(list);
        }
    }

    private void connectFrom(UsbAccessory[] list) {
        UsbAccessory selected = null;
        for (UsbAccessory item : list) {
            String model = safe(item.getModel());
            String manufacturer = safe(item.getManufacturer());
            if (model.toLowerCase(Locale.US).contains("qdrive")
                    || manufacturer.toLowerCase(Locale.US).contains("neusoft")) {
                selected = item;
                break;
            }
        }
        if (selected == null) selected = list[0];
        accessory = selected;

        if (!usbManager.hasPermission(selected)) {
            permissionPending = true;
            Intent permission = new Intent(ACTION_USB_PERMISSION).setPackage(context.getPackageName());
            PendingIntent pending = PendingIntent.getBroadcast(context, 0, permission,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            usbManager.requestPermission(selected, pending);
            setStatus("QDLink USB: waiting for permission for " + describe(selected));
            return;
        }
        openAccessory(selected);
    }

    private void openAccessory(UsbAccessory target) {
        synchronized (sessionLock) {
            if (!running.get() || connected || descriptor != null) return;
            lastAttempt = SystemClock.elapsedRealtime();
            try {
                descriptor = usbManager.openAccessory(target);
                if (descriptor == null) {
                    setStatus("QDLink USB: Android refused to open " + describe(target)
                            + "; another app (phone QDLink?) may own it");
                    return;
                }
                input = new FileInputStream(descriptor.getFileDescriptor());
                output = new FileOutputStream(descriptor.getFileDescriptor());
                connected = true;
                playing = false;
                protocol = "probing";
                setStatus("QDLink USB: opened " + describe(target) + "; probing protocol");
                readerThread = new Thread(this::sessionLoop, "LeapBox-QDLink-reader");
                readerThread.start();
            } catch (RuntimeException error) {
                setStatus("QDLink USB: open failed: " + error);
                stopSessionOnly();
            }
        }
    }

    private void sessionLoop() {
        try {
            writeRaw(buildAppStatusProbe());
            log("phone → !BIN app-status probe (512 B); waiting for car reply");
            byte[] first = readUsbPacket();
            log("car → first reply " + first.length + " B: " + hexPrefix(first, 32));
            if (startsWith(first, "5A5A")) {
                protocol = "v2";
                setStatus("QDLink USB: protocol v2 detected; negotiating C10");
                handleV2(first);
                startHeartbeat();
                while (running.get() && connected) handleV2(readUsbPacket());
            } else if (startsWith(first, "!BIN")) {
                protocol = "v1";
                setStatus("QDLink USB: legacy v1 detected; capture saved for next bridge step");
                while (running.get() && connected) {
                    byte[] packet = readUsbPacket();
                    log("car → v1 " + packet.length + " B: " + hexPrefix(packet, 24));
                }
            } else {
                protocol = "unknown";
                setStatus("QDLink USB: unknown response " + hexPrefix(first, 12));
            }
        } catch (IOException | RuntimeException error) {
            if (running.get()) sessionFailed("session ended", error);
        } finally {
            connected = false;
            playing = false;
            closeIo();
            lastAttempt = SystemClock.elapsedRealtime();
            notifyStatus();
        }
    }

    private void handleV2(byte[] packet) throws IOException {
        if (packet.length < 16 || !startsWith(packet, "5A5A")) return;
        int declared = int32(packet, 4);
        if (declared < 16 || declared > packet.length) return;
        int messageType = packet[10] & 0xff;
        int source = packet[11] & 0xff;
        int payloadFormat = packet[13] & 0xff;
        if (source != 1) {
            log("car → 5A5A type=" + messageType + " source=" + source + " (ignored)");
            return;
        }

        if (messageType == 2) {
            parseTouch(packet, declared);
            return;
        }
        if (payloadFormat != 1) return;
        String jsonText = new String(packet, 16, declared - 16, StandardCharsets.UTF_8);
        JSONObject root;
        try { root = new JSONObject(jsonText); }
        catch (Exception ignored) {
            log("car → 5A5A type=" + messageType + " unparsable JSON: " + trim(jsonText, 80));
            return;
        }
        JSONObject para = root.optJSONObject("PARA");
        String cmd = root.optString("CMD", "");
        if (!"HEARTBEAT".equals(cmd)) log("car → " + trim(jsonText, 160));

        switch (cmd) {
            case "CAR_INFO":
                if (para != null) {
                    carWidth = positive(para.optInt("CarWidth", carWidth), carWidth);
                    carHeight = positive(para.optInt("CarHeight", carHeight), carHeight);
                }
                sendPhoneInfo(para == null ? 0 : para.optInt("MirrorTypeReq", 0));
                writePadded(control("UPDATE_NOTIFY", jsonObject("UpdateStatus", 5), 1));
                writePadded(control("VIDEO_SUP_RSP",
                        jsonObject("VideoFormat", 3, "VideoSupport", 1), 1));
                writePadded(whitelist());
                setStatus("QDLink USB: C10 negotiated " + carWidth + "×" + carHeight + "; waiting for video start");
                break;
            case "VIDEO_SUP_REQ":
                int format = para == null ? 3 : para.optInt("VideoFormat", 3);
                writePadded(control("VIDEO_SUP_RSP",
                        jsonObject("VideoFormat", format, "VideoSupport", format == 3 ? 1 : 0), 1));
                break;
            case "LAND_MODE_REQ":
                writePadded(control("LAND_MODE_RSP",
                        jsonObject("Orientation", 1, "Authority", 1, "StatusArg", 1), 1));
                writePadded(control("PHONE_INFO_CHANGE", phoneInfoChange(), 1));
                break;
            case "VIDEO_ARGS":
                if (para != null) {
                    fps = positive(para.optInt("FrameRate", fps), fps);
                    bitRate = positive(para.optInt("BitRate", bitRate), bitRate);
                    frameInterval = positive(para.optInt("FrameInterval", frameInterval), frameInterval);
                }
                break;
            case "VIDEO_CTRL":
                playing = para != null && para.optInt("PlayStatus", 0) == 1;
                setStatus(playing
                        ? "QDLink USB: C10 requested video; LeapBox desktop streaming"
                        : "QDLink USB: C10 paused video");
                if (playing && listener != null) listener.onVideoRequested();
                break;
            case "KEY_FRAME_REQ":
                log("key frame requested; sent so far " + videoFrames.get() + " frames, "
                        + keyFrames.get() + " key");
                if (listener != null) listener.onKeyFrameRequested();
                break;
            case "HEARTBEAT":
                writePadded(control("HEARTBEAT", null, 1));
                break;
            case "DISCONNECT_REQ":
                writePadded(control("DISCONNECT_RSP", null, 1));
                break;
            default:
                break;
        }
    }

    private void sendPhoneInfo(int mirrorType) throws IOException {
        JSONObject info = jsonObject(
                "PhoneName", "LeapBox",
                "PhoneUUID", Build.FINGERPRINT == null ? "LeapBox" : trim(Build.FINGERPRINT, 16),
                "PhoneBrand", Build.MANUFACTURER,
                "PhoneModel", Build.MODEL,
                "Version", VERSION,
                "Platform", 0,
                "PlatformVersion", Integer.toString(Build.VERSION.SDK_INT),
                "PhoneWidth", carWidth,
                "PhoneHeight", carHeight,
                "MirrorWidth", carWidth,
                "MirrorHeight", carHeight,
                "PhoneWidthInApp", carWidth,
                "PhoneHeightInApp", carHeight,
                "MirrorWidthInApp", carWidth,
                "MirrorHeightInApp", carHeight,
                "MirrorTypeSupport", mirrorType,
                "PhoneSystemTime", System.currentTimeMillis(),
                "PhoneFeature", jsonObject("PassistMobileNum", ""));
        writePadded(control("PHONE_INFO", info, 1));
    }

    private JSONObject phoneInfoChange() {
        return jsonObject(
                "PhoneWidth", carWidth,
                "PhoneHeight", carHeight,
                "MirrorWidth", carWidth,
                "MirrorHeight", carHeight,
                "PhoneWidthInApp", carWidth,
                "PhoneHeightInApp", carHeight,
                "MirrorWidthInApp", carWidth,
                "MirrorHeightInApp", carHeight);
    }

    private void parseTouch(byte[] packet, int declared) {
        if (declared < 31) return;
        int body = 16;
        int actionId = int32(packet, body);
        int fingerAction = packet.length > body + 6 ? packet[body + 6] & 0xff : -1;
        int action;
        if (fingerAction == 1) action = 0;
        else if (fingerAction == 2) action = 1;
        else if (fingerAction == 3) action = 2;
        else if (actionId == 0) action = 0;
        else if (actionId == 1) action = 1;
        else if (actionId == 2) action = 2;
        else return;
        float x = float32(packet, body + 7);
        float y = float32(packet, body + 11);
        if (touchEvents.incrementAndGet() <= 3) {
            log(String.format(Locale.US, "car → touch action=%d x=%.1f y=%.1f", action, x, y));
        }
        if (listener != null) listener.onCarTouch(x, y, action, carWidth, carHeight);
    }

    private void startHeartbeat() {
        if (heartbeatThread != null && heartbeatThread.isAlive()) return;
        heartbeatThread = new Thread(() -> {
            while (running.get() && connected && "v2".equals(protocol)) {
                try {
                    Thread.sleep(3000);
                    if (running.get() && connected) writePadded(control("HEARTBEAT", null, 1));
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (IOException error) {
                    sessionFailed("heartbeat failed", error);
                    return;
                }
            }
        }, "LeapBox-QDLink-heartbeat");
        heartbeatThread.start();
    }

    private byte[] readUsbPacket() throws IOException {
        byte[] first = new byte[USB_CHUNK];
        readFully(first, 0, first.length);
        if (!startsWith(first, "5A5A")) return first;
        int declared = int32(first, 4);
        if (declared < 16 || declared > MAX_MESSAGE) return first;
        int padded = ((declared + USB_CHUNK - 1) / USB_CHUNK) * USB_CHUNK;
        if (padded <= USB_CHUNK) return first;
        byte[] all = new byte[padded];
        System.arraycopy(first, 0, all, 0, first.length);
        readFully(all, USB_CHUNK, padded - USB_CHUNK);
        return all;
    }

    private void readFully(byte[] target, int offset, int length) throws IOException {
        int read = 0;
        while (read < length && running.get() && connected) {
            int n = input.read(target, offset + read, length - read);
            if (n < 0) throw new IOException("USB EOF");
            if (n == 0) continue;
            read += n;
        }
        if (read != length) throw new IOException("short USB packet " + read + "/" + length);
    }

    private byte[] buildAppStatusProbe() {
        byte[] data = new byte[USB_CHUNK];
        copyAscii("!BIN", data, 0);
        putInt(data, 4, 0);
        putInt(data, 8, 512);
        putInt(data, 12, 512);
        putInt(data, 16, 64);
        putInt(data, 20, 128);
        putInt(data, 24, 128);
        putInt(data, 28, 2);
        for (int i = 0; i < 32; i++) data[32 + i] = (byte) (i + 32);
        putInt(data, 64, 0);
        putInt(data, 68, 1);
        putInt(data, 72, 1);
        putInt(data, 76, Build.VERSION.SDK_INT);
        putInt(data, 80, 2);
        return data;
    }

    private byte[] control(String command, JSONObject para, int flag) {
        JSONObject root = jsonObject("CMD", command);
        if (para != null) putJson(root, "PARA", para);
        byte[] body = root.toString().getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[16 + body.length];
        copyAscii("5A5A", message, 0);
        putInt(message, 4, message.length);
        message[13] = (byte) flag;
        System.arraycopy(body, 0, message, 16, body.length);
        return message;
    }

    private byte[] whitelist() {
        JSONObject body = jsonObject(
                "AppID", "Mirror",
                "FunctionID", "WhitelistAppOn",
                "Para", jsonObject("WhitelistAppOn", 1));
        byte[] json = body.toString().getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[16 + json.length];
        copyAscii("5A5A", message, 0);
        putInt(message, 4, message.length);
        message[10] = 13;
        message[13] = 1;
        System.arraycopy(json, 0, message, 16, json.length);
        return message;
    }

    private byte[] buildVideoPacket(byte[] h264, int width, int height, int frameRate) {
        byte[] message = new byte[48 + h264.length];
        copyAscii("5A5A", message, 0);
        putInt(message, 4, message.length);
        putShort(message, 8, 32);
        message[10] = 1;
        message[11] = 0;
        message[12] = 0;
        message[13] = 2;
        putShort(message, 16, 32);
        message[18] = 1;
        putInt(message, 20, width);
        putInt(message, 24, height);
        putShort(message, 28, 90);
        message[30] = 1;
        message[31] = 3;
        putInt(message, 32, frameRate);
        putInt(message, 36, bitRate);
        putInt(message, 40, frameInterval);
        message[44] = 2;
        System.arraycopy(h264, 0, message, 48, h264.length);
        return message;
    }

    private void logSent(byte[] message) {
        if (message.length <= 16 || message[10] == 1) return;
        String json = new String(message, 16, message.length - 16, StandardCharsets.UTF_8);
        if (!json.contains("\"HEARTBEAT\"")) log("phone → " + trim(json, 160));
    }

    private void writePadded(byte[] data) throws IOException {
        logSent(data);
        int paddedLength = ((data.length + USB_CHUNK - 1) / USB_CHUNK) * USB_CHUNK;
        if (paddedLength == data.length) {
            writeRaw(data);
            return;
        }
        byte[] padded = new byte[paddedLength];
        System.arraycopy(data, 0, padded, 0, data.length);
        writeRaw(padded);
    }

    private void writeRaw(byte[] data) throws IOException {
        synchronized (writeLock) {
            if (output == null) throw new IOException("USB output closed");
            output.write(data);
            output.flush();
        }
    }

    private void registerReceivers() {
        if (permissionReceiver == null) {
            permissionReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    if (!ACTION_USB_PERMISSION.equals(intent.getAction())) return;
                    permissionPending = false;
                    UsbAccessory granted = accessoryExtra(intent);
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && granted != null) {
                        log("USB permission granted");
                        openAccessory(granted);
                    } else {
                        permissionDenied = true;
                        setStatus("QDLink USB: permission denied; press Reconnect QDLink USB to ask again");
                    }
                }
            };
            register(permissionReceiver, new IntentFilter(ACTION_USB_PERMISSION));
        }
        if (usbReceiver == null) {
            usbReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context ctx, Intent intent) {
                    if (ACTION_USB_STATE.equals(intent.getAction())) {
                        updateUsbState(intent);
                    } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(intent.getAction())) {
                        UsbAccessory gone = accessoryExtra(intent);
                        log("Accessory detached: " + (gone == null ? "unknown" : describe(gone)));
                        permissionPending = false;
                        permissionDenied = false;
                        lastAccessories = "";
                        stopSessionOnly();
                        setStatus("QDLink USB: car accessory detached");
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION_USB_STATE);
            filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
            Intent sticky = register(usbReceiver, filter);
            if (sticky != null) updateUsbState(sticky);
        }
    }

    private Intent register(BroadcastReceiver receiver, IntentFilter filter) {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        }
        return context.registerReceiver(receiver, filter);
    }

    private void updateUsbState(Intent intent) {
        if (!ACTION_USB_STATE.equals(intent.getAction())) return;
        boolean cable = intent.getBooleanExtra("connected", false);
        boolean configured = intent.getBooleanExtra("configured", false);
        boolean accessoryMode = intent.getBooleanExtra("accessory", false);
        StringBuilder functions = new StringBuilder();
        for (String name : new String[] {"mtp", "ptp", "rndis", "midi", "adb", "none"}) {
            if (intent.getBooleanExtra(name, false)) {
                if (functions.length() > 0) functions.append('+');
                functions.append(name);
            }
        }
        String next = "USB cable " + (cable ? "connected" : "not connected")
                + ", accessory mode " + (accessoryMode ? "ON" : "off")
                + (configured ? "" : ", not configured")
                + (functions.length() > 0 ? ", functions " + functions : "");
        if (!next.equals(usbState)) {
            usbState = next;
            log(next);
            notifyStatus();
        }
    }

    @SuppressWarnings("deprecation")
    private static UsbAccessory accessoryExtra(Intent intent) {
        return Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory.class)
                : (UsbAccessory) intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
    }

    private void sessionFailed(String prefix, Exception error) {
        setStatus("QDLink USB: " + prefix + ": " + error);
        stopSessionOnly();
    }

    private void stopSessionOnly() {
        connected = false;
        playing = false;
        closeIo();
        Thread hb = heartbeatThread;
        heartbeatThread = null;
        if (hb != null) hb.interrupt();
    }

    private void closeIo() {
        synchronized (sessionLock) {
            closeIoLocked();
        }
    }

    private void closeIoLocked() {
        try { if (input != null) input.close(); } catch (IOException ignored) { }
        try { if (output != null) output.close(); } catch (IOException ignored) { }
        try { if (descriptor != null) descriptor.close(); } catch (IOException ignored) { }
        input = null;
        output = null;
        descriptor = null;
    }

    private void setStatus(String value) {
        if (!value.equals(status)) log(value);
        status = value;
        notifyStatus();
    }

    private void log(String line) {
        String stamped;
        synchronized (clock) { stamped = clock.format(new Date()) + "  " + line; }
        synchronized (log) {
            log.addLast(stamped);
            while (log.size() > LOG_LINES) log.removeFirst();
        }
    }

    private void notifyStatus() {
        if (listener != null) listener.onStatusChanged();
    }

    private static JSONObject jsonObject(Object... values) {
        if ((values.length & 1) != 0) throw new IllegalArgumentException("key/value pairs required");
        JSONObject object = new JSONObject();
        for (int i = 0; i < values.length; i += 2) {
            putJson(object, String.valueOf(values[i]), values[i + 1]);
        }
        return object;
    }

    private static void putJson(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    private static boolean startsWith(byte[] data, String marker) {
        byte[] bytes = marker.getBytes(StandardCharsets.US_ASCII);
        if (data == null || data.length < bytes.length) return false;
        for (int i = 0; i < bytes.length; i++) if (data[i] != bytes[i]) return false;
        return true;
    }

    private static int int32(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).getInt();
    }

    private static float float32(byte[] data, int offset) {
        return ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).getFloat();
    }

    private static void putInt(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data, offset, 4).order(ByteOrder.BIG_ENDIAN).putInt(value);
    }

    private static void putShort(byte[] data, int offset, int value) {
        ByteBuffer.wrap(data, offset, 2).order(ByteOrder.BIG_ENDIAN).putShort((short) value);
    }

    private static void copyAscii(String text, byte[] target, int offset) {
        byte[] bytes = text.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, target, offset, bytes.length);
    }

    private static int positive(int value, int fallback) { return value > 0 ? value : fallback; }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String trim(String value, int max) { return value.length() <= max ? value : value.substring(0, max); }
    private static String describe(UsbAccessory item) {
        return safe(item.getManufacturer()) + " / " + safe(item.getModel()) + " / " + safe(item.getVersion());
    }

    private static String describeFully(UsbAccessory item) {
        return "manufacturer=\"" + safe(item.getManufacturer()) + "\" model=\"" + safe(item.getModel())
                + "\" version=\"" + safe(item.getVersion()) + "\" description=\"" + safe(item.getDescription())
                + "\" uri=\"" + safe(item.getUri()) + "\"";
    }

    private static String hexPrefix(byte[] bytes, int max) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < Math.min(max, bytes.length); i++) {
            if (i > 0) out.append(' ');
            out.append(String.format(Locale.US, "%02X", bytes[i] & 0xff));
        }
        return out.toString();
    }
}
