package systems.sieber.fsclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
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

    private static final String TAG = "UploadServer";

    public static final int PORT = 8089;
    /** if 8089 is still held by a previous run (or another app), try the next few */
    private static final int PORT_TRIES = 5;
    /**
     * NanoHTTPD defaults to a 5 second socket read timeout. A phone pushing a multi‑MB
     * photo or a video over a weak Wi‑Fi link regularly needs longer than that between
     * two chunks, which is why the upload used to fail "randomly" while small images
     * went through. Give the upload two minutes.
     */
    private static final int READ_TIMEOUT_MS = 120_000;

    public interface UploadListener {
        void onUploaded(String savedPath); // called on the UI thread
    }

    private final Context mContext;
    private final WallpaperRepo mRepo;
    private final UploadListener mListener;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private UploadServer(Context c, int port, WallpaperRepo repo, UploadListener listener) {
        super(port);
        mContext = c.getApplicationContext();
        mRepo = repo;
        mListener = listener;
        // Do NOT use NanoHTTPD's default temp file manager: it writes to
        // System.getProperty("java.io.tmpdir"), which on Android points into the app
        // cache — a directory Android may wipe at any time. When it is gone, every
        // upload dies with "Error creating temp file" (the other half of the random
        // failures). Keep our own directory and (re)create it for each request.
        final File tempDir = new File(mContext.getCacheDir(), "upload_tmp");
        setTempFileManagerFactory(new TempFileManagerFactory() {
            @Override
            public TempFileManager create() {
                return new AppTempFileManager(tempDir);
            }
        });
    }

    /** Bind and start the server, falling back to the next port if one is taken. */
    public static UploadServer startNew(Context c, WallpaperRepo repo, UploadListener listener) throws IOException {
        IOException last = null;
        for(int i = 0; i < PORT_TRIES; i++) {
            UploadServer server = new UploadServer(c, PORT + i, repo, listener);
            try {
                server.start(READ_TIMEOUT_MS, true);
                return server;
            } catch(IOException e) {
                last = e;
                Log.e(TAG, "port " + (PORT + i) + " unavailable", e);
                try { server.stop(); } catch(Exception ignored) { }
            }
        }
        throw last != null ? last : new IOException("no free port");
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
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8",
                            "تعذّر حفظ الملف على الجهاز — تأكد من وجود مساحة كافية");
                }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8",
                        "لم يتم اختيار ملف");
            }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", uploadPage());
        } catch(Exception e) {
            // the phone shows this text, so say what actually went wrong instead of a
            // bare "upload failed"
            Log.e(TAG, "upload failed", e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8",
                    "فشل الرفع: " + reason);
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
            Log.e(TAG, "saving the uploaded file failed", e);
            return null;
        }
    }

    /**
     * The upload page. It posts with XMLHttpRequest instead of a plain form submit so the
     * customer sees the progress and, if something does go wrong, the reason the device
     * reported — a plain form just showed a blank browser error.
     */
    private String uploadPage() {
        return "<!doctype html><html dir=\"rtl\" lang=\"ar\"><head>"
                + "<meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>TS WALLPAPERS</title></head>"
                + "<body style=\"font-family:sans-serif;background:#15171e;color:#fff;text-align:center;padding:24px\">"
                + "<h2 style=\"color:#E6BE6A\">TS WALLPAPERS</h2>"
                + "<p>اختر صورة أو فيديو من هاتفك ليظهر على الشاشة كخلفية</p>"
                + "<form id=\"f\" method=\"post\" action=\"/\" enctype=\"multipart/form-data\">"
                + "<input id=\"file\" type=\"file\" name=\"image\" accept=\"image/*,video/*\" required "
                + "style=\"margin:16px 0;color:#fff\"><br>"
                + "<button id=\"btn\" type=\"submit\" style=\"background:#E6BE6A;color:#15171e;border:0;"
                + "padding:14px 28px;border-radius:10px;font-size:18px;font-weight:bold\">رفع الصورة</button>"
                + "</form>"
                + "<p id=\"msg\" style=\"margin-top:18px\"></p>"
                + "<script>"
                + "var f=document.getElementById('f'),b=document.getElementById('btn'),m=document.getElementById('msg');"
                + "f.onsubmit=function(e){e.preventDefault();"
                + "var fi=document.getElementById('file');"
                + "if(!fi.files.length){m.innerHTML='اختر ملف أولاً';return;}"
                + "var fd=new FormData();fd.append('image',fi.files[0]);"
                + "var x=new XMLHttpRequest();x.open('POST','/',true);x.timeout=180000;"
                + "b.disabled=true;b.textContent='جارٍ الرفع…';"
                + "x.upload.onprogress=function(ev){if(ev.lengthComputable){"
                + "m.innerHTML='جارٍ الرفع… '+Math.round(ev.loaded*100/ev.total)+'%';}};"
                + "x.onload=function(){b.disabled=false;b.textContent='رفع الصورة';"
                + "if(x.status===200){document.open();document.write(x.responseText);document.close();}"
                + "else{m.innerHTML='<span style=\\\"color:#ff8080\\\">'+(x.responseText||('خطأ '+x.status))+'</span>';}};"
                + "x.onerror=function(){b.disabled=false;b.textContent='رفع الصورة';"
                + "m.innerHTML='<span style=\\\"color:#ff8080\\\">انقطع الاتصال بالجهاز — تأكد إن الموبايل والشاشة على نفس شبكة الواي‑فاي وحاول تاني</span>';};"
                + "x.ontimeout=function(){b.disabled=false;b.textContent='رفع الصورة';"
                + "m.innerHTML='<span style=\\\"color:#ff8080\\\">الرفع أخذ وقتاً طويلاً — جرّب ملفاً أصغر أو قرّب من الراوتر</span>';};"
                + "x.send(fd);};"
                + "</" + "script>"
                + "</body></html>";
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
        // getListeningPort(), not PORT: startNew() may have fallen back to another port
        return ip == null ? null : ("http://" + ip + ":" + getListeningPort() + "/");
    }

    // ---- temp files (kept inside our own cache dir, created on demand) -------------

    private static class AppTempFileManager implements TempFileManager {
        private final File mDir;
        private final List<TempFile> mTempFiles = new ArrayList<>();

        AppTempFileManager(File dir) {
            mDir = dir;
            if(!mDir.exists()) mDir.mkdirs();
        }

        @Override
        public TempFile createTempFile(String filenameHint) throws Exception {
            if(!mDir.exists() && !mDir.mkdirs()) {
                throw new IOException("cannot create " + mDir.getAbsolutePath());
            }
            TempFile file = new AppTempFile(mDir);
            mTempFiles.add(file);
            return file;
        }

        @Override
        public void clear() {
            for(TempFile file : mTempFiles) {
                try { file.delete(); } catch(Exception ignored) { }
            }
            mTempFiles.clear();
        }
    }

    private static class AppTempFile implements TempFile {
        private final File mFile;
        private final OutputStream mStream;

        AppTempFile(File dir) throws IOException {
            mFile = File.createTempFile("upload_", ".tmp", dir);
            mStream = new FileOutputStream(mFile);
        }

        @Override
        public OutputStream open() {
            return mStream;
        }

        @Override
        public void delete() throws Exception {
            try { mStream.close(); } catch(Exception ignored) { }
            //noinspection ResultOfMethodCallIgnored
            mFile.delete();
        }

        @Override
        public String getName() {
            return mFile.getAbsolutePath();
        }
    }
}
