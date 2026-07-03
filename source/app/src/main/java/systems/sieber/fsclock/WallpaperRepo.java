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
    static final String PREF_DEVICE_ID = "device-id";
    static final String PREF_ACTIVE = "device-active";

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

    public static String getHardwareId(Context context) {
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
                url = sbUrl + "/rest/v1/rpc/get_wallpapers?device_hw_id=" + getDeviceId();
            }
        }
        return url;
    }

    /** sub-folder inside the app's external files dir where the shop can drop wallpapers. */
    static final String LOCAL_DIR = "Wallpapers";

    private final Context mContext;
    private final SharedPreferences mPref;
    private final SecurePrefs mSecurePref;

    private List<WallpaperItem> mItems = new ArrayList<>();
    private int mIndex = 0;

    public WallpaperRepo(Context c) {
        mContext = c.getApplicationContext();
        mPref = mContext.getSharedPreferences(SettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        mSecurePref = new SecurePrefs(mContext, mPref);
        load();
    }

    /** (Re)read the playlist (local folder + cached remote manifest) and current position. */
    public void load() {
        List<WallpaperItem> merged = new ArrayList<>();
        if(mPref.getBoolean(PREF_LOCAL_ENABLED, true)) {
            merged.addAll(scanLocalFolder());
        }
        merged.addAll(parse(mPref.getString(PREF_CACHE, "")));
        mItems = merged;
        mIndex = mPref.getInt(PREF_INDEX, 0);
        if(mItems.isEmpty()) {
            mIndex = 0;
        } else {
            mIndex = ((mIndex % mItems.size()) + mItems.size()) % mItems.size();
        }
        // if a default wallpaper (e.g. uploaded via QR) is set and present, show it first
        String def = mPref.getString(PREF_DEFAULT, "");
        if(!def.isEmpty()) {
            for(int i = 0; i < mItems.size(); i++) {
                if(def.equals(mItems.get(i).url)) { mIndex = i; break; }
            }
        }
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
        if(mItems.isEmpty()) return null;
        return mItems.get(mIndex % mItems.size());
    }

    public WallpaperItem next() {
        if(mItems.isEmpty()) return null;
        mIndex = (mIndex + 1) % mItems.size();
        saveIndex();
        return current();
    }

    public WallpaperItem prev() {
        if(mItems.isEmpty()) return null;
        mIndex = (mIndex - 1 + mItems.size()) % mItems.size();
        saveIndex();
        return current();
    }

    private void saveIndex() {
        mPref.edit().putInt(PREF_INDEX, mIndex).apply();
    }

    /** Copy a picked file (image/gif/video) from the system picker into the local wallpaper folder. */
    public boolean importLocalFile(InputStream in, String displayName) {
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
            return true;
        } catch(Exception e) {
            return false;
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
                    if(cb != null) cb.done(true, parsed.size(), null);
                } catch(Exception e) {
                    Log.w(TAG, "sync failed", e);
                    if(cb != null) cb.done(false, 0, e.getMessage());
                }
            }
        }).start();
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
            
            // extract device_hw_id from query param
            String deviceHwId = "";
            int idx = urlStr.indexOf("device_hw_id=");
            if (idx != -1) {
                deviceHwId = urlStr.substring(idx + 13);
                int amp = deviceHwId.indexOf("&");
                if (amp != -1) {
                    deviceHwId = deviceHwId.substring(0, amp);
                }
            }
            String jsonBody = "{\"device_hw_id\":\"" + deviceHwId + "\"}";
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
                    String jsonBody = "{\"device_hw_id\":\"" + getDeviceId() + "\",\"activation_serial\":\"" + serial + "\"}";
                    String response = httpPost(url, jsonBody);
                    
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
