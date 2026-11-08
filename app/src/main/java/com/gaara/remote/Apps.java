package com.gaara.remote;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import java.util.HashMap;
import java.util.Map;

/** A readable name for the app that is open on the TV. */
final class Apps {
    private static final Map<String, String> KNOWN = new HashMap<>();

    static {
        KNOWN.put("com.google.android.youtube.tv", "YouTube");
        KNOWN.put("com.google.android.youtube.tvkids", "YouTube Kids");
        KNOWN.put("com.netflix.ninja", "Netflix");
        KNOWN.put("com.amazon.amazonvideo.livingroom", "Prime Video");
        KNOWN.put("com.disney.disneyplus", "Disney+");
        KNOWN.put("ca.bellmedia.cravetv", "Crave");
        KNOWN.put("tv.tou.android", "ICI TOU.TV");
        KNOWN.put("ca.fizz_app.tv", "Fizz");
        KNOWN.put("com.plexapp.android", "Plex");
        KNOWN.put("org.xbmc.kodi", "Kodi");
        KNOWN.put("com.spotify.tv.android", "Spotify");
        KNOWN.put("com.android.tv.settings", "Paramètres");
        KNOWN.put("com.google.android.tvlauncher", "Accueil");
        KNOWN.put("com.google.android.apps.tv.launcherx", "Accueil");
        KNOWN.put("com.gathin.tvhome", "Accueil");
        KNOWN.put("com.google.android.backdrop", "Économiseur d'écran");
        KNOWN.put("com.nvidia.tvlauncher", "Accueil");
        KNOWN.put("io.gh.reisxd.tizentube.cobalt", "TizenTube");
        KNOWN.put("com.crunchyroll.crunchyroid", "Crunchyroll");
        KNOWN.put("com.mb.android", "Emby");
        KNOWN.put("com.android.vending", "Play Store");
        KNOWN.put("io.github.gaara_ux.nexio", "Nexio");
        KNOWN.put("com.gaara.remote", "Nexio");
    }

    private Apps() {}

    private static final java.util.Set<String> NOISE = new java.util.HashSet<>(java.util.Arrays.asList(
            "com", "org", "net", "io", "ca", "tv", "android", "app", "apps", "google", "mobile", "atv",
            "androidtv", "leanback", "player", "gh", "cobalt"));

    /** "io.gh.reisxd.tizentube.cobalt" gives "Tizentube": the longest meaningful part, capitalised. */
    static String guess(String pkg) {
        String best = "";
        for (String part : pkg.split("[._]")) {
            if (part.length() < 3 || NOISE.contains(part.toLowerCase(java.util.Locale.ROOT))) continue;
            if (part.length() > best.length()) best = part;
        }
        if (best.isEmpty()) return pkg;
        return Character.toUpperCase(best.charAt(0)) + best.substring(1);
    }

    static String label(Context ctx, String pkg) {
        String known = KNOWN.get(pkg);
        if (known != null) return known;
        // Same app installed on the phone? Use its name.
        try {
            PackageManager pm = ctx.getPackageManager();
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return guess(pkg);
        }
    }
}
