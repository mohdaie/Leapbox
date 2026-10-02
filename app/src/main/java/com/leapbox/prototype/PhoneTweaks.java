package com.leapbox.prototype;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.view.Surface;

/**
 * Car-mode phone settings: landscape lock (so the mirrored picture fills the wide C10 screen and
 * car touches line up) and minimum brightness. Needs the one-time "Modify system settings"
 * permission. The previous values are saved and restored when car mode stops.
 */
final class PhoneTweaks {
    private static final String PREFS = "phone_tweaks";
    /** Android's "Extra dim" (Samsung: Accessibility → Visibility enhancements → Extra dim). */
    private static final String EXTRA_DIM = "reduce_bright_colors_activated";
    private static final String EXTRA_DIM_LEVEL = "reduce_bright_colors_level";
    static final String GRANT_COMMAND =
            "adb shell pm grant com.leapbox.prototype android.permission.WRITE_SECURE_SETTINGS";

    private PhoneTweaks() {}

    static boolean allowed(Context context) {
        return Settings.System.canWrite(context);
    }

    /** Extra dim lives in secure settings; that needs a one-time adb grant. */
    static boolean extraDimAllowed(Context context) {
        return context.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static void apply(Context context, boolean dim) {
        if (!allowed(context)) {
            Diag.log("Phone tweaks skipped: 'Modify system settings' not allowed");
            return;
        }
        ContentResolver resolver = context.getContentResolver();
        SharedPreferences saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!saved.getBoolean("active", false)) {
            saved.edit()
                    .putBoolean("active", true)
                    .putInt("rotationAuto", get(resolver, Settings.System.ACCELEROMETER_ROTATION, 1))
                    .putInt("rotation", get(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_0))
                    .putInt("brightnessMode", get(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC))
                    .putInt("brightness", get(resolver, Settings.System.SCREEN_BRIGHTNESS, 128))
                    .putInt("extraDim", Settings.Secure.getInt(resolver, EXTRA_DIM, 0))
                    .putInt("extraDimLevel", Settings.Secure.getInt(resolver, EXTRA_DIM_LEVEL, 0))
                    .apply();
        }
        put(resolver, Settings.System.ACCELEROMETER_ROTATION, 0);
        put(resolver, Settings.System.USER_ROTATION, Surface.ROTATION_90);
        setDim(context, dim);
        Diag.log("Phone: landscape locked" + (dim ? ", brightness minimum" : "")
                + (dim && extraDimAllowed(context) ? ", Extra dim on" : "")
                + (extraDimAllowed(context) ? "" : " (Extra dim needs the one-time grant)"));
    }

    static void setDim(Context context, boolean dim) {
        if (!allowed(context)) return;
        ContentResolver resolver = context.getContentResolver();
        SharedPreferences saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (dim) {
            put(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
            put(resolver, Settings.System.SCREEN_BRIGHTNESS, 1);
            putSecure(context, EXTRA_DIM_LEVEL, 100);
            putSecure(context, EXTRA_DIM, 1);
        } else {
            put(resolver, Settings.System.SCREEN_BRIGHTNESS_MODE, saved.getInt("brightnessMode",
                    Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC));
            put(resolver, Settings.System.SCREEN_BRIGHTNESS, saved.getInt("brightness", 128));
            putSecure(context, EXTRA_DIM, saved.getInt("extraDim", 0));
            putSecure(context, EXTRA_DIM_LEVEL, saved.getInt("extraDimLevel", 0));
        }
    }

    /** Restores what the phone had before car mode; safe to call repeatedly. */
    static void restore(Context context) {
        SharedPreferences saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        if (!saved.getBoolean("active", false) || !allowed(context)) return;
        ContentResolver resolver = context.getContentResolver();
        put(resolver, Settings.System.USER_ROTATION, saved.getInt("rotation", Surface.ROTATION_0));
        put(resolver, Settings.System.ACCELEROMETER_ROTATION, saved.getInt("rotationAuto", 1));
        setDim(context, false);
        saved.edit().putBoolean("active", false).apply();
        Diag.log("Phone: rotation and brightness restored");
    }

    private static void putSecure(Context context, String key, int value) {
        if (!extraDimAllowed(context)) return;
        try {
            Settings.Secure.putInt(context.getContentResolver(), key, value);
        } catch (RuntimeException error) {
            Diag.log("Extra dim setting " + key + " failed: " + error);
        }
    }

    private static int get(ContentResolver resolver, String key, int fallback) {
        return Settings.System.getInt(resolver, key, fallback);
    }

    private static void put(ContentResolver resolver, String key, int value) {
        try {
            Settings.System.putInt(resolver, key, value);
        } catch (RuntimeException error) {
            Diag.log("Phone setting " + key + " failed: " + error);
        }
    }
}
