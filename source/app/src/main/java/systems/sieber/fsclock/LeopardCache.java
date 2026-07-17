package systems.sieber.fsclock;

import android.content.Context;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Turns a cloud wallpaper URL into something the live wallpaper engine can actually read.
 *
 * The engine (a faithful port of the proven MiniWallpaper reference) asks the ContentResolver
 * for the MIME type and opens the URI through it. That works for a content:// from the system
 * file picker and it does not work for an https:// URL — no type, no stream. Rather than teach
 * the engine about HTTP, which would mean changing the one part of Leopard that is already
 * proven, the file is downloaded once and handed over as a content:// from our own
 * FileProvider, with a real extension so the type resolves.
 */
class LeopardCache {

    private static final String DIR = "leopard";

    /** True when this needs {@link #materialise} before it can be applied. */
    static boolean isRemote(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /**
     * Download (or reuse) the file and return a content:// URI for it.
     * Blocking — call from a background thread.
     *
     * @return null if it could not be fetched.
     */
    static Uri materialise(Context ctx, String url) {
        try {
            File dir = new File(ctx.getCacheDir(), DIR);
            if(!dir.exists() && !dir.mkdirs()) return null;

            File dest = new File(dir, fileName(url));
            // Same URL, same file: a re-pick of the current wallpaper should not re-download.
            if(!dest.exists() || dest.length() == 0) {
                if(!download(url, dest)) return null;
            }
            return FileProvider.getUriForFile(ctx, ctx.getPackageName() + ".fileprovider", dest);
        } catch(Throwable t) {
            return null;
        }
    }

    /**
     * The extension is load-bearing: FileProvider derives the MIME from it, and the engine
     * branches image/gif/video on that MIME. A name without one would make every video decode
     * as a still and draw nothing.
     */
    private static String fileName(String url) {
        String ext = extensionOf(url);
        return "wp_" + Integer.toHexString(url.hashCode()) + "." + ext;
    }

    private static String extensionOf(String url) {
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if(q >= 0) u = u.substring(0, q);
        int dot = u.lastIndexOf('.');
        if(dot >= 0 && dot > u.lastIndexOf('/')) {
            String ext = u.substring(dot + 1);
            if(ext.length() >= 2 && ext.length() <= 4 && ext.matches("[a-z0-9]+")) return ext;
        }
        // No usable extension: fall back to the type the manifest claimed.
        return WallpaperItem.TYPE_VIDEO.equals(WallpaperItem.guessType(url)) ? "mp4" : "jpg";
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
            if(c.getResponseCode() / 100 != 2) return false;

            in = c.getInputStream();
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[16384];
            int n;
            while((n = in.read(buf)) != -1) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;

            // Rename only once it is whole, so a dropped connection can never leave a
            // half-file that later looks cached. A car's link drops constantly.
            if(dest.exists()) //noinspection ResultOfMethodCallIgnored
                dest.delete();
            return tmp.renameTo(dest);
        } catch(Throwable t) {
            return false;
        } finally {
            if(out != null) try { out.close(); } catch(Exception ignored) {}
            if(in != null) try { in.close(); } catch(Exception ignored) {}
            if(c != null) c.disconnect();
            if(tmp.exists()) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }
}
