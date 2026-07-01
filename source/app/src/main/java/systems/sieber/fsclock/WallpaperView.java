package systems.sieber.fsclock;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.util.AttributeSet;
import java.io.File;
import android.view.Surface;
import android.view.TextureView;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.Glide;

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

    private static final int DURATION = 480;

    private class Slot {
        FrameLayout root;
        ImageView image;
        TextureView texture;
        MediaPlayer player;
        WallpaperItem pendingVideo; // set while waiting for the surface to become available
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
                    s.pendingVideo = null;
                    startPlayer(s, item, new Surface(surface));
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

        s.image = new ImageView(c);
        s.image.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        s.image.setScaleType(ImageView.ScaleType.CENTER_CROP);
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
    }

    /**
     * Show the given item.
     * @param direction +1 = new content enters from the right (next),
     *                  -1 = enters from the left (previous), 0 = no animation.
     */
    public void showItem(WallpaperItem item, int direction) {
        if(item == null) { clearAll(); return; }

        if(direction == 0 || getWidth() == 0) {
            populate(mFront, item);
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
        populate(incoming, item);

        int w = getWidth();
        incoming.root.setVisibility(VISIBLE);
        incoming.root.setTranslationX(direction * w);
        incoming.root.setRotationY(-direction * 18);
        incoming.root.setAlpha(0.35f);
        incoming.root.animate()
                .translationX(0).rotationY(0).alpha(1f)
                .setDuration(DURATION)
                .setInterpolator(new DecelerateInterpolator())
                .start();

        outgoing.root.animate()
                .translationX(-direction * w * 0.6f).rotationY(direction * 18).alpha(0.2f)
                .setDuration(DURATION)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        outgoing.root.setVisibility(GONE);
                        outgoing.root.setTranslationX(0);
                        outgoing.root.setRotationY(0);
                        outgoing.root.setAlpha(1f);
                        releaseVideo(outgoing);
                        outgoing.image.setImageDrawable(null);
                    }
                })
                .start();

        mFront = incoming;
    }

    private void populate(Slot slot, WallpaperItem item) {
        releaseVideo(slot);
        if(item.isVideo()) {
            slot.image.setImageDrawable(null);
            slot.texture.setVisibility(VISIBLE);
            if(slot.texture.isAvailable() && slot.texture.getSurfaceTexture() != null) {
                startPlayer(slot, item, new Surface(slot.texture.getSurfaceTexture()));
            } else {
                slot.pendingVideo = item; // started in onSurfaceTextureAvailable
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
            // Glide animates GIFs automatically and handles network + disk caching
            Glide.with(getContext().getApplicationContext())
                    .load(model)
                    .centerCrop()
                    .into(slot.image);
        }
    }

    private void startPlayer(final Slot slot, WallpaperItem item, Surface surface) {
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
                }
            });
            mp.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer p, int what, int extra) {
                    return true; // swallow, keep black background
                }
            });
            mp.prepareAsync();
        } catch(Exception ignored) {
            releaseVideo(slot);
        }
    }

    /** Center-crop the video into the texture view. */
    private void applyVideoScale(Slot slot, int vw, int vh) {
        int tw = slot.texture.getWidth();
        int th = slot.texture.getHeight();
        if(vw <= 0 || vh <= 0 || tw <= 0 || th <= 0) return;
        float maxScale = Math.max((float) tw / vw, (float) th / vh);
        float sx = (maxScale * vw) / tw;
        float sy = (maxScale * vh) / th;
        Matrix m = new Matrix();
        m.setScale(sx, sy, tw / 2f, th / 2f);
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
