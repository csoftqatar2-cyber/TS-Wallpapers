package systems.sieber.fsclock;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/**
 * Shows or hides this app's icon in the car's app list by toggling its own
 * LauncherIcon activity-alias. The app keeps working: the THABTHABA dashboard starts
 * FullscreenActivity by explicit component, which the alias does not affect.
 *
 * Shell cannot disable another app's components, so the app does it for itself.
 * Deliberately minimal (workspace rule: no exported command receivers): it reads one
 * boolean extra, touches only its own alias, and is protected in the manifest by
 * android.permission.DUMP, which only shell/system hold.
 */
public class IconToggleReceiver extends BroadcastReceiver {

    static final String ACTION = "com.thabthaba.action.SET_LAUNCHER_ICON";
    static final String ALIAS = "systems.sieber.fsclock.LauncherIcon";

    @Override
    public void onReceive(Context ctx, Intent intent) {
        if(intent == null || !ACTION.equals(intent.getAction())) return;
        boolean visible = intent.getBooleanExtra("visible", true);
        ctx.getPackageManager().setComponentEnabledSetting(
                new ComponentName(ctx, ALIAS),
                visible ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                        : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }
}
