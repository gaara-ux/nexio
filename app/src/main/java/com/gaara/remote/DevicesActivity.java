package com.gaara.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.LinkedHashMap;
import java.util.Map;

/** Start screen: paired TVs, TVs found on the Wi-Fi, and pairing. */
public class DevicesActivity extends Activity implements Discovery.Listener {
    static final String EXTRA_NO_AUTO = "no_auto";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, String> found = new LinkedHashMap<>(); // host -> name
    private LinearLayout pairedList, foundList;
    private TextView foundEmpty;
    private Discovery discovery;

    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        Prefs.applyTheme(this, base);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Identity.migrate(this);
        String last = Devices.last(this);
        if (!getIntent().getBooleanExtra(EXTRA_NO_AUTO, false) && last != null && Devices.find(this, last) != null) {
            openRemote(last);
            return;
        }
        buildUi();
        discovery = new Discovery(this, this);
        // Create the phone's certificate in the background, so pairing starts fast.
        new Thread(() -> {
            try {
                Identity.get(this);
            } catch (Exception ignored) {
            }
        }).start();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (discovery == null) return;
        refreshPaired();
        discovery.start();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (discovery != null) discovery.stop();
    }

    // ---- UI ----

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, dp(28), p, p);
        Ui.fitScreen(this, root, 640);
        scroll.addView(root);

        TextView title = text(getString(R.string.app_name), 28, R.color.text);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);

        root.addView(header(getString(R.string.my_tvs)));
        pairedList = new LinearLayout(this);
        pairedList.setOrientation(LinearLayout.VERTICAL);
        root.addView(pairedList);

        root.addView(header(getString(R.string.found_tvs)));
        foundList = new LinearLayout(this);
        foundList.setOrientation(LinearLayout.VERTICAL);
        root.addView(foundList);
        foundEmpty = text(getString(R.string.searching), 14, R.color.muted);
        foundList.addView(foundEmpty);

        TextView add = text(getString(R.string.add_by_ip), 15, R.color.accent);
        add.setPadding(0, dp(24), 0, dp(8));
        add.setOnClickListener(v -> askIp());
        root.addView(add);

        setContentView(scroll);
    }

    private void refreshPaired() {
        pairedList.removeAllViews();
        java.util.List<Devices.Device> list = Devices.all(this);
        if (list.isEmpty()) {
            pairedList.addView(text(getString(R.string.no_paired), 14, R.color.muted));
        }
        for (Devices.Device d : list) {
            View row = row(d.name, d.host, true);
            row.setOnClickListener(v -> openRemote(d.fingerprint));
            row.setOnLongClickListener(v -> {
                confirmForget(d);
                return true;
            });
            pairedList.addView(row);
        }
        refreshFound();
    }

    private void refreshFound() {
        foundList.removeAllViews();
        int shown = 0;
        for (Map.Entry<String, String> e : found.entrySet()) {
            if (isPairedHost(e.getKey())) continue;
            String host = e.getKey(), name = e.getValue();
            View row = row(name, host + " — toucher pour appairer", false);
            row.setOnClickListener(v -> pair(host, name));
            foundList.addView(row);
            shown++;
        }
        if (shown == 0) foundList.addView(foundEmpty);
    }

    private boolean isPairedHost(String host) {
        for (Devices.Device d : Devices.all(this)) if (d.host.equals(host)) return true;
        return false;
    }

    @Override
    public void onFound(String name, String host) {
        found.put(host, name);
        refreshFound();
    }

    // ---- Pairing ----

    private void askIp() {
        EditText ip = new EditText(this);
        ip.setHint("192.168.1.50");
        ip.setInputType(InputType.TYPE_CLASS_PHONE);
        new AlertDialog.Builder(this)
                .setTitle(R.string.add_by_ip)
                .setMessage("Adresse IP de la TV (Paramètres > Réseau sur la TV).")
                .setView(padded(ip))
                .setPositiveButton("Appairer", (d, w) -> {
                    String host = ip.getText().toString().trim();
                    if (!host.isEmpty()) pair(host, "TV " + host);
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void pair(String host, String name) {
        AlertDialog wait = new AlertDialog.Builder(this)
                .setTitle("Connexion à " + name)
                .setMessage("Un code va s'afficher sur la TV…")
                .setCancelable(false)
                .show();
        new Thread(() -> {
            PairingClient pc = null;
            try {
                pc = new PairingClient(Identity.get(this), host, "Nexio (" + Build.MODEL + ")");
                pc.start();
                PairingClient ready = pc;
                main.post(() -> {
                    wait.dismiss();
                    askCode(ready, host, name, null);
                });
            } catch (Exception e) {
                if (pc != null) pc.close();
                main.post(() -> {
                    wait.dismiss();
                    error("Appairage impossible", e);
                });
            }
        }).start();
    }

    private void askCode(PairingClient pc, String host, String name, String previousError) {
        EditText code = new EditText(this);
        code.setHint("ex. 7117E5");
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        code.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(6)});
        code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
        code.setGravity(Gravity.CENTER);
        String msg = "Entre le code à 6 caractères affiché sur la TV.";
        if (previousError != null) msg = previousError + "\n\n" + msg;
        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Code de la TV")
                .setMessage(msg)
                .setView(padded(code))
                .setCancelable(false)
                .setPositiveButton("Valider", null)
                .setNegativeButton("Annuler", (d, w) -> new Thread(pc::close).start())
                .create();
        dlg.setOnShowListener(d -> dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            String c = code.getText().toString();
            dlg.dismiss();
            new Thread(() -> {
                try {
                    String fp = pc.finish(c);
                    pc.close();
                    Devices.save(this, new Devices.Device(name, host, fp));
                    main.post(() -> {
                        Toast.makeText(this, "TV appairée ✓", Toast.LENGTH_SHORT).show();
                        openRemote(fp);
                    });
                } catch (Exception e) {
                    String m = e.getMessage();
                    // A wrong code that fails our local check keeps the session open: ask again.
                    if (m != null && m.startsWith("Code incorrect")) {
                        main.post(() -> askCode(pc, host, name, m));
                    } else {
                        pc.close();
                        main.post(() -> error("Appairage refusé", e));
                    }
                }
            }).start();
        }));
        dlg.show();
    }

    private void confirmForget(Devices.Device d) {
        new AlertDialog.Builder(this)
                .setTitle("Oublier " + d.name + " ?")
                .setMessage("Il faudra refaire l'appairage avec un code pour la contrôler.")
                .setPositiveButton("Oublier", (x, w) -> {
                    Devices.remove(this, d.fingerprint);
                    refreshPaired();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void openRemote(String fp) {
        startActivity(new Intent(this, RemoteActivity.class).putExtra(RemoteActivity.EXTRA_FP, fp));
        finish();
    }

    private void error(String title, Exception e) {
        String m = e.getMessage();
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(m == null ? e.getClass().getSimpleName() : m)
                .setPositiveButton("OK", null)
                .show();
    }

    // ---- Small view helpers ----

    private View row(String title, String subtitle, boolean paired) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.card);
        row.setPadding(dp(16), dp(14), dp(16), dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        row.setLayoutParams(lp);

        ImageView icon = new ImageView(this);
        icon.setImageResource(R.drawable.ic_tv);
        icon.setColorFilter(getColor(paired ? R.color.accent : R.color.muted));
        row.addView(icon, new LinearLayout.LayoutParams(dp(28), dp(28)));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setPadding(dp(14), 0, 0, 0);
        texts.addView(text(title, 16, R.color.text));
        texts.addView(text(subtitle, 13, R.color.muted));
        row.addView(texts);
        return row;
    }

    private TextView header(String s) {
        TextView t = text(s.toUpperCase(), 12, R.color.muted);
        t.setLetterSpacing(0.08f);
        t.setPadding(0, dp(26), 0, dp(2));
        return t;
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(getColor(color));
        return t;
    }

    private View padded(View v) {
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return box;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
