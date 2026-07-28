package systems.sieber.fsclock;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Leopard's wallpaper picker.
 *
 * The app has nothing else to show in this mode — there is no clock screen behind it — so this
 * IS the app. Open it, choose, close.
 */
public class LeopardPickerActivity extends AppCompatActivity {

    private static final int PICK_LOCAL_REQUEST = 41;
    private static final String PREF_DEFAULT_SOURCE = "leopard-default-source";
    private static final String SOURCE_CLOUD = "cloud";
    private static final String SOURCE_LOCAL = "local";
    private static final String SOURCE_PHONE = "phone";

    private WallpaperRepo mRepo;
    private SharedPreferences mPrefs;

    private LinearLayout mFilmstrip;
    private View mSourceCloud, mSourceLocal, mSourcePhone, mStateBox, mSetButton;
    private UploadServer mUploadServer;
    private TextView mStateText, mStateAction, mStatus;
    private ProgressBar mProgress;

    private View mPreview;
    private ImageView mPreviewImage;
    private TextView mPreviewBadge;
    private ProgressBar mPreviewProgress;
    private VideoView mPreviewVideo;
    private TextView mPreviewLoading;
    /** Bumped whenever the preview changes, so a slow download knows it is no longer wanted. */
    private int mPreviewToken;

    private final List<WallpaperItem> mShown = new ArrayList<>();
    private WallpaperItem mSelected;
    private String mSource = SOURCE_CLOUD;
    private String mLocalPick;      // content:// uri chosen from the system picker
    /** How many leading filmstrip cells are not wallpapers (the device tab's browse cell). */
    private int mStripOffset;
    /** Guards the one automatic sync, so a car that is genuinely empty does not loop. */
    private boolean mTriedAutoSync = false;

