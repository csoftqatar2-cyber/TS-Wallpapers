package systems.sieber.fsclock;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/**
 * Which product this install is: Normal, FSE, or Leopard. The three are mutually exclusive.
 *
 * - NORMAL  : the app owns the screen and draws wallpaper + clock itself.
 * - FSE     : same, but the window is pinned to 1920x720 for ultra-wide head units.
 * - LEOPARD : the app draws nothing. It hands the chosen file to Android's wallpaper system
 *             and gets out of the way — no clock, no weather, no slideshow. See §8 of the
 *             Leopard brief: this is not a setting, it is a different product.
 *
 * FSE stays on its original boolean key so the six places that already read it, and every
 * device already in the field, keep working untouched. In Leopard that flag reads false, which
 * is correct: there is no app window to pin.
 */
class OperatingMode {

    static final int NORMAL = 0;
    static final int FSE = 1;
    static final int LEOPARD = 2;

    private static final String PREF_LEOPARD = "leopard-mode";

    static int get(SharedPreferences prefs) {
        if(prefs.getBoolean(PREF_LEOPARD, false)) return LEOPARD;
        if(prefs.getBoolean(FullscreenActivity.PREF_FSE_SCREEN, false)) return FSE;
        return NORMAL;
    }

    /** Writes both flags together, so the two can never both be on. */
    static void set(SharedPreferences prefs, int mode) {
        prefs.edit()
                .putBoolean(PREF_LEOPARD, mode == LEOPARD)
                .putBoolean(FullscreenActivity.PREF_FSE_SCREEN, mode == FSE)
                .apply();
    }

    static boolean isLeopard(SharedPreferences prefs) {
        return get(prefs) == LEOPARD;
    }

    /**
     * Not every head unit ships the live wallpaper picker — some cheap ROMs leave it out.
     * Leopard cannot work there, so the option must be visibly unavailable rather than a
     * crash or a silent no-op.
     */
    static boolean isSupported(Context ctx) {
        try {
            if(!ctx.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LIVE_WALLPAPER)) return false;
            // The picker intent is what we would have to launch for video; if nothing can
            // handle it, Leopard is only half a product on this device.
            return WallpaperManager.getInstance(ctx) != null;
        } catch(Throwable t) {
            return false;
        }
    }
}
