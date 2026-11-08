package com.gaara.remote.share;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONObject;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.InterfaceAddress;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Runs this device's sharing: HTTP server, "I'm here" UDP beacons every 3 s,
 * the list of other copies of the app seen on the Wi-Fi, and the pairing code.
 */
public final class ShareHub {
    /** Another copy of the app seen on the network (paired or not). */
    public static final class Seen {
        public final String uuid, name, host;
        public final int port;
        public final boolean tv;
        final long at;

        Seen(String uuid, String name, String host, int port, boolean tv) {
            this.uuid = uuid;
            this.name = name;
            this.host = host;
            this.port = port;
            this.tv = tv;
            this.at = SystemClock.elapsedRealtime();
        }
    }

    public interface Listener {
        void onShareChanged();
    }

    private static ShareHub instance;

    public static synchronized ShareHub get(Context c) {
        if (instance == null) instance = new ShareHub(c.getApplicationContext());
        return instance;
    }

    private final Context ctx;
    private final String uuid, name;
    private final boolean tv;
    private final ShareServer server;
    private final Map<String, Seen> seen = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WifiManager.MulticastLock lock;
    private volatile String code;
    private int wrongCodes;
    private volatile boolean running;
    private DatagramSocket beaconSocket;
    private int users;

    private ShareHub(Context ctx) {
        this.ctx = ctx;
        uuid = Share.uuid(ctx);
        name = Share.name(ctx);
        tv = Share.isTv(ctx);
        lock = ((WifiManager) ctx.getSystemService(Context.WIFI_SERVICE)).createMulticastLock("share");
        lock.setReferenceCounted(false);
        newCode();
        server = new ShareServer(ctx, new ShareServer.Hooks() {
            @Override
            public Share.Peer pair(String c, JSONObject who, String host) {
                return tryPair(c, who, host);
            }

            @Override
            public JSONObject info() {
                return myInfo();
            }
        });
    }

    public String uuid() { return uuid; }
    public String name() { return name; }
    public boolean tv() { return tv; }

    /** The 6-digit code another device must type to pair with this one. */
    public String code() {
        return code;
    }

    JSONObject myInfo() {
        try {
            return new JSONObject().put("app", "telecommande").put("uuid", uuid).put("name", name)
                    .put("port", Share.PORT).put("tv", tv);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void newCode() {
        code = String.format("%06d", new SecureRandom().nextInt(1000000));
        changed();
    }

    private synchronized Share.Peer tryPair(String c, JSONObject who, String host) {
        if (c == null || !c.equals(code)) {
            if (++wrongCodes >= 5) { // too many guesses: a new code on screen
                wrongCodes = 0;
                newCode();
            }
            return null;
        }
        wrongCodes = 0;
        newCode(); // a code works once
        Share.Peer p = new Share.Peer(who.optString("uuid"), who.optString("name", host), host,
                who.optInt("port", Share.PORT), Share.newToken(), who.optBoolean("tv"));
        Share.savePeer(ctx, p);
        changed();
        return p;
    }

    // ---- Start / stop (counted: the TV service and the screens can all hold it) ----

    public synchronized void start() {
        users++;
        if (running) return;
        running = true;
        lock.acquire();
        try {
            server.start();
        } catch (Exception ignored) {
            // Port busy (another copy?): browsing other devices still works.
        }
        new Thread(this::beaconLoop, "share-beacon").start();
        new Thread(this::listenLoop, "share-listen").start();
    }

    public synchronized void stop() {
        if (--users > 0) return;
        users = 0;
        running = false;
        server.stop();
        if (beaconSocket != null) beaconSocket.close();
        if (lock.isHeld()) lock.release();
    }

    // ---- Discovery ----

    /** Copies of the app seen in the last 12 seconds. */
    public List<Seen> seen() {
        long now = SystemClock.elapsedRealtime();
        List<Seen> out = new ArrayList<>();
        synchronized (seen) {
            for (Seen s : seen.values()) if (now - s.at < 12000) out.add(s);
        }
        return out;
    }

    /** Where a paired device is now: its beacon address, else the last one known. */
    public String hostOf(Share.Peer p) {
        synchronized (seen) {
            Seen s = seen.get(p.uuid);
            if (s != null && SystemClock.elapsedRealtime() - s.at < 12000) return s.host;
        }
        return p.host;
    }

    public boolean online(Share.Peer p) {
        synchronized (seen) {
            Seen s = seen.get(p.uuid);
            return s != null && SystemClock.elapsedRealtime() - s.at < 12000;
        }
    }

    private void beaconLoop() {
        byte[] msg = myInfo().toString().getBytes(StandardCharsets.UTF_8);
        while (running) {
            try (DatagramSocket s = new DatagramSocket()) {
                s.setBroadcast(true);
                for (InetAddress b : broadcasts()) s.send(new DatagramPacket(msg, msg.length, b, Share.BEACON_PORT));
            } catch (Exception ignored) {
            }
            SystemClock.sleep(3000);
            changed(); // refresh "online" dots as beacons age
        }
    }

    private void listenLoop() {
        try (DatagramSocket s = new DatagramSocket(null)) {
            s.setReuseAddress(true);
            s.bind(new java.net.InetSocketAddress(Share.BEACON_PORT));
            beaconSocket = s;
            byte[] buf = new byte[2048];
            while (running) {
                DatagramPacket p = new DatagramPacket(buf, buf.length);
                s.receive(p);
                try {
                    JSONObject o = new JSONObject(new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8));
                    if (!"telecommande".equals(o.optString("app")) || uuid.equals(o.optString("uuid"))) continue;
                    Seen d = new Seen(o.getString("uuid"), o.optString("name"), p.getAddress().getHostAddress(),
                            o.optInt("port", Share.PORT), o.optBoolean("tv"));
                    boolean isNew;
                    synchronized (seen) {
                        isNew = !seen.containsKey(d.uuid);
                        seen.put(d.uuid, d);
                    }
                    if (isNew) changed();
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static List<InetAddress> broadcasts() throws Exception {
        List<InetAddress> out = new ArrayList<>();
        Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
        while (ifs.hasMoreElements()) {
            NetworkInterface ni = ifs.nextElement();
            if (!ni.isUp() || ni.isLoopback()) continue;
            for (InterfaceAddress a : ni.getInterfaceAddresses()) {
                if (a.getBroadcast() != null) out.add(a.getBroadcast());
            }
        }
        if (out.isEmpty()) out.add(InetAddress.getByName("255.255.255.255"));
        return out;
    }

    // ---- Listeners ----

    public void addListener(Listener l) {
        listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public void changed() {
        main.post(() -> {
            for (Listener l : listeners) l.onShareChanged();
        });
    }
}
