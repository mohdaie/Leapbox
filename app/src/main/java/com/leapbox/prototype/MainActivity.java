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
            handler.postDelayed(this, 1000);
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

        root.addView(Ui.text(this, "LEAPBOX  /  PROTOTYPE 02", 13, Ui.BLUE, true));
        root.addView(Ui.text(this, "Your road. Your screen.", 29, Ui.INK, true),
                Ui.block(this, 10));
        root.addView(Ui.text(this,
                "Drive with your QDLink mirror. The independent car screen is an experiment and is off unless you start it.",
                16, Ui.MUTED, false), Ui.block(this, 8));

        addSection(root, "01  DRIVE WITH QDLINK",
                "Connect the USB cable, start mirroring in your QDLink app, return here, then tap Waze. This mirrors the phone on the C10.");
        root.addView(action("Open QDLink", Ui.BLUE, () -> launchPackage("com.neusoft.qdrivelink")),
                Ui.block(this, 17));
        root.addView(action("↗  Open Waze on phone", Ui.INK,
                () -> launchPackage("com.waze")), Ui.block(this, 10));

        addSection(root, "02  EXPERIMENTAL: INDEPENDENT C10 SCREEN",
                "Not for driving yet. LeapBox takes the QDriveLink USB connection away from the QDLink app, so mirroring stops until you press Stop LeapBox and reconnect QDLink.");
        root.addView(action("Start LeapBox car desktop", Ui.MUTED, this::startLeapBox),
                Ui.block(this, 17));
        root.addView(action("Reconnect LeapBox USB bridge", Ui.MUTED,
                () -> { if (screen != null) screen.reconnectCar(); else startLeapBox(); }), Ui.block(this, 10));
        root.addView(action("Inspect connected car USB", Ui.MUTED,
                this::inspectUsb), Ui.block(this, 10));
        root.addView(action("↗  Diagnostic: try normal Waze on car display", Ui.MUTED,
                this::launchWazeOnSecond), Ui.block(this, 10));
        root.addView(action("Return car to LeapBox desktop", Ui.MUTED,
                () -> { if (screen != null) screen.showDashboard(); refresh(); }),
                Ui.block(this, 10));
        root.addView(action("Stop LeapBox (give USB back to QDLink)", Ui.INK,
                this::stopScreen), Ui.block(this, 10));

        status = Ui.text(this, "Checking LeapBox service…", 14, Ui.INK, false);
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

    private void startLeapBox() {
        try {
            Intent intent = new Intent(this, SecondScreenService.class).setAction(
                    SecondScreenService.ACTION_START);
            startForegroundService(intent);
            tell("Starting independent LeapBox car session…");
        } catch (RuntimeException error) {
            tell("Cannot start LeapBox: " + error.getClass().getSimpleName());
        }
    }

    private void stopScreen() {
        if (screen != null) screen.stopPrototype();
        else stopService(new Intent(this, SecondScreenService.class));
        tell("LeapBox stopped. Unplug and replug the cable, then start QDLink.");
        refresh();
    }

    private void launchWazeOnSecond() {
        if (screen == null) {
            tell("Start LeapBox first.");
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
                    + car.getModel() + " / " + car.getVersion()
                    + " · permission=" + usb.hasPermission(car);
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

    private void refresh() {
        if (status == null) return;
        if (screen == null || !screen.isReady()) {
            status.setText("Experimental car desktop: off\n"
                    + "QDLink mirror: free to use the car USB connection\n" + accessoryStatus);
            return;
        }
        String canvas = screen.carWidth() > 0
                ? screen.carWidth() + "×" + screen.carHeight() : "unknown";
        status.setText(String.format(Locale.US,
                "LeapBox display: #%d · 1920×882\n"
                        + "Phone display: independent / may lock\n"
                        + "Background wake: %s\n"
                        + "Encoded locally: %,d frames (%.1f MB)\n"
                        + "QDLink: %s · protocol %s · car canvas %s\n"
                        + "Car video requested: %s · sent %,d frames\n"
                        + "C10 touch events: %,d\n"
                        + "%s\n%s",
                screen.displayId(), screen.isAwake() ? "held" : "not held",
                screen.framesEncoded(), screen.bytesEncoded() / 1_000_000.0,
                screen.carConnected() ? "connected" : "not connected", screen.carProtocol(), canvas,
                screen.carPlaying() ? "yes" : "no", screen.carFramesSent(),
                screen.carTouchEvents(), accessoryStatus, screen.carStatus()));
    }

    private void tell(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
