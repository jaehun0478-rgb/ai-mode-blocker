package kr.school.aimodeblocker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class AiModeAccessibilityService extends AccessibilityService {
    private static final String CHROME = "com.android.chrome";
    private static final String GOOGLE_APP = "com.google.android.googlequicksearchbox";
    private static final String URL_BAR_ID = "com.android.chrome:id/url_bar";

    private static final long CHECK_THROTTLE_MS = 100L;
    private static final long ACTION_DEBOUNCE_MS = 1200L;

    private long lastCheckAt = 0L;
    private long lastActionAt = 0L;
    private String lastActionKey = "";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) info = new AccessibilityServiceInfo();

        info.packageNames = new String[]{CHROME, GOOGLE_APP};
        info.eventTypes =
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED |
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED |
                AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED |
                AccessibilityEvent.TYPE_VIEW_CLICKED |
                AccessibilityEvent.TYPE_VIEW_SCROLLED;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        }
        info.notificationTimeout = 80;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !BlockPreferences.isEnabled(this)) return;

        long now = System.currentTimeMillis();
        if (now - lastCheckAt < CHECK_THROTTLE_MS) return;
        lastCheckAt = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String pkg = "";
        if (event.getPackageName() != null) {
            pkg = event.getPackageName().toString();
        }
        if (pkg.isEmpty() && root.getPackageName() != null) {
            pkg = root.getPackageName().toString();
        }

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
            blockWithBack("block:" + currentUrl, now);
            return;
        }

        if (AiModeUrlMatcher.shouldForceWeb(currentUrl)) {
            String webUrl = AiModeUrlMatcher.toWebUrl(currentUrl);
            if (webUrl == null || isDuplicateAction("web:" + webUrl, now)) return;

            if (navigateCurrentChromeTab(urlBar, webUrl)) {
                rememberAction("web:" + webUrl, now);
                return;
            }

            AccessibilityNodeInfo webTab = findExactTextNode(root, "웹", "Web");
            if (webTab != null && clickNodeOrParent(webTab)) {
                rememberAction("web-tab:" + currentUrl, now);
            }
        }
    }

    private void handleGoogleApp(AccessibilityEvent event, AccessibilityNodeInfo root, long now) {
        // 1) Intercept the AI Mode button/tab when Google exposes a click event.
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo source = event.getSource();
            String clicked = combinedNodeLabel(source);
            if (isAiModeLabel(clicked) || ancestorHasAiModeLabel(source)) {
                blockWithBack("google-ai-click", now);
                return;
            }

            if (event.getText() != null) {
                for (CharSequence t : event.getText()) {
                    if (isAiModeLabel(t == null ? "" : t.toString())) {
                        blockWithBack("google-ai-click", now);
                        return;
                    }
                }
            }
        }

        // 2) Some Google-app builds don't expose the AI button click reliably.
        // Detect the loaded AI Mode page itself and immediately leave it.
        String screenText = collectScreenText(root, 700);
        if (looksLikeAiModePage(screenText)) {
            blockWithBack("google-ai-page", now);
            return;
        }

        // 3) Normal Google-app search: if an AI Overview is present, switch to
        // the Web filter in the same result screen.
        if (hasAiOverview(screenText)) {
            if (isDuplicateAction("google-web-filter", now)) return;

            AccessibilityNodeInfo webTab = findExactTextNode(root, "웹", "Web");
            if (webTab != null && clickNodeOrParent(webTab)) {
                rememberAction("google-web-filter", now);
                return;
            }

            // Fallback: recover the visible search query and reopen the same
            // Google search as Web-only results in the Google app.
            String query = findLikelyGoogleQuery(root);
            if (query != null && !query.isEmpty()) {
                Uri webUri = new Uri.Builder()
                        .scheme("https")
                        .authority("www.google.com")
                        .path("/search")
                        .appendQueryParameter("q", query)
                        .appendQueryParameter("udm", "14")
                        .build();

                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, webUri);
                    intent.setPackage(GOOGLE_APP);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    rememberAction("google-web-fallback:" + query, now);
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void blockWithBack(String key, long now) {
        if (isDuplicateAction(key, now)) return;
        rememberAction(key, now);
        performGlobalAction(GLOBAL_ACTION_BACK);
        Toast.makeText(this, getString(R.string.blocked_message), Toast.LENGTH_SHORT).show();
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
                return urlBar.performAction(
                        AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()
                );
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private AccessibilityNodeInfo findExactTextNode(
            AccessibilityNodeInfo root,
            String... labels
    ) {
        for (String label : labels) {
            List<AccessibilityNodeInfo> nodes = safeFindByText(root, label);
            for (AccessibilityNodeInfo node : nodes) {
                if (node == null || !node.isVisibleToUser()) continue;
                String nodeLabel = combinedNodeLabel(node);
                if (label.equals(nodeLabel)
                        || label.equalsIgnoreCase(nodeLabel)
                        || nodeLabel.startsWith(label + ",")) {
                    return node;
                }
            }
        }
        return null;
    }

    private String findLikelyGoogleQuery(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);

        String fallback = null;

        for (int i = 0; i < queue.size() && i < 900; i++) {
            AccessibilityNodeInfo node = queue.get(i);
            if (node == null) continue;

            CharSequence classNameCs = node.getClassName();
            String className = classNameCs == null ? "" : classNameCs.toString();
            CharSequence textCs = node.getText();

            if (textCs != null) {
                String text = textCs.toString().trim();
                if (!text.isEmpty() && text.length() <= 300) {
                    if (className.contains("EditText") || node.isEditable()) {
                        return text;
                    }
                    if (fallback == null && className.contains("TextView")) {
                        fallback = text;
                    }
                }
            }

            for (int c = 0; c < node.getChildCount(); c++) {
                AccessibilityNodeInfo child = node.getChild(c);
                if (child != null) queue.add(child);
            }
        }
        return fallback;
    }

    private String collectScreenText(AccessibilityNodeInfo root, int maxNodes) {
        StringBuilder sb = new StringBuilder();
        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);

        for (int i = 0; i < queue.size() && i < maxNodes; i++) {
            AccessibilityNodeInfo node = queue.get(i);
            if (node == null) continue;

            appendText(sb, node.getText());
            appendText(sb, node.getContentDescription());
            appendText(sb, node.getHintText());

            for (int c = 0; c < node.getChildCount(); c++) {
                AccessibilityNodeInfo child = node.getChild(c);
                if (child != null) queue.add(child);
            }
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private void appendText(StringBuilder sb, CharSequence text) {
        if (text == null) return;
        String value = text.toString().trim();
        if (!value.isEmpty()) {
            sb.append(' ').append(value);
        }
    }

    private boolean looksLikeAiModePage(String screenText) {
        if (screenText == null || screenText.isEmpty()) return false;

        boolean hasAiMode =
                screenText.contains("ai 모드") ||
                screenText.contains("ai mode");

        boolean hasAiModePageSignature =
                screenText.contains("무엇이든 물어보세요") ||
                screenText.contains("ask anything") ||
                screenText.contains("ai 모드 기록") ||
                screenText.contains("ai mode history");

        return hasAiMode && hasAiModePageSignature;
    }

    private boolean hasAiOverview(String screenText) {
        if (screenText == null) return false;
        return screenText.contains("ai 개요")
                || screenText.contains("ai overview")
                || screenText.contains("ai로 생성됨")
                || screenText.contains("generative ai is experimental");
    }

    private boolean isAiModeLabel(String label) {
        if (label == null) return false;
        String clean = label.trim().toLowerCase(Locale.ROOT);
        return clean.equals("ai 모드")
                || clean.equals("ai mode")
                || clean.startsWith("ai 모드,")
                || clean.startsWith("ai mode,");
    }

    private boolean ancestorHasAiModeLabel(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 5; depth++) {
            if (isAiModeLabel(combinedNodeLabel(current))) return true;
            current = current.getParent();
        }
        return false;
    }

    private String combinedNodeLabel(AccessibilityNodeInfo node) {
        if (node == null) return "";
        StringBuilder sb = new StringBuilder();

        if (node.getText() != null) {
            sb.append(node.getText().toString().trim());
        }
        if (node.getContentDescription() != null) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(node.getContentDescription().toString().trim());
        }
        return sb.toString().trim();
    }

    private List<AccessibilityNodeInfo> safeFindByText(
            AccessibilityNodeInfo root,
            String text
    ) {
        try {
            List<AccessibilityNodeInfo> nodes =
                    root.findAccessibilityNodeInfosByText(text);
            return nodes == null ? new ArrayList<>() : nodes;
        } catch (Exception ignored) {
            return new ArrayList<>();
        }
    }

    private boolean clickNodeOrParent(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 7; depth++) {
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
