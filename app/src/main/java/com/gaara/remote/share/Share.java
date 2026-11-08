package com.gaara.remote.share;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The app's own file sharing between its copies (phone, TV, PC):
 * each device runs a small HTTP server on PORT and announces itself by UDP broadcast.
 * Only paired devices (6-digit code shown on the other screen) may list, read or write files.
 */
public final class Share {
    private Share() {}

    public static final int PORT = 40819;          // HTTP API
    public static final int BEACON_PORT = 40820;   // UDP "I'm here" announcements

    /** A paired device. The token is shared by both sides and sent with every request. */
    public static final class Peer {
        public final String uuid;
        public String name, host, token;
        public int port;
        public boolean tv;

        public Peer(String uuid, String name, String host, int port, String token, boolean tv) {
            this.uuid = uuid;
            this.name = name;
            this.host = host;
            this.port = port;
            this.token = token;
            this.tv = tv;
        }
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("share", Context.MODE_PRIVATE);
    }

    public static String uuid(Context c) {
        String id = prefs(c).getString("uuid", null);
        if (id == null) {
            id = UUID.randomUUID().toString();
            prefs(c).edit().putString("uuid", id).apply();
        }
        return id;
    }

    /** The device's own name (e.g. "SHIELD", "Galaxy S24 Ultra"), as set in its settings. */
    public static String name(Context c) {
        String n = Settings.Global.getString(c.getContentResolver(), "device_name");
        if (n == null || n.isEmpty()) n = Settings.Secure.getString(c.getContentResolver(), "bluetooth_name");
        if (n == null || n.isEmpty()) n = Build.MODEL;
        return n;
    }

    public static boolean isTv(Context c) {
        return c.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
    }

    /** Everything shared lives under the device's main storage (never app-private data). */
    public static File root() {
        return Environment.getExternalStorageDirectory();
    }

    /** Resolves a path sent by another device, refusing anything outside the shared root. */
    public static File resolve(String rel) throws IOException {
        File root = root();
        String clean = rel == null ? "" : rel.replace('\\', '/');
        StringBuilder sb = new StringBuilder();
        for (String part : clean.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new IOException("chemin refusé");
            if (sb.length() > 0) sb.append('/');
            sb.append(part);
        }
        File f = sb.length() == 0 ? root : new File(root, sb.toString());
        String r = root.getCanonicalPath();
        String p = f.getCanonicalPath();
        if (!p.equals(r) && !p.startsWith(r + File.separator)) throw new IOException("chemin refusé");
        return f;
    }

    /** The path of a local file relative to the shared root, with '/'. */
    public static String relative(File f) {
        String r = root().getAbsolutePath();
        String p = f.getAbsolutePath();
        if (p.equals(r)) return "";
        return p.startsWith(r + "/") ? p.substring(r.length() + 1) : p;
    }

    /** Deletes a file or a whole folder. */
    public static boolean delete(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) if (!delete(k)) return false;
        }
        return f.delete() || !f.exists();
    }

    public static String newToken() {
        byte[] b = new byte[24];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    // ---- Paired devices ----

    public static synchronized List<Peer> peers(Context c) {
        List<Peer> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString("peers", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                list.add(new Peer(o.getString("uuid"), o.optString("name"), o.optString("host"),
                        o.optInt("port", PORT), o.getString("token"), o.optBoolean("tv")));
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    public static synchronized Peer peer(Context c, String uuid) {
        for (Peer p : peers(c)) if (p.uuid.equals(uuid)) return p;
        return null;
    }

    /** The paired device using this token, or null: the server's only check. */
    public static synchronized Peer byToken(Context c, String token) {
        if (token == null || token.isEmpty()) return null;
        for (Peer p : peers(c)) if (p.token.equals(token)) return p;
        return null;
    }

    public static synchronized void savePeer(Context c, Peer peer) {
        List<Peer> list = peers(c);
        list.removeIf(p -> p.uuid.equals(peer.uuid));
        list.add(peer);
        write(c, list);
    }

    public static synchronized void removePeer(Context c, String uuid) {
        List<Peer> list = peers(c);
        list.removeIf(p -> p.uuid.equals(uuid));
        write(c, list);
    }

    private static void write(Context c, List<Peer> list) {
        JSONArray a = new JSONArray();
        try {
            for (Peer p : list) {
                a.put(new JSONObject().put("uuid", p.uuid).put("name", p.name).put("host", p.host)
                        .put("port", p.port).put("token", p.token).put("tv", p.tv));
            }
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString("peers", a.toString()).apply();
    }
}
