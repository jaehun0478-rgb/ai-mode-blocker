package kr.school.aimodeblocker;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.net.Uri;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class AiModeAccessibilityService extends AccessibilityService {
    private static final String CHROME = "com.android.chrome";
    private static final String URL_BAR_ID = "com.android.chrome:id/url_bar";
    private static final long CHECK_THROTTLE_MS = 100L;
    private static final long ACTION_DEBOUNCE_MS = 1200L;

    private long lastCheckAt = 0L;
    private long lastActionAt = 0L;
    private String lastActionUrl = "";

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

            if (AiModeUrlMatcher.shouldBlock(value)) {
                if (isDuplicateAction(value, now)) return;
                rememberAction(value, now);
                performGlobalAction(GLOBAL_ACTION_BACK);
                Toast.makeText(this, getString(R.string.blocked_message), Toast.LENGTH_SHORT).show();
                return;
            }

            if (AiModeUrlMatcher.shouldForceWeb(value)) {
                String webUrl = AiModeUrlMatcher.toWebUrl(value);
                if (webUrl == null || isDuplicateAction(webUrl, now)) return;

                rememberAction(webUrl, now);

                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(webUrl));
                    intent.setPackage(CHROME);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                } catch (Exception ignored) {
                }
                return;
            }
        }
    }

    private boolean isDuplicateAction(String url, long now) {
        return url.equals(lastActionUrl) && now - lastActionAt < ACTION_DEBOUNCE_MS;
    }

    private void rememberAction(String url, long now) {
        lastActionUrl = url;
        lastActionAt = now;
    }

    @Override
    public void onInterrupt() {
    }
}
