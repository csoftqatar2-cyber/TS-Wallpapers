package systems.sieber.fsclock;

import android.content.Context;
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
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Live preview of one wallpaper, drawn at the target screen's aspect ratio rather than this
 * view's own. What you see here is what the wallpaper screen will render — the fit maths is
 * deliberately the same shape as WallpaperView.applyImageMatrix().
 *
 * In FILL mode the image can be dragged to choose the surviving crop; the fit modes have
 * nothing to reposition, so dragging is ignored.
 */
public class FitPreviewView extends View {

    public interface OnFocalChangeListener {
        void onFocalChanged(float fx, float fy);
    }

    private static final PorterDuffXfermode DST_OUT = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);

    private final Paint mPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint mFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect mSrc = new Rect();
    private final RectF mDst = new RectF();

    private Bitmap mImage;
    private Bitmap mBackdrop;          // blurred copy, rebuilt only when strength changes
    private int mBackdropForStrength = -1;

    private Shader mFadeLeft, mFadeRight;
    private int mFadeShaderW = -1, mFadeShaderFade = -1;

    private FitSettings mFit = new FitSettings();
    private int mTargetW = 1920, mTargetH = 720;
    private float mFocalX = 0.5f, mFocalY = 0.5f;
    private boolean mVideo;

    private OnFocalChangeListener mFocalListener;
    private float mDownX, mDownY, mDownFocalX, mDownFocalY;

    public FitPreviewView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(Color.BLACK);
    }

    public void setOnFocalChangeListener(OnFocalChangeListener l) { mFocalListener = l; }

    public void setTargetSize(int w, int h) {
        if(w > 0 && h > 0) { mTargetW = w; mTargetH = h; requestLayout(); }
    }

    public void setImage(Bitmap bmp, boolean isVideo) {
        mImage = bmp;
        mVideo = isVideo;
        mBackdrop = null;
        mBackdropForStrength = -1;
        invalidate();
    }

    public void setFocal(float fx, float fy) {
        mFocalX = clamp01(fx);
        mFocalY = clamp01(fy);
        invalidate();
    }

    public float getFocalX() { return mFocalX; }
    public float getFocalY() { return mFocalY; }

    public void setFit(FitSettings f) {
        mFit = f;
        invalidate();
    }

    /** The mode actually being drawn, with MODE_AUTO already resolved against this image. */
    public int resolvedMode() {
        if(mImage == null) return FitSettings.MODE_FILL;
        int m = mFit.resolved(mImage.getWidth(), mImage.getHeight(), mTargetW, mTargetH);
        // Per-frame blur is far too expensive on this hardware, so a video in a fit mode gets
        // flat colour bars. The editor greys the Blur tile out to match.
        if(mVideo && m == FitSettings.MODE_BLUR) return FitSettings.MODE_COLOR;
        return m;
    }

    /** True when this image is wide enough that the bars are invisible anyway. */
    public boolean barsAreNegligible() {
        if(mImage == null) return true;
        if(mFit.fade > 0) return false;   // the fade needs the backdrop, however wide the image
        float fit = Math.min(mTargetW / (float) mImage.getWidth(), mTargetH / (float) mImage.getHeight())
                * FitSettings.clampZoom(mFit.zoom);
        return mImage.getWidth() * fit >= mTargetW - 1f;
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // Always the target screen's shape — a preview in the view's own aspect would lie.
        int w = MeasureSpec.getSize(wSpec);
        int h = MeasureSpec.getSize(hSpec);
        if(w <= 0 && h <= 0) { setMeasuredDimension(0, 0); return; }
        float target = mTargetW / (float) mTargetH;
        int outW, outH;
        if(h <= 0 || w / (float) h < target) { outW = w; outH = Math.round(w / target); }
        else { outH = h; outW = Math.round(h * target); }
        setMeasuredDimension(outW, outH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int vw = getWidth(), vh = getHeight();
        if(mImage == null || vw <= 0 || vh <= 0) return;

        int mode = resolvedMode();
        int iw = mImage.getWidth(), ih = mImage.getHeight();
        float zoom = FitSettings.clampZoom(mFit.zoom);
        mSrc.set(0, 0, iw, ih);

        // Zooming out or fading the edges makes even a FILL image stop covering, so the backdrop
        // has to be painted first there too. Only BLUR blurs — mirrors WallpaperView.applyBackdrop().
        if(mFit.needsBackdrop(mode)) {
            if(mode == FitSettings.MODE_BLUR) {
                drawBlurBackdrop(canvas, vw, vh, iw, ih);
            } else {
                canvas.drawColor(mFit.barColor);
            }
        }

        float base = mode == FitSettings.MODE_FILL
                ? Math.max(vw / (float) iw, vh / (float) ih)
                : Math.min(vw / (float) iw, vh / (float) ih);
        drawScaled(canvas, mImage, base * zoom, vw, vh, iw, ih);
    }

    private void drawBlurBackdrop(Canvas canvas, int vw, int vh, int iw, int ih) {
        if(mBackdrop == null || mBackdropForStrength != mFit.blur) {
            mBackdrop = ImageBlur.blur(mImage, mFit.blur);
            mBackdropForStrength = mFit.blur;
        }
        canvas.drawColor(Color.BLACK);
        // Cover the whole preview, so the bars are never empty.
        int bw = mBackdrop.getWidth(), bh = mBackdrop.getHeight();
        float scale = Math.max(vw / (float) bw, vh / (float) bh);
        float sw = bw * scale, sh = bh * scale;
        mDst.set((vw - sw) / 2f, (vh - sh) / 2f, (vw + sw) / 2f, (vh + sh) / 2f);
        mSrc.set(0, 0, bw, bh);
        canvas.drawBitmap(mBackdrop, mSrc, mDst, mPaint);
        mSrc.set(0, 0, iw, ih);
        // Knock the backdrop back so the sharp image stays the subject.
        canvas.drawColor(0x59000000);
    }

    private void drawScaled(Canvas canvas, Bitmap bmp, float scale, int vw, int vh, int iw, int ih) {
        float sw = iw * scale, sh = ih * scale;
        float left = offset(sw, vw, mFocalX);
        float top = offset(sh, vh, mFocalY);
        mDst.set(left, top, left + sw, top + sh);

        if(mFit.fade <= 0) {
            canvas.drawBitmap(bmp, mSrc, mDst, mPaint);
            return;
        }

        // Remove the edges from the image itself, so the backdrop below shows through — the
        // same DST_OUT trick FadingImageView uses on the real wallpaper.
        int save = canvas.saveLayer(0, 0, vw, vh, null);
        canvas.drawBitmap(bmp, mSrc, mDst, mPaint);
        float band = buildFadeShaders(vw);
        mFadePaint.setXfermode(DST_OUT);
        mFadePaint.setShader(mFadeLeft);
        canvas.drawRect(0, 0, band, vh, mFadePaint);
        mFadePaint.setShader(mFadeRight);
        canvas.drawRect(vw - band, 0, vw, vh, mFadePaint);
        mFadePaint.setShader(null);
        mFadePaint.setXfermode(null);
        canvas.restoreToCount(save);
    }

    /** Cached: onDraw runs on every frame of a fade drag, and two fresh shaders per frame is
     *  real jank on head-unit hardware. Returns the band width. */
    private float buildFadeShaders(int vw) {
        float band = vw * mFit.fade / 200f;
        if(mFadeLeft != null && mFadeShaderW == vw && mFadeShaderFade == mFit.fade) return band;
        mFadeLeft = new LinearGradient(0, 0, band, 0, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
        mFadeRight = new LinearGradient(vw - band, 0, vw, 0, 0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
        mFadeShaderW = vw;
        mFadeShaderFade = mFit.fade;
        return band;
    }

    /** Mirrors WallpaperView.offset(): overflow pans by the focal point, anything else centres. */
    private static float offset(float scaled, float view, float focal) {
        float overflow = scaled - view;
        if(overflow <= 0f) return (view - scaled) / 2f;
        return -overflow * clamp01(focal);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        // Dragging only means something where the image overflows the screen — which is FILL,
        // but now also any mode zoomed past the point where it fits.
        if(mImage == null) return false;
        int vw = getWidth(), vh = getHeight();
        int iw = mImage.getWidth(), ih = mImage.getHeight();
        float base = resolvedMode() == FitSettings.MODE_FILL
                ? Math.max(vw / (float) iw, vh / (float) ih)
                : Math.min(vw / (float) iw, vh / (float) ih);
        float scale = base * FitSettings.clampZoom(mFit.zoom);
        float overflowX = iw * scale - vw;
        float overflowY = ih * scale - vh;
        if(overflowX <= 0f && overflowY <= 0f) return false;

        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = e.getX(); mDownY = e.getY();
                mDownFocalX = mFocalX; mDownFocalY = mFocalY;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if(overflowX > 0) mFocalX = clamp01(mDownFocalX - (e.getX() - mDownX) / overflowX);
                if(overflowY > 0) mFocalY = clamp01(mDownFocalY - (e.getY() - mDownY) / overflowY);
                invalidate();
                if(mFocalListener != null) mFocalListener.onFocalChanged(mFocalX, mFocalY);
                return true;
        }
        return super.onTouchEvent(e);
    }

    private static float clamp01(float v) { return v < 0f ? 0f : (v > 1f ? 1f : v); }
}
