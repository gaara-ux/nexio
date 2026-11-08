package com.gaara.remote.share;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The APK extractor: lists the apps installed on this device and hands out their APK files.
 * An app made of one file gives a .apk; an app in several pieces (split APKs) gives a .apks,
 * a zip of base.apk + split_*.apk that SAI or "adb install-multiple" can install.
 */
public final class ApkStore {
    private ApkStore() {}

    /** Where extracted apps land, on the phone. */
    public static final String FOLDER = "Download/Nexio/APK";

    public static final class App {
        public String pkg, label, version;
        public long code, size, updated;
        public boolean system;
        /** Has a screen to open (TV home row or phone launcher): what a launcher should list. */
        public boolean launch = true;
        public int parts;

        public String fileName() {
            String v = version == null || version.isEmpty() ? String.valueOf(code) : version;
            return safe(label) + "_" + safe(v) + (parts > 1 ? ".apks" : ".apk");
        }

        public JSONObject toJson() throws JSONException {
            return new JSONObject().put("pkg", pkg).put("label", label).put("ver", version).put("code", code)
                    .put("size", size).put("upd", updated).put("sys", system).put("parts", parts).put("launch", launch);
        }

        public static App fromJson(JSONObject o) {
            App a = new App();
            a.pkg = o.optString("pkg");
            a.label = o.optString("label", a.pkg);
            a.version = o.optString("ver");
            a.code = o.optLong("code");
            a.size = o.optLong("size");
            a.updated = o.optLong("upd");
            a.system = o.optBoolean("sys");
            a.parts = Math.max(1, o.optInt("parts", 1));
            a.launch = o.optBoolean("launch", true); // older versions did not say: show everything
            return a;
        }
    }

    /** Every installed app (system ones flagged), sorted by name. Takes a moment: call off the main thread. */
    public static List<App> list(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<App> out = new ArrayList<>();
        for (PackageInfo pi : pm.getInstalledPackages(0)) {
            ApplicationInfo ai = pi.applicationInfo;
            if (ai == null || ai.sourceDir == null) continue;
            App a = new App();
            a.pkg = pi.packageName;
            a.label = String.valueOf(pm.getApplicationLabel(ai));
            a.version = pi.versionName == null ? "" : pi.versionName;
            a.code = pi.getLongVersionCode();
            a.updated = pi.lastUpdateTime;
            // An updated system app lives in /data like any other: count it as installed by the person.
            a.system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            a.launch = pm.getLeanbackLaunchIntentForPackage(a.pkg) != null || pm.getLaunchIntentForPackage(a.pkg) != null;
            List<File> files = files(ai);
            a.parts = files.size();
            for (File f : files) a.size += f.length();
            out.add(a);
        }
        out.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
        return out;
    }

    public static App info(Context ctx, String pkg) throws IOException {
        for (App a : list(ctx)) if (a.pkg.equals(pkg)) return a;
        throw new IOException("appli introuvable : " + pkg);
    }

    private static List<File> files(ApplicationInfo ai) {
        List<File> l = new ArrayList<>();
        l.add(new File(ai.sourceDir));
        if (ai.splitSourceDirs != null) for (String s : ai.splitSourceDirs) l.add(new File(s));
        return l;
    }

    private static ApplicationInfo appInfo(Context ctx, String pkg) throws IOException {
        if (pkg == null || pkg.isEmpty()) throw new IOException("appli manquante");
        try {
            return ctx.getPackageManager().getApplicationInfo(pkg, 0);
        } catch (PackageManager.NameNotFoundException e) {
            throw new IOException("appli introuvable : " + pkg);
        }
    }

    /** Writes the app's .apk (one piece) or .apks (several) to out. progress gets the bytes read. */
    public static void write(Context ctx, String pkg, OutputStream out, Progress progress) throws IOException {
        List<File> files = files(appInfo(ctx, pkg));
        byte[] buf = new byte[64 * 1024];
        if (files.size() == 1) {
            copy(files.get(0), out, buf, progress);
            return;
        }
        ZipOutputStream zip = new ZipOutputStream(out);
        zip.setLevel(0); // APKs are already compressed: just pack them
        for (File f : files) {
            String name = f == files.get(0) ? "base.apk" : f.getName(); // the first is always the base
            zip.putNextEntry(new ZipEntry(name));
            copy(f, zip, buf, progress);
            zip.closeEntry();
        }
        zip.finish();
    }

    /** A file with the exact bytes to send: the base APK itself, or a temporary .apks built in cache. */
    public static final class Prepared {
        public final File file;
        public final boolean temporary;

        Prepared(File file, boolean temporary) {
            this.file = file;
            this.temporary = temporary;
        }

        public void release() {
            if (temporary) file.delete();
        }
    }

    public static Prepared prepare(Context ctx, String pkg) throws IOException {
        List<File> files = files(appInfo(ctx, pkg));
        if (files.size() == 1) return new Prepared(files.get(0), false);
        File tmp = File.createTempFile("apks", ".apks", ctx.getCacheDir());
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(tmp), 64 * 1024)) {
            write(ctx, pkg, o, null);
        } catch (IOException e) {
            tmp.delete();
            throw e;
        }
        return new Prepared(tmp, true);
    }

    /** The app's icon as a small PNG, for the other devices' lists. */
    public static byte[] iconPng(Context ctx, String pkg, int px) throws IOException {
        Drawable d = ctx.getPackageManager().getApplicationIcon(appInfo(ctx, pkg));
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, px, px);
        d.draw(c);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, o);
        b.recycle();
        return o.toByteArray();
    }

    public interface Progress {
        /** Returns false to stop. */
        boolean read(int n);
    }

    private static void copy(File f, OutputStream out, byte[] buf, Progress progress) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (progress != null && !progress.read(n)) throw new IOException("annulé");
            }
        }
    }

    static String safe(String s) {
        String r = (s == null ? "" : s).replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return r.isEmpty() ? "app" : r;
    }
}
