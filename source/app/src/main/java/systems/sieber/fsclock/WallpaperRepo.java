package systems.sieber.fsclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Holds the wallpaper playlist that the shop owner controls through a single
 * remote manifest URL. The manifest is fetched once, cached in SharedPreferences
 * (so it also works offline) and videos are pre-downloaded into the cache dir for
 * smooth looping playback.
 *
 * Supported manifest formats (all auto-detected):
 *   1) {"wallpapers":[ {"type":"image","url":"..."}, {"type":"video","url":"..."} ]}
 *   2) ["https://a.jpg", "https://b.gif", "https://c.mp4"]
 *   3) [ {"url":"..."}, ... ]                 (type guessed from file extension)
 */
public class WallpaperRepo {

    private static final String TAG = "WallpaperRepo";

    // Supabase credentials are obfuscated – see StringObfuscator
    static String getSupabaseUrl()  { return StringObfuscator.supabaseUrl(); }
    static String getSupabaseKey()  { return StringObfuscator.supabaseKey(); }

    static final String PREF_URL = "wallpaper-manifest-url";
    static final String PREF_ENABLED = "wallpaper-enabled";
    static final String PREF_LOCAL_ENABLED = "wallpaper-local-enabled";
    static final String PREF_INDEX = "wallpaper-index";
    static final String PREF_CACHE = "wallpaper-cache-json";
    static final String PREF_DEFAULT = "wallpaper-default-path";
    /** url of the wallpaper that was on screen last, so a restart of the car resumes on it
     *  even when the playlist order changed in the meantime (an index alone would drift). */
    static final String PREF_LAST_URL = "wallpaper-last-url";
    static final String PREF_DEVICE_ID = "device-id";
    static final String PREF_ACTIVE = "device-active";
    static final String PREF_AUTO_SWITCH = "wallpaper-auto-switch";

    /** How long one wallpaper stays on screen before the slideshow moves on, in seconds. */
    static final String PREF_AUTO_SWITCH_INTERVAL = "wallpaper-auto-switch-interval";
    static final int[] AUTO_SWITCH_INTERVAL_VALUES = {30, 60, 5 * 60, 10 * 60, 60 * 60};
    static final int AUTO_SWITCH_INTERVAL_DEFAULT = 60;

    /** JSON array of wallpaper urls the owner of THIS device chose to hide from the
     *  slideshow. Purely local: it never touches the server, so a global wallpaper can be
     *  hidden on one car while every other car keeps showing it. */
    static final String PREF_HIDDEN = "wallpaper-hidden-urls";

    /** The auto-switch period in ms, clamped to a value the UI can actually produce. */
    public static long getAutoSwitchIntervalMs(SharedPreferences pref) {
        int seconds = (pref == null) ? AUTO_SWITCH_INTERVAL_DEFAULT
                : pref.getInt(PREF_AUTO_SWITCH_INTERVAL, AUTO_SWITCH_INTERVAL_DEFAULT);
        boolean known = false;
        for(int v : AUTO_SWITCH_INTERVAL_VALUES) if(v == seconds) known = true;
        if(!known) seconds = AUTO_SWITCH_INTERVAL_DEFAULT;
        return seconds * 1000L;
    }

    public static String getMacAddress() {
        try {
            java.util.List<java.net.NetworkInterface> interfaces = java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces());
            for (java.net.NetworkInterface intf : interfaces) {
                if (!intf.getName().equalsIgnoreCase("wlan0") && !intf.getName().equalsIgnoreCase("eth0")) {
                    continue;
                }
                byte[] mac = intf.getHardwareAddress();
                if (mac == null) continue;
                StringBuilder buf = new StringBuilder();
                for (byte aMac : mac) {
                    buf.append(String.format("%02X:", aMac));
                }
                if (buf.length() > 0) {
                    buf.deleteCharAt(buf.length() - 1);
                }
                return buf.toString();
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    public static String getBuildSerial() {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                return android.os.Build.getSerial();
            } else {
                return android.os.Build.SERIAL;
            }
        } catch (SecurityException ignored) {
        }
        return "";
    }

