package kr.school.aimodeblocker;

import android.content.Context;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

final class AiPageDetector {
    private AiPageDetector() {}

    static boolean looksLikeGenerativeAi(
            Context context,
            String currentUrl,
            AccessibilityNodeInfo root
    ) {
        if (root == null || !RemotePolicyManager.isAutoDetectEnabled(context)) return false;
        if (RemotePolicyManager.isAllowedUrl(context, currentUrl)) return false;

        int editableCount = 0;
        boolean hasSendControl = false;
        boolean hasModelControl = false;
        StringBuilder screen = new StringBuilder();

        List<AccessibilityNodeInfo> queue = new ArrayList<>();
        queue.add(root);

        for (int i = 0; i < queue.size() && i < 900; i++) {
            AccessibilityNodeInfo node = queue.get(i);
            if (node == null) continue;

            CharSequence text = node.getText();
            CharSequence desc = node.getContentDescription();
            CharSequence hint = node.getHintText();

            append(screen, text);
            append(screen, desc);
            append(screen, hint);

            String label = ((text == null ? "" : text.toString()) + " "
                    + (desc == null ? "" : desc.toString()) + " "
                    + (hint == null ? "" : hint.toString()))
                    .toLowerCase(Locale.ROOT);

            if (node.isEditable()) editableCount++;

            if (containsAny(label,
                    "send", "submit", "전송", "보내기", "메시지 보내기",
                    "질문 보내기", "prompt 보내기")) {
                hasSendControl = true;
            }

            if (containsAny(label,
                    "select model", "choose model", "모델 선택",
                    "gpt-4", "gpt-5", "claude", "gemini",
                    "deepseek", "llama", "qwen", "grok")) {
                hasModelControl = true;
            }

            for (int c = 0; c < node.getChildCount(); c++) {
                AccessibilityNodeInfo child = node.getChild(c);
                if (child != null) queue.add(child);
            }
        }

        String all = screen.toString().toLowerCase(Locale.ROOT);
        int score = 0;

        if (containsAny(all,
                "ai assistant", "ai chatbot", "ai chat",
                "ai 어시스턴트", "ai 챗봇", "ai 채팅",
                "생성형 ai", "generative ai")) {
            score += 3;
        }

        if (containsAny(all,
                "ask anything", "무엇이든 물어보",
                "new chat", "새 대화", "start a chat",
                "질문을 입력", "메시지를 입력", "프롬프트를 입력",
                "type a message", "enter a prompt")) {
            score += 2;
        }

        if (containsAny(all,
                "chatgpt", "openai", "gpt-4", "gpt-5",
                "claude", "gemini", "deepseek", "llama",
                "qwen", "grok", "mistral")) {
            score += 2;
        }

        if (editableCount > 0) score += 1;
        if (hasSendControl) score += 1;
        if (hasModelControl) score += 2;

        // Strong threshold: a normal article mentioning AI should not be
        // blocked merely because it has a site search box.
        return score >= 6 && editableCount > 0;
    }

    private static boolean containsAny(String value, String... needles) {
        if (value == null) return false;
        for (String needle : needles) {
            if (value.contains(needle)) return true;
        }
        return false;
    }

    private static void append(StringBuilder sb, CharSequence value) {
        if (value == null) return;
        String v = value.toString().trim();
        if (!v.isEmpty()) sb.append(' ').append(v);
    }
}
