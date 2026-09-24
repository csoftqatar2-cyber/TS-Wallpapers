package systems.sieber.fsclock;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Gives the Haval V7 launcher the complete list of still wallpapers it should offer.
 *
 * This is neither Android's WallpaperManager nor a one-file vendor intent. The launcher owns a
 * pager and observes one Settings.Global key; every write replaces the whole list immediately.
 * Files therefore live at stable public paths, and pruning the folder and rewriting the list are
 * one operation from the picker's point of view.
 */
class HavalApplier {

    private static final String TAG = "HavalApplier";
    static final String KEY_LIST_CENTRAL = "home_wallpaper_list_data_central";
    /** Launcher output only: the path the driver is currently viewing. Never write this key. */
    static final String KEY_CURRENT_CENTRAL = "home_wallpaper_central_change";
    private static final String STAGE_DIR = "HavalV7";

    static final int RESULT_APPLIED = 0;
    static final int RESULT_NO_PERMISSION = 1;
    static final int RESULT_FAILED = 2;
    /**
     * The all-files grant is missing, so the staging folder cannot be written.
     *
     * Its own result because it is a different fix from RESULT_NO_PERMISSION, and neither is
     * visible until a download is attempted: this one needs
     * `adb shell appops set store.thabthaba.clock MANAGE_EXTERNAL_STORAGE allow`.
     */
    static final int RESULT_NO_STORAGE = 3;

    private HavalApplier() { }

    /**
     * Called as each picture finishes staging, so the caller can draw a bar AND tick that one
     * picture the moment it lands rather than only when the whole batch ends.
     *
     * Runs on a worker thread, and on several of them at once - see apply().
     */
    interface Progress { void staged(int done, int total, String url); }

