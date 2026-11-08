package com.gaara.remote.share;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Installs an app from a file, through Android's own installer (it always asks the person to confirm):
 * .apk, or a bundle of pieces — .apks / .apkm / .xapk (a zip of base.apk + split_*.apk).
 */
public final class Installer {
    private Installer() {}

    public static boolean isPackage(String name) {
        return name.toLowerCase(Locale.ROOT).matches(".*\\.(apk|apks|apkm|xapk)$");
    }

    /** False until "Install unknown apps" is allowed for this app (Android asks once). */
    public static boolean allowed(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    /** Opens the setting that allows this app to install apps. */
    public static void askPermission(Context ctx) {
        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + ctx.getPackageName()))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(ctx, "Autorise « Installer des applis inconnues » pour Nexio dans les paramètres.",
                    Toast.LENGTH_LONG).show();
        }
    }

    /**
     * Hands the file to the system installer. Blocking (copies the APKs): call off the main thread.
     * The confirmation screen then opens; the result comes as a short message.
     */
    public static void install(Context ctx, File f) throws IOException {
        if (!f.isFile()) throw new IOException("fichier introuvable");
        if (!allowed(ctx)) throw new IOException("Nexio n'a pas encore le droit d'installer des applis "
                + "(Paramètres › Applis › Nexio › Installer des applis inconnues).");
        Context app = ctx.getApplicationContext();
        PackageInstaller pi = app.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        int id = pi.createSession(params);
        try (PackageInstaller.Session s = pi.openSession(id)) {
            if (f.getName().toLowerCase(Locale.ROOT).endsWith(".apk")) {
                try (InputStream in = new FileInputStream(f)) {
                    write(s, "base.apk", in, f.length());
                }
            } else {
                int n = 0;
                try (ZipFile zip = new ZipFile(f)) {
                    Enumeration<? extends ZipEntry> all = zip.entries();
                    while (all.hasMoreElements()) {
                        ZipEntry e = all.nextElement();
                        String name = e.getName();
                        // Only the APKs at the top: an .xapk may also carry icons, a manifest and OBB data.
                        if (e.isDirectory() || name.contains("/") || !name.toLowerCase(Locale.ROOT).endsWith(".apk")) continue;
                        try (InputStream in = zip.getInputStream(e)) {
                            write(s, (n++) + "_" + name, in, e.getSize());
                        }
                    }
                } catch (java.util.zip.ZipException z) {
                    throw new IOException("ce fichier n'est pas un paquet d'applis lisible");
                }
                if (n == 0) throw new IOException("aucun APK dans ce fichier");
            }
            Intent done = new Intent(app, Status.class).putExtra("name", f.getName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            s.commit(PendingIntent.getBroadcast(app, id, done, flags).getIntentSender());
        } catch (IOException | RuntimeException e) {
            try {
                pi.abandonSession(id);
            } catch (Exception ignored) {
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
    }

    private static void write(PackageInstaller.Session s, String name, InputStream in, long size) throws IOException {
        try (OutputStream out = s.openWrite(name, 0, size)) {
            byte[] buf = new byte[64 * 1024];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            s.fsync(out);
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
                        Toast.makeText(ctx, "L'écran d'installation n'a pas pu s'ouvrir : " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
                return;
            }
            String msg;
            if (status == PackageInstaller.STATUS_SUCCESS) {
                msg = name + " : installé ✓";
            } else if (status == PackageInstaller.STATUS_FAILURE_ABORTED) {
                msg = "Installation de " + name + " annulée";
            } else {
                String why = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                msg = "Installation de " + name + " impossible" + (why == null ? "" : " : " + why);
            }
            Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show();
        }
    }
}
