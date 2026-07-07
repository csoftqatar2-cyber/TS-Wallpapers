package systems.sieber.fsclock;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class PowerReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if(intent.getAction() != null && intent.getAction().equals(Intent.ACTION_POWER_CONNECTED)) {
            // only when the user enabled auto-start in the settings (default: off);
            // on car head units, turning the ignition on fires this instead of a full boot
            if(!context.getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE)
                    .getBoolean(BootReceiver.PREF_AUTO_START, false)) {
                return;
            }
            try {
                Intent i = new Intent(context, FullscreenActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(i);
            } catch(Exception ignored) { }
        }
    }

}
