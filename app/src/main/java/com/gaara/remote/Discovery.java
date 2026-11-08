package com.gaara.remote;

import android.content.Context;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Finds Android / Google TVs on the Wi-Fi: they announce "_androidtvremote2._tcp".
 * NsdManager resolves one service at a time, so resolutions are queued.
 */
@SuppressWarnings("deprecation")
final class Discovery {
    static final String SERVICE = "_androidtvremote2._tcp";

    interface Listener {
        void onFound(String name, String host);
    }

    private final NsdManager nsd;
    private final WifiManager.MulticastLock lock;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Deque<NsdServiceInfo> queue = new ArrayDeque<>();
    private boolean resolving;
    private NsdManager.DiscoveryListener discovery;

    Discovery(Context ctx, Listener listener) {
        this.nsd = (NsdManager) ctx.getSystemService(Context.NSD_SERVICE);
        WifiManager wifi = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        this.lock = wifi.createMulticastLock("remote-discovery");
        this.lock.setReferenceCounted(false);
        this.listener = listener;
    }

    void start() {
        if (discovery != null) return;
        lock.acquire();
        discovery = new NsdManager.DiscoveryListener() {
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int code) {}
            @Override public void onStopDiscoveryFailed(String type, int code) {}
            @Override public void onServiceLost(NsdServiceInfo info) {}

            @Override
            public void onServiceFound(NsdServiceInfo info) {
                synchronized (queue) {
                    queue.add(info);
                }
                resolveNext();
            }
        };
        nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery);
    }

    void stop() {
        if (discovery != null) {
            try {
                nsd.stopServiceDiscovery(discovery);
            } catch (IllegalArgumentException ignored) {
            }
            discovery = null;
        }
        synchronized (queue) {
            queue.clear();
        }
        if (lock.isHeld()) lock.release();
    }

    private void resolveNext() {
        NsdServiceInfo next;
        synchronized (queue) {
            if (resolving || queue.isEmpty()) return;
            resolving = true;
            next = queue.poll();
        }
        nsd.resolveService(next, new NsdManager.ResolveListener() {
            @Override
            public void onResolveFailed(NsdServiceInfo info, int code) {
                done();
            }

            @Override
            public void onServiceResolved(NsdServiceInfo info) {
                if (info.getHost() != null) {
                    String host = info.getHost().getHostAddress();
                    String name = info.getServiceName();
                    main.post(() -> listener.onFound(name, host));
                }
                done();
            }
        });
    }

    private void done() {
        synchronized (queue) {
            resolving = false;
        }
        resolveNext();
    }
}
