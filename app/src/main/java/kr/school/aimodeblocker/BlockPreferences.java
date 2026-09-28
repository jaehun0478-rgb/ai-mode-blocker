package kr.school.aimodeblocker;

import android.content.Context;

final class BlockPreferences {
    private static final String PREFS = "block_prefs";
    private static final String KEY_ENABLED = "blocking_enabled";

    private BlockPreferences() {}

    static boolean isEnabled(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_ENABLED, enabled)
                .apply();
    }
}
