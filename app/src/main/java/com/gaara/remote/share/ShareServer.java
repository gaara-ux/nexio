package com.gaara.remote.share;

import android.content.Context;
import android.media.MediaScannerConnection;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal HTTP/1.1 server (one request per connection) for the sharing API:
 *   GET  /api/info                     who am I (no auth)
 *   POST /api/pair    {code,uuid,name,port,tv}   → {token,uuid,name,tv}   (code shown on this screen)
 *   GET  /api/list?path=               folder content
 *   GET  /api/file?path=               download
 *   PUT  /api/file?path=dir/name       upload (never overwrites: "name (1).ext")
 *   POST /api/mkdir?path=
 *   GET  /api/apps                     installed apps (APK extractor)
 *   GET  /api/apk?pkg=                 the app's .apk, or .apks when it is in several pieces
 *   GET  /api/icon?pkg=                its icon, PNG
 *   POST /api/install?path=            install that file here (.apk/.apks/.apkm/.xapk; the screen asks to confirm)
 * Every call but info/pair needs "X-Token" of a paired device.
 */
final class ShareServer {
    interface Hooks {
        /** Returns the peer created when the code matches, or null. */
        Share.Peer pair(String code, JSONObject who, String host);
        JSONObject info();
    }

    private final Context ctx;
    private final Hooks hooks;
    private ServerSocket server;
    private volatile boolean running;

    ShareServer(Context ctx, Hooks hooks) {
        this.ctx = ctx;
        this.hooks = hooks;
    }

    synchronized int start() throws IOException {
        if (server != null) return server.getLocalPort();
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        s.bind(new InetSocketAddress(Share.PORT));
        server = s;
        running = true;
        new Thread(() -> {
            while (running) {
                try {
                    Socket c = s.accept();
                    new Thread(() -> handle(c), "share-http").start();
                } catch (IOException e) {
                    if (!running) return;
                }
            }
        }, "share-server").start();
        return s.getLocalPort();
    }

