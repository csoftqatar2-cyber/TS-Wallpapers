package systems.sieber.fsclock;

import android.app.WallpaperManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

/**
 * Which product this install is: Normal, FSE, Leopard, GWM, Lynkco, Jetour, or Denza. The seven
 * are mutually exclusive.
 *
 * - NORMAL  : the app owns the screen and draws wallpaper + clock itself.
 * - FSE     : same, but the window is pinned to 1920x720 for ultra-wide head units.
 * - LEOPARD : the app draws nothing. It hands the chosen file to Android's wallpaper system
 *             and gets out of the way — no clock, no weather, no slideshow. See §8 of the
 *             Leopard brief: this is not a setting, it is a different product.
 * - GWM     : screen-wise IDENTICAL to NORMAL (app draws wallpaper + clock). The only thing the
 *             mode carries is the "GWM images management" section: it mirrors the 'gwm_split'
 *             cloud channel into an external folder that a SEPARATE app on GWM head units reads.
 *             The mode IS the on-switch for that mirror (see FolderMirror.isEnabled).
 * - LYNKCO  : same idea as LEOPARD (a hand-off product: the app draws nothing and is just a
 *             picker), but for Lynk &amp; Co / Flyme (ECARX / Geely) head units, whose live
 *             wallpaper is owned by a proprietary engine that ignores Android's WallpaperManager.
 *             Instead of setBitmap, the chosen file is handed to the head unit's own theme app
 *             (com.flyme.auto.customize) via its exported "wallpaper.setting" intent — the one
 *             officially-supported way to change the wallpaper there. See {@link LynkcoApplier}.
 *             Needs no root and no privileged permissions.
 * - JETOUR  : exactly NORMAL on screen, exactly GWM in structure — the same slideshow and clock
 *             plus one folder mirror, this one pointed at /sdcard/Pictures/G700 for the Jetour
 *             G700 head unit's own gallery app. Two cars, one mechanism: see {@link FolderMirror}.
 * - DENZA   : today exactly LEOPARD (WallpaperManager hand-off) under its own name — see the
 *             note on the constant.
 *
 * FSE stays on its original boolean key so the six places that already read it, and every
 * device already in the field, keep working untouched. In Leopard/GWM/Jetour that flag reads
 * false, which is correct: there is no ultra-wide window to pin.
 */
class OperatingMode {

    static final int NORMAL = 0;
    static final int FSE = 1;
    static final int LEOPARD = 2;
    static final int GWM = 3;
    static final int LYNKCO = 4;
    static final int JETOUR = 5;
    /**
     * Denza was carved out of LEOPARD (the radio used to read "Leopard \ Denza"). Today it is
     * the SAME product — Android's WallpaperManager hand-off, the same picker, the same support
     * gate — under its own name, so the manager can tell the two cars apart and so the two can
     * diverge later without another migration. Every behavioural check asks
     * {@link #isLeopardFamily} / {@link #isHandoff}; only labels and the wire value differ.
     */
    static final int DENZA = 6;

    private static final String PREF_LEOPARD = "leopard-mode";
    private static final String PREF_GWM = "gwm-mode";
    private static final String PREF_LYNKCO = "lynkco-mode";
    private static final String PREF_JETOUR = "jetour-mode";
    private static final String PREF_DENZA = "denza-mode";

    /**
     * Has a human explicitly chosen this car's mode on a build that knows all six modes?
     *
     * The flag exists because the manager needs each car's mode to be the TRUTH, not a guess.
     * Cars in the field were migrated onto NORMAL/FSE by {@code migrateOperatingMode} — a
     * reasonable default, never a decision, and a car quietly reporting "normal" when it is
     * really a GWM or Lynk &amp; Co unit is worse than one reporting nothing. So an install that
     * updates into this version is held on {@link ModeConfirmActivity} until the technician
     * confirms the mode once; from then on the flag is set and the gate never appears again.
     */
    private static final String PREF_CONFIRMED = "operating-mode-confirmed";

    /** True once the mode was picked by a person (activation, settings, or the confirm gate). */
    static boolean isConfirmed(SharedPreferences prefs) {
        return prefs.getBoolean(PREF_CONFIRMED, false);
    }

    /**
     * Record that the mode standing in prefs was chosen deliberately. Called from the three
     * places a human picks one — never from {@link #set}, because migrations and internal
     * corrections write a mode too and those are exactly what the gate is meant to catch.
     */
    static void setConfirmed(SharedPreferences prefs) {
        prefs.edit().putBoolean(PREF_CONFIRMED, true).apply();
    }

    /** The head unit's own theme app; the only supported way to set the wallpaper in Lynkco. */
    static final String LYNKCO_CUSTOMIZE_PACKAGE = "com.flyme.auto.customize";

