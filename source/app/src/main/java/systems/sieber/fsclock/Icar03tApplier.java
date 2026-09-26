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
 *     apply therefore gets a fresh name.</li>
 * <li><b>Never send REMOVE after an INSERT.</b> Status 0 reaches
 *     {@code WallPaperManager.deleteFestivalWallPaper()}, which ignores the {@code holiday}
 *     extra entirely and drops EVERY entry whose path lives under {@code Download/holiday} —
 *     including the one we just inserted. Tidying the old slot that way wiped the new
 *     wallpaper on the car, and only looked to work when there was no previous slot. It is
 *     unnecessary anyway: status 1 already clears the old entry before adding the new one.
 *     So an apply sends the insert and nothing else, and the folders left behind by earlier
 *     applies are swept from disk at the start of the NEXT apply — never right after the
 *     broadcast, which the launcher processes asynchronously and may still be reading.</li>
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
            SharedPreferences p = prefs(ctx);
            String previous = p.getString(PREF_SLOT, null);
            // Sweep now, not after the insert: the folders of applies older than the current
            // slot are certainly unused, and deleting them here cannot race the launcher.
            sweepOldSlots(previous);

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

            // Record before broadcasting: if the process dies between the two, the next apply
            // must still know which folder is ours.
            p.edit().putString(PREF_SLOT, slot).apply();

            // Insert only. No REMOVE for the previous slot — see the class comment: it would
            // delete this entry too. The launcher drops the old entry on insert by itself, and
            // the previous folder is swept from disk by the next apply.
            broadcast(ctx, STATUS_INSERT, slot);
            CrashReporter.breadcrumb("icar03t: applied " + slot);
            return RESULT_APPLIED;
        } catch(Throwable t) {
            Log.e(TAG, "apply failed", t);
            if(dir != null) deleteTree(dir.getParentFile());
            return RESULT_FAILED;
        }
    }

    /**
     * Put the slot we already wrote back into the launcher's carousel, without touching disk.
     *
     * The launcher wipes us on every car start: {@code WallPaperManager.init()} calls
     * {@code deleteFestivalWallPaper()}, which drops every festival entry under
     * {@code Download/holiday}. It then asks providers to resend with an IMPLICIT broadcast
     * ({@code com.mengbo.holiday.reply}, ~5 s after {@code NewMainActivity.onCreate}) — useless
     * to us, because a manifest receiver in an app targeting a modern SDK never sees an implicit
     * broadcast. So we re-send the insert ourselves after a car start.
     *
     * The folder name deliberately does NOT change: the files are untouched, so the launcher's
     * Glide cache keyed by that path holds exactly the picture we want back on the screen.
     *
     * @return true if an insert was sent.
     */
    static boolean reinsert(Context ctx) {
        return reinsert(ctx, true);
    }

    /**
     * @param breadcrumb false for the repeats inside a boot burst: ~30 identical lines would
     *                   push everything else out of the breadcrumb buffer a crash report carries.
     */
    private static boolean reinsert(Context ctx, boolean breadcrumb) {
        try {
            String slot = prefs(ctx).getString(PREF_SLOT, null);
            if(slot == null) return false;
            File dir = new File(holidayRoot(), slot + "/launcher");
            // Both files or nothing, the same rule as apply(): a half-present slot makes the
            // launcher log "no picture loaded" and we would have announced a wallpaper that
            // cannot be shown.
            if(!new File(dir, "img_launch_wall_light.png").isFile()) return false;
            if(!new File(dir, "img_launch_wall_dark.png").isFile()) return false;
            broadcast(ctx, STATUS_INSERT, slot);
            if(breadcrumb) CrashReporter.breadcrumb("icar03t: reinserted " + slot);
            return true;
        } catch(Throwable t) {
            Log.w(TAG, "could not reinsert the wallpaper", t);
            return false;
        }
    }

    /** One burst at a time per process: two triggers racing would just double the traffic. */
    private static final java.util.concurrent.atomic.AtomicBoolean sBurstRunning =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** How long to keep offering the wallpaper back, and how often. */
    private static final long BURST_TOTAL_MS = 90000;
    private static final long BURST_INTERVAL_MS = 3000;

    /**
     * Keep handing the launcher its festival entry for the first minute and a half of a car start.
     *
     * Why a burst and not one delayed send. Measured on the car (boot at 16:24:10): the launcher
     * process started at +2 s, ours at +8 s — started by the notification-listener binding, not by
     * the boot broadcast, which only reached {@code BootReceiver} at +25 s. A single insert 20 s
     * after that broadcast landed at +46 s and worked, but the owner spent those 45 s looking at a
     * screen with no wallpaper on it. The only way to be on screen as early as the launcher allows
     * is to keep asking from the moment we exist.
     *
     * Why asking early is free. The launcher registers the festival receiver at the END of
     * {@code NewMainActivity.onCreate}, and the handler touches {@code WallPaperManager
     * .getInstance()} — whose {@code init()} does the wipe — BEFORE adding our entry. So an insert
     * is either lost (receiver not up yet) or it sticks; it can never be wiped afterwards. And a
     * repeat with the same holiday name hits the launcher's own dedup (same status + same holiday
     * → return), so all the sends after the one that landed cost nothing.
     */
    static void startBootBurst(final Context ctx) {
        try {
            if(!sBurstRunning.compareAndSet(false, true)) return;
            final Context app = ctx.getApplicationContext();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    int sends = 0;
                    try {
                        long until = android.os.SystemClock.elapsedRealtime() + BURST_TOTAL_MS;
                        while(true) {
                            if(reinsert(app, sends == 0)) sends++;
                            if(android.os.SystemClock.elapsedRealtime() >= until) break;
                            Thread.sleep(BURST_INTERVAL_MS);
                        }
                    } catch(InterruptedException ignored) {
                    } catch(Throwable t) {
                        Log.w(TAG, "boot burst failed", t);
                    } finally {
                        sBurstRunning.set(false);
                        CrashReporter.breadcrumb("icar03t: boot burst done, " + sends + " sends");
                    }
                }
            }).start();
        } catch(Throwable t) {
            Log.w(TAG, "could not start the boot burst", t);
            sBurstRunning.set(false);
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

    /**
     * Delete every folder of ours under the holiday root except {@code keep} (the slot the
     * launcher is showing right now). Only our own {@link #SLOT_PREFIX} names are touched —
     * the root is shared with the car's real theme packs.
     */
    private static void sweepOldSlots(String keep) {
        try {
            File[] kids = holidayRoot().listFiles();
            if(kids == null) return;
            for(File k : kids) {
                if(!k.isDirectory()) continue;
                String name = k.getName();
                if(!name.startsWith(SLOT_PREFIX)) continue;
                if(name.equals(keep)) continue;
                deleteTree(k);
            }
        } catch(Throwable t) {
            Log.w(TAG, "could not sweep old slots", t);
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
