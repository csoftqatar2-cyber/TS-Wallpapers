package systems.sieber.fsclock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.Toast;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

public class FullscreenActivity extends AppCompatActivity {

    private final FullscreenActivity me = this;

    // FSE: chosen on the activation screen — run the app in a fixed-size window
    // for head-unit panels whose real resolution differs from what Android reports.
    static final String PREF_FSE_SCREEN = "fse-1920x720";
    static final int FSE_SCREEN_WIDTH = 1920;
    static final int FSE_SCREEN_HEIGHT = 720;

    SharedPreferences mSharedPref;

    UiModeManager uiModeManager;

    private static final int UI_ANIMATION_DELAY = 300;
    private final Handler mHideHandler = new Handler();

    private FsClockView mContentView;
    private View mControlsView;
    private FloatingActionButton mFabSettings;
    private View mUpdateBadge;

    private final Runnable mHidePart2Runnable = new Runnable() {
        @SuppressLint("InlinedApi")
        @Override
        public void run() {
            mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    };
    /** How long the gear stays on screen after a tap before fading itself out. */
    private static final int CONTROLS_LINGER = 5000;
    private static final int CONTROLS_FADE = 600;

    private final Runnable mShowPart2Runnable = new Runnable() {
        @Override
        public void run() {
            ActionBar actionBar = getSupportActionBar();
            if(actionBar != null) {
                actionBar.show();
            }
            showControls();
        }
    };

    /**
     * The gear is a visitor, not furniture.
     *
     * It used to sit there permanently at 25% alpha over a translucent white circle, which made
     * it invisible on a light wallpaper and a smudge on a dark one — the worst of both. Now it
     * arrives solid gold when you touch the screen, and takes itself away after five seconds so
     * the wallpaper is left alone. This is a wallpaper screen; nothing should be on it forever.
     */
    private final Runnable mFadeControlsRunnable = new Runnable() {
        @Override
        public void run() {
            if(mControlsView == null) return;
            mControlsView.animate().alpha(0f).setDuration(CONTROLS_FADE)
                    .withEndAction(new Runnable() {
                        @Override
                        public void run() {
                            mControlsView.setVisibility(View.GONE);
                        }
                    }).start();
        }
    };

    private void showControls() {
        if(mControlsView == null) return;
        mHideHandler.removeCallbacks(mFadeControlsRunnable);
        mControlsView.animate().cancel();
        mControlsView.setAlpha(1f);
        mControlsView.setVisibility(View.VISIBLE);
        mHideHandler.postDelayed(mFadeControlsRunnable, CONTROLS_LINGER);
    }

    private void hideControls() {
        if(mControlsView == null) return;
        mHideHandler.removeCallbacks(mFadeControlsRunnable);
        mControlsView.animate().cancel();
        mControlsView.setVisibility(View.GONE);
        // Reset, or the next show() would fade in from whatever the interrupted animation left.
        mControlsView.setAlpha(1f);
    }
    private boolean mVisible;
    private boolean mClockHidden = false;
    private final Runnable mHideRunnable = new Runnable() {
        @Override
        public void run() {
            hide();
        }
    };

    ActivityResultLauncher<Intent> settingsActivityResultLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            new ActivityResultCallback<ActivityResult>() {
                @Override
                public void onActivityResult(ActivityResult result) {
                    if(result.getResultCode() == Activity.RESULT_OK) {
                        // The operating mode is decided in onCreate, which does not run again on
                        // the way back from Settings — so switching mode there used to write the
                        // pref and change nothing until the app was killed and reopened.
                        if(OperatingMode.isLeopard(mSharedPref) && new WallpaperRepo(FullscreenActivity.this).isActive()) {
                            startActivity(new Intent(FullscreenActivity.this, LeopardPickerActivity.class));
                            finish();
                            return;
                        }
                        // Same for FSE: the window size is applied at create time, so a toggle
                        // has to be re-applied by hand here.
                        applyFseScreenSize();
                        mContentView.loadSettings();
                    }
                }
            });

