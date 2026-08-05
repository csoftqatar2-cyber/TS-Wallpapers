package systems.sieber.fsclock;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * Others/FSE only: a hard gate between {@link ModeConfirmActivity} and the wallpaper screen.
 * Nothing else lives on this screen — unlike the in-slideshow progress overlay in
 * {@link FsClockView}, there is no cached wallpaper underneath it that could flash through
 * before the download is done. It calls {@link WallpaperRepo#sync} itself and only opens
 * {@link FullscreenActivity} once {@link WallpaperRepo.SyncCallback#mediaReady()} fires — every
 * file in the playlist genuinely local, not just "the manifest arrived".
 *
 * A dead link cannot maroon the car here forever: a failed attempt is retried a few times with a
 * back-off, same as {@code FsClockView.autoDownloadWallpapers}, and once those are exhausted the
 * screen opens anyway so a workshop with no wifi still gets into the app to fix that.
 */
public class WallpaperDownloadActivity extends AppCompatActivity {

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
