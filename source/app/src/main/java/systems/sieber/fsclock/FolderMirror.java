package systems.sieber.fsclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import org.json.JSONArray;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A folder mirror — a deliberately self-contained side channel that has NOTHING to do with how
 * this app shows wallpapers.
 *
 * Some head units run a SEPARATE app that reads its background images from a fixed folder on
 * shared storage. This class mirrors one cloud channel into one such folder: images the operator
 * tagged for all cars of that make, plus any tagged for this specific car. Our own slideshow, the
 * operating modes and the normal wallpaper channel are untouched — a car only ever does any of
 * this while it is in the matching mode.
 *
 * "Mirror" means the folder ends up holding exactly the current manifest: new images are
 * downloaded, images dropped from the cloud are deleted. To avoid ever deleting a file some other
 * app placed there, only files THIS class wrote (tracked in prefs) are eligible for deletion.
 *
 * Two mirrors exist, and they are the same mechanism twice over with different addresses:
 *   {@link #GWM}    — 'gwm_split' -> /sdcard/Pictures/GWMSplit_Styles, for GWM head units.
 *   {@link #JETOUR} — 'jetour_g700' -> /sdcard/Pictures/G700, for the Jetour G700.
 * Everything below is shared between them on purpose: this class used to be GwmSync, and a
 * second copy of it would have meant fixing every download bug twice. Each instance keeps its
 * OWN pref keys — in particular its own managed-files list, or one mirror's pass would happily
 * delete the other's pictures.
 */
class FolderMirror {

    private static final String TAG = "FolderMirror";

    /** The GWM Split channel. Pref keys are the originals, so cars in the field carry on unchanged. */
    static final FolderMirror GWM = new FolderMirror(
            OperatingMode.GWM,
            "gwm-split-folder", "gwm-split-managed-files",
            "GWMSplit_Styles", "get_gwm_wallpapers", "gwm_");

    /** Jetour G700: same idea, its own folder and its own channel. */
    static final FolderMirror JETOUR = new FolderMirror(
            OperatingMode.JETOUR,
            "jetour-g700-folder", "jetour-g700-managed-files",
            "G700", "get_jetour_wallpapers", "jetour_");

    /** Every mirror there is, for the callers that just want to kick "whatever applies". */
    static final FolderMirror[] ALL = { GWM, JETOUR };

    /** The operating mode that switches this mirror on — the mode IS the enable flag. */
    final int mode;
    private final String prefFolder;
    /** JSON array of the file names we created in the folder, so we never delete a stranger's file. */
    private final String prefManaged;
    private final String folderName;
    private final String rpc;
    private final String fallbackPrefix;

    private FolderMirror(int mode, String prefFolder, String prefManaged,
                         String folderName, String rpc, String fallbackPrefix) {
        this.mode = mode;
        this.prefFolder = prefFolder;
        this.prefManaged = prefManaged;
        this.folderName = folderName;
        this.rpc = rpc;
        this.fallbackPrefix = fallbackPrefix;
    }

    /**
     * The mirror this car should be running, or null for a car in a mode that has none.
     *
     * There is exactly one at a time because the modes are exclusive: a GWM car mirrors the GWM
     * folder, a Jetour car the G700 folder, everybody else nothing at all.
     */
    static FolderMirror active(SharedPreferences p) {
        int m = OperatingMode.get(p);
        for(FolderMirror f : ALL) if(f.mode == m) return f;
        return null;
    }

    /**
     * The path the companion app reads by default. Editable in prefs for other integrations.
     *
     * For GWM this is deliberately the SAME folder the Cars-installer script pushes photos to
     * when a technician sets up a car by cable (/sdcard/Pictures/GWMSplit_Styles — see
     * Copy-ImagesToDevice in "Cars installer.ps1"). Cloud delivery and cable delivery must land
     * in one place, or the head unit ends up with two half-full galleries.
     */
    String defaultFolder() {
        File pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        return new File(pics, folderName).getAbsolutePath();
    }

    /**
     * The mirror runs on exactly one condition: this install is in the matching mode. The mode
     * picker IS the on-switch — there is no separate enable flag.
     */
    boolean isEnabled(SharedPreferences p) {
        return OperatingMode.get(p) == mode;
    }

    String folder(SharedPreferences p) {
        String f = p.getString(prefFolder, "").trim();
        return f.isEmpty() ? defaultFolder() : f;
    }

    /**
     * Whether we can actually write into shared storage. On Android 11+ this is the special
     * all-files access; below that it is the classic write permission. The settings screen uses
     * this to decide whether to send the user to the grant screen before enabling the sync.
     */
    static boolean hasStoragePermission(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    interface Callback {
        /** @param wrote files now in the folder, or -1 on failure. */
        void done(int wrote, String error);
    }

    /**
     * When this mirror's last pass started. The mirror is kicked from every "the app is in front
     * of the user" moment — process start, the clock view attaching, and every resume — on top of
     * the 5-minute timer, so a newly published image lands without waiting. Those triggers can
     * fire two or three times within a second of each other (launch = attach + resume), and
     * running the whole pass that often is pure waste: nothing can have changed server-side in
     * that window. A pass inside the window is therefore skipped.
     */
    private volatile long lastRunMs = 0L;
    private static final long MIN_INTERVAL_MS = 30_000;

    /** Run one mirror pass on a background thread. Safe to call whenever; it no-ops when disabled. */
    void syncAsync(final Context appCtx, final WallpaperRepo repo, final Callback cb) {
        syncAsync(appCtx, repo, cb, false);
    }

    /**
     * @param force run even if a pass just ran. The settings "sync now" button passes true: a
     *              technician who taps it is owed a real attempt, not a silent skip.
     */
    void syncAsync(final Context appCtx, final WallpaperRepo repo, final Callback cb,
                   boolean force) {
        long now = android.os.SystemClock.elapsedRealtime();
        if (!force && lastRunMs != 0L && now - lastRunMs < MIN_INTERVAL_MS) {
            if (cb != null) cb.done(0, null);
            return;
        }
        lastRunMs = now;
        new Thread(new Runnable() {
            @Override public void run() {
                int r;
                String err = null;
                try {
                    r = syncBlocking(appCtx, repo);
                } catch (Throwable t) {
                    Log.e(TAG, "sync failed (" + rpc + ")", t);
                    r = -1;
                    err = t.getMessage();
                }
                if (cb != null) cb.done(r, err);
            }
        }).start();
    }

    /** @return number of files in the folder after the mirror, or throws on a hard failure. */
    int syncBlocking(Context appCtx, WallpaperRepo repo) throws Exception {
        SharedPreferences p = appCtx.getSharedPreferences(
                SettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        if (!isEnabled(p)) return 0;
        if (!hasStoragePermission(appCtx)) {
            throw new Exception("no storage permission");
        }

        File dir = new File(folder(p));
        if (!dir.exists() && !dir.mkdirs()) {
            throw new Exception("cannot create " + dir.getAbsolutePath());
        }

        List<WallpaperItem> items = repo.fetchChannelItems(rpc);

        // Desired file names, derived from the (UUID) URLs so they are stable and collision-free.
        Set<String> desired = new HashSet<>();
        for (WallpaperItem it : items) {
            if (it == null || it.url == null) continue;
            desired.add(fileNameFor(it.url));
        }

        Set<String> managed = loadManaged(p);
        List<String> touched = new ArrayList<>();   // paths to hand to the media scanner

        // 1) delete files we previously wrote that are no longer wanted
        for (String name : new HashSet<>(managed)) {
            if (!desired.contains(name)) {
                File f = new File(dir, name);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                managed.remove(name);
                touched.add(f.getAbsolutePath());
            }
        }

        // 2) download anything missing
        for (WallpaperItem it : items) {
            if (it == null || it.url == null) continue;
            String name = fileNameFor(it.url);
            File dest = new File(dir, name);
            // Already on disk => never fetched again. The name comes from the image's UUID, so
            // "same name" really does mean "same image": a car that has been mirroring for
            // months re-downloads nothing on each pass, only what the operator just added.
            if (dest.exists() && dest.length() > 0) {
                managed.add(name);
                continue;
            }
            if (download(it.url, dest)) {
                managed.add(name);
                touched.add(dest.getAbsolutePath());
            }
        }

        saveManaged(p, managed);
        mediaScan(appCtx, touched);

        File[] now = dir.listFiles();
        return now == null ? 0 : now.length;
    }

    /**
     * Tell Android's media database about files we just wrote or removed.
     *
     * The folder is only half the contract: the head unit's own background app finds its
     * pictures through MediaStore, not by listing the directory, so a freshly downloaded image
     * stays invisible to it until the file is scanned — and a deleted one keeps being offered.
     * The Cars-installer script does exactly this after pushing photos to the same folder
     * (`am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures`);
     * doing it here is what makes a cloud-delivered image behave like a hand-pushed one.
     *
     * No-op when nothing changed, so a steady-state pass costs nothing.
     */
    private static void mediaScan(Context appCtx, List<String> paths) {
        if (paths == null || paths.isEmpty()) return;
        try {
            android.media.MediaScannerConnection.scanFile(
                    appCtx, paths.toArray(new String[0]), null, null);
        } catch (Throwable t) {
            Log.w(TAG, "media scan failed", t);
        }
    }

    /** Stable on-disk name for a wallpaper URL: keep the (already unique) last path segment. */
    private String fileNameFor(String url) {
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int slash = u.lastIndexOf('/');
        String name = slash >= 0 ? u.substring(slash + 1) : u;
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty()) name = fallbackPrefix + Integer.toHexString(url.hashCode());
        return name;
    }

    private static boolean download(String url, File dest) {
        HttpURLConnection c = null;
        InputStream in = null;
        FileOutputStream out = null;
        File tmp = new File(dest.getAbsolutePath() + ".part");
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() / 100 != 2) return false;
            in = c.getInputStream();
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            // Publish atomically, so a dropped download never leaves a half file the other app reads.
            if (dest.exists()) //noinspection ResultOfMethodCallIgnored
                dest.delete();
            return tmp.renameTo(dest);
        } catch (Throwable t) {
            Log.w(TAG, "download failed: " + url, t);
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (Exception ignored) {}
            if (in != null) try { in.close(); } catch (Exception ignored) {}
            if (c != null) c.disconnect();
            if (tmp.exists()) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    private Set<String> loadManaged(SharedPreferences p) {
        Set<String> s = new HashSet<>();
        try {
            JSONArray a = new JSONArray(p.getString(prefManaged, "[]"));
            for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
        } catch (Exception ignored) {}
        return s;
    }

    private void saveManaged(SharedPreferences p, Set<String> s) {
        JSONArray a = new JSONArray();
        for (String n : s) a.put(n);
        p.edit().putString(prefManaged, a.toString()).apply();
    }

    /**
     * Save a locally-provided file (e.g. a QR upload) straight into the mirrored folder, and track
     * it so a later mirror does not treat it as a stranger's file and keep it forever. Returns the
     * saved path or null.
     */
    String saveLocalCopy(Context appCtx, InputStream in, String displayName) {
        try {
            SharedPreferences p = appCtx.getSharedPreferences(
                    SettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
            File dir = new File(folder(p));
            if (!dir.exists() && !dir.mkdirs()) return null;
            String name = (displayName == null || displayName.trim().isEmpty())
                    ? (fallbackPrefix + System.currentTimeMillis() + ".jpg")
                    : displayName.replaceAll("[\\\\/:*?\"<>|]", "_");
            File dest = new File(dir, name);
            // Avoid clobbering an existing name from a different image.
            if (dest.exists()) {
                int dot = name.lastIndexOf('.');
                String base = dot >= 0 ? name.substring(0, dot) : name;
                String ext = dot >= 0 ? name.substring(dot) : "";
                dest = new File(dir, base + "_" + System.currentTimeMillis() + ext);
            }
            FileOutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            out.close();
            Set<String> managed = loadManaged(p);
            managed.add(dest.getName());
            saveManaged(p, managed);
            mediaScan(appCtx, java.util.Collections.singletonList(dest.getAbsolutePath()));
            return dest.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "saveLocalCopy failed", t);
            return null;
        }
    }
}
