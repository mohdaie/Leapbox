package com.leapbox.prototype;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/** The car-only LeapBox desktop. It is not the phone's visible screen. */
final class CarDashboardPresentation extends Presentation {
    private final SecondScreenService service;

    CarDashboardPresentation(Context context, Display display, SecondScreenService service) {
        super(context, display);
        this.service = service;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getContext();
        LinearLayout root = Ui.column(context);
        root.setBackgroundColor(Ui.INK);
        root.setPadding(Ui.dp(context, 68), Ui.dp(context, 34),
                Ui.dp(context, 68), Ui.dp(context, 34));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = Ui.text(context, "LEAPBOX", 24, Color.WHITE, true);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextClock clock = new TextClock(context);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(22);
        header.addView(clock);
        root.addView(header);

        TextView title = Ui.text(context, "Your car screen. Your phone stays yours.", 35, Color.WHITE, true);
        root.addView(title, Ui.block(context, 40));
        TextView subtitle = Ui.text(context,
                "LeapBox runs this dashboard independently from the phone display.", 18,
                Color.rgb(178, 197, 222), false);
        root.addView(subtitle, Ui.block(context, 8));

        LinearLayout card = Ui.column(context);
        card.setPadding(Ui.dp(context, 34), Ui.dp(context, 30),
                Ui.dp(context, 34), Ui.dp(context, 30));
        card.setBackground(Ui.rounded(Color.rgb(32, 47, 68), context, 28));
        TextView symbol = Ui.text(context, "↗", 58, Color.rgb(83, 215, 197), true);
        card.addView(symbol);
        TextView label = Ui.text(context, "Waze", 34, Color.WHITE, true);
        card.addView(label);
        TextView description = Ui.text(context, "Tap to verify C10 → LeapBox touch", 16,
                Color.rgb(179, 198, 218), false);
        card.addView(description, Ui.block(context, 5));
        TextView result = Ui.text(context, "", 14, Color.rgb(255, 204, 123), false);
        card.addView(result, Ui.block(context, 12));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> result.setText(service.dashboardWazeSelected()));
        root.addView(card, Ui.block(context, 30));

        TextView footer = Ui.text(context,
                "LEAPBOX 0.2  ·  INDEPENDENT DISPLAY  ·  QDLINK USB", 13,
                Color.rgb(158, 174, 194), true);
        root.addView(footer, Ui.block(context, 26));
        setContentView(root);
    }

    void dispatchCarTouch(MotionEvent event) {
        View decor = getWindow() == null ? null : getWindow().getDecorView();
        if (decor != null) decor.dispatchTouchEvent(event);
    }
}
