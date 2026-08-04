package systems.sieber.fsclock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/**
 * The one-time "which car is this?" gate.
 *
 * A car can be activated without anybody ever telling this app what it is: the Store activates
 * the whole bundle from one serial, and every install that predates the five-mode picker was
 * migrated onto NORMAL/FSE by a guess. The manager then shows a screen full of cars claiming to
 * be "normal", and the operator cannot tell a real Normal car from an unanswered one — which
 * matters, because the mode is what decides whether a car is fed the GWM channel, the Lynk &amp; Co
 * hand-off, or nothing at all.
 *
 * So an activated car with no confirmed mode stops here, once. There is no serial field (that
 * step is already done — the green line says so) and no way past except choosing, because a
 * skippable gate would leave exactly the unanswered cars it exists to remove. The choice is
 * written down, reported to the backend right away, and the app opens straight into that mode
 * from then on.
 */
public class ModeConfirmActivity extends AppCompatActivity {

    private SharedPreferences mPrefs;
    private RadioGroup mGroup;
    private TextView mDesc;
    private boolean mLeopardSupported;
    private boolean mLynkcoSupported;

    /** Whether this install still owes us the choice. The single place the gate is decided. */
    static boolean isPending(Context ctx, SharedPreferences prefs) {
        if(OperatingMode.isConfirmed(prefs)) return false;
        // Only an ACTIVATED car is asked. An unactivated one goes to the activation overlay,
        // which carries its own mode picker and confirms through the same path.
        return new WallpaperRepo(ctx).isActive();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);

        // Someone else may have answered in the meantime (the settings picker, a second launch).
        if(OperatingMode.isConfirmed(mPrefs)) { openApp(); return; }

        setContentView(R.layout.activity_mode_confirm);

        mLeopardSupported = OperatingMode.isSupported(this);
        mLynkcoSupported = OperatingMode.isLynkcoSupported(this);

        mGroup = findViewById(R.id.radioGroupConfirmMode);
        mDesc = findViewById(R.id.textViewModeConfirmDesc);

        // A head unit that cannot do Leopard/Lynk & Co must show that as a real, visible state:
        // the technician has no other way to find out, and a mode that silently does nothing is
        // the worst answer we could record for this car.
        disableUnsupported(R.id.radioConfirmLeopard, mLeopardSupported);
        disableUnsupported(R.id.radioConfirmLynkco, mLynkcoSupported);

        TextView deviceId = findViewById(R.id.textViewModeConfirmDeviceId);
        if(deviceId != null) {
            deviceId.setText(getString(R.string.activation_device_id_label)
                    + new WallpaperRepo(this).getDeviceId());
        }

        // Start on whatever the car currently believes it is, so confirming an already-correct
        // guess is one tap rather than a hunt.
        check(OperatingMode.get(mPrefs));
        updateDesc(selectedMode());
        mGroup.setOnCheckedChangeListener((g, id) -> updateDesc(selectedMode()));

        Button confirm = findViewById(R.id.buttonModeConfirm);
        confirm.setOnClickListener(v -> apply(selectedMode()));
    }

    private void disableUnsupported(int id, boolean supported) {
        if(supported) return;
        RadioButton b = findViewById(id);
        if(b == null) return;
        b.setEnabled(false);
        b.setAlpha(0.4f);
        b.setText(b.getText() + " — " + getString(
                id == R.id.radioConfirmLynkco ? R.string.lynkco_unsupported
                        : R.string.leopard_unsupported));
    }

    private void check(int mode) {
        int id = mode == OperatingMode.FSE ? R.id.radioConfirmFse
                : mode == OperatingMode.LEOPARD ? R.id.radioConfirmLeopard
                : mode == OperatingMode.GWM ? R.id.radioConfirmGwm
                : mode == OperatingMode.LYNKCO ? R.id.radioConfirmLynkco
                : R.id.radioConfirmNormal;
        RadioButton b = findViewById(id);
        // A migrated Leopard car on a ROM that cannot do Leopard would otherwise preselect a
        // disabled button and leave the group with nothing checked.
        if(b == null || !b.isEnabled()) id = R.id.radioConfirmNormal;
        mGroup.check(id);
    }

    private int selectedMode() {
        int id = mGroup.getCheckedRadioButtonId();
        if(id == R.id.radioConfirmFse) return OperatingMode.FSE;
        if(id == R.id.radioConfirmLeopard) return OperatingMode.LEOPARD;
        if(id == R.id.radioConfirmGwm) return OperatingMode.GWM;
        if(id == R.id.radioConfirmLynkco) return OperatingMode.LYNKCO;
        return OperatingMode.NORMAL;
    }

    private void updateDesc(int mode) {
        if(mDesc == null) return;
        int res = mode == OperatingMode.LEOPARD ? R.string.mode_leopard_desc
                : mode == OperatingMode.FSE ? R.string.mode_fse_desc
                : mode == OperatingMode.GWM ? R.string.mode_gwm_desc
                : mode == OperatingMode.LYNKCO ? R.string.mode_lynkco_desc
                : R.string.mode_normal_desc;
        mDesc.setText(getString(res));
    }

    private void apply(int mode) {
        OperatingMode.set(mPrefs, mode);
        OperatingMode.setConfirmed(mPrefs);

        // Tell the manager immediately. Fire-and-forget on a background thread: an offline car
        // still gets through the gate, and the next sync reports the same mode again anyway.
        new WallpaperRepo(this).reportModeAsync();

        // FSE means "the car boots into this screen" — that is what the mode is, so the
        // start-on-boot switch follows it here exactly as it does in Settings. And boot-time
        // start needs the overlay grant on Android 10+, so it is asked for HERE rather than
        // left for whenever somebody next opens Settings: this gate is the last moment the
        // technician is standing at the car, and the failure it prevents only shows up at the
        // customer's next cold start.
        if(mode == OperatingMode.FSE) {
            mPrefs.edit().putBoolean(BootReceiver.PREF_AUTO_START, true).apply();
            OverlayPermission.request(this, this::openApp);
            return;
        }

        openApp();
    }

    /** Enter the confirmed mode: the hand-off products open their picker, the rest the clock. */
    private void openApp() {
        Intent next = OperatingMode.isHandoff(mPrefs)
                ? new Intent(this, LeopardPickerActivity.class)
                : new Intent(this, FullscreenActivity.class);
        next.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(next);
        finish();
    }

    /**
     * No way out but the choice. Back would drop the technician onto the launcher with the car
     * still unanswered, which is the state this screen exists to end.
     */
    @Override
    public void onBackPressed() {
        // intentionally empty
    }
}
