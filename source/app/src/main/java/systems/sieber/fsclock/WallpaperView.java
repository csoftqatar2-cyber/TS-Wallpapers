package systems.sieber.fsclock;

import android.graphics.drawable.BitmapDrawable;
import com.bumptech.glide.load.resource.gif.GifDrawable;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import java.io.File;
import android.view.Surface;
import android.view.TextureView;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;

/**
 * Background layer that shows the current wallpaper (static image, animated GIF or
 * looping video) and animates the switch with a nice slide + 3D rotation effect.
 *
 * It keeps two stacked "slots" and ping-pongs between them so the outgoing and the
 * incoming wallpaper are both visible during the transition, regardless of type.
 *
 * Video is rendered with a TextureView (+ MediaPlayer) instead of VideoView so that
 * it correctly follows the translation/rotation/alpha transition animation and is
 * center-cropped to fill the screen.
 */
public class WallpaperView extends FrameLayout {

    private static final int DURATION = 650;

    /**
     * Cap on how long a video crossfade waits for {@link MediaPlayer#prepareAsync()} before
     * starting anyway. A new decoder session is real setup cost that Glide's image path never
     * pays, so without this a slow prepare would leave the incoming slot invisible indefinitely
     * on a bad clip instead of just showing it a beat late.
     */
    private static final int VIDEO_READY_TIMEOUT_MS = 900;

    private class Slot {
        FrameLayout root;
        FadingImageView image;
        ImageView backdrop;         // blurred/flat fill behind the image, fit modes only
        TextureView texture;
        MediaPlayer player;
        WallpaperItem pendingVideo; // set while waiting for the surface to become available
        Runnable pendingReady;      // fired once the surface becomes available and playback starts
        int gen;                   // bumped on every populate(); guards stale async callbacks
        WallpaperItem item;         // content currently shown in this slot
        int contentW, contentH;     // natural pixel size of the image/video (0 until known)
        float focalX = 0.5f;        // 0..1 pan position (0.5 = center-crop, the default)
        float focalY = 0.5f;
        FitSettings fit;            // how this wallpaper is fitted; null until the item loads
        Bitmap sharp;               // decoded frame, kept so the backdrop can be rebuilt
    }

    private Slot mSlotA;
    private Slot mSlotB;
    private Slot mFront;
    private WallpaperRepo mRepo;

    public WallpaperView(Context c, AttributeSet attrs) {
        super(c, attrs);
        setBackgroundColor(Color.BLACK);
        mSlotA = createSlot(c);
        mSlotB = createSlot(c);
        addView(mSlotA.root);
        addView(mSlotB.root);
        mSlotB.root.setVisibility(GONE);
        mFront = mSlotA;
    }

