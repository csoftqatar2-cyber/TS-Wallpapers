package systems.sieber.fsclock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Shader;
import android.util.AttributeSet;

import androidx.appcompat.widget.AppCompatImageView;

/**
 * An ImageView whose left and right edges can be faded out to transparent.
 *
 * The fade removes alpha from the image itself rather than painting a colour over it, so what
 * shows through is whatever is behind — the blurred backdrop in the fit modes, black in FILL.
 * That is the point: it dissolves the hard vertical seam where a fitted image meets its blurred
 * bars, which is the most visible artefact of the fit modes.
 */
public class FadingImageView extends AppCompatImageView {

    private static final PorterDuffXfermode DST_OUT = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    /** 0..100. The total width faded, split evenly between the two sides. */
    private int mFade = 0;

    private Shader mLeft, mRight;
    private int mShaderW = -1;
    private int mShaderFade = -1;

    public FadingImageView(Context c) { super(c); }
    public FadingImageView(Context c, AttributeSet a) { super(c, a); }

    public void setFade(int percent) {
        int v = FitSettings.clampFade(percent);
        if(v == mFade) return;
        mFade = v;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if(mFade <= 0 || w <= 0 || h <= 0) {
            super.onDraw(canvas);
            return;
        }

        // The xfermode has to act on the image alone, so it needs its own layer — applied
        // straight to the canvas it would eat whatever was drawn underneath as well.
        int save = canvas.saveLayer(0, 0, w, h, null);
        super.onDraw(canvas);

        float band = bandWidth(w);
        buildShaders(w);

        mPaint.setXfermode(DST_OUT);
        mPaint.setShader(mLeft);
        canvas.drawRect(0, 0, band, h, mPaint);
        mPaint.setShader(mRight);
        canvas.drawRect(w - band, 0, w, h, mPaint);
        mPaint.setShader(null);
        mPaint.setXfermode(null);

        canvas.restoreToCount(save);
    }

    /** At 100 the two bands meet in the middle, so the image is gone entirely. */
    private float bandWidth(int w) {
        return w * mFade / 200f;
    }

    private void buildShaders(int w) {
        if(mLeft != null && mShaderW == w && mShaderFade == mFade) return;
        float band = bandWidth(w);
        mLeft = new LinearGradient(0, 0, band, 0,
                0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
        mRight = new LinearGradient(w - band, 0, w, 0,
                0x00000000, 0xFF000000, Shader.TileMode.CLAMP);
        mShaderW = w;
        mShaderFade = mFade;
    }
}
