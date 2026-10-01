package kr.school.aimodeblocker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

final class ClassRegistration {
    private static final String PREFS = "class_registration";
    private static final String KEY_CLASS_CODE = "class_code";
    private static final String KEY_REGISTERED_AT = "registered_at";
    private static final String KEY_RESET_NOTICE = "reset_notice";

    private ClassRegistration() {}

    static boolean hasClassCode(Context context) {
        return !getClassCode(context).isEmpty();
    }

    static String getClassCode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CLASS_CODE, "");
    }

    static long getRegisteredAt(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getLong(KEY_REGISTERED_AT, 0L);
    }

    static boolean isValid(String raw) {
        String code = normalize(raw);
        return code.matches("[A-Z0-9_-]{4,32}");
    }

    static boolean saveOnce(Context context, String raw) {
        if (hasClassCode(context)) return false;
        String code = normalize(raw);
        if (!isValid(code)) return false;

        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_CLASS_CODE, code)
                .putLong(KEY_REGISTERED_AT, System.currentTimeMillis())
                .putBoolean(KEY_RESET_NOTICE, false)
                .commit();
    }

    static void clearForRemoteReset(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_CLASS_CODE)
                .remove(KEY_REGISTERED_AT)
                .putBoolean(KEY_RESET_NOTICE, true)
                .apply();
    }

    static boolean consumeResetNotice(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        boolean value = p.getBoolean(KEY_RESET_NOTICE, false);
        if (value) p.edit().putBoolean(KEY_RESET_NOTICE, false).apply();
        return value;
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        return raw.trim()
                .replace(" ", "")
                .toUpperCase(Locale.ROOT);
    }
}
