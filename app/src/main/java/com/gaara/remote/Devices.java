package com.gaara.remote;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Paired TVs, kept in the app's preferences. */
final class Devices {
    static final class Device {
        String name;
        String host;
        String fingerprint;

        Device(String name, String host, String fingerprint) {
            this.name = name;
            this.host = host;
            this.fingerprint = fingerprint;
        }
    }

    private static final String PREFS = "devices";
    private static final String KEY_LIST = "list";
    private static final String KEY_LAST = "last";

    private Devices() {}

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static List<Device> all(Context c) {
        List<Device> list = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs(c).getString(KEY_LIST, "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                list.add(new Device(o.optString("name"), o.optString("host"), o.optString("fp")));
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    static Device find(Context c, String fingerprint) {
        for (Device d : all(c)) if (d.fingerprint.equals(fingerprint)) return d;
        return null;
    }

    /** Adds or updates a TV (same certificate = same TV, even at a new address). */
    static void save(Context c, Device dev) {
        List<Device> list = all(c);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).fingerprint.equals(dev.fingerprint)) {
                list.set(i, dev);
                replaced = true;
            }
        }
        if (!replaced) list.add(dev);
        write(c, list);
    }

    static void remove(Context c, String fingerprint) {
        List<Device> list = all(c);
        list.removeIf(d -> d.fingerprint.equals(fingerprint));
        write(c, list);
        if (fingerprint.equals(last(c))) prefs(c).edit().remove(KEY_LAST).apply();
    }

    static void clear(Context c) {
        prefs(c).edit().clear().apply();
    }

    static void setLast(Context c, String fingerprint) {
        prefs(c).edit().putString(KEY_LAST, fingerprint).apply();
    }

    static String last(Context c) {
        return prefs(c).getString(KEY_LAST, null);
    }

    private static void write(Context c, List<Device> list) {
        JSONArray a = new JSONArray();
        try {
            for (Device d : list) {
                a.put(new JSONObject().put("name", d.name).put("host", d.host).put("fp", d.fingerprint));
            }
        } catch (JSONException ignored) {
        }
        prefs(c).edit().putString(KEY_LIST, a.toString()).apply();
    }
}
