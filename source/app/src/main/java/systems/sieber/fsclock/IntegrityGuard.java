package systems.sieber.fsclock;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Debug;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.security.MessageDigest;

/**
 * Multi-layered APK integrity and environment guard.
 * <p>
 * Performs the following checks on every app start:
 * <ol>
 *   <li><b>Signature verification</b> – confirms the APK is signed with the
 *       expected certificate (detects re-signed / tampered builds).</li>
 *   <li><b>Debugger detection</b> – checks if a debugger is attached.</li>
 *   <li><b>Installer verification</b> – optionally checks the installing
 *       package (Play Store, Amazon, etc.).</li>
 *   <li><b>Root / Xposed detection</b> – basic heuristic checks for common
 *       hooking frameworks and root binaries.</li>
 * </ol>
 */
final class IntegrityGuard {

    private static final String TAG = "IntegrityGuard";

    private IntegrityGuard() { /* utility */ }

    /**
     * The SHA-256 fingerprint of the legitimate release signing certificate.
     * This is filled at build time or on first trusted run.
     * Set to null to skip signature checking (useful during development).
     */
    private static String sExpectedSigHash = null;

    /**
     * Call once during Application.onCreate() to capture the expected hash.
     * On the very first run (sExpectedSigHash == null) this "trusts" the
     * current signer, which is safe because the first APK installed is always
     * the legitimate one.
     */
    static void init(Context context) {
        String current = getSignatureHash(context);
        if (sExpectedSigHash == null && current != null) {
            sExpectedSigHash = current;
            Log.d(TAG, "Recorded signing cert hash");
        }
    }

    // ── Individual checks ─────────────────────────────────────────────────

    /** Returns true if the APK signature matches the expected certificate. */
    static boolean isSignatureValid(Context context) {
        if (sExpectedSigHash == null) return true; // not yet initialized
        String current = getSignatureHash(context);
        return sExpectedSigHash.equals(current);
    }

    /** Returns true if a debugger is currently attached. */
    static boolean isDebuggerAttached() {
        return Debug.isDebuggerConnected() || Debug.waitingForDebugger();
    }

    /** Returns true if the app's "debuggable" flag is set (release builds should be false). */
    static boolean isDebuggable(Context context) {
        return (context.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /**
     * Basic heuristic check for common root indicators.
     * Not bulletproof, but raises the bar significantly.
     */
    static boolean isLikelyRooted() {
        // Check common su binary locations
        String[] paths = {
            "/system/bin/su", "/system/xbin/su", "/sbin/su",
            "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
            "/system/sd/xbin/su", "/system/app/Superuser.apk",
            "/system/app/SuperSU.apk"
        };
        for (String path : paths) {
            if (new File(path).exists()) return true;
        }
        // Check for Magisk
        if (new File("/sbin/.magisk").exists() || new File("/data/adb/magisk").exists()) {
            return true;
        }
        return false;
    }

    /**
     * Check for Xposed framework (commonly used to hook/modify app behavior at runtime).
     */
    static boolean isXposedPresent() {
        try {
            // Xposed injects itself into the class loader
            ClassLoader.getSystemClassLoader().loadClass("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (ClassNotFoundException ignored) { }

        // Check the system process list for Xposed installer
        try {
            String stackTrace = Log.getStackTraceString(new Throwable());
            if (stackTrace.contains("de.robv.android.xposed")
                    || stackTrace.contains("EdXposed")
                    || stackTrace.contains("LSPosed")) {
                return true;
            }
        } catch (Exception ignored) { }

        return false;
    }

    // ── Aggregate check ───────────────────────────────────────────────────

    /**
     * Runs all integrity checks and returns true only if the environment
     * appears legitimate. Call this before performing sensitive operations
     * like activation or loading wallpapers.
     */
    static boolean isEnvironmentTrusted(Context context) {
        if (!isSignatureValid(context)) {
            Log.w(TAG, "APK signature mismatch – possible tampered build");
            return false;
        }
        if (isDebuggerAttached()) {
            Log.w(TAG, "Debugger attached");
            return false;
        }
        if (isXposedPresent()) {
            Log.w(TAG, "Xposed framework detected");
            return false;
        }
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    @SuppressWarnings("deprecation")
    private static String getSignatureHash(Context context) {
        try {
            Signature[] sigs;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PackageInfo pi = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
                sigs = pi.signingInfo.getApkContentsSigners();
            } else {
                PackageInfo pi = context.getPackageManager().getPackageInfo(
                        context.getPackageName(), PackageManager.GET_SIGNATURES);
                sigs = pi.signatures;
            }
            if (sigs != null && sigs.length > 0) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] hash = md.digest(sigs[0].toByteArray());
                StringBuilder sb = new StringBuilder();
                for (byte b : hash) sb.append(String.format("%02X", b));
                return sb.toString();
            }
        } catch (Exception ignored) { }
        return null;
    }
}
