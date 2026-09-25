package com.leapbox.prototype;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

final class Ui {
    static final int INK = Color.rgb(18, 27, 43);
    static final int MUTED = Color.rgb(93, 106, 123);
    static final int BLUE = Color.rgb(21, 90, 166);
    static final int PAPER = Color.rgb(248, 250, 253);

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

    static GradientDrawable rounded(int color, Context context, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(context, radius));
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

    static TextView button(Context context, String title, int background) {
        TextView view = text(context, title, 18, Color.WHITE, true);
        view.setGravity(Gravity.CENTER);
        view.setMinHeight(dp(context, 58));
        view.setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12));
        view.setBackground(rounded(background, context, 17));
        view.setClickable(true);
        view.setFocusable(true);
        return view;
    }
}
