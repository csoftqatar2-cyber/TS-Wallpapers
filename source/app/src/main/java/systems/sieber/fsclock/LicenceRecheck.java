package systems.sieber.fsclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.SystemClock;

/**
 * The fleet-wide "ask the server again" nudge (contract of 2026-09-03, all six apps).
 *
 * When one of our programs learns that this car was blocked, or that a block was lifted, it
 * sends {@link #ACTION} explicitly (setPackage) to each of the other five so they re-check
 * within seconds instead of at their next launch. THIS IS NOT A COMMAND CHANNEL: the
 * broadcast carries no data, every extra is ignored, and the only thing a receiver ever does
 * is run the same server check it already runs on its own — so a forged broadcast can at
 * worst make the app ask the server once a minute.
 *
 * Rate limit: one server round-trip per {@link #MIN_INTERVAL_MS}, whoever asked. Keeps a
 * hostile co-installed app from turning six programs into a flood against the backend.
 *
 * The check itself is {@link WallpaperRepo#sync}: the server's answer writes the activation
 * flag (only the "inactive" sentinel clears it, and the golden rule that a car in the field
 * never loses its licence on an error is unchanged), and the screens react on their next
 * frame or, for the picker, through the dynamic receiver it registers while it is up.
 */
public final class LicenceRecheck {

    /** Shared with every sibling app. Frozen. */
    public static final String ACTION = "com.thabthaba.action.RECHECK_LICENCE";

    /** The other five programs that share this car's licence. */
    static final String[] SIBLINGS = {
            "com.thabthaba.store",
            "com.csoft.backbutton",
            "com.thabthaba.tslink",
            "com.thabthaba.controller",
            "com.codex.clusterlauncher",
    };

    static final long MIN_INTERVAL_MS = 60_000L;
    /** Periodic self-check while a screen is up: 10 minutes, jittered ±2 (leader's rule). */
    static final long PERIOD_MS = 10 * 60_000L;
    static final long JITTER_MS = 2 * 60_000L;

    private static final String PREF_LAST_MS = "device-recheck-last-ms";
    private static volatile long sLastElapsed = 0L;

    private LicenceRecheck() {}

    /** Next periodic delay: PERIOD ± JITTER. */
    static long nextDelayMs() {
        long spread = (long) ((Math.random() * 2 - 1) * JITTER_MS);
        return PERIOD_MS + spread;
    }

    /**
     * True if a re-check may run now, and marks the time. False = too soon, ignore.
     * Elapsed-realtime in memory plus wall-clock in prefs, so a process restart within the
     * minute cannot be used to bypass the limit.
     */
    static synchronized boolean claim(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        if (sLastElapsed != 0 && now - sLastElapsed < MIN_INTERVAL_MS) return false;
        SharedPreferences sp = ctx.getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        long wall = System.currentTimeMillis();
        long last = sp.getLong(PREF_LAST_MS, 0L);
        if (last != 0 && wall - last < MIN_INTERVAL_MS && wall >= last) return false;
        sLastElapsed = now;
        sp.edit().putLong(PREF_LAST_MS, wall).apply();
        return true;
    }

    /** Run the server check now if the rate limit allows. Safe from any thread. */
    static void recheckNow(Context ctx, String why) {
        Context app = ctx.getApplicationContext() == null ? ctx : ctx.getApplicationContext();
        if (!claim(app)) return;
        CrashReporter.breadcrumb("licence recheck: " + why);
        try {
            new WallpaperRepo(app).sync(null);
        } catch (Throwable ignored) {
            // A check that could not start is a check that happens at the next trigger.
        }
    }

    /** Tell the other five programs to ask the server. Explicit, no extras. Never throws. */
    static void notifySiblings(Context ctx) {
        for (String pkg : SIBLINGS) {
            try {
                Intent i = new Intent(ACTION);
                i.setPackage(pkg);
                i.addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES);
                ctx.sendBroadcast(i);
            } catch (Throwable ignored) {
            }
        }
    }

    /** Manifest receiver: a sibling asked us to re-check. Extras are ignored on purpose. */
    public static class Receiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !ACTION.equals(intent.getAction())) return;
            recheckNow(context, "sibling broadcast");
        }
    }
}
