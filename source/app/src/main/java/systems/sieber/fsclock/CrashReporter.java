package systems.sieber.fsclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Every crash, written down and sent home.
 *
 * A head unit is the worst place in the world to debug: the car is with a customer in another
 * city, nobody is watching logcat, and by the time anyone reaches it the process has restarted
 * and Android's own log ring has rolled over. A crash that nobody recorded is a crash we get told
 * about as "الشاشة بتفضل سوداء" and nothing else.
 *
 * So two copies, because the two failure modes are different:
 *
 *  - **On the car**, in internal storage, so a technician standing at the unit (or an adb pull)
 *    can read exactly what happened even with no network at all. Settings → Updates shows it.
 *  - **Online**, to the same Supabase project everything else uses, so a fault on car 300 of 450
 *    reaches us without anyone having to go and look. Sent on the next launch after the crash —
 *    a dying process is not a safe place to wait on a car's connection.
 *
 * Nothing here may ever throw: this code runs while the app is already failing, and a crash
 * inside the crash handler is how a fixable bug becomes a boot loop with no evidence.
 */
class CrashReporter {

    private static final String TAG = "CrashReporter";

    /** Internal storage, not cache: the cache is exactly what a low-storage head unit deletes. */
    private static final String DIR = "crashes";

    /** Files still to send keep this name; a sent one gets {@link #SENT_SUFFIX} appended. */
    private static final String PREFIX = "crash-";
    private static final String SENT_SUFFIX = ".sent";

    /** Enough history to see a pattern, few enough that a crash loop cannot fill the disk. */
    private static final int KEEP = 25;

    /** Reading the whole of a runaway log would cost more than it tells us. */
    private static final int MAX_LOGCAT_BYTES = 24 * 1024;
    private static final int MAX_UPLOAD_CHARS = 60 * 1024;

    /**
     * The last few things the app did before it fell over.
     *
     * Release builds strip android.util.Log.v/d/i/w (see proguard-rules.pro), which is right for
     * a shipped product and leaves the "recent log" section of a crash report with almost nothing
     * of ours in it. A breadcrumb is our own method, so it survives, and it is deliberately cheap:
     * a bounded ring of short strings in memory, written out only when something actually breaks.
     */
    private static final int BREADCRUMBS = 40;
    private static final ArrayList<String> TRAIL = new ArrayList<>();

    /** Note something worth seeing in the next crash report. Safe from any thread. */
    static void breadcrumb(String what) {
        if(what == null) return;
        // Also to logcat, at a level ProGuard keeps, so that a car on the bench can be read live
        // over adb instead of only through a crash report that a working app never files. This is
        // how the wallpaper engine's own account of a fault becomes visible next to the platform's.
        android.util.Log.e("fsclock", what);
        synchronized(TRAIL) {
            TRAIL.add(stamp(System.currentTimeMillis()) + "  " + what);
            while(TRAIL.size() > BREADCRUMBS) TRAIL.remove(0);
        }
    }

