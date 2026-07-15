package systems.sieber.fsclock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.provider.CalendarContract;
import android.speech.tts.TextToSpeech;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;

import com.google.gson.Gson;

import java.io.File;
import java.text.SimpleDateFormat;
import java.time.chrono.HijrahDate;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

public class FsClockView extends FrameLayout {

    final static int BURN_IN_PREVENTION_DEVIATION = 18; /*px*/
    final static int BURN_IN_PREVENTION_CHANGE = 100000; /*ms*/
    boolean mBurnInPrevention = false;

    Random mRand = new Random();
    AppCompatActivity mActivity;
    SharedPreferences mSharedPref;
    NotificationBroadcastReceiver mNotificationBroadcastReceiver;

    View mRootView;
    View mMainView;
    Float mMainViewDefaultX;
    Float mMainViewDefaultY;
    Float mMainViewDefaultTemp;
    View mBottomBar;
    Float mBottomBarDefaultX;
    Float mBottomBarDefaultTemp;
    ImageView mBackgroundImage;
    WallpaperView mWallpaper;
    WallpaperRepo mWallpaperRepo;

    // auto-switch: change the wallpaper automatically when enabled; the period is chosen
    // in the settings (30s / 1min / 5min / 10min / 1h — WallpaperRepo.AUTO_SWITCH_INTERVAL_VALUES)
    private final Runnable mAutoSwitchRunnable = new Runnable() {
        @Override
        public void run() {
            if(isAutoSwitchActive()) {
                nextWallpaper();
                postDelayed(this, autoSwitchIntervalMs());
            }
        }
    };

