package systems.sieber.fsclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import fi.iki.elonen.NanoHTTPD;

/**
 * Tiny HTTP server embedded in the app. The customer scans a QR code that points to
 * http://<device-lan-ip>:<port>/ , opens it on their phone (same Wi-Fi), uploads an
 * image/video, and the app stores it and makes it the default wallpaper.
 * No internet or external hosting needed — everything stays on the local Wi-Fi.
 */
public class UploadServer extends NanoHTTPD {

    public static final int PORT = 8089;

    public interface UploadListener {
        void onUploaded(String savedPath); // called on the UI thread
    }

    private final Context mContext;
    private final WallpaperRepo mRepo;
    private final UploadListener mListener;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    public UploadServer(Context c, WallpaperRepo repo, UploadListener listener) {
        super(PORT);
        mContext = c.getApplicationContext();
        mRepo = repo;
        mListener = listener;
    }

    @Override
    public Response serve(IHTTPSession session) {
        try {
            if(Method.POST.equals(session.getMethod())) {
                Map<String, String> files = new HashMap<>();
                session.parseBody(files);
                String tmpPath = files.get("image");
                String originalName = "upload.jpg";
                List<String> names = session.getParameters().get("image");
                if(names != null && !names.isEmpty() && names.get(0) != null && !names.get(0).trim().isEmpty()) {
                    originalName = names.get(0);
                }
                if(tmpPath != null) {
                    final String saved = saveUpload(tmpPath, originalName);
                    if(saved != null) {
                        // make it the default wallpaper + enable the slideshow
                        SharedPreferences sp = mContext.getSharedPreferences(SettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
                        sp.edit()
                                .putString(WallpaperRepo.PREF_DEFAULT, saved)
                                .putBoolean(WallpaperRepo.PREF_ENABLED, true)
                                .putBoolean(WallpaperRepo.PREF_LOCAL_ENABLED, true)
                                .apply();
                        if(mListener != null) {
                            mMain.post(new Runnable() {
                                @Override
                                public void run() { mListener.onUploaded(saved); }
                            });
                        }
                        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", successPage());
                    }
                }
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", uploadPage("لم يتم اختيار ملف"));
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", uploadPage(null));
        } catch(Exception e) {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "error: " + e.getMessage());
        }
    }

    private String saveUpload(String tmpPath, String originalName) {
        try {
            String ext = ".jpg";
            int dot = originalName.lastIndexOf('.');
            if(dot >= 0 && dot < originalName.length() - 1) ext = originalName.substring(dot);
            // name so it sorts predictably; the default is tracked by PREF_DEFAULT path
            File dest = new File(mRepo.getLocalFolder(), "upload_" + originalName.replaceAll("[\\\\/:*?\"<>|]", "_"));
            if(!dest.getName().toLowerCase().endsWith(ext.toLowerCase())) {
                dest = new File(mRepo.getLocalFolder(), "upload" + ext);
            }
            FileInputStream in = new FileInputStream(tmpPath);
            FileOutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[8192];
            int n;
            while((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            out.close();
            in.close();
            return dest.getAbsolutePath();
        } catch(Exception e) {
            return null;
        }
    }

    private String uploadPage(String msg) {
        return "<!doctype html><html dir=\"rtl\" lang=\"ar\"><head>"
                + "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>TS WALLPAPERS</title></head>"
                + "<body style=\"font-family:sans-serif;background:#15171e;color:#fff;text-align:center;padding:24px\">"
                + "<h2 style=\"color:#E6BE6A\">TS WALLPAPERS</h2>"
                + "<p>اختر صورة أو فيديو من هاتفك ليظهر على الشاشة كخلفية</p>"
                + (msg != null ? "<p style=\"color:#ff8080\">" + msg + "</p>" : "")
                + "<form method=\"post\" action=\"/\" enctype=\"multipart/form-data\">"
                + "<input type=\"file\" name=\"image\" accept=\"image/*,video/*\" required "
                + "style=\"margin:16px 0;color:#fff\"><br>"
                + "<button type=\"submit\" style=\"background:#E6BE6A;color:#15171e;border:0;"
                + "padding:14px 28px;border-radius:10px;font-size:18px;font-weight:bold\">رفع الصورة</button>"
                + "</form></body></html>";
    }

    private String successPage() {
        return "<!doctype html><html dir=\"rtl\" lang=\"ar\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"></head>"
                + "<body style=\"font-family:sans-serif;background:#15171e;color:#fff;text-align:center;padding:40px\">"
                + "<h2 style=\"color:#E6BE6A\">تم الرفع بنجاح ✓</h2>"
                + "<p>الصورة ظهرت على الشاشة كخلفية.</p>"
                + "<a href=\"/\" style=\"color:#E6BE6A\">رفع صورة أخرى</a>"
                + "</body></html>";
    }

    /** The device's local Wi-Fi IPv4 address, or null. Prefers the actual Wi-Fi interface. */
    public static String getLocalIpAddress(Context ctx) {
        // 1) Ask the Wi-Fi manager directly — most reliable for the real Wi-Fi IP
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if(wm != null && wm.getConnectionInfo() != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if(ip != 0) {
                    return String.format(Locale.US, "%d.%d.%d.%d",
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                }
            }
        } catch(Exception ignored) { }
        // 2) Fall back to scanning interfaces, preferring wlan and skipping cellular/VPN
        String wlan = scanInterfaces(true);
        if(wlan != null) return wlan;
        return scanInterfaces(false);
    }

    private static String scanInterfaces(boolean wlanOnly) {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while(ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if(!ni.isUp() || ni.isLoopback()) continue;
                String name = ni.getName() == null ? "" : ni.getName().toLowerCase();
                if(wlanOnly && !name.contains("wlan") && !name.contains("ap")) continue;
                if(!wlanOnly && (name.contains("rmnet") || name.startsWith("tun")
                        || name.startsWith("ppp") || name.contains("dummy"))) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while(addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if(!addr.isLoopbackAddress() && addr instanceof Inet4Address) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch(Exception ignored) { }
        return null;
    }

    public String getUrl() {
        String ip = getLocalIpAddress(mContext);
        return ip == null ? null : ("http://" + ip + ":" + PORT + "/");
    }
}
