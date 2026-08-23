package systems.sieber.fsclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

/**
 * The one place "Display over other apps" is asked for.
 *
 * Android 10+ refuses to let an app start an activity from a broadcast receiver without this
 * grant. That is the whole mechanism behind FSE: the car boots, BootReceiver fires, and the app
 * puts itself on the screen. Without the grant the receiver runs, the startActivity is dropped
 * by the system with nothing said, and the car comes up on its launcher — a mode that reads as
 * simply not working, with no error anywhere to explain it.
 *
 * It used to be asked for only where the auto-start switch was toggled, which left the two
 * routes that actually pick FSE for a new car — the activation overlay, and the one-time mode
 * gate — granting nothing at all. A technician would choose FSE, finish the job, and the fault
 * would surface the next morning at the customer's first cold start. So the ask now rides on
 * "FSE was chosen", not on "a switch moved".
 *
 * {@code after} runs whichever way the dialog ends, including when it is never shown: callers
 * like {@link ModeConfirmActivity} finish themselves next, and a screen that closed underneath
 * its own dialog would have asked for nothing.
 */
final class OverlayPermission {

    private OverlayPermission() { }

    /** True when the grant is held, or when this Android is old enough not to need it. */
    static boolean isGranted(Context ctx) {
        if(Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return true;
        try {
            return Settings.canDrawOverlays(ctx);
        } catch(Throwable t) {
            // A head unit with no overlay concept at all: treat it as granted rather than
            // nagging for a permission its ROM cannot represent.
            return true;
        }
    }

    /**
     * Ask for the grant if it is missing, then run {@code after}.
     *
     * @param after may be null. Runs on the UI thread, after the dialog is dismissed — or
     *              immediately when there is nothing to ask for.
     */
    static void request(final Activity activity, final Runnable after) {
        if(activity == null || activity.isFinishing()) {
            if(after != null) after.run();
            return;
        }
        if(isGranted(activity)) {
            if(after != null) after.run();
            return;
        }
        AlertDialog dialog = DialogButtons.apply(new AlertDialog.Builder(activity)
                .setTitle(R.string.auto_start_overlay_title)
                .setMessage(R.string.auto_start_overlay_message)
                .setPositiveButton(R.string.ok, (d, w) -> open(activity))
                .setNegativeButton(R.string.update_cancel, null)
                .setCancelable(true)
                .create());
        // One listener for every way out — OK, Cancel, back, or a tap outside — so the caller's
        // continuation cannot be lost down whichever path the user happens to take.
        if(after != null) dialog.setOnDismissListener(d -> after.run());
        dialog.show();
    }

    /** Open the system screen where the grant is given. */
    private static void open(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + activity.getPackageName())));
        } catch(ActivityNotFoundException e) {
            // Some head units ship no overlay settings screen at all. Say so: on Android 10+
            // auto-start genuinely will not work there, and silence would leave a dead setting.
            Toast.makeText(activity, R.string.auto_start_overlay_unavailable, Toast.LENGTH_LONG).show();
        }
    }
}
