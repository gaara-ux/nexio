package com.gaara.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import com.gaara.remote.share.Share;
import com.gaara.remote.share.ShareClient;
import com.gaara.remote.share.ShareHub;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** One side of the two-pane file manager: a device, a folder, its files, a selection. */
final class FilePane extends LinearLayout {
    interface Host {
        void onPaneTouched(FilePane pane);
        void onSelectionChanged(FilePane pane);
        void addDevice();
        void pairWith(ShareHub.Seen seen);
        void forget(Share.Peer peer);
        void installApk(FilePane pane, ShareClient.Entry e);
    }

    private static final String[][] QUICK = {
            {"Racine", ""}, {"Téléchargements", "Download"}, {"Appareil photo", "DCIM"}, {"Images", "Pictures"},
            {"Films", "Movies"}, {"Musique", "Music"}, {"Documents", "Documents"}};

    private final Activity act;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout deviceRow;
    private final TextView pathTitle, pathSub, emptyText;
    private final ImageView upButton;
    private final ListView list;
    private final Adapter adapter = new Adapter();
    private final GradientDrawable frame = new GradientDrawable();
    final Set<String> selected = new LinkedHashSet<>();
    Share.Peer device;   // null = this phone
    String dir = "";
    List<ShareClient.Entry> items = new ArrayList<>();
    private int loadId;

