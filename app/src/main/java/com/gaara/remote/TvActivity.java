package com.gaara.remote;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.gaara.remote.share.Share;
import com.gaara.remote.share.ShareHub;
import com.gaara.remote.share.ShareService;
import com.gaara.remote.share.Transfers;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;
import java.util.List;

/**
 * The app's screen on the TV: no remote here, just file sharing. Shows the pairing code
 * to type on the phone or PC, the paired devices and the last transfers.
 */
public class TvActivity extends Activity implements ShareHub.Listener, Transfers.Listener {
    private TextView code, devices, detected, transfers;

    /** The TV screen is always dark: light themes glare on a TV, and TVs often report "day" mode. */
    @Override
    protected void attachBaseContext(android.content.Context base) {
        super.attachBaseContext(base);
        android.content.res.Configuration o = new android.content.res.Configuration();
        o.uiMode = (base.getResources().getConfiguration().uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_YES;
        applyOverrideConfiguration(o);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        ShareService.start(this); // runs in the background from now on (and after each reboot)

        ScrollView scroll = new ScrollView(this);
        LinearLayout root = Ui.column(this);
        int p = Ui.dp(this, 48);
        root.setPadding(p, Ui.dp(this, 36), p, p);
        scroll.addView(root);

        TextView title = Ui.text(this, "Nexio — partage de fichiers", 30, R.color.text);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        root.addView(title);
        root.addView(Ui.text(this, ShareHub.get(this).name() + "  ·  " + ip(), 18, R.color.muted));

        LinearLayout codeCard = Ui.column(this);
        codeCard.setBackgroundResource(R.drawable.card);
        codeCard.setGravity(Gravity.CENTER_HORIZONTAL);
        codeCard.setPadding(p, Ui.dp(this, 28), p, Ui.dp(this, 28));
        codeCard.addView(Ui.text(this, "Code d'appairage", 18, R.color.muted));
        code = Ui.text(this, "", 64, R.color.text);
        code.setLetterSpacing(0.25f);
        codeCard.addView(code);
        codeCard.addView(Ui.text(this, "Sur le téléphone : Fichiers → + Appareil → choisis cette TV, puis tape ce code.",
                16, R.color.muted));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Ui.dp(this, 28);
        root.addView(codeCard, lp);

        root.addView(Ui.header(this, "Appareils appairés"));
        devices = Ui.text(this, "", 18, R.color.text);
        root.addView(devices);
        root.addView(Ui.header(this, "Détectés sur le réseau (pas encore appairés)"));
        detected = Ui.text(this, "", 18, R.color.text);
        root.addView(detected);
        root.addView(Ui.header(this, "Derniers transferts"));
        transfers = Ui.text(this, "", 16, R.color.text);
        root.addView(transfers);
        TextView foot = Ui.text(this, "Le partage reste actif en arrière-plan, même quand cet écran est fermé.", 14, R.color.muted);
        foot.setPadding(0, Ui.dp(this, 24), 0, 0);
        root.addView(foot);

        setContentView(scroll);
    }

    @Override
    protected void onStart() {
        super.onStart();
        ShareHub.get(this).addListener(this);
        Transfers.addListener(this);
        onShareChanged();
        onTransfersChanged();
    }

    @Override
    protected void onStop() {
        super.onStop();
        ShareHub.get(this).removeListener(this);
        Transfers.removeListener(this);
    }

    @Override
    public void onShareChanged() {
        ShareHub hub = ShareHub.get(this);
        code.setText(hub.code());
        StringBuilder found = new StringBuilder();
        for (ShareHub.Seen s : hub.seen()) {
            if (Share.peer(this, s.uuid) != null) continue;
            if (found.length() > 0) found.append('\n');
            found.append("+  ").append(s.name).append("   ").append(s.host)
                    .append("   — sur cet appareil, choisis « ").append(hub.name()).append(" » et tape le code ci-dessus");
        }
        detected.setText(found.length() == 0 ? "Aucun." : found);
        detected.setTextColor(getColor(found.length() == 0 ? R.color.muted : R.color.warn));
        List<Share.Peer> peers = Share.peers(this);
        if (peers.isEmpty()) {
            devices.setText("Aucun pour l'instant.");
            devices.setTextColor(getColor(R.color.muted));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Share.Peer peer : peers) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(hub.online(peer) ? "●  " : "○  ").append(peer.name);
        }
        devices.setText(sb);
        devices.setTextColor(getColor(R.color.text));
    }

    @Override
    public void onTransfersChanged() {
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (Transfers.Transfer t : Transfers.all()) {
            if (n++ == 6) break;
            if (sb.length() > 0) sb.append('\n');
            sb.append(t.sending ? "↑ " : "↓ ").append(t.current.isEmpty() ? "" : t.current + "  ")
                    .append(t.sending ? "vers " : "de ").append(t.peer).append("  —  ")
                    .append(t.state == Transfers.State.RUNNING ? t.percent() + " %"
                            : t.state == Transfers.State.DONE ? "terminé" : "échec");
        }
        transfers.setText(n == 0 ? "Aucun." : sb);
    }

    private static String ip() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.getName().startsWith("tun")) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address) return a.getHostAddress();
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }
}
