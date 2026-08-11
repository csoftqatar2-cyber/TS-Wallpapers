package systems.sieber.fsclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.drawable.AnimatedImageDrawable;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.service.wallpaper.WallpaperService;
import android.view.SurfaceHolder;

import java.io.InputStream;

/**
 * Live wallpaper that plays a user-picked image, GIF, or video, scaled center-crop to fill the
 * screen. No OpenGL and no native engine:
 *
 *  - image  -> decode to Bitmap, draw once on the surface Canvas.
 *  - gif    -> AnimatedImageDrawable, redrawn on the Canvas via its callback.
 *  - video  -> MediaPlayer rendering straight onto the wallpaper Surface.
 *
 * This is a faithful Java port of the proven MiniWallpaper reference engine
 * (ts - engine/MiniWallpaper/.../MediaWallpaperService.kt). Do not "improve" it before it has
 * been tested on a real Leopard car — the details below are the whole point of the reference:
 *
 *  - load from onSurfaceChanged, where the surface size is actually known;
 *  - take the type from contentResolver.getType(), not from a filename (a content:// URI has
 *    no extension to guess from);
 *  - VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING, or video stretches instead of cropping;
 *  - REPEAT_INFINITE + Drawable.Callback, or a GIF plays once and freezes;
 *  - two-pass decode, or a 10MP photo OOMs a cheap head unit.
 */
public class MediaWallpaperService extends WallpaperService {

    /** The chosen file. Written by LeopardApplier; the engine reloads when it changes. */
    static final String PREF_URI = "leopard-uri";

    /**
     * Bumped by {@link LeopardApplier#apply} on EVERY apply, and the reason a second wallpaper
     * ever reaches the screen.
     *
     * PREF_URI alone cannot carry that news. A still is copied to one fixed path
     * (leopard-current/current.jpg) so the engine has something durable to re-read at boot — which
     * means the string written here is byte-identical every time, and SharedPreferences does not
     * notify a listener for a value that did not change (SharedPreferencesImpl.commitToMemory
     * skips keys whose existing value equals the new one). So the engine was never told, kept the
     * bitmap it had decoded, and the car went on showing the FIRST picture ever applied: an edit
     * made in the fit editor previewed correctly and then landed as the untouched original.
     *
     * The same applies to re-applying a video or a cloud picture already on screen. A counter that
     * always changes is what makes "apply" mean apply.
     */
    static final String PREF_REV = "leopard-uri-rev";

    private enum Mode { NONE, IMAGE, GIF, VIDEO }

    @Override
    public Engine onCreateEngine() {
        return new MediaEngine();
    }

    private class MediaEngine extends Engine implements SharedPreferences.OnSharedPreferenceChangeListener {

        private final SharedPreferences prefs =
                getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        private final Handler handler = new Handler(Looper.getMainLooper());
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

        private Mode mode = Mode.NONE;
        private boolean visible = false;

        /** How many times the current still has asked for a surface that was not ready yet. */
        private int drawRetries;
        /** How many times the current still has failed to load at all. */
        private int loadRetries;

        // image / gif
        private Bitmap bitmap;
        private AnimatedImageDrawable gifDrawable;

        // video
        private MediaPlayer player;
        private boolean videoPrepared = false;

        private final Drawable.Callback gifCallback = new Drawable.Callback() {
            @Override public void invalidateDrawable(Drawable who) { drawImageOrGif(); }
            @Override public void scheduleDrawable(Drawable who, Runnable what, long whenAt) {
                handler.postAtTime(what, who, whenAt);
            }
            @Override public void unscheduleDrawable(Drawable who, Runnable what) {
                handler.removeCallbacks(what, who);
            }
        };

        @Override
        public void onCreate(SurfaceHolder surfaceHolder) {
            super.onCreate(surfaceHolder);
            prefs.registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // A brand new surface is a fresh chance for a still that failed on the last one.
            loadRetries = 0;
            loadMedia(width, height);
        }

        /**
         * The system asking for the picture back — and the reason a car used to come out of a
         * restart with the video intact and the photo black.
         *
         * A video never needs this hook: MediaPlayer keeps pushing frames at the Surface, so
         * whatever the window manager does to the buffers is repaired a few milliseconds later by
         * the next frame. A still is posted exactly once, and a wallpaper surface does not keep
         * what was posted to it — when its window is re-created (which is what happens while a
         * head unit boots and the launcher and the second display settle) the buffer comes back
         * blank, and blank on a wallpaper is a black screen with nothing left to redraw it.
         */
        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            ensureDrawn();
        }

        @Override
        public void onSurfaceCreated(SurfaceHolder holder) {
            super.onSurfaceCreated(holder);
            ensureDrawn();
        }

