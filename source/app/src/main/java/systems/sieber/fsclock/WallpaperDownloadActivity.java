package systems.sieber.fsclock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * A hard gate between activation and the wallpaper screen, for every mode that draws our own
 * screen: Others, FSE, GWM and Jetour. Nothing else lives on this screen — unlike the in-slideshow
 * progress overlay in {@link FsClockView}, there is no cached wallpaper underneath it that could
 * flash through before the download is done. It calls {@link WallpaperRepo#sync} itself and only
 * opens {@link FullscreenActivity} once {@link WallpaperRepo.SyncCallback#mediaReady()} fires —
 * every file in the playlist genuinely local, not just "the manifest arrived".
 *
 * It used to be reached only by being routed into, from the activation screen and from
 * {@link ModeConfirmActivity}. That is not the same as mandatory, and on the cars it showed:
 * anything that reached {@link FullscreenActivity} by another route — a plain launch, a car
 * restarted in the middle of the first download, an update landing on a car that had never
 * completed one — went straight to the slideshow and downloaded underneath it, which is the
 * "it starts loading and the screen freezes" the workshop kept reporting. So the decision now
 * lives in {@link #isPending}, which FullscreenActivity asks on the way in. Being routed here is
 * the fast path; being sent back here is the guarantee.
 *
 * A dead link cannot maroon the car here forever: a failed attempt is retried a few times with a
 * back-off, same as {@code FsClockView.autoDownloadWallpapers}, and once those are exhausted the
 * screen opens anyway so a workshop with no wifi still gets into the app to fix that.
 */
public class WallpaperDownloadActivity extends AppCompatActivity {

    /**
     * Set once this car has been through the gate — whether the library completed or the retries
     * ran out. It is a first-run step, not a toll to pay on every open.
     */
    private static final String PREF_GATE_DONE = "wallpapers-prefetched";

    /**
     * Must this car be held here before it is allowed to draw wallpapers?
     *
     * The order of the checks is the order of the questions a new car answers: is it activated at
     * all (an unactivated one belongs on the activation overlay, which lives inside
     * FullscreenActivity), has somebody chosen its mode ({@link ModeConfirmActivity} asks first),
     * is it a mode that draws wallpapers at all (Leopard and Lynk &amp; Co hand the file to the
     * platform and have no slideshow to protect), and finally: has it already been through here.
     */
    static boolean isPending(Context ctx, SharedPreferences prefs) {
        if(prefs.getBoolean(PREF_GATE_DONE, false)) return false;
        if(OperatingMode.isHandoff(prefs)) return false;
        if(ModeConfirmActivity.isPending(ctx, prefs)) return false;
        return new WallpaperRepo(ctx).isActive();
    }

    private TextView mPercent;
    private TextView mCount;
    private TextView mSpeed;
    private TextView mEta;
    private ProgressBar mBar;
    private WallpaperRepo mRepo;
    private boolean mProceeded = false;

    /**
     * When the first byte of this session actually moved, and how far along it was then.
     *
     * The clock does not start in onCreate: the manifest fetch, the DNS and a car's wifi
     * negotiating itself come first, and counting that dead time into the rate would put the very
     * first estimate minutes too high — the one estimate somebody is definitely reading. It starts
     * at the first progress report and measures from there.
     *
     * The baseline percentage matters just as much. A car that has already been through part of a
     * download (a retry, or the pre-activation prefetch putting most of the library on disk) opens
     * this screen at 60% in the first second, and dividing 60% by that second says the rest will
     * take no time at all. Only progress made SINCE the clock started is used to set the rate.
     */
    private long mRateStartedAt = 0L;
    private float mRateStartPercent = 0f;

    /**
     * Where the download really is, and where the screen has caught up to.
     *
     * They are two numbers because the truth arrives four times a second and the eye wants
     * motion in between: {@link #mAnimate} walks the shown value towards the real one every
     * frame. It only ever walks FORWARD — a retry starts its file count again from zero, and a
     * bar that fell back to 0% would read as "it lost everything I just waited for".
     */
    private float mTargetPercent = 0f;
    private float mShownPercent = 0f;
    private int mDoneFiles = 0;
    private int mTotalFiles = 0;

    private final Runnable mAnimate = new Runnable() {
        @Override
        public void run() {
            if(mProceeded) return;
            float gap = mTargetPercent - mShownPercent;
            if(gap > 0.01f) {
                // Proportional, with a floor: close the big gaps fast, and still creep visibly
                // while a single large video is coming down.
                mShownPercent += Math.max(0.08f, gap * 0.2f);
                if(mShownPercent > mTargetPercent) mShownPercent = mTargetPercent;
            }
            render();
            mBar.postDelayed(this, 50);
        }
    };

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_wallpaper_download);

        mPercent = findViewById(R.id.textViewWpDownloadPercent);
        mCount = findViewById(R.id.textViewWpDownloadCount);
        mSpeed = findViewById(R.id.textViewWpDownloadSpeed);
        mEta = findViewById(R.id.textViewWpDownloadEta);
        mBar = findViewById(R.id.progressBarWpDownload);
        mRepo = new WallpaperRepo(this);

        findViewById(R.id.buttonWpDownloadSkip).setOnClickListener(v -> skip());

        mBar.post(mAnimate);
        download(3);
    }

    /**
     * Leave now and let the library finish arriving behind the app.
     *
     * Nothing is cancelled. {@link WallpaperRepo#sync} runs on a thread of its own that outlives
     * this activity, so the files carry on landing exactly as they were — what changes is only
     * that nobody is being made to watch. The screen behind picks each wallpaper up as it appears,
     * because the slideshow reads the cache on every draw.
     *
     * The gate is marked done, deliberately. Being sent back here on the next launch to wait for a
     * download somebody has already chosen to skip would make the button a lie; the periodic sync
     * and the sync on resume keep fetching whatever is still missing.
     */
    private void skip() {
        android.widget.Toast.makeText(this, R.string.download_progress_skipped,
                android.widget.Toast.LENGTH_LONG).show();
        proceed();
    }

    @Override
    protected void onDestroy() {
        mBar.removeCallbacks(mAnimate);
        super.onDestroy();
    }

    private void download(final int attemptsLeft) {
        mRepo.sync(new WallpaperRepo.SyncCallback() {
            @Override
            public void done(final boolean success, int count, String error) {
                if(success) return; // media download continues below; wait for mediaReady()
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { retryOrProceed(attemptsLeft); }
                });
            }

            @Override
            public void mediaProgress(final int done, final int total) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { updateProgress(done, total, 0f, -1); }
                });
            }

            @Override
            public void mediaTick(final int done, final int total,
                                  final float fraction, final long bytesPerSecond) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { updateProgress(done, total, fraction, bytesPerSecond); }
                });
            }

            @Override
            public void mediaReady() {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { proceed(); }
                });
            }

            @Override
            public void mediaIncomplete(int failed, int total) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() { retryOrProceed(attemptsLeft); }
                });
            }
        });
    }

    /**
     * Files are missing. Another pass costs nothing for the ones already cached — needsDownload
     * skips them — so retry, and only let the car through once the retries are spent, because a
     * workshop with a genuinely dead link still has to be able to reach the app.
     */
    private void retryOrProceed(int attemptsLeft) {
        // The car has already been let through — by the skip button, or by an earlier path. A
        // retry now would start a second download thread behind the app for no one's benefit.
        if(mProceeded) return;
        if(attemptsLeft > 1) {
            mBar.postDelayed(new Runnable() {
                @Override public void run() { download(attemptsLeft - 1); }
            }, 5000);
        } else {
            proceed();
        }
    }

    /**
     * @param fraction        how far into the file currently downloading, 0..1
     * @param bytesPerSecond  measured speed, or -1 to leave the readout as it is (a whole-file
     *                        callback carries no measurement of its own)
     */
    private void updateProgress(int done, int total, float fraction, long bytesPerSecond) {
        // Skipped, or already through: the thread that reports this is deliberately allowed to
        // outlive the screen (see skip()), so its reports have to land on nothing.
        if(mProceeded || isFinishing()) return;
        mDoneFiles = done;
        mTotalFiles = total;
        // done + fraction, not done alone: the file being downloaded counts for what has already
        // arrived of it, which is the whole difference between a bar that creeps and one that
        // stands still for a minute and then jumps four points.
        float exact = total > 0 ? (done + fraction) * 100f / total : 100f;
        if(exact < 0f) exact = 0f;
        if(exact > 100f) exact = 100f;
        if(exact > mTargetPercent) mTargetPercent = exact;

        if(bytesPerSecond > 0) {
            mSpeed.setText(getString(R.string.download_progress_speed, formatSpeed(bytesPerSecond)));
        } else if(bytesPerSecond == 0) {
            mSpeed.setText(R.string.download_progress_speed_waiting);
        }
        render();
    }

    /** Draw whatever the animator has caught up to. */
    private void render() {
        mPercent.setText((int) mShownPercent + "%");
        mBar.setProgress(Math.round(mShownPercent * 10f));   // the bar runs 0..1000
        if(mTotalFiles > 0) {
            mCount.setText(getString(R.string.download_progress_count, mDoneFiles, mTotalFiles));
        }
        mEta.setText(etaText());
    }

    /**
     * How much longer, in the only unit anybody asks it in.
     *
     * Measured from the rate this download has actually achieved — percent gained over seconds
     * elapsed — rather than from bytes remaining. The bytes are not knowable here: an image's size
     * is learnt only once Glide has finished writing it, so a byte-based estimate would be
     * confidently wrong for the whole first half of a library and would then swing every time a
     * 50 MB video started.
     *
     * Nothing is claimed before there is something to claim it from: under six seconds, or under
     * three points of progress since the clock started, it says it is still working it out. That
     * is not a stall — an estimate that opens at "42 minutes" and settles at two is worse than no
     * estimate at all, because it is the first one that gets believed.
     *
     * Rounded UP to the minute, and never down to zero while files are still coming: a car that
     * says "less than a minute" for four minutes has told one small lie, while one that says
     * "0 minutes" and keeps going has told a plain untruth.
     */
    private String etaText() {
        if(mTotalFiles <= 0 || mTargetPercent <= 0f) {
            return getString(R.string.download_progress_eta_unknown);
        }
        if(mRateStartedAt == 0L) {
            mRateStartedAt = android.os.SystemClock.elapsedRealtime();
            mRateStartPercent = mTargetPercent;
            return getString(R.string.download_progress_eta_unknown);
        }
        long elapsedMs = android.os.SystemClock.elapsedRealtime() - mRateStartedAt;
        float gained = mTargetPercent - mRateStartPercent;
        if(elapsedMs < 6000L || gained < 3f) {
            return getString(R.string.download_progress_eta_unknown);
        }
        float remaining = 100f - mTargetPercent;
        if(remaining <= 0f) return getString(R.string.download_progress_eta_soon);
        long remainingMs = (long) (elapsedMs / gained * remaining);
        if(remainingMs < 60_000L) return getString(R.string.download_progress_eta_soon);
        int minutes = (int) Math.ceil(remainingMs / 60_000d);
        return getString(R.string.download_progress_eta, minutes);
    }

    /** Bytes/second as a workshop reads it. */
    private String formatSpeed(long bytesPerSecond) {
        if(bytesPerSecond >= 1024 * 1024) {
            return String.format(java.util.Locale.US, "%.1f MB/s", bytesPerSecond / (1024f * 1024f));
        }
        return String.format(java.util.Locale.US, "%.0f KB/s", bytesPerSecond / 1024f);
    }

    /** Enter the wallpaper screen. Idempotent: mediaReady() and an error path can both call it. */
    private void proceed() {
        if(mProceeded) return;
        mProceeded = true;
        // Written BEFORE the hop, not after: FullscreenActivity asks isPending() on the way in,
        // and a flag set a moment too late would bounce the car straight back here forever.
        getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE)
                .edit().putBoolean(PREF_GATE_DONE, true).apply();
        Intent next = new Intent(this, FullscreenActivity.class);
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(next);
        finish();
    }

    /** No way out but the download finishing — same reasoning as the mode gate before it. */
    @Override
    public void onBackPressed() {
        // intentionally empty
    }
}
