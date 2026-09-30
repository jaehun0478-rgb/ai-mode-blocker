package kr.school.aimodeblocker;

import android.accessibilityservice.AccessibilityService;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class AiModeAccessibilityService extends AccessibilityService {
    private static final String CHROME = "com.android.chrome";
    private static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";
    private static final String URL_BAR_ID = "com.android.chrome:id/url_bar";

    private static final long CHECK_THROTTLE_MS = 120L;
    private static final long ACTION_DEBOUNCE_MS = 1400L;

    private long lastCheckAt = 0L;
    private long lastActionAt = 0L;
    private String lastActionKey = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !BlockPreferences.isEnabled(this)) return;

        CharSequence pkgCs = event.getPackageName();
        if (pkgCs == null) return;
        String pkg = pkgCs.toString();

        if (!CHROME.equals(pkg) && !GOOGLE_APP.equals(pkg)) return;

        long now = System.currentTimeMillis();
        if (now - lastCheckAt < CHECK_THROTTLE_MS) return;
        lastCheckAt = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        if (CHROME.equals(pkg)) {
            handleChrome(root, now);
        } else if (GOOGLE_APP.equals(pkg)) {
            handleGoogleApp(event, root, now);
        }
    }

    private void handleChrome(AccessibilityNodeInfo root, long now) {
        AccessibilityNodeInfo urlBar = findChromeUrlBar(root);
        if (urlBar == null || urlBar.getText() == null) return;

        String currentUrl = urlBar.getText().toString();
        if (currentUrl.isEmpty()) return;

        if (AiModeUrlMatcher.shouldBlock(currentUrl)) {
            if (isDuplicateAction("block:" + currentUrl, now)) return;
            rememberAction("block:" + currentUrl, now);
            performGlobalAction(GLOBAL_ACTION_BACK);
            Toast.makeText(this, getString(R.string.blocked_message), Toast.LENGTH_SHORT).show();
            return;
        }

        if (AiModeUrlMatcher.shouldForceWeb(currentUrl)) {
            String webUrl = AiModeUrlMatcher.toWebUrl(currentUrl);
            if (webUrl == null || isDuplicateAction("web:" + webUrl, now)) return;

            if (navigateCurrentChromeTab(urlBar, webUrl)) {
                rememberAction("web:" + webUrl, now);
                return;
            }

            // Fallback for Chrome builds where the omnibox cannot receive
            // ACTION_SET_TEXT while a page is displayed.
            AccessibilityNodeInfo webTab = findWebFilter(root);
            if (webTab != null && clickNodeOrParent(webTab)) {
                rememberAction("web-tab:" + currentUrl, now);
            }
        }
    }

    private void handleGoogleApp(AccessibilityEvent event, AccessibilityNodeInfo root, long now) {
        // Google app does not expose a Chrome-style URL bar. Intercept the
        // AI Mode tab/button directly from the accessibility event.
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            String clicked = nodeLabel(event.getSource());
            if (isAiModeLabel(clicked)) {
                if (!isDuplicateAction("google-ai-mode", now)) {
                    rememberAction("google-ai-mode", now);
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    Toast.makeText(this, getString(R.string.blocked_message), Toast.LENGTH_SHORT).show();
                }
                return;
            }

            if (event.getText() != null) {
                for (CharSequence t : event.getText()) {
                    if (isAiModeLabel(t == null ? "" : t.toString())) {
                        if (!isDuplicateAction("google-ai-mode", now)) {
                            rememberAction("google-ai-mode", now);
                            performGlobalAction(GLOBAL_ACTION_BACK);
                            Toast.makeText(this, getString(R.string.blocked_message), Toast.LENGTH_SHORT).show();
                        }
                        return;
                    }
                }
            }
        }

        // If an AI Overview is present in normal Google-app search results,
        // move to the Web filter in the same result screen.
        if (containsAnyText(root,
                "AI 개요",
                "AI Overview",
                "AI로 생성됨",
                "Generative AI is experimental")) {

            AccessibilityNodeInfo webTab = findWebFilter(root);
            if (webTab != null && !isDuplicateAction("google-web-filter", now)) {
                if (clickNodeOrParent(webTab)) {
                    rememberAction("google-web-filter", now);
                }
            }
        }
    }

    private AccessibilityNodeInfo findChromeUrlBar(AccessibilityNodeInfo root) {
        try {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByViewId(URL_BAR_ID);
            if (nodes == null) return null;
            for (AccessibilityNodeInfo node : nodes) {
                if (node != null) return node;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private boolean navigateCurrentChromeTab(AccessibilityNodeInfo urlBar, String newUrl) {
        try {
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                    newUrl
            );

            urlBar.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            boolean textSet = urlBar.performAction(
                    AccessibilityNodeInfo.ACTION_SET_TEXT,
                    args
            );
            if (!textSet) return false;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return urlBar.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            }

            return false;
        } catch (Exception ignored) {
            return false;
        }
    }

    private AccessibilityNodeInfo findWebFilter(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        addAll(candidates, safeFindByText(root, "웹"));
        addAll(candidates, safeFindByText(root, "Web"));

        for (AccessibilityNodeInfo node : candidates) {
            if (node == null || !node.isVisibleToUser()) continue;

            String label = nodeLabel(node);
            if ("웹".equals(label) || "Web".equalsIgnoreCase(label)) {
                return node;
            }
        }
        return null;
    }

    private boolean containsAnyText(AccessibilityNodeInfo root, String... texts) {
        for (String text : texts) {
            List<AccessibilityNodeInfo> nodes = safeFindByText(root, text);
            if (nodes != null && !nodes.isEmpty()) return true;
        }
        return false;
    }

    private List<AccessibilityNodeInfo> safeFindByText(AccessibilityNodeInfo root, String text) {
        try {
            return root.findAccessibilityNodeInfosByText(text);
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private String nodeLabel(AccessibilityNodeInfo node) {
        if (node == null) return "";
        if (node.getText() != null) return node.getText().toString().trim();
        if (node.getContentDescription() != null) {
            return node.getContentDescription().toString().trim();
        }
        return "";
    }

    private boolean isAiModeLabel(String label) {
        if (label == null) return false;
        String clean = label.trim();
        return "AI 모드".equals(clean)
                || "AI Mode".equalsIgnoreCase(clean)
                || clean.startsWith("AI 모드,")
                || clean.toLowerCase().startsWith("ai mode,");
    }

    private void addAll(List<AccessibilityNodeInfo> target, List<AccessibilityNodeInfo> source) {
        if (source != null) target.addAll(source);
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (current.isClickable()) {
                return current.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            current = current.getParent();
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private boolean isDuplicateAction(String key, long now) {
        return key.equals(lastActionKey) && now - lastActionAt < ACTION_DEBOUNCE_MS;
    }

    private void rememberAction(String key, long now) {
        lastActionKey = key;
        lastActionAt = now;
    }

    @Override
    public void onInterrupt() {
    }
}