    private static String trail() {
        synchronized(TRAIL) {
            if(TRAIL.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for(String s : TRAIL) sb.append(s).append('\n');
            return sb.toString();
        }
    }

    private CrashReporter() { }

    // ------------------------------------------------------------------ install

    /**
     * Take over the default uncaught-exception handler, chaining to whatever was there.
     *
     * The chain matters: Android's own handler is what actually kills the process and shows
     * "app has stopped". Swallowing it would leave a half-dead process on screen.
     */
    static void install(final Context context) {
        try {
            final Context app = context.getApplicationContext();
            final Thread.UncaughtExceptionHandler previous =
                    Thread.getDefaultUncaughtExceptionHandler();
            if(previous instanceof Handler) return;   // already installed (double onCreate)
            Thread.setDefaultUncaughtExceptionHandler(new Handler(app, previous));
        } catch(Throwable t) {
            Log.e(TAG, "could not install the crash handler", t);
        }
    }

    private static class Handler implements Thread.UncaughtExceptionHandler {
        private final Context app;
        private final Thread.UncaughtExceptionHandler previous;

        Handler(Context app, Thread.UncaughtExceptionHandler previous) {
            this.app = app;
            this.previous = previous;
        }

        @Override
        public void uncaughtException(Thread thread, Throwable error) {
            try {
                save(app, thread, error);
            } catch(Throwable t) {
                Log.e(TAG, "could not record the crash", t);
            }
            if(previous != null) previous.uncaughtException(thread, error);
        }
    }

    // ------------------------------------------------------------------ writing

    /** Write one crash to disk. Returns the file, or null if even that failed. */
    static File save(Context ctx, Thread thread, Throwable error) {
        try {
            File dir = dir(ctx);
            if(dir == null) return null;

            long now = System.currentTimeMillis();
            String when = stamp(now);

            // A machine-readable first line, so an upload months later still reports the version
            // the crash actually happened on rather than whatever is installed by then.
            JSONObject meta = new JSONObject();
            put(meta, "at", when);
            put(meta, "v", versionName(ctx));
            meta.put("vc", versionCode(ctx));
            put(meta, "mode", mode(ctx));
            put(meta, "id", deviceId(ctx));

            StringBuilder sb = new StringBuilder(4096);
            sb.append("# ").append(meta.toString()).append("\n\n");
            sb.append("=== TS Wallpapers crash ===\n");
            sb.append("time    : ").append(when).append('\n');
            sb.append("app     : ").append(versionName(ctx)).append(" (")
                    .append(versionCode(ctx)).append(")\n");
            sb.append("mode    : ").append(mode(ctx)).append('\n');
            sb.append("device  : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                    .append("  /  Android ").append(Build.VERSION.RELEASE)
                    .append(" (SDK ").append(Build.VERSION.SDK_INT).append(")\n");
            sb.append("car id  : ").append(deviceId(ctx)).append('\n');
            sb.append("thread  : ").append(thread == null ? "?" : thread.getName()).append('\n');
            sb.append("\n--- stack trace ---\n").append(stackTrace(error)).append('\n');

            String trail = trail();
            if(!trail.isEmpty()) sb.append("\n--- what the app was doing ---\n").append(trail);

            String log = recentLog();
            if(!log.isEmpty()) sb.append("\n--- recent log ---\n").append(log).append('\n');

            File f = new File(dir, PREFIX + now + ".txt");
            FileOutputStream out = new FileOutputStream(f);
            out.write(sb.toString().getBytes("UTF-8"));
            out.flush();
            out.close();

            prune(dir);
            Log.e(TAG, "crash recorded to " + f.getAbsolutePath());
            return f;
        } catch(Throwable t) {
            Log.e(TAG, "could not write the crash file", t);
            return null;
        }
    }

    private static String stackTrace(Throwable error) {
        if(error == null) return "(no exception)";
        try {
            StringWriter sw = new StringWriter();
            PrintWriter pw = new PrintWriter(sw);
            error.printStackTrace(pw);
            pw.flush();
            return sw.toString();
        } catch(Throwable t) {
            return String.valueOf(error);
        }
    }

    /**
     * The tail of our own process log — the lines leading up to the crash.
     *
     * An app may only read its own log on modern Android, which is exactly the part that is
     * useful here. On a head unit that refuses even that, the crash file is still written; the
     * section is simply absent.
     */
    private static String recentLog() {
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{
                    "logcat", "-d", "-v", "time", "-t", "300" });
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while((line = r.readLine()) != null && sb.length() < MAX_LOGCAT_BYTES) {
                sb.append(line).append('\n');
            }
            r.close();
            return sb.toString();
        } catch(Throwable t) {
            return "";
        } finally {
            if(p != null) try { p.destroy(); } catch(Throwable ignored) { }
        }
    }

    /** Oldest first out of the door, so a crash loop cannot grow without bound. */
    private static void prune(File dir) {
        try {
            List<File> all = new ArrayList<>(Arrays.asList(listOrEmpty(dir)));
            if(all.size() <= KEEP) return;
            Collections.sort(all, new Comparator<File>() {
                @Override public int compare(File a, File b) {
                    return Long.compare(a.lastModified(), b.lastModified());
                }
            });
            for(int i = 0; i < all.size() - KEEP; i++) //noinspection ResultOfMethodCallIgnored
                all.get(i).delete();
        } catch(Throwable ignored) { }
    }

    // ------------------------------------------------------------------ reading

    static File dir(Context ctx) {
        try {
            File dir = new File(ctx.getFilesDir(), DIR);
            if(!dir.exists() && !dir.mkdirs()) return null;
            return dir;
        } catch(Throwable t) {
            return null;
        }
    }

    private static File[] listOrEmpty(File dir) {
        File[] files = dir == null ? null : dir.listFiles();
        return files == null ? new File[0] : files;
    }

    /** Every recorded crash, newest first — sent or not. */
    static List<File> reports(Context ctx) {
        List<File> out = new ArrayList<>();
        for(File f : listOrEmpty(dir(ctx))) {
            if(f.isFile() && f.getName().startsWith(PREFIX)) out.add(f);
        }
        Collections.sort(out, new Comparator<File>() {
            @Override public int compare(File a, File b) {
                return Long.compare(b.lastModified(), a.lastModified());
            }
        });
        return out;
    }

    static int count(Context ctx) {
        return reports(ctx).size();
    }

    /** The most recent crash as text, or "" when the car has never crashed. */
    static String latest(Context ctx) {
        List<File> all = reports(ctx);
        if(all.isEmpty()) return "";
        return read(all.get(0), MAX_UPLOAD_CHARS);
    }

