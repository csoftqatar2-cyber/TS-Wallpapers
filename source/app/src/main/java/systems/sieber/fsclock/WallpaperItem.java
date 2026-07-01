package systems.sieber.fsclock;

/**
 * One wallpaper entry coming from the remote manifest (or the local cache).
 * type is one of {@link #TYPE_IMAGE}, {@link #TYPE_GIF} or {@link #TYPE_VIDEO}.
 */
public class WallpaperItem {

    public static final String TYPE_IMAGE = "image";
    public static final String TYPE_GIF = "gif";
    public static final String TYPE_VIDEO = "video";

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
        if(url == null) return TYPE_IMAGE;
        String u = url.toLowerCase();
        // strip query string
        int q = u.indexOf('?');
        if(q >= 0) u = u.substring(0, q);
        if(u.endsWith(".gif")) return TYPE_GIF;
        if(u.endsWith(".mp4") || u.endsWith(".webm") || u.endsWith(".mkv")
                || u.endsWith(".3gp") || u.endsWith(".m4v") || u.endsWith(".mov")) return TYPE_VIDEO;
        return TYPE_IMAGE;
    }
}
