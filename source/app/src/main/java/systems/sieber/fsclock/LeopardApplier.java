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
 * Hands a chosen file to Android's wallpaper system, through {@link MediaWallpaperService} —
 * our own live wallpaper — for everything, stills included.
 *
 * That last part is the whole fix for "the wallpaper is gone after the car restarts", and it was
 * arrived at from the car rather than from the docs. Measured on a BYD DiLink 5.1 unit:
 *
 *  - The ROM refuses to start a third-party app from a boot broadcast. Its BroadcastQueue logs
 *    "Self start permission detection ... skip reciever ... ignored" and our process is simply
 *    never created at boot, so reassert() — the repair this class used to lean on — never ran on
 *    the one occasion it existed for.
 *  - A still written with wm.setBitmap() is therefore on its own at boot, and the vendor's
 *    privileged WallpaperHome (which DOES run, on LOCKED_BOOT_COMPLETED) puts its own wallpaper
 *    back. The picture the owner chose lasts until the next start and no further.
 *  - Wallpaper Engine, an ordinary unprivileged app on the same car, survives every restart and
 *    declares no boot receiver at all. All it has is a live wallpaper service. That is the
 *    mechanism: Android itself owns the wallpaper COMPONENT, persists it, and re-binds it at
 *    boot — no self-start needed and nothing for the vendor's routine to overrule.
 *
 * So a still is not set as a bitmap any more; it becomes the picture our live wallpaper draws.
 * The one cost is Android's rule that an app may not install itself as the live wallpaper
 * silently — it must go through ACTION_CHANGE_LIVE_WALLPAPER, where the user presses the system's
 * own button. Android only insists on that for the first wallpaper of any kind; we send every
 * apply through it by choice, so setting a wallpaper looks the same on the tenth picture as on
 * the first. See {@link #needsSystemScreen}.
 *
 * A live wallpaper generally cannot cover the lock screen, so what is set is the home screen.
 * The copy must not promise otherwise.
 */
class LeopardApplier {

    private static final String TAG = "LeopardApplier";

    /** Our own note of what was picked. The engine deliberately does NOT read this — it asks
     *  contentResolver for the real MIME, exactly as the proven reference does. This is only
     *  here so the applier can decide silent-vs-system-screen without touching the file. */
    static final String PREF_TYPE = "leopard-type";

    /** Where our durable copy of the picked still lives — see {@link #keepCopyOf}. */
    private static final String KEEP_DIR = "leopard-current";

    /** What applying this file will actually do, so the UI can warn before it happens. */
    static final int RESULT_NEEDS_SYSTEM_SCREEN = 1; // launch the system picker, once per car
    static final int RESULT_APPLIED_LIVE = 2;        // our service is already active: silent
    static final int RESULT_FAILED = 3;

    /**
     * True when applying this item throws the user into the system's own screen: the
     * live-wallpaper hand-off, for a still exactly as for a video.
     *
     * This is EVERY apply, not only the first one on a car. Android only requires the hand-off
     * once — after our service is the active wallpaper the engine picks up each new picture on
     * its own — but the owner asked for the system screen to appear every time, so that it is
     * always the same act with the same visible confirmation rather than something that changes
     * shape after the first use. The picture itself is already stored and the engine has already
     * reloaded by the time the screen comes up; what the screen adds from the second time on is
     * the confirmation, not the apply.
     *
     * Lynkco is the exception and always was — its stills go to the Flyme theme app, which is not
     * Android's wallpaper system at all, so nothing here applies to them. Only a Lynkco VIDEO
     * falls back to our live wallpaper, because the theme app's file entry point is image-only.
     */
    static boolean needsSystemScreen(Context ctx, String type) {
        if(OperatingMode.isLynkco(prefs(ctx)) && !WallpaperItem.TYPE_VIDEO.equals(type)) return false;
        return true;
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
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

    /**
     * Whether our engine puts this kind of file on the screen with a Canvas — a picture and a GIF —
     * as opposed to handing the surface to a MediaPlayer, which is a video and only a video.
     */
    private static boolean drawnOnCanvas(String type) {
        return !WallpaperItem.TYPE_VIDEO.equals(type);
    }

    /**
     * Make Android build our wallpaper a new surface, by dropping the live wallpaper so that the
     * hand-off screen the caller is about to show binds the service again from scratch.
     *
     * A wallpaper surface can be a Canvas surface or a video surface and never both. Whichever
     * connects first owns it for as long as it exists, and the loser is refused — on a TI7 head
     * unit, with our engine holding the surface for a picture and the player asking for it next:
     *
     *     BufferQueueProducer: [Wallpaper#0] connect: already connected (cur=2 req=3)
     *
     * Nothing throws. The video opens and decodes and posts no frames at all, so the screen keeps
     * showing the last thing that was posted — the picture being replaced — and every video applied
     * afterwards fails the same way, because the surface lives as long as the binding does. The
     * engine cannot fix this from inside itself: asking for a different pixel format brings a
     * surfaceChanged and no new surface. Only a new binding gives a new surface.
     *
     * The cost is a moment of the car's own wallpaper between here and the owner pressing the
     * system's button, which is a screen they are on their way to anyway.
     */
    private static void freshSurfaceNeeded(Context ctx) {
        CrashReporter.breadcrumb("leopard: kind changed — releasing the wallpaper for a new surface");
        clearOurLiveWallpaper(ctx);
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
     * Take this app's task out of the recents list for as long as our live wallpaper is the one
     * on the screen — the single fix for "the wallpaper disappears when I close the app".
     *
     * Swiping a card away in the recents list is not the ordinary task removal it is on a phone.
     * Measured on a Denza (DiLink 6.0) head unit, the vendor's recents FORCE-STOPS the package:
     *
     *     RecentsPresenter: onSwipeUpDelete task = TS Wallpapers
     *     RecentsModel:     Recents forceStopPackage = store.thabthaba.clock
     *     ActivityManager:  Force stopping store.thabthaba.clock ... from pid 2672
     *     WallpaperManagerService: Wallpaper service gone: ...MediaWallpaperService
     *     WallpaperManagerService: Wallpaper uninstalled, removing: ...MediaWallpaperService
     *     WallpaperManagerService: bindWallpaperComponentLocked: componentName=null
     *
     * A force-stop reaches WallpaperManagerService as "this wallpaper's package is GONE", so it
     * does not re-bind us the way it does after an ordinary process death (which is why a car
     * comes back from a restart with the picture intact) — it clears the wallpaper and falls
     * back to the vendor's own. Nothing on our side can undo that afterwards: re-binding a live
     * wallpaper needs SET_WALLPAPER_COMPONENT, a signature permission, and the one entry point
     * an ordinary app has is the system screen with the user's own button on it.
     *
     * Splitting the service into its own process does NOT help either — a force-stop kills every
     * process of the package. The only thing that helps is not being swipeable, and the card is
     * the only thing the owner can swipe. Verified on the car: started with
     * FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS, the vendor's recents reported {@code preloadTaskList
     * size=1} and listed the sibling app only, with ours running the whole time.
     *
     * Scoped to "our wallpaper is live" rather than to the mode, so Others/GWM/Jetour — which
     * draw their own screen and own no wallpaper component — keep an ordinary recents card, and
     * a hand-off car gets one too until the moment it actually has a wallpaper to protect.
     */
    static void keepTaskOutOfRecents(android.app.Activity activity) {
        try {
            if(!isOurServiceActive(activity)) return;
            android.app.ActivityManager am = (android.app.ActivityManager)
                    activity.getSystemService(Context.ACTIVITY_SERVICE);
            if(am == null) return;
            final int myTaskId = activity.getTaskId();
            for(android.app.ActivityManager.AppTask task : am.getAppTasks()) {
                if(idOf(task.getTaskInfo()) != myTaskId) continue;
                task.setExcludeFromRecents(true);
                CrashReporter.breadcrumb("leopard: task " + myTaskId + " hidden from recents");
                return;
            }
        } catch(Throwable t) {
            // A ROM that refuses this leaves the card in place: the old behaviour, not a crash.
            Log.w(TAG, "could not hide the task from recents", t);
        }
    }

    /**
     * Which task an AppTask describes, or -1.
     *
     * {@code RecentTaskInfo.id} is the only field that exists all the way back to our minSdk, and
     * it is documented to read -1 for a task that is not running — never ours, which is the one we
     * are standing in. {@code taskId} replaced it in Q and is the field to trust where it exists;
     * it lives on TaskInfo, a class that does not exist below Q, so the version gate is what keeps
     * an older ROM from resolving a field that is not there.
     */
    private static int idOf(android.app.ActivityManager.RecentTaskInfo info) {
        if(info == null) return -1;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return info.taskId;
        //noinspection deprecation
        return info.id;
    }

    /**
     * True when a wallpaper this app applied is no longer the one on the screen — a card that was
     * swiped away before {@link #keepTaskOutOfRecents} could hide it, "close all", or a force-stop
     * from Settings.
     *
     * Only counts files that went to our own live wallpaper: a Lynkco still lives in the Flyme
     * theme app, which no force-stop of ours can disturb, and claiming otherwise would offer to
     * "restore" a wallpaper that was never lost.
     *
     * Two answers are needed, not one. {@link #isOurServiceActive} is the question Android is
     * meant to answer, and a BYD DiLink 5.1 answers it wrongly: getWallpaperInfo() reports no
     * component while our engine holds a window and paints the picture, so the car asked to
     * restore a wallpaper it was still showing, every time the app was opened. Our own engine
     * count ({@link MediaWallpaperService#isEngineLive}) cannot disagree with reality — and the
     * one case this offer exists for, a force-stop, kills the process that holds it.
     */
    static boolean wasTakenFromUs(Context ctx) {
        try {
            SharedPreferences p = prefs(ctx);
            if(p.getString(MediaWallpaperService.PREF_URI, null) == null) return false;
            if(!needsSystemScreen(ctx, p.getString(PREF_TYPE, null))) return false;
            if(isOurServiceActive(ctx)) return false;
            if(MediaWallpaperService.isEngineLive()) return false;
            // A car whose ROM never hands the wallpaper to our component has nothing to lose and
            // nothing to restore; see PREF_LIVE_OWNED.
            if(!p.getBoolean(PREF_LIVE_OWNED, true)) return false;
            // And a car we are not sure about is asked once per applied wallpaper — never once
            // per launch. See PREF_RESTORE_ASKED_REV.
            return p.getLong(PREF_RESTORE_ASKED_REV, -1L)
                    != p.getLong(MediaWallpaperService.PREF_REV, 0L);
        } catch(Throwable t) {
            return false;
        }
    }

    /**
     * Whether Android has ever actually handed this car's wallpaper to OUR component.
     *
     * Not every head unit does. A BYD DiLink 5.1 takes the picture from the system's
     * live-wallpaper screen and keeps it as a plain bitmap: the owner's picture is on the screen
     * and stays there across a reboot, while {@code mWallpaperComponent} is null and our engine
     * is never bound. Nothing was taken from us there — we were never given it — so the offer to
     * put it back is pure noise, and it was arriving on every single launch.
     *
     * Written by the picker, which is the only place that can see the answer: it already waits a
     * few seconds after the system screen to find out whether the wallpaper became ours
     * (LeopardPickerActivity.awaitSystemScreenResult). True and false are both worth recording.
     *
     * Defaults to TRUE for a car that has not been through an apply since this shipped: the offer
     * exists for cars that lost a wallpaper before the fix reached them, and defaulting to false
     * would quietly retire it for exactly that population.
     */
    static final String PREF_LIVE_OWNED = "leopard-live-owned";

    /**
     * The apply revision ({@link MediaWallpaperService#PREF_REV}) the restore offer was last
     * raised for — the difference between asking about a wallpaper and nagging about it. Written
     * when the dialog is shown, whichever button the owner then presses.
     */
    static final String PREF_RESTORE_ASKED_REV = "leopard-restore-asked-rev";

    static void noteLiveOwnership(Context ctx, boolean owned) {
        try {
            prefs(ctx).edit().putBoolean(PREF_LIVE_OWNED, owned).apply();
            CrashReporter.breadcrumb("leopard: live wallpaper owned by us = " + owned);
        } catch(Throwable t) {
            Log.w(TAG, "could not record live-wallpaper ownership", t);
        }
    }

    /**
     * Store the selection, then apply it.
     *
     * "Apply" is now the same act for every kind of file: point our live wallpaper at it. The
     * engine reloads the moment the stored URI changes, so the picture is already on the screen
     * behind whatever the caller does next.
     *
     * @return one of the RESULT_* constants. RESULT_NEEDS_SYSTEM_SCREEN means the caller must
     *         launch {@link #systemPickerIntent} — which, per {@link #needsSystemScreen}, is now
     *         every apply outside Lynkco stills.
     */
    static int apply(Context ctx, String uriStr, String type) {
        String stored = uriStr;
        boolean moving = WallpaperItem.TYPE_VIDEO.equals(type) || WallpaperItem.TYPE_GIF.equals(type);
        if(!moving) {
            // Where a still is pointed at matters more than it used to: the engine re-reads this
            // URI on every boot, so a cloud picture left pointing into the cache would come back
            // black the first time the head unit runs itself low on space and clears it. One copy
            // in internal storage is a source that is still there months later.
            String kept = keepCopyOf(ctx, uriStr);
            if(kept != null) stored = "file://" + kept;
        }
        // Persist before anything else: the service reads this, and on the needs-system-screen
        // path the system will start us before we get another chance.
        //
        // The revision is what actually reaches a running engine. Every still is copied to the
        // same durable path, so the uri written here is usually the string that is already
        // stored — and SharedPreferences does not call a listener for an unchanged value, so the
        // engine slept through every apply after the first one. See MediaWallpaperService.PREF_REV.
        SharedPreferences p = prefs(ctx);
        boolean kindChanged = drawnOnCanvas(p.getString(PREF_TYPE, null)) != drawnOnCanvas(type);
        p.edit()
                .putString(MediaWallpaperService.PREF_URI, stored)
                .putString(LeopardApplier.PREF_TYPE, type)
                .putLong(MediaWallpaperService.PREF_REV, p.getLong(MediaWallpaperService.PREF_REV, 0) + 1)
                .apply();
        CrashReporter.breadcrumb("leopard: apply " + type + " " + stored);
        // Going from a picture to a video, or back, needs a wallpaper surface that has never been
        // used for the other kind. See freshSurfaceNeeded.
        if(kindChanged && isOurServiceActive(ctx)) freshSurfaceNeeded(ctx);
        // One decision, asked in one place — the picker asks needsSystemScreen() before it starts,
        // and this must not be able to disagree with the answer the user was already given.
        return needsSystemScreen(ctx, type) ? RESULT_NEEDS_SYSTEM_SCREEN : RESULT_APPLIED_LIVE;
    }

    /*
     * There used to be a reassert() here, called from BootReceiver and from the Application's
     * onCreate, which re-set the stored still with wm.setBitmap() every time the app started. It
     * is gone, and deleting it is part of this fix rather than tidying up around it.
     *
     * It could never do its job: on these head units the app is not started at boot at all, so
     * the one moment it existed for never arrived. What it could do was damage. An Application's
     * onCreate runs in EVERY process of the app — including the one the system creates to host
     * MediaWallpaperService. So the sequence on the car was: Android restores our live wallpaper
     * at boot, starts our process to host it, our own onCreate fires, reassert asks
     * getWallpaperInfo() whether we are the active wallpaper and is told "not yet" because the
     * binding is still in flight, and setBitmap then REPLACES the live wallpaper it was supposed
     * to be protecting. Measured on the car: live wallpaper bound at 15:18:36, gone by 15:18:43.
     *
     * A guard would only have narrowed the race. The mechanism does not need help — Android
     * restores the wallpaper component by itself — so the honest fix is to stop touching it.
     */

    /**
     * Copy a picked still into internal storage and return the path our live wallpaper should
     * read from then on, or null when it could not be copied (the caller keeps the original).
     *
     * A byte copy, not a decode-and-re-encode: the engine downsamples to the screen when it
     * loads, so there is nothing to gain from throwing quality away here, and a cheap head unit
     * does not have to hold a 10MP bitmap in memory to file one picture away.
     *
     * Re-applying the picture that is already the wallpaper lands here with source and
     * destination being the same file — which, copied naively, truncates it to nothing and leaves
     * the car with a black screen. Hence the identity check.
     */
    private static String keepCopyOf(Context ctx, String uriStr) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            File dir = new File(ctx.getFilesDir(), KEEP_DIR);
            if(!dir.exists() && !dir.mkdirs()) return null;
            File dest = new File(dir, "current.jpg");
            String plain = uriStr.startsWith("file://") ? uriStr.substring("file://".length()) : uriStr;
            if(dest.getAbsolutePath().equals(plain)) return dest.getAbsolutePath();

            File tmp = new File(dir, "current.tmp");
            in = openAny(ctx, Uri.parse(uriStr));
            if(in == null) return null;
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            if(tmp.length() == 0) { //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            if(dest.exists()) //noinspection ResultOfMethodCallIgnored
                dest.delete();
            if(!tmp.renameTo(dest)) return null;
            return dest.getAbsolutePath();
        } catch(Throwable t) {
            Log.w(TAG, "could not keep a copy of the picked wallpaper", t);
            return null;
        } finally {
            if(in != null) try { in.close(); } catch(Exception ignored) {}
            if(out != null) try { out.close(); } catch(Exception ignored) {}
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
        try {
            ctx.startActivity(intent, opts);
        } catch(SecurityException e) {
            // Naming a display is a privilege, not a right. SafeActivityOptions.checkPermissions
            // rejects launchDisplayId for a display this app does not own — which happens when the
            // picker is itself running inside another app's window (the THABTHABA dashboard hosts
            // it on a virtual display, and pressing "set wallpaper" crashed here). Losing the
            // display hint only risks the rotation this method exists to avoid; losing the app
            // loses everything, so drop the hint and start it plainly.
            Log.w(TAG, "display hint refused, starting without it", e);
            ctx.startActivity(intent);
        }
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
