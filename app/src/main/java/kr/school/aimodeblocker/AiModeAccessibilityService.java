package kr.school.aimodeblocker;

import android.accessibilityservice.AccessibilityService;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class AiModeAccessibilityService extends AccessibilityService {
    private static final String CHROME = "com.android.chrome";
    private static final String URL_BAR_ID = "com.android.chrome:id/url_bar";
    private static final long CHECK_THROTTLE_MS = 100L;
    private static final long BLOCK_DEBOUNCE_MS = 1400L;

    private long lastCheckAt = 0L;
    private long lastBlockedAt = 0L;
    private String lastBlockedUrl = "";

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

        List<AccessibilityNodeInfo> nodes;
        try {
            nodes = root.findAccessibilityNodeInfosByViewId(URL_BAR_ID);
        } catch (Exception e) {
            return;
        }
        if (nodes == null || nodes.isEmpty()) return;

        for (AccessibilityNodeInfo node : nodes) {
            if (node == null || node.getText() == null) continue;
            String value = node.getText().toString();
            if (!AiModeUrlMatcher.shouldBlock(value)) continue;

            if (value.equals(lastBlockedUrl) && now - lastBlockedAt < BLOCK_DEBOUNCE_MS) return;
            lastBlockedUrl = value;
            lastBlockedAt = now;

            performGlobalAction(GLOBAL_ACTION_BACK);
            Toast.makeText(this, getString(R.string.blocked_message), Toast.LENGTH_SHORT).show();
            return;
        }
    }

    @Override
    public void onInterrupt() {
    }
}
