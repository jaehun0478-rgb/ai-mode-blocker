package kr.school.aimodeblocker;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

public class AiModeAccessibilityService extends AccessibilityService {
    private static final String CHROME = "com.android.chrome";
    private static final String URL_BAR_ID = "com.android.chrome:id/url_bar";
    private static final long CHECK_THROTTLE_MS = 120L;
    private static final long ACTION_DEBOUNCE_MS = 1600L;

    private long lastCheckAt = 0L;
    private long lastActionAt = 0L;
    private String lastActionKey = "";

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || !BlockPreferences.isEnabled(this)) return;

        CharSequence pkg = event.getPackageName();
        if (pkg == null || !CHROME.contentEquals(pkg)) return;

        long now = System.currentTimeMillis();
        if (now - lastCheckAt < CHECK_THROTTLE_MS) return;
        lastCheckAt = now;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String currentUrl = readChromeUrl(root);
        if (currentUrl == null || currentUrl.isEmpty()) return;

        if (AiModeUrlMatcher.shouldBlock(currentUrl)) {
            if (isDuplicateAction("block:" + currentUrl, now)) return;
            rememberAction("block:" + currentUrl, now);
            performGlobalAction(GLOBAL_ACTION_BACK);
            Toast.makeText(this, getString(R.string.blocked_message), Toast.LENGTH_SHORT).show();
            return;
        }

        if (AiModeUrlMatcher.shouldForceWeb(currentUrl)) {
            if (isDuplicateAction("web:" + currentUrl, now)) return;

            AccessibilityNodeInfo webTab = findWebFilter(root);
            if (webTab != null) {
                rememberAction("web:" + currentUrl, now);
                if (clickNodeOrParent(webTab)) {
                    return;
                }
            }
        }
    }

    private String readChromeUrl(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> nodes;
        try {
            nodes = root.findAccessibilityNodeInfosByViewId(URL_BAR_ID);
        } catch (Exception e) {
            return null;
        }
        if (nodes == null || nodes.isEmpty()) return null;

        for (AccessibilityNodeInfo node : nodes) {
            if (node != null && node.getText() != null) {
                return node.getText().toString();
            }
        }
        return null;
    }

    private AccessibilityNodeInfo findWebFilter(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> candidates = new ArrayList<>();
        addAll(candidates, root.findAccessibilityNodeInfosByText("웹"));
        addAll(candidates, root.findAccessibilityNodeInfosByText("Web"));

        AccessibilityNodeInfo best = null;
        int bestTop = Integer.MAX_VALUE;

        for (AccessibilityNodeInfo node : candidates) {
            if (node == null || !node.isVisibleToUser()) continue;

            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            String label = text != null ? text.toString().trim() :
                    (desc != null ? desc.toString().trim() : "");

            if (!("웹".equals(label) || "Web".equalsIgnoreCase(label))) continue;

            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);

            // Search filters are positioned near the top of Google results.
            if (bounds.top >= 0 && bounds.top < bestTop) {
                best = node;
                bestTop = bounds.top;
            }
        }
        return best;
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
