package systems.sieber.fsclock;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.ExifInterface;

/**
 * Turns a bitmap the way its file's EXIF tag says it should be shown.
 *
 * Glide honours EXIF orientation; {@link android.graphics.BitmapFactory} does not. Every
 * wallpaper on the clock screen and every thumbnail in the picker comes through Glide, while
 * the fit editor and the Leopard bake decode the file themselves — so a phone photo (they
 * nearly all carry an orientation tag; a screenshot does not, which is why this only ever
 * showed up on pictures sent from a phone) was drawn one way in the editor and another way
 * everywhere else.
 *
 * The visible symptom was not "the picture is sideways". It was "the edit did not save": the
 * technician turned the photo until the EDITOR looked right, and the wallpaper screen — which
 * had already applied the EXIF turn — then showed something that matched neither. Reopening
 * the editor showed the rotation still set, which made it look like the screen had ignored it.
 *
 * So this is not a rotation feature. It is the one line that makes the editor and the screen
 * decode the same file into the same picture, which is what {@link FitSettings#rotation} is
 * measured against.
 */
final class ExifOrientation {

    private ExifOrientation() {}

    /**
     * The transform the file asks for, or null when it asks for nothing.
     *
     * All eight EXIF orientations are covered, not just the four rotations: Glide applies the
     * mirrored ones too, and handling only half of them would leave exactly the same mismatch
     * for the photos that carry them.
     */
    private static Matrix matrixFor(String path) {
        int o;
        try {
            o = new ExifInterface(path).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
        } catch(Throwable t) {
            // Not a JPEG, or the tag is unreadable. Upright is what every caller assumed
            // before this class existed, so it stays the answer when there is nothing to read.
            return null;
        }
        Matrix m = new Matrix();
        switch(o) {
            case ExifInterface.ORIENTATION_FLIP_HORIZONTAL: m.setScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_180:      m.setRotate(180f); break;
            case ExifInterface.ORIENTATION_FLIP_VERTICAL:   m.setScale(1f, -1f); break;
            case ExifInterface.ORIENTATION_TRANSPOSE:       m.setRotate(90f); m.postScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_90:       m.setRotate(90f); break;
            case ExifInterface.ORIENTATION_TRANSVERSE:      m.setRotate(270f); m.postScale(-1f, 1f); break;
            case ExifInterface.ORIENTATION_ROTATE_270:      m.setRotate(270f); break;
            default: return null;   // ORIENTATION_NORMAL / UNDEFINED — nothing to do
        }
        return m;
    }

    /**
     * The bitmap as the file says it should be shown.
     *
     * Returns the source untouched when there is nothing to apply, so the common case costs one
     * tag read and no second allocation. When a new bitmap IS produced the source is recycled:
     * these are full-screen decodes of phone photos and keeping both would double the peak.
     */
    static Bitmap apply(Bitmap src, String path) {
        if(src == null || path == null) return src;
        Matrix m = matrixFor(path);
        if(m == null) return src;
        try {
            Bitmap out = Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
            if(out == null) return src;
            if(out != src) src.recycle();
            return out;
        } catch(Throwable t) {
            // Out of memory turning a very large photo: a picture facing the wrong way is still
            // a picture, and it is what the customer had before this ran.
            return src;
        }
    }
}