    private Slot createSlot(Context c) {
        final Slot s = new Slot();
        s.root = new FrameLayout(c);
        s.root.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        s.root.setBackgroundColor(Color.BLACK);
        // big camera distance so the rotationY effect does not clip
        s.root.setCameraDistance(8000 * getResources().getDisplayMetrics().density);

        s.texture = new TextureView(c);
        s.texture.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        s.texture.setVisibility(GONE);
        s.texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                if(s.pendingVideo != null) {
                    WallpaperItem item = s.pendingVideo;
                    Runnable ready = s.pendingReady;
                    s.pendingVideo = null;
                    s.pendingReady = null;
                    startPlayer(s, item, new Surface(surface), ready);
                }
            }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                if(s.player != null) applyVideoScale(s, s.player.getVideoWidth(), s.player.getVideoHeight());
            }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                return true;
            }
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) { }
        });
        s.root.addView(s.texture);

        // Backdrop sits behind the sharp image and only shows in the fit modes, where the image
        // does not cover the screen. Added first so it stays underneath.
        s.backdrop = new ImageView(c);
        s.backdrop.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        s.backdrop.setScaleType(ImageView.ScaleType.CENTER_CROP);
        s.backdrop.setVisibility(GONE);
        s.root.addView(s.backdrop);

        s.image = new FadingImageView(c);
        s.image.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        // MATRIX so we control the crop/pan ourselves (center-crop by default, see applyImageMatrix)
        s.image.setScaleType(ImageView.ScaleType.MATRIX);
        s.root.addView(s.image);

        return s;
    }

    public void setRepo(WallpaperRepo repo) {
        mRepo = repo;
    }

    /** Remove any content (used when the slideshow is disabled). */
    public void clearAll() {
        releaseVideo(mSlotA);
        releaseVideo(mSlotB);
        mSlotA.image.setImageDrawable(null);
        mSlotB.image.setImageDrawable(null);
        mSlotA.item = mSlotB.item = null;
    }

    /**
     * Show the given item.
     * @param direction +1 = new content enters from the right (next),
     *                  -1 = enters from the left (previous), 0 = no animation.
     */
    public void showItem(WallpaperItem item, int direction) {
        if(item == null) { clearAll(); return; }

        if(direction == 0 || getWidth() == 0) {
            populate(mFront, item, null);
            mFront.root.setTranslationX(0);
            mFront.root.setRotationY(0);
            mFront.root.setAlpha(1f);
            mFront.root.setVisibility(VISIBLE);
            Slot other = (mFront == mSlotA) ? mSlotB : mSlotA;
            releaseVideo(other);
            other.root.setVisibility(GONE);
            return;
        }

        final Slot incoming = (mFront == mSlotA) ? mSlotB : mSlotA;
        final Slot outgoing = mFront;
        mFront = incoming;

        // Images render into the ImageView fast enough (Glide, usually cached) that starting
        // the fade immediately still lines up. Video pays for a brand new decoder session on
        // every swipe, so starting the same fade immediately would finish it on a black/blank
        // texture and the frame would visibly pop in late — the fade is deferred to onPrepared
        // instead, capped by VIDEO_READY_TIMEOUT_MS so a slow or broken clip cannot strand the
        // transition. beginCrossfade guards against firing twice (onPrepared racing the timeout).
        final boolean[] started = {false};
        Runnable begin = new Runnable() {
            @Override
            public void run() {
                if(started[0]) return;
                started[0] = true;
                beginCrossfade(incoming, outgoing);
            }
        };
        populate(incoming, item, item.isVideo() ? begin : null);
        if(item.isVideo()) {
            incoming.root.postDelayed(begin, VIDEO_READY_TIMEOUT_MS);
        } else {
            begin.run();
        }
    }

    /**
     * Smooth crossfade: the incoming wallpaper fades in on top of the outgoing one, so both
     * overlap and blend during the transition. No black background on the incoming slot while
     * fading, otherwise it would darken the outgoing wallpaper showing through underneath.
     */
    private void beginCrossfade(final Slot incoming, final Slot outgoing) {
        incoming.root.setBackgroundColor(Color.TRANSPARENT);
        incoming.root.setTranslationX(0);
        incoming.root.setRotationY(0);
        incoming.root.setVisibility(VISIBLE);
        incoming.root.setAlpha(0f);
        bringChildToFront(incoming.root);
        incoming.root.animate()
                .alpha(1f)
                .setDuration(DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        // restore the opaque background once fully shown
                        incoming.root.setBackgroundColor(Color.BLACK);
                        outgoing.root.setVisibility(GONE);
                        outgoing.root.setAlpha(1f);
                        releaseVideo(outgoing);
                        outgoing.image.setImageDrawable(null);
                    }
                })
                .start();
    }

    private void populate(final Slot slot, WallpaperItem item, final Runnable onReady) {
        releaseVideo(slot);
        final int myGen = ++slot.gen;
        slot.item = item;
        slot.contentW = slot.contentH = 0;
        slot.sharp = null;
        slot.backdrop.setVisibility(GONE);
        slot.backdrop.setImageDrawable(null);
        // How this wallpaper is fitted. Unlike the focal point this applies whether or not FSE
        // is on: a 9:16 photo is butchered by center-crop on any wide screen, not just 1920x720.
        slot.fit = mRepo != null ? mRepo.getFit(item.url) : new FitSettings();
        // Load the saved focal point for this wallpaper. Now that "adjust position" is offered
        // on the normal screen too (not just FSE), the saved position must be restored in both:
        // getFocal() returns the centre (0.5, 0.5) when nothing was ever saved, so a wallpaper
        // the user never repositioned still centre-crops exactly as before.
        if(mRepo != null && item.url != null) {
            float[] f = mRepo.getFocal(item.url);
            slot.focalX = f[0];
            slot.focalY = f[1];
        } else {
            slot.focalX = 0.5f;
            slot.focalY = 0.5f;
        }
        if(item.isVideo()) {
            slot.image.setImageDrawable(null);
            slot.texture.setVisibility(VISIBLE);
            // Stale guard: if this slot gets repopulated with something else before this
            // video's callback lands, gen has moved on and the old callback is a no-op.
            Runnable ready = onReady == null ? null : new Runnable() {
                @Override
                public void run() {
                    if(slot.gen == myGen) onReady.run();
                }
            };
            if(slot.texture.isAvailable() && slot.texture.getSurfaceTexture() != null) {
                startPlayer(slot, item, new Surface(slot.texture.getSurfaceTexture()), ready);
            } else {
                slot.pendingVideo = item; // started in onSurfaceTextureAvailable
                slot.pendingReady = ready;
            }
        } else {
            slot.pendingVideo = null;
            slot.texture.setVisibility(GONE);
            slot.image.setVisibility(VISIBLE);
            // remote URLs load by string; local files must be loaded as a File (Glide can't take a bare path)
            Object model;
            String u = item.url == null ? "" : item.url;
            if(u.startsWith("http://") || u.startsWith("https://")
                    || u.startsWith("content://") || u.startsWith("file://")) {
                model = u;
            } else {
                model = new File(u);
            }
            // Glide animates GIFs automatically and handles network + disk caching.
            // No .centerCrop() here: we keep the full image and crop/pan it ourselves
            // with a matrix so the wallpaper can be repositioned in FSE mode.
            Glide.with(getContext().getApplicationContext())
                    .load(model)
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model,
                                                    Target<Drawable> target, boolean isFirstResource) {
                            return false;
                        }
                        @Override
                        public boolean onResourceReady(Drawable resource, Object model,
                                                       Target<Drawable> target, DataSource dataSource,
                                                       boolean isFirstResource) {
                            slot.contentW = resource.getIntrinsicWidth();
                            slot.contentH = resource.getIntrinsicHeight();
                            // Keep the pixels so the blurred backdrop can be built from them.
                            // A GIF only yields its first frame here, which is all the backdrop
                            // needs — an animated blur would cost far more than it is worth.
                            slot.sharp = bitmapOf(resource);
                            slot.image.post(new Runnable() {
                                @Override public void run() { applyImageMatrix(slot); }
                            });
                            return false; // let Glide set the drawable on the ImageView
                        }
                    })
                    .into(slot.image);
        }
    }

    /**
     * Lay the image into its ImageView.
     *
     * FILL scales to cover and pans by the focal point — the original behaviour. The fit modes
     * scale to *contain* instead, so a 9:16 phone photo is shown whole rather than zoomed ~4.7x
     * into a sliver, and the leftover side bars are filled by the backdrop.
     */
    private void applyImageMatrix(Slot slot) {
        int vw = slot.image.getWidth(), vh = slot.image.getHeight();
        int cw = slot.contentW, ch = slot.contentH;
        if(vw <= 0 || vh <= 0 || cw <= 0 || ch <= 0) return;

        int mode = resolvedMode(slot, vw, vh);
        FitSettings fit = slot.fit != null ? slot.fit : new FitSettings();
        float zoom = FitSettings.clampZoom(fit.zoom);
        int rot = FitSettings.clampRotation(fit.rotation);
        // Everything below measures the image as the screen will see it: a quarter turn swaps
        // its width and height, and fitting the pre-rotation shape would frame the wrong box.
        int ew = fit.effectiveW(cw, ch), eh = fit.effectiveH(cw, ch);

        float scale = FitSettings.isFitMode(mode)
                ? Math.min((float) vw / ew, (float) vh / eh) * zoom
                : Math.max((float) vw / ew, (float) vh / eh) * zoom;
        float sw = ew * scale, sh = eh * scale;   // on-screen size of the rotated image

        Matrix m = new Matrix();
        // Rotate about the bitmap's own centre first: the turned image then occupies a box of
        // ew x eh centred on that same point, which the scale and translate below place. At
        // rotation 0 this collapses to the original setScale/postTranslate exactly.
        m.setRotate(rot, cw / 2f, ch / 2f);
        m.postScale(scale, scale);
        // Nothing is cropped at zoom 1 in a fit mode, so centring is the whole story; past 1 the
        // image starts overflowing and the focal point takes over, exactly as in FILL.
        m.postTranslate(offset(sw, vw, slot.focalX) - (cw - ew) * scale / 2f,
                        offset(sh, vh, slot.focalY) - (ch - eh) * scale / 2f);
        slot.image.setImageMatrix(m);
        slot.image.setFade(slot.fit != null ? slot.fit.fade : 0);
        applyBackdrop(slot, mode);
    }

    /**
     * Where a scaled edge lands on one axis. Overflowing content pans by the focal point;
     * content that fits (zoomed out, or fit-mode at zoom 1) is centred, because there is no
     * crop for a focal point to choose between.
     */
    private static float offset(float scaled, float view, float focal) {
        float overflow = scaled - view;
        if(overflow <= 0f) return (view - scaled) / 2f;
        return -overflow * clamp01(focal);
    }

    /** Pull pixels out of whatever Glide handed us, for the blurred backdrop. */
    private static Bitmap bitmapOf(Drawable d) {
        try {
            if(d instanceof BitmapDrawable) return ((BitmapDrawable) d).getBitmap();
            if(d instanceof GifDrawable) return ((GifDrawable) d).getFirstFrame();
            int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
            if(w <= 0 || h <= 0) return null;
            // Anything else (rare): rasterise once, small — the backdrop is blurred anyway.
            float scale = Math.min(1f, 320f / Math.max(w, h));
            Bitmap bmp = Bitmap.createBitmap(Math.max(1, Math.round(w * scale)),
                    Math.max(1, Math.round(h * scale)), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bmp);
            d.setBounds(0, 0, c.getWidth(), c.getHeight());
            d.draw(c);
            return bmp;
        } catch(Throwable t) {
            return null;
        }
    }

    /** The fit mode to draw this slot with, with MODE_AUTO already resolved. */
    private int resolvedMode(Slot slot, int vw, int vh) {
        if(slot.fit == null || slot.contentW <= 0 || slot.contentH <= 0) return FitSettings.MODE_FILL;
        int mode = slot.fit.resolved(slot.contentW, slot.contentH, vw, vh);
        // Per-frame blur on a video is far past this hardware's budget; fall back to flat bars.
        if(mode == FitSettings.MODE_BLUR && slot.item != null && slot.item.isVideo()) {
            return FitSettings.MODE_COLOR;
        }
        return mode;
    }

    /**
     * Paint the side bars onto the backdrop layer.
     *
     * Deliberately not slot.root's background: the crossfade in showItem() sets that to
     * TRANSPARENT so the outgoing wallpaper can show through, so the two would fight over the
     * same property and the bars would flicker on every switch.
     */
    private void applyBackdrop(Slot slot, int mode) {
        slot.backdrop.setColorFilter(null);
        slot.backdrop.setImageDrawable(null);

        // Not just the fit modes: a zoomed-out or faded image also stops covering the screen, and
        // without something behind it the gap would show the outgoing wallpaper mid-crossfade.
        if(slot.fit == null || !slot.fit.needsBackdrop(mode)) {
            slot.backdrop.setVisibility(GONE);
            return;
        }
        slot.backdrop.setVisibility(VISIBLE);

        // Only BLUR gets a blurred backdrop — it is that mode's entire feature.
        //
        // This used to blur for FILL too, which quietly defeated the edge fade: at zoom 1 a FILL
        // image covers the screen, so the backdrop was a blurred copy of the SAME picture in the
        // SAME place, and fading the edges into it changed almost nothing. Fading to the flat bar
        // colour (black by default) is what makes the image visibly melt into the screen.
        if(mode != FitSettings.MODE_BLUR || slot.sharp == null) {
            slot.backdrop.setImageDrawable(new ColorDrawable(
                    slot.fit != null ? slot.fit.barColor : Color.BLACK));
            return;
        }
        slot.backdrop.setImageBitmap(ImageBlur.blur(slot.sharp, slot.fit.blur));
        // Knock the backdrop back so the sharp image stays the subject and the seam where it
        // meets the blur does not read as a rendering fault.
        slot.backdrop.setColorFilter(0x59000000, android.graphics.PorterDuff.Mode.SRC_ATOP);
    }

    /** Overflow (scaled content size minus view size) in view pixels, per axis. */
    private float[] overflow(Slot slot) {
        float vw = getWidth(), vh = getHeight();
        if(slot.contentW <= 0 || slot.contentH <= 0 || vw <= 0 || vh <= 0) return new float[]{0f, 0f};
        FitSettings fit = slot.fit != null ? slot.fit : new FitSettings();
        float zoom = FitSettings.clampZoom(fit.zoom);
        // Must pick the same base scale — and the same rotated dimensions — applyImageMatrix()
        // did, or the pan disagrees with what is on screen: it used max unconditionally, so in a
        // fit mode it reported an overflow that offset() then ignored — the drag did nothing but
        // still wrote a focal point.
        int mode = resolvedMode(slot, (int) vw, (int) vh);
        float ew = fit.effectiveW(slot.contentW, slot.contentH);
        float eh = fit.effectiveH(slot.contentW, slot.contentH);
        float base = FitSettings.isFitMode(mode)
                ? Math.min(vw / ew, vh / eh)
                : Math.max(vw / ew, vh / eh);
        float scale = base * zoom;
        return new float[]{ ew * scale - vw, eh * scale - vh };
    }

    private void reapply(Slot slot) {
        if(slot.item == null) return;
        if(slot.item.isVideo()) {
            if(slot.player != null) applyVideoScale(slot, slot.player.getVideoWidth(), slot.player.getVideoHeight());
        } else {
            applyImageMatrix(slot);
        }
    }

    /** Pan the front wallpaper by a finger delta (pixels); clamps to the image edges. */
    public void panFront(float dxPx, float dyPx) {
        Slot s = mFront;
        if(s == null || s.item == null) return;
        float[] o = overflow(s);
        if(o[0] > 0) s.focalX = clamp01(s.focalX - dxPx / o[0]);
        if(o[1] > 0) s.focalY = clamp01(s.focalY - dyPx / o[1]);
        reapply(s);
    }

    /** Persist the front wallpaper's current position so it reopens the same way. */
    public void saveFrontFocal() {
        Slot s = mFront;
        if(s == null || s.item == null || mRepo == null) return;
        mRepo.setFocal(s.item.url, s.focalX, s.focalY);
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        // the fixed FSE size is applied after the view already exists — reposition both slots
        reapply(mSlotA);
        reapply(mSlotB);
    }

    private void startPlayer(final Slot slot, WallpaperItem item, Surface surface, final Runnable onReady) {
        try {
            final MediaPlayer mp = new MediaPlayer();
            slot.player = mp;
            mp.setSurface(surface);
            String local = (mRepo != null) ? mRepo.localVideoPath(item) : null;
            mp.setDataSource(local != null ? local : item.url);
            mp.setLooping(true);
            mp.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer p) {
                    if(slot.player != mp) { p.release(); return; }
                    try { p.setVolume(0f, 0f); } catch(Exception ignored) {}
                    applyVideoScale(slot, p.getVideoWidth(), p.getVideoHeight());
                    p.start();
                    if(onReady != null) onReady.run();
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer p, int what, int extra) {
                    // swallow, keep black background — but still release the crossfade so a
                    // broken clip does not leave the incoming slot invisible forever.
                    if(onReady != null) onReady.run();
                    return true;
                }
            });
            mp.prepareAsync();
        } catch(Exception ignored) {
            releaseVideo(slot);
            if(onReady != null) onReady.run();
        }
    }

    /**
     * Center-crop the video into the texture view, offset by the slot's focal point and turned
     * by the slot's rotation (a portrait-filmed clip is the reason rotation exists at all).
     *
     * A TextureView already stretches the stream to fill its own box, so the transform works in
     * view pixels: sx/sy undo that stretch back to the cover-scaled video, and the rotation then
     * happens about the centre of that box. At rotation 0 the maths is the plain centre-crop it
     * has always been.
     */
    private void applyVideoScale(Slot slot, int vw, int vh) {
        int tw = slot.texture.getWidth();
        int th = slot.texture.getHeight();
        if(vw <= 0 || vh <= 0 || tw <= 0 || th <= 0) return;
        slot.contentW = vw;
        slot.contentH = vh;

        FitSettings fit = slot.fit != null ? slot.fit : new FitSettings();
        int rot = FitSettings.clampRotation(fit.rotation);
        float ew = fit.effectiveW(vw, vh), eh = fit.effectiveH(vw, vh);

        float cover = Math.max(tw / ew, th / eh);
        float sx = (cover * vw) / tw;      // texture box is tw x th; sx*tw = scaled video width
        float sy = (cover * vh) / th;
        float bw = ew * cover, bh = eh * cover;   // on-screen size once rotated

        Matrix m = new Matrix();
        m.setScale(sx, sy);                       // scale about origin
        m.postRotate(rot, sx * tw / 2f, sy * th / 2f);
        m.postTranslate(offset(bw, tw, clamp01(slot.focalX)) + (bw - sx * tw) / 2f,
                        offset(bh, th, clamp01(slot.focalY)) + (bh - sy * th) / 2f);
        slot.texture.setTransform(m);
    }

    private void releaseVideo(Slot slot) {
        slot.pendingVideo = null;
        if(slot.player != null) {
            try { slot.player.setOnPreparedListener(null); } catch(Exception ignored) {}
            try { if(slot.player.isPlaying()) slot.player.stop(); } catch(Exception ignored) {}
            try { slot.player.reset(); } catch(Exception ignored) {}
            try { slot.player.release(); } catch(Exception ignored) {}
            slot.player = null;
        }
        slot.texture.setVisibility(GONE);
    }

    /**
     * Average brightness (0..255) of the current wallpaper inside the given fractional
     * rectangle (0..1). Returns -1 if it can't be sampled. Used to pick a contrasting clock color.
     */
    public int sampleLuminance(float fl, float ft, float fr, float fb) {
        int w = getWidth(), h = getHeight();
        if(w <= 0 || h <= 0) return -1;
        int sw = Math.max(8, w / 8), sh = Math.max(8, h / 8);
        Bitmap bmp = null;
        try {
            if(mFront.texture.getVisibility() == VISIBLE && mFront.player != null) {
                bmp = mFront.texture.getBitmap(sw, sh);
            } else {
                bmp = Bitmap.createBitmap(sw, sh, Bitmap.Config.ARGB_8888);
                Canvas c = new Canvas(bmp);
                c.scale((float) sw / w, (float) sh / h);
                mFront.root.draw(c);
            }
            if(bmp == null) return -1;
            int x0 = Math.max(0, (int) (fl * sw)), x1 = Math.min(sw, (int) (fr * sw));
            int y0 = Math.max(0, (int) (ft * sh)), y1 = Math.min(sh, (int) (fb * sh));
            if(x1 <= x0) x1 = x0 + 1;
            if(y1 <= y0) y1 = y0 + 1;
            long sum = 0; int n = 0;
            for(int y = y0; y < y1; y++) {
                for(int x = x0; x < x1; x++) {
                    int p = bmp.getPixel(x, y);
                    int lum = (int) (0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p));
                    sum += lum; n++;
                }
            }
            return n > 0 ? (int) (sum / n) : -1;
        } catch(Exception e) {
            return -1;
        } finally {
            if(bmp != null) bmp.recycle();
        }
    }

    public void pauseVideo() {
        try { if(mFront.player != null && mFront.player.isPlaying()) mFront.player.pause(); } catch(Exception ignored) {}
    }

    public void resumeVideo() {
        try { if(mFront.player != null) mFront.player.start(); } catch(Exception ignored) {}
    }
}
