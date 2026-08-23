package systems.sieber.fsclock;

/**
 * One wallpaper entry coming from the remote manifest (or the local cache).
 * type is one of {@link #TYPE_IMAGE}, {@link #TYPE_GIF} or {@link #TYPE_VIDEO}.
 */
public class WallpaperItem {

    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_GIF = "gif";
    public static final String TYPE_VIDEO = "video";

    /**
     * Every extension the app accepts as a moving wallpaper.
     *
     * The list is here, once, because it used to live in two places — this class and
     * {@code WallpaperRepo.isSupportedMedia} — and they drifted: a phone could hand over a file
     * whose type was recognised but which the folder scan then refused to list, so the upload
     * vanished with no error anywhere.
     */
    static final String[] VIDEO_EXT = {
            ".mp4", ".m4v", ".mov", ".webm", ".mkv", ".3gp", ".3g2", ".ts", ".avi"
    };

    /**
     * Still pictures, animated ones excluded (see {@link #GIF_EXT}).
     *
     * heic/heif is why this list grew: it is what an iPhone stores by default, and Safari only
     * converts it to JPEG on upload some of the time. The rest of the app decodes it fine —
     * Android has since API 28 — so the only thing that ever rejected it was this list.
     */
    static final String[] IMAGE_EXT = {
            ".jpg", ".jpeg", ".jfif", ".png", ".webp", ".bmp",
            ".heic", ".heif", ".avif"
    };

    static final String[] GIF_EXT = { ".gif" };

    public String type;
    public String url;

    public WallpaperItem() {}

    public WallpaperItem(String type, String url) {
        this.type = type;
        this.url = url;
    }

    public boolean isVideo() {
        return TYPE_VIDEO.equals(type);
    }

    /** Guess the type from the file extension when the manifest does not specify it. */
    static String guessType(String url) {
        String u = bareName(url);
        if(u == null) return TYPE_IMAGE;
        if(endsWithAny(u, GIF_EXT)) return TYPE_GIF;
        if(endsWithAny(u, VIDEO_EXT)) return TYPE_VIDEO;
        return TYPE_IMAGE;
    }

    /** True for anything the app knows how to show — picture, GIF or clip. */
    static boolean isSupportedMedia(String url) {
        String u = bareName(url);
        if(u == null) return false;
        return endsWithAny(u, IMAGE_EXT) || endsWithAny(u, GIF_EXT) || endsWithAny(u, VIDEO_EXT);
    }

    /** Lower-cased and stripped of any query string, or null. */
    private static String bareName(String url) {
        if(url == null) return null;
        String u = url.toLowerCase();
        int q = u.indexOf('?');
        if(q >= 0) u = u.substring(0, q);
        return u;
    }

    private static boolean endsWithAny(String name, String[] exts) {
        for(String e : exts) if(name.endsWith(e)) return true;
        return false;
    }
}