    /** Public folder the GWM launcher reads directly. */
    static File stageDir(Context ctx) {
        File dir = new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_PICTURES), STAGE_DIR);
        if(!dir.exists() && !dir.mkdirs()) Log.e(TAG, "could not create " + dir);
        return dir;
    }

    static boolean hasPermission(Context ctx) {
        return ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** Stage every selected still, prune stale files, then publish the complete launcher list. */
    static int apply(Context ctx, List<WallpaperItem> selection) {
        return apply(ctx, selection, null);
    }

    static int apply(Context ctx, List<WallpaperItem> selection, Progress progress) {
        if(!hasPermission(ctx)) return RESULT_NO_PERMISSION;
        try {
            File dir = stageDir(ctx);
            if(!dir.isDirectory() || !dir.canWrite()) return RESULT_NO_STORAGE;
            JSONArray beans = new JSONArray();
            Set<String> keep = new HashSet<>();
            String current = Settings.Global.getString(ctx.getContentResolver(), KEY_CURRENT_CENTRAL);

            // Staged in parallel, four at a time.
            //
            // One at a time is what a phone would do, and on a car it is the wrong shape: each
            // picture is a separate HTTPS connection over a link whose latency dwarfs its
            // bandwidth, so a twenty-picture library spent most of its time waiting rather than
            // transferring. Four is a deliberate ceiling - these head units have little RAM and
            // a wide fan-out buys nothing once the link is full.
            final java.util.List<WallpaperItem> staging = new java.util.ArrayList<>();
            for(WallpaperItem item : selection) {
                if(item == null || item.url == null || item.isVideo()) continue;
                staging.add(item);
                keep.add(stableName(item.url));
            }
            final int total = staging.size();
            final java.util.concurrent.atomic.AtomicInteger done = new java.util.concurrent.atomic.AtomicInteger();
            final java.util.concurrent.atomic.AtomicBoolean failed = new java.util.concurrent.atomic.AtomicBoolean();
            java.util.concurrent.ExecutorService pool =
                    java.util.concurrent.Executors.newFixedThreadPool(Math.min(4, Math.max(1, total)));
            for(final WallpaperItem item : staging) {
                pool.execute(() -> {
                    File dest = new File(dir, stableName(item.url));
                    if(!stage(ctx, item.url, dest)) failed.set(true);
                    int n = done.incrementAndGet();
                    if(progress != null) progress.staged(n, total, item.url);
                });
            }
            pool.shutdown();
            try {
                // Generous: a slow car link plus a big picture, not a deadline to design around.
                if(!pool.awaitTermination(10, java.util.concurrent.TimeUnit.MINUTES)) {
                    pool.shutdownNow();
                    return RESULT_FAILED;
                }
            } catch(InterruptedException e) {
                pool.shutdownNow();
                Thread.currentThread().interrupt();
                return RESULT_FAILED;
            }
            if(failed.get()) return RESULT_FAILED;

            File[] files = dir.listFiles();
            if(files != null) for(File file : files) {
                if(file.isFile() && !keep.contains(file.getName()) && !file.delete()) {
                    Log.e(TAG, "could not remove stale staged file " + file);
                    return RESULT_FAILED;
                }
            }

            boolean aliveAssigned = false;
            for(WallpaperItem item : selection) {
                if(item == null || item.url == null || item.isVideo()) continue;
                File dest = new File(dir, stableName(item.url));
                boolean alive = current != null && current.equals(dest.getAbsolutePath());
                if(alive) aliveAssigned = true;
                JSONObject bean = new JSONObject();
                bean.put("path", dest.getAbsolutePath());
                bean.put("preview", "");
                bean.put("type", 0);
                bean.put("isAlive", alive);
                beans.put(bean);
            }
            if(!aliveAssigned && beans.length() > 0) beans.getJSONObject(0).put("isAlive", true);
            return write(ctx, payload(beans));
        } catch(SecurityException e) {
            return RESULT_NO_PERMISSION;
        } catch(Throwable t) {
            Log.e(TAG, "apply failed", t);
            return RESULT_FAILED;
        }
    }

    /** Return the launcher to its built-in wallpapers and remove every file staged by us. */
    static int clear(Context ctx) {
        if(!hasPermission(ctx)) return RESULT_NO_PERMISSION;
        try {
            int result = write(ctx, payload(new JSONArray()));
            if(result != RESULT_APPLIED) return result;
            File dir = stageDir(ctx);
            File[] files = dir.listFiles();
            if(files != null) for(File file : files) {
                if(file.isFile() && !file.delete()) Log.e(TAG, "could not delete " + file);
            }
            return RESULT_APPLIED;
        } catch(SecurityException e) {
            return RESULT_NO_PERMISSION;
        } catch(Throwable t) {
            Log.e(TAG, "clear failed", t);
            return RESULT_FAILED;
        }
    }

    private static JSONObject payload(JSONArray beans) throws Exception {
        JSONObject root = new JSONObject();
        root.put("dataSource", 0);
        root.put("bean", beans);
        return root;
    }

    private static int write(Context ctx, JSONObject root) {
        try {
            return Settings.Global.putString(ctx.getContentResolver(), KEY_LIST_CENTRAL,
                    root.toString()) ? RESULT_APPLIED : RESULT_FAILED;
        } catch(SecurityException e) {
            return RESULT_NO_PERMISSION;
        } catch(Throwable t) {
            Log.e(TAG, "settings write failed", t);
            return RESULT_FAILED;
        }
    }

    /** Same stable URL-hash idea used by the picker and Lynkco staging. */
    private static String stableName(String source) {
        return "wp_" + Integer.toHexString(source.hashCode()) + "." + extensionFor(source);
    }

    private static String extensionFor(String source) {
        String value = source.toLowerCase(java.util.Locale.US);
        int query = value.indexOf('?');
        if(query >= 0) value = value.substring(0, query);
        int dot = value.lastIndexOf('.');
        if(dot > value.lastIndexOf('/')) {
            String ext = value.substring(dot + 1);
            if(ext.matches("[a-z0-9]{2,5}")) return ext;
        }
        return "jpg";
    }

    /** Copy through a .part file; equal known sizes make a repeated list write cheap. */
    private static boolean stage(Context ctx, String source, File dest) {
        InputStream in = null;
        FileOutputStream out = null;
        HttpURLConnection connection = null;
        try {
            long expected = -1;
            if(source.startsWith("http://") || source.startsWith("https://")) {
                // The picker downloads a cloud wallpaper the first time it is previewed, so by
                // the time it is being put on the car the bytes are usually already on this
                // disk. Copying them beats fetching them again over the car's connection.
                File cached = LeopardCache.cachedFile(ctx, source);
                if(cached != null) {
                    if(dest.exists() && dest.length() == cached.length()) return true;
                    in = new FileInputStream(cached);
                    expected = cached.length();
                }
                if(in == null) {
                    connection = (HttpURLConnection) new URL(source).openConnection();
                    connection.setConnectTimeout(15000);
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(true);
                    if(connection.getResponseCode() / 100 != 2) return false;
                    expected = connection.getContentLengthLong();
                    if(dest.exists() && expected > 0 && dest.length() == expected) return true;
                    in = connection.getInputStream();
                }
            } else if(source.startsWith("content://")) {
                android.content.res.AssetFileDescriptor afd = null;
                try {
                    afd = ctx.getContentResolver().openAssetFileDescriptor(Uri.parse(source), "r");
                    if(afd != null) expected = afd.getLength();
                } finally { if(afd != null) try { afd.close(); } catch(Exception ignored) { } }
                if(dest.exists() && expected > 0 && dest.length() == expected) return true;
                in = ctx.getContentResolver().openInputStream(Uri.parse(source));
            } else {
                File src = new File(source.startsWith("file://")
                        ? source.substring("file://".length()) : source);
                expected = src.length();
                if(dest.exists() && expected > 0 && dest.length() == expected) return true;
                in = new FileInputStream(src);
            }
            if(in == null) return false;
            File tmp = new File(dest.getAbsolutePath() + ".part");
            out = new FileOutputStream(tmp);
            byte[] buffer = new byte[64 * 1024];
            int read;
            while((read = in.read(buffer)) != -1) out.write(buffer, 0, read);
            out.flush();
            out.close();
            out = null;
            if(tmp.length() == 0 || (expected > 0 && tmp.length() != expected)) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return false;
            }
            if(dest.exists() && !dest.delete()) return false;
            return tmp.renameTo(dest);
        } catch(Throwable t) {
            Log.e(TAG, "staging failed for " + source, t);
            return false;
        } finally {
            if(out != null) try { out.close(); } catch(Exception ignored) { }
            if(in != null) try { in.close(); } catch(Exception ignored) { }
            if(connection != null) connection.disconnect();
        }
    }
}