    // Periodic weather refresh: re-query the API every 15 min so the temperature and the
    // day/night icon stay current even while parked (location changes trigger their own refresh).
    static final long WEATHER_REFRESH_INTERVAL_MS = 15 * 60_000;
    private final Runnable mWeatherRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            if(mSharedPref.getBoolean("show-weather", true)) {
                refreshWeather();
                postDelayed(this, WEATHER_REFRESH_INTERVAL_MS);
            }
        }
    };

    // Periodic re-sync: pull the latest playlist from the server every few minutes so
    // that additions AND deletions made in the manager reach devices that are already
    // running, without waiting for the app to be restarted. Deleting a public wallpaper
    // then removes it everywhere; deleting a device-specific one removes it on that device.
    static final long PERIODIC_SYNC_INTERVAL_MS = 5 * 60_000;
    private final Runnable mPeriodicSyncRunnable = new Runnable() {
        @Override
        public void run() {
            if(mWallpaperRepo != null && mWallpaperRepo.isSyncEnabled()) {
                WallpaperItem cur = mWallpaperRepo.current();
                final String beforeUrl = (cur != null) ? cur.url : null;
                final int beforeSize = mWallpaperRepo.size();
                mWallpaperRepo.sync(new WallpaperRepo.SyncCallback() {
                    @Override
                    public void done(final boolean success, final int count, String error) {
                        post(new Runnable() {
                            @Override
                            public void run() {
                                if(!success) return;
                                // Refresh what's on screen only when the playlist actually
                                // changed, so a deleted wallpaper disappears immediately while
                                // an unchanged list keeps playing without any flicker.
                                WallpaperItem now = (mWallpaperRepo != null) ? mWallpaperRepo.current() : null;
                                String afterUrl = (now != null) ? now.url : null;
                                boolean changed = (count != beforeSize)
                                        || (beforeUrl == null ? afterUrl != null : !beforeUrl.equals(afterUrl));
                                if(changed) loadSettings();
                            }
                        });
                    }
                });
            }
            postDelayed(this, PERIODIC_SYNC_INTERVAL_MS);
        }
    };

    /** (Re)start the periodic server re-sync timer, cancelling any previous schedule. */
    private void startPeriodicSync() {
        removeCallbacks(mPeriodicSyncRunnable);
        postDelayed(mPeriodicSyncRunnable, PERIODIC_SYNC_INTERVAL_MS);
    }
    View mLayoutActivation;
    TextView mTextViewActivationDeviceId;
    EditText mEditTextActivationSerial;
    Button mButtonActivate;
    TextView mTextViewActivationStatus;
    View mBatteryView;
    TextView mBatteryText;
    ImageView mBatteryImage;
    View mAlarmView;
    TextView mAlarmText;
    ImageView mAlarmImage;
    View mNotificationsView;
    TextView mNotificationsText;
    ImageView mNotificationsImage;
    View mWeatherView;
    TextView mWeatherText;
    String mOutsideWeather;
    Float mInsideTemp;
    SensorManager mSensorManager;
    Sensor mTempSensor;
    LocationManager mLocationManager;
    Location mLastLocation;
    DigitalClockView mDigitalClock;
    DateView mDateText;
    TextView mTextViewEvents;
    ImageView mClockFace;
    ImageView mSecondsHand;
    ImageView mMinutesHand;
    ImageView mHoursHand;
    Typeface mFontClock;
    Typeface mFontDate;
    Typeface mFontEvents;

    Timer mTimerAnalogClock;
    Timer mTimerCalendarUpdate;
    Timer mTimerCheckEvent;
    Timer mTimerBurnInPreventionRotation;

    TextToSpeech mTts;
    Event[] mEvents;
    boolean mFormat24hrs;
    boolean mArabicDigits;
    boolean mShowHijri;
    TextView mHijriText;
    boolean mShowAnalog;
    boolean mSmoothHands;
    boolean mHighRefreshRate;
    boolean mShowDigital;
    boolean mShowDate;
    boolean mShowAlarms;

    public FsClockView(Context c, AttributeSet attrs) {
        super(c, attrs);
        commonInit(c);
    }
    private void commonInit(Context c) {
        inflate(getContext(), R.layout.view_fsclock, this);

        // find views
        mRootView = findViewById(R.id.fsclockRootView);
        mMainView = findViewById(R.id.linearLayoutMain);
        mBottomBar = findViewById(R.id.linearLayoutBottomBar);
        mBackgroundImage = findViewById(R.id.imageViewBackground);
        mWallpaper = findViewById(R.id.wallpaperView);
        if(mWallpaper == null) {
            Log.e("FSCLOCK", "wallpaperView NOT found by findViewById");
        } else {
            mWallpaperRepo = new WallpaperRepo(c);
            mWallpaper.setRepo(mWallpaperRepo);
        }
        mLayoutActivation = findViewById(R.id.layoutActivation);
        mTextViewActivationDeviceId = findViewById(R.id.textViewActivationDeviceId);
        mEditTextActivationSerial = findViewById(R.id.editTextActivationSerial);
        mButtonActivate = findViewById(R.id.buttonActivate);
        mTextViewActivationStatus = findViewById(R.id.textViewActivationStatus);

        if (mButtonActivate != null && mEditTextActivationSerial != null && mTextViewActivationStatus != null) {
            mButtonActivate.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    final String serial = mEditTextActivationSerial.getText().toString().trim();
                    if (serial.isEmpty()) {
                        mTextViewActivationStatus.setText("يرجى إدخال الرقم التسلسلي");
                        mTextViewActivationStatus.setVisibility(View.VISIBLE);
                        return;
                    }
                    if (!serial.startsWith("7078")) {
                        mTextViewActivationStatus.setText("الرقم التسلسلي غير صحيح");
                        mTextViewActivationStatus.setVisibility(View.VISIBLE);
                        return;
                    }
                    
                    mTextViewActivationStatus.setText("جاري التحقق من التفعيل...");
                    mTextViewActivationStatus.setTextColor(Color.YELLOW);
                    mTextViewActivationStatus.setVisibility(View.VISIBLE);
                    mButtonActivate.setEnabled(false);
                    
                    mWallpaperRepo.activate(serial, new WallpaperRepo.ActivateCallback() {
                        @Override
                        public void done(final boolean success, final String result, final String error) {
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    mButtonActivate.setEnabled(true);
                                    if (success) {
                                        mTextViewActivationStatus.setText("تم التفعيل بنجاح!");
                                        mTextViewActivationStatus.setTextColor(Color.GREEN);

                                        // activation done -> now hide the on-screen keyboard
                                        InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
                                        if (imm != null) {
                                            imm.hideSoftInputFromWindow(mEditTextActivationSerial.getWindowToken(), 0);
                                        }
                                        mEditTextActivationSerial.clearFocus();

                                        // Make sure the wallpaper slideshow is switched on so the
                                        // shop owner never has to flip a toggle after activating.
                                        mSharedPref.edit()
                                                .putBoolean(WallpaperRepo.PREF_ENABLED, true)
                                                .apply();

                                        postDelayed(new Runnable() {
                                            @Override
                                            public void run() {
                                                loadSettings();
                                                // Automatically download the public wallpapers right
                                                // after activation, retrying a few times so a brief
                                                // network hiccup doesn't leave the screen empty.
                                                autoDownloadWallpapers(3);
                                            }
                                        }, 1500);
                                    } else {
                                        mTextViewActivationStatus.setTextColor(Color.RED);
                                        if ("serial_already_used".equals(result)) {
                                            mTextViewActivationStatus.setText("هذا الرقم التسلسلي مستخدم بالفعل على جهاز آخر");
                                        } else if ("invalid_format".equals(result)) {
                                            mTextViewActivationStatus.setText("الرقم التسلسلي غير صحيح");
                                        } else if ("blocked".equals(result)) {
                                            mTextViewActivationStatus.setText("تم حظر هذا الجهاز. يرجى التواصل مع الدعم.");
                                        } else {
                                            mTextViewActivationStatus.setText("فشل التفعيل: " + (error != null ? error : "خطأ غير معروف"));
                                        }
                                    }
                                }
                            });
                        }
                    });
                }
            });
        }
        mDigitalClock = findViewById(R.id.digitalClock);
        mDateText = findViewById(R.id.textViewDate);
        mHijriText = findViewById(R.id.textViewHijriDate);
        mTextViewEvents = findViewById(R.id.textViewEvents);
        mClockFace = findViewById(R.id.imageViewClockFace);
        mHoursHand = findViewById(R.id.imageViewClockHoursHand);
        mMinutesHand = findViewById(R.id.imageViewClockMinutesHand);
        mSecondsHand = findViewById(R.id.imageViewClockSecondsHand);
        mBatteryView = findViewById(R.id.linearLayoutBattery);
        mBatteryText = findViewById(R.id.textViewBattery);
        mBatteryImage = findViewById(R.id.imageViewBattery);
        mBatteryImage.setImageResource(R.drawable.ic_battery_full_white_24dp);
        mAlarmView = findViewById(R.id.linearLayoutAlarm);
        mAlarmText = findViewById(R.id.textViewAlarm);
        mAlarmImage = findViewById(R.id.imageViewAlarm);
        mAlarmImage.setImageResource(R.drawable.ic_alarm_white_24dp);
        mNotificationsView = findViewById(R.id.linearLayoutNotifications);
        mNotificationsText = findViewById(R.id.textViewNotifications);
        mNotificationsImage = findViewById(R.id.imageViewNotifications);
        mNotificationsImage.setImageResource(R.drawable.ic_notifications_white_24dp);
        mWeatherView = findViewById(R.id.linearLayoutWeather);
        mWeatherText = findViewById(R.id.textViewWeather);

        // init settings
        mSharedPref = c.getSharedPreferences(SettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        loadSettings();

        // instant refresh so that the user does not see "00:00:00"
        updateEventView();
        if(!mHighRefreshRate) {
            updateClock();
        }

        // init layout listener
        initLayoutListener();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        // init text to speech
        mTts = new TextToSpeech(getContext(), new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status != TextToSpeech.SUCCESS) {
                    mTts = null;
                }
            }
        });

        // connect to notification service
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            mNotificationBroadcastReceiver = new NotificationBroadcastReceiver();
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(NotificationListener.BROADCAST_ACTION);
            ContextCompat.registerReceiver(getContext(), mNotificationBroadcastReceiver, intentFilter, ContextCompat.RECEIVER_EXPORTED);
        }

        // Auto-sync wallpapers from Supabase on startup.
        // Use isSyncEnabled() (not isEnabled()) so the very first download also runs on a
        // fresh install, when no wallpapers are cached locally yet.
        if(mWallpaperRepo != null && mWallpaperRepo.isSyncEnabled()) {
            autoDownloadWallpapers(3);
        }
        // Keep pulling changes from the server while the app runs (see mPeriodicSyncRunnable).
        startPeriodicSync();
    }

    /**
     * Download the wallpaper playlist (public/global + device-specific) from Supabase and
     * refresh the screen when done. Retries a few times so a transient network failure right
     * after install/activation doesn't leave the device with no wallpapers until the owner
     * manually presses sync in the settings.
     *
     * @param attemptsLeft how many more tries to make on failure (pass e.g. 3)
     */
    private void autoDownloadWallpapers(final int attemptsLeft) {
        if(mWallpaperRepo == null) return;
        mWallpaperRepo.sync(new WallpaperRepo.SyncCallback() {
            @Override
            public void done(final boolean success, final int count, String error) {
                post(new Runnable() {
                    @Override
                    public void run() {
                        if (success) {
                            loadSettings();
                        } else if (attemptsLeft > 1) {
                            // retry after a short back-off
                            postDelayed(new Runnable() {
                                @Override
                                public void run() {
                                    autoDownloadWallpapers(attemptsLeft - 1);
                                }
                            }, 5000);
                        }
                    }
                });
            }
        });
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        // destroy tts service connection
        if(mTts != null) {
            mTts.stop();
            mTts.shutdown();
        }

        getContext().unregisterReceiver(mNotificationBroadcastReceiver);
        removeCallbacks(mPeriodicSyncRunnable);
        removeCallbacks(mWeatherRefreshRunnable);
        unregisterTempSensor();
        unregisterLocation();
    }

    private void initLayoutListener() {
        mMainViewDefaultTemp = null;
        mBottomBarDefaultTemp = null;
        if(mMainViewDefaultX != null && mMainViewDefaultY != null) {
            mMainView.setX(mMainViewDefaultX);
            mMainView.setY(mMainViewDefaultY);
        }
        if(mBottomBarDefaultX != null) {
            mBottomBar.setX(mBottomBarDefaultX);
        }
        mMainView.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() { // layout has happened here
                        if(mMainViewDefaultTemp != null && mMainViewDefaultTemp == mMainView.getX()) {
                            mMainView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            mMainViewDefaultX = mMainView.getX();
                            mMainViewDefaultY = mMainView.getY();
                        }
                        mMainViewDefaultTemp = mMainView.getX();
                    }
                });
        mBottomBar.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() { // layout has happened here
                        if(mBottomBarDefaultTemp != null && mBottomBarDefaultTemp == mBottomBar.getX()) {
                            mBottomBar.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                            mBottomBarDefaultX = mBottomBar.getX();
                            Log.w("POSI", mBottomBar.getX()+"");
                        }
                        mBottomBarDefaultTemp = mBottomBar.getX();
                    }
                });
    }

    static String getDefaultDateFormat(Context c) {
        final SimpleDateFormat sdfSystem = (SimpleDateFormat) DateFormat.getDateFormat(c);
        String strDatePattern = "EEEE, " + sdfSystem.toLocalizedPattern(); // day of week followed by localized date
        if(!strDatePattern.contains("yyyy")) {
            // devices with API level 17 or below return date format already with yyyy,
            // for all other devices we manually replace yy with yyyy
            strDatePattern = strDatePattern.replace("yy", "yyyy");
        }
        return strDatePattern;
    }

    @SuppressLint("SimpleDateFormat")
    private void updateClock() {
        final Calendar cal = Calendar.getInstance();

        if(mShowDate) {
            try {
                String strDatePattern = mSharedPref.getString("date-format", getDefaultDateFormat(getContext()));
                if(mSharedPref.getBoolean("use-hijri", false)
                && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    mDateText.setText(localizeDigits(
                            DateTimeFormatter.ofPattern(strDatePattern, Locale.getDefault())
                                .format(HijrahDate.now())
                    ));
                } else {
                    mDateText.setText(localizeDigits(
                            new SimpleDateFormat(strDatePattern, new Locale("ar"))
                                .format(cal.getTime())
                    ));
                }
            } catch(IllegalArgumentException ignored) {
                mDateText.setText("---");
            }
        }

        // Hijri (Islamic) date shown under the Gregorian date
        if(mHijriText != null) {
            if(mShowHijri && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                try {
                    String h = DateTimeFormatter.ofPattern("d MMMM yyyy", new Locale("ar"))
                            .format(HijrahDate.now()) + " هـ";
                    mHijriText.setText(localizeDigits(h));
                    mHijriText.setVisibility(View.VISIBLE);
                } catch(Exception ignored) {
                    mHijriText.setVisibility(View.GONE);
                }
            } else {
                mHijriText.setVisibility(View.GONE);
            }
        }

        if(mShowDigital) {
            final SimpleDateFormat sdfTime = new SimpleDateFormat(mFormat24hrs ? "H:mm" : "h:mm");
            final SimpleDateFormat sdfSeconds = new SimpleDateFormat("ss");
            mDigitalClock.setText(localizeDigits(sdfTime.format(cal.getTime())), localizeDigits(sdfSeconds.format(cal.getTime())));
        }

        if(mShowAnalog) {
            float secRotation = 0;
            float minRotation = 0;
            float hrsRotation = (cal.get(Calendar.HOUR) + ((float)cal.get(Calendar.MINUTE)/60)) * 360 / 12;
            if(mSmoothHands) {
                secRotation = (cal.get(Calendar.SECOND) + ((float)cal.get(Calendar.MILLISECOND)/1000)) * 360 / 60;
                minRotation = (cal.get(Calendar.MINUTE) + ((float)cal.get(Calendar.SECOND)/60)) * 360 / 60;
            } else {
                secRotation = cal.get(Calendar.SECOND) * 360f / 60;
                minRotation = cal.get(Calendar.MINUTE) * 360f / 60;
            }
            mSecondsHand.setRotation(secRotation);
            mMinutesHand.setRotation(minRotation);
            mHoursHand.setRotation(hrsRotation);
        }
    }
    private void startTimer() {
        TimerTask taskAnalogClock = new TimerTask() {
            @SuppressLint("SimpleDateFormat")
            @Override
            public void run() {
                post(new Runnable() {
                    @Override
                    public void run() {
                        updateClock();
                    }
                });
            }
        };
        TimerTask taskCalendarUpdate = new TimerTask() {
            @Override
            public void run() {
                post(new Runnable() {
                    @Override
                    public void run() {
                        updateEventView();
                    }
                });
            }
        };
        TimerTask taskCheckEvent = new TimerTask() {
            @Override
            public void run() {
                post(new Runnable() {
                    final Calendar cal = Calendar.getInstance();
                    @Override
                    public void run() {
                        if(mEvents != null) {
                            for(Event e : mEvents) {
                                if(cal.get(Calendar.HOUR_OF_DAY) == e.triggerHour
                                        && cal.get(Calendar.MINUTE) == e.triggerMinute
                                        && cal.get(Calendar.SECOND) == 0) {
                                    doEventStuff(e);
                                }
                            }
                        }
                        refreshNightMode();
                    }
                });
            }
        };
        TimerTask taskBurnInAvoidRotation = new TimerTask() {
            @Override
            public void run() {
                post(new Runnable() {
                    @Override
                    public void run() {
                        if(mMainViewDefaultX == null || mMainViewDefaultY == null || mBottomBarDefaultX == null) {
                            return;
                        }
                        if(mBurnInPrevention) {
                            int randomDeviationX = mRand.nextInt(BURN_IN_PREVENTION_DEVIATION*2) - BURN_IN_PREVENTION_DEVIATION;
                            int randomDeviationY = mRand.nextInt(BURN_IN_PREVENTION_DEVIATION*2) - BURN_IN_PREVENTION_DEVIATION;
                            mMainView.animate()
                                    .x(mMainViewDefaultX + randomDeviationX)
                                    .y(mMainViewDefaultY + randomDeviationY)
                                    .setDuration(1000)
                                    .start();
                            mBottomBar.animate()
                                    .x(mBottomBarDefaultX + randomDeviationX)
                                    .setDuration(1000)
                                    .start();
                        } else {
                            // reset position after setting changed
                            mMainView.animate()
                                    .x(mMainViewDefaultX)
                                    .y(mMainViewDefaultY)
                                    .setDuration(1000)
                                    .start();
                            mBottomBar.animate()
                                    .x(mBottomBarDefaultX)
                                    .setDuration(1000)
                                    .start();
                        }
                    }
                });
            }
        };

        mTimerAnalogClock = new Timer(false);
        mTimerCalendarUpdate = new Timer(false);
        mTimerCheckEvent = new Timer(false);
        mTimerBurnInPreventionRotation = new Timer(false);
        mTimerAnalogClock.schedule(taskAnalogClock, 0, mHighRefreshRate ? 100 : 1000);
        mTimerCalendarUpdate.schedule(taskCalendarUpdate, 0, 10000);
        mTimerCheckEvent.schedule(taskCheckEvent, 0, 1000);
        mTimerBurnInPreventionRotation.schedule(taskBurnInAvoidRotation, 1000, BURN_IN_PREVENTION_CHANGE);
    }

    void loadSettings() {
        if(mActivity != null) {
            if(mSharedPref.getBoolean("keep-screen-on", true)) {
                mActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                Log.i("SCREEN", "Keep ON");
            } else {
                mActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                Log.i("SCREEN", "Keep OFF");
            }
        }

        refreshNightMode();

        mBurnInPrevention = mSharedPref.getBoolean("burn-in-prevention", mBurnInPrevention);

        Gson gson = new Gson();
        mEvents = gson.fromJson(mSharedPref.getString("events",""), Event[].class);
        mShowAlarms = mSharedPref.getBoolean("show-alarms", false);

        mFormat24hrs = mSharedPref.getBoolean("24hrs", false);
        mArabicDigits = mSharedPref.getBoolean("arabic-digits", false);
        mShowHijri = mSharedPref.getBoolean("show-hijri-date", true);

        if(mSharedPref.getBoolean("show-analog", true)) {
            mShowAnalog = true;
            findViewById(R.id.analogClockContainer).setVisibility(View.VISIBLE);
        } else {
            mShowAnalog = false;
            findViewById(R.id.analogClockContainer).setVisibility(View.GONE);
        }

        if(mSharedPref.getBoolean("show-digital", true)) {
            mShowDigital = true;
            mDigitalClock.setVisibility(View.VISIBLE);
        } else {
            mShowDigital = false;
            mDigitalClock.setVisibility(View.GONE);
        }

        if(mSharedPref.getBoolean("show-date", true)) {
            mShowDate = true;
            mDateText.setVisibility(View.VISIBLE);
            mDigitalClock.setGravity(Gravity.BOTTOM);
        } else {
            mShowDate = false;
            mDateText.setVisibility(View.GONE);
            mDigitalClock.setGravity(Gravity.NO_GRAVITY);
        }

        if(!mSharedPref.getBoolean("show-digital", true) && !mSharedPref.getBoolean("show-date", true))
            findViewById(R.id.digitalClockAndDateContainer).setVisibility(GONE);
        else
            findViewById(R.id.digitalClockAndDateContainer).setVisibility(VISIBLE);

        if(mSharedPref.getBoolean("show-seconds-analog", true))
            findViewById(R.id.imageViewClockSecondsHand).setVisibility(View.VISIBLE);
        else
            findViewById(R.id.imageViewClockSecondsHand).setVisibility(View.GONE);

        if(mSharedPref.getBoolean("show-seconds-digital", true))
            mDigitalClock.setShowSec(true);
        else
            mDigitalClock.setShowSec(false);

        mSmoothHands = mSharedPref.getBoolean("smooth-hands", true);
        mHighRefreshRate = mSmoothHands
            && mSharedPref.getBoolean("show-seconds-analog", true);

        // init font
        FontOption fontOptionClock = FontOptions.getById(mSharedPref.getInt("font-digital-clock", FontOptions.DSEG7_CLASSIC));
        // the 7-segment DSEG fonts have no Arabic-Indic digits, so use Cairo when Arabic numerals are selected
        if(mArabicDigits) fontOptionClock = FontOptions.getById(FontOptions.CAIRO_REGULAR);
        mFontClock = ResourcesCompat.getFont(getContext(), fontOptionClock.mResourceId);
        mDigitalClock.setTypeface(mFontClock, fontOptionClock.mXCorr);
        FontOption fontOptionDate = FontOptions.getById(mSharedPref.getInt("font-digital-date", FontOptions.CAIRO_REGULAR));
        mFontDate = ResourcesCompat.getFont(getContext(), fontOptionDate.mResourceId);
        mDateText.setTypeface(mFontDate);
        if(mHijriText != null) mHijriText.setTypeface(mFontDate);
        FontOption fontOptionEvents = FontOptions.getById(mSharedPref.getInt("font-events", FontOptions.CAIRO_REGULAR));
        mFontEvents = ResourcesCompat.getFont(getContext(), fontOptionEvents.mResourceId);
        mTextViewEvents.setTypeface(mFontEvents);

        // init custom digital color
        int colorDigitalClock = mSharedPref.getInt("color-digital-clock", 0xffffffff);
        int colorDigitalDate = mSharedPref.getInt("color-digital-date", 0xffffffff);
        int colorEvents = mSharedPref.getInt("color-events", colorDigitalDate);
        mDigitalClock.setColor(colorDigitalClock);
        mDateText.setColor(colorDigitalDate);
        if(mHijriText != null) mHijriText.setTextColor(colorDigitalDate);
        mTextViewEvents.setTextColor(colorEvents);
        mBatteryText.setTextColor(colorEvents);
        mBatteryImage.setColorFilter(colorEvents, PorterDuff.Mode.SRC_ATOP);
        mAlarmText.setTextColor(colorEvents);
        mAlarmImage.setColorFilter(colorEvents, PorterDuff.Mode.SRC_ATOP);
        mNotificationsText.setTextColor(colorEvents);
        mNotificationsImage.setColorFilter(colorEvents, PorterDuff.Mode.SRC_ATOP);
        mWeatherText.setTextColor(colorEvents);

        // current weather (outside) + inside/car temperature (device ambient sensor)
        if(mSharedPref.getBoolean("show-weather", true)) {
            registerTempSensor();
            registerLocation();   // start listening to GPS so the weather follows the car
            refreshWeather();
            updateWeatherText();
            removeCallbacks(mWeatherRefreshRunnable);
            postDelayed(mWeatherRefreshRunnable, WEATHER_REFRESH_INTERVAL_MS);
        } else {
            unregisterTempSensor();
            unregisterLocation();
            removeCallbacks(mWeatherRefreshRunnable);
            mOutsideWeather = null;
            mWeatherView.setVisibility(View.GONE);
        }

        // init custom analog color
        if(mSharedPref.getBoolean("own-color-analog-clock-face", false)) {
            mClockFace.setColorFilter(mSharedPref.getInt("color-analog-face", 0xffffffff), PorterDuff.Mode.SRC_ATOP);
        } else {
            mClockFace.clearColorFilter();
        }
        if(mSharedPref.getBoolean("own-color-analog-hours", false)) {
            mHoursHand.setColorFilter(mSharedPref.getInt("color-analog-hours", 0xffffffff), PorterDuff.Mode.SRC_ATOP);
        } else {
            mHoursHand.clearColorFilter();
        }
        if(mSharedPref.getBoolean("own-color-analog-minutes", false)) {
            mMinutesHand.setColorFilter(mSharedPref.getInt("color-analog-minutes", 0xffffffff), PorterDuff.Mode.SRC_ATOP);
        } else {
            mMinutesHand.clearColorFilter();
        }
        if(mSharedPref.getBoolean("own-color-analog-seconds", false)) {
            mSecondsHand.setColorFilter(mSharedPref.getInt("color-analog-seconds", 0xffffffff), PorterDuff.Mode.SRC_ATOP);
        } else {
            mSecondsHand.clearColorFilter();
        }

        // init custom background color
        mRootView.setBackgroundColor(mSharedPref.getInt("color-back", 0xff000000));

        // init custom background image
        StorageControl sc = new StorageControl(getContext());
        mBackgroundImage.setImageBitmap(null);
        File img = sc.getStorage(StorageControl.FILENAME_BACKGROUND_IMAGE);
        if(img.exists()) {
            try {
                Bitmap myBitmap = BitmapFactory.decodeFile(img.getAbsolutePath());
                mBackgroundImage.setImageBitmap(myBitmap);
                if(mSharedPref.getBoolean("back-stretch", false)) {
                    mBackgroundImage.setScaleType(ImageView.ScaleType.FIT_XY);
                } else {
                    mBackgroundImage.setScaleType(ImageView.ScaleType.CENTER_CROP);
                }
            } catch(Exception ignored) {
                Toast.makeText(getContext(), "Image corrupted or too large", Toast.LENGTH_SHORT).show();
            }
        }

        // init wallpaper slideshow (overrides the static background when enabled)
        if(mWallpaperRepo != null && mWallpaper != null) {
            mWallpaperRepo.load();
            if(mWallpaperRepo.isActive()) {
                if(mWallpaperRepo.isEnabled()) {
                    mWallpaper.setVisibility(View.VISIBLE);
                    mBackgroundImage.setVisibility(View.GONE);
                    WallpaperItem current = mWallpaperRepo.current();
                    mWallpaper.showItem(current, 0);
                    // record it, so restarting the car comes back to this very wallpaper
                    mWallpaperRepo.savePosition(current);
                } else {
                    mWallpaper.setVisibility(View.GONE);
                    mWallpaper.clearAll();
                    mBackgroundImage.setVisibility(View.VISIBLE);
                }
            } else {
                mWallpaper.setVisibility(View.GONE);
                mWallpaper.clearAll();
                mBackgroundImage.setVisibility(View.GONE);
            }
            // start/stop the automatic wallpaper switching according to the settings
            updateAutoSwitch();
        }

        // Apply activation layout visibility
        if(mWallpaperRepo != null && mLayoutActivation != null && mTextViewActivationDeviceId != null) {
            if(!mWallpaperRepo.isActive()) {
                mLayoutActivation.setVisibility(View.VISIBLE);
                mTextViewActivationDeviceId.setText(mWallpaperRepo.getDeviceId());
            } else {
                mLayoutActivation.setVisibility(View.GONE);
            }
        }

        // apply clock overlay visibility, position and size
        applyClockLayout();

        // auto-contrast clock color against the wallpaper behind it
        scheduleContrast();

        // init (custom) analog clock images
        mClockFace.setImageResource(R.drawable.analog_classic_bg);
        mHoursHand.setImageResource(R.drawable.analog_classic_h);
        mMinutesHand.setImageResource(R.drawable.analog_classic_m);
        mSecondsHand.setImageResource(R.drawable.analog_classic_s);

        img = sc.getStorage(StorageControl.FILENAME_CLOCK_FACE);
        if(img.exists()) {
            try {
                Bitmap myBitmap = BitmapFactory.decodeFile(img.getAbsolutePath());
                mClockFace.setImageBitmap(myBitmap);
            } catch(Exception ignored) {
                Toast.makeText(getContext(), "Image corrupted or too large", Toast.LENGTH_SHORT).show();
            }
        } else {
            GraphicItem gi = GraphicSelectionAdapter.getById(mSharedPref.getInt("clock-analog-face", 0), GraphicSelectionAdapter.CLOCK_FACES);
            if(gi != null && gi.mGraphicResourceId != null)
                mClockFace.setImageResource(gi.mGraphicResourceId);
        }

        img = sc.getStorage(StorageControl.FILENAME_HOURS_HAND);
        if(img.exists()) {
            try {
                Bitmap myBitmap = BitmapFactory.decodeFile(img.getAbsolutePath());
                mHoursHand.setImageBitmap(myBitmap);
            } catch(Exception ignored) {
                Toast.makeText(getContext(), "Image corrupted or too large", Toast.LENGTH_SHORT).show();
            }
        } else {
            GraphicItem gi = GraphicSelectionAdapter.getById(mSharedPref.getInt("clock-analog-hours", 0), GraphicSelectionAdapter.HOUR_HANDS);
            if(gi != null && gi.mGraphicResourceId != null)
                mHoursHand.setImageResource(gi.mGraphicResourceId);
        }

        img = sc.getStorage(StorageControl.FILENAME_MINUTES_HAND);
        if(img.exists()) {
            try {
                Bitmap myBitmap = BitmapFactory.decodeFile(img.getAbsolutePath());
                mMinutesHand.setImageBitmap(myBitmap);
            } catch(Exception ignored) {
                Toast.makeText(getContext(), "Image corrupted or too large", Toast.LENGTH_SHORT).show();
            }
        } else {
            GraphicItem gi = GraphicSelectionAdapter.getById(mSharedPref.getInt("clock-analog-minutes", 0), GraphicSelectionAdapter.MINUTE_HANDS);
            if(gi != null && gi.mGraphicResourceId != null)
                mMinutesHand.setImageResource(gi.mGraphicResourceId);
        }

        img = sc.getStorage(StorageControl.FILENAME_SECONDS_HAND);
        if(img.exists()) {
            try {
                Bitmap myBitmap = BitmapFactory.decodeFile(img.getAbsolutePath());
                mSecondsHand.setImageBitmap(myBitmap);
            } catch(Exception ignored) {
                Toast.makeText(getContext(), "Image corrupted or too large", Toast.LENGTH_SHORT).show();
            }
        } else {
            GraphicItem gi = GraphicSelectionAdapter.getById(mSharedPref.getInt("clock-analog-seconds", 0), GraphicSelectionAdapter.SECOND_HANDS);
            if(gi != null && gi.mGraphicResourceId != null)
                mSecondsHand.setImageResource(gi.mGraphicResourceId);
        }
    }

    private final SensorEventListener mTempListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if(event.values != null && event.values.length > 0) {
                mInsideTemp = event.values[0];
                updateWeatherText();
            }
        }
        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) { }
    };

    private void registerTempSensor() {
        if(mSensorManager == null) {
            mSensorManager = (SensorManager) getContext().getSystemService(Context.SENSOR_SERVICE);
        }
        if(mSensorManager != null && mTempSensor == null) {
            mTempSensor = mSensorManager.getDefaultSensor(Sensor.TYPE_AMBIENT_TEMPERATURE);
        }
        if(mSensorManager != null && mTempSensor != null) {
            mSensorManager.registerListener(mTempListener, mTempSensor, SensorManager.SENSOR_DELAY_NORMAL);
        }
    }

    private void unregisterTempSensor() {
        if(mSensorManager != null && mTempSensor != null) {
            mSensorManager.unregisterListener(mTempListener);
        }
    }

    private boolean hasLocationPermission() {
        return ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(getContext(), Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private final LocationListener mLocationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            if(location == null) return;
            // only re-query the weather API once the car has moved a meaningful distance
            boolean moved = mLastLocation == null || mLastLocation.distanceTo(location) > 3000; // > 3 km
            mLastLocation = location;
            if(moved) refreshWeather();
        }
        @Override public void onStatusChanged(String provider, int status, android.os.Bundle extras) { }
        @Override public void onProviderEnabled(String provider) { }
        @Override public void onProviderDisabled(String provider) { }
    };

    @SuppressLint("MissingPermission")
    private void registerLocation() {
        if(!hasLocationPermission()) return; // fall back silently to city/IP
        if(mLocationManager == null) {
            mLocationManager = (LocationManager) getContext().getSystemService(Context.LOCATION_SERVICE);
        }
        if(mLocationManager == null) return;
        // seed with the best last-known fix so we don't wait for the first update
        try {
            Location gps = mLocationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            Location net = mLocationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);
            if(gps != null) mLastLocation = gps;
            if(net != null && (mLastLocation == null || net.getTime() > mLastLocation.getTime())) mLastLocation = net;
        } catch(Exception ignored) { }
        // subscribe to updates from whichever providers exist on this device / head unit
        try {
            if(mLocationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                mLocationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 60000, 3000, mLocationListener);
            }
        } catch(Exception ignored) { }
        try {
            if(mLocationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                mLocationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 60000, 3000, mLocationListener);
            }
        } catch(Exception ignored) { }
    }

    private void unregisterLocation() {
        if(mLocationManager != null) {
            try { mLocationManager.removeUpdates(mLocationListener); } catch(Exception ignored) { }
        }
    }

    /** Fetch outside weather using the real GPS location when available, else the typed city / IP. */
    private void refreshWeather() {
        if(!mSharedPref.getBoolean("show-weather", true)) return;
        Double lat = null, lon = null;
        if(hasLocationPermission() && mLastLocation != null) {
            lat = mLastLocation.getLatitude();
            lon = mLastLocation.getLongitude();
        }
        Weather.fetch(mSharedPref.getBoolean("weather-celsius", true), lat, lon,
                mSharedPref.getString("weather-city", "Doha"), new Weather.WeatherCallback() {
            @Override
            public void onResult(String text) {
                mOutsideWeather = text;
                updateWeatherText();
            }
        });
    }

    /** Compose the weather line: outside weather (API) + inside/car temperature (device sensor). */
    private void updateWeatherText() {
        StringBuilder sb = new StringBuilder();
        if(mOutsideWeather != null) sb.append(mOutsideWeather);
        if(mInsideTemp != null) {
            if(sb.length() > 0) sb.append("    ");
            sb.append("🚗 ").append(Math.round(mInsideTemp)).append("°");
        }
        if(sb.length() > 0) {
            mWeatherText.setText(localizeDigits(sb.toString()));
            mWeatherView.setVisibility(View.VISIBLE);
        } else {
            mWeatherView.setVisibility(View.GONE);
        }
    }

    /** Convert digits to Arabic-Indic or Western according to the user setting. */
    String localizeDigits(String s) {
        if(s == null) return null;
        StringBuilder sb = new StringBuilder(s.length());
        for(char c : s.toCharArray()) {
            if(mArabicDigits) {
                if(c >= '0' && c <= '9') c = (char) ('٠' + (c - '0'));
            } else {
                if(c >= '٠' && c <= '٩') c = (char) ('0' + (c - '٠'));
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /** Apply one color to all clock elements (used by auto-contrast). */
    private void applyClockColor(int c) {
        mDigitalClock.setColor(c);
        mDateText.setColor(c);
        if(mHijriText != null) mHijriText.setTextColor(c);
        mTextViewEvents.setTextColor(c);
        mBatteryText.setTextColor(c);
        mBatteryImage.setColorFilter(c, PorterDuff.Mode.SRC_ATOP);
        mAlarmText.setTextColor(c);
        mAlarmImage.setColorFilter(c, PorterDuff.Mode.SRC_ATOP);
        mNotificationsText.setTextColor(c);
        mNotificationsImage.setColorFilter(c, PorterDuff.Mode.SRC_ATOP);
        mWeatherText.setTextColor(c);
        mClockFace.setColorFilter(c, PorterDuff.Mode.SRC_ATOP);
        mHoursHand.setColorFilter(c, PorterDuff.Mode.SRC_ATOP);
        mMinutesHand.setColorFilter(c, PorterDuff.Mode.SRC_ATOP);
        mSecondsHand.setColorFilter(c, PorterDuff.Mode.SRC_ATOP);
    }

    /** Pick black/white clock color so it contrasts with the wallpaper behind it. */
    void updateContrastColors() {
        if(!mSharedPref.getBoolean("auto-contrast", true)) return;
        int lum;
        if(mWallpaper != null && mWallpaper.getVisibility() == View.VISIBLE && mWallpaper.getWidth() > 0) {
            float rw = mWallpaper.getWidth(), rh = mWallpaper.getHeight();
            float l = Math.max(0f, mMainView.getX() / rw);
            float t = Math.max(0f, mMainView.getY() / rh);
            float r = Math.min(1f, (mMainView.getX() + mMainView.getWidth()) / rw);
            float b = Math.min(1f, (mMainView.getY() + mMainView.getHeight()) / rh);
            if(r <= l || b <= t) { l = 0f; t = 0.6f; r = 0.6f; b = 1f; }
            lum = mWallpaper.sampleLuminance(l, t, r, b);
        } else {
            int bg = mSharedPref.getInt("color-back", 0xff000000);
            lum = (int) (0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg));
        }
        if(lum < 0) return; // could not sample; keep colors
        applyClockColor(lum > 140 ? 0xff000000 : 0xffffffff);
    }

    private void scheduleContrast() {
        if(!mSharedPref.getBoolean("auto-contrast", true)) return;
        Runnable r = new Runnable() {
            @Override
            public void run() { updateContrastColors(); }
        };
        postDelayed(r, 700);
        postDelayed(r, 1700);
    }

    /** Hide or show the whole clock overlay (single-tap toggle). */
    public void setClockHidden(boolean hidden) {
        if(hidden) {
            mMainView.setVisibility(View.GONE);
            mBottomBar.setVisibility(View.GONE);
        } else {
            applyClockLayout();
        }
    }

    /** Position/size the clock overlay according to the user settings. */
    void applyClockLayout() {
        boolean overlay = mSharedPref.getBoolean("clock-overlay", true);
        if(!overlay) {
            mMainView.setVisibility(View.GONE);
            mBottomBar.setVisibility(View.GONE);
            return;
        }
        mMainView.setVisibility(View.VISIBLE);
        mBottomBar.setVisibility(View.VISIBLE);

        String position = mSharedPref.getString("clock-position", "bottom_left");
        String size = mSharedPref.getString("clock-size", "small");

        DisplayMetrics dm = getResources().getDisplayMetrics();
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) mMainView.getLayoutParams();
        int marginMain = getResources().getDimensionPixelSize(R.dimen.margin_main);

        if("full".equals(size)) {
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT;
            lp.gravity = Gravity.CENTER;
            lp.setMargins(marginMain, marginMain, marginMain, marginMain);
        } else {
            float f;
            switch(size) {
                case "medium": f = 0.72f; break;
                case "large":  f = 0.90f; break;
                default:       f = 0.55f; break; // small (a bit bigger/bolder by default)
            }
            lp.width = (int) (dm.widthPixels * f);
            lp.height = (int) (dm.heightPixels * f);
            lp.gravity = gravityForPosition(position);
            int m = (int) (24 * dm.density);
            lp.setMargins(m, m, m, m);
        }
        mMainView.setLayoutParams(lp);
        // reset burn-in defaults so they get re-captured for the new position
        mMainViewDefaultX = null;
        mMainViewDefaultY = null;
        mBottomBarDefaultX = null;
    }

    private int gravityForPosition(String position) {
        switch(position) {
            case "center":       return Gravity.CENTER;
            case "bottom_right": return Gravity.BOTTOM | Gravity.END;
            case "top_left":     return Gravity.TOP | Gravity.START;
            case "top_right":    return Gravity.TOP | Gravity.END;
            case "bottom_left":
            default:             return Gravity.BOTTOM | Gravity.START;
        }
    }

    /** Whether the automatic wallpaper switching should currently be running. */
    private boolean isAutoSwitchActive() {
        return mSharedPref != null && mSharedPref.getBoolean(WallpaperRepo.PREF_AUTO_SWITCH, false)
                && mWallpaperRepo != null && mWallpaperRepo.isEnabled() && mWallpaperRepo.isActive();
    }

    /** How long the current wallpaper stays on screen, as picked in the settings. */
    private long autoSwitchIntervalMs() {
        return WallpaperRepo.getAutoSwitchIntervalMs(mSharedPref);
    }

    /** (Re)start or stop the auto-switch timer based on the current settings. */
    private void updateAutoSwitch() {
        removeCallbacks(mAutoSwitchRunnable);
        if(isAutoSwitchActive()) {
            postDelayed(mAutoSwitchRunnable, autoSwitchIntervalMs());
        }
    }

    /** Switch to the next wallpaper with a left-swipe animation. */
    public void nextWallpaper() {
        if(mWallpaperRepo != null && mWallpaperRepo.isEnabled()) {
            mWallpaper.showItem(mWallpaperRepo.next(), 1);
            scheduleContrast();
        }
    }

    /** Switch to the previous wallpaper with a right-swipe animation. */
    public void prevWallpaper() {
        if(mWallpaperRepo != null && mWallpaperRepo.isEnabled()) {
            mWallpaper.showItem(mWallpaperRepo.prev(), -1);
            scheduleContrast();
        }
    }

    void refreshNightMode() {
        WindowManager.LayoutParams layout = null;
        if(mActivity != null) layout = mActivity.getWindow().getAttributes();

        Calendar c = Calendar.getInstance();
        int time = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE);
        int timeStart = mSharedPref.getInt("dark-mode-start", 0);
        int timeEnd = mSharedPref.getInt("dark-mode-end", 0);
        if(mSharedPref.getBoolean("dark-mode", false)
                && ((timeStart == timeEnd)
                    || (timeStart < timeEnd && timeStart <= time && timeEnd >= time)
                    || (timeStart > timeEnd && (time < 12*60 || timeStart <= time) && (time > 12*60 || timeEnd >= time))
                )
        ) {
            // in normal mode, we can set the display brightness to lowest (not possible in screensaver mode)
            if(layout != null) {
                layout.screenBrightness = 0;
                mActivity.getWindow().setAttributes(layout);
                Log.i("SCREEN", "Dark Mode enabled: set display brightness to lowest");
            }
            // dim the colors of all UI elements
            dimClockView(mRootView, true);
        } else {
            if(layout != null) {
                layout.screenBrightness = -1;
                mActivity.getWindow().setAttributes(layout);
            }
            dimClockView(mRootView, false);
        }
    }
    void dimClockView(View clockView, boolean enabled) {
        Paint paint = new Paint();
        paint.setColor(Color.WHITE);
        paint.setColorFilter(
                new PorterDuffColorFilter(enabled ? 0x40FFFFFF : 0xFFFFFFFF, PorterDuff.Mode.MULTIPLY)
        );
        clockView.setLayerType(View.LAYER_TYPE_HARDWARE, paint);
    }

    AlertDialog mEventDialog;
    Ringtone mEventRingtone;
    void doEventStuff(Event e) {
        if(e.playAlarm) {
            Uri notification = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM);
            mEventRingtone = RingtoneManager.getRingtone(getContext(), notification);
            mEventRingtone.play();
        }
        if(e.speakText != null && !e.speakText.trim().equals("")) {
            speak(e.speakText);
        }
        if(e.title != null && !e.title.trim().equals("")) {
            final AlertDialog.Builder dlg = new AlertDialog.Builder(getContext());
            if(e.title != null) dlg.setTitle(e.title);
            if(e.speakText != null) dlg.setMessage(e.speakText);
            dlg.setOnDismissListener(new DialogInterface.OnDismissListener() {
                @Override
                public void onDismiss(DialogInterface dialogInterface) {
                    if(mEventRingtone != null && mEventRingtone.isPlaying()) mEventRingtone.stop();
                }
            });
            dlg.setPositiveButton(getContext().getString(R.string.ok),
                    new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {
                            dialog.dismiss();
                        }
                    });
            dlg.setCancelable(true);
            mEventDialog = dlg.create();
            mEventDialog.show();
        }
        if(e.hideAfter > 0) {
            TimerTask taskEndAlarm = new TimerTask() {
                @Override
                public void run() {
                    if(mEventRingtone != null && mEventRingtone.isPlaying()) mEventRingtone.stop();
                    if(mEventDialog != null && mEventDialog.isShowing()) mEventDialog.dismiss();
                }
            };
            new Timer(false).schedule(taskEndAlarm, e.hideAfter * 1000L);
        }
    }

    private void speak(String text) {
        if(mTts != null) {
            if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null);
            } else {
                mTts.speak(text, TextToSpeech.QUEUE_FLUSH, null);
            }
        }
    }

    @SuppressLint("SetTextI18n")
    void updateBattery(int plugged, int level) {
        if((plugged == 0 && mSharedPref.getBoolean("show-battery-info", true))
        || (plugged != 0 && mSharedPref.getBoolean("show-battery-info-when-charging", false))) {
            mBatteryText.setText(localizeDigits(level + "%"));
            mBatteryView.setVisibility(View.VISIBLE);
            if(plugged == 0) {
                if(level < 5) {
                    mBatteryImage.setImageResource(R.drawable.ic_battery_0_bar_white_24dp);
                } else if(level < 10) {
                    mBatteryImage.setImageResource(R.drawable.ic_battery_1_bar_white_24dp);
                } else if(level < 25) {
                    mBatteryImage.setImageResource(R.drawable.ic_battery_2_bar_white_24dp);
                } else if(level < 40) {
                    mBatteryImage.setImageResource(R.drawable.ic_battery_3_bar_white_24dp);
                } else if(level < 55) {
                    mBatteryImage.setImageResource(R.drawable.ic_battery_4_bar_white_24dp);
                } else if(level < 70) {
                    mBatteryImage.setImageResource(R.drawable.ic_battery_5_bar_white_24dp);
                } else if(level < 85) {
                    mBatteryImage.setImageResource(R.drawable.ic_battery_6_bar_white_24dp);
                } else {
                    mBatteryImage.setImageResource(R.drawable.ic_battery_full_white_24dp);
                }
            } else {
                mBatteryImage.setImageResource(R.drawable.ic_battery_charging_white_24dp);
            }
        } else {
            mBatteryView.setVisibility(View.GONE);
        }
    }

    private final static int EVENT_WINDOW_MINUTES = 60;

    @SuppressLint("SetTextI18n")
    void updateEventView() {
        SimpleDateFormat sdfDisplay = new SimpleDateFormat(mFormat24hrs ? "HH:mm" : "h:mm", Locale.getDefault());
        SimpleDateFormat sdfDisplayWithDay = new SimpleDateFormat(mFormat24hrs ? "E HH:mm" : "E h:mm", Locale.getDefault());

        // clear previous event
        mTextViewEvents.setVisibility(View.GONE);
        mAlarmView.setVisibility(View.GONE);
        mNotificationsView.setVisibility(View.GONE);

        // 1. check app internal events
        if(mEvents != null) {
            boolean eventFound = false;
            Calendar calNow = Calendar.getInstance();
            long lastDiffMins = EVENT_WINDOW_MINUTES;
            for(Event e : mEvents) {
                if(!e.showOnScreen) continue;
                Calendar calEvent = Calendar.getInstance();
                calEvent.set(Calendar.HOUR_OF_DAY, e.triggerHour);
                calEvent.set(Calendar.MINUTE, e.triggerMinute);
                long diffMins = (calEvent.getTimeInMillis() - calNow.getTimeInMillis()) / 1000 / 60;
                // check if event is in current time window
                if(diffMins > 0 && diffMins < EVENT_WINDOW_MINUTES) {
                    // if multiple events are in current time window: show nearest event
                    if(diffMins < lastDiffMins) {
                        eventFound = true;
                        lastDiffMins = diffMins;
                        mTextViewEvents.setVisibility(View.VISIBLE);
                        mTextViewEvents.setText(e.toString());
                    }
                }
            }
            if(eventFound) {
                return;
            }
        }

        // 2. check system alarms
        if(mShowAlarms && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            final AlarmManager m = (AlarmManager) getContext().getSystemService(Context.ALARM_SERVICE);
            AlarmManager.AlarmClockInfo alarmInfo = m.getNextAlarmClock();
            if(alarmInfo != null) {
                Long systemAlarmTime = alarmInfo.getTriggerTime();
                mAlarmText.setText(
                        systemAlarmTime - System.currentTimeMillis() > 1000*60*60*24
                        ? sdfDisplayWithDay.format(systemAlarmTime) : sdfDisplay.format(systemAlarmTime)
                );
                mAlarmView.setVisibility(View.VISIBLE);
            }
        }

        // 3. check notification number
        if(mReceivedNotificationCount > 0) {
            mNotificationsText.setText(String.valueOf(mReceivedNotificationCount));
            mNotificationsView.setVisibility(View.VISIBLE);
        }

        // 4. check system calendar events
        if(ContextCompat.checkSelfPermission(getContext(), Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED) {
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.setTime(start.getTime());
            end.set(Calendar.HOUR_OF_DAY, 0);
            end.set(Calendar.MINUTE, 0);
            end.set(Calendar.SECOND, 0);
            end.add(Calendar.DATE, 1);

            String selection = "((dtstart >= "+start.getTimeInMillis()+") AND (dtstart <= "+end.getTimeInMillis()+"))";
            Cursor cursor = getContext().getContentResolver().query(
                    Uri.parse("content://com.android.calendar/events"),
                    new String[]{"calendar_id", "title", "description", "dtstart", "dtend", "eventLocation"},
                    selection, null, CalendarContract.Events.DTSTART+" ASC"
            );
            if(cursor == null) return;
            cursor.moveToFirst();
            int length = cursor.getCount();
            if(length != 0) {
                for(int i = 0; i < length; i++) {
                    mTextViewEvents.setVisibility(View.VISIBLE);
                    mTextViewEvents.setText(sdfDisplay.format(cursor.getLong(3)) + " " + cursor.getString(1));
                    //Log.e("EVENT", cursor.getString(1)+" "+sdfDisplay.format(cursor.getLong(3))+" "+sdfDisplay.format(systemAlarmTime));
                    //cursor.moveToNext();
                    cursor.close();
                    return;
                }
            }
        }
    }

    protected void pause() {
        unregisterTempSensor();
        removeCallbacks(mAutoSwitchRunnable);
        removeCallbacks(mPeriodicSyncRunnable);
        if(mWallpaper != null) mWallpaper.pauseVideo();
        mTimerAnalogClock.cancel();
        mTimerAnalogClock.purge();
        mTimerCalendarUpdate.cancel();
        mTimerCalendarUpdate.purge();
        mTimerCheckEvent.cancel();
        mTimerCheckEvent.purge();
        mTimerBurnInPreventionRotation.cancel();
        mTimerBurnInPreventionRotation.purge();
    }

    protected void resume() {
        loadSettings();
        initLayoutListener();
        startTimer();
        // Pull a fresh playlist on resume and keep re-syncing while visible.
        if(mWallpaperRepo != null && mWallpaperRepo.isSyncEnabled()) {
            autoDownloadWallpapers(1);
        }
        startPeriodicSync();
    }

    int mReceivedNotificationCount = 0;
    public class NotificationBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            // NotificationListener always sends count=1; ignore malformed or
            // spoofed broadcasts so the counter can never go backwards.
            int count = intent.getIntExtra("count", 0);
            if(count > 0) mReceivedNotificationCount += count;
        }
    }
    public void resetNotificationCount() {
        mReceivedNotificationCount = 0;
        updateEventView();
    }

}
