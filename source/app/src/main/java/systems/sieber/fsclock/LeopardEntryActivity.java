package systems.sieber.fsclock;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;

/**
 * The door the THABTHABA dashboard opens this app through — and nothing else.
 *
 * <h3>Why it exists</h3>
 *
 * <p>The dashboard hosts a sibling on a {@code VirtualDisplay} of its own and starts it there
 * from the shell with {@code am start --display N}. That launch is accepted and lands exactly
 * where it was asked to. The app's OWN next launch is not: measured on a real car, this platform's
 * patched {@code ActivityTaskManager} rewrites the options of an app-initiated start and puts it
 * on the default display area —
 *
 * <pre>
 *   changeOptionsIfNeed mPreferredTaskDisplayArea=DefaultTaskDisplayArea@74312741
 *   activity pkgname=store.thabthaba.clock launch displayId=0
 * </pre>
 *
 * <p>— 150ms after the same package landed correctly on displayId=30. So the app arrived where it
 * belonged, then handed off to its own next screen and that screen went to the main panel, full
 * size, in front of the driver, until the dashboard carried it back. THAT is the flash. Naming the
 * display on the second start does not help: {@code setLaunchDisplayId} is refused for an ordinary
 * app on a display it does not own, and this app does not own that display.
 *
 * <p>The proof is the two siblings that never flash: TS Link and TS Roaming Keeper each open one
 * activity and hand off to nothing, and each shows a single {@code launch displayId=30} and no
 * second line.
 *
 * <p>So this door removes the second launch instead of trying to aim it. It IS the picker — the
 * screen the handoff was going to reach — and it draws straight onto the display it was started
 * on. Its own {@code taskAffinity} (see the manifest) keeps it out of the task the customer's own
 * launcher icon builds on the main screen.
 *
 * <h3>The gate still decides</h3>
 *
 * <p>A second way in is a second place for the licence check to be forgotten, so this asks
 * {@link FullscreenActivity#routeFor} — the same method {@link FullscreenActivity} itself asks,
 * the only copy of that decision. Reaching the picker here therefore requires exactly what it
 * requires there: the mode question answered, the car activated, the library downloaded.
 *
 * <p>Anything other than the picker — the mode gate, the download screen, or an unactivated car,
 * which {@code routeFor} answers with null because the activation overlay lives on
 * {@link FullscreenActivity} — is handed back to that screen, and {@link #finish()} is called
 * before {@code super.onCreate} so the library is never drawn, not even for a frame, to a car
 * that has not earned it.
 */
public class LeopardEntryActivity extends LeopardPickerActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        SharedPreferences sp = getSharedPreferences(
                SettingsActivity.SHARED_PREF_DOMAIN, MODE_PRIVATE);

        Intent gated = null;
        boolean pickerIsRight = false;
        try {
            gated = FullscreenActivity.routeFor(this, sp);
            pickerIsRight = gated != null
                    && gated.getComponent() != null
                    && LeopardPickerActivity.class.getName()
                            .equals(gated.getComponent().getClassName());
        } catch(Throwable t) {
            // A gate that cannot answer is not a gate that says yes.
            pickerIsRight = false;
        }

        if(pickerIsRight) {
            super.onCreate(savedInstanceState);
            return;
        }

        // Not the picker: finish FIRST so nothing of this screen is ever drawn, then hand the
        // customer to the screen the gate actually named. A null route means the activation
        // overlay, which lives on FullscreenActivity.
        finish();
        super.onCreate(savedInstanceState);
        Intent next = (gated != null) ? gated : new Intent(this, FullscreenActivity.class);
        try {
            startActivity(next);
        } catch(Throwable ignored) {
        }
    }
}
