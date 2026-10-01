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
    private static final String NAVER_APP = "com.nhn.android.search";
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

        info.packageNames = new String[]{CHROME, GOOGLE_APP, NAVER_APP};
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
            handleChrome(event, root, now);
        } else if (GOOGLE_APP.equals(pkg)) {
            handleGoogleApp(event, root, now);
        } else if (NAVER_APP.equals(pkg)) {
            handleNaverApp(event, root, now);
        }
    }

    private void handleChrome(AccessibilityEvent event, AccessibilityNodeInfo root, long now) {
        AccessibilityNodeInfo urlBar = findChromeUrlBar(root);
        if (urlBar == null || urlBar.getText() == null) return;

        String currentUrl = urlBar.getText().toString();
        if (currentUrl.isEmpty()) return;

        if (isNaverUrl(currentUrl) && event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo source = event.getSource();
            String clicked = combinedNodeLabel(source);

            if (isNaverAiLabel(clicked) || ancestorHasNaverAiLabel(source)) {
                blockWithBack("naver-web-ai-click", now);
                return;
            }

            if (event.getText() != null) {
                for (CharSequence t : event.getText()) {
                    if (isNaverAiLabel(t == null ? "" : t.toString())) {
                        blockWithBack("naver-web-ai-click", now);
                        return;
                    }
                }
            }
        }

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
        // 1) Block AI Mode button/tab when a click event is exposed.
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

        String screenText = collectScreenText(root, 900);

        // 2) If AI Mode has already opened, immediately leave the page.
        if (looksLikeAiModePage(screenText)) {
            blockWithBack("google-ai-page", now);
            return;
        }

        // 3) Do not wait for an AI Overview label. On every normal Google-app
        // search results screen, force the Web filter. Google documents that
        // the Web filter omits AI Overview-style features.
        AccessibilityNodeInfo webTab = findExactTextNode(root, "웹", "Web");
        if (webTab != null) {
            if (isWebTabAlreadySelected(webTab)) {
                return;
            }

            if (!isDuplicateAction("google-force-web", now)
                    && clickNodeOrParent(webTab)) {
                rememberAction("google-force-web", now);
                return;
            }
        }

        // 4) Fallback for Google-app builds that do not expose the Web tab to
        // accessibility. Recover the visible query and open Web-only results
        // in Chrome, whose URL bar handling is reliable.
        String query = findEditableGoogleQuery(root);
        if (query != null && !query.isEmpty()
                && looksLikeSearchResults(screenText)
                && !isDuplicateAction("google-web-fallback:" + query, now)) {

            Uri webUri = new Uri.Builder()
                    .scheme("https")
                    .authority("www.google.com")
                    .path("/search")
                    .appendQueryParameter("q", query)
                    .appendQueryParameter("udm", "14")
                    .build();

            try {
                Intent intent = new Intent(Intent.ACTION_VIEW, webUri);
                intent.setPackage(CHROME);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                rememberAction("google-web-fallback:" + query, now);
            } catch (Exception ignored) {
            }
        }
    }


    private void handleNaverApp(AccessibilityEvent event, AccessibilityNodeInfo root, long now) {
        // Block direct AI-tab buttons inside the NAVER Android app.
        if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            AccessibilityNodeInfo source = event.getSource();
            String clicked = combinedNodeLabel(source);

            if (isNaverAiLabel(clicked) || ancestorHasNaverAiLabel(source)) {
                blockWithBack("naver-app-ai-click", now);
                return;
            }

            if (event.getText() != null) {
                for (CharSequence t : event.getText()) {
                    if (isNaverAiLabel(t == null ? "" : t.toString())) {
                        blockWithBack("naver-app-ai-click", now);
                        return;
                    }
                }
            }
        }

        // Fallback: if an AI conversation screen was opened without a clean
        // click event, detect characteristic AI-tab text and leave it.
        String screenText = collectScreenText(root, 900);
        if (looksLikeNaverAiPage(screenText)) {
            blockWithBack("naver-app-ai-page", now);
        }
    }

    private boolean isNaverUrl(String rawUrl) {
        if (rawUrl == null) return false;
        String lower = rawUrl.trim().toLowerCase(Locale.ROOT);
        return lower.contains("naver.com")
                || lower.contains("search.naver.com")
                || lower.contains("m.naver.com");
    }

    private boolean isNaverAiLabel(String label) {
        if (label == null) return false;
        String clean = label.trim().toLowerCase(Locale.ROOT)
                .replace(" ", "");

        return clean.equals("ai탭")
                || clean.equals("ai검색")
                || clean.contains("ai탭에서대화하기")
                || clean.contains("ai로더알아보기")
                || clean.startsWith("ai탭,")
                || clean.startsWith("ai검색,");
    }

    private boolean ancestorHasNaverAiLabel(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo current = node;
        for (int depth = 0; current != null && depth < 6; depth++) {
            if (isNaverAiLabel(combinedNodeLabel(current))) return true;
            current = current.getParent();
        }
        return false;
    }

    private boolean looksLikeNaverAiPage(String screenText) {
        if (screenText == null || screenText.isEmpty()) return false;

        boolean hasAiTab =
                screenText.contains("ai탭")
                        || screenText.contains("ai 탭");

        boolean hasConversationSignature =
                screenText.contains("새 대화")
                        || screenText.contains("대화 기록")
                        || screenText.contains("대화 입력")
                        || screenText.contains("질문을 입력")
                        || screenText.contains("궁금한 것을 물어보")
                        || screenText.contains("ai와 대화");

        return hasAiTab && hasConversationSignature;
    }

    private boolean isWebTabAlreadySelected(AccessibilityNodeInfo node) {
        if (node == null) return false;
        if (node.isSelected() || node.isChecked()) return true;

        String label = combinedNodeLabel(node).toLowerCase(Locale.ROOT);
        return label.contains("선택됨")
                || label.contains("selected")
                || label.contains("현재 탭");
    }

    private String findEditableGoogleQuery(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);

        for (int i = 0; i < queue.size() && i < 900; i++) {
            AccessibilityNodeInfo node = queue.get(i);
            if (node == null) continue;

            CharSequence classNameCs = node.getClassName();
            String className = classNameCs == null ? "" : classNameCs.toString();
            CharSequence textCs = node.getText();

            if (textCs != null) {
                String text = textCs.toString().trim();
                if (!text.isEmpty() && text.length() <= 300
                        && (node.isEditable() || className.contains("EditText"))) {
                    return text;
                }
            }

            for (int c = 0; c < node.getChildCount(); c++) {
                AccessibilityNodeInfo child = node.getChild(c);
                if (child != null) queue.add(child);
            }
        }
        return null;
    }

    private boolean looksLikeSearchResults(String screenText) {
        if (screenText == null || screenText.isEmpty()) return false;

        return screenText.contains("전체")
                || screenText.contains("이미지")
                || screenText.contains("동영상")
                || screenText.contains("뉴스")
                || screenText.contains("쇼핑")
                || screenText.contains("검색 결과")
                || screenText.contains("search results");
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
