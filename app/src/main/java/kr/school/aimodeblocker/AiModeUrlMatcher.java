package kr.school.aimodeblocker;

import android.net.Uri;

import java.util.Locale;

final class AiModeUrlMatcher {
    private AiModeUrlMatcher() {}

    static boolean shouldBlock(String rawValue) {
        Uri uri = parseGoogleUri(rawValue);
        if (uri == null) return false;

        String path = lower(uri.getPath());
        if ("/ai".equals(path) || path.startsWith("/aimode")) {
            return true;
        }

        String udm = uri.getQueryParameter("udm");
        return "50".equals(udm);
    }

    static boolean shouldForceWeb(String rawValue) {
        Uri uri = parseGoogleUri(rawValue);
        if (uri == null) return false;

        String path = lower(uri.getPath());
        if (!"/search".equals(path)) return false;

        String query = uri.getQueryParameter("q");
        if (query == null || query.trim().isEmpty()) return false;

        // AI Mode is handled separately and any explicit Google search vertical
        // (images, shopping, etc.) should be left alone.
        if (uri.getQueryParameter("udm") != null) return false;
        if (uri.getQueryParameter("tbm") != null) return false;

        return true;
    }

    static String toWebUrl(String rawValue) {
        Uri uri = parseGoogleUri(rawValue);
        if (uri == null) return null;
        return uri.buildUpon()
                .appendQueryParameter("udm", "14")
                .build()
                .toString();
    }

    private static Uri parseGoogleUri(String rawValue) {
        if (rawValue == null) return null;
        String raw = rawValue.trim();
        if (raw.isEmpty()) return null;

        String candidate = raw.contains("://") ? raw : "https://" + raw;
        try {
            Uri uri = Uri.parse(candidate);
            String host = lower(uri.getHost());
            if (!isGoogleHost(host)) return null;
            return uri;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isGoogleHost(String host) {
        if (host == null || host.isEmpty()) return false;
        return host.startsWith("google.") ||
                host.startsWith("www.google.") ||
                host.contains(".google.");
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }
}
