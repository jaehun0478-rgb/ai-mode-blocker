package kr.school.aimodeblocker;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

final class AiModeUrlMatcher {
    private AiModeUrlMatcher() {}

    static boolean shouldBlock(String rawValue) {
        if (rawValue == null) return false;
        String raw = rawValue.trim().toLowerCase(Locale.ROOT);
        if (raw.isEmpty() || !raw.contains("google.")) return false;

        String candidate = raw.contains("://") ? raw : "https://" + raw;
        try {
            URI uri = new URI(candidate);
            String host = uri.getHost();
            if (!isGoogleHost(host)) return false;

            String path = uri.getPath();
            if (path != null) {
                String cleanPath = path.toLowerCase(Locale.ROOT);
                if (cleanPath.equals("/ai") || cleanPath.startsWith("/aimode")) {
                    return true;
                }
            }

            String query = uri.getRawQuery();
            if (query != null && queryHasUdm50(query)) {
                return true;
            }
        } catch (Exception ignored) {
        }

        return raw.contains("google.") &&
                (raw.contains("/aimode") ||
                 raw.matches(".*google\\.[^/]+/ai(?:[?#/].*)?$") ||
                 raw.contains("udm=50"));
    }

    private static boolean isGoogleHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.startsWith("google.") || h.contains(".google.");
    }

    private static boolean queryHasUdm50(String rawQuery) {
        for (String pair : rawQuery.split("&")) {
            int i = pair.indexOf('=');
            String key = i >= 0 ? pair.substring(0, i) : pair;
            String value = i >= 0 ? pair.substring(i + 1) : "";
            key = decode(key);
            value = decode(value);
            if ("udm".equalsIgnoreCase(key) && "50".equals(value)) return true;
        }
        return false;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }
}
