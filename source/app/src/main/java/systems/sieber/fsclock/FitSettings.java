package systems.sieber.fsclock;

import android.graphics.Color;

/**
 * How one wallpaper is fitted to the screen.
 *
 * The screen is ~2.67:1 (FSE 1920x720). People upload 9:16 phone photos, which the old
 * center-crop scaled ~4.7x and cropped to a sliver. So an image that is much taller than the
 * screen is instead fitted whole, and the leftover side bars get filled — either with a
 * blurred copy of the image, or with a flat colour.
 */
public class FitSettings {

    /** Zoom to cover; edges are cropped. Correct for wide/landscape photos. */
    public static final int MODE_FILL = 0;
    /** Fit whole, sides filled with a blurred, zoomed copy of the image. */
    public static final int MODE_BLUR = 1;
    /** Fit whole, sides filled with a flat colour. */
    public static final int MODE_COLOR = 2;
    /** Not a render mode: "pick FILL or BLUR by measuring the image". Defaults only. */
    public static final int MODE_AUTO = 3;

    /** An image this much narrower than the screen gets the blurred backdrop by default.
     *  0.6 puts a 9:16 phone photo well inside, and leaves a 16:9 landscape shot outside. */
    private static final float AUTO_BLUR_RATIO = 0.6f;

    public int mode = MODE_BLUR;
    /** 0..100. 0 = backdrop is a plain zoomed copy, not blurred. */
    public int blur = 60;
    public int barColor = Color.BLACK;
    /** Multiplies whatever scale the mode computed. 1.0 = the mode's own framing. */
    public float zoom = ZOOM_DEFAULT;
    /** 0..100: how much of each side the image fades out over, as a percentage of view width. */
    public int fade = 0;

    public static final float ZOOM_MIN = 0.5f;
    public static final float ZOOM_MAX = 5.0f;
    public static final float ZOOM_DEFAULT = 1.0f;

    public FitSettings() {}

    public FitSettings(int mode, int blur, int barColor) {
        this.mode = mode;
        this.blur = clampBlur(blur);
        this.barColor = barColor;
    }

    public FitSettings(int mode, int blur, int barColor, float zoom, int fade) {
        this(mode, blur, barColor);
        this.zoom = clampZoom(zoom);
        this.fade = clampFade(fade);
    }

    public static int clampBlur(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }

    public static float clampZoom(float v) {
        return v < ZOOM_MIN ? ZOOM_MIN : (v > ZOOM_MAX ? ZOOM_MAX : v);
    }

    public static int clampFade(int v) {
        return v < 0 ? 0 : (v > 100 ? 100 : v);
    }

    /**
     * Zooming out below 1.0 shrinks the image away from the screen edges, and FILL has no
     * backdrop of its own — so without this the gap would show whatever is behind the slot,
     * which during a crossfade is the previous wallpaper.
     */
    public boolean needsBackdrop(int resolvedMode) {
        return isFitMode(resolvedMode) || zoom < 1f || fade > 0;
    }

    /**
     * Resolve MODE_AUTO against the real image and screen. A portrait photo on a very wide
     * screen becomes BLUR; anything near the screen's own shape stays FILL.
     */
    public static int resolveAuto(int contentW, int contentH, int screenW, int screenH) {
        if(contentW <= 0 || contentH <= 0 || screenW <= 0 || screenH <= 0) return MODE_FILL;
        float imageAspect = (float) contentW / contentH;
        float screenAspect = (float) screenW / screenH;
        return imageAspect < screenAspect * AUTO_BLUR_RATIO ? MODE_BLUR : MODE_FILL;
    }

    /** The mode to actually render with, never MODE_AUTO. */
    public int resolved(int contentW, int contentH, int screenW, int screenH) {
        if(mode == MODE_AUTO) return resolveAuto(contentW, contentH, screenW, screenH);
        return mode;
    }

    /** True when the image is drawn whole and side bars exist. */
    public static boolean isFitMode(int resolvedMode) {
        return resolvedMode == MODE_BLUR || resolvedMode == MODE_COLOR;
    }

    String serialize() {
        return mode + "," + blur + "," + barColor + "," + zoom + "," + fade;
    }

    /**
     * Tolerates the three-field form written before zoom and fade existed, so wallpapers already
     * fitted on a customer's car keep their settings across the update.
     */
    static FitSettings parse(String s, FitSettings fallback) {
        if(s == null || s.isEmpty()) return fallback;
        try {
            String[] p = s.split(",");
            FitSettings f = new FitSettings(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            if(p.length > 3) f.zoom = clampZoom(Float.parseFloat(p[3]));
            if(p.length > 4) f.fade = clampFade(Integer.parseInt(p[4]));
            return f;
        } catch(Exception e) {
            return fallback;
        }
    }
}
