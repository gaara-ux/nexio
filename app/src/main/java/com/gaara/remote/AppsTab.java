package com.gaara.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.gaara.remote.share.ApkStore;
import com.gaara.remote.share.Share;
import com.gaara.remote.share.ShareClient;
import com.gaara.remote.share.ShareHub;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * APK extractor: the apps of this phone or of a paired device (the SHIELD...), searchable.
 * Tap an app to extract it, hold to choose several. Files go to Download/Nexio/APK on the phone;
 * an app in several pieces becomes a .apks (installable with SAI or adb install-multiple).
 */
final class AppsTab extends LinearLayout implements ShareHub.Listener {
    private final Activity act;
    private final FilesTab.Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService icons = Executors.newFixedThreadPool(3);
    private final LruCache<String, Drawable> iconCache = new LruCache<>(400);
    private final Set<String> iconLoading = new HashSet<>();

    private final LinearLayout deviceRow, actions;
    private final EditText search;
    private final TextView systemChip, empty;
    private final ListView listView;
    private final Adapter adapter = new Adapter();

    private Share.Peer device;                 // null = this phone
    private List<ApkStore.App> all = new ArrayList<>();
    private final List<ApkStore.App> shown = new ArrayList<>();
    private final Set<String> selected = new HashSet<>();
    private boolean showSystem, loading;
    private int generation;                    // ignores answers for a device no longer shown

