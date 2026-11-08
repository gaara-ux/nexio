package com.gaara.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.gaara.remote.share.Share;
import com.gaara.remote.share.ShareClient;
import com.gaara.remote.share.ShareHub;

import java.util.ArrayList;
import java.util.List;

/**
 * Two-pane file manager: each pane shows a device (this phone, the TV, the PC...) and a folder.
 * Choose files in one pane, then copy or move them into the folder shown in the other pane.
 */
final class FilesTab extends LinearLayout implements FilePane.Host, ShareHub.Listener {
    interface Host {
        void showTransfers();
    }

    private final Activity act;
    private final Host host;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final FilePane top, bottom;
    private final LinearLayout actions;
    private final HorizontalScrollView barScroll;
    private final LinearLayout panesRow;
    private FilePane active;
    private Boolean wide;

    FilesTab(Activity act, Host host) {
        super(act);
        this.act = act;
        this.host = host;
        setOrientation(VERTICAL);

        List<Share.Peer> peers = Share.peers(act);
        top = new FilePane(act, this, null);
        bottom = new FilePane(act, this, peers.isEmpty() ? null : peers.get(0));

        barScroll = new HorizontalScrollView(act);
        barScroll.setHorizontalScrollBarEnabled(false);
        actions = Ui.row(act);
        actions.setPadding(0, Ui.dp(act, 6), 0, Ui.dp(act, 6));
        barScroll.addView(actions);
        panesRow = Ui.row(act);
        panesRow.setGravity(Gravity.NO_GRAVITY);

        setWide(false);
        setActive(top);
    }

