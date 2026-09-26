package com.leapbox.prototype;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/** One shared, copyable diagnostic log for USB, display and Shizuku events. */
final class Diag {
    private static final int LINES = 120;
    private static final ArrayDeque<String> LOG = new ArrayDeque<>();
    private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    private Diag() {}

    static void log(String line) {
        String stamped;
        synchronized (CLOCK) { stamped = CLOCK.format(new Date()) + "  " + line; }
        synchronized (LOG) {
            LOG.addLast(stamped);
            while (LOG.size() > LINES) LOG.removeFirst();
        }
    }

    static String text() {
        synchronized (LOG) { return String.join("\n", LOG); }
    }
}
