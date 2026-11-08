package com.gaara.remote;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/** Small helpers to build views in code, in the app's style. */
final class Ui {
    private Ui() {}

    /** Follows the phone's own rotation setting, unless the person chose "always upright". */
    static void applyOrientation(android.app.Activity a) {
        int want = Prefs.portraitOnly(a) ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        if (a.getRequestedOrientation() != want) a.setRequestedOrientation(want);
    }

    /** On wide screens the content keeps a readable width (maxDp) in the middle. */
    static void fitScreen(android.app.Activity a, android.view.View content, int maxDp) {
        android.content.res.Configuration c = a.getResources().getConfiguration();
        applyOrientation(a);
        int side = Math.max(0, dp(a, (c.screenWidthDp - maxDp) / 2f));
        content.setPadding(content.getPaddingLeft() + side, content.getPaddingTop(),
                content.getPaddingRight() + side, content.getPaddingBottom());
    }

    static int dp(Context c, float v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    static TextView text(Context c, CharSequence s, float sp, int colorRes) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(c.getColor(colorRes));
        return t;
    }

    static TextView header(Context c, String s) {
        TextView t = text(c, s.toUpperCase(Locale.FRENCH), 12, R.color.muted);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(c, 20), 0, dp(c, 8));
        return t;
    }

    /** A round coloured badge with a white icon, like a file-type avatar. */
    static ImageView badge(Context c, int iconRes, int color, int sizeDp) {
        ImageView v = new ImageView(c);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(color);
        v.setBackground(bg);
        v.setImageResource(iconRes);
        v.setColorFilter(0xFFFFFFFF);
        int p = dp(c, sizeDp * 0.22f);
        v.setPadding(p, p, p, p);
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    static LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    }

    static String size(long b) {
        if (b < 1024) return b + " o";
        double v = b;
        String[] u = {"Ko", "Mo", "Go", "To"};
        int i = -1;
        do {
            v /= 1024;
            i++;
        } while (v >= 1024 && i < u.length - 1);
        return String.format(Locale.FRENCH, v < 10 ? "%.1f %s" : "%.0f %s", v, u[i]);
    }
}
