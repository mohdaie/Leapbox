package com.leapbox.prototype;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** The apps shown on the car home screen, in the order the user added them. */
final class HomeApps {
    private static final String PREFS = "home_apps";
    private static final String KEY = "packages";
    private static final String[] DEFAULTS = {
            "com.waze",
            "com.google.android.apps.maps",
            "com.google.android.youtube",
            "com.google.android.apps.youtube.music",
            "com.spotify.music",
            "com.whatsapp",
    };

    /** A launchable app on the phone. */
    static final class App {
        final String packageName;
        final String label;
        final ResolveInfo info;

        App(String packageName, String label, ResolveInfo info) {
            this.packageName = packageName;
            this.label = label;
            this.info = info;
        }
    }

    private HomeApps() {}

    /** Chosen packages that are still installed, in order. */
    static List<String> chosen(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        List<String> result = new ArrayList<>();
        String saved = prefs.getString(KEY, null);
        PackageManager packages = context.getPackageManager();
        if (saved == null) {
            for (String name : DEFAULTS) {
                if (packages.getLaunchIntentForPackage(name) != null) result.add(name);
            }
            String dialer = dialerPackage(context);
            if (dialer != null && !result.contains(dialer)) result.add(dialer);
            return result;
        }
        for (String name : saved.split(",")) {
            if (!name.isEmpty() && packages.getLaunchIntentForPackage(name) != null) result.add(name);
        }
        return result;
    }

    static boolean isChosen(Context context, String packageName) {
        return chosen(context).contains(packageName);
    }

    /** Adds the app at the end, or removes it if already chosen. */
    static void toggle(Context context, String packageName) {
        Set<String> apps = new LinkedHashSet<>(chosen(context));
        if (!apps.remove(packageName)) apps.add(packageName);
        save(context, new ArrayList<>(apps));
    }

    /** Moves an app one place earlier on the home screen. */
    static void moveEarlier(Context context, String packageName) {
        List<String> apps = chosen(context);
        int index = apps.indexOf(packageName);
        if (index > 0) {
            Collections.swap(apps, index, index - 1);
            save(context, apps);
        }
    }

    private static void save(Context context, List<String> apps) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                .putString(KEY, String.join(",", apps)).apply();
    }

    /** Every app with a launcher icon, sorted by name (LeapBox itself excluded). */
    static List<App> installed(Context context) {
        PackageManager packages = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<App> apps = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (ResolveInfo info : packages.queryIntentActivities(launcher, 0)) {
            String name = info.activityInfo.packageName;
            if (name.equals(context.getPackageName()) || !seen.add(name)) continue;
            apps.add(new App(name, String.valueOf(info.loadLabel(packages)), info));
        }
        apps.sort((a, b) -> a.label.toLowerCase(Locale.ROOT).compareTo(b.label.toLowerCase(Locale.ROOT)));
        return apps;
    }

    private static String dialerPackage(Context context) {
        ResolveInfo info = context.getPackageManager().resolveActivity(new Intent(Intent.ACTION_DIAL), 0);
        return info == null || info.activityInfo == null ? null : info.activityInfo.packageName;
    }
}
