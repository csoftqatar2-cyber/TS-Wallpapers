package systems.sieber.fsclock;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.DisplayMetrics;

import androidx.annotation.NonNull;

/**
 * The SeekBar track for the bar-colour slider, painted as the spectrum itself — so the slider
 * shows what it selects.
 *
 * This is Java rather than a drawable XML because Android's `<gradient>` takes three colour
 * stops and a hue ramp needs seven; a three-stop approximation would skip green and blue
 * outright, which is most of the point.
 *
 * Deliberately no `@android:id/progress` layer: a hue slider has a position, not an amount, so
 * there is nothing for a "filled so far" bar to mean.
 */
public class HueTrackDrawable extends Drawable {

    /** Full turn of the hue wheel, ending where it started so the ramp has no seam. */
    private static final int[] HUES = {
            0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
            0xFF0000FF, 0xFFFF00FF, 0xFFFF0000
    };

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();
    private final int mHeightPx;
    private int mShaderForWidth = -1;

    public HueTrackDrawable(DisplayMetrics dm) {
        mHeightPx = Math.round(dm.density * 10);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        int w = getBounds().width(), h = getBounds().height();
        if(w <= 0 || h <= 0) return;

        if(mShaderForWidth != w) {
            mPaint.setShader(new LinearGradient(getBounds().left, 0, getBounds().right, 0,
                    HUES, null, Shader.TileMode.CLAMP));
            mShaderForWidth = w;
        }

        // Centre a fixed-height bar in whatever the SeekBar hands us, rather than filling it —
        // the track should read the same weight as the blur and zoom sliders beside it.
        float barH = Math.min(mHeightPx, h);
        float top = getBounds().top + (h - barH) / 2f;
        mRect.set(getBounds().left, top, getBounds().right, top + barH);
        canvas.drawRoundRect(mRect, barH / 2f, barH / 2f, mPaint);
    }

    @Override
    public int getIntrinsicHeight() {
        return mHeightPx;
    }

    @Override
    public void setAlpha(int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(ColorFilter cf) {
        mPaint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.OPAQUE;
    }

    /** The colour this slider selects at the given position. Fully saturated, fully bright:
     *  the neutrals it cannot reach are exactly the preset swatches sitting next to it. */
    public static int colorAt(int degrees) {
        return Color.HSVToColor(new float[]{ ((degrees % 360) + 360) % 360, 1f, 1f });
    }

    /** Where on the track a colour sits, so the slider can be positioned to match one that was
     *  chosen some other way (a preset, or the sampled edge colour). */
    public static int hueOf(int color) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return Math.round(hsv[0]);
    }
}
