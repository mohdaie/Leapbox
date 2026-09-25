package com.leapbox.prototype;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

/** The separate display's own UI, independent of the phone's visible activity. */
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
        root.setPadding(Ui.dp(context, 52), Ui.dp(context, 28),
                Ui.dp(context, 52), Ui.dp(context, 28));

        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = Ui.text(context, "LEAPBOX", 23, Color.WHITE, true);
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextClock clock = new TextClock(context);
        clock.setFormat12Hour("h:mm a");
        clock.setFormat24Hour("HH:mm");
        clock.setTextColor(Color.WHITE);
        clock.setTextSize(22);
        header.addView(clock);
        root.addView(header);

        TextView title = Ui.text(context, "Ready for the road.", 39, Color.WHITE, true);
        root.addView(title, Ui.block(context, 38));
        TextView subtitle = Ui.text(context,
                "A separate dashboard while your phone stays available.", 18,
                Color.rgb(178, 197, 222), false);
        root.addView(subtitle, Ui.block(context, 8));

        LinearLayout card = Ui.column(context);
        card.setPadding(Ui.dp(context, 28), Ui.dp(context, 24),
                Ui.dp(context, 28), Ui.dp(context, 24));
        card.setBackground(Ui.rounded(Color.rgb(32, 47, 68), context, 24));
        TextView symbol = Ui.text(context, "↗", 54, Color.rgb(83, 215, 197), true);
        card.addView(symbol);
        TextView label = Ui.text(context, "Waze", 30, Color.WHITE, true);
        card.addView(label);
        TextView description = Ui.text(context, "Tap to test launch on this display", 16,
                Color.rgb(179, 198, 218), false);
        card.addView(description, Ui.block(context, 5));
        TextView result = Ui.text(context, "", 14, Color.rgb(255, 204, 123), false);
        card.addView(result, Ui.block(context, 12));
        card.setClickable(true);
        card.setFocusable(true);
        card.setOnClickListener(view -> {
            String message = service.launchWaze(context);
            result.setText(message);
        });
        root.addView(card, Ui.block(context, 28));

        TextView footer = Ui.text(context,
                "PROTOTYPE  ·  Local display only  ·  C10 transport pending", 13,
                Color.rgb(158, 174, 194), true);
        root.addView(footer, Ui.block(context, 25));
        setContentView(root);
    }
}