    /** Wide screen (tablet, landscape): the two panes side by side, actions underneath. */
    void setWide(boolean on) {
        if (wide != null && wide == on) return;
        wide = on;
        removeAllViews();
        panesRow.removeAllViews();
        if (on) {
            LayoutParams l = new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f);
            l.rightMargin = Ui.dp(act, 6);
            panesRow.addView(top, l);
            panesRow.addView(bottom, new LayoutParams(0, LayoutParams.MATCH_PARENT, 1f));
            LayoutParams rl = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
            rl.topMargin = Ui.dp(act, 6);
            addView(panesRow, rl);
            addView(barScroll);
        } else {
            LayoutParams paneLp = new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f);
            paneLp.topMargin = Ui.dp(act, 6);
            addView(top, paneLp);
            addView(barScroll);
            addView(bottom, new LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f));
        }
        if (active != null) buildActions();
    }

    void attach() {
        ShareHub.get(act).addListener(this);
    }

    void detach() {
        ShareHub.get(act).removeListener(this);
    }

    void onResume() {
        top.reload();
        bottom.reload();
    }

    boolean back() {
        return active.back();
    }

    @Override
    public void onShareChanged() {
        top.buildDevices();
        bottom.buildDevices();
    }

    private FilePane other(FilePane p) {
        return p == top ? bottom : top;
    }

    private void setActive(FilePane p) {
        active = p;
        top.setActive(p == top);
        bottom.setActive(p == bottom);
        buildActions();
    }

    // ---- FilePane.Host ----

    @Override
    public void onPaneTouched(FilePane pane) {
        if (pane != active) {
            other(pane).clearSelection();
            setActive(pane);
        }
    }

    @Override
    public void onSelectionChanged(FilePane pane) {
        if (pane == active) buildActions();
    }

    // ---- Action bar ----

    private void buildActions() {
        actions.removeAllViews();
        FilePane src = active, dst = other(active);
        int n = src.selected.size();
        if (n == 0) {
            actions.addView(button("Nouveau dossier", R.color.text, v -> newFolder()));
            actions.addView(button("Actualiser", R.color.text, v -> {
                top.reload();
                bottom.reload();
            }));
            actions.addView(label("Garde le doigt sur un fichier pour le choisir"));
            return;
        }
        String arrow = wide != null && wide ? (src == top ? "→" : "←") : (src == top ? "↓" : "↑");
        actions.addView(label(n + " choisi" + (n > 1 ? "s" : "")));
        actions.addView(button("Copier " + arrow, R.color.accent, v -> transfer(false)));
        actions.addView(button("Déplacer " + arrow, R.color.accent, v -> transfer(true)));
        if (n == 1) actions.addView(button("Renommer", R.color.text, v -> rename()));
        actions.addView(button("Supprimer", R.color.danger, v -> delete()));
        actions.addView(button("✕", R.color.muted, v -> src.clearSelection()));
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
        t.setPadding(Ui.dp(act, 6), 0, Ui.dp(act, 10), 0);
        return t;
    }

    // ---- Actions ----

    private void transfer(boolean move) {
        FilePane src = active, dst = other(active);
        List<ShareClient.Entry> picked = src.picked();
        if (picked.isEmpty()) return;
        if (ShareClient.same(src.device, dst.device) && src.dir.equals(dst.dir)) {
            toast("Les deux panneaux montrent le même dossier : ouvre la destination dans l'autre panneau.");
            return;
        }
        String what = picked.size() == 1 ? "« " + picked.get(0).name + " »" : picked.size() + " éléments";
        new AlertDialog.Builder(act)
                .setTitle(move ? "Déplacer" : "Copier")
                .setMessage(what + "\n\nvers  " + dst.place())
                .setPositiveButton(move ? "Déplacer" : "Copier", (d, w) -> {
                    ShareClient.transfer(act, src.device, src.dir, picked, dst.device, dst.dir, move,
                            () -> main.post(() -> {
                                top.reload();
                                bottom.reload();
                            }));
                    src.clearSelection();
                    host.showTransfers();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void delete() {
        FilePane pane = active;
        List<ShareClient.Entry> picked = pane.picked();
        String what = picked.size() == 1 ? "« " + picked.get(0).name + " »" : picked.size() + " éléments";
        new AlertDialog.Builder(act)
                .setTitle("Supprimer ?")
                .setMessage(what + " sur " + pane.deviceName() + ".\nC'est définitif (pas de corbeille).")
                .setPositiveButton("Supprimer", (d, w) -> background(() -> {
                    for (ShareClient.Entry e : picked) ShareClient.delete(act, pane.device, ShareClient.join(pane.dir, e.name));
                }, pane, "Supprimé"))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void rename() {
        FilePane pane = active;
        List<ShareClient.Entry> picked = pane.picked();
        if (picked.size() != 1) return;
        String old = picked.get(0).name;
        EditText name = new EditText(act);
        name.setText(old);
        int dot = old.lastIndexOf('.');
        name.setSelection(0, dot > 0 && !picked.get(0).dir ? dot : old.length());
        new AlertDialog.Builder(act)
                .setTitle("Renommer")
                .setView(padded(name))
                .setPositiveButton("Renommer", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty() || n.equals(old) || n.contains("/")) return;
                    background(() -> ShareClient.rename(act, pane.device, ShareClient.join(pane.dir, old),
                            ShareClient.join(pane.dir, n)), pane, "Renommé");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void newFolder() {
        FilePane pane = active;
        EditText name = new EditText(act);
        name.setHint("Nouveau dossier");
        new AlertDialog.Builder(act)
                .setTitle("Nouveau dossier dans " + pane.place())
                .setView(padded(name))
                .setPositiveButton("Créer", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty() || n.contains("/")) return;
                    background(() -> ShareClient.mkdir(act, pane.device, ShareClient.join(pane.dir, n)), pane, "Dossier créé");
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private interface Job {
        void run() throws Exception;
    }

    private void background(Job job, FilePane pane, String okText) {
        new Thread(() -> {
            try {
                job.run();
                main.post(() -> {
                    toast(okText);
                    pane.reload();
                    other(pane).reload();
                });
            } catch (Exception e) {
                main.post(() -> {
                    toast(ShareClient.message(e));
                    pane.reload();
                });
            }
        }).start();
    }

    // ---- Pairing ----

    @Override
    public void pairWith(ShareHub.Seen s) {
        askCode(s.name, s.host, s.port);
    }

    @Override
    public void addDevice() {
        List<ShareHub.Seen> candidates = new ArrayList<>();
        for (ShareHub.Seen s : ShareHub.get(act).seen()) {
            if (Share.peer(act, s.uuid) == null) candidates.add(s);
        }
        List<String> labels = new ArrayList<>();
        for (ShareHub.Seen s : candidates) labels.add((s.tv ? "TV : " : "") + s.name + "  (" + s.host + ")");
        labels.add("Par adresse IP…");
        new AlertDialog.Builder(act)
                .setTitle("Appairer un appareil")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which < candidates.size()) {
                        ShareHub.Seen s = candidates.get(which);
                        askCode(s.name, s.host, s.port);
                    } else {
                        askIp();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
        if (candidates.isEmpty()) toast("Aucun nouvel appareil trouvé : l'appli Nexio doit être lancée dessus.");
    }

    private void askIp() {
        EditText ip = new EditText(act);
        ip.setHint("192.168.1.50");
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        new AlertDialog.Builder(act)
                .setTitle("Adresse IP de l'appareil")
                .setView(padded(ip))
                .setPositiveButton("Suivant", (d, w) -> {
                    String h = ip.getText().toString().trim();
                    if (!h.isEmpty()) askCode(h, h, Share.PORT);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void askCode(String name, String hostAddr, int port) {
        EditText code = new EditText(act);
        code.setHint("123456");
        code.setInputType(InputType.TYPE_CLASS_NUMBER);
        code.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        code.setGravity(Gravity.CENTER);
        new AlertDialog.Builder(act)
                .setTitle("Code de " + name)
                .setMessage("Tape le code à 6 chiffres affiché sur l'appareil (sur la TV : ouvre l'appli Nexio).")
                .setView(padded(code))
                .setPositiveButton("Appairer", (d, w) -> {
                    String c = code.getText().toString();
                    FilePane target = active;
                    new Thread(() -> {
                        try {
                            Share.Peer p = ShareClient.pair(act, hostAddr, port, c);
                            main.post(() -> {
                                toast(p.name + " appairé ✓");
                                target.switchTo(p);
                            });
                        } catch (Exception e) {
                            main.post(() -> toast("Appairage refusé : " + ShareClient.message(e)));
                        }
                    }).start();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    @Override
    public void forget(Share.Peer p) {
        new AlertDialog.Builder(act)
                .setTitle("Oublier " + p.name + " ?")
                .setMessage("Il faudra refaire l'appairage avec un code pour voir ses fichiers.")
                .setPositiveButton("Oublier", (d, w) -> {
                    Share.removePeer(act, p.uuid);
                    for (FilePane pane : new FilePane[]{top, bottom}) {
                        if (pane.device != null && pane.device.uuid.equals(p.uuid)) pane.switchTo(null);
                        else pane.buildDevices();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ---- Installing an app from a file ----

    @Override
    public void installApk(FilePane pane, ShareClient.Entry e) {
        String path = ShareClient.join(pane.dir, e.name);
        Share.Peer dev = pane.device;
        List<String> labels = new ArrayList<>();
        labels.add(dev == null ? "Installer sur ce téléphone" : "Installer sur " + dev.name);
        if (dev != null) labels.add("Installer sur ce téléphone");
        labels.add("Choisir (pour copier ou déplacer)");
        // A dialog with a message hides its list: the details go in the title instead.
        LinearLayout head = Ui.column(act);
        head.setPadding(Ui.dp(act, 24), Ui.dp(act, 20), Ui.dp(act, 24), Ui.dp(act, 4));
        TextView title = Ui.text(act, e.name, 19, R.color.text);
        head.addView(title);
        head.addView(Ui.text(act, Ui.size(e.size) + "  ·  sur " + pane.deviceName()
                + (e.name.toLowerCase(java.util.Locale.ROOT).endsWith(".apk") ? "" : "\nPaquet en plusieurs morceaux : Android l'installe d'un coup."),
                13, R.color.muted));
        new AlertDialog.Builder(act)
                .setCustomTitle(head)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    if (which == labels.size() - 1) {
                        pane.toggleFromHost(e);
                    } else if (which == 0 && dev != null) {
                        installThere(dev, path, e.name);
                    } else {
                        installHere(dev, path, e.name);
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** On this phone: the file is read here (or fetched first from the other device). */
    private void installHere(Share.Peer from, String path, String name) {
        if (!com.gaara.remote.share.Installer.allowed(act)) {
            new AlertDialog.Builder(act)
                    .setTitle("Autoriser les installations")
                    .setMessage("Android demande d'autoriser Nexio à installer des applis. Active « Autoriser », reviens, puis touche à nouveau le fichier.")
                    .setPositiveButton("Ouvrir le réglage", (d, w) -> com.gaara.remote.share.Installer.askPermission(act))
                    .setNegativeButton("Annuler", null)
                    .show();
            return;
        }
        toast(from == null ? "Préparation de l'installation…" : "Copie depuis " + from.name + " puis installation…");
        new Thread(() -> {
            java.io.File tmp = null;
            try {
                java.io.File f;
                if (from == null) {
                    f = Share.resolve(path);
                } else {
                    tmp = new java.io.File(act.getCacheDir(), "install-" + System.currentTimeMillis() + "-" + name.replace('/', '_'));
                    ShareClient.download(act, from, path, tmp);
                    f = tmp;
                }
                com.gaara.remote.share.Installer.install(act, f);
            } catch (Exception ex) {
                main.post(() -> toast("Installation impossible : " + ShareClient.message(ex)));
            } finally {
                if (tmp != null) tmp.delete(); // the installer already has its own copy
            }
        }).start();
    }

    /** On the other device: its screen shows Android's confirmation. */
    private void installThere(Share.Peer dev, String path, String name) {
        toast("Installation de " + name + " sur " + dev.name + "…");
        new Thread(() -> {
            try {
                ShareClient.install(act, dev, path);
                main.post(() -> toast("Confirme l'installation sur l'écran de " + dev.name + "."));
            } catch (Exception ex) {
                String m = ShareClient.message(ex);
                if (m.contains("inconnu")) m = dev.name + " ne sait pas encore installer : mets à jour son appli Nexio.";
                String msg = m;
                main.post(() -> toast("Installation impossible : " + msg));
            }
        }).start();
    }

    private View padded(View v) {
        LinearLayout box = new LinearLayout(act);
        box.setPadding(Ui.dp(act, 20), Ui.dp(act, 8), Ui.dp(act, 20), 0);
        box.addView(v, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        return box;
    }

    private void toast(String s) {
        Toast.makeText(act, s, Toast.LENGTH_LONG).show();
    }
}
