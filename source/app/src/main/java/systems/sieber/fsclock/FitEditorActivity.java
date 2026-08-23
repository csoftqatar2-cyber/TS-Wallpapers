package systems.sieber.fsclock;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.io.File;

/**
 * Per-image fit editor: choose how one wallpaper sits on the very wide screen.
 *
 * Auto-detection has usually already picked the right mode by the time this opens, so the
 * happy path is "look at it, press Done". There is no Save — the whole app is auto-save, and
 * Done only closes.
 */
public class FitEditorActivity extends AppCompatActivity {

    public static final String EXTRA_URL = "url";
    public static final String EXTRA_TARGET_W = "target_w";
    public static final String EXTRA_TARGET_H = "target_h";

    /**
     * Result extra: true only when the user left through the Done button.
     *
     * Back and Done both save — there is no Cancel in this app — but they do not mean the same
     * thing. Done is "this is the picture I want", and the caller answers it by showing the
     * picture in place: the clock screen in Normal, the applied wallpaper in Leopard/Lynk &amp; Co.
     * Back is "I am finished looking at this screen" and must not move anybody anywhere.
     */
    public static final String EXTRA_DONE = "done_pressed";

    /** Longest edge we decode to. A 10MP phone photo at full size is pointless here and slow. */
    private static final int MAX_DECODE = 1600;

    private WallpaperRepo mRepo;
    private String mUrl;
    private FitSettings mFit;
    private boolean mIsVideo;
    private int mTargetW = 1920, mTargetH = 720;

    private FitPreviewView mPreview;
    private SeekBar mSeekBlur, mSeekZoom, mSeekFade, mSeekHue;
    private TextView mBlurValue, mZoomValue, mFadeValue, mHint, mTarget, mBlurNote, mRotateValue;
    private View mPanelBlur, mPanelColor, mPanelFill;
    private LinearLayout mTileFill, mTileBlur, mTileColor, mPresets;
    private ProgressBar mProgress;
    private Bitmap mBitmap;

    private final Handler mMain = new Handler(Looper.getMainLooper());

