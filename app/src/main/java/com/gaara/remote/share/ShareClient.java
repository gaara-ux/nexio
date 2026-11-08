package com.gaara.remote.share;

import android.content.Context;
import android.media.MediaScannerConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Talks to the other devices: pairing, listing, and copying in any direction. */
public final class ShareClient {
    private ShareClient() {}

    /** A file or folder on some device. */
    public static final class Entry {
        public final String name;
        public final boolean dir;
        public final long size, mtime;
        public final int count;

        public Entry(String name, boolean dir, long size, long mtime, int count) {
            this.name = name;
            this.dir = dir;
            this.size = size;
            this.mtime = mtime;
            this.count = count;
        }
    }

    // ---- Pairing ----

    /** Pairs with the device at host using the code shown on its screen. Blocking. */
    public static Share.Peer pair(Context ctx, String host, int port, String code) throws IOException {
        try {
            ShareHub me = ShareHub.get(ctx);
            JSONObject body = me.myInfo().put("code", code.trim());
            HttpURLConnection c = open(host, port, "/api/pair", null, "POST");
            byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(b.length);
            c.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = c.getOutputStream()) {
                o.write(b);
            }
            JSONObject r = readJson(c);
            Share.Peer p = new Share.Peer(r.getString("uuid"), r.optString("name", host), host,
                    r.optInt("port", port), r.getString("token"), r.optBoolean("tv"));
            Share.savePeer(ctx, p);
            me.changed();
            return p;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    // ---- Listing ----

    /** Lists a folder: on this device when peer is null, else on the paired device. Blocking. */
    public static List<Entry> list(Context ctx, Share.Peer peer, String path) throws IOException {
        List<Entry> out = new ArrayList<>();
        if (peer == null) {
            File[] kids = Share.resolve(path).listFiles();
            if (kids == null) return out;
            for (File k : kids) {
                String[] sub = k.isDirectory() ? k.list() : null;
                out.add(new Entry(k.getName(), k.isDirectory(), k.isDirectory() ? 0 : k.length(), k.lastModified(),
                        sub == null ? 0 : sub.length));
            }
            out.sort((a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
            return out;
        }
        try {
            HttpURLConnection c = open(ShareHub.get(ctx).hostOf(peer), peer.port, "/api/list?path=" + enc(path), peer.token, "GET");
            JSONArray items = readJson(c).getJSONArray("items");
            for (int i = 0; i < items.length(); i++) {
                JSONObject o = items.getJSONObject(i);
                out.add(new Entry(o.getString("n"), o.optBoolean("d"), o.optLong("s"), o.optLong("m"), o.optInt("c")));
            }
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    // ---- Simple actions (this phone when peer is null) ----

    public static void mkdir(Context ctx, Share.Peer peer, String path) throws IOException {
        if (peer == null) {
            File d = Share.resolve(path);
            if (!d.mkdirs() && !d.isDirectory()) throw new IOException("impossible de créer le dossier");
        } else {
            post(ctx, peer, "/api/mkdir?path=" + enc(path));
        }
    }

    public static void delete(Context ctx, Share.Peer peer, String path) throws IOException {
        if (peer == null) {
            File f = Share.resolve(path);
            if (f.equals(Share.root()) || !Share.delete(f)) throw new IOException("suppression impossible : " + f.getName());
        } else {
            post(ctx, peer, "/api/delete?path=" + enc(path));
        }
    }

    /** Renames or moves inside one device; "to" is the new full path. */
    public static void rename(Context ctx, Share.Peer peer, String path, String to) throws IOException {
        if (peer == null) {
            File f = Share.resolve(path), dest = Share.resolve(to);
            if (dest.exists()) throw new IOException("« " + dest.getName() + " » existe déjà");
            File parent = dest.getParentFile();
            if (parent != null) parent.mkdirs();
            if (!f.renameTo(dest)) throw new IOException("impossible de renommer");
        } else {
            post(ctx, peer, "/api/rename?path=" + enc(path) + "&to=" + enc(to));
        }
    }

    private static void post(Context ctx, Share.Peer peer, String path) throws IOException {
        try {
            HttpURLConnection c = open(ShareHub.get(ctx).hostOf(peer), peer.port, path, peer.token, "POST");
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
            readJson(c);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    public static boolean same(Share.Peer a, Share.Peer b) {
        return a == null ? b == null : b != null && a.uuid.equals(b.uuid);
    }

    // ---- Copying / moving ----

    /**
     * Copies files/folders (names inside fromDir on device "from") into toDir on device "to".
     * null means this phone. Runs in the background; progress in {@link Transfers}.
     */
    public static Transfers.Transfer copy(Context ctx, Share.Peer from, String fromDir, List<Entry> picked,
                                          Share.Peer to, String toDir) {
        return transfer(ctx, from, fromDir, picked, to, toDir, false, null);
    }

    /**
     * Copies then removes the originals (only once everything arrived). On the same device it is a rename.
     * done runs on the copy thread when finished.
     */
    public static Transfers.Transfer transfer(Context ctx, Share.Peer from, String fromDir, List<Entry> picked,
                                              Share.Peer to, String toDir, boolean move, Runnable done) {
        String toName = to == null ? "ce téléphone" : to.name;
        Transfers.Transfer t = Transfers.start(true, toName);
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                if (move && same(from, to)) { // same device: just move the entries
                    t.count = picked.size();
                    for (Entry e : picked) {
                        t.current = e.name;
                        Transfers.changed(true);
                        rename(app, from, join(fromDir, e.name), join(toDir, e.name));
                        t.done++;
                    }
                    t.state = Transfers.State.DONE;
                    t.current = "";
                    Transfers.changed(true);
                    if (done != null) done.run();
                    return;
                }
                // Expand folders first, to know the total size.
                List<String[]> jobs = new ArrayList<>(); // {source path, destination path, size, mtime}
                for (Entry e : picked) expand(app, from, join(fromDir, e.name), join(toDir, e.name), e, jobs);
                t.count = jobs.size();
                for (String[] j : jobs) t.total += Long.parseLong(j[2]);
                Transfers.changed(true);
                List<String> scanned = new ArrayList<>();
                for (String[] j : jobs) {
                    if (t.cancel) throw new IOException("annulé");
                    t.current = new File(j[0]).getName();
                    Transfers.changed(true);
                    copyOne(app, from, j[0], to, j[1], Long.parseLong(j[2]), Long.parseLong(j[3]), t);
                    if (to == null) scanned.add(Share.resolve(j[1]).getAbsolutePath());
                    t.done++;
                }
                if (!scanned.isEmpty()) MediaScannerConnection.scanFile(app, scanned.toArray(new String[0]), null, null);
                // Moving: every file arrived, now remove the originals.
                if (move) for (Entry e : picked) delete(app, from, join(fromDir, e.name));
                t.state = Transfers.State.DONE;
            } catch (Exception e) {
                t.state = t.cancel ? Transfers.State.CANCELED : Transfers.State.FAILED;
                t.error = message(e);
            }
            t.current = "";
            Transfers.changed(true);
            if (done != null) done.run();
        }, "share-copy").start();
        return t;
    }

    private static void expand(Context ctx, Share.Peer from, String src, String dst, Entry e, List<String[]> jobs)
            throws IOException {
        if (!e.dir) {
            jobs.add(new String[]{src, dst, Long.toString(e.size), Long.toString(e.mtime)});
            return;
        }
        for (Entry k : list(ctx, from, src)) expand(ctx, from, join(src, k.name), join(dst, k.name), k, jobs);
    }

    private static void copyOne(Context ctx, Share.Peer from, String src, Share.Peer to, String dst, long size,
                                long mtime, Transfers.Transfer t) throws IOException {
        InputStream in;
        HttpURLConnection get = null;
        if (from == null) {
            in = new FileInputStream(Share.resolve(src));
        } else {
            get = open(ShareHub.get(ctx).hostOf(from), from.port, "/api/file?path=" + enc(src), from.token, "GET");
            check(get);
            in = get.getInputStream();
        }
        try {
            if (to == null) {
                File dest = ShareServer.unique(Share.resolve(dst));
                File parent = dest.getParentFile();
                if (parent != null) parent.mkdirs();
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 64 * 1024)) {
                    pump(in, out, size, t);
                } catch (IOException ex) {
                    dest.delete();
                    throw ex;
                }
                if (mtime > 0) dest.setLastModified(mtime);
            } else {
                HttpURLConnection put = open(ShareHub.get(ctx).hostOf(to), to.port,
                        "/api/file?path=" + enc(dst) + "&mtime=" + mtime, to.token, "PUT");
                put.setDoOutput(true);
                put.setFixedLengthStreamingMode(size);
                try (OutputStream out = put.getOutputStream()) {
                    pump(in, out, size, t);
                }
                check(put);
                put.disconnect();
            }
        } finally {
            in.close();
            if (get != null) get.disconnect();
        }
    }

    private static void pump(InputStream in, OutputStream out, long size, Transfers.Transfer t) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long left = size;
        while (left > 0) {
            if (t.cancel) throw new IOException("annulé");
            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (n < 0) throw new IOException("connexion coupée");
            out.write(buf, 0, n);
            left -= n;
            t.bytes += n;
            Transfers.changed(false);
        }
    }

    // ---- Installing ----

    /** Installs a file of the paired device on that device (its screen asks to confirm). Blocking. */
    public static void install(Context ctx, Share.Peer peer, String path) throws IOException {
        post(ctx, peer, "/api/install?path=" + enc(path));
    }

    /** Copies one file of a paired device into a local file. Blocking. */
    public static void download(Context ctx, Share.Peer peer, String path, File dest) throws IOException {
        HttpURLConnection c = open(ShareHub.get(ctx).hostOf(peer), peer.port, "/api/file?path=" + enc(path), peer.token, "GET");
        try {
            check(c);
            try (InputStream in = c.getInputStream();
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 64 * 1024)) {
                byte[] buf = new byte[64 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            }
        } catch (IOException e) {
            dest.delete();
            throw e;
        } finally {
            c.disconnect();
        }
    }

    // ---- APK extractor ----

    /** Apps installed on this phone (peer null) or on a paired device. Blocking. */
    public static List<ApkStore.App> apps(Context ctx, Share.Peer peer) throws IOException {
        if (peer == null) return ApkStore.list(ctx);
        try {
            HttpURLConnection c = open(ShareHub.get(ctx).hostOf(peer), peer.port, "/api/apps", peer.token, "GET");
            JSONArray a = readJson(c).getJSONArray("apps");
            List<ApkStore.App> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(ApkStore.App.fromJson(a.getJSONObject(i)));
            return out;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
    }

    /** A paired device's app icon (PNG bytes). Blocking. */
    public static byte[] icon(Context ctx, Share.Peer peer, String pkg) throws IOException {
        HttpURLConnection c = open(ShareHub.get(ctx).hostOf(peer), peer.port, "/api/icon?pkg=" + enc(pkg), peer.token, "GET");
        try {
            check(c);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
                return b.toByteArray();
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Saves the apps' APKs (from this phone, or from a paired device when from is not null)
     * into Download/Nexio/APK on this phone. Runs in the background; progress in {@link Transfers}.
     */
    public static Transfers.Transfer extract(Context ctx, Share.Peer from, List<ApkStore.App> apps) {
        Transfers.Transfer t = Transfers.start(true, ApkStore.FOLDER);
        Context app = ctx.getApplicationContext();
        t.count = apps.size();
        for (ApkStore.App a : apps) t.total += a.size;
        new Thread(() -> {
            List<String> scanned = new ArrayList<>();
            try {
                File dir = Share.resolve(ApkStore.FOLDER);
                if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("impossible de créer " + ApkStore.FOLDER);
                for (ApkStore.App a : apps) {
                    if (t.cancel) throw new IOException("annulé");
                    t.current = a.label;
                    Transfers.changed(true);
                    File dest = ShareServer.unique(new File(dir, a.fileName()));
                    long before = t.bytes;
                    try (OutputStream out = new BufferedOutputStream(new FileOutputStream(dest), 64 * 1024)) {
                        if (from == null) {
                            ApkStore.write(app, a.pkg, out, n -> {
                                t.bytes += n;
                                Transfers.changed(false);
                                return !t.cancel;
                            });
                        } else {
                            HttpURLConnection get = open(ShareHub.get(app).hostOf(from), from.port,
                                    "/api/apk?pkg=" + enc(a.pkg), from.token, "GET");
                            try {
                                check(get);
                                long len = get.getContentLengthLong();
                                try (InputStream in = get.getInputStream()) {
                                    pump(in, out, len, t);
                                }
                            } finally {
                                get.disconnect();
                            }
                        }
                    } catch (IOException ex) {
                        dest.delete();
                        throw ex;
                    }
                    t.bytes = before + a.size; // a .apks is a bit bigger than its pieces: keep the bar honest
                    scanned.add(dest.getAbsolutePath());
                    t.done++;
                }
                t.state = Transfers.State.DONE;
                t.folder = ApkStore.FOLDER;
            } catch (Exception e) {
                t.state = t.cancel ? Transfers.State.CANCELED : Transfers.State.FAILED;
                t.error = message(e);
            }
            if (!scanned.isEmpty()) MediaScannerConnection.scanFile(app, scanned.toArray(new String[0]), null, null);
            t.current = "";
            Transfers.changed(true);
        }, "apk-extract").start();
        return t;
    }

    // ---- HTTP ----

    private static HttpURLConnection open(String host, int port, String path, String token, String method)
            throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL("http://" + host + ":" + port + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(5000);
        c.setReadTimeout(60000);
        if (token != null) c.setRequestProperty("X-Token", token);
        return c;
    }

    private static void check(HttpURLConnection c) throws IOException {
        int code = c.getResponseCode();
        if (code != 200) throw new IOException(error(c, code));
    }

    private static JSONObject readJson(HttpURLConnection c) throws Exception {
        check(c);
        try (InputStream in = c.getInputStream()) {
            return new JSONObject(readAll(in));
        } finally {
            c.disconnect();
        }
    }

    private static String error(HttpURLConnection c, int code) {
        try (InputStream e = c.getErrorStream()) {
            if (e != null) return new JSONObject(readAll(e)).optString("error", "erreur " + code);
        } catch (Exception ignored) {
        }
        return "erreur " + code;
    }

    private static String readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        for (int n; (n = in.read(buf)) > 0; ) b.write(buf, 0, n);
        return b.toString("UTF-8");
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static String join(String dir, String name) {
        return dir == null || dir.isEmpty() ? name : dir + "/" + name;
    }

    public static String message(Exception e) {
        String m = e.getMessage();
        if (m == null) return e.getClass().getSimpleName();
        if (m.contains("ECONNREFUSED") || m.contains("Failed to connect") || m.contains("refused")) {
            return "L'appareil ne répond pas. L'appli doit être installée et lancée dessus.";
        }
        if (m.contains("timed out") || m.contains("ETIMEDOUT")) return "L'appareil ne répond pas.";
        return m;
    }
}
