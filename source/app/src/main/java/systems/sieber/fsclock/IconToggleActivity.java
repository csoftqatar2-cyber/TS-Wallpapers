package systems.sieber.fsclock;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;

/**
 * The same job as {@link IconToggleReceiver}, reached by an activity start instead of a broadcast.
 *
 * BYD's self-start ban silently drops a broadcast that would have to start this app's process,
 * while `am broadcast` still reports success. So when the app is not running, the THABTHABA
 * dashboard's shell starts this activity instead, which the ban lets through.
 *
 * No UI: Theme.NoDisplay, and it finishes in onCreate. It does not go through FullscreenActivity
 * or any of its gates. Guarded by DUMP like the receiver, so only shell/system can start it.
 * It reads one boolean and touches nothing but this app's own LauncherIcon alias.
 */
public class IconToggleActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Intent intent = getIntent();
        if(intent != null && IconToggleReceiver.ACTION.equals(intent.getAction())) {
            boolean visible = intent.getBooleanExtra("visible", true);
            getPackageManager().setComponentEnabledSetting(
                    new ComponentName(this, IconToggleReceiver.ALIAS),
                    visible ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                            : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP);
        }
        finish();
    }
}
