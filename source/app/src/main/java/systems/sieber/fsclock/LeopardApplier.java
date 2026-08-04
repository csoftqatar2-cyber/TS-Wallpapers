package systems.sieber.fsclock;

import android.app.WallpaperManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
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

    private static final String TAG = "LeopardApplier";

    /** Our own note of what was picked. The engine deliberately does NOT read this — it asks
     *  contentResolver for the real MIME, exactly as the proven reference does. This is only
     *  here so the applier can decide silent-vs-system-screen without touching the file. */
    static final String PREF_TYPE = "leopard-type";

    /** Our own copy of the last still we applied — see {@link #keepCopy}. */
    private static final String PREF_STILL_PATH = "leopard-still-path";
    private static final String PREF_LAST_REASSERT = "leopard-reassert-ms";
    private static final String KEEP_DIR = "leopard-current";

    /** Several wake signals arrive together on one start; one re-assert covers them all. */
    private static final long REASSERT_THROTTLE_MS = 60 * 1000L;

    /**
     * How long to give the vendor theme service to finish whatever it does right after a still
     * is set, before we set it again. See {@link #apply} for why a second write exists at all.
     */
    private static final long SETTLE_REAPPLY_MS = 1500L;

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

    /**
     * Remove our video live wallpaper if it is the currently active one. Lynk & Co sets images
     * through the Flyme theme app, a different wallpaper system from the Android live wallpaper a
     * video uses — so a still applied after a video would be hidden underneath the still-running
     * video. Clearing the live wallpaper first lets the Flyme image actually show. No-op (and
     * harmless) when our service is not the active wallpaper.
     */
    static void clearOurLiveWallpaper(Context ctx) {
        try {
            if(isOurServiceActive(ctx)) WallpaperManager.getInstance(ctx).clear();
        } catch(Throwable t) {
            Log.w(TAG, "could not clear live wallpaper before applying a still", t);
        }
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
        // A still and a live wallpaper are two different systems, and the live one is drawn on
        // top. If a video was ever applied on this car our service is still the active wallpaper
        // component, so the picture would be set into a layer nobody can see — and on a head unit
        // that restores its wallpaper component at boot, that is exactly the picture that
        // disappears on the next start. Stand the live wallpaper down first, every time.
        clearOurLiveWallpaper(ctx);
        boolean ok = applyStill(ctx, uriStr, true);
        CrashReporter.breadcrumb("leopard: apply still " + (ok ? "ok" : "FAILED") + " " + uriStr);
        // The picker leaves (and often tears itself down) a second or two after this call
        // returns — see onApplied()/goHome() in LeopardPickerActivity. On these head units that
        // window overlaps the vendor theme service's own post-boot-like re-assert of whatever
        // wallpaper it last knew about, which silently wins the race and the picture the user
        // just chose never actually shows — until the app is reopened, which runs reassert()
        // and sets it again after the vendor has settled. Do that same second write here,
        // proactively, on this same background thread, instead of making the user do it by hand.
        if(ok) {
            try {
                Thread.sleep(SETTLE_REAPPLY_MS);
            } catch(InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            boolean reok = applyStill(ctx, uriStr, false);
            CrashReporter.breadcrumb("leopard: settle re-apply " + (reok ? "ok" : "FAILED"));
        }
        return ok ? RESULT_APPLIED_BOTH : RESULT_FAILED;
    }

    /**
     * Put the stored still back after a restart.
     *
     * Setting a wallpaper is supposed to be permanent, and on ordinary Android it is. Car head
     * units are not ordinary Android: the ones this mode ships on run a vendor theme service that
     * re-asserts its own wallpaper (or re-binds the last live wallpaper component) as it boots,
     * and whatever we set silently loses. The visible result is a black screen every morning and
     * an owner who has to open the app and press "set" again — which is the bug this exists for.
     *
     * So the app puts it back itself: cheap, idempotent, and it costs nothing on a car where the
     * wallpaper survived (Android is handed the same picture it already has).
     *
     * Leopard only. Lynk &amp; Co hands the file to the Flyme theme app, which opens ITS OWN
     * preview screen and waits for a human to press Apply — firing that at boot would throw a
     * system screen in the driver's face on every start, so that mode is left alone.
     */
    static void reassert(Context context) {
        try {
            final Context ctx = context.getApplicationContext();
            final SharedPreferences prefs = ctx.getSharedPreferences(
                    BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
            if(OperatingMode.get(prefs) != OperatingMode.LEOPARD) return;

            // A video IS the wallpaper component; the system restores that by itself and
            // re-running it here would do nothing but wake a decoder.
            String type = prefs.getString(PREF_TYPE, WallpaperItem.TYPE_IMAGE);
            if(WallpaperItem.TYPE_VIDEO.equals(type) || WallpaperItem.TYPE_GIF.equals(type)) return;

            String path = prefs.getString(PREF_STILL_PATH, null);
            final boolean haveKeptCopy = path != null && !path.isEmpty();
            if(!haveKeptCopy) {
                // A car updating into this version has never kept a copy — it was set before the
                // copy existed. Its stored selection is the next best thing, and on the common
                // case (a picture in the wallpaper folder or the cache) it is still readable.
                path = prefs.getString(MediaWallpaperService.PREF_URI, null);
                if(path == null || path.startsWith("content://") || path.startsWith("http")) return;
                if(path.startsWith("file://")) path = path.substring("file://".length());
            }
            File f = new File(path);
            if(!f.exists() || f.length() == 0) {
                Log.w(TAG, "nothing to re-assert: " + path + " is gone");
                return;
            }

            long now = System.currentTimeMillis();
            long last = prefs.getLong(PREF_LAST_REASSERT, 0L);
            if(last != 0L && now >= last && now - last < REASSERT_THROTTLE_MS) return;
            prefs.edit().putLong(PREF_LAST_REASSERT, now).apply();

            // Decoding a screen-sized picture and handing it to the wallpaper service is far too
            // much for a broadcast receiver's main thread, which is where the boot signal lands.
            final String finalPath = f.getAbsolutePath();
            new Thread(new Runnable() {
                @Override
                public void run() {
                    // Same reasoning as the apply path: a live wallpaper the ROM brought back at
                    // boot would sit on top of the picture we are about to set.
                    clearOurLiveWallpaper(ctx);
                    // On the fallback route there is no kept copy yet — make one now, so the
                    // next restart no longer depends on a cache file still being there.
                    boolean ok = applyStill(ctx, finalPath, !haveKeptCopy);
                    Log.i(TAG, "wallpaper re-asserted after start: " + (ok ? "ok" : "FAILED"));
                    CrashReporter.breadcrumb(
                            "leopard: re-assert after start " + (ok ? "ok" : "FAILED"));
                }
            }).start();
        } catch(Throwable t) {
            // Never let this take down a boot broadcast or an app launch.
            Log.w(TAG, "could not re-assert the wallpaper", t);
        }
    }

    /**
     * Keep our own copy of the picture that is now the wallpaper.
     *
     * {@link #reassert} needs a file it can count on months later, and every source it could
     * point at is unreliable: a cloud image lives in the cache (which a full head unit deletes
     * first), a phone photo's framed version is baked into the cache too, and a content:// from
     * the system picker is a permission grant that any storage change can invalidate. One
     * screen-sized JPEG in internal storage answers all three, and it is the picture as it was
     * actually applied rather than the source it was made from.
     */
    private static void keepCopy(Context ctx, Bitmap bmp) {
        try {
            File dir = new File(ctx.getFilesDir(), KEEP_DIR);
            if(!dir.exists() && !dir.mkdirs()) return;
            File tmp = new File(dir, "current.tmp");
            File dest = new File(dir, "current.jpg");
            FileOutputStream out = new FileOutputStream(tmp);
            bmp.compress(Bitmap.CompressFormat.JPEG, 95, out);
            out.flush();
            out.close();
            if(dest.exists()) //noinspection ResultOfMethodCallIgnored
                dest.delete();
            if(!tmp.renameTo(dest)) return;
            ctx.getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_STILL_PATH, dest.getAbsolutePath())
                    .apply();
        } catch(Throwable t) {
            // The wallpaper is already set; only the restart repair is lost.
            Log.w(TAG, "could not keep a copy of the applied wallpaper", t);
        }
    }

    private static boolean applyStill(Context ctx, String uriStr, boolean remember) {
        InputStream in = null;
        try {
            Uri uri = Uri.parse(uriStr);
            in = openAny(ctx, uri);
            if(in == null) {
                Log.e(TAG, "cannot open " + uriStr);
                return false;
            }
            Bitmap bmp = BitmapFactory.decodeStream(in);
            if(bmp == null) {
                Log.e(TAG, "not a decodable image: " + uriStr);
                return false;
            }

            WallpaperManager wm = WallpaperManager.getInstance(ctx);
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                try {
                    // Home and lock together, in one call, with no system screen.
                    wm.setBitmap(bmp, null, true,
                            WallpaperManager.FLAG_SYSTEM | WallpaperManager.FLAG_LOCK);
                } catch(Throwable t) {
                    // Car head units regularly ship a stripped wallpaper service with no lock
                    // screen at all, and asking for FLAG_LOCK there fails the whole call. The
                    // home screen is the one that matters on a dashboard — set it alone rather
                    // than report failure for a lock screen the car does not have.
                    Log.w(TAG, "home+lock rejected, falling back to home only", t);
                    wm.setBitmap(bmp, null, true, WallpaperManager.FLAG_SYSTEM);
                }
            } else {
                // Pre-N has no per-target flags: this sets the one wallpaper there is.
                wm.setBitmap(bmp);
            }
            if(remember) keepCopy(ctx, bmp);
            return true;
        } catch(Throwable t) {
            // This used to be silent, which is why a missing SET_WALLPAPER permission looked
            // like "video works, images do not" with nothing in the log to say why.
            Log.e(TAG, "setting the still wallpaper failed", t);
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

    /**
     * Start a hand-off screen on the SAME display the caller is running on.
     *
     * These cars have more than one screen — the L946 runs this picker on a 1280x640 passenger
     * panel while the driver's is 5120x1600 — and a plain startActivity with NEW_TASK does not
     * inherit the caller's display: the system picks the default one. An activity that lands on
     * a display whose natural orientation is not the one it was built for comes up turned on its
     * side, which is what "the set-wallpaper screen opens rotated 90°" looks like from the seat.
     *
     * Naming the display is the only part of this we control. If the target screen is the right
     * one and the other app still draws sideways, that is its own layout on that panel and no
     * flag of ours will straighten it.
     *
     * Falls back to a plain start on anything older than O, or when the display cannot be read.
     */
    static void startOnSameDisplay(Context ctx, Intent intent) {
        android.os.Bundle opts = null;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            int displayId = displayIdOf(ctx);
            if(displayId >= 0) {
                try {
                    android.app.ActivityOptions o = android.app.ActivityOptions.makeBasic();
                    o.setLaunchDisplayId(displayId);
                    opts = o.toBundle();
                } catch(Throwable t) {
                    Log.w(TAG, "could not pin the launch to display " + displayId, t);
                }
            }
        }
        ctx.startActivity(intent, opts);
    }

    /** Which screen this context is on, or -1 when it cannot be told. */
    private static int displayIdOf(Context ctx) {
        try {
            android.view.Display d = null;
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Throws on a context with no display (application, service) — hence the catch.
                d = ctx.getDisplay();
            } else if(ctx instanceof android.app.Activity) {
                d = ((android.app.Activity) ctx).getWindowManager().getDefaultDisplay();
            }
            return d == null ? -1 : d.getDisplayId();
        } catch(Throwable t) {
            return -1;
        }
    }
}
