package systems.sieber.fsclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Tamper-resistant preferences wrapper.
 * <p>
 * Stores sensitive boolean/string values alongside an HMAC-SHA256 signature
 * that is derived from the value + the APK signing certificate hash.
 * If the value is edited manually in the SharedPreferences XML, the HMAC
 * will not match and the getter returns the default (safe) value.
 * <p>
 * If someone re-signs the APK with a different certificate, the HMAC key
 * changes automatically, invalidating all stored values.
 */
final class SecurePrefs {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final String SUFFIX_SIG = "__sig";

    private final SharedPreferences mPref;
    private final byte[] mHmacKey;

    SecurePrefs(Context context, SharedPreferences prefs) {
        mPref = prefs;
        mHmacKey = deriveKey(context);
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Store a boolean with its HMAC. */
    void putBoolean(String key, boolean value) {
        String raw = key + "=" + value;
        String sig = hmac(raw);
        mPref.edit()
             .putBoolean(key, value)
             .putString(key + SUFFIX_SIG, sig)
             .apply();
    }

    /** Read a boolean; returns {@code defValue} if the HMAC is invalid. */
    boolean getBoolean(String key, boolean defValue) {
        if (!mPref.contains(key)) return defValue;
        boolean value = mPref.getBoolean(key, defValue);
        String storedSig = mPref.getString(key + SUFFIX_SIG, "");
        String expected = hmac(key + "=" + value);
        if (expected.equals(storedSig)) {
            return value;
        }
        // Signature mismatch – someone tampered with the value
        return defValue;
    }

    /** Store a string with its HMAC. */
    void putString(String key, String value) {
        String raw = key + "=" + (value == null ? "" : value);
        String sig = hmac(raw);
        mPref.edit()
             .putString(key, value)
             .putString(key + SUFFIX_SIG, sig)
             .apply();
    }

    /** Read a string; returns {@code defValue} if the HMAC is invalid. */
    String getString(String key, String defValue) {
        if (!mPref.contains(key)) return defValue;
        String value = mPref.getString(key, defValue);
        String storedSig = mPref.getString(key + SUFFIX_SIG, "");
        String expected = hmac(key + "=" + (value == null ? "" : value));
        if (expected.equals(storedSig)) {
            return value;
        }
        return defValue;
    }

    // ── Internal ──────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static byte[] deriveKey(Context context) {
        try {
            // Use the APK signing certificate fingerprint as part of the key.
            // This means if the APK is re-signed (pirated rebuild), the key changes
            // and all stored secure values become invalid.
            PackageInfo pi;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pi = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                Signature[] sigs = pi.signingInfo.getApkContentsSigners();
                if (sigs != null && sigs.length > 0) {
                    return sha256(sigs[0].toByteArray());
                }
            } else {
                pi = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), PackageManager.GET_SIGNATURES);
                if (pi.signatures != null && pi.signatures.length > 0) {
                    return sha256(pi.signatures[0].toByteArray());
                }
            }
        } catch (Exception ignored) { }
        // Fallback – still better than nothing
        return "TS-WP-FALLBACK-KEY-2026".getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return md.digest(data);
        } catch (Exception e) {
            return data; // should never happen
        }
    }

    private String hmac(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(mHmacKey, HMAC_ALGO));
            byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(result, Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            return "";
        }
    }
}
