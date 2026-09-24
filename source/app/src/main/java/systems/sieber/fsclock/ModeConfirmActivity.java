package systems.sieber.fsclock;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.Log;
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
        if(!new WallpaperRepo(ctx).isActive()) return false;
        // Before asking: the controller may already have said which car this is.
        return !answerFromController(ctx, prefs);
    }

    private static final String TAG = "CarTypeFile";

    /** No mode chosen yet. Distinct from every real OperatingMode value, which start at 0. */
    static final int MODE_NONE = -1;

    /** The negative outcome is noted once per process; the gate is consulted many times. */
    private static boolean sControllerOutcomeNoted = false;

    /**
     * The store's answer to "which car is this?" ({@code get_car_type}), asked at most once per
     * process. {@code sStoreAsked} separates "never asked" from "asked, and the answer was null or
     * the call failed": a car that is offline is asked again on its next launch, not every time
     * the gate is consulted in this one. {@code sStoreCar} is the raw store id
     * ({@code leopard}, {@code tank500}, …) so the log and the breadcrumb can name it.
     */
    private static volatile boolean sStoreAsked = false;
    private static volatile boolean sStoreInFlight = false;
    private static volatile String sStoreCar = null;
    private static final java.util.List<Runnable> sStoreWaiters = new java.util.ArrayList<>();

    /**
     * The operating mode a car family stands for on THIS unit, or {@link #MODE_NONE}.
     *
     * The family may come from the controller's files or from the store's RPC — the source does
     * not matter here. Each family still has to pass the same support gate its radio button does:
     * a Leopard answer on a unit without live-wallpaper support must not silently put the car into
     * a mode that cannot work there — that car keeps being asked, with the option dimmed, exactly
     * as today.
     *
     * <p><b>The Leopard family is two modes on a two-screen car.</b> A BYD Leopard unit runs this
     * app twice: user 0 on the driver screen, where the wallpaper hand-off is the product, and a
     * second Android user (999) on the passenger strip, whose product is the drawn FSE screen.
     * The instance knows which it is ({@link WallpaperRepo#isSecondaryInstance}), so a Leopard
     * answer from any source is Leopard on the driver screen and FSE on the passenger one — the
     * exact pair the owner used to set by hand on every such car. FSE needs no support gate: it
     * is the app's own window.
     */
    static int controllerMode(Context ctx, CarTypeFile.Family family) {
        if(family == null) return MODE_NONE;
        switch(family) {
            case LEOPARD:
                if(WallpaperRepo.isSecondaryInstance()) return OperatingMode.FSE;
                return OperatingMode.isSupported(ctx) ? OperatingMode.LEOPARD : MODE_NONE;
            case DENZA:
                return OperatingMode.isDenzaSupported(ctx) ? OperatingMode.DENZA : MODE_NONE;
            case ICAR03T:
                return OperatingMode.isIcar03tSupported(ctx) ? OperatingMode.ICAR03T : MODE_NONE;
            case GWM:
                return OperatingMode.GWM;
            case LYNKCO:
                return OperatingMode.isLynkcoSupported(ctx) ? OperatingMode.LYNKCO : MODE_NONE;
            case JETOUR:
                return OperatingMode.JETOUR;
            case OTHERS:
                // The plain drawn screen. Applied, not asked: the owner's table names these cars
                // as Others on purpose, so the question would only ever have one answer.
                return OperatingMode.NORMAL;
            default:
                return MODE_NONE;
        }
    }

    /**
     * The mode every source that has already answered agrees on for this unit, or
     * {@link #MODE_NONE}. Files first — {@code car_family.txt}, then {@code car.txt}
     * ({@link CarTypeFile#read}) — then the store's answer, if it has arrived in this process
     * ({@link #askStoreAsync}). Never touches the network: this is asked on the main thread.
     */
    static int autoMode(Context ctx) {
        int mode = controllerMode(ctx, CarTypeFile.read());
        if(mode != MODE_NONE) return mode;
        return controllerMode(ctx, CarTypeFile.familyOfStoreCar(sStoreCar));
    }

    /** True when {@link #autoMode} would be answered by the store rather than the controller's files. */
    static boolean autoModeIsFromStore(Context ctx) {
        return controllerMode(ctx, CarTypeFile.read()) == MODE_NONE && sStoreCar != null;
    }

    /**
     * Answer the mode question from what the car already knows instead of asking the driver.
     *
     * The owner's rule (2026-09-10): لوحة تحكم ذبذبة has already chosen the car on every head
     * unit it runs on, and an app must not ask again. Extended 2026-09-14 to the second publisher
     * of that choice, ذبذبة ستور, whose picker answer the backend serves by hardware id — most
     * cars carry the store and no controller, so neither file exists there. Resolution order:
     * {@code car_family.txt} → {@code car.txt} → the store's {@code get_car_type} answer (asked
     * off the main thread by {@link #askStoreAsync}; this method only reads what has arrived).
     *
     * On an install where no mode was EVER saved ({@link OperatingMode#isUnset}) and a source
     * names a family this app knows, the mode is applied here through the same writes the radio
     * buttons make — {@code set}, {@code setConfirmed}, {@code reportModeAsync}, and for FSE the
     * start-on-boot switch — so prefs, the manager's mode column, the hand-off routing and the
     * folder mirror all see exactly what a human's pick would have produced. Settings can still
     * change it afterwards, as always.
     *
     * A saved mode of any kind is never touched: a driver's choice, a migration's pin, or an
     * earlier run of this very method. An unknown answer, missing files, an unsupported unit or
     * any error leaves the question to the driver, as before.
     *
     * @return true only when the mode was applied by THIS call.
     */
    static boolean answerFromController(Context ctx, SharedPreferences prefs) {
        try {
            if(!OperatingMode.isUnset(prefs)) return false;
            int mode = controllerMode(ctx, CarTypeFile.read());
            String source = "car_family.txt/car.txt";   // CarTypeFile.read() just logged which
            if(mode == MODE_NONE) {
                String car = sStoreCar;
                mode = controllerMode(ctx, CarTypeFile.familyOfStoreCar(car));
                source = "get_car_type=" + (car == null ? "<none>" : car);
            }
            if(mode == MODE_NONE) {
                if(!sControllerOutcomeNoted && sStoreAsked) {
                    sControllerOutcomeNoted = true;
                    CrashReporter.breadcrumb(source + " -> no auto mode, asking the driver");
                }
                return false;
            }
            OperatingMode.set(prefs, mode);
            OperatingMode.setConfirmed(prefs);
            // FSE means "the car boots into this screen" — the same write the two human paths
            // make. The overlay grant a boot-time start also needs is NOT asked for here: nobody
            // is standing at a screen that answered itself.
            if(mode == OperatingMode.FSE) {
                prefs.edit().putBoolean(BootReceiver.PREF_AUTO_START, true).apply();
            }
            new WallpaperRepo(ctx).reportModeAsync();
            String wire = OperatingMode.wire(prefs);
            // Log.e survives the release build's log stripping; this is the one line a technician
            // reading logcat on the bench needs to see.
            Log.e(TAG, source + " → mode=" + wire
                    + (WallpaperRepo.isSecondaryInstance() ? " (secondary instance)" : ""));
            CrashReporter.breadcrumb(source + " -> mode=" + wire + " (auto-applied, driver not asked)");
            return true;
        } catch(Throwable t) {
            return false;
        }
    }

    /**
     * Ask the store which car this is, once per process, off the main thread, and run
     * {@code onAnswered} on the main thread when the answer is in — whatever it was. The caller
     * then re-asks {@link #answerFromController}, which reads the cached answer; nothing is
     * applied here. Skipped outright when a mode is already saved or the files already answer,
     * because then there is no question left to ask the network.
     *
     * The waiters list is for the two screens that can be showing the question while the call is
     * in flight (the activation overlay and the confirm gate) — each gets its callback, and a
     * screen created after the answer arrived gets it straight away. The callback always runs,
     * exactly once, whether or not the network was asked, so a screen can put its "detecting…"
     * line up before this call and take it down in the callback.
     */
    static void askStoreAsync(final Context ctx, final SharedPreferences prefs, final Runnable onAnswered) {
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        if(!OperatingMode.isUnset(prefs) || controllerMode(ctx, CarTypeFile.read()) != MODE_NONE) {
            if(onAnswered != null) main.post(onAnswered);
            return;
        }
        synchronized(sStoreWaiters) {
            if(sStoreAsked && !sStoreInFlight) {
                if(onAnswered != null) main.post(onAnswered);
                return;
            }
            if(onAnswered != null) sStoreWaiters.add(onAnswered);
            if(sStoreInFlight) return;
            sStoreInFlight = true;
        }
        final Context app = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
        new Thread(new Runnable() {
            @Override public void run() {
                String car = null;
                try {
                    car = new WallpaperRepo(app).fetchStoreCarType();
                } catch(Throwable ignored) {
                    // fetchStoreCarType never throws; belt and braces around the constructor.
                }
                final Runnable[] waiters;
                synchronized(sStoreWaiters) {
                    sStoreCar = car;
                    sStoreAsked = true;
                    sStoreInFlight = false;
                    waiters = sStoreWaiters.toArray(new Runnable[0]);
                    sStoreWaiters.clear();
                }
                for(Runnable r : waiters) main.post(r);
            }
        }, "get_car_type").start();
    }

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPrefs = getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);

        // Someone else may have answered in the meantime (the settings picker, a second launch)
        // — or the controller's car file answers it now, when this screen was reached directly.
        if(OperatingMode.isConfirmed(mPrefs) || answerFromController(this, mPrefs)) { openApp(); return; }

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
        // ICAR 03T is gated by the car's launcher, which is what actually shows the picture.
        disableUnsupported(R.id.radioConfirmIcar03t, R.id.textViewConfirmIcar03tNote,
                R.string.icar03t_unsupported, OperatingMode.isIcar03tSupported(this));
        // Haval is dimmed the same way: the launcher we write the list for only exists on GWM.
        disableUnsupported(R.id.radioConfirmHaval, 0, 0, OperatingMode.isHavalSupported(this));
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

        // The files did not answer (or this car has no controller). The store may still know:
        // ask it while the list is on screen, and if it names a family this app knows the mode is
        // applied and the screen leaves on its own. A driver who picks first wins — the answer
        // only lands on an install that is still unset.
        if(OperatingMode.isUnset(mPrefs)) {
            if(mDesc != null) mDesc.setText(R.string.mode_detecting_from_store);
            ModeConfirmActivity.askStoreAsync(this, mPrefs, () -> {
                if(isFinishing() || isDestroyed()) return;
                if(answerFromController(ModeConfirmActivity.this, mPrefs)) { openApp(); return; }
                updateDesc(selectedMode());
            });
        }

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

        // A car the Store activated lands here first, so this is where its location and overlay
        // grants are asked for — the technician is standing at the car right now.
        StartupPermissions.requestIfMissing(this);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // The startup location ask was answered: the overlay ask follows it, never beside it.
        StartupPermissions.onRequestPermissionsResult(this, requestCode);
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
        if(id == R.id.radioConfirmIcar03t) return OperatingMode.ICAR03T;
        if(id == R.id.radioConfirmHaval) return OperatingMode.HAVAL;
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
                : mode == OperatingMode.ICAR03T ? R.string.mode_icar03t_desc
                : mode == OperatingMode.HAVAL ? R.string.mode_haval_desc
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