    synchronized void stop() {
        running = false;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
            }
            server = null;
        }
    }

    private void handle(Socket c) {
        try (Socket s = c) {
            s.setSoTimeout(60000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 64 * 1024);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 64 * 1024);
            String line = readLine(in);
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], target = parts[1];
            Map<String, String> headers = new HashMap<>();
            for (String h; (h = readLine(in)) != null && !h.isEmpty(); ) {
                int i = h.indexOf(':');
                if (i > 0) headers.put(h.substring(0, i).trim().toLowerCase(Locale.ROOT), h.substring(i + 1).trim());
            }
            String pathPart = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                pathPart = target.substring(0, q);
                query = target.substring(q + 1);
            }
            Map<String, String> params = params(query);
            long length = parseLong(headers.get("content-length"));
            String host = s.getInetAddress().getHostAddress();
            try {
                route(method, pathPart, params, headers, length, in, out, host);
            } catch (Denied e) {
                send(out, 403, json("error", e.getMessage()));
            } catch (Exception e) {
                send(out, 400, json("error", e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            out.flush();
        } catch (IOException ignored) {
        }
    }

    private static final class Denied extends Exception {
        Denied(String m) {
            super(m);
        }
    }

    private void route(String method, String path, Map<String, String> p, Map<String, String> h, long length,
                       InputStream in, OutputStream out, String host) throws Exception {
        if (path.equals("/api/info")) {
            send(out, 200, hooks.info());
            return;
        }
        if (path.equals("/api/pair") && method.equals("POST")) {
            JSONObject who = new JSONObject(new String(readBody(in, length), StandardCharsets.UTF_8));
            Share.Peer peer = hooks.pair(who.optString("code"), who, host);
            if (peer == null) throw new Denied("Code incorrect.");
            JSONObject me = hooks.info();
            send(out, 200, me.put("token", peer.token));
            return;
        }
        Share.Peer peer = Share.byToken(ctx, h.get("x-token"));
        if (peer == null) throw new Denied("Appareil non appairé.");
        if (!host.equals(peer.host)) { // remember where it is now
            peer.host = host;
            Share.savePeer(ctx, peer);
        }

        switch (method + " " + path) {
            case "GET /api/list": {
                File dir = Share.resolve(p.get("path"));
                File[] kids = dir.listFiles();
                JSONArray items = new JSONArray();
                if (kids != null) {
                    Arrays.sort(kids, (a, b) -> a.isDirectory() != b.isDirectory()
                            ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
                    for (File k : kids) {
                        JSONObject o = new JSONObject().put("n", k.getName()).put("d", k.isDirectory())
                                .put("m", k.lastModified());
                        if (k.isDirectory()) {
                            String[] sub = k.list();
                            o.put("c", sub == null ? 0 : sub.length);
                        } else {
                            o.put("s", k.length());
                        }
                        items.put(o);
                    }
                }
                send(out, 200, new JSONObject().put("path", Share.relative(dir)).put("items", items));
                return;
            }
            case "GET /api/file": {
                File f = Share.resolve(p.get("path"));
                if (!f.isFile()) throw new IOException("fichier introuvable");
                long size = f.length();
                writeHead(out, 200, "application/octet-stream", size);
                byte[] buf = new byte[64 * 1024];
                try (InputStream fin = new FileInputStream(f)) {
                    for (int n; (n = fin.read(buf)) > 0; ) out.write(buf, 0, n);
                }
                return;
            }
            case "PUT /api/file": {
                File target = Share.resolve(p.get("path"));
                File parent = target.getParentFile();
                if (parent != null) parent.mkdirs();
                File dest = unique(target);
                Transfers.Transfer t = Transfers.start(false, peer.name);
                t.count = 1;
                t.total = length;
                t.current = dest.getName();
                byte[] buf = new byte[64 * 1024];
                try (OutputStream fout = new BufferedOutputStream(new FileOutputStream(dest), 64 * 1024)) {
                    long left = length;
                    while (left > 0) {
                        int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (n < 0) throw new IOException("connexion coupée");
                        fout.write(buf, 0, n);
                        left -= n;
                        t.bytes += n;
                        Transfers.changed(false);
                    }
                } catch (IOException e) {
                    dest.delete();
                    t.state = Transfers.State.FAILED;
                    t.error = e.getMessage();
                    Transfers.changed(true);
                    throw e;
                }
                long mtime = parseLong(p.get("mtime"));
                if (mtime > 0) dest.setLastModified(mtime);
                t.done = 1;
                t.state = Transfers.State.DONE;
                t.folder = parent == null ? "" : Share.relative(parent);
                Transfers.changed(true);
                MediaScannerConnection.scanFile(ctx, new String[]{dest.getAbsolutePath()}, null, null);
                send(out, 200, new JSONObject().put("path", Share.relative(dest)));
                return;
            }
            case "POST /api/mkdir": {
                File d = Share.resolve(p.get("path"));
                if (!d.mkdirs() && !d.isDirectory()) throw new IOException("impossible de créer le dossier");
                send(out, 200, new JSONObject().put("path", Share.relative(d)));
                return;
            }
            case "POST /api/delete": {
                File f = Share.resolve(p.get("path"));
                if (f.equals(Share.root())) throw new IOException("refusé");
                if (!Share.delete(f)) throw new IOException("suppression impossible : " + f.getName());
                send(out, 200, new JSONObject().put("ok", true));
                return;
            }
            case "POST /api/rename": { // also moves within this device: "to" is a full path
                File f = Share.resolve(p.get("path"));
                File to = Share.resolve(p.get("to"));
                if (to.exists()) throw new IOException("« " + to.getName() + " » existe déjà");
                File parent = to.getParentFile();
                if (parent != null) parent.mkdirs();
                if (!f.renameTo(to)) throw new IOException("impossible de renommer");
                send(out, 200, new JSONObject().put("path", Share.relative(to)));
                return;
            }
            // ---- APK extractor: this device's apps ----
            case "GET /api/apps": {
                JSONArray apps = new JSONArray();
                for (ApkStore.App a : ApkStore.list(ctx)) apps.put(a.toJson());
                send(out, 200, new JSONObject().put("apps", apps));
                return;
            }
            case "GET /api/apk": { // the .apk, or a .apks for an app in several pieces
                ApkStore.Prepared f = ApkStore.prepare(ctx, p.get("pkg"));
                try {
                    writeHead(out, 200, "application/octet-stream", f.file.length());
                    byte[] buf = new byte[64 * 1024];
                    try (InputStream fin = new FileInputStream(f.file)) {
                        for (int n; (n = fin.read(buf)) > 0; ) out.write(buf, 0, n);
                    }
                } finally {
                    f.release();
                }
                return;
            }
            case "POST /api/install": { // opens this device's install screen for one of its files
                Installer.install(ctx, Share.resolve(p.get("path")));
                send(out, 200, new JSONObject().put("ok", true));
                return;
            }
            case "GET /api/icon": {
                byte[] png = ApkStore.iconPng(ctx, p.get("pkg"), 96);
                writeHead(out, 200, "image/png", png.length);
                out.write(png);
                return;
            }
            default:
                send(out, 404, json("error", "inconnu"));
        }
    }

    // ---- HTTP helpers ----

    private static void send(OutputStream out, int code, JSONObject body) throws IOException {
        byte[] b = body.toString().getBytes(StandardCharsets.UTF_8);
        writeHead(out, code, "application/json; charset=utf-8", b.length);
        out.write(b);
    }

    private static void writeHead(OutputStream out, int code, String type, long length) throws IOException {
        String reason = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : "Bad Request";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + length + "\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.US_ASCII));
    }

    private static JSONObject json(String k, String v) {
        try {
            return new JSONObject().put(k, v);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\n') return b.toString("UTF-8").replace("\r", "");
            if (b.size() > 8192) throw new IOException("ligne trop longue");
            b.write(c);
        }
        return b.size() == 0 ? null : b.toString("UTF-8");
    }

    private static byte[] readBody(InputStream in, long length) throws IOException {
        if (length < 0 || length > 64 * 1024) throw new IOException("requête trop grosse");
        byte[] b = new byte[(int) length];
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) throw new IOException("requête coupée");
            off += n;
        }
        return b;
    }

    private static Map<String, String> params(String query) {
        Map<String, String> m = new HashMap<>();
        for (String kv : query.split("&")) {
            if (kv.isEmpty()) continue;
            int i = kv.indexOf('=');
            try {
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), "UTF-8");
                String v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), "UTF-8");
                m.put(k, v);
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    static File unique(File f) {
        if (!f.exists()) return f;
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        String base = dot > 0 ? n.substring(0, dot) : n, ext = dot > 0 ? n.substring(dot) : "";
        for (int i = 1; ; i++) {
            File c = new File(f.getParentFile(), base + " (" + i + ")" + ext);
            if (!c.exists()) return c;
        }
    }
}
