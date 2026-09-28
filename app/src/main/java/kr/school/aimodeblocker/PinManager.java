package kr.school.aimodeblocker;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

final class PinManager {
    private static final String PREFS = "pin_prefs";
    private static final String KEY_SALT = "pin_salt";
    private static final String KEY_HASH = "pin_hash";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_LENGTH_BITS = 256;

    private PinManager() {}

    static boolean hasPin(Context context) {
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return p.contains(KEY_SALT) && p.contains(KEY_HASH);
    }

    static boolean isValidFormat(String pin) {
        return pin != null && pin.matches("\\d{4,12}");
    }

    static boolean savePin(Context context, String pin) {
        if (!isValidFormat(pin)) return false;
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            byte[] hash = derive(pin.toCharArray(), salt);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString(KEY_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
                    .apply();
            Arrays.fill(hash, (byte) 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean verify(Context context, String pin) {
        if (pin == null) return false;
        SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String saltString = p.getString(KEY_SALT, null);
        String hashString = p.getString(KEY_HASH, null);
        if (saltString == null || hashString == null) return false;

        try {
            byte[] salt = Base64.decode(saltString, Base64.NO_WRAP);
            byte[] expected = Base64.decode(hashString, Base64.NO_WRAP);
            byte[] actual = derive(pin.toCharArray(), salt);
            boolean equal = constantTimeEquals(expected, actual);
            Arrays.fill(actual, (byte) 0);
            return equal;
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] derive(char[] pin, byte[] salt) throws Exception {
        KeySpec spec = new PBEKeySpec(pin, salt, ITERATIONS, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(spec)
                    .getEncoded();
        } finally {
            if (spec instanceof PBEKeySpec) ((PBEKeySpec) spec).clearPassword();
            Arrays.fill(pin, '\0');
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int result = 0;
        for (int i = 0; i < a.length; i++) result |= a[i] ^ b[i];
        return result == 0;
    }
}
