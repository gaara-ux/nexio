package com.gaara.remote;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The person's choices: look, buttons, behaviour. Everything has a sensible default. */
final class Prefs {
    static final String THEME_DARK = "dark", THEME_LIGHT = "light", THEME_SYSTEM = "system";
    static final String SIZE_AUTO = "auto", SIZE_SMALL = "small", SIZE_LARGE = "large";

    private Prefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("prefs", Context.MODE_PRIVATE);
    }

    static String get(Context c, String key, String def) { return p(c).getString(key, def); }
    static boolean on(Context c, String key, boolean def) { return p(c).getBoolean(key, def); }
    static void put(Context c, String key, String v) { p(c).edit().putString(key, v).apply(); }
    static void put(Context c, String key, boolean v) { p(c).edit().putBoolean(key, v).apply(); }

    static String theme(Context c) { return get(c, "theme", THEME_DARK); }
    static String buttonSize(Context c) { return get(c, "button_size", SIZE_AUTO); }
    static boolean portraitOnly(Context c) { return on(c, "portrait_only", false); }
    static boolean haptics(Context c) { return on(c, "haptics", true); }
    static boolean volumeKeys(Context c) { return on(c, "volume_keys", true); }
    static boolean keepScreenOn(Context c) { return on(c, "keep_screen_on", false); }
    static boolean showMedia(Context c) { return on(c, "show_media", true); }
    static boolean showKeyboard(Context c) { return on(c, "show_keyboard", true); }
    static boolean autoKeyboard(Context c) { return on(c, "auto_keyboard", true); }

    /** Favourite apps for the launcher, in the order they were added. */
    static List<String> favourites(Context c) {
        String s = get(c, "favourites", "");
        return s.isEmpty() ? new ArrayList<>() : new ArrayList<>(Arrays.asList(s.split(",")));
    }

    static boolean toggleFavourite(Context c, String pkg) {
        List<String> f = favourites(c);
        boolean added = !f.remove(pkg);
        if (added) f.add(pkg);
        put(c, "favourites", android.text.TextUtils.join(",", f));
        return added;
    }

    /**
     * Applies the chosen theme to an activity: call right after super.attachBaseContext. Only the
     * night bit is overridden (light colours live in values-notnight), so rotation, size and density
     * changes keep reaching the activity.
     */
    static void applyTheme(android.app.Activity a, Context base) {
        String t = theme(base);
        if (THEME_SYSTEM.equals(t)) return;
        Configuration o = new Configuration();
        int ui = base.getResources().getConfiguration().uiMode;
        o.uiMode = (ui & ~Configuration.UI_MODE_NIGHT_MASK)
                | (THEME_LIGHT.equals(t) ? Configuration.UI_MODE_NIGHT_NO : Configuration.UI_MODE_NIGHT_YES);
        a.applyOverrideConfiguration(o);
    }
}