    FilePane(Activity act, Host host, Share.Peer startDevice) {
        super(act);
        this.act = act;
        this.host = host;
        this.device = startDevice;
        setOrientation(VERTICAL);
        int p = Ui.dp(act, 8);
        setPadding(p, p / 2, p, p / 2);
        frame.setCornerRadius(Ui.dp(act, 16));
        setBackground(frame);

        HorizontalScrollView devScroll = new HorizontalScrollView(act);
        devScroll.setHorizontalScrollBarEnabled(false);
        deviceRow = Ui.row(act);
        deviceRow.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 2));
        devScroll.addView(deviceRow);
        addView(devScroll);

        LinearLayout pathRow = Ui.row(act);
        pathRow.setPadding(0, Ui.dp(act, 4), 0, Ui.dp(act, 4));
        upButton = new ImageView(act);
        upButton.setImageResource(R.drawable.ic_devices);
        upButton.setColorFilter(act.getColor(R.color.text));
        upButton.setBackgroundResource(R.drawable.round_button);
        int bp = Ui.dp(act, 8);
        upButton.setPadding(bp, bp, bp, bp);
        upButton.setContentDescription("Dossier parent");
        upButton.setOnClickListener(v -> {
            host.onPaneTouched(this);
            up();
        });
        pathRow.addView(upButton, new LayoutParams(Ui.dp(act, 36), Ui.dp(act, 36)));
        LinearLayout pathTexts = Ui.column(act);
        pathTexts.setPadding(Ui.dp(act, 10), 0, 0, 0);
        pathTitle = Ui.text(act, "", 15, R.color.text);
        pathTitle.setSingleLine(true);
        pathSub = Ui.text(act, "", 11, R.color.muted);
        pathSub.setSingleLine(true);
        pathTexts.addView(pathTitle);
        pathTexts.addView(pathSub);
        pathTexts.setOnClickListener(v -> {
            host.onPaneTouched(this);
            quickFolders();
        });
        pathRow.addView(pathTexts, Ui.weight1());
        TextView quick = Ui.text(act, "Raccourcis ▾", 12, R.color.accent);
        quick.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), Ui.dp(act, 4), Ui.dp(act, 8));
        quick.setOnClickListener(v -> {
            host.onPaneTouched(this);
            quickFolders();
        });
        pathRow.addView(quick);
        addView(pathRow);

        emptyText = Ui.text(act, "", 13, R.color.muted);
        emptyText.setPadding(Ui.dp(act, 4), Ui.dp(act, 8), 0, Ui.dp(act, 8));
        emptyText.setOnClickListener(v -> {
            if (device == null && !hasAccess()) askAccess();
        });
        addView(emptyText);
        list = new ListView(act);
        list.setDivider(null);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, pos, id) -> {
            host.onPaneTouched(this);
            tap(items.get(pos));
        });
        list.setOnItemLongClickListener((parent, view, pos, id) -> {
            host.onPaneTouched(this);
            toggle(items.get(pos));
            return true;
        });
        addView(list, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));

        setOnClickListener(v -> host.onPaneTouched(this));
        buildDevices();
        open("");
    }

    void setActive(boolean active) {
        frame.setColor(act.getColor(active ? R.color.surface : R.color.bg));
        frame.setStroke(Ui.dp(act, active ? 2 : 1), act.getColor(active ? R.color.accent : R.color.surface));
    }

    String deviceName() {
        return device == null ? "Ce téléphone" : device.name;
    }

    String place() {
        return deviceName() + (dir.isEmpty() ? "" : " › " + dir.replace("/", " › "));
    }

    List<ShareClient.Entry> picked() {
        List<ShareClient.Entry> out = new ArrayList<>();
        for (ShareClient.Entry e : items) if (selected.contains(e.name)) out.add(e);
        return out;
    }

    void clearSelection() {
        selected.clear();
        adapter.notifyDataSetChanged();
        host.onSelectionChanged(this);
    }

    // ---- Devices ----

    void buildDevices() {
        deviceRow.removeAllViews();
        ShareHub hub = ShareHub.get(act);
        deviceRow.addView(chip("Ce téléphone", R.drawable.ic_phone, device == null, v -> switchTo(null), null));
        for (Share.Peer peer : Share.peers(act)) {
            deviceRow.addView(chip((hub.online(peer) ? "● " : "○ ") + peer.name, peer.tv ? R.drawable.ic_tv : R.drawable.ic_pc,
                    device != null && device.uuid.equals(peer.uuid), v -> switchTo(peer), v -> {
                        host.forget(peer);
                        return true;
                    }));
        }
        // Found automatically on the Wi-Fi, not paired yet: one tap to pair.
        for (ShareHub.Seen s : hub.seen()) {
            if (Share.peer(act, s.uuid) != null) continue;
            View c = chip("+ " + s.name, s.tv ? R.drawable.ic_tv : R.drawable.ic_pc, false, v -> host.pairWith(s), null);
            c.setAlpha(0.75f);
            deviceRow.addView(c);
        }
        deviceRow.addView(chip("Par IP…", 0, false, v -> host.addDevice(), null));
    }

    void switchTo(Share.Peer p) {
        host.onPaneTouched(this);
        device = p;
        selected.clear();
        host.onSelectionChanged(this);
        buildDevices();
        open("");
    }

    private View chip(String label, int icon, boolean active, OnClickListener click, OnLongClickListener longClick) {
        LinearLayout chip = Ui.row(act);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(act.getColor(active ? R.color.accent : R.color.surface_pressed));
        bg.setCornerRadius(Ui.dp(act, 14));
        chip.setBackground(bg);
        chip.setPadding(Ui.dp(act, 10), Ui.dp(act, 6), Ui.dp(act, 12), Ui.dp(act, 6));
        if (icon != 0) {
            ImageView i = new ImageView(act);
            i.setImageResource(icon);
            i.setColorFilter(act.getColor(active ? R.color.on_accent : R.color.accent));
            chip.addView(i, new LayoutParams(Ui.dp(act, 16), Ui.dp(act, 16)));
        }
        TextView t = Ui.text(act, label, 13, active ? R.color.on_accent : R.color.text);
        t.setPadding(icon != 0 ? Ui.dp(act, 6) : 0, 0, 0, 0);
        chip.addView(t);
        chip.setOnClickListener(click);
        if (longClick != null) chip.setOnLongClickListener(longClick);
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.rightMargin = Ui.dp(act, 6);
        chip.setLayoutParams(lp);
        return chip;
    }

    private void quickFolders() {
        String[] labels = new String[QUICK.length];
        for (int i = 0; i < QUICK.length; i++) labels[i] = QUICK[i][0];
        new AlertDialog.Builder(act)
                .setTitle(deviceName())
                .setItems(labels, (d, w) -> open(QUICK[w][1]))
                .show();
    }

    // ---- Phone access ----

    static boolean hasAccess() {
        return Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager();
    }

    private void askAccess() {
        try {
            act.startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:" + act.getPackageName())));
        } catch (Exception e) {
            act.startActivity(new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
        }
    }

    // ---- Browsing ----

    void reload() {
        open(dir);
    }

    void open(String path) {
        dir = path == null ? "" : path;
        int id = ++loadId;
        Share.Peer dev = device;
        selected.clear();
        host.onSelectionChanged(this);
        emptyText.setText("Chargement…");
        emptyText.setVisibility(VISIBLE);
        items = new ArrayList<>();
        refresh();
        new Thread(() -> {
            try {
                List<ShareClient.Entry> got = ShareClient.list(act, dev, dir);
                main.post(() -> {
                    if (id != loadId) return;
                    items = got;
                    if (dev == null && !hasAccess()) {
                        emptyText.setText("⚠ Les fichiers du téléphone sont cachés : touche ici pour autoriser l'accès à tous les fichiers.");
                        emptyText.setTextColor(act.getColor(R.color.warn));
                        emptyText.setVisibility(VISIBLE);
                    } else {
                        emptyText.setTextColor(act.getColor(R.color.muted));
                        emptyText.setText(got.isEmpty() ? "Dossier vide." : "");
                        emptyText.setVisibility(got.isEmpty() ? VISIBLE : GONE);
                    }
                    refresh();
                    list.setSelection(0);
                });
            } catch (Exception e) {
                main.post(() -> {
                    if (id != loadId) return;
                    emptyText.setTextColor(act.getColor(R.color.danger));
                    emptyText.setText(dev == null ? "Impossible de lire ce dossier." : ShareClient.message(e));
                    emptyText.setVisibility(VISIBLE);
                    refresh();
                });
            }
        }).start();
    }

    void up() {
        if (dir.isEmpty()) return;
        int i = dir.lastIndexOf('/');
        open(i < 0 ? "" : dir.substring(0, i));
    }

    boolean back() {
        if (!selected.isEmpty()) {
            clearSelection();
            return true;
        }
        if (dir.isEmpty()) return false;
        up();
        return true;
    }

    private void tap(ShareClient.Entry e) {
        if (!selected.isEmpty()) toggle(e);
        else if (e.dir) open(ShareClient.join(dir, e.name));
        else if (com.gaara.remote.share.Installer.isPackage(e.name)) host.installApk(this, e);
        else toggle(e);
    }

    void toggleFromHost(ShareClient.Entry e) {
        toggle(e);
    }

    private void toggle(ShareClient.Entry e) {
        if (!selected.remove(e.name)) selected.add(e.name);
        adapter.notifyDataSetChanged();
        host.onSelectionChanged(this);
    }

    private void refresh() {
        pathTitle.setText(dir.isEmpty() ? deviceName() : dir.substring(dir.lastIndexOf('/') + 1));
        int n = items.size();
        pathSub.setText(place() + "  ·  " + n + " élément" + (n > 1 ? "s" : ""));
        upButton.setAlpha(dir.isEmpty() ? 0.3f : 1f);
        adapter.notifyDataSetChanged();
    }

    // ---- Rows ----

    static int iconFor(ShareClient.Entry e) {
        if (e.dir) return R.drawable.ic_folder;
        String n = e.name.toLowerCase(Locale.ROOT);
        if (n.matches(".*\\.(jpe?g|png|gif|webp|heic|heif|bmp|svg|tiff?)$")) return R.drawable.ic_image;
        if (n.matches(".*\\.(mp4|mkv|avi|mov|webm|m4v|ts|wmv|flv|3gp|mpe?g)$")) return R.drawable.ic_video;
        if (n.matches(".*\\.(mp3|flac|wav|ogg|m4a|aac|opus|wma|amr)$")) return R.drawable.ic_audio;
        if (n.matches(".*\\.(apk|apks|xapk|apkm)$")) return R.drawable.ic_apk;
        if (n.matches(".*\\.(pdf|txt|docx?|xlsx?|pptx?|odt|ods|csv|md|rtf|json|xml|html?)$")) return R.drawable.ic_doc;
        return R.drawable.ic_file;
    }

    static int colorFor(int icon) {
        if (icon == R.drawable.ic_folder) return 0xFF4C8DFF;
        if (icon == R.drawable.ic_image) return 0xFF3FC37A;
        if (icon == R.drawable.ic_video) return 0xFFF0605A;
        if (icon == R.drawable.ic_audio) return 0xFFB06AF0;
        if (icon == R.drawable.ic_apk) return 0xFF7CB342;
        if (icon == R.drawable.ic_doc) return 0xFFF0B24A;
        return 0xFF6B7280;
    }

    private final class Adapter extends BaseAdapter {
        private final DateFormat date = DateFormat.getDateInstance(DateFormat.SHORT, Locale.FRENCH);

        @Override public int getCount() { return items.size(); }
        @Override public Object getItem(int i) { return items.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            ShareClient.Entry e = items.get(pos);
            LinearLayout row = Ui.row(act);
            row.setPadding(Ui.dp(act, 2), Ui.dp(act, 6), Ui.dp(act, 2), Ui.dp(act, 6));
            boolean picked = selected.contains(e.name);
            int icon = picked ? R.drawable.ic_check : iconFor(e);
            row.addView(Ui.badge(act, icon, picked ? act.getColor(R.color.accent) : colorFor(icon), 34));
            LinearLayout texts = Ui.column(act);
            texts.setPadding(Ui.dp(act, 12), 0, 0, 0);
            TextView name = Ui.text(act, e.name, 14, R.color.text);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            if (e.name.startsWith(".")) name.setAlpha(0.6f); // hidden files: shown, but dimmed
            texts.addView(name);
            String sub = e.dir ? e.count + " élément" + (e.count > 1 ? "s" : "")
                    : Ui.size(e.size) + "  ·  " + date.format(new Date(e.mtime));
            texts.addView(Ui.text(act, sub, 11, R.color.muted));
            row.addView(texts, Ui.weight1());
            return row;
        }
    }
}
