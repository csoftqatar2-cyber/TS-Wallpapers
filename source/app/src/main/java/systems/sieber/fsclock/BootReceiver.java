package systems.sieber.fsclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.Log;

/**
 * Starts the clock automatically when the car comes on.
 * Only active when the user enabled the "auto start" option in the settings
 * (pref "auto-start-on-boot", default: off).
 *
 * Two things made this fire only sometimes, and both are handled here:
 *
 *  1) A car head unit usually SLEEPS when the car is switched off and WAKES when it comes back
 *     on. A wake is not a boot, so BOOT_COMPLETED never arrives — it only arrives after a long
 *     park that cut the power, or a real reboot. Listening to boot alone therefore works after
 *     an overnight park and does nothing after a five minute stop, which is exactly what
 *     "sometimes it opens" looks like. So every plausible come-back-to-life signal is accepted
 *     here, and the receiver is idempotent so several of them firing together is harmless.
 *
 *  2) The head unit's own launcher frequently finishes starting AFTER we do and takes the
 *     foreground. A single attempt at a fixed delay won that race only when the boot happened
 *     to be slow. We now attempt early — FSE means the car is supposed to come up on this
 *     screen, so speed is the feature — and then keep re-asserting for a few seconds.
 */
public class BootReceiver extends BroadcastReceiver {

    static final String PREF_AUTO_START = "auto-start-on-boot";

    private static final String TAG = "BootReceiver";

    /**
     * When to try, in ms after the signal. The first is deliberately early so the app is what
     * the driver sees rather than a launcher that flashes first; the rest exist to win back the
     * foreground if the launcher lands after us. Must stay inside the broadcast timeout.
     */
    private static final long[] ATTEMPTS_MS = { 700, 2500, 5000, 8000 };

    /** Signals that unambiguously mean "the car just came on". */
    static boolean isStartTrigger(String action) {
        if(action == null) return false;
        return action.equals(Intent.ACTION_BOOT_COMPLETED)
                || action.equals("android.intent.action.QUICKBOOT_POWERON")
                || action.equals("com.htc.intent.action.QUICKBOOT_POWERON")
                || action.equals(Intent.ACTION_POWER_CONNECTED)
                || action.equals(Intent.ACTION_MY_PACKAGE_REPLACED)
                || action.equals(Intent.ACTION_USER_PRESENT);
    }

    /**
     * Two triggers apart, we would happily open over whatever the driver is doing.
     *
     * USER_PRESENT is the one that catches a woken (not booted) head unit, which is the whole
     * point — but it also fires on every ordinary unlock, mid-drive, with maps on screen. So it
     * is honoured only in FSE, where the app owning the screen IS the mode, and never in Normal,
     * where a car that has been working for months must not suddenly start grabbing focus.
     */
    private static boolean isAllowedHere(Context context, String action, SharedPreferences prefs) {
        if(!Intent.ACTION_USER_PRESENT.equals(action)) return true;
        return OperatingMode.get(prefs) == OperatingMode.FSE;
    }

    /** Don't relaunch again straight away: several of these signals arrive together on one wake. */
    private static final String PREF_LAST_AUTO_START = "auto-start-last-ms";
    private static final long MIN_INTERVAL_MS = 2 * 60 * 1000L;

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        // Logged unconditionally: when this misfires on a car, the first thing anyone needs to
        // know is which signal the head unit actually sent, and previously nothing was recorded.
        Log.i(TAG, "received: " + action);
        if(!isStartTrigger(action)) return;

        maybeStart(context, TAG + "/" + action, action);
    }

    /**
     * Launch the clock if the user asked for it. Safe to call from any of the triggers, and
     * safe to call twice — the activity is started SINGLE_TOP, so an extra call while it is
     * already on screen does not restart it.
     */
    static void maybeStart(final Context context, String source, String action) {
        final SharedPreferences prefs;
        try {
            prefs = context.getSharedPreferences(
                    BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        } catch(Throwable t) {
            // Reading settings this early can fail on a device that is still unlocking. Never
            // let that take the whole boot broadcast down with it.
            Log.e(TAG, "cannot read settings for " + source, t);
            return;
        }
        if(!prefs.getBoolean(PREF_AUTO_START, false)) {
            Log.i(TAG, "auto-start is off, ignoring " + source);
            return;
        }
        if(!isAllowedHere(context, action, prefs)) {
            Log.i(TAG, "ignoring " + source + ": only FSE opens itself on unlock");
            return;
        }
        long now = System.currentTimeMillis();
        long last = prefs.getLong(PREF_LAST_AUTO_START, 0L);
        // now < last guards a head unit whose clock jumps backwards once GPS time arrives —
        // otherwise a stale future timestamp would suppress auto-start until it passed.
        if(last != 0L && now >= last && now - last < MIN_INTERVAL_MS) {
            Log.i(TAG, "ignoring " + source + ": started " + ((now - last) / 1000) + "s ago");
            return;
        }
        prefs.edit().putLong(PREF_LAST_AUTO_START, now).apply();
        // Not a reason to give up — some head units do not enforce it — but it IS the usual
        // reason a correctly configured car still comes up on its launcher, and until now it
        // failed with nothing written down anywhere.
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(context)) {
            Log.w(TAG, "overlay permission missing - Android will most likely block this launch");
        }

        Log.i(TAG, "auto-start armed by " + source);
        final Handler handler = new Handler(Looper.getMainLooper());
        for(int i = 0; i < ATTEMPTS_MS.length; i++) {
            final int attempt = i + 1;
            handler.postDelayed(new Runnable() {
                @Override
                public void run() { launch(context, attempt); }
            }, ATTEMPTS_MS[i]);
        }
    }

    private static void launch(Context context, int attempt) {
        try {
            Intent i = new Intent(context, FullscreenActivity.class);
            // SINGLE_TOP so the later attempts cost nothing when the first one already worked:
            // the activity is not recreated, and whatever the driver is doing is not interrupted.
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            context.startActivity(i);
            Log.i(TAG, "launch attempt " + attempt + " sent");
        } catch(Exception e) {
            // Background activity starts are blocked on Android 10+ without the overlay
            // permission. This used to be swallowed in silence, which is why the feature could
            // fail on a car with no trace of having tried.
            Log.e(TAG, "launch attempt " + attempt + " failed", e);
        }
    }
}
