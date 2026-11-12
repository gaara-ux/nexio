package com.gaara.remote.share;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.os.Build;
import android.widget.Toast;

import java.io.IOException;

/**
 * Uninstalls an app through Android's own installer, which always asks the person to confirm.
 * A system app cannot be removed; for an updated system app, Android offers to remove the updates.
 */
public final class Uninstaller {
    private Uninstaller() {}

    /** Asks Android to uninstall {@code pkg}; its confirmation screen opens. */
    public static void uninstall(Context ctx, String pkg) throws IOException {
        if (pkg == null || pkg.isEmpty()) throw new IOException("aucune appli indiquée");
        Context app = ctx.getApplicationContext();
        PackageManager pm = app.getPackageManager();
        String label;
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            boolean updated = (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0;
            if (system && !updated) throw new IOException("c'est une appli système : Android ne permet pas de la désinstaller");
            label = String.valueOf(pm.getApplicationLabel(ai));
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("cette appli n'est pas installée");
        }
        Intent done = new Intent(app, Status.class).putExtra("name", label);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
        PendingIntent pi = PendingIntent.getBroadcast(app, pkg.hashCode(), done, flags);
        try {
            pm.getPackageInstaller().uninstall(pkg, pi.getIntentSender());
        } catch (SecurityException e) {
            throw new IOException("Android refuse : " + e.getMessage());
        }
    }

    /** The installer's answers: open its confirmation screen, then say how it went. */
    public static final class Status extends BroadcastReceiver {
        @Override
        public void onReceive(Context ctx, Intent intent) {
            int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            String name = intent.getStringExtra("name");
            if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    try {
                        ctx.startActivity(confirm);
                    } catch (Exception e) {
                        Toast.makeText(ctx, "L'écran de désinstallation n'a pas pu s'ouvrir : " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
                return;
            }
            String msg;
            if (status == PackageInstaller.STATUS_SUCCESS) {
                msg = name + " : désinstallée ✓";
            } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                msg = "Désinstallation de " + name + " annulée";
            } else {
                String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                msg = "Impossible de désinstaller " + name + (why == null ? "" : " : " + why);
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        }
    }
}