    static void clear(Context ctx) {
        for(File f : listOrEmpty(dir(ctx))) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    private static String read(File f, int maxChars) {
        try {
            FileInputStream in = new FileInputStream(f);
            byte[] buf = new byte[Math.min(maxChars, (int) Math.max(1, f.length()))];
            int n = in.read(buf);
            in.close();
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } catch(Throwable t) {
            return "";
        }
    }

    // ------------------------------------------------------------------ uploading

    /**
     * Send everything that has not gone up yet. Called on every launch: the crash itself cannot
     * wait on the network (the process is already dying), so the report rides the next start —
     * including the next start after a crash-on-start, which is the case that matters most.
     */
    static void uploadPendingAsync(final Context context) {
        try {
            final Context app = context.getApplicationContext();
            new Thread(new Runnable() {
                @Override public void run() { uploadPending(app); }
            }).start();
        } catch(Throwable ignored) { }
    }

    private static void uploadPending(Context ctx) {
        try {
            String base = WallpaperRepo.getSupabaseUrl();
            if(base == null || base.contains("YOUR_SUPABASE_PROJECT")) return;
            for(File f : reports(ctx)) {
                if(f.getName().endsWith(SENT_SUFFIX)) continue;
                if(upload(ctx, base, f)) {
                    // Renamed rather than deleted: the copy on the car is the one a technician
                    // standing at the unit can read, and it costs a few kilobytes.
                    //noinspection ResultOfMethodCallIgnored
                    f.renameTo(new File(f.getParentFile(), f.getName() + SENT_SUFFIX));
                }
            }
        } catch(Throwable t) {
            Log.w(TAG, "crash upload failed", t);
        }
    }

    private static boolean upload(Context ctx, String base, File f) {
        HttpURLConnection conn = null;
        try {
            String text = read(f, MAX_UPLOAD_CHARS);
            if(text.isEmpty()) return true;   // nothing to send; stop retrying it

            JSONObject meta = metaOf(text);
            JSONObject body = new JSONObject();
            put(body, "device_hw_id", deviceId(ctx));
            String legacy = WallpaperRepo.getLegacyHardwareId(ctx);
            if(legacy != null && !legacy.isEmpty()) put(body, "legacy_hw_id", legacy);
            put(body, "app_version", meta.optString("v", versionName(ctx)));
            body.put("app_version_code", meta.optInt("vc", versionCode(ctx)));
            put(body, "device_mode", meta.optString("mode", mode(ctx)));
            put(body, "crash_at", meta.optString("at", ""));
            put(body, "crash_text", text);

            conn = (HttpURLConnection) new URL(base + "/rest/v1/rpc/report_crash").openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("apikey", WallpaperRepo.getSupabaseKey());
            conn.setRequestProperty("Authorization", "Bearer " + WallpaperRepo.getSupabaseKey());
            conn.setDoOutput(true);
            conn.getOutputStream().write(body.toString().getBytes("UTF-8"));

            int code = conn.getResponseCode();
            // 404 = an older backend with no report_crash yet. Treat it as "sent" or every launch
            // on every car would re-post the same file forever against a route that cannot exist.
            return (code >= 200 && code < 300) || code == 404;
        } catch(Throwable t) {
            Log.w(TAG, "could not send " + f.getName(), t);
            return false;
        } finally {
            if(conn != null) try { conn.disconnect(); } catch(Throwable ignored) { }
        }
    }

    /** The JSON header line written by {@link #save}; an empty object for anything older. */
    private static JSONObject metaOf(String text) {
        try {
            int nl = text.indexOf('\n');
            if(nl > 2 && text.startsWith("# ")) return new JSONObject(text.substring(2, nl).trim());
        } catch(Throwable ignored) { }
        return new JSONObject();
    }

    // ------------------------------------------------------------------ small helpers

    private static void put(JSONObject o, String k, String v) {
        try { o.put(k, v == null ? "" : v); } catch(Throwable ignored) { }
    }

    private static String stamp(long ms) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date(ms));
        } catch(Throwable t) {
            return String.valueOf(ms);
        }
    }

    static String versionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName == null ? "?" : pi.versionName;
        } catch(Throwable t) {
            return "?";
        }
    }

    static int versionCode(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionCode;
        } catch(Throwable t) {
            return 0;
        }
    }

    private static String mode(Context ctx) {
        try {
            SharedPreferences prefs = ctx.getSharedPreferences(
                    BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
            return OperatingMode.wire(prefs);
        } catch(Throwable t) {
            return "?";
        }
    }

    private static String deviceId(Context ctx) {
        try {
            return WallpaperRepo.getHardwareId(ctx);
        } catch(Throwable t) {
            return "UNKNOWN";
        }
    }
}
