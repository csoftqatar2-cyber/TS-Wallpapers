package systems.sieber.fsclock;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/* ==== MERGE:OWNER begin ==== */
/**
 * Whether THABTHABA Dashboard ({@code com.thabthaba.controller}) has taken over one of this
 * app's jobs on this car.
 *
 * The dashboard writes {@code Settings.Global thab_owner =
 * controller;<versionCode>;<csv tokens>;<epochSeconds>} — only on Leopard-family cars, and only
 * for the features its owner switched on. A token present means "the dashboard does this now";
 * a token removed means "give it back". This app only reads it.
 *
 * Both halves are required: the flag AND the dashboard actually installed. A flag left behind by
 * an uninstalled dashboard must never silence this app, or the car would be left with nobody
 * setting its wallpaper.
 *
 * Read fresh at every decision point (one settings read + one package lookup): the owner can flip
 * the switch in the dashboard at any moment, and turning it off must hand the job straight back.
 */
final class DashboardOwner {

    static final String KEY = "thab_owner";
    static final String DASHBOARD_PACKAGE = "com.thabthaba.controller";
    /** The wallpaper feature's token. */
    static final String PAPER = "paper";

    private DashboardOwner() {}

    static boolean owned(Context ctx, String token) {
        if(ctx == null || token == null || token.isEmpty()) return false;
        try {
            String v = Settings.Global.getString(ctx.getContentResolver(), KEY);
            if(v == null || !v.startsWith("controller;")) return false;
            String[] parts = v.split(";", -1);
            if(parts.length < 3) return false;
            boolean listed = false;
            for(String t : parts[2].split(",")) {
                if(token.equals(t.trim())) { listed = true; break; }
            }
            if(!listed) return false;
            ctx.getPackageManager().getPackageInfo(DASHBOARD_PACKAGE, 0);
            return true;
        } catch(Throwable t) {
            // NameNotFoundException (dashboard gone) or anything odd: the app keeps its job.
            return false;
        }
    }

    /** Open the dashboard on the given section; false when it could not be started. */
    static boolean openDashboard(Activity activity, String section) {
        try {
            Intent i = activity.getPackageManager().getLaunchIntentForPackage(DASHBOARD_PACKAGE);
            if(i == null) return false;
            i.putExtra("section", section);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(i);
            return true;
        } catch(Throwable t) {
            return false;
        }
    }
}
/* ==== MERGE:OWNER end ==== */