    static int get(SharedPreferences prefs) {
        // Ordered: an existing install carrying "leopard-mode" must keep resolving to LEOPARD,
        // so it is checked before DENZA (which no fielded car has yet).
        if(prefs.getBoolean(PREF_LEOPARD, false)) return LEOPARD;
        if(prefs.getBoolean(PREF_DENZA, false)) return DENZA;
        if(prefs.getBoolean(PREF_GWM, false)) return GWM;
        if(prefs.getBoolean(PREF_LYNKCO, false)) return LYNKCO;
        if(prefs.getBoolean(PREF_JETOUR, false)) return JETOUR;
        if(prefs.getBoolean(FullscreenActivity.PREF_FSE_SCREEN, false)) return FSE;
        return NORMAL;
    }

    /** Writes all six flags together, so no two modes can ever both be on. */
    static void set(SharedPreferences prefs, int mode) {
        prefs.edit()
                .putBoolean(PREF_LEOPARD, mode == LEOPARD)
                .putBoolean(PREF_DENZA, mode == DENZA)
                .putBoolean(PREF_GWM, mode == GWM)
                .putBoolean(PREF_LYNKCO, mode == LYNKCO)
                .putBoolean(PREF_JETOUR, mode == JETOUR)
                .putBoolean(FullscreenActivity.PREF_FSE_SCREEN, mode == FSE)
                .apply();
    }

    static boolean isLeopard(SharedPreferences prefs) {
        return get(prefs) == LEOPARD;
    }

    static boolean isDenza(SharedPreferences prefs) {
        return get(prefs) == DENZA;
    }

    /**
     * Leopard and Denza: the WallpaperManager hand-off family. This is what behaviour asks —
     * {@link #isLeopard}/{@link #isDenza} are for labels only, so a Denza car walks the exact
     * code path a Leopard car does until somebody deliberately makes them differ.
     */
    static boolean isLeopardFamily(SharedPreferences prefs) {
        int m = get(prefs);
        return m == LEOPARD || m == DENZA;
    }

    static boolean isGwm(SharedPreferences prefs) {
        return get(prefs) == GWM;
    }

    static boolean isLynkco(SharedPreferences prefs) {
        return get(prefs) == LYNKCO;
    }

    static boolean isJetour(SharedPreferences prefs) {
        return get(prefs) == JETOUR;
    }

    /**
     * The modes that draw our own screen — slideshow, clock, download gate, the lot. GWM and
     * Jetour are on this list because on screen they ARE Others; all they add is a folder mirror
     * running behind it. Everything that used to ask "is this NORMAL?" about the screen should
     * ask this instead, or the two folder-mirror modes silently lose a piece of Others.
     */
    static boolean isOthersLike(SharedPreferences prefs) {
        int m = get(prefs);
        return m == NORMAL || m == GWM || m == JETOUR;
    }

    /**
     * Leopard and Lynkco are the two "hand-off" products: the app draws nothing and is only a
     * picker that passes the chosen file to the platform's own wallpaper system. Everything that
     * hides the clock rail and opens straight into the picker is shared between them; only the
     * apply step differs (Android WallpaperManager vs the Flyme theme intent).
     */
    static boolean isHandoff(SharedPreferences prefs) {
        return isHandoffMode(get(prefs));
    }

    /** {@link #isHandoff} for a mode value that is not in prefs yet (a picker's selection). */
    static boolean isHandoffMode(int m) {
        return m == LEOPARD || m == DENZA || m == LYNKCO;
    }

    /** Stable wire value the device reports to the backend / manager. Never rename these. */
    static String wire(SharedPreferences prefs) {
        switch(get(prefs)) {
            case LEOPARD: return "leopard";
            case DENZA:   return "denza";
            case GWM:     return "gwm";
            case LYNKCO:  return "lynkco";
            case JETOUR:  return "jetour";
            case FSE:     return "fse";
            default:      return "normal";
        }
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

    /** Denza rides on the same WallpaperManager hand-off as Leopard, so it has the same gate. */
    static boolean isDenzaSupported(Context ctx) {
        return isSupported(ctx);
    }

    /**
     * Lynkco only makes sense on a head unit that actually ships the Flyme theme app we hand the
     * wallpaper to. On anything else the mode would be a dead switch, so the option is offered
     * only where {@link #LYNKCO_CUSTOMIZE_PACKAGE} is installed and can receive our intent.
     */
    static boolean isLynkcoSupported(Context ctx) {
        try {
            PackageManager pm = ctx.getPackageManager();
            // Primary check: can the theme app actually receive our wallpaper intent? This is what
            // support truly means, and it resolves even on units where the receiver lives under a
            // component the bare package name does not (the package is declared in <queries>).
            Intent probe = new Intent(LynkcoApplier.ACTION_SET).setPackage(LYNKCO_CUSTOMIZE_PACKAGE);
            if(!pm.queryIntentActivities(probe, 0).isEmpty()) return true;
            // Fallback: the package is simply installed and visible.
            pm.getPackageInfo(LYNKCO_CUSTOMIZE_PACKAGE, 0);
            return true;
        } catch(Throwable t) {
            return false;
        }
    }
}
