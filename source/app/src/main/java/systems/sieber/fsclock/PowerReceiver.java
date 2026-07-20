package systems.sieber.fsclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * On a car head unit, turning the ignition on often fires ACTION_POWER_CONNECTED instead of a
 * full boot — the unit was asleep, not off. That makes this the trigger that actually carries
 * the common case.
 *
 * The launch itself lives in {@link BootReceiver#maybeStart} so both receivers behave
 * identically: same retries, same logging, same overlay-permission warning. This class used to
 * carry its own single-shot copy of that logic, which meant the wake path silently lacked every
 * fix made to the boot path.
 */
public class PowerReceiver extends BroadcastReceiver {

    private static final String TAG = "PowerReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        Log.i(TAG, "received: " + action);
        if(!Intent.ACTION_POWER_CONNECTED.equals(action)) return;
        BootReceiver.maybeStart(context, TAG + "/" + action, action);
    }
}
