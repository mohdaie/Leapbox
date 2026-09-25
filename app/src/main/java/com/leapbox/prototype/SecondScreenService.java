package com.leapbox.prototype;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.view.Surface;

import java.util.concurrent.atomic.AtomicLong;

/** Local video/display experiment. No QDLink USB transport is included. */
public final class SecondScreenService extends Service {
    static final String ACTION_START = "com.leapbox.prototype.START";
    static final String ACTION_STOP = "com.leapbox.prototype.STOP";
    private static final String CHANNEL = "leapbox_display";
    private static final int NOTIFICATION = 21;
    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;

    final class LocalBinder extends Binder {
        SecondScreenService getService() { return SecondScreenService.this; }
    }

    private final IBinder binder = new LocalBinder();
    private final AtomicLong frames = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private volatile String status = "Not started";
    private volatile boolean encoding;
    private MediaCodec encoder;
    private Surface encoderSurface;
    private VirtualDisplay virtualDisplay;
    private CarDashboardPresentation dashboard;
    private PowerManager.WakeLock fullWakeLock;
    private Thread drainThread;

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
            } catch (Exception | LinkageError error) {
                status = "Display failed: " + error.getClass().getSimpleName() + ": " + error.getMessage();
                releaseScreen();
                stopSelf();
            }
        }
        return START_NOT_STICKY;
    }

    private void startVisibleService() {
        NotificationManager manager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(new NotificationChannel(
                    CHANNEL, "LeapBox display", NotificationManager.IMPORTANCE_LOW));
        }
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setContentTitle("LeapBox display is active")
                .setContentText("Separate display experiment; tap to return")
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
        fullWakeLock = power.newWakeLock(PowerManager.FULL_WAKE_LOCK, "LeapBox:SecondDisplay");
        fullWakeLock.setReferenceCounted(false);
        fullWakeLock.acquire();

        MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, 24);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
        encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        encoderSurface = encoder.createInputSurface();
        encoder.start();

        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        int density = getResources().getDisplayMetrics().densityDpi;
        int displayFlags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
        virtualDisplay = displayManager.createVirtualDisplay("LeapBox navigation", WIDTH, HEIGHT,
                density, encoderSurface, displayFlags);
        if (virtualDisplay == null) throw new IllegalStateException("Virtual display unavailable");

        showDashboard();
        encoding = true;
        drainThread = new Thread(this::drainFrames, "LeapBox-H264-output");
        drainThread.start();
        status = "Display #" + virtualDisplay.getDisplay().getDisplayId()
                + " ready; H.264 is local only";
    }

    private void drainFrames() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (encoding) {
            try {
                int index = encoder.dequeueOutputBuffer(info, 100_000);
                if (index >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        frames.incrementAndGet();
                        bytes.addAndGet(info.size);
                    }
                    encoder.releaseOutputBuffer(index, false);
                }
            } catch (IllegalStateException error) {
                status = "Encoder stopped: " + error.getMessage();
                return;
            }
        }
    }

    boolean isReady() { return virtualDisplay != null; }
    int displayId() { return virtualDisplay == null ? -1 : virtualDisplay.getDisplay().getDisplayId(); }
    boolean isAwake() { return fullWakeLock != null && fullWakeLock.isHeld(); }
    long framesEncoded() { return frames.get(); }
    long bytesEncoded() { return bytes.get(); }
    String status() { return status; }

    void stopPrototype() {
        releaseScreen();
        status = "Stopped; wake lock released";
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    void showDashboard() {
        if (virtualDisplay == null || dashboard != null) return;
        dashboard = new CarDashboardPresentation(this, virtualDisplay.getDisplay(), this);
        dashboard.show();
        status = "LeapBox desktop on display #" + displayId();
    }

    /** Called by the phone UI or the icon on the secondary display. */
    String launchWaze(Context caller) {
        if (virtualDisplay == null) return "Start the second-screen lab first.";
        if (!getPackageManager().hasSystemFeature(
                PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS)) {
            return "This phone does not advertise activities on secondary displays.";
        }
        Intent waze = getPackageManager().getLaunchIntentForPackage("com.waze");
        if (waze == null) return "Install Waze on your phone first.";
        int id = displayId();
        ActivityManager activities = (ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (!activities.isActivityStartAllowedOnDisplay(caller, id, waze)) {
            return "Android blocked Waze on display #" + id + ".";
        }
        waze.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        ActivityOptions options = ActivityOptions.makeBasic().setLaunchDisplayId(id);
        CarDashboardPresentation previous = dashboard;
        dashboard = null;
        if (previous != null) previous.dismiss();
        try {
            caller.startActivity(waze, options.toBundle());
            status = "Requested Waze on display #" + id + "; verify on device";
            return status;
        } catch (RuntimeException error) {
            showDashboard();
            status = "Waze launch failed: " + error.getClass().getSimpleName();
            return status;
        }
    }

    private void releaseScreen() {
        encoding = false;
        if (dashboard != null) {
            dashboard.dismiss();
            dashboard = null;
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
        if (fullWakeLock != null) {
            if (fullWakeLock.isHeld()) fullWakeLock.release();
            fullWakeLock = null;
        }
    }

    @Override public void onDestroy() {
        releaseScreen();
        super.onDestroy();
    }
}
