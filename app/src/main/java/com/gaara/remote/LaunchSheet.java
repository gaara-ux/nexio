package com.gaara.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.gaara.remote.share.ApkStore;
import com.gaara.remote.share.Share;
import com.gaara.remote.share.ShareClient;
import com.gaara.remote.share.ShareHub;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Opens an app on the TV. The list comes from the TV itself when it runs Nexio (with icons);
 * otherwise it is a list of common TV apps. A long press adds or removes a favourite: favourites
 * come first, here and in the row on the remote.
 */
final class LaunchSheet {
    /** Common TV apps, for TVs that do not run Nexio. */
    static final String[][] COMMON = {
            {"com.google.android.youtube.tv", "YouTube"}, {"com.netflix.ninja", "Netflix"},
            {"com.amazon.amazonvideo.livingroom", "Prime Video"}, {"com.disney.disneyplus", "Disney+"},
            {"ca.bellmedia.cravetv", "Crave"}, {"tv.tou.android", "ICI TOU.TV"}, {"com.spotify.tv.android", "Spotify"},
            {"com.plexapp.android", "Plex"}, {"org.xbmc.kodi", "Kodi"}, {"com.google.android.youtube.tvmusic", "YouTube Music"},
            {"com.apple.atve.androidtv.appletv", "Apple TV"}, {"com.android.vending", "Play Store"},
            {"com.android.tv.settings", "Paramètres"},
    };

    private final Activity act;
    private final Devices.Device device;
    private final Consumer<String> launch;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> apps = new LinkedHashMap<>(); // pkg -> label
    private final Map<String, Bitmap> icons = new java.util.HashMap<>();
    private GridLayout grid;
    private TextView note;
    private AlertDialog dialog;
    private Share.Peer peer;
    Runnable onClose;

    LaunchSheet(Activity act, Devices.Device device, Consumer<String> launch) {
        this.act = act;
        this.device = device;
        this.launch = launch;
    }

    /** The TV's own Nexio server, when it runs it and is paired for files. */
    static Share.Peer peerFor(Activity act, Devices.Device d) {
        ShareHub hub = ShareHub.get(act);
        for (Share.Peer p : Share.peers(act))
            if (p.tv && (d.host.equals(hub.hostOf(p)) || d.host.equals(p.host) || d.name.equalsIgnoreCase(p.name))) return p;
        return null;
    }

    void show() {
        LinearLayout box = Ui.column(act);
        int pad = Ui.dp(act, 18);
        box.setPadding(pad, Ui.dp(act, 6), pad, pad);
        note = Ui.text(act, "", 13, R.color.muted);
        note.setPadding(0, 0, 0, Ui.dp(act, 10));
        box.addView(note);
        grid = new GridLayout(act);
        int widthDp = act.getResources().getConfiguration().screenWidthDp;
        grid.setColumnCount(Math.max(3, Math.min(6, (widthDp - 40) / 104)));
        box.addView(grid);
        ScrollView scroll = new ScrollView(act);
        scroll.addView(box);

        for (String[] a : COMMON) apps.put(a[0], a[1]);
        peer = peerFor(act, device);
        note.setText(peer != null ? "Chargement des applis de " + device.name + "…"
                : "Applis courantes. Installe Nexio sur la TV pour voir toutes ses applis avec leurs icônes.");
        build();
        dialog = new AlertDialog.Builder(act).setTitle("Ouvrir sur " + device.name).setView(scroll)
                .setNegativeButton("Fermer", null).setOnDismissListener(d -> { if (onClose != null) onClose.run(); }).show();
        if (peer != null) loadFromTv();
    }

