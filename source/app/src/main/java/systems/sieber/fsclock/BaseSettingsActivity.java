package systems.sieber.fsclock;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.TimePickerDialog;
import android.app.UiModeManager;
import android.appwidget.AppWidgetManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.widget.ImageView;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.accessibility.AccessibilityManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.gson.Gson;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;

public class BaseSettingsActivity extends AppCompatActivity {

    static final String SHARED_PREF_DOMAIN = "CLOCK";

    static final int PERMISSION_REQUEST = 0;
    static final int PICK_CLOCK_FACE_REQUEST = 1;
    static final int PICK_HOURS_HAND_REQUEST = 2;
    static final int PICK_MINUTES_HAND_REQUEST = 3;
    static final int PICK_SECONDS_HAND_REQUEST = 4;
    static final int PICK_BACKGROUND_REQUEST = 5;
    static final int PICK_WALLPAPERS_REQUEST = 6;

    StorageControl mStorage = new StorageControl(this);
    Gson mGson = new Gson();
    ArrayList<Event> mEvents = new ArrayList<>();
    SharedPreferences mSharedPref;

    LinearLayout mLinearLayoutPurchaseContainer;
    LinearLayout mLinearLayoutSettingsContainer;
    Button mButtonUnlockSettings;
    CheckBox mCheckBoxKeepScreenOn;
    CheckBox mCheckBoxShowBatteryInfo;
    CheckBox mCheckBoxShowBatteryInfoWhenCharging;
    CheckBox mCheckBoxBurnInPrevention;
    CheckBox mCheckBoxForceLandscape;
    CheckBox mCheckBoxDarkMode;
    Button mButtonDarkModeStart;
    Button mButtonDarkModeEnd;
    CheckBox mCheckBoxAnalogClockShow;
    CheckBox mCheckBoxAnalogClockShowSeconds;
    CheckBox mCheckBoxAnalogClockSmoothHands;
    Spinner mSpinnerDesignAnalogFace;
    Spinner mSpinnerDesignAnalogHours;
    Spinner mSpinnerDesignAnalogMinutes;
    Spinner mSpinnerDesignAnalogSeconds;
    CheckBox mCheckBoxDigitalClockShow;
    CheckBox mCheckBoxDateShow;
    EditText mEditTextDateFormat;
    RadioButton mRadioButtonGregorianCalendar;
    RadioButton mRadioButtonHijriCalendar;
    CheckBox mCheckBoxDigitalClockShowSeconds;
    CheckBox mCheckBoxDigitalClock24Format;
    CheckBox mCheckBoxArabicDigits;
    CheckBox mCheckBoxShowHijri;
    CheckBox mCheckBoxAutoContrast;
    View mColorChangerAnalogFace;
    View mColorPreviewAnalogFace;
    View mColorChangerAnalogHours;
    View mColorPreviewAnalogHours;
    View mColorChangerAnalogMinutes;
    View mColorPreviewAnalogMinutes;
    View mColorChangerAnalogSeconds;
    View mColorPreviewAnalogSeconds;
    int mColorAnalogFace;
    int mColorAnalogHours;
    int mColorAnalogMinutes;
    int mColorAnalogSeconds;
    boolean mCustomColorAnalogFace;
    boolean mCustomColorAnalogHours;
    boolean mCustomColorAnalogMinutes;
    boolean mCustomColorAnalogSeconds;
    View mColorChangerDigitalClock;
    View mColorPreviewDigitalClock;
    int mColorDigitalClock;
    Spinner mSpinnerDigitalClockFont;
    View mColorChangerDigitalDate;
    View mColorPreviewDigitalDate;
    int mColorDigitalDate;
    Spinner mSpinnerDigitalDateFont;
    View mColorChangerEvents;
    View mColorPreviewEvents;
    int mColorEvents;
    Spinner mSpinnerEventsFont;
    View mColorChangerBack;
    View mColorPreviewBack;
    int mColorBack;
    Spinner mSpinnerDesignBack;
    boolean mBackStretch;
    CheckBox mCheckBoxShowAlarms;
    CheckBox mCheckBoxShowWeather;
    EditText mEditTextWeatherCity;
    Button mButtonNewEvent;
    int mDarkModeStart = 0;
    int mDarkModeEnd = 0;

