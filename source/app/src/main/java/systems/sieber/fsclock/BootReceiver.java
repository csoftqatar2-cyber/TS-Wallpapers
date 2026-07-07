package systems.sieber.fsclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;

/**
 * Starts the clock automatically when the device (car head unit) boots.
 * Only active when the user enabled the "auto start" option in the settings
 * (pref "auto-start-on-boot", default: off).
 */
public class BootReceiver extends BroadcastReceiver {

    static final String PREF_AUTO_START = "auto-start-on-boot";

    // delay the launch a bit so the head unit finishes booting its own launcher first
    private static final long START_DELAY_MS = 3000;

    @Override
    public void onReceive(final Context context, Intent intent) {
        String action = intent.getAction();
        if(action == null) return;
        if(!action.equals(Intent.ACTION_BOOT_COMPLETED)
                && !action.equals("android.intent.action.QUICKBOOT_POWERON")
                && !action.equals("com.htc.intent.action.QUICKBOOT_POWERON")) {
            return;
        }

        if(!context.getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTO_START, false)) {
            return;
        }

        final PendingResult pendingResult = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent i = new Intent(context, FullscreenActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(i);
                } catch(Exception ignored) {
                    // starting from background may be blocked on some devices
                } finally {
                    pendingResult.finish();
                }
            }
        }, START_DELAY_MS);
    }
}
