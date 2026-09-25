package com.leapbox.prototype;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Color;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private SecondScreenService screen;
    private boolean bound;
    private boolean dimPhone;
    private TextView status;
    private String accessoryStatus = "USB accessory: not checked";
    private final Handler handler = new Handler(Looper.getMainLooper());

    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            screen = ((SecondScreenService.LocalBinder) binder).getService();
            refresh();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            screen = null;
            refresh();
        }
    };

    private final Runnable update = new Runnable() {
        @Override public void run() {
            refresh();
            handler.postDelayed(this, 1200);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildInterface();
    }

    @Override protected void onStart() {
        super.onStart();
        bound = bindService(new Intent(this, SecondScreenService.class), connection,
                Context.BIND_AUTO_CREATE);
        handler.post(update);
    }

    @Override protected void onStop() {
        handler.removeCallbacks(update);
        if (bound) {
            unbindService(connection);
            bound = false;
        }
        screen = null;
        super.onStop();
    }

    private void buildInterface() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Ui.PAPER);
        LinearLayout root = Ui.column(this);
        root.setPadding(Ui.dp(this, 23), Ui.dp(this, 36),
                Ui.dp(this, 23), Ui.dp(this, 34));
        scroll.addView(root);

        TextView eyebrow = Ui.text(this, "LEAPBOX  /  PROTOTYPE 01", 13, Ui.BLUE, true);
        root.addView(eyebrow);
        TextView heading = Ui.text(this, "Your road. Your screen.", 30, Ui.INK, true);
        root.addView(heading, Ui.block(this, 10));
        TextView introduction = Ui.text(this,
                "Two paths to test: your current QDLink mirror and a separate Android display.",
                16, Ui.MUTED, false);
        root.addView(introduction, Ui.block(this, 8));

        addSection(root, "01  DRIVE WITH QDLINK",
                "Connect using your installed QDLink app, return here to see this home screen in the car, then tap Waze. This mirrors the phone.");
        root.addView(action("Open QDLink", Ui.BLUE, () -> launchPackage("com.neusoft.qdrivelink")),
                Ui.block(this, 17));
        root.addView(action("↗  Open Waze on phone", Ui.INK,
                () -> launchPackage("com.waze")), Ui.block(this, 10));

        addSection(root, "02  SEPARATE SCREEN LAB",
                "Creates its own landscape dashboard and H.264 stream. The stream is local: it is not yet connected to the C10.");
        root.addView(action("Start second screen + full wake lock", Ui.BLUE,
                this::startScreen), Ui.block(this, 17));
        root.addView(action("Inspect connected car USB", Ui.MUTED,
                this::inspectUsb), Ui.block(this, 10));
        root.addView(action("↗  Try Waze on second screen", Ui.INK,
                this::launchWazeOnSecond), Ui.block(this, 10));
        root.addView(action("Return to LeapBox desktop", Ui.MUTED,
                () -> { if (screen != null) screen.showDashboard(); refresh(); }),
                Ui.block(this, 10));

        addSection(root, "03  PHONE POWER",
                "Full wake lock keeps the phone awake. Low brightness only dims this phone window; it does not turn the display off.");
        root.addView(action("Toggle low phone brightness", Ui.MUTED,
                this::toggleBrightness), Ui.block(this, 17));
        root.addView(action("Stop second screen and release wake lock", Ui.INK,
                this::stopScreen), Ui.block(this, 10));

        status = Ui.text(this, "Checking display service…", 14, Ui.INK, false);
        status.setPadding(Ui.dp(this, 17), Ui.dp(this, 17),
                Ui.dp(this, 17), Ui.dp(this, 17));
        status.setBackground(Ui.rounded(Color.WHITE, this, 16));
        root.addView(status, Ui.block(this, 28));
        setContentView(scroll);
    }

    private void addSection(LinearLayout root, String title, String details) {
        TextView label = Ui.text(this, title, 14, Ui.BLUE, true);
        root.addView(label, Ui.block(this, 31));
        TextView description = Ui.text(this, details, 15, Ui.MUTED, false);
        root.addView(description, Ui.block(this, 7));
    }

    private View action(String title, int color, Runnable onClick) {
        TextView button = Ui.button(this, title, color);
        button.setOnClickListener(view -> onClick.run());
        return button;
    }

    private void startScreen() {
        try {
            Intent intent = new Intent(this, SecondScreenService.class).setAction(
                    SecondScreenService.ACTION_START);
            startForegroundService(intent);
            tell("Starting separate display…");
        } catch (RuntimeException error) {
            tell("Cannot start display: " + error.getClass().getSimpleName());
        }
    }

    private void stopScreen() {
        if (screen != null) screen.stopPrototype();
        else stopService(new Intent(this, SecondScreenService.class));
        tell("Separate display stopped; wake lock released.");
        refresh();
    }

    private void launchWazeOnSecond() {
        if (screen == null) {
            tell("Start the second-screen lab first.");
            return;
        }
        tell(screen.launchWaze(this));
        refresh();
    }

    private void inspectUsb() {
        UsbManager usb = (UsbManager) getSystemService(USB_SERVICE);
        UsbAccessory[] accessories = usb == null ? null : usb.getAccessoryList();
        if (accessories == null || accessories.length == 0) {
            accessoryStatus = "USB accessory: none detected";
        } else {
            UsbAccessory car = accessories[0];
            accessoryStatus = "USB accessory: " + car.getManufacturer() + " / "
                    + car.getModel() + " / " + car.getVersion();
        }
        tell(accessoryStatus);
        refresh();
    }

    private void launchPackage(String packageName) {
        Intent launch = getPackageManager().getLaunchIntentForPackage(packageName);
        if (launch == null) {
            tell(packageName.equals("com.waze") ? "Install Waze first." : "Install QDLink first.");
            return;
        }
        try {
            startActivity(launch);
        } catch (RuntimeException error) {
            tell("Could not open app: " + error.getClass().getSimpleName());
        }
    }

    private void toggleBrightness() {
        dimPhone = !dimPhone;
        WindowManager.LayoutParams attributes = getWindow().getAttributes();
        attributes.screenBrightness = dimPhone ? 0.04f : -1f;
        getWindow().setAttributes(attributes);
        tell(dimPhone ? "Low brightness requested for this phone window."
                : "Normal phone brightness restored.");
    }

    private void refresh() {
        if (status == null) return;
        if (screen == null || !screen.isReady()) {
            status.setText("Second screen: inactive\nCar stream: not connected\n"
                    + "QDLink mirror: available through your existing app\n"
                    + accessoryStatus + "\n"
                    + (screen == null ? "" : screen.status()));
            return;
        }
        status.setText(String.format(Locale.US,
                "Second screen: #%d\nFull wake lock: %s\nEncoded locally: %,d frames (%.1f MB)\nCar stream: not connected\n%s\n%s",
                screen.displayId(), screen.isAwake() ? "held" : "not held",
                screen.framesEncoded(), screen.bytesEncoded() / 1_000_000.0,
                accessoryStatus, screen.status()));
    }

    private void tell(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
