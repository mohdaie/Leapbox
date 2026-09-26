package com.leapbox.prototype;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Warm, Claude-inspired palette and small view helpers. */
final class Ui {
    // Light (phone setup screens)
    static final int PAPER = Color.rgb(245, 244, 237);
    static final int CARD = Color.rgb(255, 255, 253);
    static final int LINE = Color.rgb(229, 226, 214);
    static final int INK = Color.rgb(20, 20, 19);
    static final int MUTED = Color.rgb(107, 104, 96);
    // Dark (car home screen)
    static final int NIGHT = Color.rgb(38, 38, 36);
    static final int NIGHT_CARD = Color.rgb(48, 48, 46);
    static final int NIGHT_LINE = Color.rgb(64, 63, 59);
    static final int CREAM = Color.rgb(250, 249, 245);
    static final int SAND = Color.rgb(166, 162, 150);
    // Accent
    static final int CLAY = Color.rgb(217, 119, 87);
    static final int CLAY_DEEP = Color.rgb(193, 95, 60);

    /** Kept for older call sites: the accent now replaces the previous blue. */
    static final int BLUE = CLAY;

    private Ui() {}

    static int dp(Context context, float value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + 0.5f);
    }

    static TextView text(Context context, String content, int sp, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(content);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    /** Serif headline, as in Claude's interface. */
    static TextView serif(Context context, String content, int sp, int color) {
        TextView view = text(context, content, sp, color, false);
        view.setTypeface(Typeface.create(Typeface.SERIF, Typeface.NORMAL));
        view.setLetterSpacing(-0.01f);
        return view;
    }

    static GradientDrawable rounded(int color, Context context, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
        return drawable;
    }

    static GradientDrawable outlined(int fill, int stroke, Context context, float radius) {
        GradientDrawable drawable = rounded(fill, context, radius);
        drawable.setStroke(dp(context, 1), stroke);
        return drawable;
    }

    static LinearLayout column(Context context) {
        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    static LinearLayout.LayoutParams block(Context context, int top) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.topMargin = dp(context, top);
        return params;
    }

    /** Filled button; white text on the given colour. */
    static TextView button(Context context, String title, int background) {
        TextView view = text(context, title, 16, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 54));
        view.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        view.setBackground(rounded(background, context, 14));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }

    /** Quiet outlined button for the light screens. */
    static TextView ghostButton(Context context, String title) {
        TextView view = text(context, title, 15, INK, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 50));
        view.setPadding(dp(context, 16), dp(context, 10), dp(context, 16), dp(context, 10));
        view.setBackground(outlined(CARD, LINE, context, 14));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }
}