        @Override
        public void onOffsetsChanged(float xOffset, float yOffset, float xStep, float yStep,
                                     int xPixels, int yPixels) {
            // Some launchers re-lay-out the wallpaper window as they scroll their pages, which
            // costs a still its buffer exactly like a boot does.
            ensureDrawn();
        }

        /**
         * Put the picture back on the surface, loading it again first if it is no longer in
         * memory. Called from every hook that can mean "the buffer you drew into is gone".
         *
         * Deliberately a no-op for video: the player owns that surface and repairs it itself.
         */
        private void ensureDrawn() {
            if(mode == Mode.VIDEO) return;
            if((mode == Mode.IMAGE && bitmap != null) || (mode == Mode.GIF && gifDrawable != null)) {
                drawImageOrGif();
                return;
            }
            scheduleReload();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            this.visible = visible;
            switch(mode) {
                case VIDEO:
                    if(visible) {
                        if(videoPrepared && player != null) player.start();
                    } else if(player != null) {
                        try { if(player.isPlaying()) player.pause(); } catch(IllegalStateException ignored) {}
                    }
                    break;
                case GIF:
                    if(gifDrawable != null) { if(visible) gifDrawable.start(); else gifDrawable.stop(); }
                    break;
                case IMAGE:
                    if(visible) drawImageOrGif();
                    break;
                default:
                    break;
            }
        }

        @Override
        public void onSurfaceDestroyed(SurfaceHolder holder) {
            visible = false;
            release();
        }

        @Override
        public void onDestroy() {
            super.onDestroy();
            prefs.unregisterOnSharedPreferenceChangeListener(this);
            release();
        }

        /** Re-read whatever is stored now. Posted rather than run inline, so one apply that
         *  writes both the uri and the revision reloads once instead of twice. */
        private final Runnable reload = new Runnable() {
            @Override public void run() {
                android.graphics.Rect frame = getSurfaceHolder().getSurfaceFrame();
                loadMedia(frame.width(), frame.height());
            }
        };

        /** One more go at putting the still on the surface. See {@link #repaintLater}. */
        private final Runnable repaint = new Runnable() {
            @Override public void run() { drawImageOrGif(); }
        };

        /**
         * Try the whole load again shortly, a few times, then stop.
         *
         * The failures this exists for are all temporary — a surface with no size yet, a file the
         * car is a moment away from being able to read, memory that is about to free up as the
         * boot finishes. Giving up on the first one is what leaves a black screen behind.
         */
        private void scheduleReload() {
            if(loadRetries >= 3) return;
            loadRetries++;
            handler.removeCallbacks(reload);
            handler.postDelayed(reload, 1500L * loadRetries);
        }

        /**
         * Draw the still again a few times over the next few seconds.
         *
         * At boot the wallpaper window is created, measured and re-created while the launcher and
         * the second display settle, and each of those throws away the single buffer a still ever
         * posts. These are three blits of a bitmap that is already decoded and already in memory —
         * next to nothing — bought against the one failure that actually reached owners: a car
         * that comes back from a restart with a black home screen.
         */
        private void repaintLater() {
            handler.postDelayed(repaint, 500);
            handler.postDelayed(repaint, 2000);
            handler.postDelayed(repaint, 6000);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if(PREF_URI.equals(key) || PREF_REV.equals(key)) {
                handler.removeCallbacks(reload);
                handler.post(reload);
            }
        }

        // --- loading -------------------------------------------------------

        /**
         * Nothing thrown in here may reach the system callback that called us.
         *
         * The catch is on Throwable and not on Exception on purpose: the one failure a cheap head
         * unit actually produces while it is booting is OutOfMemoryError, which is an Error, and
         * it was therefore sailing straight past every catch(Exception) below and out of
         * onSurfaceChanged — killing the engine on a surface that had never been drawn on.
         */
        private void loadMedia(int targetW, int targetH) {
            try {
                loadMediaLocked(targetW, targetH);
            } catch(Throwable t) {
                CrashReporter.breadcrumb("wallpaper engine: load failed — " + t);
                mode = Mode.NONE;
                scheduleReload();
            }
        }

        private void loadMediaLocked(int targetW, int targetH) {
            release();
            if(targetW <= 0 || targetH <= 0) {
                // No surface to measure against yet; there will be one in a moment.
                scheduleReload();
                return;
            }
            String uriStr = prefs.getString(PREF_URI, null);
            if(uriStr == null) return;
            Uri uri = normalise(uriStr);

            String type = getContentResolver().getType(uri);
            type = type == null ? "" : type.toLowerCase();

            boolean video, gif;
            if(type.isEmpty()) {
                // The resolver has nothing to say about a plain file. Fall back to the extension
                // rather than treating it as an image by default — that is how a local video used
                // to come up as a black screen.
                String guess = WallpaperItem.guessType(uriStr);
                video = WallpaperItem.TYPE_VIDEO.equals(guess);
                gif = WallpaperItem.TYPE_GIF.equals(guess);
            } else {
                video = type.startsWith("video/");
                gif = type.equals("image/gif");
            }

            if(video) {
                loadVideo(uri);
            } else if(gif && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                loadGif(uri);
            } else {
                loadImage(uri, targetW, targetH);   // best-effort fallback too
            }
        }

