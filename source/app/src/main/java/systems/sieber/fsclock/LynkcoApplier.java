package systems.sieber.fsclock;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Hands a chosen file to a Lynk &amp; Co / Flyme (ECARX / Geely) head unit's own wallpaper system.
 *
 * These units render their live wallpaper through a proprietary engine
 * (com.flyme.auto.wallpaperlauncher) that ignores Android's WallpaperManager — so the Leopard
 * path (WallpaperManager.setBitmap / a live wallpaper) silently does nothing here. The one
 * officially-supported way to change the wallpaper is the head unit's own theme app,
 * com.flyme.auto.customize, which exposes an exported activity:
 *
 *   action  com.flyme.auto.customize.wallpaper.setting
 *   extra   select_file_path   (String)  absolute path of the image/video
 *   extra   key_source_type    (int)     3 = treated like a gallery pick
 *
 * It opens the head unit's own preview with an "Apply" button — one tap, no root, no privileged
 * permission. The theme app holds SET_WALLPAPER_COMPONENT and does the actual switch.
 *
 * The one catch is readability: the theme app reads select_file_path from its own sandbox, so the
 * file must live on shared external storage (the app already holds MANAGE_EXTERNAL_STORAGE for the
 * GWM mirror). We stage the chosen media into a public folder and pass that path — a file written
 * to another app's private dir, or one placed by root into the lower filesystem, would not be
 * readable through the theme app's FUSE view.
 */
class LynkcoApplier {

    private static final String TAG = "LynkcoApplier";

    /** The theme app's exported wallpaper entry point. Never rename — it is another app's API. */
    static final String ACTION_SET = "com.flyme.auto.customize.wallpaper.setting";
    static final String EXTRA_PATH = "select_file_path";
    static final String EXTRA_SOURCE_TYPE = "key_source_type";
    /** 3 = the theme app treats it like a gallery pick (see RouteActivity in AutoCustomizeCenter). */
    static final int SOURCE_TYPE_GALLERY = 3;

    /** Public staging folder the theme app can read. Shared with nothing else, so it stays tidy. */
    private static final String STAGE_DIR = "ThabthabaWallpaper";

    static final int RESULT_LAUNCHED = 0;   // staged and the theme app's preview was opened
    static final int RESULT_FAILED = 1;

    /**
     * Stage the file to shared storage and open the theme app's apply screen.
     *
     * Blocking on the copy/download — call from a background thread. The startActivity itself is
     * cheap and safe to run from here; the caller is an Activity so no NEW_TASK juggling is needed,
     * but we add the flag anyway so a future non-Activity caller still works.
     *
     * @param uriStr an https:// URL, a content:// from the system picker, or a file path/URI.
     */
    static int apply(Context ctx, String uriStr, String type) {
        File staged = stage(ctx, uriStr, type);
        if(staged == null) {
            Log.e(TAG, "could not stage " + uriStr);
            return RESULT_FAILED;
        }
        try {
            Intent i = new Intent(ACTION_SET);
            i.setPackage(OperatingMode.LYNKCO_CUSTOMIZE_PACKAGE);
            i.putExtra(EXTRA_PATH, staged.getAbsolutePath());
            i.putExtra(EXTRA_SOURCE_TYPE, SOURCE_TYPE_GALLERY);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return RESULT_LAUNCHED;
        } catch(Throwable t) {
            Log.e(TAG, "theme app rejected the wallpaper intent", t);
            return RESULT_FAILED;
        }
    }

    /** True when the head unit's theme app is present to receive our intent. */
    static boolean isAvailable(Context ctx) {
        return OperatingMode.isLynkcoSupported(ctx);
    }

    /**
     * Copy/download the chosen media into the public staging folder, keeping a real extension so
     * the theme app resolves image-vs-video correctly. Returns the staged file, or null on failure.
     */
    private static File stage(Context ctx, String uriStr, String type) {
        InputStream in = null;
        OutputStream out = null;
        try {
            File dir = new File(Environment.getExternalStorageDirectory(), STAGE_DIR);
            if(!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "cannot create staging dir " + dir);
                return null;
            }
            File dest = new File(dir, "wp_" + Integer.toHexString(uriStr.hashCode()) + "." + extensionFor(uriStr, type));
            // Same file already staged: skip the copy. A re-pick of the current wallpaper is free,
            // and the theme app is happy to re-open the same path.
            if(dest.exists() && dest.length() > 0) return dest;

            File tmp = new File(dest.getAbsolutePath() + ".part");
            in = LeopardCache.isRemote(uriStr) ? openRemote(uriStr) : openLocal(ctx, uriStr);
            if(in == null) return null;
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            while((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            if(dest.exists()) //noinspection ResultOfMethodCallIgnored
                dest.delete();
            return tmp.renameTo(dest) ? dest : null;
        } catch(Throwable t) {
            Log.e(TAG, "staging failed for " + uriStr, t);
            return null;
        } finally {
            if(out != null) try { out.close(); } catch(Exception ignored) {}
            if(in != null) try { in.close(); } catch(Exception ignored) {}
        }
    }

    private static InputStream openRemote(String url) throws Exception {
        java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        if(c.getResponseCode() / 100 != 2) {
            c.disconnect();
            return null;
        }
        return c.getInputStream();
    }

    private static InputStream openLocal(Context ctx, String uriStr) throws Exception {
        if(uriStr.startsWith("content://")) return ctx.getContentResolver().openInputStream(Uri.parse(uriStr));
        String path = uriStr.startsWith("file://") ? uriStr.substring("file://".length()) : uriStr;
        return new java.io.FileInputStream(path);
    }

    /**
     * The extension is load-bearing: the theme app (and the engine behind it) branch image vs video
     * on it. Prefer the URL's own extension, fall back to the declared type.
     */
    private static String extensionFor(String url, String type) {
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if(q >= 0) u = u.substring(0, q);
        int dot = u.lastIndexOf('.');
        if(dot >= 0 && dot > u.lastIndexOf('/')) {
            String ext = u.substring(dot + 1);
            if(ext.length() >= 2 && ext.length() <= 4 && ext.matches("[a-z0-9]+")) return ext;
        }
        return WallpaperItem.TYPE_VIDEO.equals(type) ? "mp4" : "jpg";
    }
}
