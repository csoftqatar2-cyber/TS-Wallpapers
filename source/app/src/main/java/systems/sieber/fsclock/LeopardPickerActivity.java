package systems.sieber.fsclock;

import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Outline;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;

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

    private final List<WallpaperItem> mShown = new ArrayList<>();
    private WallpaperItem mSelected;
    private String mSource = SOURCE_CLOUD;
    private String mLocalPick;      // content:// uri chosen from the system picker
    /** Guards the one automatic sync, so a car that is genuinely empty does not loop. */
    private boolean mTriedAutoSync = false;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
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
        // mode is Leopard, so the moment that stops being true — the user switched back in
        // Settings, which we launch plainly and get no result from — it has to stand down.
        if(!OperatingMode.isLeopard(mPrefs)) {
            startActivity(new Intent(this, FullscreenActivity.class));
            finish();
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
            openLocalPicker();
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

        if(mShown.isEmpty()) {
            // Nothing cached. In Leopard there is no Settings screen doing the first sync for
            // us, so a freshly activated car would otherwise open onto an empty box and wait
            // for the technician to guess that a button behind it needs pressing. Fetch once,
            // by ourselves, and only give up out loud if that fails.
            if(!mTriedAutoSync) {
                mTriedAutoSync = true;
                refreshCloud();
                return;
            }
            showState(R.string.leopard_empty, R.string.leopard_empty_action, v -> refreshCloud());
            return;
        }
        hideState();
        buildFilmstrip();
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
            if(mShown.isEmpty()) showState(R.string.leopard_local_none, 0, null);
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
        int cellW = Math.round(326 * d), cellH = Math.round(122 * d), gap = Math.round(16 * d);
        String currentUri = mPrefs.getString(MediaWallpaperService.PREF_URI, "");

        final int radius = Math.round(16 * d);   // must match the corners in leopard_cell_bg

        for(final WallpaperItem item : mShown) {
            FrameLayout cell = new FrameLayout(this);
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
            // FIT_XY, not CENTER_CROP: a cell is 2.67:1 and most uploads are portrait, so
            // cropping to fill cut the top and bottom off exactly the part you are choosing by.
            // Stretched-but-whole is the honest thumbnail here — you can see what the image IS.
            img.setScaleType(ImageView.ScaleType.FIT_XY);
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
                            .into(img);
                }
                cell.addView(typeBadge(R.drawable.ic_badge_video_20dp, d));
            } else {
                // Full-size originals over a weak link: let Glide downsample to the cell.
                Object model = item.url.startsWith("content://") ? Uri.parse(item.url)
                        : (item.url.startsWith("http") ? item.url : new File(item.url));
                Glide.with(this).load(model).override(cellW, cellH).into(img);
                cell.addView(typeBadge(R.drawable.ic_badge_image_20dp, d));
            }

            if(!currentUri.isEmpty() && currentUri.equals(item.url)) {
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

    private void refreshSelection() {
        for(int i = 0; i < mFilmstrip.getChildCount(); i++) {
            mFilmstrip.getChildAt(i).setSelected(i < mShown.size() && mShown.get(i) == mSelected);
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

        if(item.isVideo()) {
            // No poster frame exists on the server, and pulling one would mean fetching the
            // whole file just to preview it. Say "video" honestly instead of faking a still.
            mPreviewImage.setImageDrawable(new android.graphics.drawable.ColorDrawable(0xFF241c11));
        } else {
            Object model = item.url.startsWith("content://") ? Uri.parse(item.url)
                    : (item.url.startsWith("http") ? item.url : new File(item.url));
            Glide.with(this).load(model).into(mPreviewImage);
        }
        findViewById(R.id.buttonPreviewSet).requestFocus();
    }

    private void hidePreview() {
        mPreview.setVisibility(View.GONE);
        mPreviewProgress.setVisibility(View.GONE);
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
        new Thread(() -> {
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
            }
            final int result = LeopardApplier.apply(this, applyUrl, type);
            runOnUiThread(() -> { setBusy(false, 0); onApplied(result, type); });
        }).start();
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