    @Override
    protected void attachBaseContext(Context newBase) {
        // Follow the in-app language switch, not just the head unit's system locale.
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // In Leopard this activity has nothing to draw — Android owns the wallpaper and there
        // is no clock. Launching straight into the picker is the only honest thing to do: the
        // app is a tool you open, use for fifteen seconds, and close.
        //
        // Not before activation, though. The activation overlay is baked into this screen, so
        // skipping to the picker on an unactivated device would hand a technician a wallpaper
        // library with no way to enter the serial.
        mSharedPref = getSharedPreferences(SettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        if(OperatingMode.isLeopard(mSharedPref) && new WallpaperRepo(this).isActive()) {
            startActivity(new Intent(this, LeopardPickerActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_fullscreen);
        uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);

        // find views
        mControlsView = findViewById(R.id.linearLayoutControls);
        mFabSettings = findViewById(R.id.fabSettings);
        mUpdateBadge = findViewById(R.id.viewUpdateBadge);
        mContentView = findViewById(R.id.fsClockViewFullscreen);
        mContentView.mActivity = this;

        // register actions
        mFabSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                openSettings(view);
            }
        });
        if(uiModeManager.getCurrentModeType() != Configuration.UI_MODE_TYPE_TELEVISION) {
            // we do not enable the onTouch event on TVs because this intersects with the onKeyDown event
            // single tap = toggle settings button, horizontal swipe = change wallpaper
            final GestureDetector gestureDetector = new GestureDetector(this,
                    new GestureDetector.SimpleOnGestureListener() {
                        @Override
                        public boolean onSingleTapConfirmed(MotionEvent e) {
                            // in adjust mode a tap saves the wallpaper position and exits
                            if(mContentView.isAdjustMode()) {
                                mContentView.exitAdjustModeAndSave();
                                mClockHidden = false;
                                show();
                                return true;
                            }
                            // single tap hides the clock (pure wallpaper); tap again brings it back
                            mClockHidden = !mClockHidden;
                            mContentView.setClockHidden(mClockHidden);
                            if(mClockHidden) hide(); else show();
                            mContentView.resetNotificationCount();
                            return true;
                        }
                        @Override
                        public void onLongPress(MotionEvent e) {
                            // long press on an FSE screen starts wallpaper "adjust position" mode
                            if(mContentView.isFseMode() && !mContentView.isAdjustMode()) {
                                mContentView.enterAdjustMode();
                                mClockHidden = true;
                                hide();
                            }
                        }
                        @Override
                        public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                            // while adjusting, a drag pans the current wallpaper (distanceX/Y are
                            // the negated finger movement, so flip the sign to follow the finger)
                            if(mContentView.isAdjustMode()) {
                                mContentView.panWallpaper(-distanceX, -distanceY);
                                return true;
                            }
                            return false;
                        }
                        @Override
                        public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                            if(e1 == null || e2 == null) return false;
                            // don't switch wallpapers while repositioning one
                            if(mContentView.isAdjustMode()) return true;
                            float dx = e2.getX() - e1.getX();
                            float dy = e2.getY() - e1.getY();
                            if(Math.abs(dx) > Math.abs(dy) && Math.abs(dx) > 120 && Math.abs(velocityX) > 200) {
                                if(dx < 0) mContentView.nextWallpaper();
                                else mContentView.prevWallpaper();
                                return true;
                            }
                            return false;
                        }
                    });
            mContentView.setClickable(true);
            mContentView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return gestureDetector.onTouchEvent(event);
                }
            });
        }

        // apply the insets as a margin to the view, so that elements at the bottom
        // of the ScrollView do not get hidden behind the navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(mControlsView, (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            mlp.leftMargin = insets.left;
            mlp.rightMargin = insets.right;
            mlp.topMargin = insets.top;
            mlp.bottomMargin = insets.bottom;
            v.setLayoutParams(mlp);
            // Return CONSUMED if you don't want the window insets to keep passing down to descendant views.
            return WindowInsetsCompat.CONSUMED;
        });

        // apply own background color to navigation bar - especially for Samsung One UI, which displays a white navbar by default
        int colorBack = Color.argb(0xff,
                mSharedPref.getInt("color-red-back", 0),
                mSharedPref.getInt("color-green-back", 0),
                mSharedPref.getInt("color-blue-back", 0)
        );
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().setNavigationBarColor(colorBack);
        }

        // stretch activity over the entire screen, even under the status bar with notch or cutout
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        }
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getWindow().getAttributes().layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }

        // force the fixed FSE screen size when it was selected at activation time
        applyFseScreenSize();

        // initial event state update
        mContentView.updateEventView();
    }

    @Override
    public void onPause() {
        super.onPause();

        // stop the clock
        mContentView.pause();

        // unregister receiver
        unregisterReceiver(this.mBatInfoReceiver);
    }

    @Override
    public void onResume() {
        super.onResume();

        // init battery info
        registerReceiver(this.mBatInfoReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));

        // start the clock
        mClockHidden = false;
        mContentView.resume();
        mContentView.resetNotificationCount();
        incrementStartedCounter();
        // ad/review popup removed for the TS WALLPAPERS resell build

        // check for an app update on every open; show a red dot next to the
        // settings button when a newer version is published on Supabase
        checkForUpdateBadge();

        // show TV keys info
        if(uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            int tvHintShown = mSharedPref.getInt("tv-hint-shown", 0);
            if(tvHintShown == 0) {
                // increment counter
                SharedPreferences.Editor editor = mSharedPref.edit();
                editor.putInt("tv-hint-shown", tvHintShown + 1);
                editor.apply();
                // show info
                Toast.makeText(this, getString(R.string.tv_settings_note), Toast.LENGTH_LONG).show();
            }
        }

        // re-apply the FSE fixed screen size (the toggle may have changed in settings)
        applyFseScreenSize();

        // the "force landscape" option was removed — always follow the device orientation
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);

        // enter fullscreen mode (again)
        if(mSharedPref.getBoolean("opened-settings", false) || mSharedPref.getBoolean("purchased-settings", false)) {
            hide();
        } else {
            show();
            Animation animation = AnimationUtils.loadAnimation(getApplicationContext(), R.anim.settings_blink);
            mFabSettings.startAnimation(animation);
        }
    }

    private final BroadcastReceiver mBatInfoReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctxt, Intent intent) {
            int plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0);
            mContentView.updateBattery(plugged, level);
        }
    };

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean handled = false;
        switch(keyCode) {
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_MENU:
            case KeyEvent.KEYCODE_SETTINGS:
                openSettings(null);
                handled = true; break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                mContentView.prevWallpaper();
                handled = true; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                mContentView.nextWallpaper();
                handled = true; break;
        }
        return handled || super.onKeyDown(keyCode, event);
    }

    @Override
    public void onRequestPermissionsResult(int callbackId, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(callbackId, permissions, grantResults);
        int i = 0;
        for(String p : permissions) {
            if(p.equals(Manifest.permission.READ_CALENDAR)) {
                if(grantResults[i] == PackageManager.PERMISSION_GRANTED) {
                    mContentView.updateEventView();
                }
            }
            i++;
        }
    }

    private void toggle() {
        if(mVisible) {
            hide();
        } else {
            show();
        }
    }

    private void hide() {
        // Hide UI first
        ActionBar actionBar = getSupportActionBar();
        if(actionBar != null) {
            actionBar.hide();
        }
        hideControls();
        mVisible = false;

        // Schedule a runnable to remove the status and navigation bar after a delay
        mHideHandler.removeCallbacks(mShowPart2Runnable);
        mHideHandler.postDelayed(mHidePart2Runnable, UI_ANIMATION_DELAY);
    }

    @SuppressLint("InlinedApi")
    private void show() {
        // Show the system bar
        mContentView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        mVisible = true;

        // Schedule a runnable to display UI elements after a delay
        mHideHandler.removeCallbacks(mHidePart2Runnable);
        mHideHandler.postDelayed(mShowPart2Runnable, UI_ANIMATION_DELAY);
    }

    private void delayedHide(int delayMillis) {
        mHideHandler.removeCallbacks(mHideRunnable);
        mHideHandler.postDelayed(mHideRunnable, delayMillis);
    }

    public void openSettings(View v) {
        Intent i = new Intent(this, SettingsActivity.class);
        settingsActivityResultLauncher.launch(i);
    }

    /** Resize the activity window to the fixed FSE resolution (1920x720) if the
     *  FSE checkbox was ticked on the activation screen; otherwise keep fullscreen.
     *  FLAG_LAYOUT_NO_LIMITS is already set in onCreate, so the window may extend
     *  beyond what the system reports as the display size. */
    void applyFseScreenSize() {
        if(mSharedPref.getBoolean(PREF_FSE_SCREEN, false)) {
            getWindow().setLayout(FSE_SCREEN_WIDTH, FSE_SCREEN_HEIGHT);
            getWindow().setGravity(Gravity.TOP | Gravity.START);
        } else {
            getWindow().setLayout(WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
        }
    }

    /** Silently ask Supabase for a newer version and toggle the red update dot. */
    private void checkForUpdateBadge() {
        if(mUpdateBadge == null) return;
        new UpdateManager(this).checkForUpdate(new UpdateManager.UpdateCheckListener() {
            @Override
            public void onUpdateAvailable(int versionCode, String versionName, String apkUrl, String changelog) {
                if(mUpdateBadge != null) mUpdateBadge.setVisibility(View.VISIBLE);
            }
            @Override
            public void onNoUpdate() {
                if(mUpdateBadge != null) mUpdateBadge.setVisibility(View.GONE);
            }
            @Override
            public void onError(String message) {
                // network/error: keep whatever state we had, don't flash the dot
            }
        });
    }

    private void incrementStartedCounter() {
        int oldStartedValue = mSharedPref.getInt("started", 0);
        SharedPreferences.Editor editor = mSharedPref.edit();
        editor.putInt("started", oldStartedValue+1);
        editor.apply();
    }
    private void showDialogReview() {
        if(mSharedPref.getInt("started", 0) % 14 == 0
                && mSharedPref.getInt("ad-other-apps-shown", 0) < 1) {
            // increment counter
            SharedPreferences.Editor editor = mSharedPref.edit();
            editor.putInt("ad-other-apps-shown", mSharedPref.getInt("ad-other-apps-shown", 0)+1);
            editor.apply();

            // show ad "other apps"
            final Dialog ad = new Dialog(this);
            ad.requestWindowFeature(Window.FEATURE_NO_TITLE);
            ad.setContentView(R.layout.dialog_review);
            ad.setCancelable(true);
            ad.findViewById(R.id.buttonReviewNow).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openPlayStore(me, getPackageName());
                    ad.hide();
                }
            });
            ad.findViewById(R.id.linearLayoutRateStars).setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openPlayStore(me, getPackageName());
                    ad.hide();
                }
            });
            ad.show();
        }
    }
    static void openPlayStore(Activity activity, String appId) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + appId)));
        } catch (android.content.ActivityNotFoundException ignored) {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + appId)));
        }
    }

}
