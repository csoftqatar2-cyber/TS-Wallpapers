package systems.sieber.fsclock;

import android.graphics.Bitmap;

/**
 * Software box blur for the fitted-wallpaper backdrop.
 *
 * RenderScript is deprecated and RenderEffect needs API 31; this app is minSdk 21 and runs on
 * cheap head-unit SoCs. So: downscale hard, blur small, and let the upscale do most of the
 * smoothing. Blurring a 64px-wide bitmap and stretching it to 1920 is both faster and softer
 * than blurring at full size, and the backdrop is out of focus by definition — nobody can tell.
 */
class ImageBlur {

    /** Backdrop is never sampled above this width before blurring. */
    private static final int MAX_SAMPLE_W = 160;
    private static final int MIN_SAMPLE_W = 24;

    /**
     * @param strength 0..100. 0 returns a plain (unblurred) copy — the honest bottom of the
     *                 slider's range, per the brief.
     */
    static Bitmap blur(Bitmap src, int strength) {
        if(src == null || src.getWidth() <= 0 || src.getHeight() <= 0) return src;
        strength = FitSettings.clampBlur(strength);

        // Stronger blur == smaller sample buffer. This is what actually carries the effect;
        // the box passes below only clean up the blockiness.
        float t = strength / 100f;
        int sampleW = (int) (MAX_SAMPLE_W - t * (MAX_SAMPLE_W - MIN_SAMPLE_W));
        if(sampleW < MIN_SAMPLE_W) sampleW = MIN_SAMPLE_W;

        int w = Math.max(1, sampleW);
        int h = Math.max(1, Math.round(src.getHeight() * (w / (float) src.getWidth())));
        Bitmap small = Bitmap.createScaledBitmap(src, w, h, true);

        // When the source is already exactly this wide the scale is the identity, and
        // createScaledBitmap hands back the SOURCE rather than a copy. boxBlur writes in place,
        // so without this it would setPixels() on Glide's shared, immutable cache bitmap and
        // throw. Only bites when a wallpaper decodes to 24..160px wide, which is rare and
        // therefore exactly the kind of crash that reaches a customer rather than us.
        if(strength > 0 && (small == src || !small.isMutable())) {
            small = small.copy(Bitmap.Config.ARGB_8888, true);
            if(small == null) return src;   // OOM: an unblurred backdrop beats no wallpaper
        }

        if(strength > 0) {
            int radius = 1 + Math.round(t * 3);   // 1..4 on an already tiny bitmap
            boxBlur(small, radius);
            boxBlur(small, radius);               // two passes ~ gaussian, still cheap
        }
        return small;
    }

    /** Separable box blur, in place. */
    private static void boxBlur(Bitmap bmp, int radius) {
        int w = bmp.getWidth(), h = bmp.getHeight();
        if(w < 2 || h < 2 || radius < 1) return;
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        blurPass(px, w, h, radius, true);
        blurPass(px, w, h, radius, false);
        bmp.setPixels(px, 0, w, 0, 0, w, h);
    }

    private static void blurPass(int[] px, int w, int h, int radius, boolean horizontal) {
        int outer = horizontal ? h : w;
        int inner = horizontal ? w : h;
        int[] line = new int[inner];
        for(int o = 0; o < outer; o++) {
            for(int i = 0; i < inner; i++) {
                line[i] = px[horizontal ? o * w + i : i * w + o];
            }
            int[] blurred = new int[inner];
            for(int i = 0; i < inner; i++) {
                int a = 0, r = 0, g = 0, b = 0, n = 0;
                int from = Math.max(0, i - radius), to = Math.min(inner - 1, i + radius);
                for(int k = from; k <= to; k++) {
                    int c = line[k];
                    a += (c >>> 24) & 0xff;
                    r += (c >> 16) & 0xff;
                    g += (c >> 8) & 0xff;
                    b += c & 0xff;
                    n++;
                }
                blurred[i] = ((a / n) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n);
            }
            for(int i = 0; i < inner; i++) {
                px[horizontal ? o * w + i : i * w + o] = blurred[i];
            }
        }
    }
}
