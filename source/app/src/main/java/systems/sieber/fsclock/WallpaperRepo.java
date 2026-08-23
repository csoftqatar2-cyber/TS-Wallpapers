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

    /**
     * Activation-code prefixes. The check here is UX only — it saves a round trip on an
     * obvious typo — and is deliberately looser than the server: since 2026-08-05 the
     * backend only accepts a "7078" serial that is already on file (an existing car
     * re-activating), rejecting an unused one as invalid_format. The client can't know
     * locally whether a given serial is already registered, so it still accepts the
     * prefix here and lets the server make the real call (see activate_device / the
     * activation Worker).
     *
     *   "7078" — every code issued up to 2026-08-02, ~530 of them already in the field.
     *            Never remove it; those cars re-send their serial when they re-activate.
     *            No longer activates a NEW car server-side — see above.
     *   "578"  — everything issued from 2026-08-02 onward (578001, 578002, ...).
     *
     * Length is deliberately not checked: codes in the field run from 5 to 14 characters.
     */
    static final String[] SERIAL_PREFIXES = { "7078", "578" };

    /** True if the serial looks like one we issue. See {@link #SERIAL_PREFIXES}. */
    static boolean hasValidSerialPrefix(String serial) {
        if(serial == null) return false;
        for(String p : SERIAL_PREFIXES) {
            if(serial.startsWith(p)) return true;
        }
        return false;
    }

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
    /** The last VIN this car ever reported. A head unit that answers the VIN query today and
     *  comes up empty tomorrow (the settings row is written late in the boot, and on some Geely
     *  builds not at all until the car has been driven) would otherwise fall all the way back to
     *  ANDROID_ID and register itself as a brand new, unactivated device — the customer sees
     *  "0 wallpapers" and re-entering the serial fails with serial_already_used. Once a VIN has
     *  been seen it is the car's identity for good. */
    static final String PREF_VIN_SEEN = "device-vin-seen";
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
            "sys.dms.config.vin", "sys.virtual.vin",
            // Chery (gen4_gvm / cheryidcpro). Filed under the navigation stack rather than
            // anything vehicle-shaped, which is why the first Chery on the fleet identified
            // itself by ANDROID_ID — an id that a factory reset or a head-unit swap changes.
            "persist.sys.navi.vin"
        };
        for (String k : keys) {
            String v = getSystemProperty(k);
            if (isPlausibleVin(v)) return v.trim();
        }
        // Desay SV head units (Jetour T1J/T2 and the rest of the DesaySV IVI line). Their
        // SVVDSCarInfo app publishes the VIN twice: here from the TBOX, and under
        // sys.vehicle.hardware.vin.code above once the diagnosis CAN frame carrying it arrives.
        //
        // Strict, unlike the list above: the property scan and the two sibling apps (Back Button,
        // THABTHABA STORE) only ever accept a 17-character VIN from a key nobody told us to
        // trust, and a car those three read differently is a car with two rows in devices.
        String tbox = getSystemProperty("persist.sys.tbox.vin");
        if (isStrictVin(tbox)) return tbox.trim();
        return "";
    }

    /**
     * The property equivalent of {@link #scanSettingsForVin}: read every system property and
     * take one whose NAME mentions a VIN and whose VALUE is shaped like one.
     *
     * The named list above is a list of vendors we have already met. Each new brand files the
     * VIN somewhere of its own — Chery under the navigation stack, BYD under the driver
     * monitoring config, Geely not in properties at all — and learning that spelling one
     * outage at a time is the process this replaces: until the key is known, the car identifies
     * itself by ANDROID_ID and quietly falls out of its own activation the first time the head
     * unit is reset.
     *
     * There is no API to enumerate properties, so this shells out to {@code getprop}, which is
     * a plain world-executable binary and needs no permission. That is a process spawn, hence
     * the cache in {@link #scannedVin}, and it is the LAST thing tried — a car answering on a
     * known key never reaches it, so no id already in the field can move because of this.
     *
     * Strict 17-character VINs only, for the same reason as the settings scan: the key was
     * chosen by somebody else and can hold anything.
     */
    private static String scanPropertiesForVin() {
        java.io.BufferedReader r = null;
        Process p = null;
        try {
            p = new ProcessBuilder("getprop").redirectErrorStream(true).start();
            r = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                // getprop prints one property per line as: [name]: [value]
                int sep = line.indexOf("]: [");
                if (sep < 1 || !line.endsWith("]")) continue;
                String name = line.substring(1, sep);
                if (!name.toLowerCase().contains("vin")) continue;
                String value = line.substring(sep + 4, line.length() - 1);
                if (isStrictVin(value)) return value.trim();
            }
        } catch (Throwable ignored) {
            // No getprop, no permission, a ROM that blocks exec: fall through to the legacy id.
        } finally {
            if (r != null) try { r.close(); } catch (Throwable ignored) { }
            if (p != null) try { p.destroy(); } catch (Throwable ignored) { }
        }
        return "";
    }

    /** The scan above, at most once a minute — getHardwareId is called on every request. */
    private static volatile String sScannedVin = "";
    private static volatile long sScannedAt = 0;

    private static String scannedVin() {
        String found = sScannedVin;
        if (!found.isEmpty()) return found;
        // Not cached negatively for good: a head unit can publish its VIN a little after boot,
        // and a car that answers on the second attempt must not be stuck as an ANDROID_ID device
        // for the rest of the session.
        long now = android.os.SystemClock.elapsedRealtime();
        if (sScannedAt != 0 && now - sScannedAt < 60_000) return "";
        sScannedAt = now;
        String v = scanPropertiesForVin();
        if (!v.isEmpty()) sScannedVin = v;
        return v;
    }

    /**
     * VIN sources that live in the settings database rather than in system properties.
     *
     * Geely / Lynk &amp; Co head units (Flyme) publish it here and expose no VIN property at all,
     * so without this every Lynk car falls back to the legacy chain and identifies itself by a
     * serial that a head-unit swap changes.
     *
     * The obvious route — CarPropertyManager with INFO_VIN — is closed to us: that property is
     * gated behind android.car.permission.CAR_IDENTIFICATION, which is signature|privileged on
     * AAOS and cannot be granted to a normal app. Settings.Global needs no permission at all.
     */
    private static String getVinFromSettings(Context context) {
        String[] keys = { "geely_gpt_vin" };   // Geely / Lynk & Co
        for (String k : keys) {
            try {
                String v = android.provider.Settings.Global.getString(
                        context.getContentResolver(), k);
                if (isPlausibleVin(v)) return v.trim();
            } catch (Throwable ignored) { }
        }
        // Desay SV (Jetour) files its copy in Settings.System, not Global — but the table scan
        // below already reads System and accepts exactly what the sibling apps accept, so there
        // is deliberately no named shortcut for it here.
        return scanSettingsForVin(context);
    }

    /**
     * Last resort before the legacy chain: walk the settings tables and take any row whose NAME
     * mentions a VIN and whose VALUE is shaped like one.
     *
     * The named key above is what one Lynk & Co build (ULAUT012…) calls it. A car of the same
     * model on a different build answered nothing for it and identified itself by ANDROID_ID
     * instead — which is per-user and per-signing-key, so the car dropped out of its activation
     * and was told it had no wallpapers. Guessing the next vendor's spelling one key at a time
     * repeats that outage per ROM; reading the table does not.
     *
     * Deliberately stricter than {@link #isPlausibleVin}: a key we did not choose ourselves can
     * hold anything, so only a real 17-character VIN counts here. Cars that already answer on a
     * known key never reach this method, so no id in the field can move because of it.
     */
    private static String scanSettingsForVin(Context context) {
        android.net.Uri[] tables = {
            android.provider.Settings.Global.CONTENT_URI,
            android.provider.Settings.System.CONTENT_URI,
            android.provider.Settings.Secure.CONTENT_URI
        };
        for (android.net.Uri table : tables) {
            android.database.Cursor c = null;
            try {
                c = context.getContentResolver().query(
                        table, new String[]{"name", "value"}, null, null, null);
                if (c == null) continue;
                while (c.moveToNext()) {
                    String name = c.getString(0);
                    if (name == null || !name.toLowerCase().contains("vin")) continue;
                    if (isStrictVin(c.getString(1))) return c.getString(1).trim();
                }
            } catch (Throwable ignored) {
                // A locked-down provider is a reason to try the next table, not to lose the app.
            } finally {
                if (c != null) try { c.close(); } catch (Throwable ignored) { }
            }
        }
        return "";
    }

    /** The ISO 3779 shape: 17 characters, alphanumeric, never I/O/Q. */
    private static boolean isStrictVin(String v) {
        if (v == null) return false;
        v = v.trim();
        if (v.length() != 17) return false;
        for (int i = 0; i < 17; i++) {
            char ch = Character.toUpperCase(v.charAt(i));
            if (ch == 'I' || ch == 'O' || ch == 'Q') return false;
            if (!((ch >= '0' && ch <= '9') || (ch >= 'A' && ch <= 'Z'))) return false;
        }
        return true;
    }

    /** Guards against the placeholders these fields carry before the car has been provisioned. */
    private static boolean isPlausibleVin(String v) {
        if (v == null) return false;
        v = v.trim();
        return v.length() >= 8
                && !v.equalsIgnoreCase("unknown")
                && !v.equalsIgnoreCase("null")
                && !v.replace("0", "").isEmpty();   // all-zero placeholder
    }

    public static String getHardwareId(Context context) {
        // 0. Vehicle VIN (Android Automotive) – tied to the car, survives factory reset.
        //    Settings first: a car that has both must not get a different id depending on which
        //    source is consulted, and the settings value is the one Geely keeps current.
        String vin = getVinFromSettings(context);
        if (vin.isEmpty()) vin = getVin();
        // Last resort before giving up on the VIN entirely: a key no vendor has told us about.
        if (vin.isEmpty()) vin = scannedVin();
        if (!vin.isEmpty()) {
            rememberVin(context, vin);
            return "VIN-" + vin;
        }
        // Nothing answered this time. If this car has ever answered, it is still the same car:
        // keep the identity it was activated under instead of turning into a new device the
        // moment the head unit is slow to publish its VIN (see PREF_VIN_SEEN).
        String remembered = rememberedVin(context);
        if (!remembered.isEmpty()) {
            return "VIN-" + remembered;
        }
        // No VIN, and none ever seen (non-vehicle device) -> fall back to the legacy chain.
        return getLegacyHardwareId(context);
    }

    /** Store the VIN this car answered with, so a later empty answer cannot change its id. */
    private static void rememberVin(Context context, String vin) {
        try {
            SharedPreferences pref = context.getSharedPreferences(
                    BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
            if (!vin.equals(pref.getString(PREF_VIN_SEEN, ""))) {
                pref.edit().putString(PREF_VIN_SEEN, vin).apply();
            }
        } catch (Throwable ignored) { }
    }

    /** The VIN this car last answered with, "" if it never has. */
    private static String rememberedVin(Context context) {
        try {
            String vin = context.getSharedPreferences(
                    BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE)
                    .getString(PREF_VIN_SEEN, "");
            return isPlausibleVin(vin) ? vin.trim() : "";
        } catch (Throwable ignored) {
            return "";
        }
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

    /**
     * Report this install's operating mode (normal/fse/leopard/gwm) to the backend so the
     * manager can see it. Fire-and-forget: any failure (offline, RPC missing on an older
     * backend, non-2xx) is swallowed and never disturbs the sync it rides along with.
     */
    /**
     * Report the current operating mode right now, off the UI thread. The report that rides
     * {@link #sync} only fires after a successful manifest fetch — and in the Lynkco/Leopard
     * picker a warm cache means no fetch happens, so a car could sit in Lynk & Co for days
     * without the manager ever learning its mode. Call this on every app launch so the mode
     * is reported regardless of whether a sync runs.
     */
    public void reportModeAsync() {
        new Thread(new Runnable() {
            @Override public void run() { reportOperatingMode(); }
        }).start();
    }

    private void reportOperatingMode() {
        try {
            String sbUrl = getSupabaseUrl();
            if (sbUrl.contains("YOUR_SUPABASE_PROJECT")) return;
            JSONObject body = new JSONObject();
            body.put("device_hw_id", getDeviceId());
            body.put("device_mode", OperatingMode.wire(mPref));
            String legacy = getLegacyDeviceId();
            if (legacy != null && !legacy.isEmpty()) body.put("legacy_hw_id", legacy);
            // Which build this car is actually running. Publishing a version only ever said an
            // APK existed; without this nobody could tell whether the fleet had taken it, so
            // every release went out blind. Reported on the same call that already runs on every
            // launch — no extra request, and the server also stamps last_seen_at from it.
            try {
                android.content.pm.PackageInfo pi = mContext.getPackageManager()
                        .getPackageInfo(mContext.getPackageName(), 0);
                body.put("app_version", pi.versionName);
                body.put("app_version_code", pi.versionCode);
            } catch (Throwable ignored) {
                // Reporting the mode still matters on a device that will not tell us its own
                // version; the server treats the missing fields as "leave what you had".
            }
            httpPost(sbUrl + "/rest/v1/rpc/report_device_mode", body.toString());
        } catch (Exception e) {
            Log.w(TAG, "reportOperatingMode failed", e);
        }
    }

    /**
     * RPC that returns one folder-mirror channel's images for this car (empty when not
     * configured). Every mirror channel has its own RPC with the same shape and the same
     * activation gate — see get_gwm_wallpapers / get_jetour_wallpapers.
     */
    private String getMirrorSyncUrl(String rpcName) {
        String sbUrl = getSupabaseUrl();
        if (sbUrl.contains("YOUR_SUPABASE_PROJECT")) return "";
        return sbUrl + "/rest/v1/rpc/" + rpcName + "?device_hw_id=" + getDeviceId()
                + "&legacy_hw_id=" + getLegacyDeviceId();
    }

    /**
     * Fetch a folder-mirror manifest for this car: the images the operator tagged for that
     * external folder, global-to-all-cars-of-that-make plus this car's own. Blocking — call from
     * a background thread.
     *
     * An EMPTY list means "the operator published nothing" — an authoritative answer the caller
     * may mirror, deletions included. NULL means "no manifest": not activated (the RPC answers
     * with a single "inactive" sentinel, exactly like the normal channel), blocked, hardware id
     * not recognised, or no backend configured. The two must stay apart, because the mirror
     * deletes whatever an authoritative manifest omits — answering null-as-empty made a car that
     * merely lost its identity for one call wipe its whole folder.
     */
    public List<WallpaperItem> fetchChannelItems(String rpcName) throws Exception {
        String url = getMirrorSyncUrl(rpcName);
        if (url.isEmpty()) return null;                          // no backend configured
        String json = httpGet(url);
        if (json == null || json.trim().isEmpty()) return null;   // no answer, not an empty answer
        List<WallpaperItem> parsed = parse(json);
        if (parsed.size() == 1 && "inactive".equals(parsed.get(0).url)) {
            return null;
        }
        return parsed;
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
        migrateRemoteKeys();   // must precede load(): the hide list is read through wallpaperKey
        load();
    }

    /** (Re)read the playlist (local folder + cached remote manifest) and current position. */
    public void load() {
        List<WallpaperItem> all = new ArrayList<>();
        if(mPref.getBoolean(PREF_LOCAL_ENABLED, true)) {
            all.addAll(scanLocalFolder());
        }
        all.addAll(parse(mPref.getString(PREF_CACHE, "")));

        // drop the wallpapers this device was told to hide (see PREF_HIDDEN). Matched by
        // wallpaperKey, not by raw url, so a hide survives the library moving to another host.
        java.util.Set<String> hidden = new java.util.HashSet<>();
        for(String h : getHiddenUrls()) {
            String k = wallpaperKey(h);
            if(k != null) hidden.add(k);
        }
        List<WallpaperItem> merged = new ArrayList<>();
        for(WallpaperItem it : all) {
            if(it.url == null || !hidden.contains(wallpaperKey(it.url))) merged.add(it);
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
                if(sameWallpaper(last, merged.get(i).url)) { index = i; resumed = true; break; }
            }
        }
        // nothing to resume (first run, or that wallpaper is gone/hidden now): fall back to
        // the default wallpaper (e.g. the one the customer uploaded via QR), if it is there
        if(!resumed) {
            String def = mPref.getString(PREF_DEFAULT, "");
            if(!def.isEmpty()) {
                for(int i = 0; i < merged.size(); i++) {
                    if(sameWallpaper(def, merged.get(i).url)) { index = i; break; }
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

    /**
     * The wallpapers this car actually offers — the same list the slideshow plays, with everything
     * the owner unticked in Settings ▸ إدارة الصور removed.
     *
     * The hand-off pickers (Leopard, Lynk &amp; Co) want this rather than {@link #allItems()}:
     * editing a cloud image there copies it onto the car and hides the original, so a picker built
     * from the unfiltered list offers the untouched original and the edit looks lost.
     */
    public List<WallpaperItem> visibleItems() {
        return new ArrayList<>(mItems);
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

    /**
     * Delegated to {@link WallpaperItem}, which owns the one list.
     *
     * This used to carry its own copy, and the two fell out of step: a file could be typed as a
     * video by guessType and then dropped by this scan, so a phone upload landed on disk and
     * never appeared anywhere — no error, no entry, nothing to look at.
     */
    private boolean isSupportedMedia(String name) {
        return WallpaperItem.isSupportedMedia(name);
    }

    public boolean isEnabled() {
        return mPref.getBoolean(PREF_ENABLED, true) && !mItems.isEmpty();
    }

    // --- FSE per-wallpaper focal point (where in the image should be centered on the
    //     fixed 1920x720 screen). Stored as "fx,fy" fractions (0..1) keyed by the
    //     wallpaper url, so it works the same for global and per-car private images.
    private static final String FOCAL_PREFIX = "wp-focal:";

    /**
     * The identity a wallpaper is remembered by — its fit, focal point, hide entry and
     * "resume here" pointer all hang off this, not off the raw url.
     *
     * The same local image reaches us under two spellings: the import and phone-upload paths
     * open the editor with "file:///storage/…", while localItems() builds its WallpaperItems
     * from File.getAbsolutePath(), which is bare "/storage/…". Unnormalised, the editor wrote
     * one key and the renderer read the other, so every edit made on import was silently
     * thrown away.
     *
     * A remote wallpaper is keyed by its FILE NAME alone, never by the full url. The library
     * has already moved hosts once (Supabase Storage -> Cloudflare R2) and will move again the
     * day we put a custom domain in front of it. Keyed by url, every such move silently throws
     * away the crop, rotation and hide the technician set on all 450 cars — the pictures come
     * back uncropped and the ones they hid reappear. The buckets are flat, so the file name
     * identifies the image on its own; see {@link #migrateRemoteKeys()} for the one-time
     * rewrite of what the url-keyed builds left behind.
     */
    static String wallpaperKey(String url) {
        if(url == null) return null;
        if(isRemoteUrl(url)) {
            String path = url;
            int q = path.indexOf('?');
            if(q != -1) path = path.substring(0, q);
            int slash = path.lastIndexOf('/');
            return (slash == -1 || slash == path.length() - 1) ? path : path.substring(slash + 1);
        }
        return url.startsWith("file://") ? url.substring("file://".length()) : url;
    }

    /** Whether two urls name the same wallpaper, no matter which host each was written for. */
    private static boolean sameWallpaper(String a, String b) {
        if(a == null || b == null) return false;
        String ka = wallpaperKey(a);
        return ka != null && ka.equals(wallpaperKey(b));
    }

    /** Set once the url-keyed -> name-keyed rewrite below has run on this device. */
    private static final String PREF_KEYS_BY_NAME = "wallpaper-keys-by-name";

    /**
     * Move every per-image setting a url-keyed build left behind onto its file-name key, so a
     * car that upgrades keeps the crops, rotations and focal points its technician set before
     * the library moved off Supabase Storage.
     *
     * Runs once per device. The old entries are deliberately NOT deleted: they are a few
     * hundred bytes, and they are the only copy of those settings if this ever has to run
     * again. An existing name-keyed value always wins — a car that was edited after the move
     * must not have that work overwritten by the stale pre-move value.
     */
    private void migrateRemoteKeys() {
        if(mPref.getBoolean(PREF_KEYS_BY_NAME, false)) return;
        SharedPreferences.Editor e = mPref.edit();
        for(java.util.Map.Entry<String, ?> entry : mPref.getAll().entrySet()) {
            String key = entry.getKey();
            String prefix;
            if(key.startsWith(FIT_PREFIX)) prefix = FIT_PREFIX;
            else if(key.startsWith(FOCAL_PREFIX)) prefix = FOCAL_PREFIX;
            else continue;
            String oldUrl = key.substring(prefix.length());
            if(!isRemoteUrl(oldUrl)) continue;                  // local paths are already the key
            String renamed = prefix + wallpaperKey(oldUrl);
            if(renamed.equals(key) || mPref.contains(renamed)) continue;
            if(entry.getValue() instanceof String) {
                e.putString(renamed, (String) entry.getValue());
            }
        }
        e.putBoolean(PREF_KEYS_BY_NAME, true).apply();
    }

    /** Saved focal point for a wallpaper, or the image center (0.5,0.5) if none. */
    public float[] getFocal(String url) {
        if(url != null) {
            String v = mPref.getString(FOCAL_PREFIX + wallpaperKey(url), "");
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
        mPref.edit()
                .putString(FOCAL_PREFIX + wallpaperKey(url), clamp01(fx) + "," + clamp01(fy))
                .putLong(FIT_AT_PREFIX + wallpaperKey(url), System.currentTimeMillis())
                .apply();
    }

    /**
     * When this image's framing was last touched — the fit, the focal point, or a reset.
     *
     * Leopard and Lynk &amp; Co do not read the fit at draw time; they burn it into a file once and
     * hand that file to the head unit. Without a timestamp, an edit made anywhere ELSE than the
     * screen that baked it — Settings ▸ إدارة الصور, most obviously — changes numbers nobody will
     * ever read again: the baked picture is still on disk under the same name, still looks
     * finished, and goes on being the one applied. The car then shows the FIRST framing forever
     * while the editor cheerfully saves every later one. Comparing this against the baked file's
     * own mtime is what tells a stale bake from a current one.
     *
     * @return 0 when this image has never been edited.
     */
    public long fitChangedAt(String url) {
        if(url == null || url.isEmpty()) return 0L;
        return mPref.getLong(FIT_AT_PREFIX + wallpaperKey(url), 0L);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    // --- Per-wallpaper fit settings (how a too-tall image is fitted to the very wide screen).
    //     Keyed by url like the focal point above, so it survives a re-sync and works for both
    //     global and per-car images. The defaults live under their own keys and are what the
    //     editor opens with, so the technician sets them once.
    private static final String FIT_PREFIX = "wp-fit:";
    /** When the fit/focal above last changed — see {@link #fitChangedAt}. */
    private static final String FIT_AT_PREFIX = "wp-fit-at:";
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
        return FitSettings.parse(mPref.getString(FIT_PREFIX + wallpaperKey(url), ""), getFitDefaults());
    }

    public void setFit(String url, FitSettings s) {
        if(url == null || url.isEmpty()) return;
        mPref.edit()
                .putString(FIT_PREFIX + wallpaperKey(url), s.serialize())
                .putLong(FIT_AT_PREFIX + wallpaperKey(url), System.currentTimeMillis())
                .apply();
    }

    /** Drop this wallpaper's override so it follows the defaults again. */
    public void clearFit(String url) {
        if(url == null || url.isEmpty()) return;
        mPref.edit()
                .remove(FIT_PREFIX + wallpaperKey(url))
                .putLong(FIT_AT_PREFIX + wallpaperKey(url), System.currentTimeMillis())
                .apply();
    }

    /**
     * The technician's "40 images, all reels, same treatment" button.
     *
     * Rotation is deliberately NOT part of "the same treatment": it corrects one photo that
     * arrived on its side, so copying it onto the whole library would lay 39 correct images down
     * flat. Every wallpaper keeps whatever rotation it already had.
     */
    public void applyFitToAll(FitSettings s) {
        SharedPreferences.Editor e = mPref.edit();
        long now = System.currentTimeMillis();
        for(WallpaperItem item : mItems) {
            if(item.url == null || item.url.isEmpty()) continue;
            String key = wallpaperKey(item.url);
            FitSettings copy = new FitSettings(s.mode, s.blur, s.barColor, s.zoom, s.fade);
            copy.rotation = getFit(item.url).rotation;
            e.putString(FIT_PREFIX + key, copy.serialize());
            e.putLong(FIT_AT_PREFIX + key, now);   // every bake made from these is now stale
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

    /**
     * Put the slideshow on one specific wallpaper, by url.
     *
     * This is what makes a freshly added image actually appear. Adding an image only ever
     * grew the playlist — and since {@link #load()} re-anchors on PREF_LAST_URL, the screen
     * stayed on whatever was showing before, with the new picture buried wherever its file
     * name sorted. From the customer's side that is indistinguishable from "my photo never
     * arrived", which is exactly what the phone upload page promises it did.
     *
     * @return true when the wallpaper is in the playlist and is now the current one.
     */
    public boolean jumpTo(String url) {
        if(url == null || url.isEmpty()) return false;
        List<WallpaperItem> items = mItems;
        String key = wallpaperKey(url);
        for(int i = 0; i < items.size(); i++) {
            WallpaperItem it = items.get(i);
            if(it.url != null && key.equals(wallpaperKey(it.url))) {
                mIndex = i;
                savePosition(it);
                return true;
            }
        }
        return false;
    }

    /** A wallpaper served from the shop's library rather than stored on this device. */
    public static boolean isRemoteUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * Delete a wallpaper that lives on this device — the file itself and everything keyed to
     * it: fit, focal point, hide entry, and the "resume here" / "default wallpaper" pointers.
     *
     * Only local files. A cloud wallpaper cannot be deleted from a car: the row lives on the
     * server and the very next sync would bring it back, so "deleted" would be a lie the user
     * discovers on the next swipe.
     *
     * @return true only when the file is genuinely gone from disk. The caller must not report
     *         success on false — that is the whole point of returning it.
     */
    public boolean deleteLocal(String url) {
        if(url == null || url.isEmpty() || isRemoteUrl(url)) return false;
        String key = wallpaperKey(url);
        File f = new File(key);
        if(f.exists() && !f.delete()) return false;
        if(f.exists()) return false;      // delete() lied, or something re-created it

        SharedPreferences.Editor e = mPref.edit();
        // Forget the per-image settings, so a future file that happens to land on the same
        // name does not inherit a stranger's crop.
        e.remove(FIT_PREFIX + key).remove(FOCAL_PREFIX + key);
        String last = mPref.getString(PREF_LAST_URL, "");
        if(!last.isEmpty() && key.equals(wallpaperKey(last))) e.remove(PREF_LAST_URL);
        String def = mPref.getString(PREF_DEFAULT, "");
        if(!def.isEmpty() && key.equals(wallpaperKey(def))) e.remove(PREF_DEFAULT);
        e.apply();

        // Drop any hide entry as well, or the pref grows a tail of urls to files that no
        // longer exist. setHiddenUrls rebuilds the playlist; otherwise do it here.
        java.util.Set<String> hidden = getHiddenUrls();
        List<String> keep = new ArrayList<>();
        boolean wasHidden = false;
        for(String h : hidden) {
            if(key.equals(wallpaperKey(h))) wasHidden = true;
            else keep.add(h);
        }
        if(wasHidden) setHiddenUrls(keep);
        else load();
        return true;
    }

    /**
     * The playlist as a set of wallpaper identities — hand it to {@link #newSince} after a
     * reload. Identities, not raw urls: the sync that moved the library to another host would
     * otherwise look like 99 brand-new wallpapers arriving at once on every car.
     */
    public java.util.Set<String> urlSnapshot() {
        java.util.Set<String> set = new java.util.HashSet<>();
        for(WallpaperItem it : mItems) {
            String k = wallpaperKey(it.url);
            if(k != null) set.add(k);
        }
        return set;
    }

    /**
     * Wallpapers in the playlist now that were not in this snapshot — i.e. what a sync (or a
     * local-folder rescan) just brought in. Order follows the playlist, so a batch published
     * together stays together.
     */
    public List<String> newSince(java.util.Set<String> before) {
        List<String> fresh = new ArrayList<>();
        if(before == null || before.isEmpty()) return fresh;   // first build: everything is "new"
        for(WallpaperItem it : mItems) {
            String k = wallpaperKey(it.url);
            if(k != null && !before.contains(k)) fresh.add(it.url);
        }
        return fresh;
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
        /**
         * The playlist is known and saved. Fired as soon as the manifest has been parsed —
         * BEFORE the media behind it has been downloaded, because everything a caller needs to
         * decide something (is this car registered, how many wallpapers are there, did the
         * server answer at all) is already settled at that point.
         */
        void done(boolean success, int count, String error);

        /**
         * Every video and image in that playlist is now in the local cache — all of them, with
         * nothing skipped over a failed download. A screen that gates on the library being
         * present may treat this as the go-ahead; {@link #mediaIncomplete} is the other outcome
         * and exactly one of the two always fires.
         *
         * Optional, and only worth implementing by a screen that shows the media itself: a
         * thumbnail grid that drew placeholders for clips that had not arrived yet, or a
         * progress screen waiting for the download to end.
         */
        default void mediaReady() { }

        /**
         * The download pass ended with {@code failed} of the files still missing.
         *
         * This exists because failures here are individually survivable — a wallpaper that did
         * not arrive still loads on demand later — but they are NOT the same as success, and a
         * screen that blocks until the library is local has to be able to tell the difference
         * instead of being waved through at a fake 100%.
         */
        default void mediaIncomplete(int failed, int total) { }

        /**
         * How the download is going, {@code done} of {@code total} files. Called on the sync
         * thread after each file, so a listener has to hop to the UI thread itself.
         */
        default void mediaProgress(int done, int total) { }

        /**
         * How much of the whole library has arrived, in bytes.
         *
         * The file counter cannot answer that. A library of fifteen wallpapers is five videos of
         * 10-63 MB and ten images under a megabyte: the videos are ~98% of the download and 33%
         * of the count, so a bar driven by files crawls to 5% over several minutes and then jumps
         * to 100% as the images fly past. This is the honest measure; the counter stays for
         * "3 of 15", which is the question it is actually good at.
         *
         * @param totalBytes 0 when nothing could be sized — treat the file counter as the truth.
         */
        default void mediaBytes(long doneBytes, long totalBytes) { }

        /**
         * The same progress, four times a second and measured in bytes rather than in whole
         * files — plus the speed those bytes are arriving at.
         *
         * {@link #mediaProgress} can only move when a file FINISHES, so on a car with twenty
         * wallpapers the gate screen sat still at 19% for as long as one download took and then
         * jumped to 23%. Nothing was wrong, but a number that stands still is exactly what a
         * frozen app looks like from the driver's seat. This one advances while the bytes are
         * still coming in.
         *
         * @param done            files fully downloaded
         * @param total           files this pass has to fetch
         * @param fraction        0..1 through the file currently in flight (never reaches 1 —
         *                        that is what {@code done} going up means)
         * @param bytesPerSecond  measured link speed, 0 until there is enough to measure
         */
        default void mediaTick(int done, int total, float fraction, long bytesPerSecond) { }
    }

    /**
     * Byte-level progress for one media pass, and the link speed that falls out of measuring it.
     *
     * Videos report themselves exactly: the bytes go through our own stream, so the count is the
     * count. Images go through Glide, which hands back the cached file and nothing while it is
     * working, so their progress is read off the growth of Glide's cache directory and their size
     * is learned from the file it returns — after a couple of wallpapers this car knows what its
     * own library weighs and the estimate stops being one. A file in flight is capped below 100%
     * either way: only {@link #fileDone} is allowed to say a file has landed.
     */
    private static final class DownloadProgress {

        /** What to assume one image weighs until this car has actually downloaded one. */
        static final long DEFAULT_IMAGE_BYTES = 900_000L;
        /** A file that is still arriving is never drawn as arrived. */
        private static final float MAX_FILE_FRACTION = 0.97f;

        private final Context mCtx;
        private final SyncCallback mCb;
        private final int mTotal;

        private volatile int mDone;
        private volatile long mCurBytes;      // bytes of the file in flight
        private volatile long mCurExpected;   // its size: exact for video, learned for images
        private volatile long mTotalBytes;    // everything this pass pulled down (speed meter)
        private volatile long mPlannedBytes;  // what the whole pass is expected to weigh
        private volatile long mCompletedBytes;// bytes of the files that have fully landed

        private volatile boolean mImageInFlight;
        private volatile long mImageBaseline = -1;
        private long mAvgImageBytes;
        private int mAvgImageCount;

        private long mSampleAt, mSampleBytes;
        private volatile long mSpeed;

        private Thread mTicker;
        private volatile boolean mRunning;

        DownloadProgress(Context ctx, SyncCallback cb, int total) {
            mCtx = ctx;
            mCb = cb;
            mTotal = total;
        }

        /** Begin emitting ticks. No-op when nobody is listening or there is nothing to fetch. */
        void start() {
            if(mCb == null || mTotal <= 0) return;
            mRunning = true;
            mTicker = new Thread(new Runnable() {
                @Override
                public void run() {
                    int tick = 0;
                    while(mRunning) {
                        try { Thread.sleep(250); } catch(InterruptedException e) { return; }
                        // Measuring an image means stat()ing every file in Glide's cache, which
                        // on a full one is a few hundred of them: worth doing twice a second,
                        // not four times, on the kind of storage a head unit ships with.
                        if((++tick % 2) == 0) pollImageBytes();
                        sampleSpeed();
                        emit();
                    }
                }
            });
            mTicker.setDaemon(true);
            mTicker.start();
        }

        void stop() {
            mRunning = false;
            if(mTicker != null) mTicker.interrupt();
        }

        long speed() { return mSpeed; }

        /** Total bytes this pass expects to move. 0 leaves the bar on the file count. */
        void plan(long bytes) { mPlannedBytes = bytes > 0 ? bytes : 0; }

        /** Everything landed: the estimate for the images was never going to be exact. */
        void allDone() {
            mCompletedBytes = mPlannedBytes;
            emit();
        }

        /** A video is starting; {@code contentLength} is what the server says it weighs. */
        void fileStart(long contentLength) {
            mCurBytes = 0;
            mCurExpected = contentLength > 0 ? contentLength : 0;
        }

        /** Bytes just read off the wire (video path — the only one we stream ourselves). */
        void addBytes(long n) {
            mCurBytes += n;
            mTotalBytes += n;
        }

        void imageStart() {
            mCurBytes = 0;
            mCurExpected = mAvgImageBytes > 0 ? mAvgImageBytes : DEFAULT_IMAGE_BYTES;
            mImageBaseline = cacheDirSize();
            mImageInFlight = true;
        }

        /** @param cached what Glide handed back, so this car learns what its images weigh. */
        void imageDone(File cached) {
            long len = (cached == null) ? 0 : cached.length();
            if(len > 0) {
                if(len > mCurBytes) mTotalBytes += len - mCurBytes;
                mCurBytes = len;
                mAvgImageBytes = (mAvgImageBytes * mAvgImageCount + len) / (mAvgImageCount + 1);
                mAvgImageCount++;
            }
            fileDone();
        }

        void fileDone() {
            mDone++;
            // Banked before reset() zeroes it — this is the only record that the file's bytes
            // are in, and the in-flight counter is about to be handed to the next file.
            mCompletedBytes += mCurBytes;
            reset();
            if(mCb != null) mCb.mediaProgress(mDone, mTotal);
            emit();
        }

        /** The file did not arrive. It does not count as done, and its bytes stop counting. */
        void fileFailed() {
            reset();
            emit();
        }

        private void reset() {
            mImageInFlight = false;
            mImageBaseline = -1;
            mCurBytes = 0;
            mCurExpected = 0;
        }

        private void emit() {
            if(mCb == null) return;
            // Bytes first: the screen prefers them for the percentage, and a tick carrying the
            // previous pass's byte figure would draw one frame of the old number.
            mCb.mediaBytes(mCompletedBytes + mCurBytes, mPlannedBytes);
            mCb.mediaTick(mDone, mTotal, fraction(), mSpeed);
        }

        private float fraction() {
            long expected = mCurExpected;
            if(expected <= 0) return 0f;
            float f = mCurBytes / (float) expected;
            if(f < 0f) return 0f;
            return f > MAX_FILE_FRACTION ? MAX_FILE_FRACTION : f;
        }

        /**
         * How far the image in flight has got, read off Glide's cache directory growing.
         *
         * Only ever allowed to move forward: the same cache evicts old entries while it writes,
         * so a shrinking directory means somebody else's file went away, not that ours un-downloaded.
         */
        private void pollImageBytes() {
            if(!mImageInFlight) return;
            long baseline = mImageBaseline;
            if(baseline < 0) return;
            long now = cacheDirSize();
            if(now < 0) return;
            long grown = now - baseline;
            if(grown > mCurBytes) {
                mTotalBytes += grown - mCurBytes;
                mCurBytes = grown;
            }
        }

        private long cacheDirSize() {
            try {
                File dir = com.bumptech.glide.Glide.getPhotoCacheDir(mCtx);
                if(dir == null) return -1;
                File[] files = dir.listFiles();
                if(files == null) return -1;
                long sum = 0;
                for(File f : files) if(f.isFile()) sum += f.length();
                return sum;
            } catch(Throwable t) {
                // No cache dir, no permission, a Glide that moved it: the bar falls back to
                // whole-file steps, which is what it did before this existed.
                return -1;
            }
        }

        /** Smoothed bytes/second, so the readout is a speed and not a stopwatch reading. */
        private void sampleSpeed() {
            long now = android.os.SystemClock.elapsedRealtime();
            long bytes = mTotalBytes;
            if(mSampleAt == 0) { mSampleAt = now; mSampleBytes = bytes; return; }
            long dt = now - mSampleAt;
            if(dt < 700) return;
            long db = bytes - mSampleBytes;
            mSampleAt = now;
            mSampleBytes = bytes;
            if(db < 0) db = 0;
            long instant = db * 1000L / dt;
            mSpeed = (mSpeed == 0) ? instant : (mSpeed * 2 + instant * 3) / 5;
        }
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
                                    if(!f.isFile() || !f.getName().startsWith("vid_")) continue;
                                    // A .part left behind by a process killed mid-download: it is
                                    // never resumed, so it is pure dead weight in the cache.
                                    //
                                    // Only once it has gone cold. Downloads now overlap by design
                                    // — the pre-activation prefetch, the gate, and the sync behind
                                    // a skipped gate can all be running — and this sweep runs at
                                    // the start of every one of them, so deleting the newest .part
                                    // would be this pass throwing away another pass's live work.
                                    // Ten minutes is far longer than any single clip takes and far
                                    // shorter than "for ever".
                                    if (f.getName().endsWith(".part")) {
                                        if(System.currentTimeMillis() - f.lastModified() > 10 * 60_000L) {
                                            f.delete();
                                        }
                                    } else if (f.getName().endsWith(".bin")
                                            && !activeCacheFiles.contains(f.getName())) {
                                        f.delete();
                                    }
                                }
                            }
                        }
                    } catch(Exception ignored) {}

                    // The answer, the moment it is known — and before a byte of media is
                    // fetched. This ordering is load-bearing. It used to sit after the video
                    // pre-download, which meant every caller waiting on this callback was
                    // really waiting on tens of megabytes over a car's link: the "already
                    // registered? re-check" button sat on "checking the servers…" until every
                    // clip had come down, which on a slow connection is minutes and reads as a
                    // frozen app — the only way out being a force stop.
                    if(cb != null) cb.done(true, parsed.size(), null);

                    // Tell the backend which mode this car runs, so the manager can see it.
                    // Best-effort telemetry on the same thread; runs after the manifest fetch
                    // already migrated any VIN, so the device row is under the current id.
                    reportOperatingMode();

                    // Everything below is the slow half: the caller has its answer and the UI
                    // is alive, so this can take as long as the link makes it take.
                    int total = 0;
                    for(WallpaperItem it : parsed) if(needsDownload(it)) total++;
                    if(cb != null) cb.mediaProgress(0, total);

                    // A file that did not arrive must not be counted as arrived: progress is only
                    // advanced by a download that actually succeeded, so the bar cannot reach the
                    // total while something is still missing.
                    int failed = 0;

                    // Reports the bytes underneath those files while they are still arriving —
                    // see DownloadProgress for why whole-file steps were not enough.
                    final DownloadProgress progress = new DownloadProgress(mContext, cb, total);
                    // Weigh the pass before starting it. Only the videos are asked — they are
                    // nearly all of the bytes, and it is five or six HEADs rather than fifteen —
                    // while the images ride on the same estimate the in-flight meter already uses.
                    // Any video that will not give a length falls back to that estimate too: a
                    // slightly wrong plan still moves the bar honestly, and a missing one leaves
                    // it exactly where it was before.
                    long planned = 0;
                    for(WallpaperItem it : parsed) {
                        if(it == null || !needsDownload(it)) continue;
                        if(!it.isVideo()) { planned += DownloadProgress.DEFAULT_IMAGE_BYTES; continue; }
                        long len = remoteLength(it.url);
                        planned += len > 0 ? len : ASSUMED_VIDEO_BYTES;
                    }
                    progress.plan(planned);
                    progress.start();
                    try {
                        // Videos first: they are the big files and the ones that cannot be shown
                        // at all until they are local, while an image at least loads on demand.
                        for(WallpaperItem it : parsed) {
                            if(!it.isVideo()) continue;
                            boolean counted = needsDownload(it);
                            boolean ok = true;
                            try { ensureVideoCached(it, counted ? progress : null); } catch(Exception e) {
                                Log.w(TAG, "video download failed: " + it.url, e);
                                ok = false;
                            }
                            if(!counted) continue;
                            if(ok) progress.fileDone();
                            else { progress.fileFailed(); failed++; }
                        }

                        // Pull every image/GIF into Glide's disk cache right away. Without this
                        // each wallpaper is only fetched the first time it is actually shown, so
                        // a freshly activated car had to be swiped through picture by picture
                        // before they were all available offline.
                        for(WallpaperItem it : parsed) {
                            if(it == null || it.isVideo()) continue;
                            // Not counted means a local file — there is nothing to fetch and
                            // nothing to report, so it never enters the progress at all.
                            if(!needsDownload(it)) continue;
                            progress.imageStart();
                            File cached = prefetchImage(it);
                            if(cached != null) progress.imageDone(cached);
                            else { progress.fileFailed(); failed++; }
                        }
                    } finally {
                        progress.stop();
                    }
                    if(cb != null) {
                        if(failed > 0) cb.mediaIncomplete(failed, total);
                        else {
                            cb.mediaProgress(total, total);
                            progress.allDone();
                            cb.mediaTick(total, total, 0f, progress.speed());
                            cb.mediaReady();
                        }
                    }
                } catch(Exception e) {
                    Log.w(TAG, "sync failed", e);
                    if(cb != null) cb.done(false, 0, e.getMessage());
                }
            }
        }).start();
    }

    /** What an unsizeable video is assumed to weigh, so one silent server cannot flatten the bar. */
    private static final long ASSUMED_VIDEO_BYTES = 25L * 1024 * 1024;

    /**
     * What the server says a file weighs, or -1.
     *
     * A HEAD, with a short timeout and every failure swallowed: this only makes the progress bar
     * truthful, and no wallpaper should fail to download because a size could not be read first.
     */
    private static long remoteLength(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setRequestMethod("HEAD");
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            c.setInstanceFollowRedirects(true);
            if(c.getResponseCode() / 100 != 2) return -1;
            // getContentLengthLong() is API 24; this app still runs on 21 head units.
            String len = c.getHeaderField("Content-Length");
            return len == null ? -1 : Long.parseLong(len.trim());
        } catch(Exception e) {
            return -1;
        } finally {
            if(c != null) c.disconnect();
        }
    }

    // ---- pre-activation prefetch --------------------------------------------

    /** One prefetch at a time, and never a second one in the same process. */
    private volatile boolean mPrefetching;
    private volatile boolean mPrefetched;

    /**
     * Start pulling the shared wallpapers down while the activation card is still on screen.
     *
     * The car cannot be told what IT will show until it is registered — {@link #getSyncUrl} is
     * answered with the "inactive" sentinel and nothing else — so the download used to begin only
     * after the serial was accepted and the car type chosen, with a technician and a customer both
     * watching a progress bar start from zero. Most of what any car ends up showing is the shared
     * library, and that part does not depend on which car this is: it can be on the disk before
     * anybody has finished typing. Whatever is already cached is skipped by the real sync when it
     * runs, so the gate after activation has only the car's own pictures left to fetch.
     *
     * Deliberately quiet: no progress, no callback, no retry. It is a head start, and a head start
     * that fails costs nothing — the normal sync fetches the same files afterwards.
     */
    public void prefetchSharedAsync() {
        if(mPrefetching || mPrefetched || isActive()) return;
        String sbUrl = getSupabaseUrl();
        if(sbUrl.contains("YOUR_SUPABASE_PROJECT")) return;
        final String url = sbUrl + "/rest/v1/rpc/get_prefetch_wallpapers";
        mPrefetching = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String json = httpGet(url);
                    if(json == null || json.trim().isEmpty()) return;
                    for(WallpaperItem it : parse(json)) {
                        if(it == null || it.url == null || "inactive".equals(it.url)) continue;
                        // An activation that lands mid-prefetch makes this pass redundant: the
                        // real sync is already fetching the same files with a bar in front of it.
                        if(isActive()) break;
                        if(it.isVideo()) {
                            try { ensureVideoCached(it, null); } catch(Exception e) {
                                Log.w(TAG, "prefetch video failed: " + it.url, e);
                            }
                        } else {
                            prefetchImage(it);
                        }
                    }
                    mPrefetched = true;
                } catch(Exception e) {
                    Log.w(TAG, "prefetch failed", e);
                } finally {
                    mPrefetching = false;
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
     *
     * @return the cache file Glide wrote, or null if the download failed. The file is worth
     *         handing back rather than a bare boolean: its length is the only place this app can
     *         learn what one of its own wallpapers actually weighs, which is what lets the
     *         progress bar advance inside a file instead of only between files.
     */
    private File prefetchImage(WallpaperItem it) {
        if(it == null || it.isVideo() || it.url == null) return null;
        String url = it.url.trim();
        if(!url.startsWith("http://") && !url.startsWith("https://")) return null; // local file
        try {
            return com.bumptech.glide.Glide.with(mContext)
                    .download(url)
                    .submit()
                    .get();
        } catch(Exception e) {
            Log.w(TAG, "prefetch failed: " + url, e);
            return null;
        }
    }

    /**
     * Is this wallpaper still to be fetched? Used only to size the progress bar.
     *
     * Exact for video, where the cache file is ours and its absence is the whole question.
     * Deliberately loose for images: Glide owns that cache and asking it whether a key is on
     * disk means going through its engine, so an image already on disk is counted as work and
     * then completes in the few milliseconds a disk hit takes. A progress bar that briefly
     * over-counts is a fair price for not reaching into another library's internals.
     */
    private boolean needsDownload(WallpaperItem it) {
        if(it == null || it.url == null) return false;
        String url = it.url.trim();
        if(!url.startsWith("http://") && !url.startsWith("https://")) return false;
        return !it.isVideo() || localVideoPath(it) == null;
    }

    // ---- video caching -------------------------------------------------------

    /** Cache name keyed by the wallpaper's identity, so moving hosts does not force every car
     *  to pull the videos down again — at 50 MB apiece that is the most expensive thing we
     *  could make them re-fetch. Files left over under an older naming scheme are swept up by
     *  the orphan cleanup in {@link #sync}. */
    private File videoCacheFile(WallpaperItem item) {
        File dir = new File(mContext.getCacheDir(), "wallpapers");
        if(!dir.exists()) dir.mkdirs();
        return new File(dir, "vid_" + Integer.toHexString(wallpaperKey(item.url).hashCode()) + ".bin");
    }

    /**
     * Downloads to a .part file and renames only once the stream has been read to the end.
     *
     * The cache file's mere existence is what {@link #localVideoPath} treats as "this clip is
     * local", so writing the final name while the bytes are still arriving means a link dropped
     * mid-download leaves a truncated file that every later pass then skips as already cached —
     * a wallpaper permanently broken by one bad moment on the car's connection.
     *
     * The scratch name carries the thread's id, because two passes over the same clip at once is
     * no longer a hypothetical: the pre-activation prefetch can still be running when the gate
     * starts its own sync, and skipping the gate leaves its download running while the wallpaper
     * screen starts another. Sharing one ".part" between two writers interleaves the two streams
     * into a file that is the right length, passes every check here, and is not a video. Separate
     * scratch files make the loser of the race harmless: both write their own, both rename onto
     * the same finished name, and a rename is atomic.
     */
    private void ensureVideoCached(WallpaperItem item, DownloadProgress progress) throws Exception {
        File f = videoCacheFile(item);
        if(f.exists() && f.length() > 0) return;
        File part = new File(f.getAbsolutePath() + "." + Thread.currentThread().getId() + ".part");
        HttpURLConnection conn = (HttpURLConnection) new URL(item.url).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(30000);
        conn.setInstanceFollowRedirects(true);
        try {
            InputStream in = conn.getInputStream();
            // The one file whose size we are told before we start: the bar can be exact here.
            if(progress != null) progress.fileStart(conn.getContentLength());
            FileOutputStream out = new FileOutputStream(part);
            try {
                byte[] buf = new byte[8192];
                int n;
                while((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                    if(progress != null) progress.addBytes(n);
                }
                out.flush();
            } finally {
                try { out.close(); } catch(Exception ignored) {}
                try { in.close(); } catch(Exception ignored) {}
            }
            if(part.length() <= 0 || !part.renameTo(f)) throw new Exception("video cache write failed");
        } catch(Exception e) {
            part.delete();
            throw e;
        } finally {
            conn.disconnect();
        }
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
        // Every wallpaper RPC takes the same (device_hw_id, legacy_hw_id) body, so they share this
        // path. The mirror RPCs (get_gwm_wallpapers, get_jetour_wallpapers) do not contain the
        // literal "get_wallpapers" substring, so they must be listed explicitly.
        boolean isRpc = urlStr.contains("/rpc/get_wallpapers")
                || urlStr.contains("/rpc/get_gwm_wallpapers")
                || urlStr.contains("/rpc/get_jetour_wallpapers");
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

    /** Answer of {@link #fetchStatus}: 'active' | 'inactive' | 'blocked' | 'unknown'. */
    public interface StatusCallback {
        void done(String status);
    }

    /** This car is on the block list — no serial will activate it until the shop lifts it. */
    public static final String STATUS_BLOCKED = "blocked";

    /**
     * Ask the server WHY this car is not showing wallpapers.
     *
     * get_wallpapers answers "inactive" for a car that was never registered and for one that
     * has just been blocked, which is fine for deciding what to draw but useless for deciding
     * what to TELL somebody: one of them needs a serial typed in, the other needs a phone call
     * to the shop, and offering a serial box to a blocked car sends the technician chasing a
     * code the backend is guaranteed to refuse.
     *
     * Failures are answered with null, never with a guess. A car that cannot reach the server
     * is not a blocked car, and showing it a blocked screen because the wifi dropped would be
     * the worst possible false positive.
     */
    public void fetchStatus(final StatusCallback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                String status = null;
                try {
                    String sbUrl = getSupabaseUrl();
                    if(!sbUrl.contains("YOUR_SUPABASE_PROJECT")) {
                        JSONObject body = new JSONObject();
                        body.put("device_hw_id", getDeviceId());
                        body.put("legacy_hw_id", getLegacyDeviceId());
                        String response = httpPost(sbUrl + "/rest/v1/rpc/get_device_status",
                                body.toString());
                        status = response == null ? null : response.trim();
                        if(status != null && status.startsWith("\"") && status.endsWith("\"")
                                && status.length() >= 2) {
                            status = status.substring(1, status.length() - 1);
                        }
                    }
                } catch(Exception e) {
                    // Older backend without the RPC included: nothing is broken, the app simply
                    // does not learn the distinction and keeps its previous behaviour.
                    Log.w(TAG, "status check failed", e);
                }
                if(cb != null) cb.done(status);
            }
        }).start();
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