    private void loadFromTv() {
        Share.Peer p = peer;
        new Thread(() -> {
            try {
                List<ApkStore.App> list = ShareClient.apps(act, p);
                Map<String, String> found = new LinkedHashMap<>();
                for (ApkStore.App a : list) {
                    if (!a.launch || a.pkg.equals(act.getPackageName())) continue;
                    if (!a.system || a.pkg.equals("com.android.tv.settings")) found.put(a.pkg, a.label);
                }
                main.post(() -> {
                    apps.clear();
                    apps.putAll(found);
                    note.setText("Touche pour ouvrir · garde le doigt pour l'ajouter aux favoris");
                    build();
                });
                for (String pkg : found.keySet()) {
                    try {
                        byte[] b = ShareClient.icon(act, p, pkg);
                        Bitmap bm = BitmapFactory.decodeByteArray(b, 0, b.length);
                        if (bm != null) main.post(() -> { icons.put(pkg, bm); build(); });
                    } catch (Exception ignored) {
                    }
                }
            } catch (Exception e) {
                main.post(() -> note.setText("La TV ne répond pas (Nexio doit y être lancée) : applis courantes."));
            }
        }).start();
    }

    private void build() {
        if (dialog != null && !dialog.isShowing()) return;
        grid.removeAllViews();
        List<String> order = new ArrayList<>(apps.keySet());
        List<String> fav = Prefs.favourites(act);
        Collections.sort(order, (a, b) -> {
            int fa = fav.indexOf(a), fb = fav.indexOf(b);
            if (fa >= 0 || fb >= 0) return (fa < 0 ? 999 : fa) - (fb < 0 ? 999 : fb);
            return apps.get(a).compareToIgnoreCase(apps.get(b));
        });
        int cell = 0; // columns share the width (see tile)
        for (String pkg : order) grid.addView(tile(act, pkg, apps.get(pkg), icons.get(pkg), fav.contains(pkg), cell, v -> {
            launch.accept(pkg);
            if (dialog != null) dialog.dismiss();
        }, v -> {
            boolean added = Prefs.toggleFavourite(act, pkg);
            Toast.makeText(act, apps.get(pkg) + (added ? " ajouté aux favoris" : " retiré des favoris"), Toast.LENGTH_SHORT).show();
            build();
            return true;
        }));
    }

    /** An app tile: icon (or its first letter), name, a star when it is a favourite. */
    static View tile(Activity act, String pkg, String label, Bitmap icon, boolean fav, int width,
                     View.OnClickListener click, View.OnLongClickListener longClick) {
        LinearLayout t = Ui.column(act);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setPadding(Ui.dp(act, 4), Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8));
        t.setBackgroundResource(R.drawable.pill_button);
        FrameLayout iconBox = new FrameLayout(act);
        int s = Ui.dp(act, 52);
        if (icon != null) {
            ImageView iv = new ImageView(act);
            iv.setImageBitmap(icon);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            iconBox.addView(iv, new FrameLayout.LayoutParams(s, s));
        } else {
            TextView letter = Ui.text(act, label.isEmpty() ? "?" : label.substring(0, 1).toUpperCase(), 22, R.color.text);
            letter.setGravity(Gravity.CENTER);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(act.getColor(R.color.surface_pressed));
            letter.setBackground(bg);
            iconBox.addView(letter, new FrameLayout.LayoutParams(s, s));
        }
        if (fav) {
            ImageView star = new ImageView(act);
            star.setImageResource(R.drawable.ic_star);
            star.setColorFilter(act.getColor(R.color.warn));
            FrameLayout.LayoutParams sl = new FrameLayout.LayoutParams(Ui.dp(act, 18), Ui.dp(act, 18), Gravity.TOP | Gravity.END);
            iconBox.addView(star, sl);
        }
        t.addView(iconBox);
        TextView name = Ui.text(act, label, 12, R.color.text);
        name.setGravity(Gravity.CENTER);
        name.setMaxLines(2);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        name.setPadding(0, Ui.dp(act, 6), 0, 0);
        t.addView(name, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        t.setContentDescription(label);
        t.setOnClickListener(click);
        if (longClick != null) t.setOnLongClickListener(longClick);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(GridLayout.spec(GridLayout.UNDEFINED),
                GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = width;
        lp.setMargins(Ui.dp(act, 2), Ui.dp(act, 2), Ui.dp(act, 2), Ui.dp(act, 2));
        t.setLayoutParams(lp);
        return t;
    }

    /** "market://launch?id=pkg" opens any installed app; known apps could use their own links. */
    static String link(String pkg) {
        return "market://launch?id=" + pkg;
    }
}
