package systems.sieber.fsclock;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.GridLayout;
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
import com.bumptech.glide.signature.ObjectKey;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
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
    private static final int FIT_EDITOR_REQUEST = 42;
    private static final String PREF_DEFAULT_SOURCE = "leopard-default-source";
    /**
     * Set when the owner ticks "do not show this again" in the hand-off dialog and then confirms.
     * Per car, not per picture — see {@link #showHandoffDialog}.
     */
    private static final String PREF_HANDOFF_EXPLAINED = "leopard-handoff-explained";
    private static final String SOURCE_CLOUD = "cloud";
    private static final String SOURCE_LOCAL = "local";
    private static final String SOURCE_PHONE = "phone";

    private WallpaperRepo mRepo;
    private SharedPreferences mPrefs;

    /** The library grid: two columns, scrolling down. Named for the strip it replaced. */
    private GridLayout mFilmstrip;
    private View mFilmstripScroll, mPhonePane;
    private GridLayout mPhoneGrid;
    private LinearLayout mPhoneQrBox;
    private View mSourceCloud, mSourceLocal, mSourcePhone, mStateBox, mSetButton;
    /** The video / images toggles above the cloud circle, and the row that holds them. */
    private View mFilterRow, mFilterVideo, mFilterImages;
    /**
     * Type filter on the cloud grid. Both on at every open, on purpose — it is a way to find a
     * picture faster in a long library, not a setting, and a car that opened on "videos only"
     * last week would read as "the photos are gone" this week. Nothing is persisted. Purely
     * what is DRAWN: sync, downloads and the cache do not know the filter exists.
     */
    // Owner's semantics (2026-09-05): nothing lit = everything shown; exactly one lit = that type only;
    // both lit = everything. Both start unlit at every open; nothing is persisted.
    private boolean mShowVideos = false, mShowImages = false;
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
    /** The image handed to the fit editor, and the screen it was framed against. */
    private WallpaperItem mEditing;
    private int mEditingW, mEditingH;

    /**
     * The picture in hand arrived from a phone over the QR code, so setting it is the end of a
     * whole errand — somebody sent a photo and is waiting to see it on the car — rather than one
     * step in browsing the library.
     */
    private boolean mFromPhoneUpload;

    /** Guards the one automatic sync, so a car that is genuinely empty does not loop. */
    private boolean mTriedAutoSync = false;

    /** Set while the one-shot "grid has a width now" listener is attached. See awaitGridWidth(). */
    private boolean mAwaitingGridWidth = false;

    /**
     * Clips this screen is fetching for itself, in the order they were asked for.
     *
     * A video cell can only draw once the file is on the car, and until then it is a flat brown
     * rectangle with a play badge — indistinguishable from a broken wallpaper. That state used to
     * be permanent in practice: the only thing that ever downloaded a clip was the playlist sync,
     * so a video the sync had missed (a dropped link, the skip button on the download screen, a
     * wallpaper added to the fleet after this car last synced) stayed blank for as long as the
     * owner cared to look at it. Opening the cell downloaded the file — into the OTHER cache, the
     * one the apply reads — and coming back showed the same blank cell, which is what made it
     * read as stuck rather than as slow.
     *
     * So the grid fetches its own missing clips, one at a time: one car link, one download, and
     * every other cell keeps whatever it already had.
     */
    private final java.util.LinkedHashSet<String> mVideoQueue = new java.util.LinkedHashSet<>();
    /** One download in flight — a head unit's link does not get faster by being asked twice. */
    private boolean mFetchingVideo;
    /** Urls that came back empty. Re-queueing them on every rebuild would never stop. */
    private final java.util.Set<String> mVideoFetchFailed = new java.util.HashSet<>();
    /** A preview downloaded a clip the grid behind it was drawing blank; rebuild on the way out. */
    private boolean mPreviewFetched;

    /**
     * Videos playing inside the library grid, so a clip can be judged before it is opened.
     *
     * Capped, and that cap is not a nicety. A head unit has a handful of hardware video decoders
     * — on the cheaper ones, very few — and they are shared with everything else the car is
     * doing. Exhausting them does not fail the tile that asked; it fails the NEXT thing to want
     * one, which here is the full-screen preview or the live wallpaper itself. So the grid takes
     * a few and no more, and every other video keeps the still frame it had before.
     *
     * Which few is the part that matters. The cap used to be handed out in grid order — the first
     * three video cells built took the players and kept them for the life of the screen, so
     * scrolling down led into a library where nothing moved, which is precisely the complaint the
     * moving tiles exist to answer. The pool now follows the viewport: a clip that scrolls out
     * hands its decoder back and one that scrolls in takes it.
     */
    private static final class VideoTile {
        final FrameLayout cell;
        final String path;
        MediaPlayer player;
        TextureView surface;
        VideoTile(FrameLayout cell, String path) { this.cell = cell; this.path = path; }
    }
    private final List<VideoTile> mVideoTiles = new ArrayList<>();
    /**
     * Enough to cover a full band of visible cells, which is the point — a cap that stops short
     * of what is on screen just moves the "this one does not move" complaint further down the
     * page. Six is two more than the four rows the tallest panel shows at once, and the grid now
     * gives its decoders back the moment an apply or a preview starts, which is what the cap was
     * really protecting.
     */
    private static final int MAX_LIVE_TILES = 6;
    /** Coalesces the burst of scroll and resize callbacks into one pass over the tiles. */
    private final Runnable mBindTiles = this::bindVisibleTiles;

    /** onPause released the grid's players; the strip needs rebuilding to get them back. */
    private boolean mTilesReleased;

    /**
     * "This screen has done its job and should leave" — kept in prefs, not in a field.
     *
     * It has to survive the activity, and that is not a theoretical worry: setting a wallpaper
     * changes the configuration, and the system answers a configuration change by destroying
     * this activity and building a new one. Anything held in memory at the moment of the apply
     * is gone — the field that remembered we were waiting on the system screen, and the delayed
     * "now go home" that was posted to a view belonging to the old instance. That is the whole
     * reason "the app closes after setting the wallpaper" worked for one case (a video on a car
     * whose live wallpaper was already ours — the one path that changes no configuration) and
     * silently did nothing for the rest.
     *
     * {@link #LEAVE_NOW} the wallpaper is set; go as soon as the message has been read.
     * {@link #LEAVE_AFTER_SYSTEM_SCREEN} the system's live-wallpaper screen is up and the answer
     * is unknown; on the way back, leave only if it took.
     */
    private static final String PREF_LEAVE = "leopard-leave-after-apply";
    private static final String PREF_LEAVE_AT = "leopard-leave-after-apply-at";
    /** The string resource the departing instance wanted shown. See markLeaveAfterApply. */
    private static final String PREF_LEAVE_MSG = "leopard-leave-after-apply-msg";
    private static final String LEAVE_NOW = "now";
    private static final String LEAVE_AFTER_SYSTEM_SCREEN = "system";
    /** Stand down without jumping to the launcher — something else is already in front. */
    private static final String LEAVE_QUIET = "quiet";
    /**
     * How long the intention stays good for. Long enough for the slowest hand-off (the system
     * screen, a human reading it, and a wallpaper engine starting up), short enough that a
     * request abandoned mid-way cannot close the app on somebody who opens it tomorrow.
     */
    private static final long LEAVE_VALID_MS = 3 * 60_000;

    /** The bottom-of-screen message panel. Built on first use — see toast(int). */
    private TextView mBanner;
    private final Runnable mHideBanner = () -> {
        if(mBanner != null) mBanner.setVisibility(View.GONE);
    };

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
        mFilmstripScroll = findViewById(R.id.filmstripScroll);
        mPhonePane = findViewById(R.id.leopardPhonePane);
        mPhoneGrid = findViewById(R.id.phoneGrid);
        mPhoneQrBox = findViewById(R.id.phoneQrBox);
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

        mFilterRow = findViewById(R.id.leopardTypeFilter);
        mFilterVideo = findViewById(R.id.filterVideo);
        mFilterImages = findViewById(R.id.filterImages);
        mFilterVideo.setSelected(mShowVideos);
        mFilterImages.setSelected(mShowImages);
        mFilterVideo.setOnClickListener(v -> toggleTypeFilter(true));
        mFilterImages.setOnClickListener(v -> toggleTypeFilter(false));

        mSourceCloud.setOnClickListener(v -> selectSource(SOURCE_CLOUD));
        mSourceLocal.setOnClickListener(v -> selectSource(SOURCE_LOCAL));
        mSourcePhone.setOnClickListener(v -> selectSource(SOURCE_PHONE));
        mSetButton.setOnClickListener(v -> applySelection());
        findViewById(R.id.buttonPreviewSet).setOnClickListener(v -> applySelection());
        findViewById(R.id.buttonPreviewChange).setOnClickListener(v -> hidePreview());
        findViewById(R.id.buttonLeopardSettings).setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));

        // Scrolling changes which clips are on screen, and the pool of decoders has to follow.
        // A tree-wide scroll listener rather than View.setOnScrollChangeListener: this runs on
        // ROMs older than the API that added it, and the band is not always the view that moves.
        mFilmstripScroll.getViewTreeObserver().addOnScrollChangedListener(this::scheduleTileBind);

        mSource = mPrefs.getString(PREF_DEFAULT_SOURCE, SOURCE_CLOUD);
        selectSource(mSource);
    }

    /** When this screen last asked the server (elapsed-realtime ms); 0 = never. */
    private long mLastSyncAskedMs;
    /** Fleet contract 2026-09-03: a real return to the foreground re-checks, at most once a minute. */
    private static final long RESUME_RECHECK_MIN_MS = 60_000L;

    @Override
    protected void onResume() {
        super.onResume();
        // Fleet contract 2026-09-03: coming back to the foreground asks the server again
        // (the licence may have been blocked, or a block lifted, while we were away), at
        // most once a minute. The first visit is covered by showCloud()'s own auto-sync;
        // the answer is handled exactly as every other sync (standDownIfInactive).
        if(mTriedAutoSync && mLastSyncAskedMs != 0
                && android.os.SystemClock.elapsedRealtime() - mLastSyncAskedMs >= RESUME_RECHECK_MIN_MS) {
            silentRefresh();
        }
        // The mirror of the check in FullscreenActivity: this screen only exists because the
        // mode is a hand-off product (Leopard or Lynkco), so the moment that stops being true —
        // the user switched back in Settings, which we launch plainly and get no result from —
        // it has to stand down.
        if(!OperatingMode.isHandoff(mPrefs)) {
            startActivity(new Intent(this, FullscreenActivity.class));
            finish();
            return;
        }
        // An apply that is already finished, or a system screen we are coming back from. Read
        // before anything else touches the screen: in the common case this instance was built a
        // moment ago by the configuration change the apply itself caused, and it exists only to
        // find this note and leave.
        if(resumeAfterApply()) return;
        // A wallpaper this car had is gone (see offerWallpaperRestore). Asked here rather than
        // fixed silently: putting a live wallpaper back is the system's screen and the owner's
        // own button, so the least we can do is spare them finding the picture again.
        offerWallpaperRestore();
        // onPause tore the player down. Coming back — most often from the system's live-wallpaper
        // screen, which every video apply passes through — the preview would otherwise be a black
        // rectangle where a playing clip was. The file is cached by now, so this is instant.
        if(mPreview != null && mPreview.getVisibility() == View.VISIBLE
                && mSelected != null && mSelected.isVideo()) {
            preparePreviewVideo(mSelected);
            return;   // the grid is behind the preview; rebuilding it now would be for nobody
        }
        // Settings is launched from here plainly, with no result to wait for, and it is where
        // pictures get hidden (إدارة الصور). This screen holds its OWN WallpaperRepo, so the hide
        // was written to prefs and this instance never heard about it: the picture the owner had
        // just unticked was still sitting on the grid, and picking it still worked. Others-mode
        // never showed this because FullscreenActivity reloads on the settings result.
        if(!hiddenSignature().equals(mShownHiddenSig)) {
            mRepo.load();
            if(SOURCE_PHONE.equals(mSource)) { markHiddenShown(); buildPhoneGrid(); }
            else selectSource(mSource);
            return;
        }
        restartTilePlayers();
    }

    /** Asked at most once per visit, so a declined offer does not come back on every resume. */
    private boolean mRestoreOffered;

    /**
     * Offer to put back a wallpaper the head unit took away.
     *
     * The car this exists for is a Denza, whose recents force-stops the package when its card is
     * swiped — and a force-stop makes the system drop our live wallpaper for the vendor's own
     * (the log is quoted in {@link LeopardApplier#keepTaskOutOfRecents}, which is what stops it
     * happening again). This is the other half: the cars already in that state when the fix
     * arrives, and the ways round it that remain — "close all", or a force-stop from Settings.
     *
     * Re-applying is deliberately the SAME call the Set button makes, so the restore cannot drift
     * from the apply: the stored file is already durable ({@code leopard-current/current.jpg}),
     * and the system's screen it hands to is the only way an ordinary app may install a live
     * wallpaper. One button on our side, one on the system's, and the picture the owner chose is
     * back — instead of finding it in the library again.
     */
    private void offerWallpaperRestore() {
        if(mRestoreOffered || isFinishing()) return;
        if(!LeopardApplier.wasTakenFromUs(this)) return;
        final String uri = mPrefs.getString(MediaWallpaperService.PREF_URI, null);
        final String type = mPrefs.getString(LeopardApplier.PREF_TYPE, null);
        if(uri == null) return;
        mRestoreOffered = true;
        CrashReporter.breadcrumb("leopard: offering to restore a wallpaper that was cleared");
        new AuroraDialog.Builder(this)
                .setTitle(R.string.leopard_restore_title)
                .setMessage(R.string.leopard_restore_message)
                .setPositiveButton(R.string.leopard_restore_ok,
                        (dlg, w) -> onApplied(LeopardApplier.apply(this, uri, type), type))
                .setNegativeButton(R.string.update_cancel, null)
                .show();
    }

    /**
     * The hide list as it stood when the pane on screen was built.
     *
     * Compared rather than watched: there is no callback when another screen edits the list, and
     * a blanket reload on every resume would re-run the cloud pane's sync each time the user came
     * back from the system's wallpaper screen.
     */
    private String mShownHiddenSig = "";

    private String hiddenSignature() {
        return mPrefs.getString(WallpaperRepo.PREF_HIDDEN, "");
    }

    private void markHiddenShown() {
        mShownHiddenSig = hiddenSignature();
    }

    // ---------------------------------------------------------------- sources

    private void selectSource(String source) {
        mSource = source;
        markHiddenShown();
        // Moving off the phone source ends that errand: what is set from the library afterwards
        // is browsing, and browsing must not throw anyone out to the launcher. (The upload
        // listener sets the flag before it switches back here, so this cannot undo its own case.)
        if(!SOURCE_PHONE.equals(source)) mFromPhoneUpload = false;
        mSourceCloud.setSelected(SOURCE_CLOUD.equals(source));
        mSourceLocal.setSelected(SOURCE_LOCAL.equals(source));
        mSourcePhone.setSelected(SOURCE_PHONE.equals(source));
        // The filter is the cloud library's. Off that source it stays where it is (a control
        // that jumps around is worse than one that is greyed) but goes quiet, so a tap on it
        // while the device list is up does not look like a button that ignored you.
        boolean cloud = SOURCE_CLOUD.equals(source);
        mFilterRow.setAlpha(cloud ? 1f : 0.35f);
        mFilterVideo.setEnabled(cloud);
        mFilterImages.setEnabled(cloud);
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
        // Re-entering this source must not restart the server. A customer may already be looking
        // at the page this URL opened; a fresh server would move the port out from under them and
        // their upload would fail with nothing on either screen to explain why.
        if(mUploadServer != null && mUploadServer.getUrl() != null) {
            showPhonePane(mUploadServer.getUrl());
            return;
        }
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
        showPhonePane(url);
    }

    /**
     * The phone source, as two halves of one band.
     *
     * It used to be the QR alone, which made every picture that arrived a one-time event: it was
     * shown once and then had nowhere to live, because this pane redrew as a bare code the next
     * time it was opened. What comes from a phone belongs to the phone source, so the pictures
     * are listed here — on the left, where there is room to grow — and the code keeps the right
     * edge, pinned rather than scrolling, so scanning is always one look away.
     */
    private void showPhonePane(String url) {
        hideState();
        mFilmstripScroll.setVisibility(View.GONE);
        mPhonePane.setVisibility(View.VISIBLE);
        buildPhoneQr(url);
        buildPhoneGrid();
    }

    /** The right half: the code, what to do with it, and the one thing that breaks it. */
    private void buildPhoneQr(String url) {
        mPhoneQrBox.removeAllViews();
        float d = getResources().getDisplayMetrics().density;
        final int colW = Math.round(210 * d);

        ImageView qr = new ImageView(this);
        int px = Math.round(140 * d);
        qr.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        qr.setImageBitmap(QrCode.generate(url, 600));
        mPhoneQrBox.addView(qr);

        // Numbered, because "scan this" is not the whole job — the page that opens has two more
        // steps on it, and someone standing at a car with a customer's phone should not have to
        // guess which of them the car is waiting on.
        int[] steps = { R.string.leopard_phone_step1, R.string.leopard_phone_step2,
                R.string.leopard_phone_step3 };
        for(int i = 0; i < steps.length; i++) {
            TextView step = new TextView(this);
            step.setText(getString(steps[i]));
            step.setTextSize(11);
            step.setTextColor(ContextCompat.getColor(this, R.color.aurora_muted));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    colW, LinearLayout.LayoutParams.WRAP_CONTENT);
            slp.topMargin = Math.round((i == 0 ? 10 : 3) * d);
            step.setLayoutParams(slp);
            mPhoneQrBox.addView(step);
        }

        // The server lives with this pane: leaving the phone source stops it, and a phone that
        // uploads after that gets nothing but an error it cannot explain. That has already cost
        // one real attempt, so it is stated on the screen rather than left to be discovered.
        TextView warn = new TextView(this);
        warn.setText(R.string.leopard_phone_keep_open);
        warn.setTextSize(11);
        warn.setTypeface(warn.getTypeface(), android.graphics.Typeface.BOLD);
        warn.setTextColor(ContextCompat.getColor(this, R.color.gold));
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                colW, LinearLayout.LayoutParams.WRAP_CONTENT);
        wlp.topMargin = Math.round(9 * d);
        warn.setLayoutParams(wlp);
        mPhoneQrBox.addView(warn);

        // Switching Wi-Fi networks gives the car a different IP while this pane stays on screen,
        // and the code keeps encoding the old one. The server binds every interface, so nothing
        // needs restarting — only the address in the code has to be read again.
        Button refresh = new Button(this);
        refresh.setText(R.string.qr_refresh);
        refresh.setAllCaps(false);
        refresh.setTextSize(11);
        refresh.setOnClickListener(v -> {
            String fresh = mUploadServer == null ? null : mUploadServer.getUrl();
            if(fresh == null) {
                Toast.makeText(this, R.string.pair_no_wifi, Toast.LENGTH_LONG).show();
                return;
            }
            if(fresh.equals(url)) {
                Toast.makeText(this, getString(R.string.qr_refresh_same, fresh), Toast.LENGTH_LONG).show();
                return;
            }
            // Redraw the whole column rather than swapping the bitmap: `url` is what the pane was
            // built from, so a rebuild is what keeps a second tap comparing against the right one.
            buildPhoneQr(fresh);
            Toast.makeText(this, getString(R.string.qr_refresh_done, fresh), Toast.LENGTH_LONG).show();
        });
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                colW, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = Math.round(9 * d);
        refresh.setLayoutParams(rlp);
        mPhoneQrBox.addView(refresh);
    }

    /**
     * The left half: every picture that has come over from a phone, newest first.
     *
     * Newest first because the one someone just sent is the one they are looking for, and this
     * list only ever grows. Where a picture has been through the editor it is the framed version
     * that is listed — that is the one they said yes to, and re-picking it must not quietly hand
     * back the raw photo instead.
     */
    private void buildPhoneGrid() {
        mPhoneGrid.removeAllViews();
        mShown.clear();
        mStripOffset = 0;

        for(File f : phoneUploads()) {
            String url = f.getAbsolutePath();
            mShown.add(new WallpaperItem(WallpaperItem.guessType(url), url));
        }
        // Anything edited anywhere is listed as the picture that edit produced, not as the raw
        // photo it replaced — and anything whose edit has not been burnt in yet is burnt in now.
        applyEdits();

        if(mShown.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.leopard_phone_empty);
            empty.setTextSize(13);
            empty.setTextColor(ContextCompat.getColor(this, R.color.aurora_muted));
            mPhoneGrid.addView(empty);
            return;
        }

        float d = getResources().getDisplayMetrics().density;
        final int cellW = Math.round(190 * d), cellH = Math.round(107 * d);   // 16:9
        final int gap = Math.round(8 * d);
        // The pane has not been measured the first time through, so fall back to the width the
        // band has once the sources and the QR are taken out of it.
        int paneW = mPhoneGrid.getWidth();
        if(paneW <= 0) paneW = getResources().getDisplayMetrics().widthPixels - Math.round(560 * d);
        mPhoneGrid.setColumnCount(Math.max(1, paneW / (cellW + gap)));

        String currentUri = samePath(mPrefs.getString(MediaWallpaperService.PREF_URI, ""));
        for(final WallpaperItem item : mShown) {
            mPhoneGrid.addView(thumbCell(item, cellW, cellH, gap, d, currentUri, false, true));
        }
        refreshSelection();
    }

    /** The grid's own copy of an item, so the selection ring lands on a cell that exists. */
    private WallpaperItem pickFromGrid(WallpaperItem wanted) {
        for(WallpaperItem it : mShown) {
            if(samePath(it.url).equals(samePath(wanted.url))) return it;
        }
        return wanted;
    }

    /** Files in the wallpaper folder that came from a phone, newest first. */
    private List<File> phoneUploads() {
        List<File> out = new ArrayList<>();
        File[] files = mRepo.getLocalFolder().listFiles();
        if(files == null) return out;
        for(File f : files) {
            // UploadServer names every received file "upload_…", which is the only durable mark
            // of where a file came from — nothing else on disk remembers.
            if(f.isFile() && f.getName().startsWith(UploadServer.RECEIVED_PREFIX)) out.add(f);
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        return out;
    }

    /**
     * The framed version of this picture, if it has been through the editor AND still matches the
     * framing saved for it.
     *
     * The freshness test is the whole point. Leopard burns the fit into this file once and then
     * hands the FILE to the head unit, so an edit made anywhere that does not bake — Settings ▸
     * إدارة الصور, most of all — leaves a finished-looking picture on disk that no longer matches
     * what the editor just saved. Without the comparison the car goes on showing the first framing
     * for ever while every later "تم" writes numbers nobody reads: from the seat it looks exactly
     * like an edit that refuses to save. Stale is treated as absent, and
     * {@link #rebakeStaleUploads()} burns it again.
     */
    private File bakedFileFor(String url) {
        File f = new File(new File(getCacheDir(), "leopard"), bakedName(url));
        if(!f.exists() || f.length() == 0) return null;
        return f.lastModified() >= mRepo.fitChangedAt(url) ? f : null;
    }

    /** One file per source image, overwritten on every re-edit: a history here would fill a
     *  head unit's cache with dead wallpapers nobody can see or delete. */
    private static String bakedName(String url) {
        return "edit_" + Integer.toHexString(samePath(url).hashCode()) + ".jpg";
    }

    /** True for a file this screen rendered from an edit — already exactly screen-shaped. */
    private boolean isBaked(String url) {
        if(url == null) return false;
        File f = new File(samePath(url));
        return f.getName().startsWith("edit_")
                && new File(getCacheDir(), "leopard").equals(f.getParentFile());
    }

    private void onPhoneUpload(String savedPath) {
        if(savedPath == null) return;
        toast(R.string.leopard_phone_received);
        mFromPhoneUpload = true;

        WallpaperItem item = new WallpaperItem(WallpaperItem.guessType(savedPath), savedPath);
        // The server outlives the tab, so a picture can land while another source is on screen.
        // Come back to the phone source rather than dropping the new picture into a list it does
        // not belong to.
        if(!SOURCE_PHONE.equals(mSource)) selectSource(SOURCE_PHONE);
        hideState();
        buildPhoneGrid();
        mSelected = pickFromGrid(item);
        refreshSelection();

        // A photo off a phone is portrait as often as not, and the dashboard is extremely wide —
        // dropping it straight onto the screen is where the framing question gets decided badly
        // and silently. So the editor opens first: crop, edges, rotation, then Done brings back
        // the preview with the picture as it will actually land. A video has nothing to frame
        // here (the fit is applied while it plays), so it keeps the old straight-to-preview path.
        if(item.isVideo()) { showPreview(item); return; }
        openFitEditor(item);
    }

    /** Frame this image against the real screen before it is previewed or applied. */
    private void openFitEditor(WallpaperItem item) {
        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        mEditing = item;
        mEditingW = dm.widthPixels;
        mEditingH = dm.heightPixels;
        try {
            startActivityForResult(
                    FitEditorActivity.intent(this, item.url, mEditingW, mEditingH), FIT_EDITOR_REQUEST);
        } catch(ActivityNotFoundException e) {
            // Nothing is lost by skipping it — the unedited image is still a usable wallpaper.
            mEditing = null;
            showPreview(item);
        }
    }

    /**
     * Turn the editor's numbers into a real picture.
     *
     * The editor saves a fit and a focal point keyed by url, which the slideshow reads at draw
     * time. Leopard has no draw time — it hands a finished bitmap to Android's wallpaper system —
     * so unless the framing is burnt into a file here, the editor would be a screen that changes
     * nothing. The result is written screen-sized, which is also all the wallpaper will ever use.
     */
    private void bakeEdit(final WallpaperItem item) {
        final boolean mEditingFromGrid = mFromGridPencil;
        mFromGridPencil = false;
        setBusy(true, R.string.leopard_preparing);
        final int outW = mEditingW, outH = mEditingH;
        final String url = item.url;
        new Thread(() -> {
            final String result = bakeToFile(url, outW, outH);
            runOnUiThread(() -> {
                if(gone()) return;
                setBusy(false, 0);
                // A failed bake is not a failed upload: fall back to the picture as it arrived
                // rather than leaving the user with nothing to set.
                WallpaperItem shown = result == null ? item
                        : new WallpaperItem(WallpaperItem.TYPE_IMAGE, result);
                // Back to the pane the picture came from — the phone grid for a QR upload, the
                // strip for a file browsed on the head unit — and then straight into the preview,
                // which is what the person standing at the head unit is waiting for.
                if(SOURCE_PHONE.equals(mSource)) {
                    buildPhoneGrid();   // rebuilt, so it now holds this one too
                    mSelected = pickFromGrid(shown);
                } else if(mEditingFromGrid) {
                    // A pencil pressed on a picture that is already in the list: the list is what
                    // the technician was working through, so it comes back whole rather than
                    // collapsing to the one picture they touched.
                    showLocal();
                    mSelected = pickFromGrid(shown);
                } else {
                    mShown.clear();
                    mShown.add(shown);
                    mSelected = shown;
                    hideState();
                    buildFilmstrip();
                }
                refreshSelection();
                showPreview(mSelected);
            });
        }).start();
    }

    /**
     * Burn this image's saved framing into one screen-sized JPEG and return its path, or null when
     * it could not be rendered. Blocking — call from a background thread.
     *
     * Always the same file name for the same source (see {@link #bakedName}), so a re-edit
     * overwrites the picture it replaces instead of leaving a second one behind for the grid to
     * choose between.
     */
    private String bakeToFile(String url, int outW, int outH) {
        try {
            String path = localSourceFor(url);
            if(path == null) return null;
            Bitmap src = decodeForBake(path, outW, outH);
            if(src == null) return null;
            Bitmap out = FitRenderer.bake(src, mRepo.getFit(url), mRepo.getFocal(url)[0],
                    mRepo.getFocal(url)[1], outW, outH, false);
            if(src != out) src.recycle();
            if(out == null) return null;
            String baked = null;
            File dir = new File(getCacheDir(), "leopard");
            if(dir.exists() || dir.mkdirs()) {
                File f = new File(dir, bakedName(url));
                FileOutputStream fos = new FileOutputStream(f);
                out.compress(Bitmap.CompressFormat.JPEG, 95, fos);
                fos.close();
                baked = f.getAbsolutePath();
            }
            out.recycle();
            return baked;
        } catch(Throwable t) {
            return null;
        }
    }

    /** One re-bake attempt per (image, framing), so a picture that cannot decode does not spin. */
    private final java.util.Set<String> mRebakeTried = new java.util.HashSet<>();
    private boolean mRebaking;

    /**
     * Show every edited picture as the picture that edit produced, and burn in the ones that have
     * not been burnt in yet.
     *
     * This is what makes an edit made anywhere but here reach the car at all. Leopard and Lynk &amp;
     * Co do not read the fit at draw time — they hand a FILE to the head unit — so Settings ▸
     * إدارة الصور writes the framing and stops: the picker went on listing and applying the raw
     * photo, and the technician who edited, pressed تم and set it again watched the car show the
     * old crop. From the seat that is indistinguishable from an edit that refuses to save.
     *
     * It used to cover phone uploads only, and only ones that already had a bake. Both limits were
     * the bug: a cloud or device picture had no bake to be stale, so editing one changed nothing at
     * all, for ever. Now anything with a framing of its own ({@link WallpaperRepo#fitChangedAt} is
     * the record that it was edited) is burnt in, whichever source it came from — a cloud image is
     * downloaded once to do it. A picture nobody has edited is left alone: baking it would invent a
     * decision nobody made.
     */
    private void applyEdits() {
        mBakedSource.clear();
        final List<String> stale = new ArrayList<>();
        for(int i = 0; i < mShown.size(); i++) {
            final WallpaperItem item = mShown.get(i);
            if(item.isVideo() || isBaked(item.url)) continue;
            final long changed = mRepo.fitChangedAt(item.url);
            if(changed == 0) continue;                     // never edited: nothing to burn in
            File baked = bakedFileFor(item.url);
            if(baked != null) {
                WallpaperItem shown = new WallpaperItem(
                        WallpaperItem.TYPE_IMAGE, baked.getAbsolutePath());
                // The cell still has to know which picture it stands for: the pencil edits the
                // ORIGINAL (the bake is an output, and editing it would stack crop upon crop) and
                // the bin deletes the original with it.
                mBakedSource.put(samePath(shown.url), item.url);
                if(mSelected == item) mSelected = shown;
                mShown.set(i, shown);
            } else if(mRebakeTried.add(item.url + "@" + changed)) {
                // Keyed by the edit, not by the image: a later edit of the same picture must be
                // allowed its own attempt.
                stale.add(item.url);
            }
        }
        if(stale.isEmpty() || mRebaking) return;

        android.util.DisplayMetrics dm = new android.util.DisplayMetrics();
        getWindowManager().getDefaultDisplay().getRealMetrics(dm);
        final int outW = dm.widthPixels, outH = dm.heightPixels;
        mRebaking = true;
        new Thread(() -> {
            for(String url : stale) bakeToFile(url, outW, outH);
            runOnUiThread(() -> {
                mRebaking = false;
                if(gone()) return;
                // Nothing is stale now, so this pass cannot start another one.
                if(SOURCE_PHONE.equals(mSource)) buildPhoneGrid();
                else if(mFilmstripScroll.getVisibility() == View.VISIBLE) buildFilmstrip();
            });
        }).start();
    }

    /** Baked file path → the picture it was made from. Rebuilt on every {@link #applyEdits}. */
    private final java.util.Map<String, String> mBakedSource = new java.util.HashMap<>();

    /** The original behind a listed picture: the raw photo when this one is a burnt-in edit. */
    private String sourceOf(WallpaperItem item) {
        if(item == null) return null;
        String src = mBakedSource.get(samePath(item.url));
        return src != null ? src : item.url;
    }

    /**
     * A real file this url's pixels can be decoded from, fetching a cloud image if that is what it
     * takes. Blocking — call from a background thread.
     */
    private String localSourceFor(String url) {
        if(url == null || url.isEmpty()) return null;
        if(LeopardCache.isRemote(url)) {
            if(LeopardCache.materialise(this, url) == null) return null;
            File f = LeopardCache.cachedFile(this, url);
            return f == null ? null : f.getAbsolutePath();
        }
        // A document uri has no path to decode from. Everything that reaches the editor has been
        // staged to a file first (see stageThenEdit), so this only skips one that never was.
        if(url.startsWith("content://")) return null;
        return samePath(url);
    }

    /** Decode no larger than the screen needs — a 12MP phone photo would blow the heap here. */
    private Bitmap decodeForBake(String path, int outW, int outH) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        if(bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
        // Twice the target, so a zoomed-in crop still has pixels to spend.
        int sample = 1;
        while(bounds.outWidth / (sample * 2) >= outW * 2 && bounds.outHeight / (sample * 2) >= outH * 2) {
            sample *= 2;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        // Same EXIF turn the fit editor applies to its preview, for the same reason: the bake
        // has to start from the picture the technician framed, not from the untouched pixels.
        return ExifOrientation.apply(BitmapFactory.decodeFile(path, opts), path);
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
        releaseTilePlayers();
        super.onDestroy();
    }

    private void showCloud() {
        mShown.clear();
        // Cloud circle = cloud only. allItems() is the local folder AND the synced manifest
        // merged together, which is what the slideshow wants but not what this says on the tin:
        // the circle next to it is literally called "from the device".
        //
        // The per-car hide list IS honoured, and that is a correction rather than a preference.
        // Editing a cloud image in Settings ▸ إدارة الصور cannot edit the cloud: it copies the
        // picture onto the car, moves the framing onto the copy and hides the original. Ignoring
        // the hide list therefore left this list offering the untouched original — while the
        // edited copy sat on the device source — so the edit read as lost. What the owner
        // unticked, this car does not offer.
        //
        // The type filter is applied HERE, on the drawn list only. "Is there anything in the
        // cloud at all" is tracked separately from "is there anything left after the filter":
        // the first decides whether to sync, the second only what the empty band says.
        boolean anyCloud = false;
        for(WallpaperItem it : mRepo.visibleItems()) {
            if(!LeopardCache.isRemote(it.url)) continue;
            anyCloud = true;
            if(passesTypeFilter(it)) mShown.add(it);
        }

        // Refresh once per open so images deleted (or added) on the manager propagate to the car
        // instead of the cached list lingering until the cache happens to empty. An empty box
        // shows the spinner as before; a cached box shows its images immediately and refreshes
        // quietly underneath, so a wallpaper removed from the website disappears on the next open.
        if(!mTriedAutoSync) {
            mTriedAutoSync = true;
            if(!anyCloud) {
                refreshCloud();
                return;
            }
            showCloudGrid(anyCloud);
            silentRefresh();
            return;
        }

        showCloudGrid(anyCloud);
    }

    /** The cloud band after {@link #showCloud} has decided the list: cells, or the right empty message. */
    private void showCloudGrid(boolean anyCloud) {
        if(!anyCloud) {
            showState(R.string.leopard_empty, R.string.leopard_empty_action, v -> refreshCloud());
            return;
        }
        if(mShown.isEmpty()) {
            // The library is not empty, the filter is: say that, and do not offer a sync that
            // would change nothing.
            showState(R.string.leopard_filter_empty, 0, null);
            return;
        }
        hideState();
        buildFilmstrip();
    }

    /** True if the type filter lets this item onto the cloud grid. Anything not a video is an image (GIFs included). */
    private boolean passesTypeFilter(WallpaperItem it) {
        if(mShowVideos == mShowImages) return true;      // none or both lit: no filtering
        return it.isVideo() ? mShowVideos : mShowImages;
    }

    /**
     * One of the two type toggles was tapped. Flip it, redraw the cloud grid from the cache —
     * no sync, no download — and drop a selection the filter just hid, or "Set" would apply a
     * picture that is no longer on screen.
     */
    private void toggleTypeFilter(boolean video) {
        if(video) mShowVideos = !mShowVideos; else mShowImages = !mShowImages;
        mFilterVideo.setSelected(mShowVideos);
        mFilterImages.setSelected(mShowImages);
        if(!SOURCE_CLOUD.equals(mSource)) return;
        if(mSelected != null && !passesTypeFilter(mSelected)) mSelected = null;
        showCloud();
    }

    /** Sync without disturbing the visible strip; on success re-read the list so anything deleted
     *  on the manager drops out and anything new appears. Failures are ignored — the cache stands. */
    /**
     * The server just answered, and the answer was "this car is not activated" (the
     * {@code inactive} sentinel — the car was blocked from the dashboard, or its row was
     * removed). sync() has already cleared the stored flag; what it cannot do is take this
     * screen down. Without this a blocked Leopard car sat on an empty picker saying "no
     * wallpapers yet" with nothing to tell the driver why — while every other program in the
     * fleet was already showing the blocked screen. FullscreenActivity carries the activation
     * overlay (blocked banner + support QR), exactly what a cold start would have shown.
     *
     * Only a SERVER answer gets here: sync() reports success=false on any network failure,
     * and the flag is never written on failure, so an offline car keeps its picker.
     */
    private boolean standDownIfInactive(boolean success) {
        if(!success || mRepo.isActive()) return false;
        startActivity(new Intent(this, FullscreenActivity.class));
        finish();
        return true;
    }

    private void silentRefresh() {
        mLastSyncAskedMs = android.os.SystemClock.elapsedRealtime();
        mRepo.sync(new WallpaperRepo.SyncCallback() {
            @Override
            public void done(boolean success, int count, String error) {
                runOnUiThread(() -> {
                    if(standDownIfInactive(success)) return;
                    if(success) showCloud();
                });
            }
            @Override
            public void mediaReady() { runOnUiThread(LeopardPickerActivity.this::onMediaCached); }
            @Override
            public void mediaIncomplete(int failed, int total) {
                // Whatever did arrive still deserves a real cell instead of a placeholder.
                runOnUiThread(LeopardPickerActivity.this::onMediaCached);
            }
        });
    }

    private void refreshCloud() {
        mProgress.setVisibility(View.VISIBLE);
        hideState();
        mLastSyncAskedMs = android.os.SystemClock.elapsedRealtime();
        mRepo.sync(new WallpaperRepo.SyncCallback() {
            @Override
            public void done(boolean success, int count, String error) {
                runOnUiThread(() -> {
                    mProgress.setVisibility(View.GONE);
                    if(standDownIfInactive(success)) return;
                    if(success) { showCloud(); return; }   // sync() reloads the list itself
                    // An offline car may still have a usable library: the last good response is
                    // cached and videos are pre-downloaded. "Offline, showing cached" and
                    // "offline with nothing" are different situations and must not share one
                    // message.
                    if(hasCloudItems()) { showCloud(); toast(R.string.leopard_offline_cached); }
                    else showState(R.string.leopard_offline_empty, R.string.leopard_retry,
                            v -> refreshCloud());
                });
            }
            @Override
            public void mediaReady() { runOnUiThread(LeopardPickerActivity.this::onMediaCached); }
            @Override
            public void mediaIncomplete(int failed, int total) {
                // Whatever did arrive still deserves a real cell instead of a placeholder.
                runOnUiThread(LeopardPickerActivity.this::onMediaCached);
            }
        });
    }

    /**
     * The clips have finished downloading, so the cells that had nothing to show now do.
     *
     * sync() reports the playlist before it fetches the media — it has to, or every screen
     * waiting on it freezes for the length of the download — which means the grid is built while
     * some videos are still coming down and those cells draw a flat placeholder with no still
     * frame and no movement. This is the second half arriving.
     */
    private void onMediaCached() {
        if(gone()) return;
        if(mFilmstripScroll == null || mFilmstripScroll.getVisibility() != View.VISIBLE) return;
        if(mStateBox != null && mStateBox.getVisibility() == View.VISIBLE) return;
        if(mPreview != null && mPreview.getVisibility() == View.VISIBLE) return;
        if(!SOURCE_CLOUD.equals(mSource)) return;
        buildFilmstrip();
    }

    /** Cached cloud wallpapers only — local files are not this circle's business. */
    private boolean hasCloudItems() {
        for(WallpaperItem it : mRepo.visibleItems()) if(LeopardCache.isRemote(it.url)) return true;
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
        // The visible list, for the same reason the cloud circle uses it: what the owner unticked
        // in Settings ▸ إدارة الصور, this car does not offer.
        for(WallpaperItem it : mRepo.visibleItems()) {
            if(LeopardCache.isRemote(it.url)) continue;
            // Phone uploads live on this same disk but belong to the phone source, where they
            // are shown next to the code that produced them. Listing them twice would make the
            // two circles look like copies of each other.
            if(new File(it.url).getName().startsWith(UploadServer.RECEIVED_PREFIX)) continue;
            mShown.add(it);
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

        if(req == FIT_EDITOR_REQUEST) {
            WallpaperItem edited = mEditing;
            mEditing = null;
            if(edited == null) return;
            // The editor is auto-save and has no Cancel, so any way back from it means "this is
            // how I want it" — including the back button. Bake either way; the only difference on
            // a plain back is that the settings are the ones it opened with. Either way lands on
            // the preview, where the Set button waits — same as every other source (cloud, local
            // file). "تم" used to skip straight to applying, but a still on these head units can
            // take a moment to actually render (see LeopardApplier), so a silent auto-apply here
            // read as "I pressed Done and the app just closed" rather than "it's set". Landing on
            // the full preview instead gives an explicit, visible Set button to press.
            bakeEdit(edited);
            return;
        }

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

        // A picture browsed on the head unit is in exactly the same position as one that arrived
        // from a phone: it has just entered the app, nobody has decided how it should sit on a
        // screen this wide, and dropping it straight into the preview is where that question gets
        // answered badly and silently. So it takes the same route — editor, then preview with the
        // Set button. A video skips it, as it does on the phone side (there is no still to frame).
        if(!item.isVideo()) stageThenEdit(uri);
    }

    /**
     * Copy a browsed content:// image to a real file, then hand it to the fit editor.
     *
     * The editor reads files and nothing else — it decodes with BitmapFactory.decodeFile — and
     * the bake that follows re-reads the same path on a background thread. A document uri is
     * neither: its grant can be revoked and its stream is not seekable twice. One copy, named
     * after the uri so re-picking the same picture reuses it and keeps the framing set last time.
     */
    private void stageThenEdit(final Uri uri) {
        setBusy(true, R.string.leopard_preparing);
        new Thread(() -> {
            final String path = stageForEdit(uri);
            runOnUiThread(() -> {
                if(gone()) return;
                setBusy(false, 0);
                // Could not copy it: the preview is already up with the picture as picked, which
                // is still perfectly settable. Losing the editor is not worth losing the import.
                if(path == null) return;
                openFitEditor(new WallpaperItem(WallpaperItem.TYPE_IMAGE, path));
            });
        }).start();
    }

    /** @return the staged file's path, or null when the picture could not be copied. */
    private String stageForEdit(Uri uri) {
        InputStream in = null;
        FileOutputStream out = null;
        try {
            File dir = new File(getCacheDir(), "leopard");
            if(!dir.exists() && !dir.mkdirs()) return null;
            // Always .jpg: everything downstream decodes by content, and the bake writes JPEG
            // anyway — the name only has to be stable and to read as an image.
            File dest = new File(dir, "pick_" + Integer.toHexString(uri.toString().hashCode()) + ".jpg");
            File tmp = new File(dir, dest.getName() + ".part");
            in = getContentResolver().openInputStream(uri);
            if(in == null) return null;
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[64 * 1024];
            int n;
            while((n = in.read(buf)) > 0) out.write(buf, 0, n);
            out.flush();
            out.close();
            out = null;
            // Written aside and moved into place, so a copy cut short by a card being pulled
            // leaves the previous staging (and the fit set on it) rather than a truncated file.
            if(tmp.length() == 0) { //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            }
            if(dest.exists()) //noinspection ResultOfMethodCallIgnored
                dest.delete();
            return tmp.renameTo(dest) ? dest.getAbsolutePath() : null;
        } catch(Throwable t) {
            return null;
        } finally {
            if(in != null) try { in.close(); } catch(Exception ignored) {}
            if(out != null) try { out.close(); } catch(Exception ignored) {}
        }
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

    /**
     * True once this screen is on its way out.
     *
     * Everything that applies a wallpaper runs on a background thread and comes back through
     * runOnUiThread, and the picker can be gone by then: "close after set" finishes it 900ms
     * after an apply, and a video apply hands over to the system live-wallpaper screen entirely.
     * Glide refuses to start a load against a destroyed activity — it throws — so a callback
     * that repaints the strip was killing the app right after a successful apply
     * (3 crashes on one car in 15 minutes, LeopardPickerActivity.buildFilmstrip → thumbCell).
     * Repainting a screen nobody is looking at has no value, so the callbacks just stand down.
     */
    private boolean gone() {
        return isFinishing() || isDestroyed();
    }

    private void buildFilmstrip() {
        if(gone()) return;
        // Same as the phone pane: what is listed is the picture the last edit produced, and an
        // edit that has not been burnt into a file yet is burnt in now.
        applyEdits();
        // The cells about to be discarded own the grid's video players; drop them first or each
        // rebuild strands another set of decoders.
        releaseTilePlayers();
        mTilesReleased = false;
        mFilmstrip.removeAllViews();
        // Whatever the phone pane was showing, this is the strip's band now.
        mPhonePane.setVisibility(View.GONE);
        mFilmstripScroll.setVisibility(View.VISIBLE);
        float d = getResources().getDisplayMetrics().density;
        int gap = Math.round(16 * d);

        // Two columns, so WIDTH is the fixed dimension and the height follows each picture's own
        // proportions (see fitCellToImage) — the opposite of the strip this replaced.
        final int columns = 2;
        int paneW = mFilmstrip.getWidth();
        if(paneW <= 0) {
            // Nothing has been measured yet on the very first pass. Draw with a rough guess so
            // the band is not empty, then rebuild once for real: this screen runs on panels from
            // 1280x640 to 5120x1600, and any constant wide enough for one is wrong for another.
            // The guess is the screen less the two 210dp source rails that flank this band.
            paneW = getResources().getDisplayMetrics().widthPixels - Math.round(420 * d);
            awaitGridWidth();
        }
        // Whatever is left, split in two. Deliberately no lower bound beyond a sanity floor: a
        // minimum wide enough to be comfortable would, on the narrow panels this mode runs on,
        // make two columns wider than the band they sit in — and cells running off the edge are
        // worse than small ones, because the second column becomes unreachable.
        int cellW = Math.max(Math.round(72 * d), (paneW - gap * (columns + 1)) / columns);
        // 2.67:1 is the shape of the screen the wallpaper lands on: the right guess to draw
        // before the real proportions are known, and what a cell keeps if they never arrive.
        int cellH = Math.round(cellW / 2.67f);
        // The same local file reaches us under two spellings — "file:///storage/…" from the phone
        // upload, bare "/storage/…" from the folder scan — so compare them stripped, or the
        // "Current" badge never lands on the picture that is actually on screen.
        String currentUri = samePath(mPrefs.getString(MediaWallpaperService.PREF_URI, ""));

        final int radius = Math.round(16 * d);   // must match the corners in leopard_cell_bg

        // The device source keeps the old behaviour available as its first cell: the list is what
        // you normally want, browsing the file system is the exception.
        mFilmstrip.setColumnCount(columns);
        mStripOffset = SOURCE_LOCAL.equals(mSource) ? 1 : 0;
        if(mStripOffset == 1) mFilmstrip.addView(browseCell(cellW, cellH, gap, d, radius));

        for(final WallpaperItem item : mShown) {
            // The device source is this car's own storage too, so a file browsed onto the head
            // unit gets the same pencil and bin as a photo that came off a phone. Cloud pictures
            // do not: they belong to the fleet, and neither editing in place nor deleting them
            // here would mean anything.
            mFilmstrip.addView(thumbCell(item, cellW, cellH, gap, d, currentUri, true,
                    SOURCE_LOCAL.equals(mSource)));
        }
        refreshSelection();
        // Nothing has a size yet — the tiles can only be matched to the viewport once the grid
        // has been laid out, which is a frame or two away.
        scheduleTileBind();
    }

    /**
     * One tappable picture, sized {@code cellW} x {@code cellH}.
     *
     * Shared by the strip and the phone grid: the two arrange cells differently but a cell is a
     * cell, and having built it twice once already, the second copy is where the badge or the
     * selection ring quietly stops matching.
     */
    private View thumbCell(final WallpaperItem item, final int cellW, final int cellH,
                           final int gap, float d, String currentUri, final boolean shapeToImage,
                           final boolean actions) {
        final int radius = Math.round(16 * d);
        final FrameLayout cell = new FrameLayout(this);
        cell.setFocusable(true);
        cell.setClickable(true);
        cell.setBackgroundColor(0xFF241c11);

        // The size goes on BEFORE anything is loaded into the cell, and that ordering is the whole
        // fix for a frame that did not match the picture inside it. Glide answers a memory-cache
        // hit synchronously, inside the .into() call below — so on any rebuild, once the thumbnails
        // were cached, fitCellToImage ran while this view still had no LayoutParams at all, found
        // nothing to resize, and gave up. Every cell then kept the 2.67:1 placeholder shape and
        // every picture that was not 2.67:1 sat letterboxed inside its own border.
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = cellW;
        lp.height = cellH;
        lp.setMargins(0, 0, gap, gap);
        cell.setLayoutParams(lp);

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
        // Two different jobs. In the strip the cell is reshaped to the picture, so FIT_CENTER
        // has nothing left to letterbox and the frame ends where the photo ends. In the grid
        // the cells must stay on their rows, so the picture fills its box instead and takes a
        // small crop — a ragged grid is worse than a trimmed thumbnail, and a tap opens the
        // whole picture anyway.
        img.setScaleType(shapeToImage ? ImageView.ScaleType.FIT_CENTER
                : ImageView.ScaleType.CENTER_CROP);
        cell.addView(img);

        if(item.isVideo()) {
            // The playlist pre-downloads videos for smooth looping, so when the file is
            // already cached we can pull frame 0 out of it locally. Only a video that has
            // not been fetched yet falls back to the flat placeholder.
            String local = videoFile(item);
            if(local == null) {
                // Nothing to draw yet. Say so — a spinner is "coming", a bare brown box is
                // "broken" — and put the clip on this screen's own download queue so the cell
                // fills itself in instead of waiting for a sync that may never come.
                cell.addView(cellSpinner(d));
                queueVideoFetch(item);
            }
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
                                if(shapeToImage) fitCellToImage(cell, res.getWidth(), res.getHeight());
                                return false;
                            }
                        })
                        .into(img);
                // ...and then, in the library grid, play it in place. A still frame 0 is often a
                // black fade-in and says nothing about the clip; the whole reason to choose a
                // video is the movement, so it has to be visible without committing to it first.
                // The library only — the phone pane is a short list of things you already know.
                if(shapeToImage) mVideoTiles.add(new VideoTile(cell, local));
            }
            cell.addView(typeBadge(R.drawable.ic_badge_video_20dp, R.id.leopardBadgeVideo, d));
        } else {
            // Full-size originals over a weak link: let Glide downsample to the cell.
            Glide.with(this).load(glideModel(item.url))
                    .signature(freshness(item.url))
                    .override(cellW, cellH)
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
                            if(shapeToImage) {
                                fitCellToImage(cell, res.getIntrinsicWidth(), res.getIntrinsicHeight());
                            }
                            return false;
                        }
                    })
                    .into(img);
            cell.addView(typeBadge(R.drawable.ic_badge_image_20dp, R.id.leopardBadgeImage, d));
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

        // The pencil and the bin, over the ring so they stay reachable on a selected cell.
        // Only a picture that is really a file on this car: a document uri picked a second ago is
        // neither editable (the editor decodes files) nor ours to delete.
        if(actions && !item.isVideo() && isLocalFile(item.url)) cell.addView(actionBar(item, d));

        // Tap opens the full-screen preview rather than just ticking the cell: a 326x122
        // thumbnail is too small to commit a whole dashboard to.
        cell.setOnClickListener(v -> { mSelected = item; refreshSelection(); showPreview(item); });
        return cell;
    }

    /**
     * Edit and delete, on the picture itself.
     *
     * A photo that came off a customer's phone is the one picture on this screen that is genuinely
     * this car's own: it is usually crooked or portrait, it is often wrong, and the two things
     * anybody wants to do with it — re-frame it, or get rid of it — used to live in Settings ▸
     * إدارة الصور, four taps away behind a screen that is not even about this picture. Here they
     * are where the picture is.
     *
     * Both act at once. The bin does not ask: this is a photo that arrived a minute ago on a car
     * with a customer standing at it, the file is gone from the grid before the finger is off the
     * glass, and a confirmation dialog for something that trivial is the tax the old route already
     * charged. The pencil opens the fit editor on the ORIGINAL photo — never on the burnt-in copy,
     * or every edit would crop the crop — and the way back re-burns it, so the framing is on the
     * picture in the grid before the editor has finished closing.
     */
    private View actionBar(final WallpaperItem item, float d) {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.setMargins(Math.round(8 * d), Math.round(8 * d), 0, 0);
        bar.setLayoutParams(lp);
        bar.addView(actionButton(R.drawable.ic_edit_20dp, R.string.wallpaper_edit, d,
                v -> editListed(item)));
        bar.addView(actionButton(R.drawable.ic_delete_24dp, R.string.wallpaper_delete, d,
                v -> deleteListed(item)));
        return bar;
    }

    /** One round icon button, sized for a finger in a car rather than for a mouse. */
    private View actionButton(int icon, int label, float d, View.OnClickListener click) {
        ImageView v = new ImageView(this);
        int size = Math.round(40 * d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size);
        lp.setMarginEnd(Math.round(8 * d));
        v.setLayoutParams(lp);
        v.setBackgroundResource(R.drawable.leopard_badge_circle);
        int pad = Math.round(9 * d);
        v.setPadding(pad, pad, pad, pad);
        v.setImageResource(icon);
        v.setContentDescription(getString(label));
        v.setFocusable(true);
        v.setClickable(true);
        v.setOnClickListener(click);
        return v;
    }

    /** Set while the editor was opened by a cell's pencil rather than by an import. */
    private boolean mFromGridPencil;

    /** Re-frame this picture — the photo it was made from, when it is already a burnt-in edit. */
    private void editListed(WallpaperItem item) {
        String src = sourceOf(item);
        if(src == null || src.isEmpty()) return;
        mFromGridPencil = true;
        mSelected = item;
        refreshSelection();
        openFitEditor(new WallpaperItem(WallpaperItem.TYPE_IMAGE, src));
    }

    /** Delete it now, picture and burnt-in copy alike, and redraw the pane it was in. */
    private void deleteListed(WallpaperItem item) {
        final String src = sourceOf(item);
        if(src == null || src.isEmpty()) return;
        // The bake is an output of this photo and must not outlive it — left behind it would be a
        // finished-looking picture in the cache with nothing on the car it belongs to.
        File baked = new File(new File(getCacheDir(), "leopard"), bakedName(src));
        if(baked.exists()) //noinspection ResultOfMethodCallIgnored
            baked.delete();
        if(!mRepo.deleteLocal(src)) { toast(R.string.wallpaper_delete_failed); return; }
        if(mSelected == item) {
            mSelected = null;
            hidePreview();
        }
        if(SOURCE_PHONE.equals(mSource)) buildPhoneGrid();
        else showLocal();
        toast(R.string.wallpaper_delete_done);
    }

    /**
     * Play a cached clip inside its grid cell, muted and looping.
     *
     * Layered OVER the still frame rather than replacing it: everything that can go wrong here —
     * no decoder free, a codec the ROM cannot open, a file still being written — ends with this
     * view never becoming visible and the thumbnail that was already there carrying on. A tile
     * that quietly does not move is a small loss; a tile that goes black is a wallpaper nobody
     * will pick.
     *
     * setOpaque(false) is what makes that work, and it is the whole trick: a translucent
     * TextureView with no frames yet draws NOTHING, so the thumbnail underneath simply stays on
     * screen until there is genuinely something better to show. Left opaque it would paint solid
     * black while it waited.
     *
     * It must also stay VISIBLE the entire time. An earlier version started it INVISIBLE and
     * revealed it on the first frame, which sounds safer and is in fact fatal: a TextureView that
     * is not drawn never creates its SurfaceTexture, so onSurfaceTextureAvailable never fires,
     * the player is never given a surface, and not one tile in the grid ever moves.
     *
     * The cell is already shaped to the clip's own proportions by {@link #fitCellToImage}, so
     * filling it is the right framing and no aspect maths is needed here.
     */
    private void startTile(final VideoTile tile) {
        final String path = tile.path;
        final MediaPlayer mp = new MediaPlayer();
        tile.player = mp;

        final TextureView tv = new TextureView(this);
        tv.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        tv.setOpaque(false);
        tv.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture st, int w, int h) {
                if(gone()) return;
                try {
                    mp.setSurface(new Surface(st));
                    mp.setDataSource(path);
                    mp.setLooping(true);
                    // Silent, like the wallpaper itself. A grid of clips that all started talking
                    // at once would be its own bug report.
                    mp.setVolume(0f, 0f);
                    mp.setOnPreparedListener(p -> {
                        if(gone()) return;
                        try { p.start(); } catch(Throwable ignored) { }
                    });
                    mp.setOnErrorListener((p, what, extra) -> true);   // still frame stays up
                    mp.prepareAsync();
                } catch(Throwable ignored) {
                    // Leave the tile invisible: the thumbnail underneath is the fallback.
                }
            }
            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture st) { return true; }
            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture st, int w, int h) { }
            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture st) { }
        });
        tile.surface = tv;
        // Index 1: straight above the still and below everything else, because the type badge,
        // the "current" badge and the selection ring all have to stay on top of a moving picture.
        // (Appending would have put the clip over its own border.)
        tile.cell.addView(tv, 1);
    }

    /** Hand one tile's decoder back and let its still frame show through again. */
    private void stopTile(VideoTile tile) {
        if(tile.player != null) {
            try {
                tile.player.reset();
                tile.player.release();
            } catch(Throwable ignored) { }
            tile.player = null;
        }
        if(tile.surface != null) {
            tile.cell.removeView(tile.surface);
            tile.surface = null;
        }
    }

    /**
     * Lend the small pool of decoders to the clips that are actually on screen.
     *
     * Runs after a scroll, after the grid is laid out, after a cell is reshaped, and on the way
     * back in from another app. Cheap enough to run often — it is a walk over the video cells and
     * a rectangle test each — and everything it does is idempotent, so an extra pass costs
     * nothing.
     *
     * "Visible" is deliberately generous (any part of the cell in the viewport) but ordered by
     * position, so when more clips are on screen than there are slots the ones nearest the top
     * get them. Half a moving tile at the bottom edge is still an invitation to scroll.
     */
    private void bindVisibleTiles() {
        if(gone() || mTilesReleased || mVideoTiles.isEmpty()) return;
        // Not while the library is not what the screen is showing: the phone pane, a "nothing
        // here" box and the full-screen preview all want the decoders more than the grid does.
        if(mFilmstripScroll == null || mFilmstripScroll.getVisibility() != View.VISIBLE
                || (mStateBox != null && mStateBox.getVisibility() == View.VISIBLE)
                || (mPreview != null && mPreview.getVisibility() == View.VISIBLE)) {
            for(VideoTile t : mVideoTiles) stopTile(t);
            return;
        }

        final Rect r = new Rect();
        List<VideoTile> onScreen = new ArrayList<>();
        for(VideoTile t : mVideoTiles) {
            boolean shown = t.cell.getWidth() > 0 && t.cell.isShown() && t.cell.getGlobalVisibleRect(r);
            if(shown) onScreen.add(t);
            else if(t.player != null) stopTile(t);   // scrolled away: give the decoder back first
        }

        int live = 0;
        for(VideoTile t : mVideoTiles) if(t.player != null) live++;
        for(VideoTile t : onScreen) {
            if(live >= MAX_LIVE_TILES) break;
            if(t.player != null) continue;
            startTile(t);
            live++;
        }
    }

    /**
     * Coalesce a burst of callbacks into one pass.
     *
     * A drag fires a scroll callback per frame and a grid whose cells are being reshaped by Glide
     * fires a layout change per picture. Starting and stopping decoders at that rate is how a
     * head unit stutters, so the work waits for the burst to end.
     */
    private void scheduleTileBind() {
        if(mFilmstrip == null) return;
        mFilmstrip.removeCallbacks(mBindTiles);
        mFilmstrip.postDelayed(mBindTiles, 180);
    }

    /**
     * Hand every decoder back.
     *
     * Called before every rebuild and whenever this screen stops being the foreground. The grid
     * is rebuilt often — a sync, an apply, the one-shot width measure — and each rebuild throws
     * the old cells away; without this the players they owned would leak, and after a few
     * rebuilds nothing on the car could open a video at all.
     */
    private void releaseTilePlayers() {
        if(mFilmstrip != null) mFilmstrip.removeCallbacks(mBindTiles);
        for(VideoTile t : mVideoTiles) stopTile(t);
        mVideoTiles.clear();
    }

    /**
     * Put the library's video tiles back after a pause.
     *
     * onPause hands the decoders back, which leaves those cells frozen on their still frames —
     * right while another app is in front, wrong the moment this screen returns. The cells
     * themselves were never touched, so coming back is only a matter of lending the players out
     * again: no rebuild, which also means the scroll position and everything Glide had already
     * drawn survive the trip.
     */
    private void restartTilePlayers() {
        if(!mTilesReleased) return;
        mTilesReleased = false;
        scheduleTileBind();
    }

    /**
     * What Glide should load for a wallpaper url.
     *
     * The phone upload spells a local file "file:///storage/…" while the folder scan spells the
     * same file "/storage/…", and handing the first spelling to {@code new File(...)} produces a
     * path that cannot exist — which is exactly why a picture that had just arrived from a phone
     * previewed as a black rectangle.
     */
    private Object glideModel(String url) {
        if(url == null) return null;
        if(url.startsWith("content://")) return Uri.parse(url);
        if(url.startsWith("http")) return url;
        return new File(samePath(url));
    }

    /**
     * A cache key that changes when the file behind it does.
     *
     * Glide keys a local file by its path alone, and the fit editor deliberately keeps ONE baked
     * file per source image, overwritten on every re-edit — so the same path names a different
     * picture each time somebody changes the framing. Without this the grid cell and the
     * full-screen preview both go on showing the previous bake, which reads exactly like an edit
     * that was not saved.
     */
    private ObjectKey freshness(String url) {
        if(url == null || url.startsWith("content://") || url.startsWith("http")) {
            return new ObjectKey("");
        }
        return new ObjectKey(new File(samePath(url)).lastModified());
    }

    /**
     * "This is a video" / "this is a photo", bottom-left of a cell.
     *
     * An icon rather than the word: at 326x122 over a busy photo a text badge is a smear, and
     * the two glyphs are told apart at a glance across a cabin in a way two Arabic words are not.
     */
    private ImageView typeBadge(int iconRes, int id, float d) {
        ImageView v = new ImageView(this);
        v.setId(id);   // shared by every cell of that type; see ids.xml for why it exists
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

    /** A quiet "this is on its way", centred in a cell that has no frame to show yet. */
    private View cellSpinner(float d) {
        ProgressBar p = new ProgressBar(this);
        int size = Math.round(32 * d);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(size, size);
        lp.gravity = Gravity.CENTER;
        p.setLayoutParams(lp);
        p.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return p;
    }

    /**
     * The file this clip can be drawn from, under either of the two names it may be cached as.
     *
     * There are two video caches and they are not interchangeable. The playlist keeps its own
     * ({@code cache/wallpapers/vid_*.bin}, keyed by wallpaper identity so a host move does not
     * cost every car 50 MB a clip) and the apply path keeps another ({@code cache/leopard/wp_*.mp4},
     * which must carry a real extension or the wallpaper engine reads no MIME and draws black).
     * The grid only ever asked the first one — so previewing a video, which fills the second,
     * left the cell behind it exactly as blank as before. Either copy is a perfectly good source
     * for a thumbnail and for a tile, so ask both.
     */
    private String videoFile(WallpaperItem item) {
        if(item == null || !item.isVideo()) return null;
        String local = mRepo.localVideoPath(item);
        if(local != null) return local;
        File f = LeopardCache.cachedFile(this, item.url);
        return f == null ? null : f.getAbsolutePath();
    }

    /** Put a missing clip on the queue and make sure something is working through it. */
    private void queueVideoFetch(WallpaperItem item) {
        if(item == null || !LeopardCache.isRemote(item.url)) return;
        if(mVideoFetchFailed.contains(item.url)) return;
        mVideoQueue.add(item.url);
        pumpVideoFetch();
    }

    /**
     * Fetch the next queued clip, then repaint the grid so its cell stops being a placeholder.
     *
     * Strictly one at a time. These are the biggest files the app moves and the link they move
     * over is a car's — three at once would make all three slow and starve the preview the owner
     * is probably waiting on.
     */
    private void pumpVideoFetch() {
        if(mFetchingVideo || gone() || mVideoQueue.isEmpty()) return;
        final String url = mVideoQueue.iterator().next();
        mVideoQueue.remove(url);
        mFetchingVideo = true;
        new Thread(() -> {
            final boolean ok = LeopardCache.materialise(LeopardPickerActivity.this, url) != null;
            runOnUiThread(() -> {
                mFetchingVideo = false;
                if(gone()) return;
                if(!ok) {
                    // One failure per screen: the owner reopening the picker is the retry.
                    mVideoFetchFailed.add(url);
                } else if(mFilmstripScroll != null
                        && mFilmstripScroll.getVisibility() == View.VISIBLE
                        && (mPreview == null || mPreview.getVisibility() != View.VISIBLE)) {
                    // Rebuilding re-queues whatever is still missing, so this is also what keeps
                    // the queue moving through the rest of them.
                    buildFilmstrip();
                }
                pumpVideoFetch();
            });
        }).start();
    }

    /**
     * Narrow (or widen) a cell so its frame ends exactly where the picture does.
     *
     * Every cell used to be 2.67:1, the shape of the car's screen, and the photo was fitted
     * inside it — so a 16:9 wallpaper sat in the middle of its frame with a bar of dead
     * background down each side. The border was drawn around the slot, not around the image.
     *
     * In the two-column grid the width is what the column dictates, so the HEIGHT is the one free
     * dimension — the reverse of the strip this replaced, where the band's height was fixed and
     * the width moved instead.
     */
    private void fitCellToImage(View cell, int imgW, int imgH) {
        if(imgW <= 0 || imgH <= 0) return;
        ViewGroup.LayoutParams lp = cell.getLayoutParams();
        if(lp == null || lp.width <= 0) return;
        // The clamps keep one odd picture from making a row three times the height of its
        // neighbours: GridLayout gives a whole row the tallest cell in it, so an unbounded
        // panorama or portrait would leave a band of empty cell beside it.
        int h = Math.round(lp.width * (imgH / (float) imgW));
        h = Math.max(Math.round(lp.width * 0.30f), Math.min(Math.round(lp.width * 1.20f), h));
        if(h == lp.height) return;
        lp.height = h;
        cell.setLayoutParams(lp);
        // Every cell that reshapes moves the ones under it, so what is on screen has changed.
        scheduleTileBind();
    }

    /**
     * Rebuild the grid the first time it has a real width, then stop listening.
     *
     * One shot on purpose: buildFilmstrip() is what registers this, so a listener that stayed
     * attached would rebuild on the layout its own rebuild causes, forever.
     */
    private void awaitGridWidth() {
        if(mAwaitingGridWidth) return;
        mAwaitingGridWidth = true;
        mFilmstrip.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b,
                                       int ol, int ot, int or_, int ob) {
                if(v.getWidth() <= 0) return;
                v.removeOnLayoutChangeListener(this);
                mAwaitingGridWidth = false;
                if(gone()) return;
                v.post(() -> { if(!gone()) buildFilmstrip(); });
            }
        });
    }

    /** "Browse the device" — the old system-picker route, now a cell instead of the whole tab. */
    private View browseCell(int cellW, int cellH, int gap, float d, final int radius) {
        FrameLayout cell = new FrameLayout(this);
        // It takes a whole column like any other cell, but a shorter one: it is an action, not a
        // wallpaper, and giving it a picture's height would read as one of the images.
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
        lp.width = cellW;
        lp.height = Math.max(Math.round(64 * d), Math.round(cellH * 0.7f));
        lp.setMargins(0, 0, gap, gap);
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

    /** True for a url that names a file on this car, whichever way it is spelled. */
    private static boolean isLocalFile(String url) {
        return url != null && !url.isEmpty()
                && !url.startsWith("content://") && !url.startsWith("http");
    }

    /** One spelling for a local file, so "file:///storage/x" and "/storage/x" compare equal. */
    private static String samePath(String url) {
        if(url == null) return "";
        return url.startsWith("file://") ? url.substring("file://".length()) : url;
    }

    private void refreshSelection() {
        // Whichever container is showing holds the cells; mShown is the same list either way.
        ViewGroup cells = mPhonePane.getVisibility() == View.VISIBLE ? mPhoneGrid : mFilmstrip;
        // Cells and items are no longer 1:1 — the device strip opens with a browse cell that
        // stands for no wallpaper, so every item sits mStripOffset places to the right.
        for(int i = 0; i < cells.getChildCount(); i++) {
            int item = i - mStripOffset;
            cells.getChildAt(i).setSelected(
                    item >= 0 && item < mShown.size() && mShown.get(item) == mSelected);
        }
        mSetButton.setAlpha(mSelected == null ? 0.5f : 1f);
    }

    // ---------------------------------------------------------------- preview

    /** Show the chosen wallpaper full-screen before committing it. */
    private void showPreview(WallpaperItem item) {
        if(gone()) return;   // reached from the bake thread too — see gone()
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
            Glide.with(this).load(glideModel(item.url))
                    .signature(freshness(item.url))
                    .into(mPreviewImage);
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
                        // The cell behind this preview was drawing a placeholder for want of this
                        // very file. It is here now; the grid gets to know on the way out.
                        mPreviewFetched = true;
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
        // A clip that arrived while the preview was open is a cell that can finally draw itself.
        if(mPreviewFetched) {
            mPreviewFetched = false;
            if(mFilmstripScroll != null && mFilmstripScroll.getVisibility() == View.VISIBLE) {
                buildFilmstrip();
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Leaving a decoder running behind another app is not something a head unit forgives.
        stopPreviewVideo();
        // Same for the grid's tiles, and for the same reason — but they have to be put back on
        // the way in, which the preview does for itself in onResume. The cells stay: only the
        // decoders go, so returning is a rebind rather than a rebuild.
        mTilesReleased = true;
        if(mFilmstrip != null) mFilmstrip.removeCallbacks(mBindTiles);
        for(VideoTile t : mVideoTiles) stopTile(t);
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

        // Free the grid's decoders before asking for the big one. The live wallpaper engine, or
        // the theme app on a Lynk & Co, is about to want a hardware decoder of its own, and on a
        // head unit those run out — a tile that stops moving here is invisible (this screen is
        // leaving anyway), while a wallpaper that cannot get one is the whole failure.
        if(mFilmstrip != null) mFilmstrip.removeCallbacks(mBindTiles);
        for(VideoTile t : mVideoTiles) stopTile(t);

        // Lynk & Co images: the theme app's own crop/fit preview is unreliable, so we ask here and
        // bake the framing in ourselves. Video skips this (it rides the Leopard live-wallpaper
        // path), and so does anything that has already been through the fit editor — it is
        // exactly screen-shaped now, and asking "fill or fit?" about it would only offer to undo
        // the framing the user just chose.
        if(OperatingMode.isLynkco(mPrefs) && !mSelected.isVideo() && !isBaked(mSelected.url)) {
            new AuroraDialog.Builder(this)
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

        // The live-wallpaper hand-off, for every wallpaper of any kind on this car: pictures ride
        // our own MediaWallpaperService now, exactly as videos always have, because that is the
        // only wallpaper these head units bring back after a restart. Lynkco images are the
        // exception — they go to the head unit's theme app and never touch this.
        //
        // The system screen opens on every apply (see LeopardApplier.needsSystemScreen); the
        // dialog in front of it keeps appearing until the owner says they no longer need it.
        if(LeopardApplier.needsSystemScreen(this, mSelected.type)
                && !mPrefs.getBoolean(PREF_HANDOFF_EXPLAINED, false)) {
            showHandoffDialog();
            return;
        }
        doApply();
    }

    /**
     * Explain the system screen before throwing the user into it.
     *
     * It is the worst moment in the feature — an unstyled Android screen with an English button,
     * on a car whose owner did not ask to leave the app — so this says what is about to appear
     * and exactly which button ends it. An unexplained hand-off reads as the app breaking.
     *
     * The tick box is what stops it becoming a tax. Someone setting their tenth wallpaper knows
     * the steps; they say so once and from then on "Set as wallpaper" goes straight through to
     * the system screen. It is deliberately only honoured on the positive button — ticking the
     * box and then cancelling is not agreement to anything.
     */
    private void showHandoffDialog() {
        final float d = getResources().getDisplayMetrics().density;

        TextView msg = new TextView(this);
        msg.setText(R.string.leopard_video_first_message);
        msg.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        msg.setLineSpacing(0, 1.2f);   // three numbered steps read badly when they are packed

        final android.widget.CheckBox dontAsk = new android.widget.CheckBox(this);
        dontAsk.setText(R.string.leopard_handoff_dont_show);
        dontAsk.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 16);
        // A finger-sized target: this is a touchscreen an arm's length away in a car, not a phone.
        dontAsk.setMinHeight(Math.round(48 * d));
        dontAsk.setPadding(Math.round(10 * d), 0, 0, 0);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(Math.round(24 * d), Math.round(10 * d), Math.round(24 * d), 0);
        body.addView(msg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = Math.round(18 * d);
        body.addView(dontAsk, lp);

        // The Lynkco density override makes this dialog tall on a short passenger panel, and the
        // steps are the part that must not be cut off.
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(body);

        new AuroraDialog.Builder(this)
                .setTitle(R.string.leopard_video_first_title)
                .setView(scroll)
                .setPositiveButton(R.string.leopard_video_first_ok, (dlg, w) -> {
                    if(dontAsk.isChecked()) {
                        mPrefs.edit().putBoolean(PREF_HANDOFF_EXPLAINED, true).apply();
                    }
                    doApply();
                })
                .setNegativeButton(R.string.update_cancel, null)
                .show();
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
        // A baked image is already the size of the screen, so the theme app is handed it raw.
        final int scaleMode = isBaked(url) ? LynkcoApplier.SCALE_NONE : mLynkcoScaleMode;
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
            sayLoud(R.string.leopard_apply_failed);
            return;
        }
        sayLoud(R.string.lynkco_applied);
        hidePreview();
        buildFilmstrip();
        // No home trip here, unlike Leopard: the theme app has just come to the front with this
        // exact picture and its own Apply button, so it IS the "here is your wallpaper" screen.
        // Jumping to the launcher now would pull the user off the tap the wallpaper still needs.
        // The picker still stands down behind it — there is nothing left for it to do either.
        mFromPhoneUpload = false;
        markLeaveAfterApply(LEAVE_QUIET, R.string.lynkco_applied);
        mSetButton.postDelayed(this::leaveQuietly, 1400);
    }

    /** finishAffinity, plus forgetting the note that asked for it. */
    private void leaveQuietly() {
        if(isFinishing() || isDestroyed()) return;
        clearLeaveAfterApply();
        finishAffinity();
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
        final int appliedMsg;
        switch(result) {
            case LeopardApplier.RESULT_NEEDS_SYSTEM_SCREEN:
                try {
                    // Pinned to this screen: on a two-panel car the system's own live-wallpaper
                    // screen would otherwise open on the default display, sideways.
                    markLeaveAfterApply(LEAVE_AFTER_SYSTEM_SCREEN, R.string.leopard_applied_video);
                    LeopardApplier.startOnSameDisplay(this, LeopardApplier.systemPickerIntent(this));
                } catch(ActivityNotFoundException e) {
                    clearLeaveAfterApply();
                    // Some cheap ROMs ship without the live wallpaper picker at all.
                    say(R.string.leopard_unsupported);
                    scheduleTileBind();
                }
                return;
            case LeopardApplier.RESULT_APPLIED_LIVE:
                // A live wallpaper cannot cover the lock screen on most versions; say home only.
                // Every kind of file arrives here now, a still included — see LeopardApplier.
                appliedMsg = R.string.leopard_applied_video;
                sayLoud(appliedMsg);
                break;
            default:
                sayLoud(R.string.leopard_apply_failed);
                // Nothing is leaving after all, so give the library its movement back.
                scheduleTileBind();
                return;
        }
        // The app has just made itself redundant — Android owns the screen now. Leaving is the
        // honest end to "open, choose, close", and the home screen is where the picture that was
        // just set can actually be seen. Unconditional: this used to sit behind a "close after
        // setting" switch, which only ever offered the choice of staying on a screen with nothing
        // left to show.
        //
        // Nothing is repainted on the way out, and that is the fix for a real complaint:
        // hidePreview() + buildFilmstrip() used to run first, which tore down the preview and
        // rebuilt the library under it — so for the second before the screen closed, someone who
        // had just sent a photo from their phone and pressed Set watched the app throw them back
        // into the picker. On the phone route it read as the picture not having been taken. The
        // last thing on screen should be the wallpaper they chose.
        mFromPhoneUpload = false;
        // Written before the delay, not instead of it. The delay is for the eye; the note is for
        // the case where this instance does not live long enough to honour it — setting a still
        // wallpaper is itself a configuration change, so more often than not the activity is
        // torn down and rebuilt somewhere inside that 900ms.
        markLeaveAfterApply(LEAVE_NOW, appliedMsg);
        mSetButton.postDelayed(this::goHome, 1400);
    }

    /**
     * Remember, across a restart of this activity, that the picker is done — and what to say.
     *
     * The message travels with the note for the same reason the note exists at all: the banner
     * is drawn by an activity that is about to be destroyed by the very apply that raised it, so
     * on the common path nobody ever sees it. The new instance puts it back up.
     */
    private void markLeaveAfterApply(String kind, int msgRes) {
        mPrefs.edit()
                .putString(PREF_LEAVE, kind)
                .putInt(PREF_LEAVE_MSG, msgRes)
                .putLong(PREF_LEAVE_AT, System.currentTimeMillis())
                .apply();
    }

    private void clearLeaveAfterApply() {
        mPrefs.edit().remove(PREF_LEAVE).remove(PREF_LEAVE_MSG).remove(PREF_LEAVE_AT).apply();
    }

    /**
     * Act on a pending "leave" note, if there is one worth acting on.
     *
     * @return true when this screen is on its way out and the rest of onResume should not run.
     */
    private boolean resumeAfterApply() {
        String kind = mPrefs.getString(PREF_LEAVE, null);
        if(kind == null) return false;
        // Stale: an apply that was interrupted, or a car left overnight on this screen. Forget
        // it rather than close the app on somebody who has just opened it.
        if(System.currentTimeMillis() - mPrefs.getLong(PREF_LEAVE_AT, 0) > LEAVE_VALID_MS) {
            clearLeaveAfterApply();
            return false;
        }
        int msg = mPrefs.getInt(PREF_LEAVE_MSG, 0);
        if(LEAVE_AFTER_SYSTEM_SCREEN.equals(kind)) {
            awaitSystemScreenResult(msg, 0);
            return true;
        }
        // The note deliberately stays until the screen is actually gone. Clearing it here and
        // trusting the delay below was the last version of this bug: the wallpaper that was just
        // set is itself a configuration change, so a second teardown lands inside that pause,
        // takes the pending departure with it, and the new instance wakes up with nothing to
        // tell it what it was in the middle of. goHome() is the only place that consumes it.
        mFromPhoneUpload = false;
        // The banner the previous instance raised died with it. Put it back, then leave — with
        // enough of a pause to be read, which is the only reason for a delay here at all.
        if(msg != 0) sayLoud(msg);
        mSetButton.postDelayed(
                LEAVE_QUIET.equals(kind) ? this::leaveQuietly : this::goHome, 1400);
        return true;
    }

    /**
     * Wait to find out whether the system's live-wallpaper screen actually set anything.
     *
     * There is no result to read, so the only answer available is the wallpaper component — and
     * asking for it the instant we are resumed gets the OLD one. Binding a live wallpaper is
     * asynchronous: the picker screen closes, we come back, and the service is bound a moment
     * later. A single check on resume therefore reads "they backed out" from a car that has just
     * had the wallpaper set on it, which is exactly what it did.
     *
     * So it asks repeatedly for a few seconds. Leaving early is impossible (the check is the
     * same one either way) and the give-up path is the honest reading of a screen that really
     * was dismissed: put the library back and carry on.
     */
    private static final int SYSTEM_RESULT_TRIES = 12;      // x 400ms ≈ 5s
    private static final long SYSTEM_RESULT_POLL_MS = 400;

    private void awaitSystemScreenResult(final int msg, final int attempt) {
        if(gone()) return;
        if(LeopardApplier.isOurServiceActive(this)) {
            // Note left in place on purpose — see resumeAfterApply. Binding that wallpaper is
            // itself about to restart this activity, and the note is what tells its replacement
            // to carry on leaving.
            mFromPhoneUpload = false;
            if(msg != 0) sayLoud(msg);
            mSetButton.postDelayed(this::goHome, 1400);
            return;
        }
        if(attempt >= SYSTEM_RESULT_TRIES) {
            clearLeaveAfterApply();
            restartTilePlayers();   // the rest of onResume was skipped for this
            return;
        }
        mSetButton.postDelayed(() -> awaitSystemScreenResult(msg, attempt + 1),
                SYSTEM_RESULT_POLL_MS);
    }

    /**
     * Leave for the head unit's home screen, which in a hand-off mode IS the wallpaper.
     *
     * There is nothing left for this screen to show: the picture is no longer ours to draw, it
     * belongs to Android (or to the Flyme theme app) now. Ending on the picker would mean the
     * one thing the user asked for — see it on the car — is a step they still have to work out
     * for themselves, with the app's own UI in the way of the very thing it just set.
     *
     * The delay before this runs is so the "applied" line is read before the screen changes.
     */
    private void goHome() {
        if(isFinishing() || isDestroyed()) return;
        // Consumed. Leaving it behind would close the app on the next person to open it.
        clearLeaveAfterApply();
        try {
            Intent home = new Intent(Intent.ACTION_MAIN);
            home.addCategory(Intent.CATEGORY_HOME);
            home.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(home);
        } catch(Throwable ignored) {
            // No launcher that will take the intent: closing still beats staying on a dead screen.
        }
        // finishAffinity, not finish: Settings, the fit editor and the mode screens can all be
        // stacked underneath this one, and finishing only the picker leaves the app's own UI in
        // front of the wallpaper it just set — a back press from the launcher walks straight back
        // into it. This is the "close the app, without force-stopping it" ending: the whole task
        // goes, the process is left alone.
        finishAffinity();
    }

    // ---------------------------------------------------------------- state view

    private void showState(int textRes, int actionRes, View.OnClickListener action) {
        releaseTilePlayers();   // same reason as in buildFilmstrip: these cells are going away
        mFilmstrip.removeAllViews();
        // This box is a sibling of both containers, so a phone pane left visible underneath would
        // show a dead QR behind the very message explaining that the server could not start.
        mPhonePane.setVisibility(View.GONE);
        mFilmstripScroll.setVisibility(View.VISIBLE);
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

    /**
     * A message at the foot of the screen — our own, not the platform's.
     *
     * "Wallpaper set" is the one line in this whole flow that has to land: it is the answer to
     * the only question the user asked. A system toast answers it in small grey-on-grey text
     * over a photograph, from a car seat, which is close to not answering at all. This is the
     * same idea drawn to be read — white, larger, and with weight behind it.
     *
     * Built once and kept: it is added to the activity's own content view, so it goes away with
     * the screen and there is nothing to leak.
     */
    private void toast(int res) {
        if(gone()) return;
        if(mBanner == null) {
            ViewGroup root = findViewById(android.R.id.content);
            if(root == null) {   // nothing to attach to; better silent than crashed
                Toast.makeText(this, res, Toast.LENGTH_SHORT).show();
                return;
            }
            float d = getResources().getDisplayMetrics().density;
            mBanner = new TextView(this);
            mBanner.setBackgroundResource(R.drawable.leopard_banner_bg);
            mBanner.setTextColor(0xFF14161A);
            mBanner.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20);
            mBanner.setTypeface(null, android.graphics.Typeface.BOLD);
            mBanner.setGravity(Gravity.CENTER);
            mBanner.setPadding(Math.round(28 * d), Math.round(16 * d),
                    Math.round(28 * d), Math.round(16 * d));
            mBanner.setElevation(12 * d);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT);
            lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
            // This activity draws edge to edge, so the foot of the content view is behind the
            // navigation bar and a banner at a plain margin has its last line sliced off by it.
            // The inset is read back off leopardRoot's padding, which the insets listener in
            // onCreate has already set to exactly that value — asking the window again returns
            // nothing, because that listener consumes them.
            View insetHost = findViewById(R.id.leopardRoot);
            int nav = insetHost != null ? insetHost.getPaddingBottom() : 0;
            lp.bottomMargin = Math.round(24 * d) + nav;
            lp.leftMargin = lp.rightMargin = Math.round(24 * d);
            mBanner.setLayoutParams(lp);
            root.addView(mBanner);
        }
        mBanner.setText(res);
        mBanner.setVisibility(View.VISIBLE);
        mBanner.bringToFront();   // the preview and the ring are added after it on a rebuild
        mBanner.removeCallbacks(mHideBanner);
        mBanner.postDelayed(mHideBanner, 2600);
    }

    /**
     * Status that reaches the user wherever they are. The status line lives under the
     * filmstrip, which the preview covers — so while the preview is up, the same message has
     * to come through as a toast or it is written to a hidden view.
     */
    private void say(int res) {
        mStatus.setText(res);
        if(mPreview.getVisibility() == View.VISIBLE) toast(res);
    }

    /**
     * The outcome of an apply — said where it cannot be missed, preview or no preview.
     *
     * The status line under the grid is the right place for "downloading…" and is the wrong
     * place for "done": by the time this is said the screen is about to close, and a line of
     * small text at the bottom edge is not what somebody who just pressed Set is looking at.
     */
    private void sayLoud(int res) {
        mStatus.setText(res);
        toast(res);
    }
}
