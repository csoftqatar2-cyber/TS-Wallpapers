package systems.sieber.fsclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;

/**
 * Scales the text of the SETTINGS UI only.
 *
 * Head units ship with wildly different panel sizes at the same resolution — the same 15sp row
 * that is comfortable on a 10" screen is unreadable on a small 7" one, and the installer cannot
 * change the ROM's font scale. So the app carries its own.
 *
 * This deliberately does NOT go into LocaleHelper.wrap(): FullscreenActivity wraps its context
 * too, and the clock face must keep its own sizing — the user picks that separately and a global
 * scale would fight it.
 *
 * Every text size in the settings tree is declared in sp, so a single Configuration.fontScale
 * override covers the whole screen, including the sizes hardcoded per-view in the layout. That is
 * why this is a context wrapper and not a walk over the view tree.
 */
public class TextScaleHelper {

    private static final String PREF_KEY = "settings-text-scale";

    /** Percent, not a multiplier — it is what the slider and the label both speak. */
    public static final int SCALE_MIN = 100;
    public static final int SCALE_MAX = 200;
    public static final int SCALE_DEFAULT = 100;

    /** Slider granularity. 5% steps: fine enough to tune, coarse enough to hit with a finger. */
    public static final int SCALE_STEP = 5;

    public static int saved(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(
                BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        return clamp(prefs.getInt(PREF_KEY, SCALE_DEFAULT));
    }

    public static void save(Context context, int percent) {
        context.getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE)
                .edit().putInt(PREF_KEY, clamp(percent)).apply();
    }

    public static int clamp(int percent) {
        if(percent < SCALE_MIN) return SCALE_MIN;
        if(percent > SCALE_MAX) return SCALE_MAX;
        return percent;
    }

    /** Snap a raw slider position to the nearest step, so the value is always a round number. */
    public static int snap(int percent) {
        int stepped = Math.round((float) percent / SCALE_STEP) * SCALE_STEP;
        return clamp(stepped);
    }

    /**
     * Wrap a base context so its sp sizes are scaled. Call from Activity.attachBaseContext(),
     * outside LocaleHelper.wrap() — that one returns early when the language is the system
     * default, so it cannot be relied on to run.
     */
    public static Context wrap(Context context) {
        int percent = saved(context);
        if(percent == SCALE_DEFAULT) return context;

        Configuration config = new Configuration(context.getResources().getConfiguration());
        // Multiply rather than assign: the head unit may already carry its own accessibility
        // scale, and stomping it would shrink text for a user who deliberately enlarged it.
        config.fontScale = config.fontScale * (percent / 100f);
        // Carry the density across explicitly. createConfigurationContext has a long history of
        // resetting densityDpi to the system default on older releases, and this app still runs
        // on API 21 head units — losing it would rescale every dp in the settings tree, not just
        // the text we meant to touch.
        config.densityDpi = context.getResources().getDisplayMetrics().densityDpi;
        return context.createConfigurationContext(config);
    }
}
