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
import android.widget.GridView;
import android.widget.NumberPicker;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
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
import java.io.File;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class BaseSettingsActivity extends AppCompatActivity {

    static final String SHARED_PREF_DOMAIN = "CLOCK";

    static final int PERMISSION_REQUEST = 0;
    static final int PICK_CLOCK_FACE_REQUEST = 1;
    static final int PICK_HOURS_HAND_REQUEST = 2;
    static final int PICK_MINUTES_HAND_REQUEST = 3;
    static final int PICK_SECONDS_HAND_REQUEST = 4;
    static final int PICK_WALLPAPERS_REQUEST = 6;
    static final int FIT_EDITOR_REQUEST = 7;

    StorageControl mStorage = new StorageControl(this);
    Gson mGson = new Gson();
    SharedPreferences mSharedPref;

    LinearLayout mLinearLayoutPurchaseContainer;
    LinearLayout mLinearLayoutSettingsContainer;
    Button mButtonUnlockSettings;
    CompoundButton mCheckBoxFse;
    CompoundButton mCheckBoxAutoStartOnBoot;
    /** Live "grant overlay access" dialog, so two routes on one tap cannot stack two of them. */
    private AlertDialog mOverlayPermissionDialog;
    /** Whether this visit already asked, so onResume re-checks without nagging in a loop. */
    private boolean mOverlayPermissionAsked;
    CompoundButton mCheckBoxAnalogClockShow;
    CompoundButton mCheckBoxAnalogClockShowSeconds;
    CompoundButton mCheckBoxAnalogClockSmoothHands;
    Spinner mSpinnerDesignAnalogFace;
    Spinner mSpinnerDesignAnalogHours;
    Spinner mSpinnerDesignAnalogMinutes;
    Spinner mSpinnerDesignAnalogSeconds;
    CompoundButton mCheckBoxDigitalClockShow;
    CompoundButton mCheckBoxDateShow;
    EditText mEditTextDateFormat;
    RadioButton mRadioButtonGregorianCalendar;
    RadioButton mRadioButtonHijriCalendar;
    CompoundButton mCheckBoxDigitalClockShowSeconds;
    CompoundButton mCheckBoxDigitalClock24Format;
    CompoundButton mCheckBoxArabicDigits;
    CompoundButton mCheckBoxShowHijri;
    CompoundButton mCheckBoxAutoContrast;
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
    CompoundButton mCheckBoxShowWeather;
    EditText mEditTextWeatherCity;

    // wallpaper slideshow + clock layout
    CompoundButton mCheckBoxWallpaperEnabled;
    CompoundButton mCheckBoxWallpaperAutoSwitch;
    View mButtonManageWallpapers;
    View mButtonRefreshWallpapers;
    TextView mTextViewWallpaperStatus;
    CompoundButton mCheckBoxShowClockOverlay;
    Spinner mSpinnerClockPosition;
    Spinner mSpinnerClockSize;
    CompoundButton mCheckBoxWallpaperLocal;
    View mButtonAddLocalWallpapers;
    TextView mTextViewLocalFolder;
    WallpaperRepo mWallpaperRepo;
    UploadServer mUploadServer;
    /** Absolute paths of an uploaded batch awaiting the customer's "تم", or null. */
    private List<String> mPendingBatch;
    /** Which of that batch are currently unticked — survives a trip through the fit editor. */
    private Set<String> mBatchDiscarded;
    TextView mTextViewSettingsDeviceId;
    TextView mTextViewSettingsMacAddress;
    TextView mPairStatus;
    static final String[] CLOCK_POSITION_VALUES = {"center", "bottom_left", "bottom_right", "top_left", "top_right"};
    static final String[] CLOCK_SIZE_VALUES = {"small", "medium", "large", "full"};

    // There is no "apply" button any more: every change is written to the preferences the
    // moment it is made. The flag keeps the initial setChecked/setSelection calls in
    // onCreate from saving before all views are filled in.
    private boolean mAutoSaveReady = false;
    // views that already carry a listener of their own (autoSave() is called from inside it),
    // so the generic listener below must not overwrite it
    private final java.util.HashSet<View> mAutoSaveExcluded = new java.util.HashSet<>();

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        EdgeToEdge.enable(this);
        WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView())
                .setAppearanceLightStatusBars(false);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        // Inset the rail + content pane together. This used to hang off textViewBuildInfo and only
        // handled the bottom, which was enough while everything was one centred scrolling column.
        // The rail now runs to the screen edge, so on a device with side system bars (any car head
        // unit in landscape) it would sit underneath them without the horizontal insets.
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.linearLayoutSettingsRoot), (v, windowInsets) -> {
            Insets insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(insets.left, 0, insets.right, insets.bottom);
            // Return CONSUMED if you don't want the window insets to keep passing down to descendant views.
            return WindowInsetsCompat.CONSUMED;
        });

        // init toolbar
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if(getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            // The header is a custom child of the Toolbar; the built-in title would draw on top.
            getSupportActionBar().setDisplayShowTitleEnabled(false);
        }

        // display version & flavor
        ((TextView) findViewById(R.id.textViewHeaderTitle)).setText(R.string.settings_header_title);
        try {
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            setTitle(getTitle() + " " + pInfo.versionName);
            // Brand + version always reads left-to-right, even in Arabic — it is a product token.
            TextView subtitle = findViewById(R.id.textViewHeaderSubtitle);
            subtitle.setText("THABTHABA STORE · v" + pInfo.versionName);
            ViewCompat.setLayoutDirection(subtitle, ViewCompat.LAYOUT_DIRECTION_LTR);
            // The headline version on the Updates card: what you are running, said plainly,
            // before the button asks whether you want something else.
            ((TextView) findViewById(R.id.textViewCurrentVersion)).setText("v" + pInfo.versionName);
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
        mCheckBoxFse = findViewById(R.id.checkBoxFseSettings);
        mCheckBoxAutoStartOnBoot = findViewById(R.id.checkBoxAutoStartOnBoot);
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
        mCheckBoxShowWeather = findViewById(R.id.checkBoxShowWeather);
        mEditTextWeatherCity = findViewById(R.id.editTextWeatherCity);
        mCheckBoxWallpaperEnabled = findViewById(R.id.checkBoxWallpaperEnabled);
        mCheckBoxWallpaperAutoSwitch = findViewById(R.id.checkBoxWallpaperAutoSwitch);
        mButtonManageWallpapers = findViewById(R.id.buttonManageWallpapers);
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
        mCheckBoxFse.setChecked( mSharedPref.getBoolean(FullscreenActivity.PREF_FSE_SCREEN, false) );
        mCheckBoxAutoStartOnBoot.setChecked( mSharedPref.getBoolean(BootReceiver.PREF_AUTO_START, false) );
        mCheckBoxAutoStartOnBoot.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean checked) {
                // save immediately so the setting survives even if the user leaves without pressing "done"
                mSharedPref.edit().putBoolean(BootReceiver.PREF_AUTO_START, checked).apply();
                if(checked) requestOverlayPermissionIfNeeded();
                autoSave();
            }
        });
        mAutoSaveExcluded.add(mCheckBoxAutoStartOnBoot);
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
        mCheckBoxShowWeather.setChecked(mSharedPref.getBoolean("show-weather", true));
        mEditTextWeatherCity.setText(mSharedPref.getString("weather-city", "Doha"));
        // ask once for location so the weather can follow the car (optional; falls back to city/IP if denied)
        maybeRequestLocationForWeather();

        // wallpaper + clock layout settings
        mCheckBoxWallpaperEnabled.setChecked(mSharedPref.getBoolean(WallpaperRepo.PREF_ENABLED, true));
        mCheckBoxWallpaperAutoSwitch.setChecked(mSharedPref.getBoolean(WallpaperRepo.PREF_AUTO_SWITCH, false));
        buildIntervalButtons();
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
                autoSave();
            }
        });
        mRadioButtonHijriCalendar.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                mRadioButtonGregorianCalendar.setChecked(false);
                autoSave();
            }
        });
        // the radios drive each other through the click listeners above
        mAutoSaveExcluded.add(mRadioButtonGregorianCalendar);
        mAutoSaveExcluded.add(mRadioButtonHijriCalendar);

        // init UI elements
        initColorPreview();
        initImageSpinner();
        initFontSpinner();

        initNavRail();
        initModePicker();
        initTextScale();
        initFitDefaults();
        refreshHeaderChips();
        syncDependentRows();

        // from here on, every change to any setting saves itself immediately
        // (this claims the OnCheckedChangeListener of every switch in the tree, so anything
        //  that needs to react to a setting change has to hang off autoSave(), not its own
        //  listener — attachAutoSave would silently replace it)
        attachAutoSave(mLinearLayoutSettingsContainer);
        mAutoSaveReady = true;
    }

    /**
     * The header chips are status, not decoration — they have to track the real state or they
     * are worse than nothing. Called on load, whenever FSE flips, and after activation.
     */
    protected void refreshHeaderChips() {
        TextView mode = findViewById(R.id.chipMode);
        if(mode != null) {
            // Read the mode itself rather than the FSE mirror: that only knows FSE from
            // not-FSE, so it called Leopard "Normal" while the radio right below it said
            // Leopard — the chip was contradicting the setting it reports on.
            switch(OperatingMode.get(mSharedPref)) {
                case OperatingMode.LEOPARD: mode.setText(R.string.chip_mode_leopard); break;
                case OperatingMode.FSE:     mode.setText(R.string.chip_mode_fse); break;
                default:                    mode.setText(R.string.chip_mode_normal); break;
            }
            // The chip already names the mode; the caret is what says it can be changed here.
            mode.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ic_chip_caret_12dp, 0);
            mode.setOnClickListener(v -> showModeDialog());
        }

        TextView language = findViewById(R.id.chipLanguage);
        if(language != null) {
            // Label it with the language it switches TO, so it reads as an action not a status.
            boolean arabicNow = LocaleHelper.LANG_ARABIC.equals(LocaleHelper.resolved(this));
            language.setText(arabicNow ? R.string.language_english : R.string.language_arabic);
            language.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    R.drawable.ic_chip_language_16dp, 0, 0, 0);
            language.setOnClickListener(v -> toggleLanguage());
        }

        TextView activation = findViewById(R.id.chipActivation);
        if(activation != null) {
            boolean active = mWallpaperRepo != null && mWallpaperRepo.isActive();
            activation.setText(active ? R.string.chip_activated : R.string.chip_not_activated);
            activation.setBackgroundResource(active ? R.drawable.chip_ok_bg : R.drawable.chip_danger_bg);
            activation.setTextColor(ContextCompat.getColor(this, active ? R.color.aurora_ok : R.color.aurora_danger));
            activation.setCompoundDrawablesRelativeWithIntrinsicBounds(
                    active ? R.drawable.chip_dot_ok : R.drawable.chip_dot_danger, 0, 0, 0);
        }
    }

    /**
     * Rail item i shows the cards in NAV_RAIL_SECTIONS[i] — the two arrays are index-matched
     * and must stay that way.
     *
     * "Clock" is one subject, so it is one rail entry that reveals three cards: the analog
     * clock, the digital clock, and where the clock sits. They were three entries only because
     * they were three cards in the old scrolling screen — that is a fact about the old layout,
     * not about how anyone thinks about a clock.
     */
    private static final int[] NAV_RAIL_ITEMS = {
            R.id.navGeneral, R.id.navClock, R.id.navWallpapers, R.id.navAbout
    };
    private static final int[][] NAV_RAIL_SECTIONS = {
            { R.id.sectionGeneral },
            { R.id.sectionAnalog, R.id.sectionDigital, R.id.sectionClockLayout },
            { R.id.sectionWallpapers },
            { R.id.sectionAbout }
    };

    /**
     * In Leopard, Android draws the wallpaper and we cannot draw on top of it, so the clocks,
     * the clock layout and the background have nothing to act on.
     *
     * They are HIDDEN, not greyed out. Forty-odd disabled rows would be grim, and "everything
     * visible but disabled" is the exact defect this redesign set out to remove — repeating it
     * here would be going backwards. Leopard is not a setting, it is a different product, and
     * the Settings screen should admit that.
     */
    private static final int[] LEOPARD_HIDDEN_CARDS = {
            R.id.sectionAnalog, R.id.sectionDigital, R.id.sectionClockLayout
    };

    /** Rail entries with nothing left to show once their cards are hidden. */
    private static final int[] NAV_RAIL_LEOPARD_HIDDEN = {
            R.id.navClock
    };

    private static boolean isLeopardHiddenCard(int cardId) {
        for(int id : LEOPARD_HIDDEN_CARDS) if(id == cardId) return true;
        return false;
    }

    private void initNavRail() {
        for(int i = 0; i < NAV_RAIL_ITEMS.length; i++) {
            final int section = i;
            findViewById(NAV_RAIL_ITEMS[i]).setOnClickListener(v -> showSection(section));
        }
        applyLeopardVisibility();
        showSection(defaultSection());
    }

    /**
     * Where Settings opens. Wallpapers, not General: changing the picture is what this screen
     * gets opened for nearly every time, while General holds install-time switches the owner
     * touches once. Landing on General meant one extra tap on every single visit.
     */
    private int defaultSection() {
        for(int i = 0; i < NAV_RAIL_ITEMS.length; i++) {
            if(NAV_RAIL_ITEMS[i] != R.id.navWallpapers) continue;
            View v = findViewById(NAV_RAIL_ITEMS[i]);
            if(v != null && v.getVisibility() == View.VISIBLE) return i;
            break;
        }
        return firstVisibleSection();
    }

    /** Strip the rail down to what Leopard can actually act on. */
    private void applyLeopardVisibility() {
        boolean leopard = OperatingMode.isLeopard(mSharedPref);
        for(int id : NAV_RAIL_LEOPARD_HIDDEN) {
            View v = findViewById(id);
            if(v != null) v.setVisibility(leopard ? View.GONE : View.VISIBLE);
        }
        View banner = findViewById(R.id.leopardBanner);
        if(banner != null) banner.setVisibility(leopard ? View.VISIBLE : View.GONE);
        View leopardGroup = findViewById(R.id.groupLeopard);
        if(leopardGroup != null) leopardGroup.setVisibility(leopard ? View.VISIBLE : View.GONE);
    }

    private int firstVisibleSection() {
        for(int i = 0; i < NAV_RAIL_ITEMS.length; i++) {
            View v = findViewById(NAV_RAIL_ITEMS[i]);
            if(v != null && v.getVisibility() == View.VISIBLE) return i;
        }
        return 0;
    }

    /**
     * Only one section is attached at a time. Note this runs before attachAutoSave(), and the
     * rail lives outside mLinearLayoutSettingsContainer, so switching sections never counts as
     * a settings change and never triggers a save.
     */
    private void showSection(int section) {
        boolean leopard = OperatingMode.isLeopard(mSharedPref);
        for(int i = 0; i < NAV_RAIL_ITEMS.length; i++) {
            findViewById(NAV_RAIL_ITEMS[i]).setSelected(i == section);
            for(int card : NAV_RAIL_SECTIONS[i]) {
                // Belt and braces: in Leopard the whole Clock rail entry is gone anyway, but a
                // card must never appear just because its group happens to be selected.
                boolean show = i == section && !(leopard && isLeopardHiddenCard(card));
                findViewById(card).setVisibility(show ? View.VISIBLE : View.GONE);
            }
        }
        ScrollView content = findViewById(R.id.scrollViewSettingsContent);
        content.scrollTo(0, 0);
    }

    /**
     * Persist the settings right away and tell the caller (FullscreenActivity) to reload,
     * so leaving the screen with the back arrow applies everything — no apply button needed.
     */
    private void autoSave() {
        if(!mAutoSaveReady) return;
        save();
        setResult(RESULT_OK);
        // The mode chip mirrors the FSE switch, which lives in this tree.
        refreshHeaderChips();
        // Auto-switch is a switch that reveals a control. Hooked here rather than to its own
        // OnCheckedChangeListener because attachAutoSave() claims that listener on every switch
        // in the tree and would silently replace it.
        syncDependentRows();
    }

    /** Sub-settings that only mean something while the setting above them is on. */
    private void syncDependentRows() {
        View intervalRow = findViewById(R.id.rowAutoSwitchInterval);
        if(intervalRow != null && mCheckBoxWallpaperAutoSwitch != null) {
            intervalRow.setVisibility(mCheckBoxWallpaperAutoSwitch.isChecked() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * The five intervals as buttons rather than a spinner.
     *
     * There are only five and they all fit on one row, so a spinner was two taps to reach an
     * answer and hid the other four while you did it. Built here rather than in XML so the
     * labels and the seconds behind them come from one array each and cannot fall out of step.
     */
    private void buildIntervalButtons() {
        final LinearLayout row = findViewById(R.id.intervalButtons);
        if(row == null) return;
        row.removeAllViews();

        String[] labels = getResources().getStringArray(R.array.wallpaper_interval_labels);
        final int[] values = WallpaperRepo.AUTO_SWITCH_INTERVAL_VALUES;
        if(labels.length != values.length) return;   // a mismatched array is a build-time mistake

        int gap = Math.round(getResources().getDisplayMetrics().density * 8);
        for(int i = 0; i < values.length; i++) {
            final int seconds = values[i];
            TextView b = new TextView(this);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            if(i > 0) lp.setMarginStart(gap);
            b.setLayoutParams(lp);
            b.setText(labels[i]);
            b.setGravity(android.view.Gravity.CENTER);
            b.setMaxLines(1);
            b.setTextSize(13);
            b.setTypeface(b.getTypeface(), android.graphics.Typeface.BOLD);
            b.setTextColor(ContextCompat.getColorStateList(this, R.color.interval_btn_text));
            b.setBackgroundResource(R.drawable.interval_btn_bg);
            int padV = Math.round(getResources().getDisplayMetrics().density * 12);
            b.setPadding(gap, padV, gap, padV);
            b.setFocusable(true);
            b.setClickable(true);
            b.setOnClickListener(v -> {
                mSharedPref.edit().putInt(WallpaperRepo.PREF_AUTO_SWITCH_INTERVAL, seconds).apply();
                refreshIntervalSelection();
                autoSave();
            });
            row.addView(b);
        }
        refreshIntervalSelection();
    }

    /** Selection is drawn by the state list, so this only has to say which one is on. */
    private void refreshIntervalSelection() {
        LinearLayout row = findViewById(R.id.intervalButtons);
        if(row == null) return;
        int current = mSharedPref.getInt(WallpaperRepo.PREF_AUTO_SWITCH_INTERVAL,
                WallpaperRepo.AUTO_SWITCH_INTERVAL_DEFAULT);
        int[] values = WallpaperRepo.AUTO_SWITCH_INTERVAL_VALUES;
        for(int i = 0; i < row.getChildCount() && i < values.length; i++) {
            row.getChildAt(i).setSelected(values[i] == current);
        }
    }

    /** Hook a "save now" listener onto every checkbox, spinner and text field in the tree. */
    private void attachAutoSave(View view) {
        if(view == null || mAutoSaveExcluded.contains(view)) return;

        if(view instanceof CompoundButton) {
            ((CompoundButton) view).setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    autoSave();
                }
            });
        } else if(view instanceof Spinner) {
            ((Spinner) view).setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @Override
                public void onItemSelected(AdapterView<?> parent, View v, int position, long id) {
                    autoSave();
                }
                @Override
                public void onNothingSelected(AdapterView<?> parent) { }
            });
        } else if(view instanceof EditText) {
            ((EditText) view).addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) { }
                @Override
                public void afterTextChanged(Editable s) {
                    autoSave();
                }
            });
        } else if(view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for(int i = 0; i < group.getChildCount(); i++) {
                attachAutoSave(group.getChildAt(i));
            }
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        // safety net for anything that changed without going through a listener
        autoSave();
    }

    // No options menu: there is no "apply" item (settings save themselves as they change), and
    // the language switch moved into the header row as a chip. As a menu item it was drawn by
    // the system in the action-bar style, which is why it never lined up with the chips it sat
    // beside — the fix is for it to BE one of them, not to nudge it.
    // (The widget config screens still inflate menu_settings on their own.)

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if(item.getItemId() == android.R.id.home) {
            saveAndFinish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * Flip the app between Arabic and English. Persist first, then recreate() so the whole
     * activity re-inflates against the new locale — that is what re-mirrors the layout, which
     * setting the strings alone would not do.
     */
    /**
     * The settings text size slider.
     *
     * The slider runs 0..100 and carries an offset of 100, because SeekBar has no minimum before
     * API 26 and this app still supports 21.
     *
     * Applying the new scale needs recreate() — sp sizes are resolved at inflate time, so nothing
     * already on screen changes on its own. That is why the commit happens on release and not on
     * every tick: recreating the activity on each pixel of drag would be unusable.
     */
    private void initTextScale() {
        final SeekBar bar = findViewById(R.id.seekBarTextScale);
        final TextView value = findViewById(R.id.textViewTextScaleValue);
        if(bar == null || value == null) return;

        final int current = TextScaleHelper.saved(this);
        bar.setMax(TextScaleHelper.SCALE_MAX - TextScaleHelper.SCALE_MIN);
        bar.setProgress(current - TextScaleHelper.SCALE_MIN);
        value.setText(current + "%");

        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                // Live label while dragging, so the number tracks the thumb even though the
                // screen itself cannot re-scale until release.
                value.setText(TextScaleHelper.snap(progress + TextScaleHelper.SCALE_MIN) + "%");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {
                int percent = TextScaleHelper.snap(s.getProgress() + TextScaleHelper.SCALE_MIN);
                if(percent == TextScaleHelper.saved(BaseSettingsActivity.this)) return;
                TextScaleHelper.save(BaseSettingsActivity.this, percent);
                recreate();
            }
        });
    }

    private void toggleLanguage() {
        boolean arabicNow = LocaleHelper.LANG_ARABIC.equals(LocaleHelper.resolved(this));
        LocaleHelper.save(this, arabicNow ? LocaleHelper.LANG_ENGLISH : LocaleHelper.LANG_ARABIC);
        // The clock screen reads its own strings, so tell it to reload when we hand back.
        setResult(RESULT_OK);
        recreate();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        // Language first, then text scale. The scale wrapper has to be applied on its own because
        // LocaleHelper.wrap() returns the context untouched when the language is the system
        // default, so chaining the scale inside it would silently do nothing for most users.
        super.attachBaseContext(TextScaleHelper.wrap(LocaleHelper.wrap(newBase)));
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
            case(FIT_EDITOR_REQUEST):
                // Coming back from editing one image of an unconfirmed batch: put the review
                // screen back up so the customer can carry on through the rest. Without this the
                // batch would be silently accepted the moment they edited any single image.
                if(mPendingBatch != null && !mPendingBatch.isEmpty()) {
                    reopenBatchReview();
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
        }
        autoSave();
    }

    private void saveAndFinish() {
        save();
        setResult(RESULT_OK);
        finish();
    }

    /**
     * Android 10+ blocks starting activities from a BroadcastReceiver unless the app
     * holds the "Display over other apps" permission. Ask for it when the user enables
     * auto-start so the app can actually open itself when the car (device) boots.
     *
     * Without it the failure is invisible: the pref reads "on", BootReceiver runs, and the
     * startActivity call is dropped by the system — the car simply comes up on its launcher.
     * That is why this is asked for insistently rather than once.
     */
    private void requestOverlayPermissionIfNeeded() {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        if(Settings.canDrawOverlays(this)) return;
        // Two routes can fire on one tap (the checkbox listener and applyMode); never stack
        // a second dialog on top of the first.
        if(mOverlayPermissionDialog != null && mOverlayPermissionDialog.isShowing()) return;
        mOverlayPermissionAsked = true;
        mOverlayPermissionDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.auto_start_overlay_title)
                .setMessage(R.string.auto_start_overlay_message)
                .setPositiveButton(R.string.ok, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        try {
                            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(intent);
                        } catch(ActivityNotFoundException e) {
                            // Some head units ship no overlay settings screen. The old comment
                            // here claimed auto-start "usually still works" then — it does not on
                            // Android 10+, which is the whole reason this permission is needed.
                            // Say so instead of leaving a dead setting behind.
                            Toast.makeText(BaseSettingsActivity.this,
                                    R.string.auto_start_overlay_unavailable, Toast.LENGTH_LONG).show();
                        }
                    }
                })
                .setNegativeButton(R.string.update_cancel, null)
                .show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The overlay screen is a separate activity that reports no result, and on a fresh
        // install the technician can reach FSE before ever seeing the dialog. Re-check on the
        // way back in, so "auto-start is on but Android is blocking it" cannot survive quietly.
        // Once per visit: asking again the instant the user declines would be a loop.
        if(mSharedPref != null && !mOverlayPermissionAsked
                && mSharedPref.getBoolean(BootReceiver.PREF_AUTO_START, false)) {
            requestOverlayPermissionIfNeeded();
        }
    }

    protected void enableDisableAllSettings(boolean state) {
        mCheckBoxAutoStartOnBoot.setEnabled(state);
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
        mCheckBoxShowWeather.setEnabled(state);
        mCheckBoxWallpaperEnabled.setEnabled(state);
        mCheckBoxWallpaperAutoSwitch.setEnabled(state);
        mButtonManageWallpapers.setEnabled(state);
        mButtonRefreshWallpapers.setEnabled(state);
        mCheckBoxShowClockOverlay.setEnabled(state);
        mSpinnerClockPosition.setEnabled(state);
        mSpinnerClockSize.setEnabled(state);
        mCheckBoxWallpaperLocal.setEnabled(state);
        mButtonAddLocalWallpapers.setEnabled(state);

        // The interval buttons are built at runtime, so they are not one of the fields above and
        // were silently left live when the interval was a Spinner's replacement.
        LinearLayout intervals = findViewById(R.id.intervalButtons);
        if(intervals != null) {
            for(int i = 0; i < intervals.getChildCount(); i++) {
                View b = intervals.getChildAt(i);
                b.setEnabled(state);
                b.setAlpha(state ? 1f : 0.4f);
            }
        }

        // The operating mode was never gated, because the old FSE checkbox was easy to miss at
        // the bottom of the screen. The header chip put it one tap from anywhere — including on
        // a device that has not been activated, where picking FSE would also quietly switch on
        // start-on-boot. Gate both routes.
        int[] modeViews = { R.id.radioGroupMode, R.id.radioModeNormal, R.id.radioModeFse,
                R.id.radioModeLeopard, R.id.chipMode };
        for(int id : modeViews) {
            View v = findViewById(id);
            if(v == null) continue;
            // Leopard has its own reason to be off; do not overrule it back on here.
            if(id == R.id.radioModeLeopard && !OperatingMode.isSupported(this)) continue;
            v.setEnabled(state);
        }
        View chip = findViewById(R.id.chipMode);
        if(chip != null) chip.setAlpha(state ? 1f : 0.5f);
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

    /**
     * Show every wallpaper this device can play — public/global ones and the ones assigned
     * privately to this car alike — each with a checkbox. Unchecking one hides it from the
     * slideshow on THIS device only; nothing is sent to the server, so the same public
     * wallpaper keeps playing on every other car.
     */
    public void onClickManageWallpapers(View v) {
        if(mWallpaperRepo == null) return;
        mWallpaperRepo.load();
        final List<WallpaperItem> items = mWallpaperRepo.allItems();
        if(items.isEmpty()) {
            Toast.makeText(this, getString(R.string.wallpaper_manage_empty), Toast.LENGTH_LONG).show();
            return;
        }

        final WallpaperSelectAdapter adapter = new WallpaperSelectAdapter(
                this, mWallpaperRepo, items, mWallpaperRepo.getHiddenUrls());

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.wallpaper_manage_hint));
        hint.setPadding(pad, pad, pad, pad / 2);
        hint.setTextSize(12);

        // A grid, two wide: you are choosing between pictures, so show the pictures. As a list
        // of 88x56 thumbnails the image was the smallest thing in its own row.
        //
        // No setOnItemClickListener here on purpose — the cells carry their own (see
        // WallpaperSelectAdapter.getView), because a cell with a button in it never receives the
        // AdapterView's row click.
        GridView grid = new GridView(this);
        grid.setAdapter(adapter);
        grid.setNumColumns(2);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        grid.setPadding(pad / 2, 0, pad / 2, 0);
        grid.setClipToPadding(false);

        ll.addView(hint);
        ll.addView(grid);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.wallpaper_manage_title)
                .setView(ll)
                .setPositiveButton(R.string.save, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d2, int which) {
                        List<String> hidden = adapter.getHiddenUrls();
                        mWallpaperRepo.setHiddenUrls(hidden);
                        mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_manage_saved,
                                items.size() - hidden.size(), hidden.size()));
                        autoSave(); // makes the clock screen reload the playlist on return
                    }
                })
                .setNegativeButton(R.string.update_cancel, null)
                .setNeutralButton(R.string.wallpaper_manage_select_all, null)
                .show();

        // The grid is the content, so give it the glass. The default dialog width wraps its view
        // and left two columns of pictures squeezed into half the screen.
        if(dialog.getWindow() != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            dialog.getWindow().setLayout(Math.round(dm.widthPixels * 0.92f),
                    Math.round(dm.heightPixels * 0.9f));
        }

        // Editing closes this dialog: the editor is a full screen, and leaving a dialog behind it
        // would mean coming back to a list built from a playlist the edit just changed.
        adapter.setOnEditListener(item -> {
            mWallpaperRepo.setHiddenUrls(adapter.getHiddenUrls());   // don't lose the ticks
            dialog.dismiss();
            editWallpaper(item);
        });

        // keep the dialog open when "select all" is pressed (it toggles all rows instead)
        Button selectAll = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if(selectAll != null) {
            selectAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View button) {
                    adapter.setAllVisible(!adapter.areAllVisible());
                }
            });
        }
    }

    /**
     * Edit how one wallpaper is fitted, from anywhere in the manage list.
     *
     * A cloud wallpaper is copied to this device first and edited as the copy, and the cloud
     * original is hidden here. That is a deliberate product rule, not a workaround: the fit
     * settings are per-url, and the same url plays on every other car. Editing the cloud image
     * in place would either do nothing visible or — worse, if it were ever synced — push one
     * technician's crop onto everybody. A local copy makes the blast radius exactly one car.
     */
    private void editWallpaper(final WallpaperItem item) {
        if(item == null || item.url == null || item.url.isEmpty()) return;

        if(!isRemoteUrl(item.url)) {
            openFitEditor(item.url);
            return;
        }

        mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_copying));
        new Thread(() -> {
            java.io.File file = null;
            try {
                java.net.HttpURLConnection c =
                        (java.net.HttpURLConnection) new java.net.URL(item.url).openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(30000);
                c.setInstanceFollowRedirects(true);
                java.io.InputStream in = c.getInputStream();
                try {
                    file = mWallpaperRepo.importLocalFileReturningPath(in, fileNameOf(item.url));
                } finally {
                    try { in.close(); } catch(Exception ignored) {}
                    c.disconnect();
                }
            } catch(Exception ignored) {
                // falls through to the null check below
            }
            final String saved = file == null ? null : file.getAbsolutePath();
            runOnUiThread(() -> {
                if(isFinishing()) return;
                if(saved == null) {
                    mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_copy_failed));
                    Toast.makeText(this, R.string.wallpaper_copy_failed, Toast.LENGTH_LONG).show();
                    return;
                }
                // The copy carries the original's fit as a starting point, so the technician
                // edits from where it was rather than from the defaults.
                mWallpaperRepo.setFit("file://" + saved, mWallpaperRepo.getFit(item.url));
                hideUrlLocally(item.url);
                mTextViewWallpaperStatus.setText(getString(R.string.wallpaper_now_local));
                autoSave();
                openFitEditor("file://" + saved);
            });
        }).start();
    }

    /** Add one url to this device's hidden list, leaving every other car untouched. */
    private void hideUrlLocally(String url) {
        java.util.Set<String> hidden = mWallpaperRepo.getHiddenUrls();
        java.util.List<String> next = new java.util.ArrayList<>(hidden);
        if(!next.contains(url)) next.add(url);
        mWallpaperRepo.setHiddenUrls(next);
    }

    private static boolean isRemoteUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    /** Last path segment, query stripped — the name the local copy is saved under. */
    private static String fileNameOf(String url) {
        String u = url;
        int q = u.indexOf('?');
        if(q >= 0) u = u.substring(0, q);
        int slash = u.lastIndexOf('/');
        if(slash >= 0 && slash < u.length() - 1) u = u.substring(slash + 1);
        return u.isEmpty() ? "wallpaper.jpg" : u;
    }

    /** Fill the Device ID and MAC address text views shown in the wallpapers section. */
    @SuppressLint("SetTextI18n")
    private void updateDeviceInfoViews() {
        if(mTextViewSettingsDeviceId != null && mWallpaperRepo != null) {
            String status = getString(mWallpaperRepo.isActive()
                    ? R.string.device_status_active : R.string.device_status_pending);
            mTextViewSettingsDeviceId.setText(
                    getString(R.string.device_id_status, mWallpaperRepo.getDeviceId(), status));
        }
        if(mTextViewSettingsMacAddress != null && mWallpaperRepo != null) {
            // Show the actual identifier used to match wallpapers (Device ID), not the
            // MAC address, which is unavailable on Android 6+ for privacy reasons.
            mTextViewSettingsMacAddress.setText(
                    getString(R.string.device_linking_code, mWallpaperRepo.getDeviceId()));
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
                autoSave();
            }
            @Override
            public void onNothingSelected(AdapterView<?> adapterView) { }
        };
        mSpinnerDesignAnalogFace.setOnItemSelectedListener(listener);
        mSpinnerDesignAnalogHours.setOnItemSelectedListener(listener);
        mSpinnerDesignAnalogMinutes.setOnItemSelectedListener(listener);
        mSpinnerDesignAnalogSeconds.setOnItemSelectedListener(listener);

        // these four carry their own listeners (image picker), so the generic auto-save
        // listener must not replace them
        mAutoSaveExcluded.add(mSpinnerDesignAnalogFace);
        mAutoSaveExcluded.add(mSpinnerDesignAnalogHours);
        mAutoSaveExcluded.add(mSpinnerDesignAnalogMinutes);
        mAutoSaveExcluded.add(mSpinnerDesignAnalogSeconds);
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
        paintColorPreview(color, previewView, hexTextBox);
        if(hexTextBox == null) {
            // no hex box = one of the previews on the settings screen, i.e. a colour was
            // just confirmed in the picker; the live preview inside the dialog passes one
            autoSave();
        }
    }

    /** The drawing half, without the autoSave — static so the shared colour dialog can run
     *  from any Activity, including the Fit Editor. */
    private static void paintColorPreview(int color, View previewView, EditText hexTextBox) {
        previewView.setBackgroundColor(Color.argb(0xff, Color.red(color), Color.green(color), Color.blue(color)));
        if(hexTextBox != null) {
            hexTextBox.setText(String.format("#%06X", (0xFFFFFF & color)));
        }
    }
    interface ColorDialogCallback {
        void ok(boolean customColor, int red, int green, int blue, boolean applyForAll);
    }

    private void showColorDialog(Boolean customColor, int initialColor, boolean showApplyForAll, final ColorDialogCallback colorDialogFinished) {
        showColorDialog(this, customColor, initialColor, showApplyForAll, colorDialogFinished);
    }

    /** Static so the Fit Editor can reuse this exact dialog instead of growing a second one.
     *  It never touched instance state — only the Context. */
    static void showColorDialog(Context ctx, Boolean customColor, int initialColor, boolean showApplyForAll, final ColorDialogCallback colorDialogFinished) {
        final Dialog ad = new Dialog(ctx);
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
                paintColorPreview(Color.argb(0xff, seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress()), colorPreview, editTextColorHex);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        seekBarGreen.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                paintColorPreview(Color.argb(0xff, seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress()), colorPreview, editTextColorHex);
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) { }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) { }
        });
        seekBarBlue.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                paintColorPreview(Color.argb(0xff, seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress()), colorPreview, editTextColorHex);
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
                    //paintColorPreview(Color.argb(0xff, seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress()), colorPreview, null);
                } catch(Exception ignored) { }
            }
            @Override
            public void afterTextChanged(Editable editable) { }
        });
        paintColorPreview(Color.argb(0xff, seekBarRed.getProgress(), seekBarGreen.getProgress(), seekBarBlue.getProgress()), colorPreview, editTextColorHex);
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

        editor.putBoolean(FullscreenActivity.PREF_FSE_SCREEN, mCheckBoxFse.isChecked());
        editor.putBoolean(BootReceiver.PREF_AUTO_START, mCheckBoxAutoStartOnBoot.isChecked());
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
        editor.putBoolean("show-weather", mCheckBoxShowWeather.isChecked());
        editor.putString("weather-city", mEditTextWeatherCity.getText().toString().trim());
        editor.putBoolean(WallpaperRepo.PREF_ENABLED, mCheckBoxWallpaperEnabled.isChecked());
        editor.putBoolean(WallpaperRepo.PREF_AUTO_SWITCH, mCheckBoxWallpaperAutoSwitch.isChecked());
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
        java.io.File lastImported = null;
        for(Uri uri : uris) {
            try {
                String name = queryDisplayName(uri);
                if(name == null || name.trim().isEmpty()) name = "wp_" + uri.hashCode() + ".jpg";
                java.io.InputStream in = getContentResolver().openInputStream(uri);
                if(in == null) continue;
                java.io.File f = mWallpaperRepo.importLocalFileReturningPath(in, name);
                if(f != null) { ok++; lastImported = f; }
            } catch(Exception ignored) { }
        }
        mWallpaperRepo.load();
        mTextViewLocalFolder.setText(getString(R.string.wallpaper_local_folder, mWallpaperRepo.getLocalFolder().getAbsolutePath()));
        Toast.makeText(this, getString(R.string.wallpaper_added, ok), Toast.LENGTH_LONG).show();

        // A single import lands in the editor — auto-detection has usually already picked the
        // right mode, so this is a one-glance confirmation, not homework.
        // A bulk import does NOT: opening a queue of 10 editors punishes the exact case (a
        // technician loading a customer's 40 reels) that most needs to be fast. Those get the
        // defaults applied silently and can be edited one by one from the list later.
        if(ok == 1 && lastImported != null && mWallpaperRepo.isEditOnImport()) {
            openFitEditor("file://" + lastImported.getAbsolutePath());
        }
    }

    /**
     * The three-way operating mode: Normal / FSE / Leopard.
     *
     * This replaces the old standalone FSE checkbox as the thing the technician touches. The
     * checkbox itself stays in the tree but hidden, because save() and half a dozen other
     * places still read it — the radio drives it, so the two can never disagree the way the
     * activation-screen and settings FSE checkboxes historically did.
     */
    private void initModePicker() {
        final RadioGroup group = findViewById(R.id.radioGroupMode);
        if(group == null) return;

        // The FSE switch is now a mirror, not a control.
        View fseRow = findViewById(R.id.checkBoxFseSettings);
        if(fseRow != null) fseRow.setVisibility(View.GONE);

        final RadioButton leopard = findViewById(R.id.radioModeLeopard);
        boolean supported = OperatingMode.isSupported(this);
        if(!supported) {
            // A runtime check with a real state, not a crash and not a silent no-op: some
            // cheap ROMs ship without the live wallpaper picker at all.
            leopard.setEnabled(false);
            leopard.setAlpha(0.4f);
        }

        int mode = OperatingMode.get(mSharedPref);
        if(mode == OperatingMode.LEOPARD && !supported) mode = OperatingMode.NORMAL;
        group.check(radioFor(mode));
        updateModeDescription(mode, supported);

        group.setOnCheckedChangeListener((g, checkedId) -> {
            int m = checkedId == R.id.radioModeLeopard ? OperatingMode.LEOPARD
                    : checkedId == R.id.radioModeFse ? OperatingMode.FSE : OperatingMode.NORMAL;
            applyMode(m);
        });

        View openPicker = findViewById(R.id.buttonOpenPicker);
        if(openPicker != null) {
            openPicker.setOnClickListener(v -> startActivity(new Intent(this, LeopardPickerActivity.class)));
        }
        CompoundButton closeAfter = findViewById(R.id.checkBoxLeopardCloseAfterSet);
        if(closeAfter != null) {
            closeAfter.setChecked(mSharedPref.getBoolean("leopard-close-after-set", false));
            mAutoSaveExcluded.add(closeAfter);
            closeAfter.setOnCheckedChangeListener((b, checked) ->
                    mSharedPref.edit().putBoolean("leopard-close-after-set", checked).apply());
        }
    }

    private static int radioFor(int mode) {
        return mode == OperatingMode.LEOPARD ? R.id.radioModeLeopard
                : mode == OperatingMode.FSE ? R.id.radioModeFse : R.id.radioModeNormal;
    }

    /**
     * The one place a mode change happens. Both routes in — the radio group at the bottom of
     * General, and the header chip — land here, so neither can drift from the other.
     */
    private void applyMode(int mode) {
        OperatingMode.set(mSharedPref, mode);

        // Keep the hidden mirror honest, so save() writes the same thing we just did.
        if(mCheckBoxFse != null) mCheckBoxFse.setChecked(mode == OperatingMode.FSE);

        // FSE is a car that boots straight into this screen — that is what the mode IS. Leaving
        // start-on-boot off would mean picking FSE and getting the launcher, which reads as the
        // mode not working. So the switch follows the mode in both directions: leaving FSE takes
        // it back off, because the setting belongs to FSE rather than to the car.
        boolean wantAutoStart = mode == OperatingMode.FSE;
        if(mCheckBoxAutoStartOnBoot != null && mCheckBoxAutoStartOnBoot.isChecked() != wantAutoStart) {
            // setChecked fires the listener, which persists it and (when turning on) asks for the
            // overlay permission — exactly what a manual tap would have done.
            mCheckBoxAutoStartOnBoot.setChecked(wantAutoStart);
        } else if(wantAutoStart) {
            // The switch was already on, so setChecked() above is a no-op and its listener never
            // runs — which used to mean the permission was never requested on this path. The pref
            // then says "start on boot" while Android silently blocks the launch, and the car
            // comes up on its launcher: exactly the "I enabled FSE and it still does not open"
            // report. Persist and ask here instead of relying on the switch having moved.
            mSharedPref.edit().putBoolean(BootReceiver.PREF_AUTO_START, true).apply();
            requestOverlayPermissionIfNeeded();
        }

        updateModeDescription(mode, OperatingMode.isSupported(this));
        applyLeopardVisibility();
        showSection(firstVisibleSection());
        refreshHeaderChips();
        setResult(RESULT_OK);
    }

    /**
     * The header chip is a control, not just a badge (it carries a caret to say so). Same three
     * choices as the radio group, reachable without scrolling to the bottom of General.
     */
    private void showModeDialog() {
        final boolean supported = OperatingMode.isSupported(this);
        final int[] modes = { OperatingMode.NORMAL, OperatingMode.FSE, OperatingMode.LEOPARD };
        CharSequence[] labels = {
                getString(R.string.mode_normal),
                getString(R.string.mode_fse),
                supported ? getString(R.string.mode_leopard)
                        : getString(R.string.mode_leopard) + " — " + getString(R.string.leopard_unsupported)
        };
        int current = OperatingMode.get(mSharedPref);
        int checked = current == OperatingMode.LEOPARD ? 2 : current == OperatingMode.FSE ? 1 : 0;

        new AlertDialog.Builder(this)
                .setTitle(R.string.mode_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    if(modes[which] == OperatingMode.LEOPARD && !supported) {
                        // Some cheap ROMs ship no live wallpaper picker at all. Say so rather
                        // than accepting a mode that cannot start.
                        Toast.makeText(this, R.string.leopard_unsupported, Toast.LENGTH_LONG).show();
                        return;
                    }
                    d.dismiss();
                    // Drive the radio group, whose listener is what actually applies the mode —
                    // setting it here as well would apply everything twice.
                    RadioGroup group = findViewById(R.id.radioGroupMode);
                    if(group != null) group.check(radioFor(modes[which]));
                    else applyMode(modes[which]);
                })
                .setNegativeButton(R.string.update_cancel, null)
                .show();
    }

    private void updateModeDescription(int mode, boolean leopardSupported) {
        TextView desc = findViewById(R.id.textViewModeDesc);
        if(desc == null) return;
        int res = mode == OperatingMode.LEOPARD ? R.string.mode_leopard_desc
                : mode == OperatingMode.FSE ? R.string.mode_fse_desc : R.string.mode_normal_desc;
        String text = getString(res);
        if(!leopardSupported) text += "\n" + getString(R.string.leopard_unsupported);
        desc.setText(text);
    }

    /**
     * "Image import settings" — the defaults the Fit Editor opens with.
     *
     * Wired by hand rather than through attachAutoSave: these live in WallpaperRepo's own keys,
     * not in the big save()/load() blob, so the generic auto-save has nothing to write for them.
     */
    private void initFitDefaults() {
        final Spinner mode = findViewById(R.id.spinnerFitDefaultMode);
        final SeekBar blur = findViewById(R.id.seekBarFitDefaultBlur);
        final TextView blurValue = findViewById(R.id.textViewFitDefaultBlurValue);
        final View colorRow = findViewById(R.id.rowFitDefaultColor);
        final View colorPreview = findViewById(R.id.viewFitDefaultColorPreview);
        final CompoundButton editOnImport = findViewById(R.id.checkBoxFitEditOnImport);
        if(mode == null) return;

        // Spinner order must match FIT_MODE_ORDER below.
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item,
                new String[]{
                        getString(R.string.fit_mode_auto),
                        getString(R.string.fit_mode_fill),
                        getString(R.string.fit_mode_blur),
                        getString(R.string.fit_mode_color)
                });
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        mode.setAdapter(adapter);

        FitSettings d = mWallpaperRepo.getFitDefaults();
        mode.setSelection(indexOfMode(d.mode), false);
        blur.setProgress(d.blur);
        blurValue.setText(String.valueOf(d.blur));
        colorPreview.setBackgroundColor(d.barColor);
        editOnImport.setChecked(mWallpaperRepo.isEditOnImport());

        mAutoSaveExcluded.add(mode);
        mAutoSaveExcluded.add(blur);
        mAutoSaveExcluded.add(editOnImport);

        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                FitSettings s = mWallpaperRepo.getFitDefaults();
                s.mode = FIT_MODE_ORDER[pos];
                mWallpaperRepo.setFitDefaults(s);
                syncFitDefaultRows();
            }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });

        blur.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int value, boolean fromUser) {
                blurValue.setText(String.valueOf(value));
                if(!fromUser) return;
                FitSettings f = mWallpaperRepo.getFitDefaults();
                f.blur = value;
                mWallpaperRepo.setFitDefaults(f);
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });

        colorRow.setOnClickListener(v -> showColorDialog(this, true, mWallpaperRepo.getFitDefaults().barColor, false,
                (custom, r, g, b, all) -> {
                    FitSettings f = mWallpaperRepo.getFitDefaults();
                    f.barColor = Color.rgb(r, g, b);
                    mWallpaperRepo.setFitDefaults(f);
                    colorPreview.setBackgroundColor(f.barColor);
                }));

        editOnImport.setOnCheckedChangeListener((b, checked) -> mWallpaperRepo.setEditOnImport(checked));

        syncFitDefaultRows();
    }

    /** Spinner position -> FitSettings mode. */
    private static final int[] FIT_MODE_ORDER = {
            FitSettings.MODE_AUTO, FitSettings.MODE_FILL, FitSettings.MODE_BLUR, FitSettings.MODE_COLOR
    };

    private static int indexOfMode(int mode) {
        for(int i = 0; i < FIT_MODE_ORDER.length; i++) if(FIT_MODE_ORDER[i] == mode) return i;
        return 0;
    }

    /** Hide the defaults that the chosen mode cannot use. */
    private void syncFitDefaultRows() {
        View blurRow = findViewById(R.id.rowFitDefaultBlur);
        View colorRow = findViewById(R.id.rowFitDefaultColor);
        if(blurRow == null || colorRow == null) return;
        int mode = mWallpaperRepo.getFitDefaults().mode;
        // Automatic resolves to blur for tall images, so both stay meaningful there. Only an
        // explicit Fill has no bars at all and therefore nothing to fill them with.
        boolean bars = mode != FitSettings.MODE_FILL;
        blurRow.setVisibility(bars && mode != FitSettings.MODE_COLOR ? View.VISIBLE : View.GONE);
        colorRow.setVisibility(bars && mode != FitSettings.MODE_BLUR ? View.VISIBLE : View.GONE);
    }

    /** Open the fit editor against the screen the wallpaper will actually be shown on. */
    protected void openFitEditor(String url) {
        int w, h;
        if(mCheckBoxFse != null && mCheckBoxFse.isChecked()) {
            // FSE pins the wallpaper window to 1920x720 regardless of the panel's real size.
            w = 1920; h = 720;
        } else {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            w = dm.widthPixels; h = dm.heightPixels;
            if(w < h) { int t = w; w = h; h = t; }   // the clock screen is always landscape
        }
        startActivityForResult(FitEditorActivity.intent(this, url, w, h), FIT_EDITOR_REQUEST);
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

    /**
     * Review a freshly uploaded batch before it joins the playlist.
     *
     * The files are already on disk — the server had to write them somewhere to receive them — so
     * "adding" here means keeping them and "cancelling" means deleting them again. That is why
     * cancel is destructive on purpose: the customer sent a batch from their phone, glanced at it
     * on the car screen and said no.
     *
     * Ticked = keep. Pencil = open the fit editor on that one image and come back here.
     */
    private void reviewImportedBatch(List<String> savedPaths) {
        if(mWallpaperRepo == null) return;

        // A file can vanish between upload and review — the editor's trash button deletes it.
        final List<WallpaperItem> items = new ArrayList<>();
        for(String path : savedPaths) {
            if(path == null) continue;
            if(!new File(path).exists()) continue;
            items.add(new WallpaperItem(WallpaperItem.guessType(path), path));
        }
        if(items.isEmpty()) {
            mPendingBatch = null;
            mBatchDiscarded = null;
            return;
        }

        mPendingBatch = new ArrayList<>();
        for(WallpaperItem item : items) mPendingBatch.add(item.url);

        // Carry the ticks across an edit round-trip; on the first showing nothing is discarded.
        Set<String> discarded = mBatchDiscarded != null ? mBatchDiscarded : new HashSet<String>();
        final WallpaperSelectAdapter adapter = new WallpaperSelectAdapter(
                this, mWallpaperRepo, items, discarded);

        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);

        TextView hint = new TextView(this);
        hint.setText(getString(R.string.batch_review_hint));
        hint.setPadding(pad, pad, pad, pad / 2);
        hint.setTextSize(12);

        GridView grid = new GridView(this);
        grid.setAdapter(adapter);
        grid.setNumColumns(2);
        grid.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        grid.setPadding(pad / 2, 0, pad / 2, 0);
        grid.setClipToPadding(false);

        ll.addView(hint);
        ll.addView(grid);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(getString(R.string.batch_review_title, items.size()))
                .setView(ll)
                .setCancelable(false) // dismissing by accident would silently discard the batch
                .setPositiveButton(R.string.fit_done, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d2, int which) {
                        commitBatch(adapter.getHiddenUrls());
                    }
                })
                .setNegativeButton(R.string.batch_review_discard, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d2, int which) {
                        confirmDiscardBatch();
                    }
                })
                .show();

        if(dialog.getWindow() != null) {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            dialog.getWindow().setLayout(Math.round(dm.widthPixels * 0.92f),
                    Math.round(dm.heightPixels * 0.9f));
        }

        adapter.setOnEditListener(item -> {
            // Remember the ticks; onActivityResult brings this screen back afterwards.
            mBatchDiscarded = new HashSet<>(adapter.getHiddenUrls());
            dialog.dismiss();
            openFitEditor(item.url);
        });
    }

    /** Put the review screen back after an edit, dropping anything the editor deleted. */
    private void reopenBatchReview() {
        List<String> paths = mPendingBatch;
        mPendingBatch = null;
        if(paths != null) reviewImportedBatch(paths);
    }

    /** Keep the ticked images, delete the rest off the disk, and refresh the playlist. */
    private void commitBatch(List<String> discardedUrls) {
        Set<String> discarded = new HashSet<>(discardedUrls);
        int kept = 0;
        if(mPendingBatch != null) {
            for(String path : mPendingBatch) {
                if(discarded.contains(path)) {
                    deleteImportedFile(path);
                } else {
                    kept++;
                }
            }
        }
        mPendingBatch = null;
        mBatchDiscarded = null;

        mWallpaperRepo.load();
        Toast.makeText(this, getString(R.string.batch_review_added, kept), Toast.LENGTH_LONG).show();
        setResult(RESULT_OK);
        autoSave(); // makes the clock screen reload the playlist on return
    }

    /** Cancelling throws away files the customer already sent, so make them say it twice. */
    private void confirmDiscardBatch() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.batch_review_discard)
                .setMessage(R.string.batch_review_discard_confirm)
                .setPositiveButton(R.string.batch_review_discard, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        if(mPendingBatch != null) {
                            for(String path : mPendingBatch) deleteImportedFile(path);
                        }
                        mPendingBatch = null;
                        mBatchDiscarded = null;
                        mWallpaperRepo.load();
                        autoSave();
                    }
                })
                .setNegativeButton(R.string.update_cancel, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        reopenBatchReview(); // back to the review, nothing lost
                    }
                })
                .show();
    }

    /** Delete an imported file plus the fit settings that were keyed to it. */
    private void deleteImportedFile(String path) {
        if(path == null) return;
        try {
            mWallpaperRepo.clearFit(path);
            File f = new File(path);
            if(f.exists()) f.delete();
        } catch(Exception ignored) { }
    }

    /** Show a QR code that the customer scans to upload an image over the local Wi-Fi. */
    public void onClickPairPhone(View v) {
        stopUploadServer();
        // Assigned below; the upload listener can fire before the local would be in scope.
        final AlertDialog[] holder = new AlertDialog[1];
        final String url;
        try {
            mUploadServer = UploadServer.startNew(this, mWallpaperRepo, new UploadServer.UploadListener() {
                @Override
                public void onUploaded(List<String> savedPaths) {
                    // The server posts this to the main thread, but the activity may already be
                    // gone by the time it lands — the customer can walk away mid-upload.
                    if(isFinishing() || isDestroyed()) return;
                    if(savedPaths == null || savedPaths.isEmpty()) return;

                    setResult(RESULT_OK); // so the clock reloads and shows the uploaded wallpaper
                    Toast.makeText(BaseSettingsActivity.this,
                            getString(R.string.pair_uploaded_n, savedPaths.size()),
                            Toast.LENGTH_LONG).show();

                    // The images have landed, so the QR has done its job: close it. Dismissing
                    // also stops the server (see setOnDismissListener) — a second batch arriving
                    // while the first is being reviewed would be two batches and one screen.
                    if(holder[0] != null && holder[0].isShowing()) holder[0].dismiss();

                    reviewImportedBatch(savedPaths);
                }
            });
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
        mPairStatus.setTextColor(ContextCompat.getColor(this, R.color.aurora_muted));
        mPairStatus.setText(R.string.pair_waiting);

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
        holder[0] = dlg;
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
        // The background image was the one caller this note did not apply to, and it is gone.
        // Every remaining caller is a clock graphic, which the note is about.
        Toast.makeText(this, getString(R.string.own_images_note), Toast.LENGTH_LONG).show();
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

    /** Ask once for location so the weather can follow the car. Denial is fine: it falls back to the typed city / IP. */
    private void maybeRequestLocationForWeather() {
        if(!mSharedPref.getBoolean("show-weather", true)) return;
        boolean granted = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        if(!granted) {
            ActivityCompat.requestPermissions(this, new String[]{
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION}, PERMISSION_REQUEST);
        }
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
            // the unlock API deliberately answers valid codes with status 999,
            // so the payload arrives on the error stream — read it BEFORE disconnect()
            if(statusCode == 999) {
                BufferedReader in = new BufferedReader(new InputStreamReader(conn.getErrorStream()));
                StringBuffer response = new StringBuffer();
                String inputLine;
                while((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();
                conn.disconnect();
                return response.toString();
            } else {
                conn.disconnect();
                throw new Exception(getString(R.string.invalid_code) + " ("+statusCode+")");
            }
        } catch(IOException e) {
            e.printStackTrace();
            throw new Exception(getString(R.string.check_internet_conn));
        }
    }

}
