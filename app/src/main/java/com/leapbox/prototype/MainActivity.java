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

/**
 * Setup screen when car mode is off; the landscape car home screen (big app tiles) when it runs.
 * Whatever the phone shows is mirrored to the C10.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_CAST = 41;
    /** Car apps offered on the home screen when installed: package, label. */
    private static final String[][] APPS = {
            {"com.waze", "Waze"},
            {"com.google.android.apps.maps", "Maps"},
            {"com.google.android.youtube", "YouTube"},
            {"com.google.android.apps.youtube.music", "YT Music"},
            {"com.spotify.music", "Spotify"},
            {"com.whatsapp", "WhatsApp"},
    };
    private static final int CAR_BG = Color.rgb(11, 17, 28);
    private static final int CAR_TILE = Color.rgb(27, 38, 56);
    private static final int TEAL = Color.rgb(83, 215, 197);

    private final Handler handler = new Handler(Looper.getMainLooper());
    private CarService car;
    private boolean bound;
    private FrameLayout root;
    private View setupView;
    private View homeView;
    private TextView setupStatus;
    private TextView permissionStatus;
    private TextView logView;
    private TextView homeStatus;
    private TextView dimButton;
    private boolean showingHome;

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
            tell("Car connected. Tap START CAR MODE.");
        }
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, CarService.class), connection, 0);
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

    // ---- Setup screen (car mode off) ----

    private View buildSetup() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.PAPER);
        LinearLayout column = Ui.column(this);
        column.setPadding(Ui.dp(this, 23), Ui.dp(this, 36), Ui.dp(this, 23), Ui.dp(this, 34));
        scroll.addView(column);

        column.addView(Ui.text(this, "LEAPBOX  /  CAR MODE", 13, Ui.BLUE, true));
        column.addView(Ui.text(this, "Your phone on the C10 screen.", 29, Ui.INK, true), Ui.block(this, 10));
        column.addView(Ui.text(this,
                "LeapBox shows your phone on the car screen through QDLink, without the QDLink app. "
                        + "Touch the car screen to control it. The phone turns landscape and dims while driving.",
                16, Ui.MUTED, false), Ui.block(this, 8));

        column.addView(section("01  ONE-TIME PERMISSIONS"), Ui.block(this, 28));
        permissionStatus = Ui.text(this, "", 15, Ui.INK, false);
        column.addView(permissionStatus, Ui.block(this, 8));
        column.addView(action("Allow: modify system settings (landscape + dim)", Ui.MUTED, () -> openSettings(
                new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, packageUri()))), Ui.block(this, 12));
        column.addView(action("Allow: display over other apps (LeapBox button)", Ui.MUTED, () -> openSettings(
                new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, packageUri()))), Ui.block(this, 10));

        column.addView(section("02  DRIVE"), Ui.block(this, 28));
        column.addView(Ui.text(this,
                "Plug in the car cable and open QDLink on the car. Tap Start, then choose \"Entire screen\" and Start in Android's pop-up. "
                        + "Close or disable the QDLink phone app first.", 15, Ui.MUTED, false), Ui.block(this, 7));
        column.addView(action("START CAR MODE", Ui.BLUE, this::requestCast), Ui.block(this, 16));

        setupStatus = Ui.text(this, "", 14, Ui.INK, false);
        setupStatus.setPadding(Ui.dp(this, 17), Ui.dp(this, 17), Ui.dp(this, 17), Ui.dp(this, 17));
        setupStatus.setBackground(Ui.rounded(Color.WHITE, this, 16));
        column.addView(setupStatus, Ui.block(this, 24));

        column.addView(section("03  DIAGNOSTIC LOG"), Ui.block(this, 28));
        column.addView(action("Copy diagnostic log", Ui.BLUE, this::copyLog), Ui.block(this, 12));
        logView = Ui.text(this, "", 12, Ui.INK, false);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        logView.setBackground(Ui.rounded(Color.WHITE, this, 16));
        column.addView(logView, Ui.block(this, 12));
        return scroll;
    }

    private TextView section(String title) {
        return Ui.text(this, title, 14, Ui.BLUE, true);
    }

    private View action(String title, int color, Runnable onClick) {
        TextView button = Ui.button(this, title, color);
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

    // ---- Car home screen (car mode on) ----

    private View buildHome() {
        LinearLayout screen = Ui.column(this);
        screen.setBackgroundColor(CAR_BG);
        screen.setPadding(Ui.dp(this, 36), Ui.dp(this, 18), Ui.dp(this, 36), Ui.dp(this, 18));

        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextClock clock = new TextClock(this);
        clock.setFormat12Hour("h:mm");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(34);
        clock.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        top.addView(clock);
        homeStatus = Ui.text(this, "", 14, Color.rgb(158, 174, 194), false);
        homeStatus.setPadding(Ui.dp(this, 20), 0, Ui.dp(this, 12), 0);
        top.addView(homeStatus, new LinearLayout.LayoutParams(0, -2, 1));
        dimButton = pill("Brighten phone", () -> {
            if (car != null) car.setDimmed(!car.isDimmed());
            refresh();
        });
        top.addView(dimButton);
        LinearLayout.LayoutParams logParams = new LinearLayout.LayoutParams(-2, -2);
        logParams.leftMargin = Ui.dp(this, 10);
        top.addView(pill("Copy log", this::copyLog), logParams);
        LinearLayout.LayoutParams stopParams = new LinearLayout.LayoutParams(-2, -2);
        stopParams.leftMargin = Ui.dp(this, 10);
        top.addView(pill("Stop", this::stopCar), stopParams);
        screen.addView(top);

        LinearLayout grid = Ui.column(this);
        java.util.List<View> tiles = new java.util.ArrayList<>();
        PackageManager packages = getPackageManager();
        for (String[] app : APPS) {
            Intent launch = packages.getLaunchIntentForPackage(app[0]);
            if (launch == null) continue;
            Drawable icon;
            try { icon = packages.getApplicationIcon(app[0]); }
            catch (PackageManager.NameNotFoundException error) { continue; }
            tiles.add(tile(icon, app[1], () -> launch(launch)));
        }
        Intent dial = new Intent(Intent.ACTION_DIAL);
        if (dial.resolveActivity(packages) != null) {
            Drawable icon = dial.resolveActivityInfo(packages, 0).loadIcon(packages);
            tiles.add(tile(icon, "Phone", () -> launch(new Intent(Intent.ACTION_DIAL))));
        }
        int perRow = 4;
        for (int start = 0; start < tiles.size(); start += perRow) {
            LinearLayout row = new LinearLayout(this);
            for (int i = 0; i < perRow; i++) {
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
                if (i > 0) params.leftMargin = Ui.dp(this, 16);
                View cell = start + i < tiles.size() ? tiles.get(start + i) : new View(this);
                row.addView(cell, params);
            }
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, 0, 1);
            if (start > 0) rowParams.topMargin = Ui.dp(this, 16);
            grid.addView(row, rowParams);
        }
        LinearLayout.LayoutParams gridParams = new LinearLayout.LayoutParams(-1, 0, 1);
        gridParams.topMargin = Ui.dp(this, 16);
        screen.addView(grid, gridParams);
        screen.setVisibility(View.GONE);
        return screen;
    }

    private View tile(Drawable icon, String label, Runnable onClick) {
        LinearLayout card = Ui.column(this);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Ui.rounded(CAR_TILE, this, 26));
        ImageView image = new ImageView(this);
        image.setImageDrawable(icon);
        int size = Ui.dp(this, 64);
        card.addView(image, new LinearLayout.LayoutParams(size, size));
        TextView name = Ui.text(this, label, 19, Color.WHITE, true);
        name.setGravity(Gravity.CENTER);
        card.addView(name, Ui.block(this, 10));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> onClick.run());
        return card;
    }

    private TextView pill(String title, Runnable onClick) {
        TextView button = Ui.text(this, title, 15, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(this, 20), Ui.dp(this, 10), Ui.dp(this, 20), Ui.dp(this, 10));
        button.setBackground(Ui.rounded(CAR_TILE, this, 22));
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
        WindowInsetsController insets = getWindow().getInsetsController();
        if (insets != null) {
            if (home) {
                insets.hide(WindowInsets.Type.systemBars());
                insets.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            } else {
                insets.show(WindowInsets.Type.systemBars());
            }
        }
    }

    // ---- Status and log ----

    private void refresh() {
        CarService service = car != null ? car : CarService.running;
        boolean on = service != null && CarService.running != null;
        showHome(on);
        if (on) {
            homeStatus.setText(service.carSummary());
            dimButton.setText(service.isDimmed() ? "Brighten phone" : "Dim phone");
            return;
        }
        boolean write = PhoneTweaks.allowed(this);
        boolean overlay = Settings.canDrawOverlays(this);
        permissionStatus.setText((write ? "✓" : "✗") + "  Modify system settings (landscape + dim)\n"
                + (overlay ? "✓" : "✗") + "  Display over other apps (LeapBox button, smooth video)");
        setupStatus.setText("Car mode: off\n" + usbSummary());
        String events = Diag.text();
        logView.setText(events.isEmpty() ? "No events yet." : events);
    }

    private String usbSummary() {
        UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
        android.hardware.usb.UsbAccessory[] accessories = usb == null ? null : usb.getAccessoryList();
        if (accessories == null || accessories.length == 0) return "Car USB: not detected";
        return "Car USB: " + accessories[0].getManufacturer() + " / " + accessories[0].getModel() + " detected";
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