    AppsTab(Activity act, FilesTab.Host host) {
        super(act);
        this.act = act;
        this.host = host;
        setOrientation(VERTICAL);
        setPadding(0, Ui.dp(act, 6), 0, 0);

        HorizontalScrollView devScroll = new HorizontalScrollView(act);
        devScroll.setHorizontalScrollBarEnabled(false);
        deviceRow = Ui.row(act);
        devScroll.addView(deviceRow);
        addView(devScroll);

        LinearLayout searchRow = Ui.row(act);
        searchRow.setPadding(0, Ui.dp(act, 8), 0, 0);
        search = new EditText(act);
        search.setHint("Chercher une appli ou un package");
        search.setSingleLine(true);
        search.setTextColor(act.getColor(R.color.text));
        search.setHintTextColor(act.getColor(R.color.muted));
        search.setTextSize(15);
        search.setBackgroundResource(R.drawable.chip);
        search.setPadding(Ui.dp(act, 14), Ui.dp(act, 10), Ui.dp(act, 14), Ui.dp(act, 10));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                filter();
            }
        });
        searchRow.addView(search, Ui.weight1());
        systemChip = Ui.text(act, "Système", 13, R.color.text);
        systemChip.setPadding(Ui.dp(act, 12), Ui.dp(act, 10), Ui.dp(act, 12), Ui.dp(act, 10));
        systemChip.setOnClickListener(v -> {
            showSystem = !showSystem;
            paintSystemChip();
            filter();
        });
        LayoutParams sysLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        sysLp.leftMargin = Ui.dp(act, 6);
        searchRow.addView(systemChip, sysLp);
        paintSystemChip();
        addView(searchRow);

        HorizontalScrollView barScroll = new HorizontalScrollView(act);
        barScroll.setHorizontalScrollBarEnabled(false);
        actions = Ui.row(act);
        actions.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 4));
        barScroll.addView(actions);
        addView(barScroll);

        empty = Ui.text(act, "", 14, R.color.muted);
        empty.setPadding(Ui.dp(act, 4), Ui.dp(act, 16), Ui.dp(act, 4), 0);
        addView(empty);

        listView = new ListView(act);
        listView.setDivider(null);
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((p, v, pos, id) -> tap(shown.get(pos)));
        listView.setOnItemLongClickListener((p, v, pos, id) -> {
            toggle(shown.get(pos));
            return true;
        });
        addView(listView, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        buildDevices();
        buildActions();
    }

    void attach() {
        ShareHub.get(act).addListener(this);
        if (all.isEmpty() && !loading) load();
    }

    void detach() {
        ShareHub.get(act).removeListener(this);
    }

    /** Back leaves the selection first. */
    boolean back() {
        if (selected.isEmpty()) return false;
        clearSelection();
        return true;
    }

    @Override
    public void onShareChanged() {
        buildDevices();
    }

    // ---- Devices ----

    private void buildDevices() {
        deviceRow.removeAllViews();
        ShareHub hub = ShareHub.get(act);
        deviceRow.addView(chip("Ce téléphone", R.drawable.ic_phone, device == null, v -> switchTo(null)));
        for (Share.Peer peer : Share.peers(act)) {
            deviceRow.addView(chip((hub.online(peer) ? "● " : "○ ") + peer.name, peer.tv ? R.drawable.ic_tv : R.drawable.ic_pc,
                    device != null && device.uuid.equals(peer.uuid), v -> switchTo(peer)));
        }
    }

    private void switchTo(Share.Peer p) {
        if (ShareClient.same(device, p) && !all.isEmpty()) return;
        device = p;
        all = new ArrayList<>();
        selected.clear();
        iconCache.evictAll();
        buildDevices();
        load();
    }

    private void load() {
        int gen = ++generation;
        loading = true;
        shown.clear();
        adapter.notifyDataSetChanged();
        empty.setText(device == null ? "Lecture des applis…" : "Lecture des applis de " + device.name + "…");
        empty.setVisibility(VISIBLE);
        buildActions();
        Share.Peer from = device;
        new Thread(() -> {
            try {
                List<ApkStore.App> apps = ShareClient.apps(act, from);
                main.post(() -> {
                    if (gen != generation) return;
                    loading = false;
                    all = apps;
                    filter();
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (gen != generation) return;
                    loading = false;
                    String m = ShareClient.message(e);
                    if (m.contains("inconnu")) {
                        m = from.name + " ne sait pas encore lister ses applis : installe-lui la dernière version "
                                + "de l'appli Nexio (un PC n'a pas d'applis Android).";
                    }
                    empty.setText(m);
                    buildActions();
                });
            }
        }, "apps-list").start();
    }

    // ---- List ----

    private void filter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (ApkStore.App a : all) {
            if (!showSystem && a.system) continue;
            if (!q.isEmpty() && !a.label.toLowerCase(Locale.ROOT).contains(q) && !a.pkg.toLowerCase(Locale.ROOT).contains(q)) continue;
            shown.add(a);
        }
        adapter.notifyDataSetChanged();
        if (!loading) {
            empty.setText(all.isEmpty() ? "Aucune appli." : "Aucune appli ne correspond.");
            empty.setVisibility(shown.isEmpty() ? VISIBLE : GONE);
        }
        buildActions();
    }

    private void paintSystemChip() {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.getColor(showSystem ? R.color.accent : R.color.surface_pressed));
        bg.setCornerRadius(Ui.dp(act, 18));
        systemChip.setBackground(bg);
    }

    private void tap(ApkStore.App a) {
        if (!selected.isEmpty()) {
            toggle(a);
            return;
        }
        String where = device == null ? "ce téléphone" : device.name;
        String msg = a.pkg
                + "\nVersion " + (a.version.isEmpty() ? "?" : a.version) + "  (code " + a.code + ")"
                + "\n" + Ui.size(a.size) + (a.parts > 1 ? "  ·  " + a.parts + " morceaux (split APK)" : "")
                + (a.updated > 0 ? "\nMise à jour : " + DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.FRENCH).format(new Date(a.updated)) : "")
                + (a.system ? "\nAppli système" : "")
                + "\n\nSur " + where + ", enregistrée sous :\n" + ApkStore.FOLDER + "/" + a.fileName()
                + (a.parts > 1 ? "\n\nUn .apks s'installe avec SAI, ou « adb install-multiple » après l'avoir dézippé." : "");
        new AlertDialog.Builder(act)
                .setTitle(a.label)
                .setIcon(iconCache.get(a.pkg))
                .setMessage(msg)
                .setPositiveButton("Extraire", (d, w) -> extract(java.util.Collections.singletonList(a)))
                .setNeutralButton("Choisir plusieurs", (d, w) -> toggle(a))
                .setNegativeButton("Fermer", null)
                .show();
    }

    private void toggle(ApkStore.App a) {
        if (!selected.remove(a.pkg)) selected.add(a.pkg);
        adapter.notifyDataSetChanged();
        buildActions();
    }

    private void clearSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        buildActions();
    }

    private void selectAllShown() {
        for (ApkStore.App a : shown) selected.add(a.pkg);
        adapter.notifyDataSetChanged();
        buildActions();
    }

    private List<ApkStore.App> picked() {
        List<ApkStore.App> l = new ArrayList<>();
        for (ApkStore.App a : all) if (selected.contains(a.pkg)) l.add(a);
        return l;
    }

    private void extract(List<ApkStore.App> apps) {
        if (apps.isEmpty()) return;
        if (device != null && !ShareHub.get(act).online(device)) {
            toast(device.name + " ne répond pas : l'appli Nexio doit tourner dessus.");
        }
        ShareClient.extract(act, device, apps);
        clearSelection();
        toast(apps.size() == 1 ? "Extraction de " + apps.get(0).label + "…" : "Extraction de " + apps.size() + " applis…");
        host.showTransfers();
    }

    // ---- Action bar ----

    private void buildActions() {
        actions.removeAllViews();
        int n = selected.size();
        if (n == 0) {
            if (!all.isEmpty()) actions.addView(label(shown.size() + " appli" + (shown.size() > 1 ? "s" : "")
                    + "  ·  touche pour extraire, garde le doigt pour en choisir plusieurs"));
            if (!loading) actions.addView(button("Actualiser", R.color.text, v -> load()));
            return;
        }
        long size = 0;
        for (ApkStore.App a : picked()) size += a.size;
        actions.addView(label(n + " choisie" + (n > 1 ? "s" : "") + "  ·  " + Ui.size(size)));
        actions.addView(button("Extraire", R.color.accent, v -> extract(picked())));
        actions.addView(button("Tout", R.color.text, v -> selectAllShown()));
        actions.addView(button("✕", R.color.muted, v -> clearSelection()));
    }

    private TextView button(String text, int color, View.OnClickListener click) {
        TextView b = Ui.text(act, text, 14, color);
        b.setBackgroundResource(R.drawable.chip);
        b.setPadding(Ui.dp(act, 14), Ui.dp(act, 9), Ui.dp(act, 14), Ui.dp(act, 9));
        b.setOnClickListener(click);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(act, 6);
        b.setLayoutParams(lp);
        return b;
    }

    private TextView label(String text) {
        TextView t = Ui.text(act, text, 13, R.color.muted);
        t.setPadding(Ui.dp(act, 4), 0, Ui.dp(act, 10), 0);
        return t;
    }

    private View chip(String label, int icon, boolean active, View.OnClickListener click) {
        LinearLayout chip = Ui.row(act);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.getColor(active ? R.color.accent : R.color.surface_pressed));
        bg.setCornerRadius(Ui.dp(act, 14));
        chip.setBackground(bg);
        chip.setPadding(Ui.dp(act, 10), Ui.dp(act, 6), Ui.dp(act, 12), Ui.dp(act, 6));
        ImageView i = new ImageView(act);
        i.setImageResource(icon);
        i.setColorFilter(act.getColor(active ? R.color.on_accent : R.color.text));
        chip.addView(i, new LayoutParams(Ui.dp(act, 16), Ui.dp(act, 16)));
        TextView t = Ui.text(act, label, 13, active ? R.color.on_accent : R.color.text);
        t.setPadding(Ui.dp(act, 6), 0, 0, 0);
        chip.addView(t);
        chip.setOnClickListener(click);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(act, 6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }

    // ---- Icons: this phone's from PackageManager, a paired device's over the network ----

    private Drawable icon(ApkStore.App a) {
        Drawable d = iconCache.get(a.pkg);
        if (d != null) return d;
        if (device == null) {
            try {
                d = act.getPackageManager().getApplicationIcon(a.pkg);
                iconCache.put(a.pkg, d);
                return d;
            } catch (PackageManager.NameNotFoundException e) {
                return null;
            }
        }
        if (iconLoading.add(a.pkg)) {
            Share.Peer from = device;
            int gen = generation;
            icons.execute(() -> {
                try {
                    byte[] png = ShareClient.icon(act, from, a.pkg);
                    Bitmap b = BitmapFactory.decodeByteArray(png, 0, png.length);
                    if (b == null) return;
                    main.post(() -> {
                        iconLoading.remove(a.pkg);
                        if (gen != generation) return;
                        iconCache.put(a.pkg, new BitmapDrawable(getResources(), b));
                        adapter.notifyDataSetChanged();
                    });
                } catch (Exception ignored) {
                    main.post(() -> iconLoading.remove(a.pkg));
                }
            });
        }
        return null;
    }

    private final class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Row r = convert instanceof Row ? (Row) convert : new Row();
            r.bind(shown.get(pos));
            return r;
        }
    }

    private final class Row extends LinearLayout {
        final ImageView iconView = new ImageView(act);
        final TextView name, details, check;

        Row() {
            super(act);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(Ui.dp(act, 10), Ui.dp(act, 8), Ui.dp(act, 10), Ui.dp(act, 8));
            addView(iconView, new LayoutParams(Ui.dp(act, 42), Ui.dp(act, 42)));
            LinearLayout texts = Ui.column(act);
            texts.setPadding(Ui.dp(act, 12), 0, Ui.dp(act, 8), 0);
            name = Ui.text(act, "", 15, R.color.text);
            name.setSingleLine(true);
            details = Ui.text(act, "", 12, R.color.muted);
            details.setSingleLine(true);
            texts.addView(name);
            texts.addView(details);
            addView(texts, Ui.weight1());
            check = Ui.text(act, "✓", 18, R.color.accent);
            addView(check);
        }

        void bind(ApkStore.App a) {
            Drawable d = icon(a);
            if (d != null) {
                iconView.setImageDrawable(d);
            } else {
                iconView.setImageResource(R.drawable.ic_apk);
                iconView.setColorFilter(act.getColor(R.color.muted));
            }
            if (d != null) iconView.clearColorFilter();
            name.setText(a.label);
            details.setText(a.pkg + "  ·  " + (a.version.isEmpty() ? "" : a.version + "  ·  ") + Ui.size(a.size)
                    + (a.parts > 1 ? "  ·  " + a.parts + " morceaux" : ""));
            boolean on = selected.contains(a.pkg);
            check.setVisibility(on ? VISIBLE : INVISIBLE);
            setBackgroundResource(on ? R.drawable.chip : 0);
        }
    }
}