    public static String getSystemProperty(String key) {
        try {
            Class<?> c = Class.forName("android.os.SystemProperties");
            java.lang.reflect.Method get = c.getMethod("get", String.class);
            return (String) get.invoke(c, key);
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Read the vehicle VIN from Android Automotive system properties. This value
     *  is sourced from the car itself, so it survives a factory reset of the head
     *  unit. Empty string when not running on a vehicle head unit. */
    public static String getVin() {
        String[] keys = {
            "persist.sys.vehicle.vin", "sys.vehicle.hardware.vin.code",
            // BYD DiLink (Leopard / 豹5 and friends) – same value in all four,
            // the persist.* ones survive a factory reset of the head unit.
            "persist.sys.dms.config.vin", "persist.sys.cloud.last_vin",
            "sys.dms.config.vin", "sys.virtual.vin"
        };
        for (String k : keys) {
            String v = getSystemProperty(k);
            if (v != null) {
                v = v.trim();
                if (v.length() >= 8 && !v.equalsIgnoreCase("unknown")) {
                    return v;
                }
            }
        }
        return "";
    }

    public static String getHardwareId(Context context) {
        // 0. Vehicle VIN (Android Automotive) – tied to the car, survives factory reset.
        String vin = getVin();
        if (!vin.isEmpty()) {
            return "VIN-" + vin;
        }
        // No VIN (non-vehicle device) -> fall back to the legacy identifier chain.
        return getLegacyHardwareId(context);
    }

    /** The identifier used by builds before VIN support. Kept byte-for-byte identical
     *  so it reproduces exactly what an already-activated device was registered under,
     *  letting the server migrate it to its VIN without breaking activation. */
    public static String getLegacyHardwareId(Context context) {
        // 1. Try MAC address (wlan0 or eth0)
        String mac = getMacAddress();
        if (mac != null && !mac.isEmpty() && !mac.equals("02:00:00:00:00:00")) {
            return "MAC-" + mac.replace(":", "");
        }
        
        // 2. Try System Property ro.serialno
        String sysSerial = getSystemProperty("ro.serialno");
        if (sysSerial != null && !sysSerial.isEmpty() && !sysSerial.equalsIgnoreCase("unknown")) {
            return "SYS-" + sysSerial;
        }

        String bootSerial = getSystemProperty("ro.boot.serialno");
        if (bootSerial != null && !bootSerial.isEmpty() && !bootSerial.equalsIgnoreCase("unknown")) {
            return "BOOT-" + bootSerial;
        }

        // 3. Try Build.SERIAL / Build.getSerial()
        String buildSerial = getBuildSerial();
        if (buildSerial != null && !buildSerial.isEmpty() && !buildSerial.equalsIgnoreCase("unknown")) {
            return "SRL-" + buildSerial;
        }

        // 4. Fallback to Android ID
        String androidId = android.provider.Settings.Secure.getString(
            context.getContentResolver(), 
            android.provider.Settings.Secure.ANDROID_ID
        );
        if (androidId != null && !androidId.isEmpty()) {
            return "AID-" + androidId;
        }
        
        return "UNKNOWN";
    }

    public String getDeviceId() {
        return getHardwareId(mContext);
    }

    /** Pre-VIN identifier, sent alongside the VIN so the server can migrate
     *  already-activated devices. Equal to getDeviceId() on non-vehicle devices. */
    public String getLegacyDeviceId() {
        return getLegacyHardwareId(mContext);
    }

    public boolean isActive() {
        String sbUrl = getSupabaseUrl();
        if (sbUrl.contains("YOUR_SUPABASE_PROJECT")) {
            return true; // Bypass activation if Supabase is not configured yet
        }
        // Use SecurePrefs (HMAC-protected) so manual XML edits are detected
        boolean active = mSecurePref.getBoolean(PREF_ACTIVE, false);
        // Also verify APK integrity
        if (active && !IntegrityGuard.isSignatureValid(mContext)) {
            return false; // Tampered APK – reject
        }
        return active;
    }

    public String getSyncUrl() {
        String url = mPref.getString(PREF_URL, "").trim();
        if (url.isEmpty()) {
            String sbUrl = getSupabaseUrl();
            if (!sbUrl.contains("YOUR_SUPABASE_PROJECT")) {
                url = sbUrl + "/rest/v1/rpc/get_wallpapers?device_hw_id=" + getDeviceId()
                        + "&legacy_hw_id=" + getLegacyDeviceId();
            }
        }
        return url;
    }

    /** sub-folder inside the app's external files dir where the shop can drop wallpapers. */
    static final String LOCAL_DIR = "Wallpapers";

    private final Context mContext;
    private final SharedPreferences mPref;
    private final SecurePrefs mSecurePref;

    // Written by load() (which sync() calls from a background thread) and read by the
    // UI thread (current/next/prev). volatile + read-side snapshots keep the two safe
    // without locking; the list itself is never mutated after assignment.
    private volatile List<WallpaperItem> mItems = new ArrayList<>();   // what the slideshow plays (hidden ones removed)
    private volatile List<WallpaperItem> mAllItems = new ArrayList<>(); // everything available, hidden included
    private volatile int mIndex = 0;

    public WallpaperRepo(Context c) {
        mContext = c.getApplicationContext();
        mPref = mContext.getSharedPreferences(SettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        mSecurePref = new SecurePrefs(mContext, mPref);
        load();
    }

    /** (Re)read the playlist (local folder + cached remote manifest) and current position. */
    public void load() {
        List<WallpaperItem> all = new ArrayList<>();
        if(mPref.getBoolean(PREF_LOCAL_ENABLED, true)) {
            all.addAll(scanLocalFolder());
        }
        all.addAll(parse(mPref.getString(PREF_CACHE, "")));

        // drop the wallpapers this device was told to hide (see PREF_HIDDEN)
        java.util.Set<String> hidden = getHiddenUrls();
        List<WallpaperItem> merged = new ArrayList<>();
        for(WallpaperItem it : all) {
            if(it.url == null || !hidden.contains(it.url)) merged.add(it);
        }

        int index = mPref.getInt(PREF_INDEX, 0);
        if(merged.isEmpty()) {
            index = 0;
        } else {
            index = ((index % merged.size()) + merged.size()) % merged.size();
        }
        // resume on the wallpaper that was on screen last (survives a restart of the car
        // and stays right even if the playlist grew/shrank since then)
        String last = mPref.getString(PREF_LAST_URL, "");
        boolean resumed = false;
        if(!last.isEmpty()) {
            for(int i = 0; i < merged.size(); i++) {
                if(last.equals(merged.get(i).url)) { index = i; resumed = true; break; }
            }
        }
        // nothing to resume (first run, or that wallpaper is gone/hidden now): fall back to
        // the default wallpaper (e.g. the one the customer uploaded via QR), if it is there
        if(!resumed) {
            String def = mPref.getString(PREF_DEFAULT, "");
            if(!def.isEmpty()) {
                for(int i = 0; i < merged.size(); i++) {
                    if(def.equals(merged.get(i).url)) { index = i; break; }
                }
            }
        }
        // publish fully-built state; readers snapshot mItems so they never see a
        // list/index pair from two different loads
        mAllItems = all;
        mItems = merged;
        mIndex = index;
    }

    /** Every wallpaper available to this device, hidden ones included (settings UI). */
    public List<WallpaperItem> allItems() {
        return new ArrayList<>(mAllItems);
    }

    /** Urls the owner hid on this device. */
    public java.util.Set<String> getHiddenUrls() {
        java.util.Set<String> set = new java.util.HashSet<>();
        String json = mPref.getString(PREF_HIDDEN, "");
        if(json.trim().isEmpty()) return set;
        try {
            JSONArray arr = new JSONArray(json);
            for(int i = 0; i < arr.length(); i++) {
                String u = arr.optString(i, "");
                if(!u.isEmpty()) set.add(u);
            }
        } catch(Exception e) {
            Log.w(TAG, "hidden list parse failed", e);
        }
        return set;
    }

    /** Replace the hidden list and rebuild the playlist immediately. */
    public void setHiddenUrls(java.util.Collection<String> urls) {
        JSONArray arr = new JSONArray();
        for(String u : urls) {
            if(u != null && !u.trim().isEmpty()) arr.put(u);
        }
        mPref.edit().putString(PREF_HIDDEN, arr.toString()).apply();
        load();
    }

    /** The on-device folder where the shop can drop images/GIFs/videos (no permission needed). */
    public File getLocalFolder() {
        File dir = new File(mContext.getExternalFilesDir(null), LOCAL_DIR);
        if(!dir.exists()) dir.mkdirs();
        return dir;
    }

    /** Scan the local folder for supported media files, sorted by name. */
    private List<WallpaperItem> scanLocalFolder() {
        List<WallpaperItem> list = new ArrayList<>();
        File dir = getLocalFolder();
        File[] files = dir.listFiles();
        if(files == null) return list;
        Arrays.sort(files, new java.util.Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        for(File f : files) {
            if(f.isFile() && isSupportedMedia(f.getName())) {
                list.add(new WallpaperItem(WallpaperItem.guessType(f.getName()), f.getAbsolutePath()));
            }
        }
        return list;
    }

    private boolean isSupportedMedia(String name) {
        String u = name.toLowerCase();
        return u.endsWith(".jpg") || u.endsWith(".jpeg") || u.endsWith(".png")
                || u.endsWith(".webp") || u.endsWith(".bmp")
                || u.endsWith(".gif")
                || u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".mkv")
                || u.endsWith(".3gp") || u.endsWith(".m4v") || u.endsWith(".mov");
    }

    public boolean isEnabled() {
        return mPref.getBoolean(PREF_ENABLED, true) && !mItems.isEmpty();
    }

    // --- FSE per-wallpaper focal point (where in the image should be centered on the
    //     fixed 1920x720 screen). Stored as "fx,fy" fractions (0..1) keyed by the
    //     wallpaper url, so it works the same for global and per-car private images.
    private static final String FOCAL_PREFIX = "wp-focal:";

    /**
     * The key a wallpaper's fit and focal are stored under.
     *
     * The same local image reaches us under two spellings: the import and phone-upload paths
     * open the editor with "file:///storage/…", while localItems() builds its WallpaperItems
     * from File.getAbsolutePath(), which is bare "/storage/…". Unnormalised, the editor wrote
     * one key and the renderer read the other, so every edit made on import was silently
     * thrown away. Remote urls have no scheme to strip and pass through untouched.
     */
    private static String fitKey(String url) {
        if(url == null) return null;
        return url.startsWith("file://") ? url.substring("file://".length()) : url;
    }

    /** Saved focal point for a wallpaper, or the image center (0.5,0.5) if none. */
    public float[] getFocal(String url) {
        if(url != null) {
            String v = mPref.getString(FOCAL_PREFIX + fitKey(url), "");
            if(!v.isEmpty()) {
                try {
                    String[] p = v.split(",");
                    return new float[]{ clamp01(Float.parseFloat(p[0])), clamp01(Float.parseFloat(p[1])) };
                } catch(Exception ignored) {}
            }
        }
        return new float[]{0.5f, 0.5f};
    }

    public void setFocal(String url, float fx, float fy) {
        if(url == null || url.isEmpty()) return;
        mPref.edit().putString(FOCAL_PREFIX + fitKey(url), clamp01(fx) + "," + clamp01(fy)).apply();
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // --- Per-wallpaper fit settings (how a too-tall image is fitted to the very wide screen).
    //     Keyed by url like the focal point above, so it survives a re-sync and works for both
    //     global and per-car images. The defaults live under their own keys and are what the
    //     editor opens with, so the technician sets them once.
    private static final String FIT_PREFIX = "wp-fit:";
    private static final String PREF_FIT_DEFAULT_MODE = "wallpaper-fit-default-mode";
    private static final String PREF_FIT_DEFAULT_BLUR = "wallpaper-fit-default-blur";
    private static final String PREF_FIT_DEFAULT_COLOR = "wallpaper-fit-default-color";
    private static final String PREF_FIT_EDIT_ON_IMPORT = "wallpaper-fit-edit-on-import";

    /** The defaults a freshly imported image starts from. */
    public FitSettings getFitDefaults() {
        return new FitSettings(
                mPref.getInt(PREF_FIT_DEFAULT_MODE, FitSettings.MODE_AUTO),
                mPref.getInt(PREF_FIT_DEFAULT_BLUR, 60),
                mPref.getInt(PREF_FIT_DEFAULT_COLOR, android.graphics.Color.BLACK));
    }

    public void setFitDefaults(FitSettings s) {
        mPref.edit()
                .putInt(PREF_FIT_DEFAULT_MODE, s.mode)
                .putInt(PREF_FIT_DEFAULT_BLUR, FitSettings.clampBlur(s.blur))
                .putInt(PREF_FIT_DEFAULT_COLOR, s.barColor)
                .apply();
    }

    /** Whether the editor opens after every import, or imports apply the defaults silently. */
    public boolean isEditOnImport() {
        return mPref.getBoolean(PREF_FIT_EDIT_ON_IMPORT, true);
    }

    public void setEditOnImport(boolean v) {
        mPref.edit().putBoolean(PREF_FIT_EDIT_ON_IMPORT, v).apply();
    }

    /** This wallpaper's fit settings, falling back to the defaults when it has none of its own. */
    public FitSettings getFit(String url) {
        if(url == null || url.isEmpty()) return getFitDefaults();
        return FitSettings.parse(mPref.getString(FIT_PREFIX + fitKey(url), ""), getFitDefaults());
    }

    public void setFit(String url, FitSettings s) {
        if(url == null || url.isEmpty()) return;
        mPref.edit().putString(FIT_PREFIX + fitKey(url), s.serialize()).apply();
    }

    /** Drop this wallpaper's override so it follows the defaults again. */
    public void clearFit(String url) {
        if(url == null || url.isEmpty()) return;
        mPref.edit().remove(FIT_PREFIX + fitKey(url)).apply();
    }

    /** The technician's "40 images, all reels, same treatment" button. */
    public void applyFitToAll(FitSettings s) {
        SharedPreferences.Editor e = mPref.edit();
        for(WallpaperItem item : mItems) {
            if(item.url != null && !item.url.isEmpty()) e.putString(FIT_PREFIX + fitKey(item.url), s.serialize());
        }
        e.apply();
    }

    /** Whether wallpaper syncing is turned on, regardless of whether any items are cached yet.
     *  Used to trigger the very first automatic download right after install/activation. */
    public boolean isSyncEnabled() {
        return mPref.getBoolean(PREF_ENABLED, true);
    }

    public boolean hasItems() {
        return !mItems.isEmpty();
    }

    public int size() {
        return mItems.size();
    }

    public WallpaperItem current() {
        List<WallpaperItem> items = mItems;
        if(items.isEmpty()) return null;
        return items.get(((mIndex % items.size()) + items.size()) % items.size());
    }

    public WallpaperItem next() {
        List<WallpaperItem> items = mItems;
        if(items.isEmpty()) return null;
        int index = (((mIndex % items.size()) + items.size()) + 1) % items.size();
        mIndex = index;
        savePosition(items.get(index));
        return items.get(index);
    }

    public WallpaperItem prev() {
        List<WallpaperItem> items = mItems;
        if(items.isEmpty()) return null;
        int index = (((mIndex % items.size()) + items.size()) - 1) % items.size();
        mIndex = index;
        savePosition(items.get(index));
        return items.get(index);
    }

    /** Remember where the slideshow stands, so the next app start resumes on this wallpaper. */
    public void savePosition(WallpaperItem item) {
        SharedPreferences.Editor e = mPref.edit().putInt(PREF_INDEX, mIndex);
        if(item != null && item.url != null) e.putString(PREF_LAST_URL, item.url);
        e.apply();
    }

    /** Copy a picked file (image/gif/video) from the system picker into the local wallpaper folder. */
    public boolean importLocalFile(InputStream in, String displayName) {
        return importLocalFileReturningPath(in, displayName) != null;
    }

    /** Same import, but hands back the file so the caller can open the fit editor on it. */
    public File importLocalFileReturningPath(InputStream in, String displayName) {
        try {
            String name = displayName;
            if(name == null || name.trim().isEmpty()) name = "wp_" + Math.abs(displayName == null ? 0 : displayName.hashCode());
            name = name.replaceAll("[\\\\/:*?\"<>|]", "_");
            File dest = new File(getLocalFolder(), name);
            FileOutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            out.close();
            in.close();
            return dest;
        } catch(Exception e) {
            return null;
        }
    }

    public interface SyncCallback {
        void done(boolean success, int count, String error);
    }

    /** Download the manifest in a background thread and refresh the cached playlist. */
    public void sync(final SyncCallback cb) {
        final String url = getSyncUrl();
        if(url.isEmpty()) {
            if(cb != null) cb.done(false, 0, "no-url");
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String json = httpGet(url);
                    if(json == null || json.trim().isEmpty()) throw new Exception("manifest empty or invalid");
                    List<WallpaperItem> parsed = parse(json);
                    
                    boolean active = true;
                    if (parsed.size() == 1 && "inactive".equals(parsed.get(0).url)) {
                        active = false;
                        parsed.clear(); // clear it so we don't display "inactive" as a wallpaper
                    }
                    
                    mPref.edit()
                         .putString(PREF_CACHE, toJson(parsed))
                         .apply();
                    mSecurePref.putBoolean(PREF_ACTIVE, active);
                         
                    load();

                    // Clean up video cache files that are no longer used
                    try {
                        java.util.HashSet<String> activeCacheFiles = new java.util.HashSet<>();
                        for(WallpaperItem it : parsed) {
                            if(it.isVideo()) {
                                activeCacheFiles.add(videoCacheFile(it).getName());
                            }
                        }
                        File cacheDir = new File(mContext.getCacheDir(), "wallpapers");
                        if (cacheDir.exists() && cacheDir.isDirectory()) {
                            File[] cachedFiles = cacheDir.listFiles();
                            if (cachedFiles != null) {
                                for (File f : cachedFiles) {
                                    if (f.isFile() && f.getName().startsWith("vid_") && f.getName().endsWith(".bin")) {
                                        if (!activeCacheFiles.contains(f.getName())) {
                                            f.delete();
                                        }
                                    }
                                }
                            }
                        }
                    } catch(Exception ignored) {}

                    // pre-download videos so looping playback is smooth and works offline
                    for(WallpaperItem it : parsed) {
                        if(it.isVideo()) {
                            try { ensureVideoCached(it); } catch(Exception ignored) {}
                        }
                    }
                    // report the playlist as soon as it is known; the images keep
                    // downloading in the background
                    if(cb != null) cb.done(true, parsed.size(), null);

                    // Pull every image/GIF into Glide's disk cache right away. Without this
                    // each wallpaper is only fetched the first time it is actually shown, so
                    // a freshly activated car had to be swiped through picture by picture
                    // before they were all available offline.
                    prefetchImages(parsed);
                } catch(Exception e) {
                    Log.w(TAG, "sync failed", e);
                    if(cb != null) cb.done(false, 0, e.getMessage());
                }
            }
        }).start();
    }

    // ---- image caching -------------------------------------------------------

    /**
     * Download every remote image/GIF of the playlist into Glide's disk cache (same cache
     * WallpaperView loads from, so a prefetched wallpaper appears instantly and keeps
     * working with no network). Runs on the sync thread, one file after the other, and
     * simply skips whatever fails — the wallpaper still loads on demand in that case.
     */
    private void prefetchImages(List<WallpaperItem> items) {
        for(WallpaperItem it : items) {
            if(it == null || it.isVideo() || it.url == null) continue;
            String url = it.url.trim();
            if(!url.startsWith("http://") && !url.startsWith("https://")) continue; // local file
            try {
                com.bumptech.glide.Glide.with(mContext)
                        .download(url)
                        .submit()
                        .get();
            } catch(Exception e) {
                Log.w(TAG, "prefetch failed: " + url, e);
            }
        }
    }

    // ---- video caching -------------------------------------------------------

    private File videoCacheFile(WallpaperItem item) {
        File dir = new File(mContext.getCacheDir(), "wallpapers");
        if(!dir.exists()) dir.mkdirs();
        return new File(dir, "vid_" + Integer.toHexString(item.url.hashCode()) + ".bin");
    }

    private void ensureVideoCached(WallpaperItem item) throws Exception {
        File f = videoCacheFile(item);
        if(f.exists() && f.length() > 0) return;
        HttpURLConnection conn = (HttpURLConnection) new URL(item.url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        InputStream in = conn.getInputStream();
        FileOutputStream out = new FileOutputStream(f);
        byte[] buf = new byte[8192];
        int n;
        while((n = in.read(buf)) != -1) out.write(buf, 0, n);
        out.flush();
        out.close();
        in.close();
        conn.disconnect();
    }

    /** Returns a local cached path for a video if available, otherwise null (caller may stream the URL). */
    public String localVideoPath(WallpaperItem item) {
        if(item == null || !item.isVideo() || item.url == null) return null;
        File f = videoCacheFile(item);
        return (f.exists() && f.length() > 0) ? f.getAbsolutePath() : null;
    }

    // ---- parsing -------------------------------------------------------------

    private List<WallpaperItem> parse(String json) {
        List<WallpaperItem> list = new ArrayList<>();
        if(json == null || json.trim().isEmpty()) return list;
        try {
            Object root = new JSONTokener(json).nextValue();
            JSONArray arr = null;
            if(root instanceof JSONObject) {
                JSONObject obj = (JSONObject) root;
                if(obj.has("wallpapers")) arr = obj.optJSONArray("wallpapers");
                else if(obj.has("items")) arr = obj.optJSONArray("items");
            } else if(root instanceof JSONArray) {
                arr = (JSONArray) root;
            }
            if(arr == null) return list;
            for(int i = 0; i < arr.length(); i++) {
                Object el = arr.get(i);
                String url = null, type = null;
                if(el instanceof String) {
                    url = (String) el;
                } else if(el instanceof JSONObject) {
                    JSONObject o = (JSONObject) el;
                    url = o.optString("url", o.optString("src", null));
                    type = o.optString("type", null);
                }
                if(url == null || url.trim().isEmpty()) continue;
                url = url.trim();
                if(type == null || type.trim().isEmpty()) type = WallpaperItem.guessType(url);
                else type = type.trim().toLowerCase();
                list.add(new WallpaperItem(type, url));
            }
        } catch(Exception e) {
            Log.w(TAG, "parse failed", e);
        }
        return list;
    }

    private String toJson(List<WallpaperItem> items) {
        JSONArray arr = new JSONArray();
        for(WallpaperItem it : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("type", it.type);
                o.put("url", it.url);
                arr.put(o);
            } catch(Exception ignored) {}
        }
        return arr.toString();
    }

    /** Read a single query-string parameter value from a URL (returns "" if absent). */
    private static String extractQueryParam(String urlStr, String key) {
        String needle = key + "=";
        int idx = urlStr.indexOf(needle);
        if (idx == -1) return "";
        String val = urlStr.substring(idx + needle.length());
        int amp = val.indexOf("&");
        if (amp != -1) val = val.substring(0, amp);
        return val;
    }

    private String httpGet(String urlStr) throws Exception {
        boolean isRpc = urlStr.contains("/rpc/get_wallpapers");
        URL url = new URL(isRpc ? urlStr.split("\\?")[0] : urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setInstanceFollowRedirects(true);
        conn.setRequestProperty("Accept", "application/json");
        
        // Add Supabase headers if configured and requesting from Supabase URL
        String sbUrl = getSupabaseUrl();
        String sbKey = getSupabaseKey();
        if (!sbUrl.contains("YOUR_SUPABASE_PROJECT") && urlStr.startsWith(sbUrl)) {
            conn.setRequestProperty("apikey", sbKey);
            conn.setRequestProperty("Authorization", "Bearer " + sbKey);
        }
        
        if (isRpc) {
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            
            // extract device_hw_id and legacy_hw_id from query params
            String deviceHwId = extractQueryParam(urlStr, "device_hw_id");
            String legacyHwId = extractQueryParam(urlStr, "legacy_hw_id");
            JSONObject body = new JSONObject();
            body.put("device_hw_id", deviceHwId);
            if(!legacyHwId.isEmpty()) body.put("legacy_hw_id", legacyHwId);
            String jsonBody = body.toString();
            java.io.OutputStream os = conn.getOutputStream();
            os.write(jsonBody.getBytes("UTF-8"));
            os.close();
        }
        
        int code = conn.getResponseCode();
        if(code < 200 || code >= 300) {
            conn.disconnect();
            throw new Exception("HTTP " + code + " for " + urlStr);
        }
        StringBuilder sb = new StringBuilder();
        InputStreamReader reader = new InputStreamReader(conn.getInputStream());
        char[] buf = new char[4096];
        int n;
        while((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
        reader.close();
        conn.disconnect();
        return sb.toString();
    }

    public interface ActivateCallback {
        void done(boolean success, String result, String error);
    }

    public void activate(final String serial, final ActivateCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String url = getSupabaseUrl() + "/rest/v1/rpc/activate_device";
                    JSONObject body = new JSONObject();
                    body.put("device_hw_id", getDeviceId());
                    body.put("activation_serial", serial);
                    body.put("legacy_hw_id", getLegacyDeviceId());
                    String response = httpPost(url, body.toString());
                    
                    String result = response.trim();
                    if (result.startsWith("\"") && result.endsWith("\"") && result.length() >= 2) {
                        result = result.substring(1, result.length() - 1);
                    }
                    
                    boolean success = "success".equals(result);
                    if (success) {
                        mSecurePref.putBoolean(PREF_ACTIVE, true);
                    }
                    if (cb != null) cb.done(success, result, null);
                } catch (Exception e) {
                    Log.w(TAG, "activate failed", e);
                    if (cb != null) cb.done(false, null, e.getMessage());
                }
            }
        }).start();
    }

    private String httpPost(String urlStr, String jsonBody) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(20000);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Content-Type", "application/json");
        
        // Add Supabase headers if configured and requesting from Supabase URL
        String sbUrl = getSupabaseUrl();
        String sbKey = getSupabaseKey();
        if (!sbUrl.contains("YOUR_SUPABASE_PROJECT") && urlStr.startsWith(sbUrl)) {
            conn.setRequestProperty("apikey", sbKey);
            conn.setRequestProperty("Authorization", "Bearer " + sbKey);
        }
        
        conn.setDoOutput(true);
        java.io.OutputStream os = conn.getOutputStream();
        os.write(jsonBody.getBytes("UTF-8"));
        os.close();
        
        int code = conn.getResponseCode();
        if(code < 200 || code >= 300) {
            conn.disconnect();
            throw new Exception("HTTP " + code + " for " + urlStr);
        }
        StringBuilder sb = new StringBuilder();
        InputStreamReader reader = new InputStreamReader(conn.getInputStream());
        char[] buf = new char[4096];
        int n;
        while((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
        reader.close();
        conn.disconnect();
        return sb.toString();
    }
}
