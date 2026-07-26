package systems.sieber.fsclock;

import android.content.SharedPreferences;

/**
 * How much the Lynk & Co picker UI is enlarged. Lynk & Co head units render the picker on a
 * small, dense passenger panel, so the operator can dial the whole interface up or down with a
 * slider in Settings. Stored as a whole percent (150 = 1.5x) so a plain SeekBar drives it.
 *
 * Only Lynk & Co reads this — Leopard, GWM and Normal are never scaled.
 */
class LynkcoScale {

    /** Percent bounds and default. 150% (1.5x) is the out-of-the-box size. */
    static final int MIN = 100;   // no enlargement
    static final int MAX = 300;   // 3x, the largest that still fits the panel
    static final int DEFAULT = 150;

    private static final String PREF = "lynkco-ui-scale";

    static int percent(SharedPreferences prefs) {
        int v = prefs.getInt(PREF, DEFAULT);
        return Math.max(MIN, Math.min(MAX, v));
    }

    static void save(SharedPreferences prefs, int percent) {
        prefs.edit().putInt(PREF, Math.max(MIN, Math.min(MAX, percent))).apply();
    }

    /** The multiplier the density is scaled by, e.g. 1.5f for 150%. */
    static float factor(SharedPreferences prefs) {
        return percent(prefs) / 100f;
    }
}
