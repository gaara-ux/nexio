package com.gaara.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/** Réglages: everyone likes their remote a bit differently. */
public class SettingsActivity extends Activity {
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Prefs.applyTheme(this, base);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        build();
    }

    private void build() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this);
        int p = Ui.dp(this, 20);
        root.setPadding(p, Ui.dp(this, 16), p, p);
        scroll.addView(root);
        Ui.fitScreen(this, root, 640);

        LinearLayout top = Ui.row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        ImageButton back = new ImageButton(this, null, 0, 0);
        back.setImageResource(R.drawable.ic_back);
        back.setBackgroundResource(R.drawable.round_button);
        back.setColorFilter(getColor(R.color.text));
        back.setContentDescription("Retour");
        back.setOnClickListener(v -> finish());
        top.addView(back, new LinearLayout.LayoutParams(Ui.dp(this, 48), Ui.dp(this, 48)));
        TextView title = Ui.text(this, "Réglages", 22, R.color.text);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(Ui.dp(this, 14), 0, 0, 0);
        top.addView(title);
        root.addView(top);

        section(root, "Apparence");
        choice(root, "Thème", "theme", Prefs.THEME_DARK,
                new String[]{Prefs.THEME_DARK, Prefs.THEME_LIGHT, Prefs.THEME_SYSTEM},
                new String[]{"Sombre", "Clair", "Comme le téléphone"}, true);
        choice(root, "Taille des boutons", "button_size", Prefs.SIZE_AUTO,
                new String[]{Prefs.SIZE_AUTO, Prefs.SIZE_SMALL, Prefs.SIZE_LARGE},
                new String[]{"Automatique (selon l'écran)", "Petits", "Grands"}, false);
        toggle(root, "Toujours en portrait", "Sinon l'appli suit la rotation du téléphone.", "portrait_only", false);

        section(root, "Télécommande");
        toggle(root, "Boutons de lecture", "Reculer, lecture/pause, avancer.", "show_media", true);
        toggle(root, "Bouton Clavier", "Pour écrire sur la TV depuis le téléphone.", "show_keyboard", true);
        toggle(root, "Ouvrir le clavier tout seul", "Quand la TV demande du texte (recherche, mot de passe).", "auto_keyboard", true);
        toggle(root, "Vibrer au toucher", null, "haptics", true);
        toggle(root, "Boutons de volume du téléphone", "Ils règlent le volume de la TV quand la télécommande est ouverte.", "volume_keys", true);
        toggle(root, "Garder l'écran allumé", "Tant que la télécommande est ouverte.", "keep_screen_on", false);

        section(root, "Applis favorites");
        TextView favHelp = Ui.text(this, "Dans « Ouvrir une appli » (bouton grille en haut), garde le doigt sur une appli pour l'ajouter. "
                + "Les favorites apparaissent au-dessus de la télécommande.", 14, R.color.muted);
        root.addView(favHelp);
        TextView clear = Ui.text(this, "Effacer les favoris", 15, R.color.danger);
        clear.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 6));
        clear.setOnClickListener(v -> {
            Prefs.put(this, "favourites", "");
            Toast.makeText(this, "Favoris effacés", Toast.LENGTH_SHORT).show();
        });
        root.addView(clear);

        section(root, "À propos");
        String version = "";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        root.addView(Ui.text(this, "Nexio " + version + "\nSans pub, sans compte, rien ne quitte ton réseau.", 14, R.color.muted));

        setContentView(scroll);
    }

    private void section(LinearLayout root, String name) {
        TextView t = Ui.header(this, name);
        root.addView(t);
    }

    private LinearLayout card(LinearLayout root) {
        LinearLayout c = Ui.row(this);
        c.setGravity(Gravity.CENTER_VERTICAL);
        c.setBackgroundResource(R.drawable.card);
        c.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), Ui.dp(this, 12), Ui.dp(this, 12));
        c.setMinimumHeight(Ui.dp(this, 56));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dp(this, 8);
        root.addView(c, lp);
        return c;
    }

    private LinearLayout texts(LinearLayout card, String title, String sub) {
        LinearLayout col = Ui.column(this);
        col.addView(Ui.text(this, title, 16, R.color.text));
        if (sub != null) {
            TextView s = Ui.text(this, sub, 13, R.color.muted);
            s.setPadding(0, Ui.dp(this, 2), 0, 0);
            col.addView(s);
        }
        card.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return col;
    }

    private void toggle(LinearLayout root, String title, String sub, String key, boolean def) {
        LinearLayout c = card(root);
        texts(c, title, sub);
        Switch s = new Switch(this);
        s.setChecked(Prefs.on(this, key, def));
        s.setOnCheckedChangeListener((v, on) -> {
            Prefs.put(this, key, on);
            if (key.equals("portrait_only")) Ui.applyOrientation(this);
        });
        c.addView(s);
        c.setOnClickListener(v -> s.toggle());
    }

    private void choice(LinearLayout root, String title, String key, String def, String[] values, String[] labels, boolean recreate) {
        LinearLayout c = card(root);
        String now = Prefs.get(this, key, def);
        int index = java.util.Arrays.asList(values).indexOf(now);
        LinearLayout col = texts(c, title, labels[Math.max(0, index)]);
        ((TextView) col.getChildAt(1)).setTextColor(getColor(R.color.accent));
        c.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(title)
                .setSingleChoiceItems(labels, Math.max(0, index), (d, which) -> {
                    Prefs.put(this, key, values[which]);
                    d.dismiss();
                    if (recreate) recreate(); else build();
                })
                .setNegativeButton("Annuler", null)
                .show());
    }
}