        /**
         * A URI the ContentResolver will actually answer for.
         *
         * The still path stores what it was handed, and a file on the head unit reaches it as a
         * bare "/data/…/edit_1a2b.jpg" — no scheme, so {@code getType} returns null and
         * {@code openInputStream} throws "Unknown URI". Every load then failed silently and the
         * engine drew nothing, which on a wallpaper surface is a black screen: exactly what a
         * Leopard car showed after a restart put this service back on top of the picture. The
         * same path spelled file:// answers both questions.
         */
        private Uri normalise(String s) {
            if(s.startsWith("content://") || s.startsWith("file://")
                    || s.startsWith("android.resource://") || s.startsWith("http")) {
                return Uri.parse(s);
            }
            return Uri.fromFile(new java.io.File(s));
        }

        private void loadImage(Uri uri, int targetW, int targetH) {
            mode = Mode.IMAGE;
            bitmap = decodeScaled(uri, targetW, targetH);
            if(bitmap == null) {
                // The one failure that looks like nothing: no exception, no picture, and a
                // wallpaper surface that was never drawn on is black. Say so, so the next
                // crash report from this car carries the reason rather than the symptom.
                CrashReporter.breadcrumb("wallpaper engine: could not decode " + uri);
                scheduleReload();
                return;
            }
            loadRetries = 0;
            drawImageOrGif();
            repaintLater();
        }

        /**
         * Decode small enough to survive a head unit, and keep trying smaller if it does not.
         *
         * The old two-pass decode only halved while BOTH sides still covered the screen, which on
         * the 5120x1600 driver display means a portrait photo is never sampled down at all: a 12MP
         * picture arrives as a 48MB ARGB_8888 bitmap. That is affordable when the owner is standing
         * in the app and nothing else is starting. At boot, with the whole car coming up at once,
         * it is the difference between a wallpaper and an OutOfMemoryError.
         *
         * Two things follow from that. A pixel budget, because everything past roughly one and a
         * half screens of them is thrown away by the center-crop anyway and is memory spent on
         * nothing. And RGB_565, which halves what is left and costs nothing visible: an opaque
         * photo drawn onto an opaque surface has no alpha worth keeping.
         *
         * @return the decoded bitmap, or null when even the smallest attempt failed.
         */
        private Bitmap decodeScaled(Uri uri, int targetW, int targetH) {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            InputStream in = null;
            try {
                in = getContentResolver().openInputStream(uri);
                if(in != null) BitmapFactory.decodeStream(in, null, bounds);
            } catch(Throwable t) {
                CrashReporter.breadcrumb("wallpaper engine: cannot read " + uri + " — " + t);
                return null;
            } finally {
                if(in != null) try { in.close(); } catch(Exception ignored) {}
                in = null;
            }
            if(bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            int sample = 1;
            while(bounds.outWidth / (sample * 2) >= targetW
                    && bounds.outHeight / (sample * 2) >= targetH) sample *= 2;
            long budget = (long) targetW * targetH * 3L / 2L;
            while((long) (bounds.outWidth / sample) * (bounds.outHeight / sample) > budget) sample *= 2;

            for(int attempt = 0; attempt < 4; attempt++, sample *= 2) {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                opts.inPreferredConfig = Bitmap.Config.RGB_565;
                try {
                    in = getContentResolver().openInputStream(uri);
                    if(in == null) return null;
                    Bitmap bmp = BitmapFactory.decodeStream(in, null, opts);
                    if(bmp != null) return bmp;
                } catch(Throwable t) {
                    // Out of memory at this size. Half it and try again rather than hand the car
                    // a black screen — a slightly softer wallpaper is not a failure.
                    CrashReporter.breadcrumb(
                            "wallpaper engine: decode at 1/" + sample + " failed — " + t);
                } finally {
                    if(in != null) try { in.close(); } catch(Exception ignored) {}
                    in = null;
                }
            }
            return null;
        }

        private void loadGif(Uri uri) {
            try {
                ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
                Drawable drawable = ImageDecoder.decodeDrawable(source);
                if(drawable instanceof AnimatedImageDrawable) {
                    AnimatedImageDrawable d = (AnimatedImageDrawable) drawable;
                    d.setRepeatCount(AnimatedImageDrawable.REPEAT_INFINITE);
                    d.setCallback(gifCallback);
                    gifDrawable = d;
                    mode = Mode.GIF;
                    loadRetries = 0;
                    if(visible) d.start();
                    drawImageOrGif();
                } else {
                    // Not actually animated: draw its first frame as a static image.
                    Bitmap bmp = Bitmap.createBitmap(
                            Math.max(1, drawable.getIntrinsicWidth()),
                            Math.max(1, drawable.getIntrinsicHeight()),
                            Bitmap.Config.ARGB_8888);
                    drawable.setBounds(0, 0, bmp.getWidth(), bmp.getHeight());
                    drawable.draw(new Canvas(bmp));
                    bitmap = bmp;
                    mode = Mode.IMAGE;
                    loadRetries = 0;
                    drawImageOrGif();
                    // A still, whatever container it arrived in, needs the same insurance.
                    repaintLater();
                }
            } catch(Throwable t) {
                CrashReporter.breadcrumb("wallpaper engine: gif failed " + uri + " — " + t);
                mode = Mode.NONE;
                scheduleReload();
            }
        }

        private void loadVideo(Uri uri) {
            try {
                videoPrepared = false;
                final MediaPlayer mp = new MediaPlayer();
                mp.setDataSource(getApplicationContext(), uri);
                mp.setLooping(true);
                mp.setVolume(0f, 0f);   // wallpapers are silent
                mp.setSurface(getSurfaceHolder().getSurface());
                mp.setOnPreparedListener(p -> {
                    videoPrepared = true;
                    try {
                        p.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING);
                    } catch(Exception ignored) { }
                    if(visible) p.start();
                });
                mp.prepareAsync();
                player = mp;
                mode = Mode.VIDEO;
                loadRetries = 0;
            } catch(Throwable t) {
                CrashReporter.breadcrumb("wallpaper engine: video failed " + uri + " — " + t);
                mode = Mode.NONE;
                scheduleReload();
            }
        }

