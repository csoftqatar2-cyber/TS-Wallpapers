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
    private ProgressBar mBar;
    private WallpaperRepo mRepo;
    private boolean mProceeded = false;

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
        mBar = findViewById(R.id.progressBarWpDownload);
        mRepo = new WallpaperRepo(this);

        download(3);
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
                    public void run() { updateProgress(done, total); }
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
        if(attemptsLeft > 1) {
            mBar.postDelayed(new Runnable() {
                @Override public void run() { download(attemptsLeft - 1); }
            }, 5000);
        } else {
            proceed();
        }
    }

    private void updateProgress(int done, int total) {
        int percent = total > 0 ? Math.max(0, Math.min(100, Math.round(done * 100f / total))) : 100;
        mPercent.setText(percent + "%");
        mBar.setProgress(percent);
        mCount.setText(getString(R.string.download_progress_count, done, total));
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
