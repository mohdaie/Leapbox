package com.leapbox.prototype;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Car mode: mirrors the phone screen to the C10 over LeapBox's own QDLink USB connection.
 * The car's touchscreen already drives the phone screen natively, so touch needs no code here.
 */
public final class CarService extends Service {
    static final String ACTION_START = "com.leapbox.prototype.START";
    static final String ACTION_STOP = "com.leapbox.prototype.STOP";
    static final String ACTION_ACCESSORY = "com.leapbox.prototype.ACCESSORY_ATTACHED";
    static final String EXTRA_CODE = "projection_code";
    static final String EXTRA_DATA = "projection_data";
    private static final String CHANNEL = "leapbox_car";
    private static final int NOTIFICATION = 21;
    private static final int WIDTH = 1920;
    private static final int HEIGHT = 882;
    private static final int FPS = 30;
    private static final int DPI = 240;

    /** Set while car mode runs, so the activity can tell without binding. */
    static volatile CarService running;

    final class LocalBinder extends Binder {
        CarService getService() { return CarService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private volatile boolean encoding;
    private volatile byte[] codecConfig;
    /** Last key frame sent (with SPS/PPS) and how many frames followed it. */
    private volatile byte[] lastKeyFrame;
    private volatile long sentSinceKey;
    private MediaCodec encoder;
    private Surface encoderSurface;
    private MediaProjection projection;
    private VirtualDisplay mirror;
    private Thread drainThread;
    private QdLinkUsbClient qdLink;
    private PowerManager.WakeLock screenLock;
    private View bubble;
    private boolean dimmed = true;

    @Override public IBinder onBind(Intent intent) { return binder; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopCar();
            return START_NOT_STICKY;
        }
        if (ACTION_ACCESSORY.equals(action)) {
            if (qdLink != null) qdLink.accessoryAttached();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action) && projection == null) {
            try {
                startCar(intent);
            } catch (Exception | LinkageError error) {
                Diag.log("Car mode failed to start: " + error);
                stopCar();
            }
        }
        return START_NOT_STICKY;
    }