    public static Intent intent(Context ctx, String url, int targetW, int targetH) {
        Intent i = new Intent(ctx, FitEditorActivity.class);
        i.putExtra(EXTRA_URL, url);
        i.putExtra(EXTRA_TARGET_W, targetW);
        i.putExtra(EXTRA_TARGET_H, targetH);
        return i;
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle b) {
        EdgeToEdge.enable(this);
        super.onCreate(b);
        setContentView(R.layout.activity_fit_editor);
        applyWindowInsets();

        mRepo = new WallpaperRepo(this);
        mUrl = getIntent().getStringExtra(EXTRA_URL);
        mTargetW = getIntent().getIntExtra(EXTRA_TARGET_W, 1920);
        mTargetH = getIntent().getIntExtra(EXTRA_TARGET_H, 720);
        mIsVideo = WallpaperItem.TYPE_VIDEO.equals(WallpaperItem.guessType(mUrl));
        mFit = mRepo.getFit(mUrl);

        mPreview = findViewById(R.id.fitPreview);
        mSeekBlur = findViewById(R.id.seekBarBlur);
        mSeekZoom = findViewById(R.id.seekBarZoom);
        mSeekFade = findViewById(R.id.seekBarFade);
        mSeekHue = findViewById(R.id.seekBarHue);
        mBlurValue = findViewById(R.id.textViewBlurValue);
        mZoomValue = findViewById(R.id.textViewZoomValue);
        mFadeValue = findViewById(R.id.textViewFadeValue);
        mBlurNote = findViewById(R.id.textViewBlurNote);
        mRotateValue = findViewById(R.id.textViewFitRotate);
        mHint = findViewById(R.id.textViewFitHint);
        mTarget = findViewById(R.id.textViewFitTarget);
        mPanelBlur = findViewById(R.id.panelBlur);
        mPanelColor = findViewById(R.id.panelColor);
        mPanelFill = findViewById(R.id.panelFill);
        mTileFill = findViewById(R.id.tileFill);
        mTileBlur = findViewById(R.id.tileBlur);
        mTileColor = findViewById(R.id.tileColor);
        mPresets = findViewById(R.id.colorPresets);
        mProgress = findViewById(R.id.progressFit);

        mPreview.setTargetSize(mTargetW, mTargetH);
        mTarget.setText(getString(R.string.fit_target, mTargetW, mTargetH));
        // The badge reports the saved angle from the moment the screen opens — syncUi() only
        // runs once the bitmap has decoded, and an empty button until then reads as broken.
        updateRotateLabel();

        float[] focal = mRepo.getFocal(mUrl);
        mPreview.setFocal(focal[0], focal[1]);
        mPreview.setOnFocalChangeListener((fx, fy) -> mRepo.setFocal(mUrl, fx, fy));

        // Pinch on the preview is the same zoom the slider drives, so the slider has to move with
        // it — a control that disagrees with the picture reads as broken. setProgress fires the
        // listener with fromUser=false, which updates the label and skips the redraw the gesture
        // has already done. Saving waits for the fingers to lift.
        mPreview.setOnZoomChangeListener((zoom, settled) -> {
            mSeekZoom.setProgress(progressOfZoom(zoom));
            if(settled) applyAndSave();
        });

        mTileFill.setOnClickListener(v -> pickMode(FitSettings.MODE_FILL));
        mTileBlur.setOnClickListener(v -> pickMode(FitSettings.MODE_BLUR));
        mTileColor.setOnClickListener(v -> pickMode(FitSettings.MODE_COLOR));

        mSeekBlur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                mFit.blur = value;
                mBlurValue.setText(String.valueOf(value));
                if(fromUser) scheduleBlurApply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { applyAndSave(); }
        });

        // Zoom is a factor, but a factor is not a thing anyone has an opinion about — the slider
        // is in percent and the maths happens here. 0 => 50%, 450 => 500%.
        mSeekZoom.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                mFit.zoom = zoomOf(value);
                mZoomValue.setText(getString(R.string.fit_percent, Math.round(mFit.zoom * 100)));
                if(!fromUser) return;
                mPreview.setFit(mFit);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { applyAndSave(); }
        });

        mSeekFade.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                mFit.fade = value;
                mFadeValue.setText(getString(R.string.fit_percent, value));
                if(!fromUser) return;
                mPreview.setFit(mFit);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { applyAndSave(); }
        });

        mSeekHue.setProgressDrawable(new HueTrackDrawable(getResources().getDisplayMetrics()));
        mSeekHue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                if(!fromUser) return;
                mFit.barColor = HueTrackDrawable.colorAt(value);
                mFit.mode = FitSettings.MODE_COLOR;
                mPreview.setFit(mFit);
                refreshPresetSelection();
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) { applyAndSave(); }
        });

        findViewById(R.id.buttonFitRotate).setOnClickListener(v -> rotate());
        findViewById(R.id.buttonFitDone).setOnClickListener(v -> done());
        findViewById(R.id.buttonFitReset).setOnClickListener(v -> reset());
        findViewById(R.id.buttonFitDelete).setOnClickListener(v -> confirmDelete());
        findViewById(R.id.buttonFitApplyAll).setOnClickListener(v -> confirmApplyAll());
        findViewById(R.id.buttonFitHelp).setOnClickListener(v -> showHelp());
        findViewById(R.id.repositionPad).setOnClickListener(v ->
                Toast.makeText(this, R.string.fit_reposition_hint, Toast.LENGTH_SHORT).show());

        buildPresets();
        loadImage();
    }

    /**
     * What this screen is and how to work it.
     *
     * Everything here is discoverable only by trying it: pinching zooms, dragging repositions,
     * the three tiles decide what fills the space a picture does not cover. A technician meeting
     * the screen for the first time, in a car, with a customer waiting, has no reason to guess
     * any of it — and the screen itself has no room to say it permanently, because it must stay
     * one unscrollable page (see the note at the top of activity_fit_editor.xml). A dialog is the
     * only place the explanation can live without costing the preview its height.
     */
    private void showHelp() {
        DialogButtons.apply(new AlertDialog.Builder(this)
                .setTitle(R.string.fit_help_title)
                .setMessage(R.string.fit_help_body)
                .setPositiveButton(R.string.ok, null)
                .show());
    }

    // The slider is 0..450 and the zoom is 0.5..5.0 — one step is one percent either way.
    // (android:max on seekBarZoom must stay (ZOOM_MAX - ZOOM_MIN) * 100.)
    private static float zoomOf(int progress) {
        return FitSettings.clampZoom((progress + 50) / 100f);
    }

    private static int progressOfZoom(float zoom) {
        return Math.round(FitSettings.clampZoom(zoom) * 100f) - 50;
    }

    /**
     * Keep the controls out from under the system bars.
     *
     * targetSdk 36 means Android 15 lays this activity out edge to edge whether it asks to or
     * not, so without this the bottom row — the mode tiles, the sliders and Done — renders
     * underneath the navigation bar. It looked like a layout that was too tall; it was a layout
     * that was the right height in the wrong place. Settings already does exactly this; the two
     * screens added later never got it.
     */
    private void applyWindowInsets() {
        final View root = findViewById(R.id.fitEditorRoot);
        final int baseL = root.getPaddingLeft(), baseT = root.getPaddingTop();
        final int baseR = root.getPaddingRight(), baseB = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets in = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(baseL + in.left, baseT + in.top, baseR + in.right, baseB + in.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    // ---------------------------------------------------------------- image

    private void loadImage() {
        mProgress.setVisibility(View.VISIBLE);
        new Thread(() -> {
            final Bitmap bmp = decode(mUrl);
            mMain.post(() -> {
                mProgress.setVisibility(View.GONE);
                if(isFinishing()) return;
                if(bmp == null) {
                    Toast.makeText(this, R.string.fit_load_failed, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                mBitmap = bmp;
                mPreview.setImage(bmp, mIsVideo);
                syncUi();
            });
        }).start();
    }

    /**
     * Decode downsampled — the editor never needs more than the screen can show.
     *
     * EXIF is applied on the way out, because the wallpaper screen and the thumbnails load the
     * same file through Glide, which applies it. Without that the editor previews a phone photo
     * turned differently from the way it is about to be shown, and every rotation the technician
     * makes here is measured against the wrong picture — see {@link ExifOrientation}.
     */
    private Bitmap decode(String url) {
        try {
            String path = url;
            if(path != null && path.startsWith("file://")) path = path.substring("file://".length());
            File f = new File(path);
            if(!f.exists()) return null;

            // A clip has no bitmap to decode, and BitmapFactory answers null for one — which this
            // screen read as "the file is broken", showed fit_load_failed and closed itself. So a
            // video sent from a phone reached the car and then bounced off the framing screen,
            // even though everything after it (the slideshow, the wallpaper engine, the Leopard
            // hand-off) plays video perfectly well. Its first frame is the picture to frame.
            if(mIsVideo) return videoFrame(f);

            BitmapFactory.Options probe = new BitmapFactory.Options();
            probe.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), probe);
            int longest = Math.max(probe.outWidth, probe.outHeight);
            int sample = 1;
            while(longest / sample > MAX_DECODE) sample *= 2;

            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            return ExifOrientation.apply(
                    BitmapFactory.decodeFile(f.getAbsolutePath(), o), f.getAbsolutePath());
        } catch(Throwable t) {
            return null;
        }
    }

    /**
     * A representative frame of a clip, or null if nothing can be pulled out of it.
     *
     * One second in rather than zero: the first frame of a phone recording is very often black
     * or a blur, and framing a wallpaper against black is framing it blind. OPTION_CLOSEST_SYNC
     * keeps it cheap — the nearest keyframe is good enough to position a picture by, and asking
     * for an exact frame makes a head unit chew through the file.
     */
    private Bitmap videoFrame(File f) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            Bitmap bmp = r.getFrameAtTime(1_000_000L,
                    android.media.MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            // A clip shorter than a second has no frame out there; fall back on whatever it has.
            if(bmp == null) bmp = r.getFrameAtTime();
            return bmp;
        } catch(Throwable t) {
            return null;
        } finally {
            try { r.release(); } catch(Throwable ignored) { }
        }
    }

    // ---------------------------------------------------------------- ui state

    private void pickMode(int mode) {
        // Blur has no meaning for a video: per-frame blur is far past this hardware's budget.
        if(mode == FitSettings.MODE_BLUR && mIsVideo) {
            Toast.makeText(this, R.string.fit_blur_unavailable_video, Toast.LENGTH_SHORT).show();
            return;
        }
        mFit.mode = mode;
        applyAndSave();
    }

    /**
     * One tap = one quarter turn clockwise, applied and saved straight away.
     *
     * The pan is deliberately reset with it: the focal point says "show me THIS part of the
     * frame", and after a quarter turn that part is somewhere else entirely — keeping the old
     * value would jump the picture to a crop nobody chose. From centre, one drag puts it right.
     */
    private void updateRotateLabel() {
        mRotateValue.setText(getString(R.string.fit_rotate_degrees,
                FitSettings.clampRotation(mFit.rotation)));
    }

    private void rotate() {
        mFit.rotation = FitSettings.nextRotation(mFit.rotation);
        mRepo.setFocal(mUrl, 0.5f, 0.5f);
        mPreview.setFocal(0.5f, 0.5f);
        applyAndSave();
    }

    private void applyAndSave() {
        mPreview.setFit(mFit);
        save();
        syncUi();
    }

    private void save() {
        mRepo.setFit(mUrl, mFit);
        setResult(RESULT_OK);
    }

    /**
     * Leave through Done — the end of the import, not just the end of this screen.
     *
     * Everything is already on disk by now, so this saves nothing new; what it adds is the flag
     * the caller needs to tell a deliberate "yes, this one" from a back press, and to take the
     * user to the picture where it now lives. setResult runs after save() on purpose: save()
     * sets a bare RESULT_OK and would drop the extra if it came second.
     */
    private void done() {
        save();
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_DONE, true));
        finish();
    }

    private Runnable mPendingBlur;

    /** The blur is cheap but not free; let the slider settle rather than blurring every pixel
     *  of travel. ~90ms reads as a fast follow, not as a stall. */
    private void scheduleBlurApply() {
        if(mPendingBlur != null) mMain.removeCallbacks(mPendingBlur);
        mPendingBlur = () -> { mPreview.setFit(mFit); save(); };
        mMain.postDelayed(mPendingBlur, 90);
    }

    private void syncUi() {
        int resolved = mBitmap == null ? mFit.mode
                : mFit.resolved(mBitmap.getWidth(), mBitmap.getHeight(), mTargetW, mTargetH);

        mTileFill.setSelected(resolved == FitSettings.MODE_FILL);
        mTileBlur.setSelected(resolved == FitSettings.MODE_BLUR);
        mTileColor.setSelected(resolved == FitSettings.MODE_COLOR);

        // A video simply cannot blur — say so by disabling, not by silently doing nothing.
        setTileEnabled(mTileBlur, !mIsVideo);

        // Exactly one panel: the parent brief calls "every sub-setting always visible" a defect.
        mPanelBlur.setVisibility(resolved == FitSettings.MODE_BLUR ? View.VISIBLE : View.GONE);
        mPanelColor.setVisibility(resolved == FitSettings.MODE_COLOR ? View.VISIBLE : View.GONE);
        mPanelFill.setVisibility(resolved == FitSettings.MODE_FILL ? View.VISIBLE : View.GONE);

        mSeekBlur.setProgress(mFit.blur);
        mBlurValue.setText(String.valueOf(mFit.blur));
        mSeekZoom.setProgress(progressOfZoom(mFit.zoom));
        mZoomValue.setText(getString(R.string.fit_percent, Math.round(FitSettings.clampZoom(mFit.zoom) * 100)));
        mSeekFade.setProgress(mFit.fade);
        mFadeValue.setText(getString(R.string.fit_percent, mFit.fade));
        mSeekHue.setProgress(HueTrackDrawable.hueOf(mFit.barColor));
        updateRotateLabel();

        if(resolved == FitSettings.MODE_BLUR) mHint.setText(R.string.fit_hint_blur);
        else if(resolved == FitSettings.MODE_COLOR) mHint.setText(R.string.fit_hint_color);
        else mHint.setText(R.string.fit_hint_fill);

        // Be honest when the slider cannot visibly do anything.
        if(resolved == FitSettings.MODE_BLUR && mPreview.barsAreNegligible()) {
            mBlurNote.setText(R.string.fit_hint_bars_negligible);
        } else {
            mBlurNote.setText(R.string.fit_dpad_hint);
        }

        refreshPresetSelection();
        mPreview.setFit(mFit);
    }

    private void setTileEnabled(LinearLayout tile, boolean enabled) {
        tile.setEnabled(enabled);
        tile.setFocusable(enabled);
        tile.setAlpha(enabled ? 1f : 0.4f);
        for(int i = 0; i < tile.getChildCount(); i++) tile.getChildAt(i).setEnabled(enabled);
    }

    // ---------------------------------------------------------------- colour

    private static final int[] PRESET_COLORS = { Color.BLACK, 0xFF2B2B2B, Color.WHITE };
    private static final int[] PRESET_LABELS = { R.string.fit_color_black, R.string.fit_color_grey, R.string.fit_color_white };

    private void buildPresets() {
        mPresets.removeAllViews();
        int size = Math.round(getResources().getDisplayMetrics().density * 34);
        int gap = Math.round(getResources().getDisplayMetrics().density * 8);

        for(int i = 0; i < PRESET_COLORS.length; i++) {
            mPresets.addView(makeSwatch(PRESET_COLORS[i], getString(PRESET_LABELS[i]), size, gap));
        }
        // A colour lifted from the image's own edges blends the bars into the photo, which is
        // what most people actually want and would never find in a colour picker.
        mPresets.addView(makeSwatch(0, getString(R.string.fit_color_sampled), size, gap));
    }

    private View makeSwatch(final int color, String label, int size, int gap) {
        ImageView v = new ImageView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(gap);
        v.setLayoutParams(lp);
        v.setFocusable(true);
        v.setClickable(true);
        v.setContentDescription(label);
        v.setTag(color);
        v.setOnClickListener(x -> {
            int c = color;
            if(c == 0) {   // the "from the image" swatch resolves lazily, once we have pixels
                c = sampleEdgeColor();
                v.setTag(c);
            }
            mFit.barColor = c;
            mFit.mode = FitSettings.MODE_COLOR;
            applyAndSave();
        });
        return v;
    }

    private void refreshPresetSelection() {
        for(int i = 0; i < mPresets.getChildCount(); i++) {
            View child = mPresets.getChildAt(i);
            int c = (Integer) child.getTag();
            int shown = c == 0 ? sampleEdgeColor() : c;
            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(shown);
            boolean on = shown == mFit.barColor && mFit.mode == FitSettings.MODE_COLOR;
            d.setStroke(Math.round(getResources().getDisplayMetrics().density * (on ? 3 : 1)),
                    on ? getColor2(R.color.gold) : getColor2(R.color.aurora_border));
            child.setBackground(d);
        }
    }

    private int getColor2(int res) {
        return androidx.core.content.ContextCompat.getColor(this, res);
    }

    /** Average the image's left and right edges — the bars then read as an extension of it. */
    private int sampleEdgeColor() {
        if(mBitmap == null) return Color.BLACK;
        int h = mBitmap.getHeight(), w = mBitmap.getWidth();
        long r = 0, g = 0, b = 0; int n = 0;
        int step = Math.max(1, h / 24);
        for(int y = 0; y < h; y += step) {
            for(int x : new int[]{0, w - 1}) {
                int c = mBitmap.getPixel(x, y);
                r += Color.red(c); g += Color.green(c); b += Color.blue(c); n++;
            }
        }
        if(n == 0) return Color.BLACK;
        return Color.rgb((int)(r / n), (int)(g / n), (int)(b / n));
    }

    // ---------------------------------------------------------------- actions

    private void reset() {
        mRepo.clearFit(mUrl);
        mFit = mRepo.getFitDefaults();
        mRepo.setFocal(mUrl, 0.5f, 0.5f);
        mPreview.setFocal(0.5f, 0.5f);
        applyAndSave();
    }

    private void confirmDelete() {
        DialogButtons.apply(new AlertDialog.Builder(this)
                .setMessage(R.string.fit_delete_confirm)
                .setPositiveButton(R.string.fit_delete, (d, w) -> {
                    String path = mUrl != null && mUrl.startsWith("file://")
                            ? mUrl.substring("file://".length()) : mUrl;
                    if(path != null) {
                        File f = new File(path);
                        if(f.exists()) //noinspection ResultOfMethodCallIgnored
                            f.delete();
                    }
                    mRepo.clearFit(mUrl);
                    setResult(RESULT_OK);
                    finish();
                })
                .setNegativeButton(R.string.update_cancel, null)
                .show());
    }

    private void confirmApplyAll() {
        DialogButtons.apply(new AlertDialog.Builder(this)
                .setMessage(R.string.fit_apply_all_confirm)
                .setPositiveButton(R.string.ok, (d, w) -> {
                    mRepo.applyFitToAll(mFit);
                    Toast.makeText(this, getString(R.string.fit_apply_all_done, mRepo.allItems().size()),
                            Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                })
                .setNegativeButton(R.string.update_cancel, null)
                .show());
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // A d-pad path for the pan, which the drag gesture alone would not give.
        if(mPanelFill.getVisibility() == View.VISIBLE && findViewById(R.id.repositionPad).hasFocus()) {
            float step = 0.04f;
            float fx = mPreview.getFocalX(), fy = mPreview.getFocalY();
            switch(keyCode) {
                case KeyEvent.KEYCODE_DPAD_LEFT:  fx -= step; break;
                case KeyEvent.KEYCODE_DPAD_RIGHT: fx += step; break;
                case KeyEvent.KEYCODE_DPAD_UP:    fy -= step; break;
                case KeyEvent.KEYCODE_DPAD_DOWN:  fy += step; break;
                default: return super.onKeyDown(keyCode, event);
            }
            mPreview.setFocal(fx, fy);
            mRepo.setFocal(mUrl, mPreview.getFocalX(), mPreview.getFocalY());
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        save();   // no cancel concept in this app: everything already persisted
        super.onBackPressed();
    }
}
