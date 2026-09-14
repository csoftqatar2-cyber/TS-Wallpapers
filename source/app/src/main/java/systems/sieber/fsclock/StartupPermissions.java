package systems.sieber.fsclock;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

/**
 * The two runtime grants every activated car needs, asked for on the first screen it reaches —
 * not on whichever visit to Settings somebody eventually makes.
 *
 * <p>Owner's order (2026-09-14): on a Leopard's passenger screen the app used to ask for Location
 * and "Display over other apps" only when the driver opened Settings, which on that screen nobody
 * ever does. So both are now requested right after activation / on first launch, on every
 * instance of the app (driver screen, user 0, and passenger screen, user 999), from the first
 * screen shown once the car is activated: {@link FullscreenActivity}, {@link LeopardPickerActivity}
 * (and through it {@link LeopardEntryActivity}) and {@link ModeConfirmActivity}.
 *
 * <p>Rules this keeps:
 * <ul>
 *   <li>Silent no-op when both grants are already held — the installer pre-grants them over adb
 *       ({@code pm grant} / {@code appops set SYSTEM_ALERT_WINDOW allow}) and must see nothing.</li>
 *   <li>Never on an unactivated car: the activation card is not the place, and
 *       {@link WallpaperRepo#isActive()} is the same gate the screens themselves use.</li>
 *   <li>Once per process for each grant. Rotation, a second screen in the same run, or a denial
 *       never produce a second dialog; the next launch asks again. A "don't ask again" denial
 *       comes back from Android at once with no dialog, and this class does not fight it.</li>
 *   <li>Location first (the system dialog), overlay only after the location result has arrived,
 *       so the two never sit on top of each other. The overlay dialog is the existing
 *       {@link OverlayPermission} flow — same wording, same settings screen — so this adds no
 *       second copy of that explanation.</li>
 * </ul>
 *
 * <p>The Settings-side requests are untouched: they still ask on their own triggers.
 */
final class StartupPermissions {

    private StartupPermissions() { }

    /** Distinct from every other request code in the app (Settings uses 0 for both of its asks). */
    static final int REQUEST_LOCATION = 7078;

    private static final String[] LOCATION = {
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION };

    /** Process-wide: one location ask and one overlay ask per run, whichever screen fires first. */
    private static boolean sLocationAsked;
    private static boolean sOverlayAsked;

    /**
     * Ask for whatever is missing, in order. Safe to call from every onCreate: it returns at once
     * for an unactivated, finishing or already-served activity.
     */
    static void requestIfMissing(Activity a) {
        try {
            if(a == null || a.isFinishing()) return;
            if(!isActivated(a)) return;
            if(!hasLocation(a) && !sLocationAsked) {
                sLocationAsked = true;
                CrashReporter.breadcrumb("startup permissions: asking location on "
                        + a.getClass().getSimpleName());
                // The overlay ask continues from onRequestPermissionsResult, once this dialog
                // has been answered — see onRequestPermissionsResult below.
                ActivityCompat.requestPermissions(a, LOCATION, REQUEST_LOCATION);
                return;
            }
            requestOverlayIfMissing(a);
        } catch(Throwable t) {
            // A permission prompt must never be what takes a car's first screen down.
            android.util.Log.e("StartupPermissions", "request failed", t);
        }
    }

    /**
     * Second half of the order: the wired activities forward their
     * {@code onRequestPermissionsResult} here so the overlay ask follows the location answer.
     * Ignores every request code but its own.
     */
    static void onRequestPermissionsResult(Activity a, int requestCode) {
        if(requestCode != REQUEST_LOCATION) return;
        try {
            if(a == null || a.isFinishing()) return;
            requestOverlayIfMissing(a);
        } catch(Throwable t) {
            android.util.Log.e("StartupPermissions", "overlay after location failed", t);
        }
    }

    private static void requestOverlayIfMissing(Activity a) {
        if(sOverlayAsked) return;
        if(OverlayPermission.isGranted(a)) return;
        sOverlayAsked = true;
        CrashReporter.breadcrumb("startup permissions: asking overlay on "
                + a.getClass().getSimpleName());
        OverlayPermission.request(a, null);
    }

    private static boolean hasLocation(Activity a) {
        return ContextCompat.checkSelfPermission(a, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                || ContextCompat.checkSelfPermission(a, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean isActivated(Activity a) {
        try {
            return new WallpaperRepo(a).isActive();
        } catch(Throwable t) {
            return false;
        }
    }
}
