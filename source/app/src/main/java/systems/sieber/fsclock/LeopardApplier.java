package systems.sieber.fsclock;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;

import java.io.InputStream;

/**
 * Hands a chosen file to Android's wallpaper system.
 *
 * The asymmetry here is a platform boundary, not a design choice:
 *
 *  - A still image can be set on home AND lock in one silent call. No system screen, no
 *    question. Exactly what was asked for.
 *  - Anything moving must be a live wallpaper, and Android will not let an app install itself
 *    as the live wallpaper silently — it must go through ACTION_CHANGE_LIVE_WALLPAPER, where
 *    the user presses the system's own button. There is no API to skip this.
 *
 * The second case happens once. After our service is the active live wallpaper it stays
 * active and reloads itself when the stored selection changes, so every later video is
 * instant. Also: a live wallpaper generally cannot cover the lock screen, so a video is
 * home-only while an image is both. The copy must not promise otherwise.
 */
class LeopardApplier {

    /** Our own note of what was picked. The engine deliberately does NOT read this — it asks
     *  contentResolver for the real MIME, exactly as the proven reference does. This is only
     *  here so the applier can decide silent-vs-system-screen without touching the file. */
    static final String PREF_TYPE = "leopard-type";

    /** What applying this file will actually do, so the UI can warn before it happens. */
    static final int RESULT_APPLIED_BOTH = 0;      // image: home + lock, silently
    static final int RESULT_NEEDS_SYSTEM_SCREEN = 1; // moving: launch the system picker
    static final int RESULT_APPLIED_LIVE = 2;      // moving, service already active: silent
    static final int RESULT_FAILED = 3;

    /** True when applying this item would throw the user into the system's own screen. */
    static boolean needsSystemScreen(Context ctx, String type) {
        boolean moving = WallpaperItem.TYPE_VIDEO.equals(type) || WallpaperItem.TYPE_GIF.equals(type);
        return moving && !isOurServiceActive(ctx);
    }

    static boolean isOurServiceActive(Context ctx) {
        try {
            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            android.app.WallpaperInfo info = wm.getWallpaperInfo();
            return info != null && MediaWallpaperService.class.getName().equals(info.getServiceName());
        } catch(Throwable t) {
            return false;
        }
    }

    /**
     * Store the selection, then apply it.
     * @return one of the RESULT_* constants. RESULT_NEEDS_SYSTEM_SCREEN means the caller must
     *         launch {@link #systemPickerIntent} after warning the user.
     */
    static int apply(Context ctx, String uriStr, String type) {
        // Persist first, unconditionally: the service reads this, and on the
        // needs-system-screen path the system will start us before we get another chance.
        ctx.getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE)
                .edit()
                .putString(MediaWallpaperService.PREF_URI, uriStr)
                .putString(LeopardApplier.PREF_TYPE, type)
                .apply();

        boolean moving = WallpaperItem.TYPE_VIDEO.equals(type) || WallpaperItem.TYPE_GIF.equals(type);
        if(moving) {
            return isOurServiceActive(ctx) ? RESULT_APPLIED_LIVE : RESULT_NEEDS_SYSTEM_SCREEN;
        }
        return applyStill(ctx, uriStr) ? RESULT_APPLIED_BOTH : RESULT_FAILED;
    }

    private static boolean applyStill(Context ctx, String uriStr) {
        InputStream in = null;
        try {
            Uri uri = Uri.parse(uriStr);
            in = openAny(ctx, uri);
            if(in == null) return false;
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if(bmp == null) return false;

            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Home and lock together, in one call, with no system screen.
                wm.setBitmap(bmp, null, true,
                        WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
            } else {
                // Pre-N has no per-target flags: this sets the one wallpaper there is.
                wm.setBitmap(bmp);
            }
            return true;
        } catch(Throwable t) {
            return false;
        } finally {
            if(in != null) try { in.close(); } catch(Exception ignored) {}
        }
    }

    private static InputStream openAny(Context ctx, Uri uri) throws Exception {
        String s = uri.toString();
        if(s.startsWith("content://")) return ctx.getContentResolver().openInputStream(uri);
        String path = s.startsWith("file://") ? s.substring("file://".length()) : s;
        return new java.io.FileInputStream(path);
    }

    /** The system's own live-wallpaper preview. We cannot skip it and we cannot style it. */
    static Intent systemPickerIntent(Context ctx) {
        Intent i = new Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER);
        i.putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                new ComponentName(ctx, MediaWallpaperService.class));
        return i;
    }
}
