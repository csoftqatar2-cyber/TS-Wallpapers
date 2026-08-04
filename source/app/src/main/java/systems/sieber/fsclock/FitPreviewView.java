package systems.sieber.fsclock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
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

    /**
     * Pinch reports continuously so the slider can follow the fingers, and once more with
     * {@code settled} when they lift — that is the moment worth writing to disk, rather than
     * every frame of the gesture.
     */
    public interface OnZoomChangeListener {
        void onZoomChanged(float zoom, boolean settled);
    }

    private final Paint mFramePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The shared fit maths — the same object the applied wallpaper is baked with. */
    private final FitRenderer mRenderer = new FitRenderer();

    private Bitmap mImage;
    private Bitmap mBackdrop;          // blurred copy, rebuilt only when strength changes
    private int mBackdropForStrength = -1;

    private FitSettings mFit = new FitSettings();
    private int mTargetW = 1920, mTargetH = 720;
    private float mFocalX = 0.5f, mFocalY = 0.5f;
    private boolean mVideo;

    private OnFocalChangeListener mFocalListener;
    private OnZoomChangeListener mZoomListener;
    private float mDownX, mDownY, mDownFocalX, mDownFocalY;

    private final ScaleGestureDetector mScaleDetector;
    /** True between the first and last frame of a pinch, so the drag stands aside. */
    private boolean mScaling;

    public FitPreviewView(Context c, AttributeSet a) {
        super(c, a);
        setBackgroundColor(Color.BLACK);
        mFramePaint.setStyle(Paint.Style.STROKE);
        mFramePaint.setStrokeWidth(2f * getResources().getDisplayMetrics().density);
        mFramePaint.setColor(androidx.core.content.ContextCompat.getColor(c, R.color.colorAccent));

        mScaleDetector = new ScaleGestureDetector(c, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(ScaleGestureDetector d) {
                mScaling = true;
                return true;
            }
            @Override public boolean onScale(ScaleGestureDetector d) {
                float before = FitSettings.clampZoom(mFit.zoom);
                float after = FitSettings.clampZoom(before * d.getScaleFactor());
                if(after == before) return true;      // already at an end of the range
                mFit.zoom = after;
                invalidate();
                if(mZoomListener != null) mZoomListener.onZoomChanged(after, false);
                return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d) {
                mScaling = false;
                if(mZoomListener != null) mZoomListener.onZoomChanged(FitSettings.clampZoom(mFit.zoom), true);
            }
        });
    }

    public void setOnFocalChangeListener(OnFocalChangeListener l) { mFocalListener = l; }
    public void setOnZoomChangeListener(OnZoomChangeListener l) { mZoomListener = l; }

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
        float ew = effW(), eh = effH();
        float fit = Math.min(mTargetW / ew, mTargetH / eh) * FitSettings.clampZoom(mFit.zoom);
        return ew * fit >= mTargetW - 1f;
    }

    /** The image's width/height as the screen sees them, i.e. after the rotation. */
    private float effW() {
        return mImage == null ? 0f : mFit.effectiveW(mImage.getWidth(), mImage.getHeight());
    }

    private float effH() {
        return mImage == null ? 0f : mFit.effectiveH(mImage.getWidth(), mImage.getHeight());
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        // Always the target screen's shape — a preview in the view's own aspect would lie.
        int wMode = MeasureSpec.getMode(wSpec), hMode = MeasureSpec.getMode(hSpec);
        int w = MeasureSpec.getSize(wSpec), h = MeasureSpec.getSize(hSpec);
        if(wMode == MeasureSpec.UNSPECIFIED) w = h > 0
                ? Math.round(h * mTargetW / (float) mTargetH) : mTargetW;
        if(hMode == MeasureSpec.UNSPECIFIED) h = w > 0
                ? Math.round(w * mTargetH / (float) mTargetW) : mTargetH;
        if(w <= 0 || h <= 0) { setMeasuredDimension(0, 0); return; }
        float target = mTargetW / (float) mTargetH;
        int outW, outH;
        if(w / (float) h < target) { outW = w; outH = Math.round(w / target); }
        else { outH = h; outW = Math.round(h * target); }
        setMeasuredDimension(outW, outH);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int vw = getWidth(), vh = getHeight();
        if(vw <= 0 || vh <= 0) return;
        if(mImage == null) {
            drawFrame(canvas, vw, vh);
            return;
        }

        int mode = resolvedMode();
        // The blur is kept here rather than in the renderer because only this screen re-draws
        // dozens of times a second while a slider moves.
        if(mode == FitSettings.MODE_BLUR && (mBackdrop == null || mBackdropForStrength != mFit.blur)) {
            mBackdrop = ImageBlur.blur(mImage, mFit.blur);
            mBackdropForStrength = mFit.blur;
        }
        mRenderer.draw(canvas, vw, vh, mImage, mBackdrop, mFit, mode, mFocalX, mFocalY);
        drawFrame(canvas, vw, vh);
    }

    private void drawFrame(Canvas canvas, int vw, int vh) {
        // Centre the stroke on the exact target edge without clipping its outer half.
        float inset = mFramePaint.getStrokeWidth() / 2f;
        canvas.drawRect(inset, inset, vw - inset, vh - inset, mFramePaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if(mImage == null) return false;

        // Pinch is fed first and without any precondition. Zooming in is precisely how an image
        // that fits becomes one that overflows, so gating it behind the overflow test below
        // would make the gesture impossible to start on the images that most need it.
        mScaleDetector.onTouchEvent(e);

        if(e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        // While two fingers are down the midpoint jumps around; treating that as a drag throws
        // the crop across the screen. Re-anchor when a finger lifts so the drag that follows a
        // pinch starts from where the remaining finger actually is.
        if(mScaling || e.getPointerCount() > 1) {
            mDownX = e.getX(); mDownY = e.getY();
            mDownFocalX = mFocalX; mDownFocalY = mFocalY;
            return true;
        }
        if(e.getActionMasked() == MotionEvent.ACTION_POINTER_UP) {
            mDownX = e.getX(); mDownY = e.getY();
            mDownFocalX = mFocalX; mDownFocalY = mFocalY;
            return true;
        }

        // Dragging only means something where the image overflows the screen — which is FILL,
        // but now also any mode zoomed past the point where it fits.
        int vw = getWidth(), vh = getHeight();
        float ew = effW(), eh = effH();
        float base = resolvedMode() == FitSettings.MODE_FILL
                ? Math.max(vw / ew, vh / eh)
                : Math.min(vw / ew, vh / eh);
        float scale = base * FitSettings.clampZoom(mFit.zoom);
        float overflowX = ew * scale - vw;
        float overflowY = eh * scale - vh;

        switch(e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownX = e.getX(); mDownY = e.getY();
                mDownFocalX = mFocalX; mDownFocalY = mFocalY;
                // Claim the gesture even with nothing to drag. Returning false here would end
                // delivery for the whole gesture, and the second finger of a pinch would never
                // arrive — which is exactly the case on an image that currently fits.
                return true;
            case MotionEvent.ACTION_MOVE:
                if(overflowX <= 0f && overflowY <= 0f) return true;   // nothing to pan, still ours
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
