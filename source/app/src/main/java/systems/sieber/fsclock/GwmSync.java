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
 * The GWM Split feature — a deliberately self-contained side channel that has NOTHING to do with
 * how this app shows wallpapers.
 *
 * A separate app on some Great Wall (GWM) head units reads its background images from a fixed
 * folder on shared storage. This mirrors the "gwm_split" channel of our cloud into that folder:
 * images the operator tagged for all GWM cars, plus any tagged for this specific car. Our own
 * slideshow, the operating modes, and the normal wallpaper channel are untouched — a car only
 * ever does any of this when the technician turns the section on.
 *
 * "Mirror" means the folder ends up holding exactly the current manifest: new images are
 * downloaded, images dropped from the cloud are deleted. To avoid ever deleting a file some other
 * app placed there, only files THIS class wrote (tracked in prefs) are eligible for deletion.
 */
class GwmSync {

    private static final String TAG = "GwmSync";

    static final String PREF_ENABLED = "gwm-split-enabled";
    static final String PREF_FOLDER  = "gwm-split-folder";
    /** JSON array of the file names we created in the folder, so we never delete a stranger's file. */
    private static final String PREF_MANAGED = "gwm-split-managed-files";

    /** The path the GWM Split app reads by default. Editable in settings for other integrations. */
    static String defaultFolder() {
        File pics = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        return new File(pics, "GWMSplit_Styles").getAbsolutePath();
    }

    static boolean isEnabled(SharedPreferences p) {
        return p.getBoolean(PREF_ENABLED, false);
    }

    static String folder(SharedPreferences p) {
        String f = p.getString(PREF_FOLDER, "").trim();
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

    /** Run one mirror pass on a background thread. Safe to call whenever; it no-ops when disabled. */
    static void syncAsync(final Context appCtx, final WallpaperRepo repo, final Callback cb) {
        new Thread(new Runnable() {
            @Override public void run() {
                int r;
                String err = null;
                try {
                    r = syncBlocking(appCtx, repo);
                } catch (Throwable t) {
                    Log.e(TAG, "gwm sync failed", t);
                    r = -1;
                    err = t.getMessage();
                }
                if (cb != null) cb.done(r, err);
            }
        }).start();
    }

    /** @return number of files in the folder after the mirror, or throws on a hard failure. */
    static int syncBlocking(Context appCtx, WallpaperRepo repo) throws Exception {
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

        List<WallpaperItem> items = repo.fetchGwmItems();

        // Desired file names, derived from the (UUID) URLs so they are stable and collision-free.
        Set<String> desired = new HashSet<>();
        for (WallpaperItem it : items) {
            if (it == null || it.url == null) continue;
            desired.add(fileNameFor(it.url));
        }

        Set<String> managed = loadManaged(p);

        // 1) delete files we previously wrote that are no longer wanted
        for (String name : new HashSet<>(managed)) {
            if (!desired.contains(name)) {
                File f = new File(dir, name);
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                managed.remove(name);
            }
        }

        // 2) download anything missing
        for (WallpaperItem it : items) {
            if (it == null || it.url == null) continue;
            String name = fileNameFor(it.url);
            File dest = new File(dir, name);
            if (dest.exists() && dest.length() > 0) {
                managed.add(name);
                continue;
            }
            if (download(it.url, dest)) {
                managed.add(name);
            }
        }

        saveManaged(p, managed);

        File[] now = dir.listFiles();
        return now == null ? 0 : now.length;
    }

    /** Stable on-disk name for a wallpaper URL: keep the (already unique) last path segment. */
    private static String fileNameFor(String url) {
        String u = url;
        int q = u.indexOf('?');
        if (q >= 0) u = u.substring(0, q);
        int slash = u.lastIndexOf('/');
        String name = slash >= 0 ? u.substring(slash + 1) : u;
        name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (name.isEmpty()) name = "gwm_" + Integer.toHexString(url.hashCode());
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

    private static Set<String> loadManaged(SharedPreferences p) {
        Set<String> s = new HashSet<>();
        try {
            JSONArray a = new JSONArray(p.getString(PREF_MANAGED, "[]"));
            for (int i = 0; i < a.length(); i++) s.add(a.getString(i));
        } catch (Exception ignored) {}
        return s;
    }

    private static void saveManaged(SharedPreferences p, Set<String> s) {
        JSONArray a = new JSONArray();
        for (String n : s) a.put(n);
        p.edit().putString(PREF_MANAGED, a.toString()).apply();
    }

    /**
     * Save a locally-provided file (e.g. a QR upload) straight into the GWM folder, and track it
     * so a later mirror does not treat it as a stranger's file and keep it forever. Returns the
     * saved path or null.
     */
    static String saveLocalCopy(Context appCtx, InputStream in, String displayName) {
        try {
            SharedPreferences p = appCtx.getSharedPreferences(
                    SettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
            File dir = new File(folder(p));
            if (!dir.exists() && !dir.mkdirs()) return null;
            String name = (displayName == null || displayName.trim().isEmpty())
                    ? ("gwm_" + System.currentTimeMillis() + ".jpg")
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
            return dest.getAbsolutePath();
        } catch (Throwable t) {
            Log.e(TAG, "saveLocalCopy failed", t);
            return null;
        }
    }
}
