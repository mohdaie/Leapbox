package com.leapbox.prototype;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;

public final class MainActivity extends Activity {
    private SecondScreenService screen;
    private boolean bound;
    private TextView status;
    private TextView logView;
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
        buildInterface();
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(getIntent().getAction())) {
            startLeapBox(SecondScreenService.ACTION_ACCESSORY);
        }
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) {
            startLeapBox(SecondScreenService.ACTION_ACCESSORY);
        }
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

        root.addView(Ui.text(this, "LEAPBOX  /  PROTOTYPE 03", 13, Ui.BLUE, true));
        root.addView(Ui.text(this, "One phone. Two independent screens.", 29, Ui.INK, true),
                Ui.block(this, 10));
        root.addView(Ui.text(this,
                "LeapBox sends its own dashboard to the C10. Your physical phone screen is not captured and can be locked or used normally.",
                16, Ui.MUTED, false), Ui.block(this, 8));

        addSection(root, "01  LEAPBOX → C10",
                "Press Start first, then plug in the C10 cable and open QDLink on the car. LeapBox keeps watching USB until the car appears. Force-stop or disable the QDLink phone app so it cannot take the connection, and pick LeapBox if Android asks which app to open.");
        root.addView(action("Start LeapBox car desktop", Ui.BLUE, this::startLeapBox),
                Ui.block(this, 17));
        root.addView(action("Reconnect QDLink USB", Ui.MUTED,
                () -> { if (screen != null) screen.reconnectCar(); else startLeapBox(); }), Ui.block(this, 10));
        root.addView(action("Inspect connected car USB", Ui.MUTED,
                this::inspectUsb), Ui.block(this, 10));

        addSection(root, "02  INDEPENDENT SCREEN TEST",
                "The C10 should show the LeapBox desktop with one Waze icon. While it stays there, use ChatGPT, WhatsApp, Camera or anything else on the phone.");
        root.addView(action("↗  Diagnostic: try normal Waze on car display", Ui.INK,
                this::launchWazeOnSecond), Ui.block(this, 17));
        root.addView(action("Return car to LeapBox desktop", Ui.MUTED,
                () -> { if (screen != null) screen.showDashboard(); refresh(); }),
                Ui.block(this, 10));

        addSection(root, "03  PHONE INDEPENDENCE",
                "LeapBox now uses a background partial wake lock. It keeps the projection engine alive without deliberately keeping your phone display on.");
        root.addView(action("Stop LeapBox", Ui.INK,
                this::stopScreen), Ui.block(this, 17));

        status = Ui.text(this, "Checking LeapBox service…", 14, Ui.INK, false);
        status.setPadding(Ui.dp(this, 17), Ui.dp(this, 17),
                Ui.dp(this, 17), Ui.dp(this, 17));
        status.setBackground(Ui.rounded(Color.WHITE, this, 16));
        root.addView(status, Ui.block(this, 28));

        addSection(root, "04  DIAGNOSTIC LOG",
                "What LeapBox saw on USB and exchanged with the car. After a car test, copy it and send it back.");
        root.addView(action("Copy diagnostic log", Ui.BLUE, this::copyLog), Ui.block(this, 17));
        logView = Ui.text(this, "", 12, Ui.INK, false);
        logView.setTypeface(android.graphics.Typeface.MONOSPACE);
        logView.setTextIsSelectable(true);
        logView.setPadding(Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14), Ui.dp(this, 14));
        logView.setBackground(Ui.rounded(Color.WHITE, this, 16));
        root.addView(logView, Ui.block(this, 12));
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
        startLeapBox(SecondScreenService.ACTION_START);
    }

    private void startLeapBox(String action) {
        try {
            Intent intent = new Intent(this, SecondScreenService.class).setAction(action);
            startForegroundService(intent);
            tell("Starting independent LeapBox car session…");
        } catch (RuntimeException error) {
            tell("Cannot start LeapBox: " + error.getClass().getSimpleName());
        }
    }

    private void stopScreen() {
        if (screen != null) screen.stopPrototype();
        else stopService(new Intent(this, SecondScreenService.class));
        tell("LeapBox stopped.");
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

    private void copyLog() {
        String text = diagnosticText();
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        if (clipboard == null) return;
        clipboard.setPrimaryClip(ClipData.newPlainText("LeapBox diagnostic log", text));
        tell("Diagnostic log copied");
    }

    private String diagnosticText() {
        String body = status == null ? "" : status.getText().toString();
        String events = screen == null ? "" : screen.carLog();
        return body + "\n\n" + (events.isEmpty() ? "(no USB events yet; press Start first)" : events);
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
                    + " · " + car.getDescription()
                    + " · permission=" + usb.hasPermission(car);
        }
        tell(accessoryStatus);
        refresh();
    }

    private void refresh() {
        if (status == null) return;
        if (logView != null) {
            String events = screen == null ? "" : screen.carLog();
            logView.setText(events.isEmpty() ? "No USB events yet." : events);
        }
        if (screen == null || !screen.isReady()) {
            status.setText("LeapBox display: inactive\nPhone display: independent\n"
                    + accessoryStatus + "\nStart LeapBox after opening QDLink on the C10.");
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
                        + "%s\n"
                        + "Car video requested: %s · sent %,d frames\n"
                        + "C10 touch events: %,d\n"
                        + "%s\n%s",
                screen.displayId(), screen.isAwake() ? "held" : "not held",
                screen.framesEncoded(), screen.bytesEncoded() / 1_000_000.0,
                screen.carConnected() ? "connected" : "not connected", screen.carProtocol(), canvas,
                screen.usbState(),
                screen.carPlaying() ? "yes" : "no", screen.carFramesSent(),
                screen.carTouchEvents(), accessoryStatus, screen.carStatus()));
    }

    private void tell(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }
}