    // wallpaper slideshow + clock layout
    CheckBox mCheckBoxWallpaperEnabled;
    CheckBox mCheckBoxWallpaperAutoSwitch;
    EditText mEditTextWallpaperUrl;
    Button mButtonSyncWallpapers;
    Button mButtonRefreshWallpapers;
    TextView mTextViewWallpaperStatus;
    CheckBox mCheckBoxShowClockOverlay;
    Spinner mSpinnerClockPosition;
    Spinner mSpinnerClockSize;
    CheckBox mCheckBoxWallpaperLocal;
    Button mButtonAddLocalWallpapers;
    TextView mTextViewLocalFolder;
    WallpaperRepo mWallpaperRepo;
    UploadServer mUploadServer;
    TextView mTextViewSettingsDeviceId;
    TextView mTextViewSettingsMacAddress;
    TextView mPairStatus;
    static final String[] CLOCK_POSITION_VALUES = {"center", "bottom_left", "bottom_right", "top_left", "top_right"};
    static final String[] CLOCK_SIZE_VALUES = {"small", "medium", "large", "full"};

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // apply the insets as a margin to the view, so that elements at the bottom
        // of the ScrollView do not get hidden behind the navigation bar
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.textViewBuildInfo), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            //mlp.leftMargin = insets.left;
            //mlp.rightMargin = insets.right;
            mlp.bottomMargin = insets.bottom;
            v.setLayoutParams(mlp);
            // Return CONSUMED if you don't want the window insets to keep passing down to descendant views.
            return WindowInsetsCompat.CONSUMED;
        });

        // init toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if(getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);

        // display version & flavor
        try {
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            setTitle(getTitle() + " " + pInfo.versionName);
            ((TextView) findViewById(R.id.textViewBuildInfo)).setText(
                    getString(R.string.version) + " " + pInfo.versionName + " (" + pInfo.versionCode + ") " + BuildConfig.BUILD_TYPE + " " + BuildConfig.FLAVOR
            );
        } catch(PackageManager.NameNotFoundException ignored) { }

        // automatically check for an app update once when settings are opened
        checkForUpdate(true);

        // hide dream settings button if not supported
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR2
                || uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            findViewById(R.id.buttonDreamSettings).setVisibility(View.GONE);
        }

        // show screensaver note on FireTV
        if(getPackageManager().hasSystemFeature("amazon.hardware.fire_tv")) {
            findViewById(R.id.textViewFireTvNotes).setVisibility(View.VISIBLE);
            findViewById(R.id.buttonDreamSettings).setVisibility(View.GONE);
        }

        // show info regarding "High Contrast Text" system setting
        if(isHighContrastTextEnabled(getBaseContext())) {
            findViewById(R.id.textViewHighContrastNotes).setVisibility(View.VISIBLE);
        }

        // find views
        mLinearLayoutPurchaseContainer = findViewById(R.id.linearLayoutInAppPurchase);
        mLinearLayoutSettingsContainer = findViewById(R.id.linearLayoutSettings);
        mButtonUnlockSettings = findViewById(R.id.buttonUnlockSettings);
        mCheckBoxKeepScreenOn = findViewById(R.id.checkBoxKeepScreenOn);
        mCheckBoxShowBatteryInfo = findViewById(R.id.checkBoxShowBatteryInfo);
        mCheckBoxShowBatteryInfoWhenCharging = findViewById(R.id.checkBoxShowBatteryInfoWhenCharging);
        mCheckBoxBurnInPrevention = findViewById(R.id.checkBoxBurnInPrevention);
        mCheckBoxForceLandscape = findViewById(R.id.checkBoxForceLandscape);
        mCheckBoxDarkMode = findViewById(R.id.checkBoxDarkMode);
        mButtonDarkModeStart = findViewById(R.id.buttonDarkModeStart);
        mButtonDarkModeEnd = findViewById(R.id.buttonDarkModeEnd);
        mCheckBoxAnalogClockShow = findViewById(R.id.checkBoxShowAnalogClock);
        mCheckBoxAnalogClockShowSeconds = findViewById(R.id.checkBoxSecondsAnalog);
        mCheckBoxAnalogClockSmoothHands = findViewById(R.id.checkBoxAnalogSmoothHands);
        mSpinnerDesignAnalogFace = findViewById(R.id.spinnerDesignAnalogFace);
        mSpinnerDesignAnalogHours = findViewById(R.id.spinnerDesignAnalogHours);
        mSpinnerDesignAnalogMinutes = findViewById(R.id.spinnerDesignAnalogMinutes);
        mSpinnerDesignAnalogSeconds = findViewById(R.id.spinnerDesignAnalogSeconds);
        mCheckBoxDigitalClockShow = findViewById(R.id.checkBoxShowDigitalClock);
        mEditTextDateFormat = findViewById(R.id.editTextDateFormat);
        mRadioButtonGregorianCalendar = findViewById(R.id.radioButtonGregorianCalendar);
        mRadioButtonHijriCalendar = findViewById(R.id.radioButtonHijriCalendar);
        mCheckBoxDateShow = findViewById(R.id.checkBoxShowDate);
        mCheckBoxDigitalClockShowSeconds = findViewById(R.id.checkBoxSecondsDigital);
        mCheckBoxDigitalClock24Format = findViewById(R.id.checkBox24HrsFormat);
        mCheckBoxArabicDigits = findViewById(R.id.checkBoxArabicDigits);
        mCheckBoxShowHijri = findViewById(R.id.checkBoxShowHijri);
        mCheckBoxAutoContrast = findViewById(R.id.checkBoxAutoContrast);
        mColorChangerAnalogFace = findViewById(R.id.viewColorChangerAnalogFace);
        mColorPreviewAnalogFace = findViewById(R.id.viewColorPreviewAnalogFace);
        mColorChangerAnalogHours = findViewById(R.id.viewColorChangerAnalogHour);
        mColorPreviewAnalogHours = findViewById(R.id.viewColorPreviewAnalogHour);
        mColorChangerAnalogMinutes = findViewById(R.id.viewColorChangerAnalogMinute);
        mColorPreviewAnalogMinutes = findViewById(R.id.viewColorPreviewAnalogMinute);
        mColorChangerAnalogSeconds = findViewById(R.id.viewColorChangerAnalogSecond);
        mColorPreviewAnalogSeconds = findViewById(R.id.viewColorPreviewAnalogSecond);
        mColorChangerDigitalClock = findViewById(R.id.viewColorChangerDigitalClock);
        mColorPreviewDigitalClock = findViewById(R.id.viewColorPreviewDigitalClock);
        mSpinnerEventsFont = findViewById(R.id.spinnerEventsFont);
        mColorChangerEvents = findViewById(R.id.viewColorChangerEvents);
        mColorPreviewEvents = findViewById(R.id.viewColorPreviewEvents);
        mSpinnerDigitalClockFont = findViewById(R.id.spinnerDigitalClockFont);
        mColorChangerDigitalDate = findViewById(R.id.viewColorChangerDigitalDate);
        mColorPreviewDigitalDate = findViewById(R.id.viewColorPreviewDigitalDate);
        mSpinnerDigitalDateFont = findViewById(R.id.spinnerDigitalDateFont);
        mSpinnerDesignBack = findViewById(R.id.spinnerDesignBack);
        mColorChangerBack = findViewById(R.id.viewColorChangerBack);
        mColorPreviewBack = findViewById(R.id.viewColorPreviewBack);
        mCheckBoxShowAlarms = findViewById(R.id.checkBoxShowAlarms);
        mCheckBoxShowWeather = findViewById(R.id.checkBoxShowWeather);
        mEditTextWeatherCity = findViewById(R.id.editTextWeatherCity);
        mButtonNewEvent = findViewById(R.id.buttonNewEvent);
        mCheckBoxWallpaperEnabled = findViewById(R.id.checkBoxWallpaperEnabled);
        mCheckBoxWallpaperAutoSwitch = findViewById(R.id.checkBoxWallpaperAutoSwitch);
        mEditTextWallpaperUrl = findViewById(R.id.editTextWallpaperUrl);
        mButtonSyncWallpapers = findViewById(R.id.buttonSyncWallpapers);
        mButtonRefreshWallpapers = findViewById(R.id.buttonRefreshWallpapers);
        mTextViewWallpaperStatus = findViewById(R.id.textViewWallpaperStatus);
        mCheckBoxShowClockOverlay = findViewById(R.id.checkBoxShowClockOverlay);
        mSpinnerClockPosition = findViewById(R.id.spinnerClockPosition);
        mSpinnerClockSize = findViewById(R.id.spinnerClockSize);
        mCheckBoxWallpaperLocal = findViewById(R.id.checkBoxWallpaperLocal);
        mButtonAddLocalWallpapers = findViewById(R.id.buttonAddLocalWallpapers);
        mTextViewLocalFolder = findViewById(R.id.textViewLocalFolder);
        mWallpaperRepo = new WallpaperRepo(this);
        mTextViewSettingsDeviceId = findViewById(R.id.textViewSettingsDeviceId);
        mTextViewSettingsMacAddress = findViewById(R.id.textViewSettingsMacAddress);
        updateDeviceInfoViews();

        // init settings
        mSharedPref = getSharedPreferences(SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        mCheckBoxKeepScreenOn.setChecked( mSharedPref.getBoolean("keep-screen-on", true) );
        mCheckBoxShowBatteryInfo.setChecked( mSharedPref.getBoolean("show-battery-info", true) );
        mCheckBoxShowBatteryInfoWhenCharging.setChecked( mSharedPref.getBoolean("show-battery-info-when-charging", false) );
        mCheckBoxBurnInPrevention.setChecked( mSharedPref.getBoolean("burn-in-prevention", false) );
        mCheckBoxForceLandscape.setChecked( mSharedPref.getBoolean("force-landscape", false) );
        mCheckBoxDarkMode.setChecked( mSharedPref.getBoolean("dark-mode", false) );
        mDarkModeStart = mSharedPref.getInt("dark-mode-start", 0);
        mDarkModeEnd = mSharedPref.getInt("dark-mode-end", 0);
        mButtonDarkModeStart.setText( timeFormat((int) Math.floor((double)mDarkModeStart / 60), mDarkModeStart % 60) );
        mButtonDarkModeEnd.setText( timeFormat((int) Math.floor((double)mDarkModeEnd / 60), mDarkModeEnd % 60) );
        mCheckBoxAnalogClockShow.setChecked( mSharedPref.getBoolean("show-analog", true) );
        mCheckBoxAnalogClockShowSeconds.setChecked( mSharedPref.getBoolean("show-seconds-analog", true) );
        mCheckBoxAnalogClockSmoothHands.setChecked( mSharedPref.getBoolean("smooth-hands", true) );
        mCustomColorAnalogFace = mSharedPref.getBoolean("own-color-analog-clock-face", false);
        mCustomColorAnalogHours = mSharedPref.getBoolean("own-color-analog-hours", false);
        mCustomColorAnalogMinutes = mSharedPref.getBoolean("own-color-analog-minutes", false);
        mCustomColorAnalogSeconds = mSharedPref.getBoolean("own-color-analog-seconds", false);
        mCheckBoxDigitalClockShow.setChecked( mSharedPref.getBoolean("show-digital", true) );
        mCheckBoxDateShow.setChecked( mSharedPref.getBoolean("show-date", true) );
        mEditTextDateFormat.setText( mSharedPref.getString("date-format", FsClockView.getDefaultDateFormat(this)) );
        mRadioButtonGregorianCalendar.setChecked( !mSharedPref.getBoolean("use-hijri", false) );
        mRadioButtonHijriCalendar.setChecked( mSharedPref.getBoolean("use-hijri", false) );
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            findViewById(R.id.linearLayoutCalendarSystem).setVisibility(View.GONE);
        }
        mCheckBoxDigitalClockShowSeconds.setChecked( mSharedPref.getBoolean("show-seconds-digital", true) );
        mCheckBoxDigitalClock24Format.setChecked( mSharedPref.getBoolean("24hrs", false) );
        mCheckBoxArabicDigits.setChecked( mSharedPref.getBoolean("arabic-digits", false) );
        mCheckBoxShowHijri.setChecked( mSharedPref.getBoolean("show-hijri-date", true) );
        mCheckBoxAutoContrast.setChecked( mSharedPref.getBoolean("auto-contrast", true) );
        mColorAnalogFace = mSharedPref.getInt("color-analog-face", 0xffffffff);
        mColorAnalogHours = mSharedPref.getInt("color-analog-hours", 0xffffffff);
        mColorAnalogMinutes = mSharedPref.getInt("color-analog-minutes", 0xffffffff);
        mColorAnalogSeconds = mSharedPref.getInt("color-analog-seconds", 0xffffffff);
        mColorDigitalClock = mSharedPref.getInt("color-digital-clock", 0xffffffff);
        mColorDigitalDate = mSharedPref.getInt("color-digital-date", 0xffffffff);
        mColorEvents = mSharedPref.getInt("color-events", mColorDigitalDate);
        mColorBack = mSharedPref.getInt("color-back", 0xff000000);
        mBackStretch = mSharedPref.getBoolean("back-stretch", false);
        mCheckBoxShowAlarms.setChecked(mSharedPref.getBoolean("show-alarms", false));
        mCheckBoxShowWeather.setChecked(mSharedPref.getBoolean("show-weather", true));
        mEditTextWeatherCity.setText(mSharedPref.getString("weather-city", "Doha"));

        // wallpaper + clock layout settings
        mCheckBoxWallpaperEnabled.setChecked(mSharedPref.getBoolean(WallpaperRepo.PREF_ENABLED, true));
        mCheckBoxWallpaperAutoSwitch.setChecked(mSharedPref.getBoolean("wallpaper-auto-switch", false));
        mEditTextWallpaperUrl.setText(mSharedPref.getString(WallpaperRepo.PREF_URL, ""));
        mCheckBoxShowClockOverlay.setChecked(mSharedPref.getBoolean("clock-overlay", true));
        ArrayAdapter<CharSequence> posAdapter = ArrayAdapter.createFromResource(this, R.array.clock_position_labels, android.R.layout.simple_spinner_item);
        posAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinnerClockPosition.setAdapter(posAdapter);
        mSpinnerClockPosition.setSelection(indexOf(CLOCK_POSITION_VALUES, mSharedPref.getString("clock-position", "bottom_left")), false);
        ArrayAdapter<CharSequence> sizeAdapter = ArrayAdapter.createFromResource(this, R.array.clock_size_labels, android.R.layout.simple_spinner_item);
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinnerClockSize.setAdapter(sizeAdapter);
        mSpinnerClockSize.setSelection(indexOf(CLOCK_SIZE_VALUES, mSharedPref.getString("clock-size", "small")), false);
        if(mWallpaperRepo.hasItems()) {
            mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_count, mWallpaperRepo.size()));
        }
        mCheckBoxWallpaperLocal.setChecked(mSharedPref.getBoolean(WallpaperRepo.PREF_LOCAL_ENABLED, true));
        mTextViewLocalFolder.setText(getString(R.string.wallpaper_local_folder, mWallpaperRepo.getLocalFolder().getAbsolutePath()));

        SharedPreferences.Editor edit = mSharedPref.edit();
        edit.putBoolean("opened-settings", true);
        edit.apply();

        // init radio button behavior
        mRadioButtonGregorianCalendar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mRadioButtonHijriCalendar.setChecked(false);
            }
        });
        mRadioButtonHijriCalendar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mRadioButtonGregorianCalendar.setChecked(false);
            }
        });

        // load events
        Event[] eventsArray = mGson.fromJson(mSharedPref.getString("events",""), Event[].class);
        if(eventsArray != null) {
            mEvents = new ArrayList<>(Arrays.asList(eventsArray));
        }
        displayEvents();

        // init UI elements
        initColorPreview();
        initImageSpinner();
        initFontSpinner();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_settings, menu);

        // display play/pause icon on TV devices for saving settings
        UiModeManager uiModeManager = (UiModeManager) getSystemService(UI_MODE_SERVICE);
        if(uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION) {
            menu.findItem(R.id.action_settings_done).setIcon(R.drawable.ic_play_pause_white);
        }

        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch(item.getItemId()) {
            case android.R.id.home:
                finish();
                return true;
            case R.id.action_settings_done:
                saveAndFinish();
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // did I ever told you about the Amazon Echo Show 15, a hazardous waste of technology without proper debugging options?
        if(event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY
        || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
            saveAndFinish();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        switch(requestCode) {
            case(PICK_WALLPAPERS_REQUEST):
                if(resultCode == RESULT_OK && data != null) {
                    importPickedWallpapers(data);
                }
                break;
            case(PICK_CLOCK_FACE_REQUEST):
                if(resultCode == RESULT_OK) {
                    mStorage.processImage(StorageControl.FILENAME_CLOCK_FACE, data);
                } else {
                    mSpinnerDesignAnalogFace.setSelection(mStorage.existsImage(StorageControl.FILENAME_CLOCK_FACE) ? 1 : 0, false);
                }
                break;
            case(PICK_HOURS_HAND_REQUEST):
                if(resultCode == RESULT_OK) {
                    mStorage.processImage(StorageControl.FILENAME_HOURS_HAND, data);
                } else {
                    mSpinnerDesignAnalogHours.setSelection(mStorage.existsImage(StorageControl.FILENAME_HOURS_HAND) ? 1 : 0, false);
                }
                break;
            case(PICK_MINUTES_HAND_REQUEST):
                if(resultCode == RESULT_OK) {
                    mStorage.processImage(StorageControl.FILENAME_MINUTES_HAND, data);
                } else {
                    mSpinnerDesignAnalogMinutes.setSelection(mStorage.existsImage(StorageControl.FILENAME_MINUTES_HAND) ? 1 : 0, false);
                }
                break;
            case(PICK_SECONDS_HAND_REQUEST):
                if(resultCode == RESULT_OK) {
                    mStorage.processImage(StorageControl.FILENAME_SECONDS_HAND, data);
                } else {
                    mSpinnerDesignAnalogSeconds.setSelection(mStorage.existsImage(StorageControl.FILENAME_SECONDS_HAND) ? 1 : 0, false);
                }
                break;
            case(PICK_BACKGROUND_REQUEST):
                if(resultCode == RESULT_OK) {
                    mStorage.processImage(StorageControl.FILENAME_BACKGROUND_IMAGE, data);
                    mBackStretch = mSpinnerDesignBack.getSelectedItemPosition() == 1;
                } else {
                    mSpinnerDesignBack.setSelection(mStorage.existsImage(StorageControl.FILENAME_BACKGROUND_IMAGE) ? 1 : 0, false);
                }
                break;
        }
    }

    private void saveAndFinish() {
        save();
        setResult(RESULT_OK);
        finish();
    }

    protected void enableDisableAllSettings(boolean state) {
        mCheckBoxKeepScreenOn.setEnabled(state);
        mCheckBoxShowBatteryInfo.setEnabled(state);
        mCheckBoxShowBatteryInfoWhenCharging.setEnabled(state);
        mCheckBoxBurnInPrevention.setEnabled(state);
        mCheckBoxForceLandscape.setEnabled(state);
        mCheckBoxDarkMode.setEnabled(state);
        mButtonDarkModeStart.setEnabled(state);
        mButtonDarkModeEnd.setEnabled(state);
        mCheckBoxAnalogClockShow.setEnabled(state);
        mCheckBoxAnalogClockShowSeconds.setEnabled(state);
        mCheckBoxAnalogClockSmoothHands.setEnabled(state);
        mSpinnerDesignAnalogFace.setEnabled(state);
        mSpinnerDesignAnalogHours.setEnabled(state);
        mSpinnerDesignAnalogMinutes.setEnabled(state);
        mSpinnerDesignAnalogSeconds.setEnabled(state);
        mCheckBoxDigitalClockShow.setEnabled(state);
        mCheckBoxDateShow.setEnabled(state);
        mEditTextDateFormat.setEnabled(state);
        mRadioButtonGregorianCalendar.setEnabled(state);
        mRadioButtonHijriCalendar.setEnabled(state);
        mCheckBoxDigitalClockShowSeconds.setEnabled(state);
        mCheckBoxDigitalClock24Format.setEnabled(state);
        mCheckBoxArabicDigits.setEnabled(state);
        mCheckBoxShowHijri.setEnabled(state);
        mCheckBoxAutoContrast.setEnabled(state);
        mColorChangerAnalogFace.setEnabled(state);
        mColorChangerAnalogHours.setEnabled(state);
        mColorChangerAnalogMinutes.setEnabled(state);
        mColorChangerAnalogSeconds.setEnabled(state);
        mColorChangerDigitalClock.setEnabled(state);
        mSpinnerDigitalClockFont.setEnabled(state);
        mColorChangerDigitalDate.setEnabled(state);
        mSpinnerDigitalDateFont.setEnabled(state);
        mColorChangerEvents.setEnabled(state);
        mSpinnerEventsFont.setEnabled(state);
        mColorChangerBack.setEnabled(state);
        mSpinnerDesignBack.setEnabled(state);
        mCheckBoxShowAlarms.setEnabled(state);
        mCheckBoxShowWeather.setEnabled(state);
        mButtonNewEvent.setEnabled(state);
        mCheckBoxWallpaperEnabled.setEnabled(state);
        mCheckBoxWallpaperAutoSwitch.setEnabled(state);
        mEditTextWallpaperUrl.setEnabled(state);
        mButtonSyncWallpapers.setEnabled(state);
        mButtonRefreshWallpapers.setEnabled(state);
        mCheckBoxShowClockOverlay.setEnabled(state);
        mSpinnerClockPosition.setEnabled(state);
        mSpinnerClockSize.setEnabled(state);
        mCheckBoxWallpaperLocal.setEnabled(state);
        mButtonAddLocalWallpapers.setEnabled(state);
    }

    private static int indexOf(String[] arr, String value) {
        for(int i = 0; i < arr.length; i++) if(arr[i].equals(value)) return i;
        return 0;
    }

    private static int clampSelection(Spinner spinner, int max) {
        int pos = spinner.getSelectedItemPosition();
        if(pos < 0 || pos >= max) return 0;
        return pos;
    }

    /** Save the entered URL, then fetch the manifest and report how many wallpapers were found. */
    public void onClickSyncWallpapers(View v) {
        final String url = mEditTextWallpaperUrl.getText().toString().trim();
        SharedPreferences.Editor e = mSharedPref.edit();
        e.putString(WallpaperRepo.PREF_URL, url);
        e.apply();
        if(url.isEmpty()) {
            mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_need_url));
            return;
        }
        mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_syncing));
        mButtonSyncWallpapers.setEnabled(false);
        mWallpaperRepo.sync(new WallpaperRepo.SyncCallback() {
            @Override
            public void done(final boolean success, final int count, final String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mButtonSyncWallpapers.setEnabled(true);
                        if(success) {
                            mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_count, count));
                        } else {
                            mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_failed, error == null ? "?" : error));
                        }
                        if (mTextViewSettingsDeviceId != null && mWallpaperRepo != null) {
                            String status = mWallpaperRepo.isActive() ? "نشط (Activated)" : "غير نشط (Pending Activation)";
                            mTextViewSettingsDeviceId.setText("كود الجهاز (Device ID): " + mWallpaperRepo.getDeviceId() + "\nالحالة (Status): " + status);
                        }
                    }
                });
            }
        });
    }

    /**
     * Re-fetch the wallpaper playlist straight from Supabase (public/global + this device's
     * own wallpapers), ignoring the manual manifest URL field, and report whether any new
     * wallpapers were added since last time. This lets the shop owner pull newly-uploaded
     * wallpapers on demand without waiting for the automatic sync.
     */
    public void onClickRefreshWallpapers(View v) {
        if(mWallpaperRepo == null) return;
        final int before = mWallpaperRepo.size();
        mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_checking));
        mButtonRefreshWallpapers.setEnabled(false);
        mWallpaperRepo.sync(new WallpaperRepo.SyncCallback() {
            @Override
            public void done(final boolean success, final int count, final String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        mButtonRefreshWallpapers.setEnabled(true);
                        if(success) {
                            int after = mWallpaperRepo.size();
                            int added = after - before;
                            if(added > 0) {
                                mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_new_added, added, after));
                            } else {
                                mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_up_to_date, after));
                            }
                        } else {
                            mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_failed, error == null ? "?" : error));
                        }
                        updateDeviceInfoViews();
                    }
                });
            }
        });
    }

    /** Fill the Device ID and MAC address text views shown in the wallpapers section. */
    @SuppressLint("SetTextI18n")
    private void updateDeviceInfoViews() {
        if(mTextViewSettingsDeviceId != null && mWallpaperRepo != null) {
            String status = mWallpaperRepo.isActive() ? "نشط (Activated)" : "غير نشط (Pending Activation)";
            mTextViewSettingsDeviceId.setText("كود الجهاز (Device ID): " + mWallpaperRepo.getDeviceId() + "\nالحالة (Status): " + status);
        }
        if(mTextViewSettingsMacAddress != null && mWallpaperRepo != null) {
            // Show the actual identifier used to match wallpapers (Device ID), not the
            // MAC address, which is unavailable on Android 6+ for privacy reasons.
            mTextViewSettingsMacAddress.setText("كود الربط (Linking Code): " + mWallpaperRepo.getDeviceId());
        }
    }

    /** Show a dialog with a QR code that encodes the device linking code (Device ID). */
    public void onClickShowMacQr(View v) {
        if(mWallpaperRepo == null) return;
        String mac = mWallpaperRepo.getDeviceId();
        if(mac == null || mac.isEmpty() || mac.equals("UNKNOWN")) {
            Toast.makeText(this, getString(R.string.mac_unavailable), Toast.LENGTH_LONG).show();
            return;
        }

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);
        int qrPx = (int) (240 * d);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(pad, pad, pad, pad);
        ll.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.mac_qr_hint));
        hint.setGravity(android.view.Gravity.CENTER);

        ImageView qr = new ImageView(this);
        Bitmap bmp = QrCode.generate(mac, 600);
        qr.setImageBitmap(bmp);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(qrPx, qrPx);
        qlp.topMargin = pad;
        qlp.bottomMargin = pad;
        qr.setLayoutParams(qlp);

        TextView macText = new TextView(this);
        macText.setText(mac);
        macText.setGravity(android.view.Gravity.CENTER);
        macText.setTextIsSelectable(true);
        macText.setTextSize(18);

        ll.addView(hint);
        ll.addView(qr);
        ll.addView(macText);

        new AlertDialog.Builder(this)
                .setTitle(R.string.mac_qr_title)
                .setView(ll)
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    private void initFontSpinner() {
        ArrayAdapter dataAdapterFonts = new ArrayAdapter(this, android.R.layout.simple_spinner_item, FontOptions.FONT_OPTIONS);
        dataAdapterFonts.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        mSpinnerEventsFont.setAdapter(dataAdapterFonts);
        mSpinnerEventsFont.setSelection(
                FontOptions.FONT_OPTIONS.indexOf( FontOptions.getById(mSharedPref.getInt("font-events", FontOptions.CAIRO_REGULAR)) ),
                false
        );
        mSpinnerDigitalClockFont.setAdapter(dataAdapterFonts);
        mSpinnerDigitalClockFont.setSelection(
                FontOptions.FONT_OPTIONS.indexOf( FontOptions.getById(mSharedPref.getInt("font-digital-clock", FontOptions.DSEG7_CLASSIC)) ),
                false
        );
        mSpinnerDigitalDateFont.setAdapter(dataAdapterFonts);
        mSpinnerDigitalDateFont.setSelection(
                FontOptions.FONT_OPTIONS.indexOf( FontOptions.getById(mSharedPref.getInt("font-digital-date", FontOptions.CAIRO_REGULAR)) ),
                false
        );
    }
    private void initImageSpinner() {
        List<GraphicItem> listFace = Arrays.asList(GraphicSelectionAdapter.CLOCK_FACES);
        mSpinnerDesignAnalogFace.setAdapter(new GraphicSelectionAdapter(this, R.layout.item_graphic, listFace));
        mSpinnerDesignAnalogFace.setSelection(
                listFace.indexOf(
                        GraphicSelectionAdapter.getById(
                            mStorage.existsImage(StorageControl.FILENAME_CLOCK_FACE) ? -1 : mSharedPref.getInt("clock-analog-face", 0),
                            GraphicSelectionAdapter.CLOCK_FACES
                        )
                ),
                false
        );

        List<GraphicItem> listHours = Arrays.asList(GraphicSelectionAdapter.HOUR_HANDS);
        mSpinnerDesignAnalogHours.setAdapter(new GraphicSelectionAdapter(this, R.layout.item_graphic, listHours));
        mSpinnerDesignAnalogHours.setSelection(
                listHours.indexOf(
                        GraphicSelectionAdapter.getById(
                                mStorage.existsImage(StorageControl.FILENAME_HOURS_HAND) ? -1 : mSharedPref.getInt("clock-analog-hours", 0),
                                GraphicSelectionAdapter.HOUR_HANDS
                        )
                ),
                false
        );

        List<GraphicItem> listMinutes = Arrays.asList(GraphicSelectionAdapter.MINUTE_HANDS);
        mSpinnerDesignAnalogMinutes.setAdapter(new GraphicSelectionAdapter(this, R.layout.item_graphic, listMinutes));
        mSpinnerDesignAnalogMinutes.setSelection(
                listMinutes.indexOf(
                        GraphicSelectionAdapter.getById(
                                mStorage.existsImage(StorageControl.FILENAME_MINUTES_HAND) ? -1 : mSharedPref.getInt("clock-analog-minutes", 0),
                                GraphicSelectionAdapter.MINUTE_HANDS
                        )
                ),
                false
        );

        List<GraphicItem> listSeconds = Arrays.asList(GraphicSelectionAdapter.SECOND_HANDS);
        mSpinnerDesignAnalogSeconds.setAdapter(new GraphicSelectionAdapter(this, R.layout.item_graphic, listSeconds));
        mSpinnerDesignAnalogSeconds.setSelection(
                listSeconds.indexOf(
                        GraphicSelectionAdapter.getById(
                                mStorage.existsImage(StorageControl.FILENAME_SECONDS_HAND) ? -1 : mSharedPref.getInt("clock-analog-seconds", 0),
                                GraphicSelectionAdapter.SECOND_HANDS
                        )
                ),
                false
        );

        String[] optionsBack = {
                getString(R.string.no_image),
                getString(R.string.custom_image_stretch),
                getString(R.string.custom_image_zoom)
        };
        ArrayAdapter dataAdapterBack = new ArrayAdapter(this, android.R.layout.simple_spinner_item, optionsBack);
        dataAdapterBack.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mSpinnerDesignBack.setAdapter(dataAdapterBack);
        mSpinnerDesignBack.setSelection(mStorage.existsImage(StorageControl.FILENAME_BACKGROUND_IMAGE) ? (mBackStretch ? 1 : 2) : 0, false);

        AdapterView.OnItemSelectedListener listener = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                GraphicItem gi = (GraphicItem) adapterView.getSelectedItem();

                if(adapterView.getId() == mSpinnerDesignAnalogFace.getId()) {
                    if(gi.mGraphicResourceId == null)
                        chooseImage(PICK_CLOCK_FACE_REQUEST);
                    else
                        mStorage.removeImage(StorageControl.FILENAME_CLOCK_FACE);

                } else if(adapterView.getId() == mSpinnerDesignAnalogHours.getId()) {
                    if(gi.mGraphicResourceId == null)
                        chooseImage(PICK_HOURS_HAND_REQUEST);
                    else
                        mStorage.removeImage(StorageControl.FILENAME_HOURS_HAND);

                } else if(adapterView.getId() == mSpinnerDesignAnalogMinutes.getId()) {
                    if(gi.mGraphicResourceId == null)
                        chooseImage(PICK_MINUTES_HAND_REQUEST);
                    else
                        mStorage.removeImage(StorageControl.FILENAME_MINUTES_HAND);

                } else if(adapterView.getId() == mSpinnerDesignAnalogSeconds.getId()) {
                    if(gi.mGraphicResourceId == null)
                        chooseImage(PICK_SECONDS_HAND_REQUEST);
                    else
                        mStorage.removeImage(StorageControl.FILENAME_SECONDS_HAND);
                }
            }
            @Override
            public void onNothingSelected(AdapterView<?> adapterView) { }
        };
        mSpinnerDesignAnalogFace.setOnItemSelectedListener(listener);
        mSpinnerDesignAnalogHours.setOnItemSelectedListener(listener);
        mSpinnerDesignAnalogMinutes.setOnItemSelectedListener(listener);
        mSpinnerDesignAnalogSeconds.setOnItemSelectedListener(listener);

        AdapterView.OnItemSelectedListener listener2 = new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
                if(i != 0)
                    chooseImage(PICK_BACKGROUND_REQUEST);
                else
                    mStorage.removeImage(StorageControl.FILENAME_BACKGROUND_IMAGE);
            }
            @Override
            public void onNothingSelected(AdapterView<?> adapterView) { }
        };
        mSpinnerDesignBack.setOnItemSelectedListener(listener2);
    }
    private void initColorPreview() {
        // analog color
        updateColorPreview(mColorAnalogFace, mColorPreviewAnalogFace, null);
        mColorChangerAnalogFace.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showColorDialog(mCustomColorAnalogFace, mColorAnalogFace, true, new ColorDialogCallback() {
                    @Override
                    public void ok(boolean customColor, int red, int green, int blue, boolean applyForAll) {
                        if(applyForAll) {
                            applyColorForAllAnalog(customColor, Color.argb(0xff, red, green, blue));
                        } else {
                            mColorAnalogFace = Color.argb(0xff, red, green, blue);
                            mCustomColorAnalogFace = customColor;
                            updateColorPreview(mColorAnalogFace, mColorPreviewAnalogFace, null);
                        }
                    }
                });
            }
        });
        updateColorPreview(mColorAnalogHours, mColorPreviewAnalogHours, null);
        mColorChangerAnalogHours.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showColorDialog(mCustomColorAnalogHours, mColorAnalogHours, true, new ColorDialogCallback() {
                    @Override
                    public void ok(boolean customColor, int red, int green, int blue, boolean applyForAll) {
                        if(applyForAll) {
                            applyColorForAllAnalog(customColor, Color.argb(0xff, red, green, blue));
                        } else {
                            mColorAnalogHours = Color.argb(0xff, red, green, blue);
                            mCustomColorAnalogHours = customColor;
                            updateColorPreview(mColorAnalogHours, mColorPreviewAnalogHours, null);
                        }
                    }
                });
            }
        });
        updateColorPreview(mColorAnalogMinutes, mColorPreviewAnalogMinutes, null);
        mColorChangerAnalogMinutes.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showColorDialog(mCustomColorAnalogMinutes, mColorAnalogMinutes, true, new ColorDialogCallback() {
                    @Override
                    public void ok(boolean customColor, int red, int green, int blue, boolean applyForAll) {
                        if(applyForAll) {
                            applyColorForAllAnalog(customColor, Color.argb(0xff, red, green, blue));
                        } else {
                            mColorAnalogMinutes = Color.argb(0xff, red, green, blue);
                            mCustomColorAnalogMinutes = customColor;
                            updateColorPreview(mColorAnalogMinutes, mColorPreviewAnalogMinutes, null);
                        }
                    }
                });
            }
        });
        updateColorPreview(mColorAnalogSeconds, mColorPreviewAnalogSeconds, null);
        mColorChangerAnalogSeconds.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showColorDialog(mCustomColorAnalogSeconds, mColorAnalogSeconds, true, new ColorDialogCallback() {
                    @Override
                    public void ok(boolean customColor, int red, int green, int blue, boolean applyForAll) {
                        if(applyForAll) {
                            applyColorForAllAnalog(customColor, Color.argb(0xff, red, green, blue));
                        } else {
                            mColorAnalogSeconds = Color.argb(0xff, red, green, blue);
                            mCustomColorAnalogSeconds = customColor;
                            updateColorPreview(mColorAnalogSeconds, mColorPreviewAnalogSeconds, null);
                        }
                    }
                });
            }
        });

        // digital color
        updateColorPreview(mColorDigitalClock, mColorPreviewDigitalClock, null);
        mColorChangerDigitalClock.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showColorDialog(null, mColorDigitalClock, true, new ColorDialogCallback() {
                    @Override
                    public void ok(boolean customColor, int red, int green, int blue, boolean applyForAll) {
                        mColorDigitalClock = Color.argb(0xff, red, green, blue);
                        updateColorPreview(mColorDigitalClock, mColorPreviewDigitalClock, null);
                        if(applyForAll) {
                            mColorDigitalDate = Color.argb(0xff, red, green, blue);
                            updateColorPreview(mColorDigitalDate, mColorPreviewDigitalDate, null);
                            mColorEvents = Color.argb(0xff, red, green, blue);
                            updateColorPreview(mColorEvents, mColorPreviewEvents, null);
                        }
                    }
                });
            }
        });
        updateColorPreview(mColorDigitalDate, mColorPreviewDigitalDate, null);
        mColorChangerDigitalDate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showColorDialog(null, mColorDigitalDate, true, new ColorDialogCallback() {
                    @Override
                    public void ok(boolean customColor, int red, int green, int blue, boolean applyForAll) {
                        mColorDigitalDate = Color.argb(0xff, red, green, blue);
                        updateColorPreview(mColorDigitalDate, mColorPreviewDigitalDate, null);
                        if(applyForAll) {
                            mColorDigitalClock = Color.argb(0xff, red, green, blue);
                            updateColorPreview(mColorDigitalClock, mColorPreviewDigitalClock, null);
                            mColorEvents = Color.argb(0xff, red, green, blue);
                            updateColorPreview(mColorEvents, mColorPreviewEvents, null);
                        }
                    }
                });
            }
        });

        // events color
        updateColorPreview(mColorEvents, mColorPreviewEvents, null);
        mColorChangerEvents.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showColorDialog(null, mColorEvents, true, new ColorDialogCallback() {
                    @Override
                    public void ok(boolean customColor, int red, int green, int blue, boolean applyForAll) {
                        mColorEvents = Color.argb(0xff, red, green, blue);
                        updateColorPreview(mColorEvents, mColorPreviewEvents, null);
                        if(applyForAll) {
                            mColorDigitalDate = Color.argb(0xff, red, green, blue);
                            updateColorPreview(mColorDigitalDate, mColorPreviewDigitalDate, null);
                            mColorDigitalClock = Color.argb(0xff, red, green, blue);
                            updateColorPreview(mColorDigitalClock, mColorPreviewDigitalClock, null);
                        }
                    }
                });
            }
        });

        // background color
        updateColorPreview(mColorBack, mColorPreviewBack, null);
        mColorChangerBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                showColorDialog(null, mColorBack, false, new ColorDialogCallback() {
                    @Override
                    public void ok(boolean customColor, int red, int green, int blue, boolean applyForAll) {
                        mColorBack = Color.argb(0xff, red, green, blue);
                        updateColorPreview(mColorBack, mColorPreviewBack, null);
                    }
                });
            }
        });
    }
    private void applyColorForAllAnalog(boolean customColor, int color) {
        mColorAnalogFace = color;
        mCustomColorAnalogFace = customColor;
        updateColorPreview(mColorAnalogFace, mColorPreviewAnalogFace, null);
        mColorAnalogHours = color;
        mCustomColorAnalogHours = customColor;
        updateColorPreview(mColorAnalogHours, mColorPreviewAnalogHours, null);
        mColorAnalogMinutes = color;
        mCustomColorAnalogMinutes = customColor;
        updateColorPreview(mColorAnalogMinutes, mColorPreviewAnalogMinutes, null);
        mColorAnalogSeconds = color;
        mCustomColorAnalogSeconds = customColor;
        updateColorPreview(mColorAnalogSeconds, mColorPreviewAnalogSeconds, null);
    }
    private void updateColorPreview(int color, View previewView, EditText hexTextBox) {
        previewView.setBackgroundColor(Color.argb(0xff, Color.red(color), Color.green(color), Color.blue(color)));
        if(hexTextBox != null) hexTextBox.setText(String.format("#%06X", (0xFFFFFF & color)));
    }
    interface ColorDialogCallback {
        void ok(boolean customColor, int red, int green, int blue, boolean applyForAll);
    }
    private void showColorDialog(Boolean customColor, int initialColor, boolean showApplyForAll, final ColorDialogCallback colorDialogFinished) {
        final Dialog ad = new Dialog(this);
        ad.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ad.setContentView(R.layout.dialog_color);
        final CheckBox checkBoxCustomColor = ad.findViewById(R.id.checkBoxCustomColor);
        final EditText editTextColorHex = ad.findViewById(R.id.editTextColorHex);
        final SeekBar seekBarRed = ad.findViewById(R.id.seekBarRed);
        final SeekBar seekBarGreen = ad.findViewById(R.id.seekBarGreen);
        final SeekBar seekBarBlue = ad.findViewById(R.id.seekBarBlue);
        final View colorPreview = ad.findViewById(R.id.viewColorPreview);
        final Button buttonOkForAll = ad.findViewById(R.id.buttonOkForAll);
        final Button buttonOK = ad.findViewById(R.id.buttonOK);
        if(!showApplyForAll) {
            buttonOkForAll.setVisibility(View.GONE);
        }
        if(customColor == null) {
            checkBoxCustomColor.setVisibility(View.GONE);
        } else {
            checkBoxCustomColor.setChecked(customColor);
            if(!customColor) {
                seekBarRed.setEnabled(false);
                seekBarGreen.setEnabled(false);
                seekBarBlue.setEnabled(false);
                editTextColorHex.setEnabled(false);
            }
        }
        checkBoxCustomColor.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean b) {
                seekBarRed.setEnabled(b);
                seekBarGreen.setEnabled(b);
                seekBarBlue.setEnabled(b);
                editTextColorHex.setEnabled(b);
            }
        });
        seekBarRed.setProgress(Color.red(initialColor));
        seekBarGreen.setProgress(Color.green(initialColor));
        seekBarBlue.setProgress(Color.blue(initialColor));
        seekBarRed.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateColorPreview(Color.argb(0xff, seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress()), colorPreview, editTextColorHex);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        seekBarGreen.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateColorPreview(Color.argb(0xff, seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress()), colorPreview, editTextColorHex);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        seekBarBlue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                updateColorPreview(Color.argb(0xff, seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress()), colorPreview, editTextColorHex);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        editTextColorHex.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) { }
            @Override
            public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {
                try {
                    int newColor = Color.parseColor(charSequence.toString());
                    seekBarRed.setProgress(Color.red(newColor));
                    seekBarGreen.setProgress(Color.green(newColor));
                    seekBarBlue.setProgress(Color.blue(newColor));
                    //updateColorPreview(Color.argb(0xff, seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress()), colorPreview, null);
                } catch(Exception ignored) { }
            }
            @Override
            public void afterTextChanged(Editable editable) { }
        });
        updateColorPreview(Color.argb(0xff, seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress()), colorPreview, editTextColorHex);
        ad.show();
        ad.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        buttonOK.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(colorDialogFinished != null) {
                    colorDialogFinished.ok(checkBoxCustomColor.isChecked(), seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress(), false);
                }
                ad.dismiss();
            }
        });
        buttonOkForAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if(colorDialogFinished != null) {
                    colorDialogFinished.ok(checkBoxCustomColor.isChecked(), seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress(), true);
                }
                ad.dismiss();
            }
        });
    }

    private void save() {
        SharedPreferences.Editor editor = mSharedPref.edit();

        editor.putBoolean("keep-screen-on", mCheckBoxKeepScreenOn.isChecked());
        editor.putBoolean("show-battery-info", mCheckBoxShowBatteryInfo.isChecked());
        editor.putBoolean("show-battery-info-when-charging", mCheckBoxShowBatteryInfoWhenCharging.isChecked());
        editor.putBoolean("burn-in-prevention", mCheckBoxBurnInPrevention.isChecked());
        editor.putBoolean("force-landscape", mCheckBoxForceLandscape.isChecked());
        editor.putBoolean("dark-mode", mCheckBoxDarkMode.isChecked());
        editor.putInt("dark-mode-start", mDarkModeStart);
        editor.putInt("dark-mode-end", mDarkModeEnd);
        editor.putBoolean("show-analog", mCheckBoxAnalogClockShow.isChecked());
        editor.putBoolean("own-color-analog-clock-face", mCustomColorAnalogFace);
        editor.putBoolean("own-color-analog-hours", mCustomColorAnalogHours);
        editor.putBoolean("own-color-analog-minutes", mCustomColorAnalogMinutes);
        editor.putBoolean("own-color-analog-seconds", mCustomColorAnalogSeconds);
        editor.putBoolean("show-digital", mCheckBoxDigitalClockShow.isChecked());
        editor.putBoolean("show-date", mCheckBoxDateShow.isChecked());
        editor.putString("date-format", mEditTextDateFormat.getText().toString());
        editor.putBoolean("use-hijri", mRadioButtonHijriCalendar.isChecked());
        editor.putBoolean("show-seconds-analog", mCheckBoxAnalogClockShowSeconds.isChecked());
        editor.putBoolean("smooth-hands", mCheckBoxAnalogClockSmoothHands.isChecked());
        editor.putBoolean("show-seconds-digital", mCheckBoxDigitalClockShowSeconds.isChecked());
        editor.putBoolean("24hrs", mCheckBoxDigitalClock24Format.isChecked());
        editor.putBoolean("arabic-digits", mCheckBoxArabicDigits.isChecked());
        editor.putBoolean("show-hijri-date", mCheckBoxShowHijri.isChecked());
        editor.putBoolean("auto-contrast", mCheckBoxAutoContrast.isChecked());
        editor.putString("events", mGson.toJson(mEvents.toArray()));
        editor.putInt("color-analog-face", mColorAnalogFace);
        editor.putInt("color-analog-hours", mColorAnalogHours);
        editor.putInt("color-analog-minutes", mColorAnalogMinutes);
        editor.putInt("color-analog-seconds", mColorAnalogSeconds);
        editor.putInt("color-digital-clock", mColorDigitalClock);
        editor.putInt("clock-analog-face", ((GraphicItem) mSpinnerDesignAnalogFace.getSelectedItem()).mId);
        editor.putInt("clock-analog-hours", ((GraphicItem) mSpinnerDesignAnalogHours.getSelectedItem()).mId);
        editor.putInt("clock-analog-minutes", ((GraphicItem) mSpinnerDesignAnalogMinutes.getSelectedItem()).mId);
        editor.putInt("clock-analog-seconds", ((GraphicItem) mSpinnerDesignAnalogSeconds.getSelectedItem()).mId);
        editor.putInt("font-digital-clock", ((FontOption) mSpinnerDigitalClockFont.getSelectedItem()).mId);
        editor.putInt("color-digital-date", mColorDigitalDate);
        editor.putInt("font-digital-date", ((FontOption) mSpinnerDigitalDateFont.getSelectedItem()).mId);
        editor.putInt("color-events", mColorEvents);
        editor.putInt("font-events", ((FontOption) mSpinnerEventsFont.getSelectedItem()).mId);
        editor.putInt("color-back", mColorBack);
        editor.putBoolean("back-stretch", mBackStretch);
        editor.putBoolean("show-alarms", mCheckBoxShowAlarms.isChecked());
        editor.putBoolean("show-weather", mCheckBoxShowWeather.isChecked());
        editor.putString("weather-city", mEditTextWeatherCity.getText().toString().trim());
        editor.putBoolean(WallpaperRepo.PREF_ENABLED, mCheckBoxWallpaperEnabled.isChecked());
        editor.putBoolean("wallpaper-auto-switch", mCheckBoxWallpaperAutoSwitch.isChecked());
        editor.putString(WallpaperRepo.PREF_URL, mEditTextWallpaperUrl.getText().toString().trim());
        editor.putBoolean("clock-overlay", mCheckBoxShowClockOverlay.isChecked());
        editor.putString("clock-position", CLOCK_POSITION_VALUES[clampSelection(mSpinnerClockPosition, CLOCK_POSITION_VALUES.length)]);
        editor.putString("clock-size", CLOCK_SIZE_VALUES[clampSelection(mSpinnerClockSize, CLOCK_SIZE_VALUES.length)]);
        editor.putBoolean(WallpaperRepo.PREF_LOCAL_ENABLED, mCheckBoxWallpaperLocal.isChecked());

        editor.apply();

        try {
            AppWidgetManager widgetManager = AppWidgetManager.getInstance(this);
            int[] digitalIds = widgetManager.getAppWidgetIds(new ComponentName(this, FsClockWidgetDigitalProvider.class));
            if(digitalIds.length > 0)
                FsClockWidgetDigitalProvider.updateAllWidgets(this, widgetManager, digitalIds);

            int[] analogIds = widgetManager.getAppWidgetIds(new ComponentName(this, FsClockWidgetAnalogProvider.class));
            if(analogIds.length > 0)
                FsClockWidgetAnalogProvider.updateAllWidgets(this, widgetManager, analogIds);
        } catch(Exception ignored) {}
    }

    /** Open the system picker to choose images/videos from the device and copy them into the local folder. */
    public void onClickAddLocalWallpapers(View v) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(intent, PICK_WALLPAPERS_REQUEST);
        } catch(ActivityNotFoundException e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_SHORT).show();
        }
    }

    private void importPickedWallpapers(Intent data) {
        ArrayList<Uri> uris = new ArrayList<>();
        if(data.getClipData() != null) {
            for(int i = 0; i < data.getClipData().getItemCount(); i++) {
                uris.add(data.getClipData().getItemAt(i).getUri());
            }
        } else if(data.getData() != null) {
            uris.add(data.getData());
        }
        int ok = 0;
        for(Uri uri : uris) {
            try {
                String name = queryDisplayName(uri);
                if(name == null || name.trim().isEmpty()) name = "wp_" + uri.hashCode() + ".jpg";
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if(in != null && mWallpaperRepo.importLocalFile(in, name)) ok++;
            } catch(Exception ignored) { }
        }
        mWallpaperRepo.load();
        mTextViewLocalFolder.setText(getString(R.string.wallpaper_local_folder, mWallpaperRepo.getLocalFolder().getAbsolutePath()));
        Toast.makeText(this, getString(R.string.wallpaper_added, ok), Toast.LENGTH_LONG).show();
    }

    private String queryDisplayName(Uri uri) {
        Cursor c = null;
        try {
            c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if(c != null && c.moveToFirst()) return c.getString(0);
        } catch(Exception ignored) {
        } finally {
            if(c != null) c.close();
        }
        return null;
    }

    /** Show a QR code that the customer scans to upload an image over the local Wi-Fi. */
    public void onClickPairPhone(View v) {
        stopUploadServer();
        final String url;
        try {
            mUploadServer = new UploadServer(this, mWallpaperRepo, new UploadServer.UploadListener() {
                @Override
                public void onUploaded(String savedPath) {
                    setResult(RESULT_OK); // so the clock reloads and shows the uploaded wallpaper
                    if(mPairStatus != null) mPairStatus.setText(getString(R.string.pair_uploaded));
                    Toast.makeText(BaseSettingsActivity.this, getString(R.string.pair_uploaded), Toast.LENGTH_LONG).show();
                }
            });
            mUploadServer.start();
            url = mUploadServer.getUrl();
        } catch(Exception e) {
            Toast.makeText(this, "Server error: " + e.getMessage(), Toast.LENGTH_LONG).show();
            return;
        }
        if(url == null) {
            stopUploadServer();
            infoDialog(getString(R.string.wallpaper_pair_phone), getString(R.string.pair_no_wifi));
            return;
        }

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (20 * d);
        int qrPx = (int) (240 * d);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        ll.setPadding(pad, pad, pad, pad);
        ll.setGravity(android.view.Gravity.CENTER_HORIZONTAL);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.pair_scan_hint));
        hint.setGravity(android.view.Gravity.CENTER);

        ImageView qr = new ImageView(this);
        Bitmap bmp = QrCode.generate(url, 600);
        qr.setImageBitmap(bmp);
        LinearLayout.LayoutParams qlp = new LinearLayout.LayoutParams(qrPx, qrPx);
        qlp.topMargin = pad;
        qlp.bottomMargin = pad;
        qr.setLayoutParams(qlp);

        TextView urlText = new TextView(this);
        urlText.setText(url);
        urlText.setGravity(android.view.Gravity.CENTER);
        urlText.setTextIsSelectable(true);

        mPairStatus = new TextView(this);
        mPairStatus.setGravity(android.view.Gravity.CENTER);
        mPairStatus.setPadding(0, pad, 0, 0);

        ll.addView(hint);
        ll.addView(qr);
        ll.addView(urlText);
        ll.addView(mPairStatus);

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_pair_phone)
                .setView(ll)
                .setPositiveButton(R.string.ok, null)
                .setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        stopUploadServer();
                    }
                })
                .create();
        dlg.show();
    }

    private void stopUploadServer() {
        if(mUploadServer != null) {
            try { mUploadServer.stop(); } catch(Exception ignored) {}
            mUploadServer = null;
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopUploadServer();
    }

    private void chooseImage(int requestId) {
        Intent intent = new Intent();
        intent.setType("image/*");
        intent.setAction(Intent.ACTION_GET_CONTENT);
        startActivityForResult(Intent.createChooser(intent, "Select Image"), requestId);
        if(requestId != PICK_BACKGROUND_REQUEST) {
            Toast.makeText(this, getString(R.string.own_images_note), Toast.LENGTH_LONG).show();
        }
    }

    private void displayEvents() {
        LinearLayout llEvents = findViewById(R.id.linearLayoutSettingsEvents);
        llEvents.removeAllViews();
        for(final Event e : mEvents) {
            Button b = new Button(this);
            b.setAllCaps(false);
            b.setText(e.toString());
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    onClickEditEvent(e);
                }
            });
            llEvents.addView(b);
        }
    }
    public void onClickNewEvent(View v) {
        final Dialog ad = new Dialog(this);
        ad.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ad.setContentView(R.layout.dialog_event);
        //ad.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        ((NumberPicker) ad.findViewById(R.id.numberPickerHour)).setMaxValue(23);
        ((NumberPicker) ad.findViewById(R.id.numberPickerMinute)).setMaxValue(59);
        ad.findViewById(R.id.buttonNewEventRemove).setVisibility(View.GONE);
        ad.show();
        ad.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ad.findViewById(R.id.buttonNewEventOK).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mEvents.add(new Event(
                        ((NumberPicker) ad.findViewById(R.id.numberPickerHour)).getValue(),
                        ((NumberPicker) ad.findViewById(R.id.numberPickerMinute)).getValue(),
                        ((EditText) ad.findViewById(R.id.editTextTitleNewEvent)).getText().toString(),
                        ((EditText) ad.findViewById(R.id.editTextSpeakNewEvent)).getText().toString(),
                        ((CheckBox) ad.findViewById(R.id.checkBoxAlarmNewEvent)).isChecked(),
                        ((CheckBox) ad.findViewById(R.id.checkBoxDisplayNewEvent)).isChecked(),
                        ((CheckBox) ad.findViewById(R.id.checkBoxShowUntilConfirmed)).isChecked() ? 0 : 15
                ));
                ad.dismiss();
                displayEvents();
            }
        });
    }
    public void onClickEditEvent(final Event e) {
        final Dialog ad = new Dialog(this);
        ad.requestWindowFeature(Window.FEATURE_NO_TITLE);
        ad.setContentView(R.layout.dialog_event);
        //ad.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        ((NumberPicker) ad.findViewById(R.id.numberPickerHour)).setMaxValue(23);
        ((NumberPicker) ad.findViewById(R.id.numberPickerMinute)).setMaxValue(59);
        ((NumberPicker) ad.findViewById(R.id.numberPickerHour)).setValue(e.triggerHour);
        ((NumberPicker) ad.findViewById(R.id.numberPickerMinute)).setValue(e.triggerMinute);
        ((EditText) ad.findViewById(R.id.editTextTitleNewEvent)).setText(e.title);
        ((EditText) ad.findViewById(R.id.editTextSpeakNewEvent)).setText(e.speakText);
        ((CheckBox) ad.findViewById(R.id.checkBoxAlarmNewEvent)).setChecked(e.playAlarm);
        ((CheckBox) ad.findViewById(R.id.checkBoxDisplayNewEvent)).setChecked(e.showOnScreen);
        ((CheckBox) ad.findViewById(R.id.checkBoxShowUntilConfirmed)).setChecked(e.hideAfter == 0);
        ad.show();
        ad.getWindow().setLayout(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        ad.findViewById(R.id.buttonNewEventOK).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                e.triggerHour = ((NumberPicker) ad.findViewById(R.id.numberPickerHour)).getValue();
                e.triggerMinute = ((NumberPicker) ad.findViewById(R.id.numberPickerMinute)).getValue();
                e.title = ((EditText) ad.findViewById(R.id.editTextTitleNewEvent)).getText().toString();
                e.speakText = ((EditText) ad.findViewById(R.id.editTextSpeakNewEvent)).getText().toString();
                e.playAlarm = ((CheckBox) ad.findViewById(R.id.checkBoxAlarmNewEvent)).isChecked();
                e.showOnScreen = ((CheckBox) ad.findViewById(R.id.checkBoxDisplayNewEvent)).isChecked();
                e.hideAfter = ((CheckBox) ad.findViewById(R.id.checkBoxShowUntilConfirmed)).isChecked() ? 0 : 15;
                ad.dismiss();
                displayEvents();
            }
        });
        ad.findViewById(R.id.buttonNewEventRemove).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mEvents.remove(e);
                ad.dismiss();
                displayEvents();
            }
        });
    }

    public void onClickDreamSettings(View v) {
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            try {
                startActivity(new Intent(Settings.ACTION_DREAM_SETTINGS));
            } catch(ActivityNotFoundException e) {
                infoDialog(null, getString(R.string.screensaver_not_supported));
            }
        }
    }

    public void onClickDarkModeStart(View v) {
        TimePickerDialog mTimePicker = new TimePickerDialog(this,
                new TimePickerDialog.OnTimeSetListener() {
                    @SuppressLint("SimpleDateFormat")
                    @Override
                    public void onTimeSet(TimePicker timePicker, int selectedHour, int selectedMinute) {
                        mDarkModeStart = selectedHour * 60 + selectedMinute;
                        ((Button) findViewById(R.id.buttonDarkModeStart)).setText( timeFormat(selectedHour, selectedMinute) );
                    }
                },
                (int) Math.floor((double)mDarkModeStart / 60),
                mDarkModeStart % 60,
                android.text.format.DateFormat.is24HourFormat(this)
        );
        //mTimePicker.setTitle("Select Time");
        mTimePicker.show();
    }
    public void onClickDarkModeEnd(View v) {
        TimePickerDialog mTimePicker = new TimePickerDialog(this,
                new TimePickerDialog.OnTimeSetListener() {
                    @SuppressLint("SimpleDateFormat")
                    @Override
                    public void onTimeSet(TimePicker timePicker, int selectedHour, int selectedMinute) {
                        mDarkModeEnd = selectedHour * 60 + selectedMinute;
                        ((Button) findViewById(R.id.buttonDarkModeEnd)).setText( timeFormat(selectedHour, selectedMinute) );
                    }
                },
                (int) Math.floor((double)mDarkModeEnd / 60),
                mDarkModeEnd % 60,
                android.text.format.DateFormat.is24HourFormat(this)
        );
        //mTimePicker.setTitle("Select Time");
        mTimePicker.show();
    }
    private String timeFormat(int selectedHour, int selectedMinute) {
        Calendar time = Calendar.getInstance();
        time.set(Calendar.HOUR_OF_DAY, selectedHour);
        time.set(Calendar.MINUTE, selectedMinute);
        DateFormat sdf = SimpleDateFormat.getTimeInstance(DateFormat.SHORT);
        return sdf.format(time.getTime());
    }

    public void onClickDateFormatHelp(View v) {
        StringBuilder sb = new StringBuilder();
        for(String s : getString(R.string.date_format_placeholders_help).split("\n")) {
            sb.append(s.trim()).append("\n");
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(getString(R.string.date_format_placeholders_help_title));
        builder.setMessage(sb.toString());
        builder.setPositiveButton(getString(R.string.ok),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
        builder.setNeutralButton(getString(R.string.reset_default),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        mEditTextDateFormat.setText(FsClockView.getDefaultDateFormat(getBaseContext()));
                    }
                });
        builder.setCancelable(true);
        AlertDialog dialog = builder.create();
        dialog.show();
        TextView messageView = dialog.findViewById(android.R.id.message);
        messageView.setTypeface(Typeface.MONOSPACE);
    }
    public void onClickCustomAnalogImagesHelp(View v) {
        infoDialog("", getString(R.string.own_images_note));
    }

    public final static String APPID_VSCREENSAVER  = "systems.sieber.vscreensaver";
    public final static String APPID_CUSTOMERDB    = "de.georgsieber.customerdb";
    public final static String APPID_REMOTEPOINTER = "systems.sieber.remotespotlight";
    public final static String APPID_BALLBREAK     = "de.georgsieber.ballbreak";
    public final static String URL_DESKLETS        = "https://georg-sieber.de/desklets";

    public void onClickVideoScreensaver(View v) {
        openPlayStore(APPID_VSCREENSAVER);
    }
    public void onClickGithub(View v) {
        openBrowser(getString(R.string.project_website));
    }

    public void onClickCheckUpdate(View v) {
        // manual check: also notify the user when already up to date / on error
        checkForUpdate(false);
    }

    /**
     * Ask Supabase whether a newer app version exists.
     * @param silent when true, only show a dialog if an update is available
     *               (used for the automatic check on settings open).
     */
    void checkForUpdate(final boolean silent) {
        new UpdateManager(this).checkForUpdate(new UpdateManager.UpdateCheckListener() {
            @Override
            public void onUpdateAvailable(int versionCode, final String versionName, final String apkUrl, String changelog) {
                if(isFinishing() || isDestroyed()) return;
                String message = getString(R.string.update_message, versionName);
                if(changelog != null && !changelog.trim().isEmpty()) {
                    message += "\n\n" + changelog.trim();
                }
                AlertDialog.Builder dlg = new AlertDialog.Builder(BaseSettingsActivity.this);
                dlg.setTitle(R.string.update_title);
                dlg.setMessage(message);
                dlg.setPositiveButton(R.string.update_now, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        new UpdateManager(BaseSettingsActivity.this).downloadAndInstall(apkUrl);
                    }
                });
                dlg.setNegativeButton(R.string.update_later, null);
                dlg.setCancelable(true);
                dlg.create().show();
            }

            @Override
            public void onNoUpdate() {
                if(silent || isFinishing() || isDestroyed()) return;
                Toast.makeText(BaseSettingsActivity.this, R.string.update_up_to_date, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String errorMessage) {
                if(silent || isFinishing() || isDestroyed()) return;
                Toast.makeText(BaseSettingsActivity.this, R.string.update_check_failed, Toast.LENGTH_SHORT).show();
            }
        });
    }

    public void onClickCustomerDatabaseApp(View v) {
        openPlayStore(APPID_CUSTOMERDB);
    }
    public void onClickRemotePointerApp(View v) {
        openPlayStore(APPID_REMOTEPOINTER);
    }
    public void onClickBallBreakApp(View v) {
        openPlayStore(APPID_BALLBREAK);
    }
    public void onClickClockProDesklet(View v) {
        openBrowser(URL_DESKLETS);
    }
    void openBrowser(String url) {
        try {
            Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(browserIntent);
        } catch(SecurityException ignored) {
            infoDialog(getString(R.string.no_web_browser_found), url);
        } catch(ActivityNotFoundException ignored) {
            infoDialog(getString(R.string.no_web_browser_found), url);
        }
    }
    private void openPlayStore(String appId) {
        try {
            this.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + appId)));
        } catch(android.content.ActivityNotFoundException anfe) {
            this.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=" + appId)));
        }
    }

    public void onClickAllowSystemCalendarAccess(View v) {
        int permissionCheckResult = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CALENDAR);
        if(permissionCheckResult == PackageManager.PERMISSION_GRANTED) {
            infoDialog(null, getString(R.string.access_already_granted));
        } else {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.READ_CALENDAR}, PERMISSION_REQUEST);
        }
    }
    public void onClickAllowNotificationAccess(View v) {
        try {
            Intent settingsIntent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            startActivity(settingsIntent);
        } catch (ActivityNotFoundException ignored) { }
    }

    void infoDialog(String title, String text) {
        final AlertDialog.Builder dlg = new AlertDialog.Builder(this);
        if(title != null) dlg.setTitle(title);
        if(text != null) dlg.setMessage(text);
        dlg.setPositiveButton(getString(R.string.ok),
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        dialog.dismiss();
                    }
                });
        dlg.setCancelable(true);
        dlg.create().show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if(requestCode == PERMISSION_REQUEST) {
            for(int i = 0, len = permissions.length; i < len; i++) {
                String permission = permissions[i];
                if(grantResults[i] == PackageManager.PERMISSION_DENIED) {
                    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        boolean showRationale = shouldShowRequestPermissionRationale(permission);
                        if(!showRationale) {
                            // user also CHECKED "never ask again"
                            infoDialog(null, getString(R.string.access_denied_by_user));
                        }
                    }
                }
            }
        }
    }

    public static boolean isHighContrastTextEnabled(Context context) {
        if(context != null) {
            AccessibilityManager am = (AccessibilityManager) context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            Method m = null;
            if(am != null) {
                try {
                    m = am.getClass().getMethod("isHighTextContrastEnabled", (Class<?>) null);
                } catch(NoSuchMethodException ignored) { }
            }
            Object result;
            if(m != null) {
                try {
                    result = m.invoke(am, (Object[]) null);
                    if(result instanceof Boolean) {
                        return (Boolean) result;
                    }
                } catch(Exception ignored) { }
            }
        }
        return false;
    }

    String checkCode(String feature, String code) throws Exception {
        try {
            URL urlGetRequest = new URL(getResources().getString(R.string.unlock_api));
            HttpURLConnection conn = (HttpURLConnection) urlGetRequest.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("X-Unlock-Feature", feature);
            conn.setRequestProperty("X-Unlock-Code", code);
            int statusCode = conn.getResponseCode();
            conn.disconnect();
            if(statusCode == 999) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                StringBuffer response = new StringBuffer();
                String inputLine;
                while((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                return response.toString();
            } else {
                throw new Exception(getString(R.string.invalid_code) + " ("+statusCode+")");
            }
        } catch(IOException e) {
            e.printStackTrace();
            throw new Exception(getString(R.string.check_internet_conn));
        }
    }

}
