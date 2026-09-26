package com.leapbox.prototype;

import android.app.Presentation;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Display;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * Small "LEAPBOX" button floating over apps on the car display, so the driver can get back to
 * the LeapBox desktop. Touches outside it go to the app underneath.
 */
final class CarHomeButton extends Presentation {
    private final Runnable onTap;

    CarHomeButton(Context context, Display display, Runnable onTap) {
        super(context, display);
        this.onTap = onTap;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Context context = getContext();
        TextView button = Ui.text(context, "LEAPBOX", 15, Color.WHITE, true);
        button.setGravity(Gravity.CENTER);
        button.setPadding(Ui.dp(context, 18), Ui.dp(context, 12), Ui.dp(context, 18), Ui.dp(context, 12));
        button.setBackground(Ui.rounded(Color.argb(220, 21, 90, 166), context, 22));
        button.setOnClickListener(view -> onTap.run());
        setContentView(button);

        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawable(null);
            window.setLayout(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT);
            window.setGravity(Gravity.BOTTOM | Gravity.START);
            WindowManager.LayoutParams params = window.getAttributes();
            params.x = Ui.dp(context, 16);
            params.y = Ui.dp(context, 16);
            params.dimAmount = 0f;
            window.setAttributes(params);
            window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                    | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }
}
