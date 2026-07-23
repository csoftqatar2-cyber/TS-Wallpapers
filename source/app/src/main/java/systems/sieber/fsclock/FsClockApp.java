package systems.sieber.fsclock;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.util.Log;

import androidx.appcompat.app.AppCompatDelegate;

public class FsClockApp extends Application {

    @Override
    public void onCreate() {
        migrateSettings();
        setAppTheme(getAppTheme(getApplicationContext()));
        IntegrityGuard.init(getApplicationContext());
        super.onCreate();
        kickGwmSync();
    }

    /**
     * One GWM Split mirror per process launch, independent of the operating mode (the clock view
     * that runs the periodic sync does not exist in Leopard). No-ops instantly when the section is
     * off or storage access is missing, so it costs nothing for the vast majority of cars.
     */
    private void kickGwmSync() {
        try {
            final Context app = getApplicationContext();
            SharedPreferences prefs = app.getSharedPreferences(
                    BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
            if(!GwmSync.isEnabled(prefs)) return;
            GwmSync.syncAsync(app, new WallpaperRepo(app), null);
        } catch(Throwable ignored) { }
    }

    public static void setAppTheme(int setting) {
        AppCompatDelegate.setDefaultNightMode(setting);
    }

    public static int getAppTheme(Context context) {
        //SharedPreferences prefs = context.getSharedPreferences(MainActivity.PREFS_NAME, 0);
        //return prefs.getInt("dark-mode-native", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        // Warm Aurora is a dark-only identity: the head unit's system theme must not flip us to light.
        return AppCompatDelegate.MODE_NIGHT_YES;
    }

    public static boolean isDarkThemeActive(Context context, int setting) {
        if(setting == AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM) {
            return isDarkThemeActive(context);
        } else {
            return setting == AppCompatDelegate.MODE_NIGHT_YES;
        }
    }

    public static boolean isDarkThemeActive(Context context) {
        int uiMode = context.getResources().getConfiguration().uiMode;
        return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    /**
     * Park every install that predates the operating-mode picker on an explicit mode.
     *
     * Leopard is new, so nobody can already be in it — but the flag defaults to absent rather
     * than false, and an install updating into this version has never made the choice. This
     * writes the decision down once so the picker opens on the truth instead of a guess.
     *
     * A car already running FSE keeps FSE. Flipping it would unpin the 1920x720 window and
     * strand the per-image positions the owner set, on every car in the field, silently.
     */
    private void migrateOperatingMode(SharedPreferences prefs) {
        final String MIGRATED = "operating-mode-migrated";
        if(prefs.contains(MIGRATED)) return;

        // An empty prefs file is a fresh install, which has no history to preserve and will
        // make its own choice on the activation screen.
        boolean existingInstall = !prefs.getAll().isEmpty();
        if(existingInstall) {
            boolean fse = prefs.getBoolean(FullscreenActivity.PREF_FSE_SCREEN, false);
            OperatingMode.set(prefs, fse ? OperatingMode.FSE : OperatingMode.NORMAL);
            Log.i("migrate", "operating mode PINNED to " + (fse ? "FSE" : "NORMAL"));
        }
        prefs.edit().putBoolean(MIGRATED, true).apply();
    }

    private void migrateSettings() {
        SharedPreferences sharedPref = getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        migrateOperatingMode(sharedPref);
        if(sharedPref.contains("color-red-analog") && sharedPref.contains("color-green-analog") && sharedPref.contains("color-blue-analog")) {
            SharedPreferences.Editor editor = sharedPref.edit();
            int oldColor = Color.argb(255, sharedPref.getInt("color-red-analog", 255), sharedPref.getInt("color-green-analog", 255), sharedPref.getInt("color-blue-analog", 255));
            editor.putInt("color-analog-face", oldColor);
            editor.putInt("color-analog-hours", oldColor);
            editor.putInt("color-analog-minutes", oldColor);
            editor.putInt("color-analog-seconds", oldColor);
            editor.remove("color-red-analog");
            editor.remove("color-green-analog");
            editor.remove("color-blue-analog");
            editor.apply();
            Log.i("migrate", "color-analog-* MIGRATED");
        }
        if(sharedPref.contains("color-red") && sharedPref.contains("color-green") && sharedPref.contains("color-blue")) {
            SharedPreferences.Editor editor = sharedPref.edit();
            int oldColor = Color.argb(255, sharedPref.getInt("color-red", 255), sharedPref.getInt("color-green", 255), sharedPref.getInt("color-blue", 255));
            editor.putInt("color-digital", oldColor);
            editor.remove("color-red");
            editor.remove("color-green");
            editor.remove("color-blue");
            editor.apply();
            Log.i("migrate", "color-* MIGRATED");
        }
        if(sharedPref.contains("color-red-back") && sharedPref.contains("color-green-back") && sharedPref.contains("color-blue-back")) {
            SharedPreferences.Editor editor = sharedPref.edit();
            int oldColor = Color.argb(255, sharedPref.getInt("color-red-back", 255), sharedPref.getInt("color-green-back", 255), sharedPref.getInt("color-blue-back", 255));
            editor.putInt("color-back", oldColor);
            editor.remove("color-red-back");
            editor.remove("color-green-back");
            editor.remove("color-blue-back");
            editor.apply();
            Log.i("migrate", "color-back-* MIGRATED");
        }
        if(sharedPref.contains("own-color-analog")) {
            SharedPreferences.Editor editor = sharedPref.edit();
            boolean oldValue = sharedPref.getBoolean("own-color-analog", true);
            editor.putBoolean("own-color-analog-hours", oldValue);
            editor.putBoolean("own-color-analog-minutes", oldValue);
            editor.remove("own-color-analog");
            editor.apply();
            Log.i("migrate", "own-color-analog-* MIGRATED");
        }
        if(sharedPref.contains("own-image-back")) {
            if(!sharedPref.getBoolean("own-image-back", false)) {
                // delete image if it was disabled in old version
                StorageControl sc = new StorageControl(this);
                if(sc.existsImage(StorageControl.FILENAME_BACKGROUND_IMAGE)) {
                    sc.removeImage(StorageControl.FILENAME_BACKGROUND_IMAGE);
                    Log.i("migrate", "own-image-back DELETED OLD IMAGE");
                }
            }
            SharedPreferences.Editor editor = sharedPref.edit();
            editor.putBoolean("back-stretch", true); // keep the old stretch behavior on existing installations
            editor.remove("own-image-back");
            editor.apply();
            Log.i("migrate", "own-image-back MIGRATED");
        }
        if(sharedPref.contains("color-digital")) {
            SharedPreferences.Editor editor = sharedPref.edit();
            int oldValue = sharedPref.getInt("color-digital", 0xffffffff);
            editor.putInt("color-digital-clock", oldValue);
            editor.putInt("color-digital-date", oldValue);
            editor.remove("color-digital");
            editor.apply();
            Log.i("migrate", "color-digital MIGRATED");
        }
    }

}
