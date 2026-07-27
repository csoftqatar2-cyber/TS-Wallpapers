package systems.sieber.fsclock;

import android.graphics.Bitmap;
import android.graphics.Matrix;

import androidx.annotation.NonNull;

import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool;
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation;

import java.security.MessageDigest;

/**
 * Turns a bitmap by a right angle inside Glide's pipeline.
 *
 * The wallpaper screen rotates with a matrix and never touches pixels, but a thumbnail is a
 * plain centre-cropped ImageView with no matrix of its own — so without this the picker would
 * keep showing an image on its side after the technician had already straightened it, which
 * reads as "the rotation did not save".
 *
 * The degrees are part of the cache key, so 0° and 90° of the same file are distinct entries
 * and one never serves the other.
 */
public class RotateTransformation extends BitmapTransformation {

    private static final String ID = "systems.sieber.fsclock.RotateTransformation";

    private final int mDegrees;

    public RotateTransformation(int degrees) {
        mDegrees = FitSettings.clampRotation(degrees);
    }

    @Override
    protected Bitmap transform(@NonNull BitmapPool pool, @NonNull Bitmap toTransform,
                               int outWidth, int outHeight) {
        if(mDegrees == 0) return toTransform;
        Matrix m = new Matrix();
        m.setRotate(mDegrees);
        return Bitmap.createBitmap(toTransform, 0, 0,
                toTransform.getWidth(), toTransform.getHeight(), m, true);
    }

    @Override
    public void updateDiskCacheKey(@NonNull MessageDigest md) {
        md.update((ID + mDegrees).getBytes(CHARSET));
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof RotateTransformation && ((RotateTransformation) o).mDegrees == mDegrees;
    }

    @Override
    public int hashCode() {
        return ID.hashCode() + mDegrees * 31;
    }
}
