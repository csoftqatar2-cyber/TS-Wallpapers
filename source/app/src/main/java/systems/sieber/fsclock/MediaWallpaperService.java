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
            loadMedia(width, height);
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

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sp, String key) {
            if(PREF_URI.equals(key)) {
                android.graphics.Rect frame = getSurfaceHolder().getSurfaceFrame();
                loadMedia(frame.width(), frame.height());
            }
        }

        // --- loading -------------------------------------------------------

        private void loadMedia(int targetW, int targetH) {
            release();
            if(targetW <= 0 || targetH <= 0) return;
            String uriStr = prefs.getString(PREF_URI, null);
            if(uriStr == null) return;
            Uri uri = Uri.parse(uriStr);

            String type = getContentResolver().getType(uri);
            type = type == null ? "" : type.toLowerCase();

            if(type.startsWith("video/")) {
                loadVideo(uri);
            } else if(type.equals("image/gif") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                loadGif(uri);
            } else {
                loadImage(uri, targetW, targetH);   // best-effort fallback too
            }
        }

        private void loadImage(Uri uri, int targetW, int targetH) {
            try {
                // Pass 1: bounds only, so we can downsample big photos.
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                InputStream in = getContentResolver().openInputStream(uri);
                if(in != null) { BitmapFactory.decodeStream(in, null, bounds); in.close(); }

                int sample = 1;
                while(bounds.outWidth / (sample * 2) >= targetW
                        && bounds.outHeight / (sample * 2) >= targetH) sample *= 2;

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = sample;
                in = getContentResolver().openInputStream(uri);
                if(in != null) { bitmap = BitmapFactory.decodeStream(in, null, opts); in.close(); }

                mode = Mode.IMAGE;
                drawImageOrGif();
            } catch(Exception e) {
                mode = Mode.NONE;
            }
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
                    drawImageOrGif();
                }
            } catch(Exception e) {
                mode = Mode.NONE;
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
            } catch(Exception e) {
                mode = Mode.NONE;
            }
        }

        // --- drawing (image + gif) ----------------------------------------

        private void drawImageOrGif() {
            if(mode != Mode.IMAGE && mode != Mode.GIF) return;
            SurfaceHolder holder = getSurfaceHolder();
            Canvas canvas = null;
            try {
                canvas = holder.lockCanvas();
                if(canvas == null) return;
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
            } catch(Exception ignored) {
            } finally {
                if(canvas != null) {
                    try { holder.unlockCanvasAndPost(canvas); } catch(Exception ignored) {}
                }
            }
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
            mode = Mode.NONE;
        }
    }
}