        // --- drawing (image + gif) ----------------------------------------

        private void drawImageOrGif() {
            if(mode != Mode.IMAGE && mode != Mode.GIF) return;
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if(canvas == null) { retryDraw(); return; }
                drawRetries = 0;
                canvas.drawColor(Color.BLACK);
                float vw = canvas.getWidth();
                float vh = canvas.getHeight();

                if(bitmap != null) {
                    float scale = Math.max(vw / bitmap.getWidth(), vh / bitmap.getHeight());
                    Matrix m = new Matrix();
                    m.setScale(scale, scale);
                    m.postTranslate((vw - bitmap.getWidth() * scale) / 2f,
                            (vh - bitmap.getHeight() * scale) / 2f);
                    canvas.drawBitmap(bitmap, m, paint);
                }
                if(gifDrawable != null) {
                    int iw = Math.max(1, gifDrawable.getIntrinsicWidth());
                    int ih = Math.max(1, gifDrawable.getIntrinsicHeight());
                    float scale = Math.max(vw / iw, vh / ih);
                    int w = (int) (iw * scale);
                    int h = (int) (ih * scale);
                    int left = (int) ((vw - w) / 2f);
                    int top = (int) ((vh - h) / 2f);
                    gifDrawable.setBounds(left, top, left + w, top + h);
                    gifDrawable.draw(canvas);
                }
            } catch(Throwable t) {
                retryDraw();
            } finally {
                if(canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas); } catch(Exception ignored) {}
                }
            }
        }

        /**
         * The surface was not ready to be drawn on. It usually is a frame or two later.
         *
         * A video would not need this — the player simply pushes its next frame — but a still that
         * loses its one draw has nothing else coming, so the retries are the difference between a
         * wallpaper and a black screen. Bounded: if the surface is still refusing after five
         * goes it is gone for good, and the next real lifecycle callback will load again anyway.
         */
        private void retryDraw() {
            if(drawRetries >= 5) return;
            drawRetries++;
            handler.postDelayed(repaint, 150L * drawRetries);
        }

        // --- teardown ------------------------------------------------------

        private void release() {
            handler.removeCallbacksAndMessages(null);
            if(gifDrawable != null) {
                gifDrawable.stop();
                gifDrawable.setCallback(null);
                gifDrawable = null;
            }
            if(bitmap != null) {
                bitmap.recycle();
                bitmap = null;
            }
            if(player != null) {
                try { if(player.isPlaying()) player.stop(); } catch(Exception ignored) {}
                player.release();
                player = null;
            }
            videoPrepared = false;
            drawRetries = 0;
            mode = Mode.NONE;
        }
    }
}
