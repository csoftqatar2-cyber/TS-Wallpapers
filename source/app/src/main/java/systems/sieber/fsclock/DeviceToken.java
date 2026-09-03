package systems.sieber.fsclock;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/**
 * The per-car secret handed out once by the shared activation backend (Phase 2-H).
 *
 * Stored as a private file under filesDir — not in the "CLOCK" SharedPreferences, which
 * every other component reads and which a backup/restore could carry to another car. The
 * token is never shown, never logged, and its absence is NOT an error: a car that has no
 * token is simply a car the backend has not enrolled yet (gate closed, or an older build),
 * and it keeps working exactly as before. See WallpaperRepo for the only callers.
 */
final class DeviceToken {

    /** What this app calls itself to the backend's per-app gates. Frozen. */
    static final String APP_ID = "wallpapers";

    private static final String FILE_NAME = "device-token";

    private DeviceToken() {}

    /** The stored token, or null when this car has none. Never throws. */
    static String get(Context ctx) {
        try {
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            if (!f.isFile() || f.length() == 0 || f.length() > 512) return null;
            byte[] buf = new byte[(int) f.length()];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(buf);
                if (n <= 0) return null;
                String s = new String(buf, 0, n, StandardCharsets.UTF_8).trim();
                return s.isEmpty() ? null : s;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** Store a token the backend just issued. Ignored when empty. Never throws. */
    static void set(Context ctx, String token) {
        if (token == null) return;
        String t = token.trim();
        if (t.isEmpty()) return;
        try {
            File f = new File(ctx.getFilesDir(), FILE_NAME);
            try (FileOutputStream out = new FileOutputStream(f, false)) {
                out.write(t.getBytes(StandardCharsets.UTF_8));
            }
        } catch (Throwable t2) {
            // A token we could not persist is a token we ask for again tomorrow. Nothing else
            // depends on it having been written.
        }
    }
}
