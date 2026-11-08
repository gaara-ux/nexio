package com.gaara.remote;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import com.gaara.remote.share.Share;
import com.gaara.remote.share.ShareHub;
import com.gaara.remote.share.Transfers;

import java.util.List;

/** Transfers tab: this phone's pairing code, paired devices, and every copy with its progress. */
final class TransfersTab extends ScrollView implements Transfers.Listener, ShareHub.Listener {
    private final Activity act;
    private final TextView code, peersText;
    private final LinearLayout listBox;

    TransfersTab(Activity act) {
        super(act);
        this.act = act;
        LinearLayout content = Ui.column(act);
        content.setPadding(0, Ui.dp(act, 8), 0, Ui.dp(act, 16));
        addView(content);

        LinearLayout me = card();
        me.addView(Ui.text(act, "Ce téléphone : " + ShareHub.get(act).name(), 15, R.color.text));
        me.addView(Ui.text(act, "Code pour l'appairer depuis la TV ou le PC", 12, R.color.muted));
        code = Ui.text(act, "", 34, R.color.text);
        code.setLetterSpacing(0.2f);
        me.addView(code);
        content.addView(me, cardLp());

        content.addView(Ui.header(act, "Appareils appairés"));
        peersText = Ui.text(act, "", 14, R.color.text);
        content.addView(peersText);

        LinearLayout head = Ui.row(act);
        head.addView(Ui.header(act, "Transferts"), Ui.weight1());
        TextView clear = Ui.text(act, "Effacer les terminés", 13, R.color.accent);
        clear.setOnClickListener(v -> Transfers.clearFinished());
        head.addView(clear);
        content.addView(head);

        listBox = Ui.column(act);
        content.addView(listBox);
    }

    void attach() {
        Transfers.addListener(this);
        ShareHub.get(act).addListener(this);
        onShareChanged();
        onTransfersChanged();
    }

    void detach() {
        Transfers.removeListener(this);
        ShareHub.get(act).removeListener(this);
    }

    @Override
    public void onShareChanged() {
        ShareHub hub = ShareHub.get(act);
        code.setText(hub.code());
        List<Share.Peer> peers = Share.peers(act);
        if (peers.isEmpty()) {
            peersText.setText("Aucun. Dans Fichiers, touche « + Appareil ».");
            peersText.setTextColor(act.getColor(R.color.muted));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Share.Peer p : peers) {
            if (sb.length() > 0) sb.append('\n');
            boolean on = hub.online(p);
            sb.append(on ? "●  " : "○  ").append(p.name).append(on ? "   en ligne" : "   hors ligne");
        }
        peersText.setText(sb);
        peersText.setTextColor(act.getColor(R.color.text));
    }

    @Override
    public void onTransfersChanged() {
        listBox.removeAllViews();
        List<Transfers.Transfer> all = Transfers.all();
        if (all.isEmpty()) {
            listBox.addView(Ui.text(act, "Rien pour l'instant. Dans Fichiers, choisis des fichiers puis « Copier vers… ».",
                    14, R.color.muted));
            return;
        }
        for (Transfers.Transfer t : all) listBox.addView(item(t), cardLp());
    }

    private View item(Transfers.Transfer t) {
        LinearLayout c = card();
        LinearLayout top = Ui.row(act);
        int color = t.state == Transfers.State.FAILED ? act.getColor(R.color.danger)
                : t.state == Transfers.State.DONE ? act.getColor(R.color.ok) : act.getColor(R.color.accent);
        top.addView(Ui.badge(act, t.sending ? R.drawable.ic_up : R.drawable.ic_down, color, 36));
        LinearLayout texts = Ui.column(act);
        texts.setPadding(Ui.dp(act, 12), 0, 0, 0);
        texts.addView(Ui.text(act, (t.sending ? "Copie vers " : "Reçu de ") + t.peer, 15, R.color.text));
        texts.addView(Ui.text(act, status(t), 12, R.color.muted));
        top.addView(texts, Ui.weight1());
        if (t.state == Transfers.State.RUNNING && t.sending) {
            TextView cancel = Ui.text(act, "Annuler", 13, R.color.danger);
            cancel.setPadding(Ui.dp(act, 8), Ui.dp(act, 8), 0, Ui.dp(act, 8));
            cancel.setOnClickListener(v -> t.cancel());
            top.addView(cancel);
        }
        c.addView(top);
        if (t.state == Transfers.State.RUNNING) {
            ProgressBar bar = new ProgressBar(act, null, android.R.attr.progressBarStyleHorizontal);
            bar.setProgressDrawable(act.getDrawable(R.drawable.progress));
            bar.setMax(100);
            bar.setProgress(t.percent());
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(act, 6));
            lp.topMargin = Ui.dp(act, 12);
            c.addView(bar, lp);
        }
        if (!t.current.isEmpty()) {
            TextView cur = Ui.text(act, t.current, 12, R.color.muted);
            cur.setSingleLine(true);
            cur.setPadding(0, Ui.dp(act, 6), 0, 0);
            c.addView(cur);
        }
        return c;
    }

    private static String status(Transfers.Transfer t) {
        String files = t.done + "/" + t.count + " fichier" + (t.count > 1 ? "s" : "");
        switch (t.state) {
            case DONE:
                return "Terminé  ·  " + files + "  ·  " + Ui.size(t.total);
            case FAILED:
                return "Échec : " + t.error;
            case CANCELED:
                return "Annulé  ·  " + files;
            default:
                return t.percent() + " %  ·  " + files + "  ·  " + Ui.size(t.speed()) + "/s";
        }
    }

    private LinearLayout card() {
        LinearLayout c = Ui.column(act);
        c.setBackgroundResource(R.drawable.card);
        int p = Ui.dp(act, 16);
        c.setPadding(p, p, p, p);
        return c;
    }

    private LinearLayout.LayoutParams cardLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(act, 10);
        return lp;
    }
}
