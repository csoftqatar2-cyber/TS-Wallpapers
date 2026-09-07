package systems.sieber.fsclock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Applies a wallpaper on a Chery iCAR 03T (MENGBO head unit).
 *
 * This car does NOT use Android's wallpaper system, whatever it says. Its launcher
 * ({@code com.mengbo.launcher3}) paints its own carousel of pictures on top of the system
 * wallpaper, so setting a live wallpaper the Leopard way binds correctly, reports correctly,
 * and is never seen. Measured on the car: after our engine became
 * {@code mWallpaperComponent}, the home screen went on showing the launcher's own picture.
 *
 * What the launcher DOES read is a folder on shared storage. Decompiled from
 * {@code com.mengbo.wallpager.WallPaperFragment}:
 *
 * <pre>
 *   pathLight = Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS)
 *             + "/holiday/" + holiday + "/launcher/img_launch_wall_light.png";
 *   pathDark  = ... + "/img_launch_wall_dark.png";
 *   WallPaperManager.getInstance().addWallPaper(pathLight, pathDark, index);
 * </pre>
 *
 * It is driven by a broadcast, {@link #ACTION_HOLIDAY}, with {@code status} 1 (insert) or 0
 * (remove) and {@code holiday} naming the folder. The receiver is registered by the launcher
 * itself and answered while it runs — verified on the car, which logged its way through our
 * folder looking for the rest of a theme pack and took the two files it found.
 *
 * Three things this mechanism imposes on the design, all learned on the car:
 *
 * <ul>
 * <li><b>Two files or nothing.</b> Light and dark must both exist; with one missing the
 *     launcher logs "no picture loaded" and leaves the carousel alone. We write the same
 *     picture to both — one wallpaper, whatever the car's day/night theme is doing.</li>
 * <li><b>One slot.</b> The launcher keeps a single festival entry and clears the previous one
 *     on every insert, so this is a "current wallpaper" product exactly like Leopard — not a
 *     folder of many, like GWM or Jetour.</li>
 * <li><b>Never reuse a folder name.</b> The launcher loads through Glide, which caches by
 *     path: writing a new picture under the old name leaves the OLD one on the screen. Every
 *     apply therefore gets a fresh name and the previous folder is removed afterwards.</li>
 * </ul>
 *
 * PNG only, because the launcher decodes a still. A video cannot go through here at all.
 */
class Icar03tApplier {

    private static final String TAG = "Icar03tApplier";

    /** The launcher that owns the carousel. Its absence is what makes the mode unsupported. */
    static final String LAUNCHER_PACKAGE = "com.mengbo.launcher3";

    static final String ACTION_HOLIDAY = "com.mengbo.holiday.mode";
    private static final String EXTRA_STATUS = "status";
    private static final String EXTRA_HOLIDAY = "holiday";
    private static final int STATUS_INSERT = 1;
    private static final int STATUS_REMOVE = 0;

    /** Folder name prefix under Download/holiday. Ours are recognisable at a glance on the car. */
    private static final String SLOT_PREFIX = "tsw";

    /** The folder name currently occupying the launcher's slot, so the next apply can clear it. */
    static final String PREF_SLOT = "icar03t-slot";

    static final int RESULT_APPLIED = 0;
    static final int RESULT_FAILED = 1;

    private Icar03tApplier() { }

    static boolean isAvailable(Context ctx) {
        try {
            ctx.getPackageManager().getPackageInfo(LAUNCHER_PACKAGE, 0);
            return true;
        } catch(PackageManager.NameNotFoundException e) {
            return false;
        } catch(Throwable t) {
            return false;
        }
    }

    /**
     * Put {@code uriStr} on the car's home screen.
     *
     * Runs off the UI thread: a cloud picture is downloaded here.
     *
     * @param canvasW,canvasH the screen, so the picture is written already cropped to it — the
     *                        launcher scales whatever it is given, and a letterboxed or squashed
     *                        wallpaper is the one thing an owner notices immediately.
     */
    static int apply(Context ctx, String uriStr, int canvasW, int canvasH) {
        File dir = null;
        try {
            Bitmap bmp = decode(ctx, uriStr);
            if(bmp == null) {
                Log.e(TAG, "could not decode " + uriStr);
                return RESULT_FAILED;
            }
            Bitmap fitted = cover(bmp, canvasW, canvasH);

            final String slot = SLOT_PREFIX + System.currentTimeMillis();
            dir = new File(holidayRoot(), slot + "/launcher");
            if(!dir.mkdirs() && !dir.isDirectory()) {
                Log.e(TAG, "could not create " + dir);
                return RESULT_FAILED;
            }
            // Same picture twice: the launcher wants a day file and a night file, and the owner
            // picked one wallpaper, not two.
            File light = new File(dir, "img_launch_wall_light.png");
            File dark = new File(dir, "img_launch_wall_dark.png");
            if(!write(fitted, light)) return RESULT_FAILED;
            if(!write(fitted, dark)) return RESULT_FAILED;
            CrashReporter.breadcrumb("icar03t: wrote " + light.length() + "B to " + dir);
            if(fitted != bmp) fitted.recycle();
            bmp.recycle();

            SharedPreferences p = prefs(ctx);
            String previous = p.getString(PREF_SLOT, null);
            // Record before broadcasting: if the process dies between the two, the next apply
            // must still know which folder to clean up.
            p.edit().putString(PREF_SLOT, slot).apply();

            broadcast(ctx, STATUS_INSERT, slot);
            CrashReporter.breadcrumb("icar03t: applied " + slot);

            // Tidy the previous slot only after the new one is in: the launcher clears the old
            // entry itself on insert, so deleting first would blank the screen in between.
            if(previous != null && !previous.equals(slot)) {
                broadcast(ctx, STATUS_REMOVE, previous);
                deleteTree(new File(holidayRoot(), previous));
            }
            return RESULT_APPLIED;
        } catch(Throwable t) {
            Log.e(TAG, "apply failed", t);
            if(dir != null) deleteTree(dir.getParentFile());
            return RESULT_FAILED;
        }
    }

    /** Take our wallpaper off the car and leave the launcher's own carousel as it was. */
    static void clear(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            String slot = p.getString(PREF_SLOT, null);
            if(slot == null) return;
            broadcast(ctx, STATUS_REMOVE, slot);
            deleteTree(new File(holidayRoot(), slot));
            p.edit().remove(PREF_SLOT).apply();
            CrashReporter.breadcrumb("icar03t: cleared " + slot);
        } catch(Throwable t) {
            Log.w(TAG, "could not clear the wallpaper", t);
        }
    }

    private static void broadcast(Context ctx, int status, String slot) {
        Intent i = new Intent(ACTION_HOLIDAY);
        i.putExtra(EXTRA_STATUS, status);
        i.putExtra(EXTRA_HOLIDAY, slot);
        // Named explicitly as well as by action: the launcher registers this receiver at
        // runtime, and an implicit broadcast with no package would also wake every other
        // listener on the unit (systemui, the AC service) for a wallpaper change.
        i.setPackage(LAUNCHER_PACKAGE);
        ctx.sendBroadcast(i);
    }

    private static File holidayRoot() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), "holiday");
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
    }

    /** Centre-crop to the screen: fill it, never letterbox, never squash. */
    private static Bitmap cover(Bitmap src, int w, int h) {
        if(w <= 0 || h <= 0) return src;
        if(src.getWidth() == w && src.getHeight() == h) return src;
        float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
        int sw = Math.round(src.getWidth() * scale), sh = Math.round(src.getHeight() * scale);
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        Rect dst = new Rect((w - sw) / 2, (h - sh) / 2, (w - sw) / 2 + sw, (h - sh) / 2 + sh);
        c.drawBitmap(src, null, dst, new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return out;
    }

    /**
     * Written as JPEG under a .png name, deliberately.
     *
     * The launcher reads these with {@code BitmapFactory.decodeFile}, which sniffs the content
     * and does not care what the file is called — decompiled from
     * {@code com.mengbo.launcher3.util.BitmapUtil.loadDrawableFromPath}. The names are fixed by
     * that same code, so they stay; the bytes are JPEG because a photo as a full PNG is three
     * megabytes and two of them are decoded at once inside the launcher's own process.
     */
    private static boolean write(Bitmap bmp, File dest) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(dest);
            return bmp.compress(Bitmap.CompressFormat.JPEG, 92, out);
        } catch(Throwable t) {
            Log.e(TAG, "could not write " + dest, t);
            return false;
        } finally {
            if(out != null) try { out.close(); } catch(Throwable ignored) { }
        }
    }

    private static Bitmap decode(Context ctx, String uriStr) {
        InputStream in = null;
        try {
            if(uriStr.startsWith("http://") || uriStr.startsWith("https://")) {
                HttpURLConnection c = (HttpURLConnection) new URL(uriStr).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                in = c.getInputStream();
            } else if(uriStr.startsWith("content://")) {
                in = ctx.getContentResolver().openInputStream(Uri.parse(uriStr));
            } else {
                in = new java.io.FileInputStream(new File(Uri.parse(uriStr).getPath()));
            }
            return BitmapFactory.decodeStream(in);
        } catch(Throwable t) {
            Log.e(TAG, "could not read " + uriStr, t);
            return null;
        } finally {
            if(in != null) try { in.close(); } catch(Throwable ignored) { }
        }
    }

    private static void deleteTree(File f) {
        if(f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if(kids != null) for(File k : kids) deleteTree(k);
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }
}