    @SuppressWarnings("deprecation")
    private void startCar(Intent intent) throws Exception {
        // Android 14+: the service must be a media-projection foreground service first.
        startForegroundNotice();
        int code = intent.getIntExtra(EXTRA_CODE, 0);
        Intent data = Build.VERSION.SDK_INT >= 33
                ? intent.getParcelableExtra(EXTRA_DATA, Intent.class)
                : intent.getParcelableExtra(EXTRA_DATA);
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        projection = manager.getMediaProjection(code, data);
        if (projection == null) throw new IllegalStateException("screen casting was not allowed");
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                Diag.log("Android ended screen casting");
                main.post(() -> stopCar());
            }
        }, main);

        startEncoder();
        mirror = projection.createVirtualDisplay("LeapBox mirror", WIDTH, HEIGHT, DPI,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, encoderSurface, null, main);
        running = this;
        Diag.log("Car mode: mirroring phone screen at " + WIDTH + "×" + HEIGHT);

        // Keep the (dimmed) screen on: casting stops when the phone locks.
        PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
        screenLock = power.newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "LeapBox:CarMode");
        screenLock.setReferenceCounted(false);
        screenLock.acquire();

        PhoneTweaks.apply(this, dimmed);
        showBubble();
        startCarLink();
    }

    private void startForegroundNotice() {
        NotificationManager notifications = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        notifications.createNotificationChannel(new NotificationChannel(
                CHANNEL, "LeapBox car mode", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, CarService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("LeapBox car mode")
                .setContentText("Your phone screen is shown on the car")
                .setSmallIcon(R.drawable.ic_leapbox)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIFICATION, notification);
        }
    }

    private void startEncoder() throws Exception {
        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 1_000_000L / FPS);
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.start();
        encoding = true;
        drainThread = new Thread(this::drainFrames, "LeapBox-H264-output");
        drainThread.start();
    }

    private void startCarLink() {
        qdLink = new QdLinkUsbClient(this, new QdLinkUsbClient.Listener() {
            @Override public void onStatusChanged() { }
            @Override public void onVideoRequested() { keyFrameNow(); }
            @Override public void onKeyFrameRequested() { keyFrameNow(); }
            @Override public void onCarTouch(float x, float y, int action, int carWidth, int carHeight) { }
        });
        qdLink.start();
    }

    /** Asks the encoder for a key frame, and resends the last one if nothing newer went out. */
    private void keyFrameNow() {
        MediaCodec codec = encoder;
        if (codec != null) {
            try {
                Bundle params = new Bundle();
                params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                codec.setParameters(params);
            } catch (IllegalStateException ignored) { }
        }
        byte[] key = lastKeyFrame;
        QdLinkUsbClient link = qdLink;
        if (key != null && link != null && sentSinceKey == 0) link.sendVideo(key, WIDTH, HEIGHT, FPS, true);
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
                if (index < 0) continue;
                if (info.size > 0) {
                    ByteBuffer buffer = encoder.getOutputBuffer(index);
                    if (buffer != null) {
                        buffer.position(info.offset);
                        buffer.limit(info.offset + info.size);
                        byte[] data = new byte[info.size];
                        buffer.get(data);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            codecConfig = data;
                        } else {
                            boolean key = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            frames.incrementAndGet();
                            bytes.addAndGet(info.size);
                            // Car decoders commonly need SPS/PPS in front of every IDR frame.
                            byte[] packet = key ? withCodecConfig(data) : data;
                            if (key) {
                                lastKeyFrame = packet;
                                sentSinceKey = 0;
                            } else {
                                sentSinceKey++;
                            }
                            QdLinkUsbClient link = qdLink;
                            if (link != null) link.sendVideo(packet, WIDTH, HEIGHT, FPS, key);
                        }
                    }
                }
                encoder.releaseOutputBuffer(index, false);
            } catch (IllegalStateException error) {
                return;
            }
        }
    }

    private void cacheCodecConfig(MediaFormat format) {
        ByteBuffer sps = format.getByteBuffer("csd-0");
        ByteBuffer pps = format.getByteBuffer("csd-1");
        if (sps == null && pps == null) return;
        byte[] a = copy(sps);
        byte[] b = copy(pps);
        byte[] combined = new byte[a.length + b.length];
        System.arraycopy(a, 0, combined, 0, a.length);
        System.arraycopy(b, 0, combined, a.length, b.length);
        codecConfig = combined;
    }

    private static byte[] copy(ByteBuffer source) {
        if (source == null) return new byte[0];
        ByteBuffer duplicate = source.duplicate();
        byte[] out = new byte[duplicate.remaining()];
        duplicate.get(out);
        return out;
    }

    private byte[] withCodecConfig(byte[] keyFrame) {
        byte[] config = codecConfig;
        if (config == null || config.length == 0) return keyFrame;
        byte[] combined = new byte[config.length + keyFrame.length];
        System.arraycopy(config, 0, combined, 0, config.length);
        System.arraycopy(keyFrame, 0, combined, config.length, keyFrame.length);
        return combined;
    }

    // ---- Floating LeapBox button: back to the car home screen from any app ----

    private void showBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Diag.log("Home button off: 'Display over other apps' not allowed");
            return;
        }
        WindowManager windows = (WindowManager) getSystemService(WINDOW_SERVICE);
        int size = Ui.dp(this, 62);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.START | Gravity.BOTTOM;
        params.x = Ui.dp(this, 14);
        params.y = Ui.dp(this, 14);
        bubble = new HomeBubble(this);
        bubble.setOnClickListener(view -> openHome());
        try {
            windows.addView(bubble, params);
        } catch (RuntimeException error) {
            Diag.log("Home button failed: " + error);
            bubble = null;
        }
    }

    void openHome() {
        Intent home = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(home);
    }

    /**
     * Round LeapBox button with a slowly turning ring. The ring redraws every frame, which also
     * keeps video flowing to the car when the phone screen is otherwise static.
     */
    private static final class HomeBubble extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF oval = new RectF();

        HomeBubble(Context context) {
            super(context);
            fill.setColor(Color.argb(235, 217, 119, 87));
            ring.setColor(Color.rgb(250, 249, 245));
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(Ui.dp(context, 3));
            ring.setStrokeCap(Paint.Cap.ROUND);
            label.setColor(Color.WHITE);
            label.setTextAlign(Paint.Align.CENTER);
            label.setFakeBoldText(true);
            label.setTextSize(Ui.dp(context, 17));
            setClickable(true);
        }

        @Override protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            float inset = ring.getStrokeWidth();
            canvas.drawCircle(w / 2, h / 2, Math.min(w, h) / 2 - inset, fill);
            oval.set(inset, inset, w - inset, h - inset);
            float sweep = (SystemClock.uptimeMillis() % 2400) / 2400f * 360f;
            canvas.drawArc(oval, sweep, 70, false, ring);
            canvas.drawText("LB", w / 2, h / 2 - (label.descent() + label.ascent()) / 2, label);
            postInvalidateDelayed(1000L / FPS);
        }
    }

    // ---- State for the UI ----

    boolean isDimmed() { return dimmed; }

    void setDimmed(boolean dim) {
        dimmed = dim;
        PhoneTweaks.setDim(this, dim);
    }

    String carSummary() {
        QdLinkUsbClient link = qdLink;
        if (link == null) return "Car: starting…";
        if (!link.isConnected()) return "Car: waiting for USB (plug in, open QDLink on the car)";
        if (!link.isPlaying()) return "Car: connected, waiting for the car to show the picture";
        return String.format(java.util.Locale.US, "Car: showing phone · %,d frames", link.videoFramesSent());
    }

    String details() {
        QdLinkUsbClient link = qdLink;
        if (link == null) return "Car mode starting";
        return String.format(java.util.Locale.US,
                "Mirror: %d×%d · encoded %,d frames (%.1f MB)\nQDLink: %s · protocol %s · car %d×%d\n%s\nSent to car: %,d frames (%,d key)\n%s",
                WIDTH, HEIGHT, frames.get(), bytes.get() / 1_000_000.0,
                link.isConnected() ? "connected" : "not connected", link.protocol(),
                link.carWidth(), link.carHeight(), link.usbState(),
                link.videoFramesSent(), link.keyFramesSent(), link.status());
    }

    void reconnectCar() {
        if (qdLink != null) qdLink.reconnect();
    }

    void stopCar() {
        if (running == this) running = null;
        encoding = false;
        if (qdLink != null) {
            qdLink.stop();
            qdLink = null;
        }
        if (bubble != null) {
            try { ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(bubble); }
            catch (RuntimeException ignored) { }
            bubble = null;
        }
        if (mirror != null) {
            mirror.release();
            mirror = null;
        }
        if (projection != null) {
            MediaProjection stopping = projection;
            projection = null;
            stopping.stop();
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
        if (screenLock != null) {
            if (screenLock.isHeld()) screenLock.release();
            screenLock = null;
        }
        PhoneTweaks.restore(this);
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
        Diag.log("Car mode stopped");
    }

    @Override public void onDestroy() {
        if (projection != null || running == this) stopCar();
        super.onDestroy();
    }
}
