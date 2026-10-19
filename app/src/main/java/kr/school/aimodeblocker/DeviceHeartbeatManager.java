package kr.school.aimodeblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

final class DeviceHeartbeatManager {
    private static final String PREFS = "device_heartbeat";
    private static final String KEY_LAST_SENT = "last_sent";
    private static final String KEY_FALLBACK_ID = "fallback_id";
    private static final long HEARTBEAT_INTERVAL_MS = 5 * 60 * 1000L;
    private static final AtomicBoolean sending = new AtomicBoolean(false);

    private DeviceHeartbeatManager() {}

    static void maybeSend(Context context, boolean force) {
        Context app = context.getApplicationContext();
        String classCode = ClassRegistration.getClassCode(app);
        String apiBase = RemotePolicyManager.getManagementApiBase(app);

        if (classCode.isEmpty() || apiBase.isEmpty()) return;

        SharedPreferences p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long now = System.currentTimeMillis();
        long lastSent = p.getLong(KEY_LAST_SENT, 0L);

        if (!force && now - lastSent < HEARTBEAT_INTERVAL_MS) return;
        if (!sending.compareAndSet(false, true)) return;

        new Thread(() -> {
            try {
                if (sendNow(app, apiBase, classCode)) {
                    p.edit().putLong(KEY_LAST_SENT, System.currentTimeMillis()).apply();
                }
            } finally {
                sending.set(false);
            }
        }, "ai-device-heartbeat").start();
    }

    private static boolean sendNow(
            Context context,
            String apiBase,
            String classCode
    ) {
        HttpURLConnection connection = null;
        try {
            JSONObject body = new JSONObject();
            body.put("classCode", classCode);
            body.put("deviceId", getAnonymousDeviceId(context));
            body.put("registeredAt", ClassRegistration.getRegisteredAt(context));
            body.put("appVersion", BuildConfig.VERSION_NAME);
            body.put("accessibilityEnabled", AccessibilityUtils.isServiceEnabled(context));

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(apiBase + "/api/heartbeat");
            connection = (HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            connection.setRequestProperty("Accept", "application/json");
            connection.setFixedLengthStreamingMode(payload.length);

            try (OutputStream out = connection.getOutputStream()) {
                out.write(payload);
            }

            int code = connection.getResponseCode();
            return code >= 200 && code < 300;
        } catch (Exception ignored) {
            return false;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String getAnonymousDeviceId(Context context) {
        try {
            String androidId = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ANDROID_ID
            );
            if (androidId != null && !androidId.trim().isEmpty()) {
                return shortSha256("ai-blocker|" + androidId + "|" + context.getPackageName());
            }
        } catch (Exception ignored) {
        }

        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String fallback = p.getString(KEY_FALLBACK_ID, "");
        if (!fallback.isEmpty()) return fallback;

        String generated = Long.toHexString(System.nanoTime())
                + Long.toHexString(Double.doubleToLongBits(Math.random()));
        String id = shortSha256(generated);
        p.edit().putString(KEY_FALLBACK_ID, id).apply();
        return id;
    }

    private static String shortSha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < hash.length && i < 16; i++) {
                sb.append(String.format(Locale.ROOT, "%02x", hash[i]));
            }
            return sb.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
