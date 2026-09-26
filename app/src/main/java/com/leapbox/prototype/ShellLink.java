package com.leapbox.prototype;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.view.Surface;

import rikka.shizuku.Shizuku;

/** App-side connection to {@link ShellService}, which Shizuku runs as the shell user. */
final class ShellLink {
    interface Listener {
        void onShellConnected();
        void onShellDisconnected();
    }

    static final int PERMISSION_REQUEST = 3107;
    private static final int SERVICE_VERSION = 1;

    private final Shizuku.UserServiceArgs args;
    private final Listener listener;
    private volatile IBinder remote;
    private boolean bound;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (binder == null || !binder.pingBinder()) {
                Diag.log("Shizuku: helper returned no binder");
                return;
            }
            remote = binder;
            Diag.log("Shizuku: privileged helper connected");
            listener.onShellConnected();
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            remote = null;
            Diag.log("Shizuku: privileged helper disconnected");
            listener.onShellDisconnected();
        }
    };

    ShellLink(Context context, Listener listener) {
        this.listener = listener;
        args = new Shizuku.UserServiceArgs(new ComponentName(context.getPackageName(),
                ShellService.class.getName()))
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(SERVICE_VERSION);
    }

    /** Human-readable Shizuku state for the phone UI. */
    static String state(Context context) {
        if (!installed(context)) return "Shizuku: not installed";
        if (!running()) return "Shizuku: installed but not running (open Shizuku and tap Start)";
        if (!permitted()) return "Shizuku: running; LeapBox needs permission (tap Connect Shizuku)";
        return "Shizuku: ready";
    }

    static boolean installed(Context context) {
        try {
            context.getPackageManager().getPackageInfo("moe.shizuku.privileged.api", 0);
            return true;
        } catch (PackageManager.NameNotFoundException error) {
            return running();
        }
    }

    static boolean running() {
        try { return Shizuku.pingBinder(); }
        catch (RuntimeException error) { return false; }
    }

    static boolean permitted() {
        try {
            return running() && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException error) {
            return false;
        }
    }

    static void requestPermission() {
        if (running() && !Shizuku.isPreV11()) Shizuku.requestPermission(PERMISSION_REQUEST);
    }

    boolean isConnected() {
        IBinder binder = remote;
        return binder != null && binder.pingBinder();
    }

    void bind() {
        if (bound || !permitted()) return;
        try {
            Shizuku.bindUserService(args, connection);
            bound = true;
            Diag.log("Shizuku: starting privileged helper");
        } catch (RuntimeException error) {
            Diag.log("Shizuku: bind failed: " + error);
        }
    }

    void unbind() {
        if (!bound) return;
        bound = false;
        try {
            if (isConnected()) call(ShellService.RELEASE_DISPLAY, null);
            Shizuku.unbindUserService(args, connection, true);
        } catch (RuntimeException error) {
            Diag.log("Shizuku: unbind: " + error);
        }
        remote = null;
    }

    /** Creates the app-hosting car display on {@code surface}; returns its id or -1. */
    int createDisplay(Surface surface, int width, int height, int dpi) {
        String result = call(ShellService.CREATE_DISPLAY, data -> {
            surface.writeToParcel(data, 0);
            data.writeInt(width);
            data.writeInt(height);
            data.writeInt(dpi);
        });
        Diag.log("Shizuku: create display → " + result);
        if (!result.startsWith("display:")) return -1;
        int end = result.indexOf(' ');
        try {
            return Integer.parseInt(result.substring(8, end < 0 ? result.length() : end));
        } catch (NumberFormatException error) {
            return -1;
        }
    }

    String launch(int displayId, String component) {
        return call(ShellService.LAUNCH, data -> {
            data.writeInt(displayId);
            data.writeString(component);
        });
    }

    String startTouch(String deviceName, int deviceId, int displayId, int width, int height) {
        return call(ShellService.START_TOUCH, data -> {
            data.writeString(deviceName);
            data.writeInt(deviceId);
            data.writeInt(displayId);
            data.writeInt(width);
            data.writeInt(height);
        });
    }

    String stopTouch() { return call(ShellService.STOP_TOUCH, null); }

    String key(int displayId, int keyCode) {
        return call(ShellService.KEY, data -> {
            data.writeInt(displayId);
            data.writeInt(keyCode);
        });
    }

    String touchStatus() { return call(ShellService.STATUS, null); }

    private interface Writer { void write(Parcel data); }

    private String call(int code, Writer writer) {
        IBinder binder = remote;
        if (binder == null) return "error: Shizuku helper not connected";
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(ShellService.DESCRIPTOR);
            if (writer != null) writer.write(data);
            binder.transact(code, data, reply, 0);
            reply.readException();
            String result = reply.readString();
            return result == null ? "" : result;
        } catch (RemoteException | RuntimeException error) {
            return "error: " + error;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }
}
