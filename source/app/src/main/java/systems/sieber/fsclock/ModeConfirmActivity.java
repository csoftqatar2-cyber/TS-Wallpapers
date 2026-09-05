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
 *
 * The one thing besides choosing that can be done here is updating the app — see
 * {@link #checkForUpdate}. That is not a way past the gate; it is what makes the gate answerable
 * on a car whose mode was added to a later version than the one installed.
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
        disableUnsupported(R.id.radioConfirmLeopard, R.id.textViewConfirmLeopardNote,
                R.string.leopard_unsupported, mLeopardSupported);
        // Denza is the same WallpaperManager hand-off as Leopard: same gate, own label.
        disableUnsupported(R.id.radioConfirmDenza, R.id.textViewConfirmDenzaNote,
                R.string.denza_unsupported, OperatingMode.isDenzaSupported(this));
        // Lynkco is dimmed when the unit cannot do it, with no explanatory line: the dimming is
        // the whole message on a customer-facing screen.
        disableUnsupported(R.id.radioConfirmLynkco, 0, 0, mLynkcoSupported);

        TextView deviceId = findViewById(R.id.textViewModeConfirmDeviceId);
        if(deviceId != null) {
            deviceId.setText(getString(R.string.activation_device_id_label)
                    + new WallpaperRepo(this).getDeviceId());
        }

        // Nothing is preselected, on purpose.
        //
        // This used to start on whatever the car already believed it was — which for a fresh car
        // is Others — so "confirm" was answerable without reading the list, and a car nobody had
        // actually looked at got recorded as Others. The mode decides whether the app draws the
        // screen, hands the file to the head unit, or mirrors a folder; a wrong one is not a
        // preference the customer can shrug off. So the choice has to be made, not accepted.
        final Button confirm = findViewById(R.id.buttonModeConfirm);
        mGroup.clearCheck();
        setConfirmEnabled(confirm, false);
        updateDesc(MODE_NONE);
        mGroup.setOnCheckedChangeListener((g, id) -> {
            int mode = selectedMode();
            updateDesc(mode);
            setConfirmEnabled(confirm, mode != MODE_NONE);
        });

        confirm.setOnClickListener(v -> {
            int mode = selectedMode();
            if(mode == MODE_NONE) return;   // belt and braces; the button is disabled anyway
            apply(mode);
        });

        TextView version = findViewById(R.id.textViewModeConfirmVersion);
        if(version != null) {
            version.setText(getString(R.string.update_current_version) + ": "
                    + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
        }
        Button update = findViewById(R.id.buttonModeConfirmUpdate);
        if(update != null) update.setOnClickListener(v -> checkForUpdate(update));
    }

    /**
     * Update the app without leaving the gate.
     *
     * The reason this button exists is a car that is newer than the app on it: a mode added after
     * the installed version simply is not in the list, and the only updater used to be in
     * Settings — which this screen will not let anyone reach until they have answered, with an
     * answer that cannot be right. So the technician updates here first, and picks the car
     * afterwards on a screen that finally lists it.
     *
     * Deliberately the same {@link UpdateManager} the rest of the app uses: same source of truth,
     * same installer, same failure messages. The button is disabled while the check is in flight
     * because a head unit is slow enough that a second tap is a certainty otherwise.
     */
    private void checkForUpdate(final Button button) {
        button.setEnabled(false);
        button.setText(R.string.update_downloading);
        new UpdateManager(this).checkForUpdate(new UpdateManager.UpdateCheckListener() {
            @Override
            public void onUpdateAvailable(int versionCode, String versionName,
                                          final String apkUrl, String changelog) {
                restore(button);
                String message = getString(R.string.update_message,
                        versionName + " (" + versionCode + ")");
                if(changelog != null && !changelog.trim().isEmpty()) {
                    message += "\n\n" + changelog.trim();
                }
                new AuroraDialog.Builder(ModeConfirmActivity.this)
                        .setTitle(R.string.update_title)
                        .setMessage(message)
                        .setPositiveButton(R.string.update_now,
                                (d, w) -> new UpdateManager(ModeConfirmActivity.this)
                                        .downloadAndInstall(apkUrl))
                        .setNegativeButton(R.string.update_later, null)
                        .show();
            }

            @Override
            public void onNoUpdate() {
                restore(button);
                toast(R.string.update_up_to_date);
            }

            @Override
            public void onError(String message) {
                restore(button);
                toast(R.string.update_check_failed);
            }
        });
    }

    private void restore(Button button) {
        button.setEnabled(true);
        button.setText(R.string.update_check);
    }

    private void toast(int res) {
        android.widget.Toast.makeText(this, res, android.widget.Toast.LENGTH_LONG).show();
    }

    private void disableUnsupported(int radioId, int noteId, int reasonRes, boolean supported) {
        if(supported) return;
        RadioButton b = findViewById(radioId);
        if(b != null) {
            b.setEnabled(false);
            b.setAlpha(0.4f);
        }
        if(noteId == 0 || reasonRes == 0) return;   // dimmed, and that is all it says
        TextView note = findViewById(noteId);
        if(note == null) return;
        note.setText(getString(reasonRes));
        note.setVisibility(TextView.VISIBLE);
    }

    /** No mode chosen yet. Distinct from every real OperatingMode value, which start at 0. */
    private static final int MODE_NONE = -1;

    /**
     * A locked button still has to read as the way forward, not as decoration — dimming it says
     * "not yet" where hiding it would say "not here".
     */
    private void setConfirmEnabled(Button confirm, boolean enabled) {
        if(confirm == null) return;
        confirm.setEnabled(enabled);
        confirm.setAlpha(enabled ? 1f : 0.45f);
    }

    private int selectedMode() {
        int id = mGroup.getCheckedRadioButtonId();
        if(id == -1) return MODE_NONE;      // nobody has chosen yet
        if(id == R.id.radioConfirmFse) return OperatingMode.FSE;
        if(id == R.id.radioConfirmLeopard) return OperatingMode.LEOPARD;
        if(id == R.id.radioConfirmDenza) return OperatingMode.DENZA;
        if(id == R.id.radioConfirmGwm) return OperatingMode.GWM;
        if(id == R.id.radioConfirmJetour) return OperatingMode.JETOUR;
        if(id == R.id.radioConfirmLynkco) return OperatingMode.LYNKCO;
        return OperatingMode.NORMAL;
    }

    private void updateDesc(int mode) {
        if(mDesc == null) return;
        if(mode == MODE_NONE) { mDesc.setText(R.string.mode_confirm_pick_first); return; }
        int res = mode == OperatingMode.LEOPARD ? R.string.mode_leopard_desc
                : mode == OperatingMode.DENZA ? R.string.mode_denza_desc
                : mode == OperatingMode.FSE ? R.string.mode_fse_desc
                : mode == OperatingMode.GWM ? R.string.mode_gwm_desc
                : mode == OperatingMode.JETOUR ? R.string.mode_jetour_desc
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

    /**
     * Enter the confirmed mode, by way of {@link WallpaperDownloadActivity} — always, whichever
     * mode was chosen. That screen is what decides where the car lands afterwards.
     */
    private void openApp() {
        // EVERY mode goes through the download gate, hand-off included.
        //
        // Leopard and Lynk & Co used to jump straight to the picker, on the reasoning that they
        // have no slideshow to protect. But the picker is a grid OF that library: arriving before
        // the files do is what produced the empty and stuck cells those cars kept showing. The
        // gate is the one place the technician is still standing at the car, so it is the right
        // place to wait — and WallpaperDownloadActivity sends each mode on to its own screen.
        Intent next = new Intent(this, WallpaperDownloadActivity.class);
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