    /** Chosen framing for the next Lynk & Co image apply (fill vs fit). Defaults to fill. */
    private int mLynkcoScaleMode = LynkcoApplier.SCALE_FILL;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(scaleForLynkco(LocaleHelper.wrap(newBase)));
    }

    /**
     * Lynk & Co renders this picker on a small, dense passenger panel where the default UI is
     * too tiny to use. Blow the whole activity up by overriding the density the layout inflates
     * against: every dp and sp scales together, so all views, text and icons grow uniformly with
     * no per-widget edits. The factor is operator-adjustable (see {@link LynkcoScale} and the
     * slider in Settings). Only Lynk & Co is affected — Leopard, GWM and Normal inflate at their
     * real density, unchanged.
     */
    private static Context scaleForLynkco(Context base) {
        SharedPreferences prefs =
                base.getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        if (!OperatingMode.isLynkco(prefs)) return base;
        float factor = LynkcoScale.factor(prefs);
        if (factor == 1f) return base;
        Configuration cfg = new Configuration(base.getResources().getConfiguration());
        cfg.densityDpi = Math.round(cfg.densityDpi * factor);
        return base.createConfigurationContext(cfg);
    }

    @Override
    protected void onCreate(Bundle b) {
        EdgeToEdge.enable(this);
        super.onCreate(b);
        setContentView(R.layout.activity_leopard_picker);

        // targetSdk 36 = Android 15 lays this out edge to edge whether we ask or not, so without
        // this the bottom bar (the "set wallpaper" button) sits under the navigation bar. Padded
        // on leopardRoot rather than the window, so the full-screen preview overlay that is its
        // sibling still covers the whole glass.
        final View root = findViewById(R.id.leopardRoot);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, windowInsets) -> {
            Insets in = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(in.left, in.top, in.right, in.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        mRepo = new WallpaperRepo(this);
        mPrefs = getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);

        // Tell the backend this car's mode on every open, even if the cached library means no
        // sync runs below — otherwise a Lynk & Co car never shows up as one in the manager.
        mRepo.reportModeAsync();

        // This picker is shared with Leopard, whose header chip reads "Leopard mode". On a Lynk
        // & Co car that label is wrong — relabel the chip so it names the mode the car is in.
        if (OperatingMode.isLynkco(mPrefs)) {
            ((TextView) findViewById(R.id.leopardChip)).setText(R.string.lynkco_chip);
        }

        mFilmstrip = findViewById(R.id.filmstrip);
        mSourceCloud = findViewById(R.id.sourceCloud);
        mSourceLocal = findViewById(R.id.sourceLocal);
        mSourcePhone = findViewById(R.id.sourcePhone);
        mStateBox = findViewById(R.id.leopardState);
        mStateText = findViewById(R.id.leopardStateText);
        mStateAction = findViewById(R.id.leopardStateAction);
        mStatus = findViewById(R.id.textViewLeopardStatus);
        mProgress = findViewById(R.id.leopardProgress);
        mSetButton = findViewById(R.id.buttonSetWallpaper);

        mPreview = findViewById(R.id.leopardPreview);
        mPreviewImage = findViewById(R.id.leopardPreviewImage);
        mPreviewBadge = findViewById(R.id.leopardPreviewBadge);
        mPreviewProgress = findViewById(R.id.leopardPreviewProgress);
        mPreviewVideo = findViewById(R.id.leopardPreviewVideo);
        mPreviewLoading = findViewById(R.id.leopardPreviewLoading);

        mSourceCloud.setOnClickListener(v -> selectSource(SOURCE_CLOUD));
        mSourceLocal.setOnClickListener(v -> selectSource(SOURCE_LOCAL));
        mSourcePhone.setOnClickListener(v -> selectSource(SOURCE_PHONE));
        mSetButton.setOnClickListener(v -> applySelection());
        findViewById(R.id.buttonPreviewSet).setOnClickListener(v -> applySelection());
        findViewById(R.id.buttonPreviewChange).setOnClickListener(v -> hidePreview());
        findViewById(R.id.buttonLeopardSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        mSource = mPrefs.getString(PREF_DEFAULT_SOURCE, SOURCE_CLOUD);
        selectSource(mSource);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The mirror of the check in FullscreenActivity: this screen only exists because the
        // mode is a hand-off product (Leopard or Lynkco), so the moment that stops being true —
        // the user switched back in Settings, which we launch plainly and get no result from —
        // it has to stand down.
        if(!OperatingMode.isHandoff(mPrefs)) {
            startActivity(new Intent(this, FullscreenActivity.class));
            finish();
            return;
        }
        // onPause tore the player down. Coming back — most often from the system's live-wallpaper
        // screen, which every video apply passes through — the preview would otherwise be a black
        // rectangle where a playing clip was. The file is cached by now, so this is instant.
        if(mPreview != null && mPreview.getVisibility() == View.VISIBLE
                && mSelected != null && mSelected.isVideo()) {
            preparePreviewVideo(mSelected);
        }
    }

    // ---------------------------------------------------------------- sources

    private void selectSource(String source) {
        mSource = source;
        mSourceCloud.setSelected(SOURCE_CLOUD.equals(source));
        mSourceLocal.setSelected(SOURCE_LOCAL.equals(source));
        mSourcePhone.setSelected(SOURCE_PHONE.equals(source));
        if(SOURCE_CLOUD.equals(source)) {
            showCloud();
        } else if(SOURCE_PHONE.equals(source)) {
            startPhoneUpload();
        } else {
            showLocal();
        }
    }

    // ---------------------------------------------------------------- phone (QR)

    /**
     * Receive an image from a phone over the local Wi-Fi, same server the Settings screen uses.
     *
     * Leopard has no Settings route to this — the app is the picker — so without it a Leopard
     * car simply could not take a photo from a customer's phone.
     */
    private void startPhoneUpload() {
        stopUploadServer();
        final String url;
        try {
            // The upload page can now send a batch, but Leopard's flow is deliberately one
            // picture at a time — it hands the chosen image straight to the car. Take the last
            // one and leave the rest on disk for the wallpaper list; changing the Leopard flow
            // itself is out of scope until it has been tested on a real Leopard car.
            mUploadServer = UploadServer.startNew(this, mRepo, savedPaths -> {
                if(savedPaths != null && !savedPaths.isEmpty()) {
                    onPhoneUpload(savedPaths.get(savedPaths.size() - 1));
                }
            });
            url = mUploadServer.getUrl();
        } catch(Exception e) {
            stopUploadServer();
            showState(R.string.pair_no_wifi, R.string.leopard_retry, v -> selectSource(SOURCE_PHONE));
            return;
        }
        if(url == null) {
            stopUploadServer();
            showState(R.string.pair_no_wifi, R.string.leopard_retry, v -> selectSource(SOURCE_PHONE));
            return;
        }
        showQr(url);
    }

    /** The QR fills the strip area — this IS the content while this source is chosen. */
    private void showQr(String url) {
        mFilmstrip.removeAllViews();
        hideState();
        // One box, not a strip: centre it. (buildFilmstrip puts this back for thumbnails.)
        mFilmstrip.setGravity(Gravity.CENTER);
        float d = getResources().getDisplayMetrics().density;

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);

        ImageView qr = new ImageView(this);
        int px = Math.round(190 * d);
        qr.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        qr.setImageBitmap(QrCode.generate(url, 600));
        box.addView(qr);

        TextView hint = new TextView(this);
        hint.setText(R.string.leopard_phone_hint);
        hint.setGravity(Gravity.CENTER);
        hint.setTextSize(12);
        hint.setTextColor(ContextCompat.getColor(this, R.color.aurora_muted));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                Math.round(420 * d), LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.topMargin = Math.round(12 * d);
        hint.setLayoutParams(hlp);
        box.addView(hint);

        mFilmstrip.addView(box);
    }

    private void onPhoneUpload(String savedPath) {
        if(savedPath == null) return;
        // Straight into the preview: unlike the Settings flow, whoever opened this picker is
        // standing at the head unit waiting for exactly this.
        WallpaperItem item = new WallpaperItem(WallpaperItem.guessType(savedPath), "file://" + savedPath);
        mShown.clear();
        mShown.add(item);
        mSelected = item;
        hideState();
        buildFilmstrip();
        showPreview(item);
        toast(R.string.pair_uploaded);
    }

    private void stopUploadServer() {
        if(mUploadServer != null) {
            try { mUploadServer.stop(); } catch(Exception ignored) {}
            mUploadServer = null;
        }
    }

    @Override
    protected void onDestroy() {
        // Leaving a web server listening on the car's Wi-Fi after the screen is gone would be
        // a surprise on a device that never really shuts down.
        stopUploadServer();
        super.onDestroy();
    }

    private void showCloud() {
        mShown.clear();
        // Cloud circle = cloud only. allItems() is the local folder AND the synced manifest
        // merged together, which is what the slideshow wants but not what this says on the tin:
        // the circle next to it is literally called "from the device".
        //
        // The per-car hide list is ignored on purpose. It means "skip in the slideshow", and
        // Leopard has no slideshow — the owner is picking one image by hand, so hiding it from
        // a rotation that no longer exists would just make images vanish for no visible reason.
        for(WallpaperItem it : mRepo.allItems()) {
            if(LeopardCache.isRemote(it.url)) mShown.add(it);
        }

        // Refresh once per open so images deleted (or added) on the manager propagate to the car
        // instead of the cached list lingering until the cache happens to empty. An empty box
        // shows the spinner as before; a cached box shows its images immediately and refreshes
        // quietly underneath, so a wallpaper removed from the website disappears on the next open.
        if(!mTriedAutoSync) {
            mTriedAutoSync = true;
            if(mShown.isEmpty()) {
                refreshCloud();
                return;
            }
            hideState();
            buildFilmstrip();
            silentRefresh();
            return;
        }

        if(mShown.isEmpty()) {
            showState(R.string.leopard_empty, R.string.leopard_empty_action, v -> refreshCloud());
            return;
        }
        hideState();
        buildFilmstrip();
    }

    /** Sync without disturbing the visible strip; on success re-read the list so anything deleted
     *  on the manager drops out and anything new appears. Failures are ignored — the cache stands. */
    private void silentRefresh() {
        mRepo.sync((success, count, error) -> runOnUiThread(() -> {
            if(success) showCloud();
        }));
    }

    private void refreshCloud() {
        mProgress.setVisibility(View.VISIBLE);
        hideState();
        mRepo.sync((success, count, error) -> runOnUiThread(() -> {
            mProgress.setVisibility(View.GONE);
            if(success) { showCloud(); return; }   // sync() reloads the list itself
            // An offline car may still have a usable library: the last good response is cached
            // and videos are pre-downloaded. "Offline, showing cached" and "offline with
            // nothing" are different situations and must not share one message.
            if(hasCloudItems()) { showCloud(); toast(R.string.leopard_offline_cached); }
            else showState(R.string.leopard_offline_empty, R.string.leopard_retry, v -> refreshCloud());
        }));
    }

    /** Cached cloud wallpapers only — local files are not this circle's business. */
    private boolean hasCloudItems() {
        for(WallpaperItem it : mRepo.allItems()) if(LeopardCache.isRemote(it.url)) return true;
        return false;
    }

    /**
     * Everything stored on the head unit itself: files dropped in the wallpaper folder AND every
     * picture a customer sent over with the QR code.
     *
     * This circle used to jump straight to the system file picker, which meant a phone upload had
     * exactly one moment of existence — the preview it landed in. Close the app and the picture
     * was unreachable: the cloud circle filters itself down to remote urls, and the QR circle only
     * ever draws a new code. The file was on disk the whole time with no screen that would list
     * it. This is that screen; "browse the device" moved into the strip as its first cell.
     */
    private void showLocal() {
        // The upload writes into the folder without telling the playlist, so re-read it here or a
        // picture that arrived a moment ago would still be missing from the list that exists to
        // show it.
        mRepo.load();
        mShown.clear();
        for(WallpaperItem it : mRepo.allItems()) {
            if(!LeopardCache.isRemote(it.url)) mShown.add(it);
        }
        if(mShown.isEmpty()) {
            // Nothing stored yet — the browse cell has no strip to live in, so the state box
            // carries the same action instead.
            showState(R.string.leopard_local_empty, R.string.leopard_local_browse, v -> openLocalPicker());
            return;
        }
        hideState();
        buildFilmstrip();
    }

    private void openLocalPicker() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        // Single select in Leopard: only one file can be the wallpaper, so offering ten is a
        // question we would then have to answer for the user.
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        i.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(i, PICK_LOCAL_REQUEST);
        } catch(ActivityNotFoundException e) {
            toast(R.string.leopard_apply_failed);
            selectSource(SOURCE_CLOUD);
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if(req != PICK_LOCAL_REQUEST) return;

        // The system picker is a screen we do not control and cannot style. What we DO own is
        // the seam: landing back here with nothing shown would repeat the existing defect where
        // an import gives no confirmation at all.
        if(res != RESULT_OK || data == null || data.getData() == null) {
            // Backing out of the system picker returns to the stored list rather than to an empty
            // screen — cancelling a browse is not a reason to lose sight of what is on the car.
            showLocal();
            return;
        }
        Uri uri = data.getData();
        try {
            // Persist the grant, or the choice dies at the next reboot.
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch(Throwable ignored) { }

        mLocalPick = uri.toString();
        mShown.clear();
        WallpaperItem item = new WallpaperItem(WallpaperItem.guessType(queryName(uri)), mLocalPick);
        mShown.add(item);
        mSelected = item;
        hideState();
        buildFilmstrip();
    }

    private String queryName(Uri uri) {
        android.database.Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                    null, null, null);
            if(c != null && c.moveToFirst()) return c.getString(0);
        } catch(Exception ignored) {
        } finally { if(c != null) c.close(); }
        return uri.getLastPathSegment();
    }

    // ---------------------------------------------------------------- filmstrip

    private void buildFilmstrip() {
        mFilmstrip.removeAllViews();
        // Undo the centring showQr() applies: a strip of thumbnails starts at the edge and
        // scrolls, and fillViewport would otherwise centre a short strip on its own.
        mFilmstrip.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        float d = getResources().getDisplayMetrics().density;
        // Height is the fixed dimension; width follows each picture's own proportions (see
        // fitCellToImage). Held at 165dp rather than filling the band because the body is only
        // ~185dp tall once the header and the button bar are taken out on a 2x-density panel —
        // anything taller would be clipped on exactly the head units this mode exists for.
        // 440dp is 2.67:1, the shape of the screen the wallpaper lands on: the right guess to
        // draw before the real proportions are known, and what a cell keeps if they never arrive.
        int cellW = Math.round(440 * d), cellH = Math.round(165 * d), gap = Math.round(16 * d);
        // The same local file reaches us under two spellings — "file:///storage/…" from the phone
        // upload, bare "/storage/…" from the folder scan — so compare them stripped, or the
        // "Current" badge never lands on the picture that is actually on screen.
        String currentUri = samePath(mPrefs.getString(MediaWallpaperService.PREF_URI, ""));

        final int radius = Math.round(16 * d);   // must match the corners in leopard_cell_bg

        // The device strip keeps the old behaviour available as its first cell: the list is what
        // you normally want, browsing the file system is the exception.
        mStripOffset = SOURCE_LOCAL.equals(mSource) ? 1 : 0;
        if(mStripOffset == 1) mFilmstrip.addView(browseCell(cellH, gap, d, radius));

        for(final WallpaperItem item : mShown) {
            final FrameLayout cell = new FrameLayout(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(cellW, cellH);
            lp.setMarginEnd(gap);
            cell.setLayoutParams(lp);
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setBackgroundColor(0xFF241c11);

            // The ring drawn on top has rounded corners, but the image underneath is a plain
            // rectangle — so its square corners poked out through the curve. Clip the cell to
            // the same shape the ring draws, and everything inside follows it.
            cell.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View v, Outline outline) {
                    outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
                }
            });
            cell.setClipToOutline(true);

            ImageView img = new ImageView(this);
            img.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            // FIT_CENTER never distorts, and once the cell has been resized to the picture's own
            // proportions it has nothing left to letterbox either — the frame ends where the
            // photo ends. It still matters for the moment before the image arrives, and for a
            // video whose frame we never got.
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            cell.addView(img);

            if(item.isVideo()) {
                // The playlist pre-downloads videos for smooth looping, so when the file is
                // already cached we can pull frame 0 out of it locally. Only a video that has
                // not been fetched yet falls back to the flat placeholder.
                String local = mRepo.localVideoPath(item);
                if(local != null) {
                    Glide.with(this)
                            .asBitmap()
                            .load(new File(local))
                            .apply(RequestOptions.frameOf(0))
                            .override(cellW, cellH)
                            .listener(new RequestListener<Bitmap>() {
                                @Override
                                public boolean onLoadFailed(GlideException e, Object model,
                                                            Target<Bitmap> t, boolean first) {
                                    return false;   // keep the 2.67:1 guess
                                }
                                @Override
                                public boolean onResourceReady(Bitmap res, Object model,
                                                               Target<Bitmap> t, DataSource src,
                                                               boolean first) {
                                    fitCellToImage(cell, res.getWidth(), res.getHeight());
                                    return false;
                                }
                            })
                            .into(img);
                }
                cell.addView(typeBadge(R.drawable.ic_badge_video_20dp, d));
            } else {
                // Full-size originals over a weak link: let Glide downsample to the cell.
                Object model = item.url.startsWith("content://") ? Uri.parse(item.url)
                        : (item.url.startsWith("http") ? item.url : new File(item.url));
                Glide.with(this).load(model).override(cellW, cellH)
                        .listener(new RequestListener<Drawable>() {
                            @Override
                            public boolean onLoadFailed(GlideException e, Object m,
                                                        Target<Drawable> t, boolean first) {
                                return false;
                            }
                            @Override
                            public boolean onResourceReady(Drawable res, Object m,
                                                           Target<Drawable> t, DataSource src,
                                                           boolean first) {
                                fitCellToImage(cell, res.getIntrinsicWidth(), res.getIntrinsicHeight());
                                return false;
                            }
                        })
                        .into(img);
                cell.addView(typeBadge(R.drawable.ic_badge_image_20dp, d));
            }

            if(!currentUri.isEmpty() && currentUri.equals(samePath(item.url))) {
                cell.addView(badge(getString(R.string.leopard_badge_current), Gravity.TOP | Gravity.END, d));
            }

            View ring = new View(this);
            ring.setLayoutParams(new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            ring.setBackgroundResource(R.drawable.leopard_cell_bg);
            ring.setDuplicateParentStateEnabled(true);
            cell.addView(ring);

            // Tap opens the full-screen preview rather than just ticking the cell: a 326x122
            // thumbnail is too small to commit a whole dashboard to.
            cell.setOnClickListener(v -> { mSelected = item; refreshSelection(); showPreview(item); });
            mFilmstrip.addView(cell);
        }
        refreshSelection();
    }

    /**
     * "This is a video" / "this is a photo", bottom-left of a cell.
     *
     * An icon rather than the word: at 326x122 over a busy photo a text badge is a smear, and
     * the two glyphs are told apart at a glance across a cabin in a way two Arabic words are not.
     */
    private ImageView typeBadge(int iconRes, float d) {
        ImageView v = new ImageView(this);
        int size = Math.round(28 * d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.BOTTOM | Gravity.START;
        lp.setMargins(Math.round(8 * d), 0, 0, Math.round(8 * d));
        v.setLayoutParams(lp);
        v.setBackgroundResource(R.drawable.leopard_badge_circle);
        v.setPadding(Math.round(5 * d), Math.round(5 * d), Math.round(5 * d), Math.round(5 * d));
        v.setImageResource(iconRes);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    /**
     * Narrow (or widen) a cell so its frame ends exactly where the picture does.
     *
     * Every cell used to be 2.67:1, the shape of the car's screen, and the photo was fitted
     * inside it — so a 16:9 wallpaper sat in the middle of its frame with a bar of dead
     * background down each side. The border was drawn around the slot, not around the image.
     *
     * The height is what the strip has to hold constant (they must line up), so the width is
     * what moves: height x the picture's own aspect ratio. The clamps only catch the extremes —
     * a panorama that would otherwise fill the whole strip on its own, and a tall portrait that
     * would shrink to a stripe too narrow to recognise across a cabin.
     */
    private void fitCellToImage(View cell, int imgW, int imgH) {
        if(imgW <= 0 || imgH <= 0) return;
        ViewGroup.LayoutParams lp = cell.getLayoutParams();
        if(lp == null) return;
        float d = getResources().getDisplayMetrics().density;
        int h = lp.height > 0 ? lp.height : Math.round(165 * d);
        int w = Math.round(h * (imgW / (float) imgH));
        w = Math.max(Math.round(h * 0.62f), Math.min(Math.round(h * 3.2f), w));
        if(w == lp.width) return;
        lp.width = w;
        cell.setLayoutParams(lp);
    }

    /** "Browse the device" — the old system-picker route, now a cell instead of the whole tab. */
    private View browseCell(int cellH, int gap, float d, final int radius) {
        FrameLayout cell = new FrameLayout(this);
        // Half width: it is an action, not a wallpaper, and it must not read as one of the images.
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Math.round(220 * d), cellH);
        lp.setMarginEnd(gap);
        cell.setLayoutParams(lp);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setBackgroundColor(0xFF241c11);
        cell.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline outline) {
                outline.setRoundRect(0, 0, v.getWidth(), v.getHeight(), radius);
            }
        });
        cell.setClipToOutline(true);

        TextView label = new TextView(this);
        FrameLayout.LayoutParams tlp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        tlp.gravity = Gravity.CENTER;
        label.setLayoutParams(tlp);
        label.setGravity(Gravity.CENTER);
        label.setText(R.string.leopard_local_browse);
        label.setTextSize(15);
        label.setTextColor(ContextCompat.getColor(this, R.color.gold));
        cell.addView(label);

        View ring = new View(this);
        ring.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        ring.setBackgroundResource(R.drawable.leopard_cell_bg);
        cell.addView(ring);

        cell.setOnClickListener(v -> openLocalPicker());
        return cell;
    }

    private TextView badge(String text, int gravity, float d) {
        TextView t = new TextView(this);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = gravity;
        lp.setMargins(Math.round(10 * d), Math.round(10 * d), Math.round(10 * d), Math.round(10 * d));
        t.setLayoutParams(lp);
        t.setBackgroundResource(R.drawable.fit_badge_bg);
        t.setPadding(Math.round(9 * d), Math.round(4 * d), Math.round(9 * d), Math.round(4 * d));
        t.setTextSize(11);
        t.setTextColor(ContextCompat.getColor(this, R.color.gold));
        t.setText(text);
        return t;
    }

    /** One spelling for a local file, so "file:///storage/x" and "/storage/x" compare equal. */
    private static String samePath(String url) {
        if(url == null) return "";
        return url.startsWith("file://") ? url.substring("file://".length()) : url;
    }

    private void refreshSelection() {
        // Cells and items are no longer 1:1 — the device strip opens with a browse cell that
        // stands for no wallpaper, so every item sits mStripOffset places to the right.
        for(int i = 0; i < mFilmstrip.getChildCount(); i++) {
            int item = i - mStripOffset;
            mFilmstrip.getChildAt(i).setSelected(
                    item >= 0 && item < mShown.size() && mShown.get(item) == mSelected);
        }
        mSetButton.setAlpha(mSelected == null ? 0.5f : 1f);
    }

    // ---------------------------------------------------------------- preview

    /** Show the chosen wallpaper full-screen before committing it. */
    private void showPreview(WallpaperItem item) {
        mPreview.setVisibility(View.VISIBLE);
        mStatus.setText("");
        mPreviewBadge.setVisibility(item.isVideo() ? View.VISIBLE : View.GONE);
        mPreviewBadge.setText(R.string.leopard_badge_video);
        stopPreviewVideo();

        if(item.isVideo()) {
            mPreviewImage.setImageDrawable(new android.graphics.drawable.ColorDrawable(0xFF241c11));
            preparePreviewVideo(item);
        } else {
            mPreviewVideo.setVisibility(View.GONE);
            mPreviewLoading.setVisibility(View.GONE);
            mPreviewProgress.setVisibility(View.GONE);
            Object model = item.url.startsWith("content://") ? Uri.parse(item.url)
                    : (item.url.startsWith("http") ? item.url : new File(item.url));
            Glide.with(this).load(model).into(mPreviewImage);
        }
        findViewById(R.id.buttonPreviewSet).requestFocus();
    }

    /**
     * Fetch the clip if it is not already here, then play it in the preview.
     *
     * The token guards the obvious race: the download takes seconds, and in that time the user
     * can go back and pick something else. Without it a late-finishing download would start
     * playing over whatever they chose afterwards.
     */
    private void preparePreviewVideo(final WallpaperItem item) {
        final int token = ++mPreviewToken;
        final String url = item.url;

        if(!LeopardCache.isRemote(url)) {
            // A local pick (content:// or a file on the head unit) needs no download.
            playPreviewVideo(Uri.parse(url.startsWith("content://") ? url : "file://" + url));
            return;
        }

        mPreviewVideo.setVisibility(View.GONE);
        mPreviewProgress.setVisibility(View.VISIBLE);
        mPreviewLoading.setVisibility(View.VISIBLE);
        mPreviewLoading.setText(R.string.leopard_preparing);

        new Thread(new Runnable() {
            @Override
            public void run() {
                final Uri local = LeopardCache.materialise(LeopardPickerActivity.this, url,
                        new LeopardCache.ProgressListener() {
                            @Override
                            public void onProgress(final int percent) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        if(token != mPreviewToken) return;
                                        mPreviewLoading.setText(percent < 0
                                                ? getString(R.string.leopard_preparing)
                                                : getString(R.string.leopard_downloading, percent));
                                    }
                                });
                            }
                        });
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if(token != mPreviewToken) return;   // the user moved on
                        mPreviewProgress.setVisibility(View.GONE);
                        mPreviewLoading.setVisibility(View.GONE);
                        if(local == null) {
                            say(R.string.leopard_download_failed);
                            return;
                        }
                        playPreviewVideo(local);
                    }
                });
            }
        }).start();
    }

    private void playPreviewVideo(Uri uri) {
        try {
            mPreviewVideo.setVisibility(View.VISIBLE);
            mPreviewVideo.setVideoURI(uri);
            mPreviewVideo.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
                @Override
                public void onPrepared(MediaPlayer mp) {
                    mp.setLooping(true);
                    // Silent: this is a preview on a dashboard, and the wallpaper itself has no
                    // sound either — playing audio here would misrepresent the result.
                    try { mp.setVolume(0f, 0f); } catch(Exception ignored) {}
                }
            });
            mPreviewVideo.setOnErrorListener(new MediaPlayer.OnErrorListener() {
                @Override
                public boolean onError(MediaPlayer mp, int what, int extra) {
                    // Some clips the wallpaper engine can still play will not open in a VideoView.
                    // Fall back to the old flat panel rather than a system "can't play" dialog:
                    // the file is already downloaded, so applying it may well work regardless.
                    mPreviewVideo.setVisibility(View.GONE);
                    mPreviewLoading.setVisibility(View.GONE);
                    return true;
                }
            });
            mPreviewVideo.start();
        } catch(Throwable t) {
            mPreviewVideo.setVisibility(View.GONE);
        }
    }

    private void stopPreviewVideo() {
        try {
            if(mPreviewVideo.isPlaying()) mPreviewVideo.stopPlayback();
        } catch(Throwable ignored) {}
        mPreviewVideo.setVisibility(View.GONE);
    }

    private void hidePreview() {
        mPreviewToken++;   // abandon any download still running for this preview
        stopPreviewVideo();
        mPreview.setVisibility(View.GONE);
        mPreviewProgress.setVisibility(View.GONE);
        mPreviewLoading.setVisibility(View.GONE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving a decoder running behind another app is not something a head unit forgives.
        stopPreviewVideo();
    }

    @Override
    public void onBackPressed() {
        // Back out of the preview rather than out of the app — the strip is the step behind it.
        if(mPreview != null && mPreview.getVisibility() == View.VISIBLE) { hidePreview(); return; }
        super.onBackPressed();
    }

    // ---------------------------------------------------------------- apply

    private void applySelection() {
        if(mSelected == null) { toast(R.string.leopard_pick_first); return; }

        // Lynk & Co images: the theme app's own crop/fit preview is unreliable, so we ask here and
        // bake the framing in ourselves. Video skips this (it rides the Leopard live-wallpaper path).
        if(OperatingMode.isLynkco(mPrefs) && !mSelected.isVideo()) {
            new AlertDialog.Builder(this)
                    .setTitle(R.string.lynkco_frame_title)
                    .setItems(new CharSequence[]{
                            getString(R.string.lynkco_frame_fill),
                            getString(R.string.lynkco_frame_fit) }, (d, which) -> {
                        mLynkcoScaleMode = (which == 0) ? LynkcoApplier.SCALE_FILL : LynkcoApplier.SCALE_FIT;
                        doApply();
                    })
                    .setNegativeButton(R.string.update_cancel, null)
                    .show();
            return;
        }

        // Images in Lynkco go to the head unit's theme app (no system screen). Video does NOT —
        // the theme app's file entry point is image-only — so a Lynkco video falls back to our own
        // MediaWallpaperService, which still needs the one-time system live-wallpaper screen.
        if(LeopardApplier.needsSystemScreen(this, mSelected.type)) {
            // The worst moment in the feature: we are about to throw the user into an unstyled
            // system screen with an English button. Warn first, and say it is one-time — an
            // unexplained hand-off reads as the app breaking.
            new AlertDialog.Builder(this)
                    .setTitle(R.string.leopard_video_first_title)
                    .setMessage(R.string.leopard_video_first_message)
                    .setPositiveButton(R.string.leopard_video_first_ok, (d, w) -> doApply())
                    .setNegativeButton(R.string.update_cancel, null)
                    .show();
            return;
        }
        doApply();
    }

    private void doApply() {
        setBusy(true, R.string.leopard_applying);
        final String url = mSelected.url, type = mSelected.type;
        // Only IMAGES go to the theme app — its file entry point is image-only. A Lynkco video
        // falls through to the Leopard path below (our own MediaWallpaperService live wallpaper),
        // which is the one way to play an arbitrary local video on these units without root.
        final boolean lynkcoImage = OperatingMode.isLynkco(mPrefs) && !WallpaperItem.TYPE_VIDEO.equals(type);
        // Screen size for the fill/fit reframing — read on the UI thread, where window access is safe.
        final android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        final int canvasW = dm.widthPixels, canvasH = dm.heightPixels;
        final int scaleMode = mLynkcoScaleMode;
        new Thread(() -> {
            // Lynkco hands the image to the head unit's own theme app, which reads a plain file
            // path off shared storage. LynkcoApplier stages the download/copy there itself and
            // opens the theme app's preview — a different path from Leopard's WallpaperManager.
            if(lynkcoImage) {
                runOnUiThread(() -> say(R.string.leopard_preparing));
                // If a video was applied earlier it is running as our Android live wallpaper, a
                // different system from the Flyme theme app this image goes to — clear it first
                // or the video keeps playing on top of the new image.
                LeopardApplier.clearOurLiveWallpaper(this);
                final int r = LynkcoApplier.apply(this, url, type, scaleMode, canvasW, canvasH);
                runOnUiThread(() -> { setBusy(false, 0); onLynkcoApplied(r); });
                return;
            }
            // A cloud item is an https:// URL, which the wallpaper engine cannot read: it asks
            // the ContentResolver for the type and the stream, and HTTP has neither. Download
            // it once and hand over a content:// instead — the same shape the system file
            // picker produces, so the proven engine needs no special case.
            String applyUrl = url;
            if(LeopardCache.isRemote(url)) {
                runOnUiThread(() -> say(R.string.leopard_preparing));
                Uri local = LeopardCache.materialise(this, url);
                if(local == null) {
                    runOnUiThread(() -> { setBusy(false, 0); say(R.string.leopard_download_failed); });
                    return;
                }
                applyUrl = local.toString();
            } else if(!applyUrl.startsWith("content://")
                    && (WallpaperItem.TYPE_VIDEO.equals(type) || WallpaperItem.TYPE_GIF.equals(type))) {
                // A clip stored on the head unit: the engine resolves its type through the
                // ContentResolver, so hand it the same content:// shape a cloud video gets rather
                // than a bare path it cannot ask about. Stills skip this — they are read as files.
                Uri shared = LeopardCache.localFile(this, applyUrl);
                if(shared != null) applyUrl = shared.toString();
            }
            final int result = LeopardApplier.apply(this, applyUrl, type);
            runOnUiThread(() -> { setBusy(false, 0); onApplied(result, type); });
        }).start();
    }

    /**
     * Lynkco's apply does not finish here — it opens the head unit's own preview with an "Apply"
     * button, so success means "the theme app took it and is now showing the preview", not "the
     * wallpaper is set". The copy that reaches the user has to say that, or a tap that then needs
     * a second tap on the system screen reads as the app not working.
     */
    private void onLynkcoApplied(int result) {
        if(result != LynkcoApplier.RESULT_LAUNCHED) {
            say(R.string.leopard_apply_failed);
            return;
        }
        say(R.string.lynkco_applied);
        hidePreview();
        buildFilmstrip();
        if(mPrefs.getBoolean("leopard-close-after-set", false)) {
            mSetButton.postDelayed(this::finish, 900);
        }
    }

    /** The download can take real seconds on a car's link; say so rather than look frozen. */
    private void setBusy(boolean busy, int statusRes) {
        mPreviewProgress.setVisibility(busy && mPreview.getVisibility() == View.VISIBLE
                ? View.VISIBLE : View.GONE);
        mProgress.setVisibility(busy && mPreview.getVisibility() != View.VISIBLE
                ? View.VISIBLE : View.GONE);
        findViewById(R.id.buttonPreviewSet).setEnabled(!busy);
        mSetButton.setEnabled(!busy);
        if(statusRes != 0) mStatus.setText(statusRes);
    }

    private void onApplied(int result, String type) {
        switch(result) {
            case LeopardApplier.RESULT_NEEDS_SYSTEM_SCREEN:
                try {
                    startActivity(LeopardApplier.systemPickerIntent(this));
                } catch(ActivityNotFoundException e) {
                    // Some cheap ROMs ship without the live wallpaper picker at all.
                    say(R.string.leopard_unsupported);
                }
                return;
            case LeopardApplier.RESULT_APPLIED_BOTH:
                say(R.string.leopard_applied_image);
                break;
            case LeopardApplier.RESULT_APPLIED_LIVE:
                // A live wallpaper cannot cover the lock screen on most versions; say home only.
                say(R.string.leopard_applied_video);
                break;
            default:
                say(R.string.leopard_apply_failed);
                return;
        }
        hidePreview();
        buildFilmstrip();   // the "Current" badge moves

        if(mPrefs.getBoolean("leopard-close-after-set", false)) {
            // The app has just made itself redundant — Android owns the screen now. Leaving is
            // the honest end to "open, choose, close".
            mSetButton.postDelayed(this::finish, 900);
        }
    }

    // ---------------------------------------------------------------- state view

    private void showState(int textRes, int actionRes, View.OnClickListener action) {
        mFilmstrip.removeAllViews();
        mStateBox.setVisibility(View.VISIBLE);
        mStateText.setText(textRes);
        if(actionRes != 0 && action != null) {
            mStateAction.setVisibility(View.VISIBLE);
            mStateAction.setText(actionRes);
            mStateAction.setOnClickListener(action);
        } else {
            mStateAction.setVisibility(View.GONE);
        }
    }

    private void hideState() { mStateBox.setVisibility(View.GONE); }

    private void toast(int res) { Toast.makeText(this, res, Toast.LENGTH_SHORT).show(); }

    /**
     * Status that reaches the user wherever they are. The status line lives under the
     * filmstrip, which the preview covers — so while the preview is up, the same message has
     * to come through as a toast or it is written to a hidden view.
     */
    private void say(int res) {
        mStatus.setText(res);
        if(mPreview.getVisibility() == View.VISIBLE) toast(res);
    }
}
