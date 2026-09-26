package com.leapbox.prototype;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.hardware.usb.UsbManager;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Setup screen when car mode is off; the landscape car home screen while it runs (mirrored to
 * the C10); and an app picker for choosing what the home screen shows.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_CAST = 41;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private CarService car;
    private boolean bound;
    private FrameLayout root;
    private View setupView;
    private View homeView;
    private View pickerView;
    private LinearLayout tileGrid;
    private LinearLayout pickerList;
    private final Map<String, TextView> pickerBadges = new HashMap<>();
    private final Map<String, TextView> pickerMoves = new HashMap<>();
    private TextView setupStatus;
    private TextView writeState;
    private TextView overlayState;
    private TextView logView;
    private TextView greeting;
    private TextView homeStatus;
    private TextView dimButton;
    private boolean showingHome;
    private boolean showingPicker;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            car = ((CarService.LocalBinder) binder).getService();
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            car = null;
            refresh();
        }
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        root = new FrameLayout(this);
        setupView = buildSetup();
        homeView = buildHome();
        root.addView(setupView);
        root.addView(homeView);
        setContentView(root);
        handleAccessory(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleAccessory(intent);
    }

    private void handleAccessory(Intent intent) {
        if (intent == null || !UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) return;
        if (CarService.running != null) {
            startService(new Intent(this, CarService.class).setAction(CarService.ACTION_ACCESSORY));
        } else {
            tell("Car connected. Tap Start car mode.");
        }
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, CarService.class), connection, 0);
        rebuildTiles();
        handler.post(ticker);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(ticker);
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        car = null;
        super.onStop();
    }

    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() {
        if (showingPicker) {
            closePicker();
        } else if (!showingHome) {
            super.onBackPressed();
        }
    }

    // ---- Setup screen (light) ----

    private View buildSetup() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.PAPER);
        LinearLayout column = Ui.column(this);
        column.setPadding(Ui.dp(this, 22), Ui.dp(this, 40), Ui.dp(this, 22), Ui.dp(this, 36));
        scroll.addView(column);

        TextView mark = Ui.text(this, "✳  LEAPBOX", 13, Ui.CLAY, true);
        mark.setLetterSpacing(0.12f);
        column.addView(mark);
        column.addView(Ui.serif(this, "Your phone,\non the C10 screen.", 34, Ui.INK), Ui.block(this, 10));
        column.addView(Ui.text(this,
                "LeapBox shows your phone on the car through QDLink, without the QDLink app. "
                        + "Touch the car screen to drive it. While driving the phone turns landscape and dims.",
                16, Ui.MUTED, false), Ui.block(this, 10));

        LinearLayout permissions = card();
        permissions.addView(cardTitle("Before your first drive"));
        writeState = Ui.text(this, "", 15, Ui.INK, false);
        permissions.addView(permissionRow(writeState, () -> openSettings(
                new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri()))), Ui.block(this, 12));
        overlayState = Ui.text(this, "", 15, Ui.INK, false);
        permissions.addView(permissionRow(overlayState, () -> openSettings(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))), Ui.block(this, 10));
        column.addView(permissions, Ui.block(this, 26));

        LinearLayout drive = card();
        drive.addView(cardTitle("Drive"));
        drive.addView(Ui.text(this,
                "Open QDLink on the car and plug in the cable. Tap Start, then choose \"Entire screen\" "
                        + "and Start in Android's pop-up. Keep the QDLink phone app closed.",
                15, Ui.MUTED, false), Ui.block(this, 8));
        drive.addView(action(Ui.button(this, "Start car mode", Ui.CLAY), this::requestCast), Ui.block(this, 16));
        drive.addView(action(Ui.ghostButton(this, "Choose home screen apps"), this::openPicker), Ui.block(this, 10));
        setupStatus = Ui.text(this, "", 14, Ui.MUTED, false);
        drive.addView(setupStatus, Ui.block(this, 14));
        column.addView(drive, Ui.block(this, 14));

        LinearLayout log = card();
        log.addView(cardTitle("Diagnostic log"));
        log.addView(action(Ui.ghostButton(this, "Copy log"), this::copyLog), Ui.block(this, 12));
        logView = Ui.text(this, "", 11, Ui.MUTED, false);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setMaxLines(40);
        logView.setEllipsize(TextUtils.TruncateAt.START);
        log.addView(logView, Ui.block(this, 12));
        column.addView(log, Ui.block(this, 14));
        return scroll;
    }

    private LinearLayout card() {
        LinearLayout card = Ui.column(this);
        card.setPadding(Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18), Ui.dp(this, 18));
        card.setBackground(Ui.outlined(Ui.CARD, Ui.LINE, this, 20));
        return card;
    }

    private TextView cardTitle(String title) {
        return Ui.serif(this, title, 21, Ui.INK);
    }

    private View permissionRow(TextView label, Runnable allow) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView button = Ui.text(this, "Allow", 14, Ui.CLAY_DEEP, true);
        button.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        button.setBackground(Ui.outlined(Ui.CARD, Ui.LINE, this, 18));
        button.setOnClickListener(view -> allow.run());
        row.addView(button);
        label.setTag(button);
        return row;
    }

    private View action(TextView button, Runnable onClick) {
        button.setOnClickListener(view -> onClick.run());
        return button;
    }

    private Uri packageUri() { return Uri.parse("package:" + getPackageName()); }

    private void openSettings(Intent intent) {
        try { startActivity(intent); }
        catch (ActivityNotFoundException error) { tell("Open Settings → Apps → LeapBox to allow this."); }
    }

    private void requestCast() {
        MediaProjectionManager manager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        Intent intent = Build.VERSION.SDK_INT >= 34
                ? manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
                : manager.createScreenCaptureIntent();
        startActivityForResult(intent, REQUEST_CAST);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_CAST) return;
        if (resultCode != RESULT_OK || data == null) {
            tell("Screen casting was not allowed.");
            return;
        }
        Intent start = new Intent(this, CarService.class)
                .setAction(CarService.ACTION_START)
                .putExtra(CarService.EXTRA_CODE, resultCode)
                .putExtra(CarService.EXTRA_DATA, data);
        startForegroundService(start);
        if (!bound) bound = bindService(new Intent(this, CarService.class), connection, 0);
        handler.postDelayed(this::refresh, 600);
    }

    // ---- Car home screen (dark, landscape) ----

    private View buildHome() {
        LinearLayout screen = Ui.column(this);
        screen.setBackgroundColor(Ui.NIGHT);
        screen.setPadding(Ui.dp(this, 30), Ui.dp(this, 16), Ui.dp(this, 30), Ui.dp(this, 14));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = Ui.column(this);
        greeting = Ui.serif(this, "", 26, Ui.CREAM);
        titles.addView(greeting);
        homeStatus = Ui.text(this, "", 13, Ui.SAND, false);
        titles.addView(homeStatus, Ui.block(this, 2));
        top.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Ui.CREAM);
        clock.setTextSize(40);
        clock.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        top.addView(clock);
        screen.addView(top);

        ScrollView tiles = new ScrollView(this);
        tiles.setVerticalScrollBarEnabled(false);
        tileGrid = Ui.column(this);
        tiles.addView(tileGrid);
        LinearLayout.LayoutParams tileParams = new LinearLayout.LayoutParams(-1, 0, 1);
        tileParams.topMargin = Ui.dp(this, 12);
        screen.addView(tiles, tileParams);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(pill("Edit apps", this::openPicker, false));
        dimButton = pill("Brighten phone", () -> {
            if (car != null) car.setDimmed(!car.isDimmed());
            refresh();
        }, false);
        bar.addView(dimButton, spaced());
        bar.addView(pill("Copy log", this::copyLog, false), spaced());
        bar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
        bar.addView(pill("Stop", this::stopCar, true));
        screen.addView(bar, Ui.block(this, 12));
        screen.setVisibility(View.GONE);
        return screen;
    }

    private LinearLayout.LayoutParams spaced() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2);
        params.leftMargin = Ui.dp(this, 10);
        return params;
    }

    /** Fills the home grid with the chosen apps, four per row. */
    private void rebuildTiles() {
        if (tileGrid == null) return;
        tileGrid.removeAllViews();
        PackageManager packages = getPackageManager();
        List<View> tiles = new ArrayList<>();
        for (String name : HomeApps.chosen(this)) {
            Intent launch = packages.getLaunchIntentForPackage(name);
            if (launch == null) continue;
            try {
                Drawable icon = packages.getApplicationIcon(name);
                String label = String.valueOf(packages.getApplicationLabel(packages.getApplicationInfo(name, 0)));
                tiles.add(tile(icon, label, () -> launch(launch)));
            } catch (PackageManager.NameNotFoundException ignored) { }
        }
        if (tiles.isEmpty()) {
            TextView empty = Ui.serif(this, "Tap Edit apps to choose what appears here.", 22, Ui.SAND);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(0, Ui.dp(this, 60), 0, 0);
            tileGrid.addView(empty);
            return;
        }
        int perRow = 4;
        for (int start = 0; start < tiles.size(); start += perRow) {
            LinearLayout row = new LinearLayout(this);
            for (int i = 0; i < perRow; i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, Ui.dp(this, 108), 1);
                if (i > 0) params.leftMargin = Ui.dp(this, 12);
                View cell = start + i < tiles.size() ? tiles.get(start + i) : new View(this);
                row.addView(cell, params);
            }
            tileGrid.addView(row, Ui.block(this, start == 0 ? 0 : 12));
        }
    }

    private View tile(Drawable icon, String label, Runnable onClick) {
        LinearLayout card = Ui.column(this);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Ui.outlined(Ui.NIGHT_CARD, Ui.NIGHT_LINE, this, 22));
        ImageView image = new ImageView(this);
        image.setImageDrawable(icon);
        int size = Ui.dp(this, 52);
        card.addView(image, new LinearLayout.LayoutParams(size, size));
        TextView name = Ui.text(this, label, 16, Ui.CREAM, false);
        name.setGravity(Gravity.CENTER);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
        card.addView(name, Ui.block(this, 8));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> onClick.run());
        return card;
    }

    private TextView pill(String title, Runnable onClick, boolean accent) {
        TextView button = Ui.text(this, title, 14, accent ? Color.WHITE : Ui.CREAM, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(this, 18), Ui.dp(this, 9), Ui.dp(this, 18), Ui.dp(this, 9));
        button.setBackground(accent ? Ui.rounded(Ui.CLAY, this, 20)
                : Ui.outlined(Ui.NIGHT_CARD, Ui.NIGHT_LINE, this, 20));
        button.setOnClickListener(view -> onClick.run());
        return button;
    }

    private void launch(Intent intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException error) {
            tell("Cannot open this app");
        }
    }

    private void stopCar() {
        startService(new Intent(this, CarService.class).setAction(CarService.ACTION_STOP));
        handler.postDelayed(this::refresh, 400);
    }

    private static String greetingFor(int hour) {
        if (hour < 5) return "Good night";
        if (hour < 12) return "Good morning";
        if (hour < 18) return "Good afternoon";
        return "Good evening";
    }

    // ---- App picker ----

    private void openPicker() {
        if (pickerView == null) {
            pickerView = buildPicker();
            root.addView(pickerView);
        }
        refreshPicker();
        pickerView.setVisibility(View.VISIBLE);
        showingPicker = true;
    }

    private void closePicker() {
        if (pickerView != null) pickerView.setVisibility(View.GONE);
        showingPicker = false;
        rebuildTiles();
    }

    private View buildPicker() {
        LinearLayout screen = Ui.column(this);
        screen.setBackgroundColor(Ui.NIGHT);
        screen.setPadding(Ui.dp(this, 26), Ui.dp(this, 22), Ui.dp(this, 26), Ui.dp(this, 10));
        screen.setClickable(true);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = Ui.column(this);
        titles.addView(Ui.serif(this, "Choose your apps", 26, Ui.CREAM));
        titles.addView(Ui.text(this, "Tap an app to add or remove it. ↑ moves it earlier on the home screen.",
                13, Ui.SAND, false), Ui.block(this, 2));
        header.addView(titles, new LinearLayout.LayoutParams(0, -2, 1));
        header.addView(pill("Done", this::closePicker, true));
        screen.addView(header);

        ScrollView scroll = new ScrollView(this);
        pickerList = Ui.column(this);
        scroll.addView(pickerList);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1, 0, 1);
        listParams.topMargin = Ui.dp(this, 14);
        screen.addView(scroll, listParams);

        PackageManager packages = getPackageManager();
        for (HomeApps.App app : HomeApps.installed(this)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 12), Ui.dp(this, 8));
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(app.info.loadIcon(packages));
            int size = Ui.dp(this, 40);
            row.addView(icon, new LinearLayout.LayoutParams(size, size));
            TextView name = Ui.text(this, app.label, 17, Ui.CREAM, false);
            name.setPadding(Ui.dp(this, 14), 0, Ui.dp(this, 8), 0);
            name.setSingleLine(true);
            name.setEllipsize(TextUtils.TruncateAt.END);
            row.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
            TextView move = Ui.text(this, "↑", 18, Ui.CREAM, true);
            move.setGravity(Gravity.CENTER);
            move.setBackground(Ui.outlined(Ui.NIGHT_CARD, Ui.NIGHT_LINE, this, 18));
            int moveSize = Ui.dp(this, 38);
            move.setOnClickListener(view -> {
                HomeApps.moveEarlier(this, app.packageName);
                refreshPicker();
            });
            row.addView(move, new LinearLayout.LayoutParams(moveSize, moveSize));
            TextView badge = Ui.text(this, "", 14, Ui.CREAM, true);
            badge.setGravity(Gravity.CENTER);
            badge.setMinWidth(Ui.dp(this, 96));
            badge.setPadding(Ui.dp(this, 14), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
            LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2);
            badgeParams.leftMargin = Ui.dp(this, 10);
            row.addView(badge, badgeParams);
            row.setBackground(Ui.rounded(Ui.NIGHT, this, 14));
            row.setOnClickListener(view -> {
                HomeApps.toggle(this, app.packageName);
                refreshPicker();
            });
            pickerBadges.put(app.packageName, badge);
            pickerMoves.put(app.packageName, move);
            pickerList.addView(row, Ui.block(this, 4));
        }
        return screen;
    }

    private void refreshPicker() {
        List<String> chosen = HomeApps.chosen(this);
        for (Map.Entry<String, TextView> entry : pickerBadges.entrySet()) {
            int position = chosen.indexOf(entry.getKey());
            TextView badge = entry.getValue();
            if (position >= 0) {
                badge.setText("On home · " + (position + 1));
                badge.setTextColor(Color.WHITE);
                badge.setBackground(Ui.rounded(Ui.CLAY, this, 18));
            } else {
                badge.setText("Add");
                badge.setTextColor(Ui.CREAM);
                badge.setBackground(Ui.outlined(Ui.NIGHT_CARD, Ui.NIGHT_LINE, this, 18));
            }
            TextView move = pickerMoves.get(entry.getKey());
            if (move != null) move.setVisibility(position > 0 ? View.VISIBLE : View.INVISIBLE);
        }
    }

    // ---- Mode switching, status and log ----

    private void showHome(boolean home) {
        if (home == showingHome) return;
        showingHome = home;
        homeView.setVisibility(home ? View.VISIBLE : View.GONE);
        setupView.setVisibility(home ? View.GONE : View.VISIBLE);
        setRequestedOrientation(home ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                : ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
        if (home) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        getWindow().setStatusBarColor(home ? Ui.NIGHT : Ui.PAPER);
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            if (home) {
                insets.hide(WindowInsets.Type.systemBars());
                insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                insets.show(WindowInsets.Type.systemBars());
                insets.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                        WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            }
        }
    }

    private void refresh() {
        CarService service = car != null ? car : CarService.running;
        boolean on = service != null && CarService.running != null;
        showHome(on);
        if (on) {
            greeting.setText(greetingFor(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)));
            homeStatus.setText(service.carSummary());
            dimButton.setText(service.isDimmed() ? "Brighten phone" : "Dim phone");
            return;
        }
        setPermission(writeState, PhoneTweaks.allowed(this), "Landscape + dimming");
        setPermission(overlayState, Settings.canDrawOverlays(this), "LeapBox home button");
        setupStatus.setText(usbSummary());
        String events = Diag.text();
        logView.setText(events.isEmpty() ? "No events yet." : events);
    }

    private void setPermission(TextView label, boolean granted, String name) {
        label.setText((granted ? "✓  " : "○  ") + name);
        label.setTextColor(granted ? Ui.INK : Ui.MUTED);
        Object button = label.getTag();
        if (button instanceof View) ((View) button).setVisibility(granted ? View.GONE : View.VISIBLE);
    }

    private String usbSummary() {
        UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
        android.hardware.usb.UsbAccessory[] accessories = usb == null ? null : usb.getAccessoryList();
        if (accessories == null || accessories.length == 0) return "Car: not connected";
        return "Car: " + accessories[0].getModel() + " connected";
    }

    private void copyLog() {
        CarService service = car != null ? car : CarService.running;
        String text = (service == null ? "Car mode: off" : service.details()) + "\n\n" + Diag.text();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("LeapBox diagnostic log", text));
        tell("Diagnostic log copied");
    }

    private void tell(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
