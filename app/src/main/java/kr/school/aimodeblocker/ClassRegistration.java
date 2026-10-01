package kr.school.aimodeblocker;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

final class ClassRegistration {
    private static final String PREFS = "class_registration";
    private static final String KEY_CLASS_CODE = "class_code";

    private ClassRegistration() {}

    static boolean hasClassCode(Context context) {
        return !getClassCode(context).isEmpty();
    }

    static String getClassCode(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_CLASS_CODE, "");
    }

    static boolean isValid(String raw) {
        String code = normalize(raw);
        return code.matches("[A-Z0-9_-]{4,32}");
    }

    static boolean saveOnce(Context context, String raw) {
        if (hasClassCode(context)) return false;
        String code = normalize(raw);
        if (!isValid(code)) return false;

        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.edit().putString(KEY_CLASS_CODE, code).commit();
    }

    static String normalize(String raw) {
        if (raw == null) return "";
        return raw.trim()
                .replace(" ", "")
                .toUpperCase(Locale.ROOT);
    }
}
