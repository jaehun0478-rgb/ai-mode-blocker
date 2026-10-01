package kr.school.aimodeblocker;

import android.net.Uri;

import java.util.Locale;

final class AiModeUrlMatcher {
    private static final String[] BLOCKED_AI_HOSTS = new String[] {
            "chatgpt.com",
            "chat.openai.com",
            "claude.ai",
            "gemini.google.com",
            "aistudio.google.com",
            "notebooklm.google.com",
            "copilot.microsoft.com",
            "perplexity.ai",
            "poe.com",
            "grok.com",
            "deepseek.com",
            "meta.ai",
            "you.com",
            "character.ai",
            "chat.mistral.ai",
            "kimi.com",
            "qwen.ai",
            "chat.qwen.ai",
            "phind.com",
            "blackbox.ai",
            "pi.ai",
            "duck.ai",
            "z.ai",
            "wrtn.ai"
    };

    private AiModeUrlMatcher() {}

    static boolean shouldBlock(String rawValue) {
        Uri uri = parseUri(rawValue);
        if (uri == null) return false;

        String host = lower(uri.getHost());
        String path = lower(uri.getPath());

        if (isBlockedAiHost(host)) {
            return true;
        }

        // Hugging Face itself is useful for model/document pages, so block
        // only its interactive chat surface.
        if (hostMatches(host, "huggingface.co") && path.startsWith("/chat")) {
            return true;
        }

        // Preserve normal Bing search while blocking its AI/Copilot surfaces.
        if (hostMatches(host, "bing.com") &&
                (path.startsWith("/chat") || path.startsWith("/copilot"))) {
            return true;
        }

        if (!isGoogleHost(host)) return false;

        if ("/ai".equals(path) || path.startsWith("/aimode")) {
            return true;
        }

        String udm = uri.getQueryParameter("udm");
        return "50".equals(udm);
    }

    static boolean shouldForceWeb(String rawValue) {
        Uri uri = parseUri(rawValue);
        if (uri == null) return false;

        String host = lower(uri.getHost());
        if (!isGoogleHost(host)) return false;

        String path = lower(uri.getPath());
        if (!"/search".equals(path)) return false;

        String query = uri.getQueryParameter("q");
        if (query == null || query.trim().isEmpty()) return false;

        // AI Mode is handled separately. Explicit vertical searches such as
        // images, shopping, books, etc. are left untouched.
        if (uri.getQueryParameter("udm") != null) return false;
        if (uri.getQueryParameter("tbm") != null) return false;

        return true;
    }

    static String toWebUrl(String rawValue) {
        Uri uri = parseUri(rawValue);
        if (uri == null || !isGoogleHost(lower(uri.getHost()))) return null;
        return uri.buildUpon()
                .appendQueryParameter("udm", "14")
                .build()
                .toString();
    }

    private static Uri parseUri(String rawValue) {
        if (rawValue == null) return null;
        String raw = rawValue.trim();
        if (raw.isEmpty()) return null;

        String candidate = raw.contains("://") ? raw : "https://" + raw;
        try {
            Uri uri = Uri.parse(candidate);
            if (uri.getHost() == null) return null;
            return uri;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isBlockedAiHost(String host) {
        if (host == null || host.isEmpty()) return false;
        for (String blocked : BLOCKED_AI_HOSTS) {
            if (hostMatches(host, blocked)) return true;
        }
        return false;
    }

    private static boolean hostMatches(String host, String domain) {
        if (host == null || domain == null) return false;
        String h = lower(host);
        String d = lower(domain);
        return h.equals(d) || h.endsWith("." + d);
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
