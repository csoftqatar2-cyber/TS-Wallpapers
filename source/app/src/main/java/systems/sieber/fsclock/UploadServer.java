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
        /**
         * One call per POST, carrying every file that request delivered — the phone can now pick
         * several at once, and they have to arrive together or the review screen would open once
         * per image instead of once per batch. Never empty.
         */
        void onUploaded(List<String> savedPaths); // called on the UI thread
    }

    /**
     * Field names the page posts under. A multipart body may repeat a field name, but NanoHTTPD
     * keys its temp-file map by name, so repeats overwrite each other and only the last file
     * survives. Numbering the fields is what makes N files actually reachable.
     */
    private static final String FIELD_PREFIX = "image";

    /** A phone can select an entire album; cap the batch so one tap cannot fill the disk. */
    private static final int MAX_BATCH = 30;

    private final Context mContext;
    private final WallpaperRepo mRepo;
    private final UploadListener mListener;
    /** When true, uploads land in the external GWM Split folder, not our wallpaper folder, and are
     *  NOT made the current wallpaper — they belong to a different app entirely. */
    private final boolean mGwmFolder;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private UploadServer(Context c, int port, WallpaperRepo repo, UploadListener listener, boolean gwmFolder) {
        super(port);
        mContext = c.getApplicationContext();
        mRepo = repo;
        mListener = listener;
        mGwmFolder = gwmFolder;
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
        return startNew(c, repo, listener, false);
    }

    /** Variant whose uploads go straight into the external GWM Split folder. */
    public static UploadServer startNewGwm(Context c, WallpaperRepo repo, UploadListener listener) throws IOException {
        return startNew(c, repo, listener, true);
    }

    private static UploadServer startNew(Context c, WallpaperRepo repo, UploadListener listener, boolean gwmFolder) throws IOException {
        IOException last = null;
        for(int i = 0; i < PORT_TRIES; i++) {
            UploadServer server = new UploadServer(c, PORT + i, repo, listener, gwmFolder);
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
                // The page posts to "/" under image0, image1, ... — see FIELD_PREFIX. The old
                // single-file field name is still accepted so a stale page left open on a phone
                // does not start failing after an app update.
                final List<String> saved = new ArrayList<>();
                boolean sawFile = false;
                for(int i = 0; i < MAX_BATCH; i++) {
                    String field = FIELD_PREFIX + i;
                    String tmpPath = files.get(field);
                    if(tmpPath == null && i == 0) {
                        field = FIELD_PREFIX;                 // legacy single-file page
                        tmpPath = files.get(field);
                    }
                    if(tmpPath == null) continue;
                    sawFile = true;
                    String one = saveUpload(tmpPath, originalName(session, field));
                    if(one != null) saved.add(one);
                }
                if(!saved.isEmpty()) {
                    // GWM uploads belong to a different app's folder — they are not our wallpaper,
                    // so none of the slideshow prefs apply. For our own uploads, make the last one
                    // the default wallpaper + enable the slideshow (picking one of a batch is
                    // arbitrary, but leaving PREF_DEFAULT on whatever came before is worse).
                    if(!mGwmFolder) {
                        SharedPreferences sp = mContext.getSharedPreferences(SettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
                        sp.edit()
                                .putString(WallpaperRepo.PREF_DEFAULT, saved.get(saved.size() - 1))
                                .putBoolean(WallpaperRepo.PREF_ENABLED, true)
                                .putBoolean(WallpaperRepo.PREF_LOCAL_ENABLED, true)
                                .apply();
                    }
                    if(mListener != null) {
                        mMain.post(new Runnable() {
                            @Override
                            public void run() { mListener.onUploaded(saved); }
                        });
                    }
                    return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8",
                            successPage(saved.size()));
                }
                if(sawFile) {
                    return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8",
                            "تعذّر حفظ الملف على الجهاز — تأكد من وجود مساحة كافية");
                }
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain; charset=utf-8",
                        "لم يتم اختيار ملف");
            }

            // Route on the path. This used to return the whole upload page for *every* URI,
            // so a phone asking for /favicon.ico got ~4.7 KB of Arabic HTML labelled
            // "text/html" and tried to decode it as an icon on every single page load.
            String uri = session.getUri();
            if(uri == null) uri = "/";
            int q = uri.indexOf('?');
            if(q >= 0) uri = uri.substring(0, q);

            if("/".equals(uri) || uri.isEmpty()) {
                return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", uploadPage());
            }
            if("/favicon.ico".equals(uri)) {
                // 204 rather than an icon: nothing to ship, and it stops the browser re-asking.
                return newFixedLengthResponse(Response.Status.NO_CONTENT, "image/x-icon", "");
            }
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain; charset=utf-8",
                    "الصفحة غير موجودة");
        } catch(Exception e) {
            // the phone shows this text, so say what actually went wrong instead of a
            // bare "upload failed"
            Log.e(TAG, "upload failed", e);
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain; charset=utf-8",
                    "فشل الرفع: " + reason);
        }
    }

    /** Append -2, -3, ... before the extension until the name is free. */
    private static File uniqueName(File dest, String ext) {
        if(!dest.exists()) return dest;
        String name = dest.getName();
        String stem = name.toLowerCase().endsWith(ext.toLowerCase())
                ? name.substring(0, name.length() - ext.length())
                : name;
        for(int i = 2; i < 1000; i++) {
            File candidate = new File(dest.getParentFile(), stem + "-" + i + ext);
            if(!candidate.exists()) return candidate;
        }
        return dest;
    }

    /** The browser sends the file's own name as a plain parameter under the same field name. */
    private String originalName(IHTTPSession session, String field) {
        List<String> names = session.getParameters().get(field);
        if(names != null && !names.isEmpty() && names.get(0) != null && !names.get(0).trim().isEmpty()) {
            return names.get(0);
        }
        return "upload.jpg";
    }

    private String saveUpload(String tmpPath, String originalName) {
        // GWM mode: hand the file to the GWM folder writer, which also tracks it so a later cloud
        // mirror does not treat it as a stranger's file. All the wallpaper-folder logic below is
        // skipped — this file is not one of our wallpapers.
        if(mGwmFolder) {
            try {
                FileInputStream in = new FileInputStream(tmpPath);
                String path = GwmSync.saveLocalCopy(mContext, in,
                        "upload_" + originalName.replaceAll("[\\\\/:*?\"<>|]", "_"));
                in.close();
                return path;
            } catch(Exception e) {
                Log.e(TAG, "saving the GWM upload failed", e);
                return null;
            }
        }
        try {
            String ext = ".jpg";
            int dot = originalName.lastIndexOf('.');
            if(dot >= 0 && dot < originalName.length() - 1) ext = originalName.substring(dot);
            // name so it sorts predictably; the default is tracked by PREF_DEFAULT path
            File dest = new File(mRepo.getLocalFolder(), "upload_" + originalName.replaceAll("[\\\\/:*?\"<>|]", "_"));
            if(!dest.getName().toLowerCase().endsWith(ext.toLowerCase())) {
                dest = new File(mRepo.getLocalFolder(), "upload" + ext);
            }
            // Never overwrite. Phones hand out colliding names constantly (IMG_0001.jpg on every
            // device, or the "upload.jpg" fallback above for a whole batch), and silently
            // replacing an existing wallpaper with a new one is data loss the user never asked
            // for — worse, in a batch the images would eat each other and only the last survive.
            dest = uniqueName(dest, ext);
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
     * Shared "Warm Aurora" styling for both pages.
     *
     * Everything here is inline on purpose: the head unit's Wi-Fi is usually a LAN with no
     * internet at all, so a single reference to a CDN font/CSS would block rendering until
     * the phone's DNS/connect timeout expires. No external CSS/JS/font/image — ever.
     */
    private static final String PAGE_CSS =
            "*{box-sizing:border-box}"
            + "body{margin:0;padding:20px 16px 40px;background:#14100b;color:#f5eee4;"
            + "font-family:system-ui,-apple-system,'Segoe UI',Roboto,'Noto Naskh Arabic',sans-serif;"
            + "font-size:17px;line-height:1.75;-webkit-text-size-adjust:100%}"
            + ".wrap{max-width:520px;margin:0 auto}"
            + ".brand{color:#ffd27a;font-size:23px;font-weight:700;text-align:center;margin:6px 0 2px;letter-spacing:.5px}"
            + ".sub{color:#b0a48f;font-size:16px;text-align:center;margin:0 0 20px}"
            + ".card{background:#2e2417;border:1px solid rgba(255,214,160,0.14);border-radius:16px;padding:20px;margin-bottom:16px}"
            // The privacy notice is the first thing under the title and carries the accent
            // bar — this is a promise to the customer, not fine print.
            + ".privacy{background:#2e2417;border:1px solid rgba(255,214,160,0.14);"
            + "border-right:5px solid #ff9a3d;border-radius:14px;padding:16px 18px;margin-bottom:18px}"
            + ".privacy b{display:block;color:#ffd27a;font-size:18px;margin-bottom:6px}"
            + ".privacy p{margin:0;font-size:16px;color:#f5eee4}"
            + ".pick{display:block;width:100%;min-height:60px;padding:18px;border-radius:14px;"
            + "background:#14100b;border:1.5px dashed rgba(255,214,160,0.35);color:#b0a48f;"
            + "font-size:17px;text-align:center;cursor:pointer}"
            + ".pick.has{border-style:solid;border-color:#ff9a3d;color:#f5eee4}"
            + "#file{position:absolute;width:1px;height:1px;opacity:0;pointer-events:none}"
            + "#btn{display:block;width:100%;min-height:60px;margin-top:14px;border:0;border-radius:14px;"
            + "background:#ff9a3d;color:#14100b;font-size:19px;font-weight:700;cursor:pointer;"
            + "font-family:inherit}"
            + "#btn:disabled{background:#5a4a33;color:#b0a48f;cursor:default}"
            + "#bar{display:none;height:10px;margin-top:16px;border-radius:99px;background:#14100b;"
            + "border:1px solid rgba(255,214,160,0.14);overflow:hidden}"
            + "#fill{height:100%;width:0;background:#ffd27a;transition:width .2s}"
            + "#msg{margin:14px 0 0;font-size:16px;min-height:24px;text-align:center;color:#b0a48f}"
            // Instructions for a customer who just scanned a code off a dashboard: quieter than
            // the card so it never competes with the upload button, but full size to stay
            // readable on a phone held at arm's length in a car.
            + ".steps{background:#2e2417;border:1px solid rgba(255,214,160,0.14);border-radius:16px;"
            + "padding:18px 20px}"
            + ".steps b{color:#ffd27a}"
            + ".steps>b{display:block;font-size:18px;margin-bottom:10px}"
            + ".steps ol{margin:0;padding-inline-start:22px}"
            + ".steps li{margin-bottom:10px;font-size:16px}"
            + ".steps li:last-child{margin-bottom:0}"
            + ".steps .note{margin:14px 0 0;font-size:15px;color:#b0a48f}"
            + ".err{color:#ff8f6b}.ok{color:#ffd27a}"
            + "a{color:#ffd27a}";

    /**
     * The upload page. It posts with XMLHttpRequest instead of a plain form submit so the
     * customer sees the progress and, if something does go wrong, the reason the device
     * reported — a plain form just showed a blank browser error.
     *
     * The Arabic below stays hardcoded on purpose: this page is served to the *customer's*
     * phone, and getString() would answer in the head unit's locale, not theirs.
     */
    private String uploadPage() {
        return "<!doctype html><html dir='rtl' lang='ar'><head>"
                + "<meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<meta name='theme-color' content='#14100b'>"
                + "<title>ذبذبة خلفيات</title>"
                + "<style>" + PAGE_CSS + "</style></head>"
                + "<body><div class='wrap'>"
                + "<div class='brand'>ذبذبة خلفيات</div>"
                + "<p class='sub'>THABTHABA STORE</p>"

                + "<div class='privacy'>"
                + "<b>صورك تبقى في سيارتك وحدها</b>"
                + "<p>الصور التي ترفعها من هنا تُحفَظ داخل شاشة هذه السيارة فقط، ولا تُرسَل إلى"
                + " منصتنا السحابية ولا إلى أي جهاز آخر، ولا يمكن لأحد غيرك الاطّلاع عليها.</p>"
                + "</div>"

                + "<div class='card'>"
                + "<p style='margin:0 0 14px'>اختر صورة أو أكثر من هاتفك لتظهر على الشاشة كخلفية"
                + " — تقدر تختار أكتر من صورة مرة واحدة</p>"
                + "<form id='f' method='post' action='/' enctype='multipart/form-data'>"
                + "<input id='file' type='file' name='image' accept='image/*,video/*' multiple required>"
                + "<label id='pick' class='pick' for='file'>اضغط هنا لاختيار الصور أو الفيديو</label>"
                + "<button id='btn' type='submit'>رفع الصور</button>"
                + "</form>"
                + "<div id='bar'><div id='fill'></div></div>"
                + "<p id='msg'></p>"
                + "</div>"

                // What happens next, in the customer's own words. They scanned a code off a car
                // dashboard with no idea what they are in the middle of — the steps are numbered
                // so it reads as a short process with an end, and it sits UNDER the button so it
                // never pushes the thing they came to press below the fold.
                + "<div class='steps'>"
                + "<b>إزاي تحطّ صورتك على الشاشة</b>"
                + "<ol>"
                + "<li>اضغط <b>اختيار الصور</b> واختر من ألبوم هاتفك — تقدر تختار أكتر من صورة.</li>"
                + "<li>اضغط <b>رفع الصور</b> واستنى شريط التقدّم لحد ما يكمّل.</li>"
                + "<li>الصورة هتظهر على شاشة السيارة على طول، وتقدر تظبّط مكانها وحجمها من الشاشة نفسها.</li>"
                + "</ol>"
                + "<p class='note'>خلّي هاتفك على نفس شبكة الواي‑فاي بتاعة السيارة، وما تقفلش الصفحة"
                + " دي وانت بترفع. الصور بتفضل موجودة على الشاشة حتى بعد ما تقفل الصفحة.</p>"
                + "</div>"

                + "</div><script>"
                + "var f=document.getElementById('f'),b=document.getElementById('btn'),"
                + "m=document.getElementById('msg'),fi=document.getElementById('file'),"
                + "pick=document.getElementById('pick'),bar=document.getElementById('bar'),"
                + "fill=document.getElementById('fill');"
                // Immediate feedback on choosing a file, so the page never looks inert.
                + "fi.onchange=function(){if(fi.files.length){pick.className='pick has';"
                + "pick.textContent=fi.files.length===1?fi.files[0].name:('تم اختيار '+fi.files.length+' ملف');"
                + "m.className='';m.textContent='';}};"
                + "function reset(t){b.disabled=false;b.textContent='رفع الصور';bar.style.display='none';"
                + "fill.style.width='0';m.className='err';m.textContent=t;}"
                + "f.onsubmit=function(e){e.preventDefault();"
                + "if(!fi.files.length){m.className='err';m.textContent='اختر ملف أولاً';return;}"
                + "if(fi.files.length>" + MAX_BATCH + "){m.className='err';"
                + "m.textContent='أقصى عدد " + MAX_BATCH + " ملف في المرة الواحدة';return;}"
                // Numbered field names: a repeated name would collapse to one file server-side.
                + "var fd=new FormData();"
                + "for(var i=0;i<fi.files.length;i++){fd.append('image'+i,fi.files[i]);}"
                + "var x=new XMLHttpRequest();x.open('POST','/',true);x.timeout=180000;"
                // in-progress state: button locks, bar appears, percentage counts up
                + "b.disabled=true;b.textContent='جارٍ الرفع…';bar.style.display='block';"
                + "m.className='';m.textContent='جارٍ تجهيز الملف…';"
                + "x.upload.onprogress=function(ev){if(ev.lengthComputable){"
                + "var p=Math.round(ev.loaded*100/ev.total);fill.style.width=p+'%';"
                + "m.textContent='جارٍ الرفع… '+p+'%';}};"
                + "x.upload.onload=function(){fill.style.width='100%';"
                + "m.textContent='جارٍ الحفظ على الشاشة…';};"
                + "x.onload=function(){"
                + "if(x.status===200){document.open();document.write(x.responseText);document.close();}"
                + "else{reset(x.responseText||('خطأ '+x.status));}};"
                + "x.onerror=function(){reset('انقطع الاتصال بالجهاز — تأكد إن الموبايل والشاشة على نفس شبكة الواي‑فاي وحاول تاني');};"
                + "x.ontimeout=function(){reset('الرفع أخذ وقتاً طويلاً — جرّب ملفاً أصغر أو قرّب من الراوتر');};"
                + "x.send(fd);};"
                + "</" + "script></body></html>";
    }

    private String successPage(int count) {
        String headline = count == 1 ? "تم الرفع بنجاح" : ("تم رفع " + count + " صور بنجاح");
        String detail = count == 1
                ? "الصورة وصلت للشاشة — كمّل عليها من الشاشة نفسها."
                : "الصور وصلت للشاشة — كمّل عليها من الشاشة نفسها.";
        return "<!doctype html><html dir='rtl' lang='ar'><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<meta name='theme-color' content='#14100b'>"
                + "<title>تم الرفع</title>"
                + "<style>" + PAGE_CSS + "</style></head>"
                + "<body><div class='wrap'>"
                + "<div class='brand'>ذبذبة خلفيات</div>"
                + "<p class='sub'>THABTHABA STORE</p>"
                + "<div class='card' style='text-align:center'>"
                + "<div style='font-size:44px;line-height:1;color:#ffd27a'>✓</div>"
                + "<h2 style='color:#ffd27a;font-size:21px;margin:10px 0 6px'>" + headline + "</h2>"
                + "<p style='margin:0;color:#b0a48f'>" + detail + "</p>"
                + "</div>"
                + "<div class='privacy'>"
                + "<b>محفوظة في هذه السيارة فقط</b>"
                + "<p>لم تُرسَل صورك إلى منصتنا السحابية — هي مخزّنة داخل شاشة هذه السيارة وحدها.</p>"
                + "</div>"
                + "<p style='text-align:center'><a href='/'>رفع صور أخرى</a></p>"
                + "</div></body></html>";
    }

    /** The device's local Wi-Fi IPv4 address, or null. Prefers the actual Wi-Fi interface. */
    public static String getLocalIpAddress(Context ctx) {
        // 1) Ask the Wi-Fi manager directly — most reliable for the real Wi-Fi IP.
        //    But only believe it if the address is still bound to a live interface:
        //    getConnectionInfo() reports the *station* lease, which on a head unit that is
        //    now running its own hotspot (or that roamed) can be a stale address from
        //    another network. Putting that in the QR points the customer's phone at an
        //    IP it cannot route to, and the browser then sits on a blank page until the
        //    TCP connect times out — which looks exactly like "white screen, very slow".
        try {
            WifiManager wm = (WifiManager) ctx.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if(wm != null && wm.getConnectionInfo() != null) {
                int ip = wm.getConnectionInfo().getIpAddress();
                if(ip != 0) {
                    String addr = String.format(Locale.US, "%d.%d.%d.%d",
                            (ip & 0xff), (ip >> 8 & 0xff), (ip >> 16 & 0xff), (ip >> 24 & 0xff));
                    if(isBoundToLiveInterface(addr)) return addr;
                    Log.w(TAG, "WifiManager reported " + addr + " but no live interface has it — ignoring");
                }
            }
        } catch(Exception ignored) { }
        // 2) Fall back to scanning interfaces, preferring wlan and skipping cellular/VPN
        String wlan = scanInterfaces(true);
        if(wlan != null) return wlan;
        return scanInterfaces(false);
    }

    /** True if some up, non-loopback interface currently holds this IPv4 address. */
    private static boolean isBoundToLiveInterface(String addr) {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            while(ifaces.hasMoreElements()) {
                NetworkInterface ni = ifaces.nextElement();
                if(!ni.isUp() || ni.isLoopback()) continue;
                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while(addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if(a instanceof Inet4Address && addr.equals(a.getHostAddress())) return true;
                }
            }
        } catch(Exception ignored) { }
        return false;
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
