package systems.sieber.fsclock;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;

/**
 * How an image sits on a screen of a given size — the fit maths, in one place.
 *
 * This used to live only inside {@link FitPreviewView}, which was fine while the editor was the
 * only thing that needed it: the slideshow renders through WallpaperView and never asked. Leopard
 * broke that. There the chosen picture is handed to Android's wallpaper system as a finished
 * bitmap, so an edit that only exists as numbers in the preferences would be shown in the editor
 * and then thrown away by the apply — the user would frame a photo and watch the raw one land on
 * the dashboard.
 *
 * So the drawing moved here and the view now calls it. The editor's preview and the bitmap that
 * actually becomes the wallpaper are the same code by construction; they cannot drift.
 *
 * The instance exists only to cache the fade shaders, which onDraw would otherwise rebuild on
 * every frame of a drag. {@link #bake} needs none of that and is static.
 */
class FitRenderer {

    private static final PorterDuffXfermode DST_OUT = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);

    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();

    private Shader mFadeLeft, mFadeRight;
    private int mFadeShaderW = -1, mFadeShaderFade = -1;

    /**
     * Draw {@code image} onto {@code canvas} as it will appear on a {@code vw} x {@code vh} screen.
     *
     * @param backdrop pre-blurred copy, required for MODE_BLUR and ignored otherwise. The caller
     *                 owns it because blurring is expensive and each caller caches differently:
     *                 the editor keeps one per strength, the bake makes one and drops it.
     * @param mode     already resolved — MODE_AUTO means nothing this far down.
     */
    void draw(Canvas canvas, int vw, int vh, Bitmap image, Bitmap backdrop,
              FitSettings fit, int mode, float focalX, float focalY) {
        if(image == null || vw <= 0 || vh <= 0) return;

        int iw = image.getWidth(), ih = image.getHeight();
        float zoom = FitSettings.clampZoom(fit.zoom);
        mSrc.set(0, 0, iw, ih);

        // Zooming out or fading the edges makes even a FILL image stop covering, so the backdrop
        // has to be painted first there too. Only BLUR blurs — mirrors WallpaperView.applyBackdrop().
        if(fit.needsBackdrop(mode)) {
            if(mode == FitSettings.MODE_BLUR && backdrop != null) {
                drawBlurBackdrop(canvas, vw, vh, backdrop, iw, ih);
            } else {
                canvas.drawColor(fit.barColor);
            }
        }

        // Measured on the rotated shape — same rule as WallpaperView.applyImageMatrix().
        float ew = fit.effectiveW(iw, ih), eh = fit.effectiveH(iw, ih);
        float base = mode == FitSettings.MODE_FILL
                ? Math.max(vw / ew, vh / eh)
                : Math.min(vw / ew, vh / eh);
        drawScaled(canvas, image, base * zoom, vw, vh, iw, ih, ew, eh, fit, focalX, focalY);
    }

    private void drawBlurBackdrop(Canvas canvas, int vw, int vh, Bitmap backdrop, int iw, int ih) {
        canvas.drawColor(Color.BLACK);
        // Cover the whole screen, so the bars are never empty.
        int bw = backdrop.getWidth(), bh = backdrop.getHeight();
        float scale = Math.max(vw / (float) bw, vh / (float) bh);
        float sw = bw * scale, sh = bh * scale;
        mDst.set((vw - sw) / 2f, (vh - sh) / 2f, (vw + sw) / 2f, (vh + sh) / 2f);
        mSrc.set(0, 0, bw, bh);
        canvas.drawBitmap(backdrop, mSrc, mDst, mPaint);
        mSrc.set(0, 0, iw, ih);
        // Knock the backdrop back so the sharp image stays the subject.
        canvas.drawColor(0x59000000);
    }

    private void drawScaled(Canvas canvas, Bitmap bmp, float scale, int vw, int vh,
                            int iw, int ih, float ew, float eh,
                            FitSettings fit, float focalX, float focalY) {
        // The rotated image occupies a box of ew x eh on screen; the bitmap itself is still drawn
        // at iw x ih, centred on that box, with the canvas turned under it.
        float bw = ew * scale, bh = eh * scale;
        float left = offset(bw, vw, focalX);
        float top = offset(bh, vh, focalY);
        float cx = left + bw / 2f, cy = top + bh / 2f;
        float sw = iw * scale, sh = ih * scale;
        mDst.set(cx - sw / 2f, cy - sh / 2f, cx + sw / 2f, cy + sh / 2f);

        if(fit.fade <= 0) {
            drawRotated(canvas, bmp, cx, cy, fit);
            return;
        }

        // Remove the edges from the image itself, so the backdrop below shows through — the
        // same DST_OUT trick FadingImageView uses on the real wallpaper. The fade bands are in
        // screen space (they are screen edges, not image edges), so they go on after the
        // rotation has been restored.
        int save = canvas.saveLayer(0, 0, vw, vh, null);
        drawRotated(canvas, bmp, cx, cy, fit);
        float band = buildFadeShaders(vw, fit.fade);
        mFadePaint.setXfermode(DST_OUT);
        mFadePaint.setShader(mFadeLeft);
        canvas.drawRect(0, 0, band, vh, mFadePaint);
        mFadePaint.setShader(mFadeRight);
        canvas.drawRect(vw - band, 0, vw, vh, mFadePaint);
        mFadePaint.setShader(null);
        mFadePaint.setXfermode(null);
        canvas.restoreToCount(save);
    }

    /** Draw the bitmap into mDst, spun about the box centre when a rotation is set. */
    private void drawRotated(Canvas canvas, Bitmap bmp, float cx, float cy, FitSettings fit) {
        int rot = FitSettings.clampRotation(fit.rotation);
        if(rot == 0) {
            canvas.drawBitmap(bmp, mSrc, mDst, mPaint);
            return;
        }
        int save = canvas.save();
        canvas.rotate(rot, cx, cy);
        canvas.drawBitmap(bmp, mSrc, mDst, mPaint);
        canvas.restoreToCount(save);
    }

    /** Cached: onDraw runs on every frame of a fade drag, and two fresh shaders per frame is
     *  real jank on head-unit hardware. Returns the band width. */
    private float buildFadeShaders(int vw, int fade) {
        float band = vw * fade / 200f;
        if(mFadeLeft != null && mFadeShaderW == vw && mFadeShaderFade == fade) return band;
        mFadeLeft = new LinearGradient(0, 0, band, 0, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
        mFadeRight = new LinearGradient(vw - band, 0, vw, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
        mFadeShaderW = vw;
        mFadeShaderFade = fade;
        return band;
    }

    /** Mirrors WallpaperView.offset(): overflow pans by the focal point, anything else centres. */
    private static float offset(float scaled, float view, float focal) {
        float overflow = scaled - view;
        if(overflow <= 0f) return (view - scaled) / 2f;
        return -overflow * clamp01(focal);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }

    /**
     * The framing the editor showed, as a finished screen-sized bitmap.
     *
     * Black underneath rather than transparent: this becomes a wallpaper, and a JPEG has no alpha
     * to lose anyway — leaving it unpainted would turn the bars black on one device and white on
     * the next depending on how the encoder felt.
     */
    static Bitmap bake(Bitmap image, FitSettings fit, float focalX, float focalY,
                       int outW, int outH, boolean isVideo) {
        if(image == null || outW <= 0 || outH <= 0) return null;
        int mode = fit.resolved(image.getWidth(), image.getHeight(), outW, outH);
        // Per-frame blur is too expensive to play back, so a video in a fit mode gets flat bars.
        // Baking a still has no such limit, but the editor greys the tile out for video and the
        // result must match what was on screen.
        if(isVideo && mode == FitSettings.MODE_BLUR) mode = FitSettings.MODE_COLOR;

        Bitmap out = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(out);
        c.drawColor(Color.BLACK);
        Bitmap backdrop = mode == FitSettings.MODE_BLUR ? ImageBlur.blur(image, fit.blur) : null;
        new FitRenderer().draw(c, outW, outH, image, backdrop, fit, mode, focalX, focalY);
        if(backdrop != null && backdrop != image) backdrop.recycle();
        return out;
    }
}
