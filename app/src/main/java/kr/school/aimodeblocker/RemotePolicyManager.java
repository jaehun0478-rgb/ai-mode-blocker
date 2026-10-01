package kr.school.aimodeblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

final class RemotePolicyManager {
    private static final String PREFS = "remote_policy";
    private static final String POLICY_URL =
            "https://raw.githubusercontent.com/jaehun0478-rgb/ai-mode-blocker/main/remote-policy.json";

    private static final String KEY_LAST_ATTEMPT = "last_attempt";
    private static final String KEY_LAST_SUCCESS = "last_success";
    private static final String KEY_ETAG = "etag";
    private static final String KEY_BLOCKED_HOSTS = "blocked_hosts";
    private static final String KEY_ALLOWED_HOSTS = "allowed_hosts";
    private static final String KEY_BLOCKING_ENABLED = "blocking_enabled";
    private static final String KEY_AUTO_DETECT = "auto_detect";
    private static final String KEY_POLICY_REVISION = "policy_revision";

    private static final long SYNC_INTERVAL_MS = 2 * 60 * 1000L;
    private static final AtomicBoolean syncing = new AtomicBoolean(false);

    private RemotePolicyManager() {}

    static boolean isManagedMode() {
        return true;
    }

    static void maybeSync(Context context, boolean force) {
        Context app = context.getApplicationContext();
        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastAttempt = p.getLong(KEY_LAST_ATTEMPT, 0L);

        if (!force && now - lastAttempt < SYNC_INTERVAL_MS) return;
        if (!syncing.compareAndSet(false, true)) return;

        p.edit().putLong(KEY_LAST_ATTEMPT, now).apply();

        new Thread(() -> {
            try {
                syncNow(app);
            } finally {
                syncing.set(false);
            }
        }, "ai-policy-sync").start();
    }

    private static void syncNow(Context context) {
        HttpURLConnection connection = null;
        try {
            SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            URL url = new URL(POLICY_URL + "?ts=" + System.currentTimeMillis());
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(6000);
            connection.setReadTimeout(6000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Cache-Control", "no-cache");

            String etag = p.getString(KEY_ETAG, null);
            if (etag != null && !etag.isEmpty()) {
                connection.setRequestProperty("If-None-Match", etag);
            }

            int code = connection.getResponseCode();
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                p.edit().putLong(KEY_LAST_SUCCESS, System.currentTimeMillis()).apply();
                return;
            }
            if (code < 200 || code >= 300) return;

            String body = readAll(connection.getInputStream());
            if (body == null || body.trim().isEmpty()) return;

            JSONObject json = new JSONObject(body);
            applyPolicy(context, json);

            String newEtag = connection.getHeaderField("ETag");
            SharedPreferences.Editor e = p.edit()
                    .putLong(KEY_LAST_SUCCESS, System.currentTimeMillis());
            if (newEtag != null) e.putString(KEY_ETAG, newEtag);
            e.apply();
        } catch (Exception ignored) {
            // Keep the last successfully downloaded policy when offline.
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static void applyPolicy(Context context, JSONObject json) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor e = p.edit();

        e.putBoolean(KEY_BLOCKING_ENABLED, json.optBoolean("blockingEnabled", true));
        e.putBoolean(KEY_AUTO_DETECT, json.optBoolean("autoDetectEnabled", true));
        e.putString(KEY_POLICY_REVISION, json.optString("revision", ""));

        JSONArray blocked = json.optJSONArray("blockedHosts");
        if (blocked != null) {
            e.putStringSet(KEY_BLOCKED_HOSTS, jsonArrayToHostSet(blocked));
        }

        JSONArray allowed = json.optJSONArray("allowedHosts");
        if (allowed != null) {
            e.putStringSet(KEY_ALLOWED_HOSTS, jsonArrayToHostSet(allowed));
        }

        JSONObject pin = json.optJSONObject("adminPin");
        if (pin != null) {
            String salt = pin.optString("salt", "");
            String hash = pin.optString("hash", "");
            int iterations = pin.optInt("iterations", 120000);
            if (!salt.isEmpty() && !hash.isEmpty()) {
                PinManager.applyManagedPin(context, salt, hash, iterations);
            }
        }

        e.apply();
        BlockPreferences.setEnabled(context, json.optBoolean("blockingEnabled", true));
    }

    static boolean isAutoDetectEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_AUTO_DETECT, true);
    }

    static boolean isRemoteBlockingEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_BLOCKING_ENABLED, true);
    }

    static long getLastSuccess(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_LAST_SUCCESS, 0L);
    }

    static String getRevision(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_POLICY_REVISION, "");
    }

    static boolean isBlockedUrl(Context context, String rawUrl) {
        String host = hostFromUrl(rawUrl);
        if (host.isEmpty()) return false;

        Set<String> hosts = getHostSet(context, KEY_BLOCKED_HOSTS);
        for (String blocked : hosts) {
            if (hostMatches(host, blocked)) return true;
        }
        return false;
    }

    static boolean isAllowedUrl(Context context, String rawUrl) {
        String host = hostFromUrl(rawUrl);
        if (host.isEmpty()) return false;

        Set<String> hosts = getHostSet(context, KEY_ALLOWED_HOSTS);
        for (String allowed : hosts) {
            if (hostMatches(host, allowed)) return true;
        }
        return false;
    }

    private static Set<String> getHostSet(Context context, String key) {
        Set<String> stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getStringSet(key, Collections.emptySet());
        return stored == null ? Collections.emptySet() : new HashSet<>(stored);
    }

    private static Set<String> jsonArrayToHostSet(JSONArray array) {
        Set<String> out = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            String value = array.optString(i, "").trim().toLowerCase(Locale.ROOT);
            value = normalizeHost(value);
            if (!value.isEmpty()) out.add(value);
        }
        return out;
    }

    private static String hostFromUrl(String rawValue) {
        if (rawValue == null) return "";
        String raw = rawValue.trim();
        if (raw.isEmpty()) return "";

        try {
            String candidate = raw.contains("://") ? raw : "https://" + raw;
            Uri uri = Uri.parse(candidate);
            return normalizeHost(uri.getHost());
        } catch (Exception ignored) {
            return "";
        }
    }

    private static String normalizeHost(String host) {
        if (host == null) return "";
        String h = host.trim().toLowerCase(Locale.ROOT);
        while (h.startsWith(".")) h = h.substring(1);
        if (h.startsWith("www.")) h = h.substring(4);
        return h;
    }

    private static boolean hostMatches(String host, String domain) {
        String h = normalizeHost(host);
        String d = normalizeHost(domain);
        return !h.isEmpty() && !d.isEmpty()
                && (h.equals(d) || h.endsWith("." + d));
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 256_000) break;
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }
}
