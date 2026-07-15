package systems.sieber.fsclock;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInstaller;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Self-update mechanism for the side-loaded (non-Play-Store) build.
 *
 * It reads the newest row from the Supabase "app_versions" table, compares its
 * version_code with the installed BuildConfig.VERSION_CODE and, if a newer
 * version exists, downloads the APK from the given url and launches the system
 * package installer (which replaces the old app in place).
 *
 * Supabase URL/key are reused from the (obfuscated) app configuration via
 * {@link WallpaperRepo}, so no extra credentials are stored here.
 */
public class UpdateManager {

    public interface UpdateCheckListener {
        void onUpdateAvailable(int versionCode, String versionName, String apkUrl, String changelog);
        void onNoUpdate();
        void onError(String message);
    }

    private final Activity mActivity;

    public UpdateManager(Activity activity) {
        mActivity = activity;
    }

    /** Query Supabase for the newest version on a background thread. */
    public void checkForUpdate(final UpdateCheckListener listener) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                try {
                    String base = WallpaperRepo.getSupabaseUrl();
                    String key = WallpaperRepo.getSupabaseKey();
                    if(base == null || base.contains("YOUR_SUPABASE_PROJECT")) {
                        postNoUpdate(listener);
                        return;
                    }
                    URL url = new URL(base + "/rest/v1/app_versions"
                            + "?select=version_code,version_name,apk_url,changelog"
                            + "&order=version_code.desc&limit=1");
                    conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("GET");
                    conn.setRequestProperty("apikey", key);
                    conn.setRequestProperty("Authorization", "Bearer " + key);
                    conn.setRequestProperty("Accept", "application/json");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);

                    int status = conn.getResponseCode();
                    if(status < 200 || status >= 300) {
                        postError(listener, "HTTP " + status);
                        return;
                    }

                    BufferedReader in = new BufferedReader(new InputStreamReader(conn.getInputStream()));
                    StringBuilder sb = new StringBuilder();
                    String line;
                    while((line = in.readLine()) != null) sb.append(line);
                    in.close();

                    JSONArray arr = new JSONArray(sb.toString());
                    if(arr.length() == 0) {
                        postNoUpdate(listener);
                        return;
                    }

                    JSONObject obj = arr.getJSONObject(0);
                    final int versionCode = obj.getInt("version_code");
                    final String versionName = obj.optString("version_name", "");
                    final String apkUrl = obj.optString("apk_url", "");
                    final String changelog = obj.optString("changelog", "");

                    if(versionCode > BuildConfig.VERSION_CODE && !apkUrl.isEmpty()) {
                        mActivity.runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                listener.onUpdateAvailable(versionCode, versionName, apkUrl, changelog);
                            }
                        });
                    } else {
                        postNoUpdate(listener);
                    }
                } catch(Exception e) {
                    postError(listener, e.getMessage());
                } finally {
                    if(conn != null) conn.disconnect();
                }
            }
        }).start();
    }

    private void postNoUpdate(final UpdateCheckListener l) {
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() { l.onNoUpdate(); }
        });
    }

    private void postError(final UpdateCheckListener l, final String msg) {
        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() { l.onError(msg); }
        });
    }

    /** Download the APK with DownloadManager and trigger the installer on completion. */
    public void downloadAndInstall(String apkUrl) {
        // On Android O+ the user must explicitly allow installing apps from this app.
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !mActivity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(mActivity)
                    .setTitle(R.string.update_title)
                    .setMessage(R.string.update_allow_unknown_sources)
                    .setPositiveButton(R.string.update_now, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface d, int w) {
                            try {
                                mActivity.startActivity(new Intent(
                                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                        Uri.parse("package:" + mActivity.getPackageName())));
                            } catch(Exception ignored) { }
                        }
                    })
                    .setNegativeButton(R.string.update_cancel, null)
                    .show();
            return;
        }

        Toast.makeText(mActivity, R.string.update_downloading, Toast.LENGTH_SHORT).show();

        final File target = new File(mActivity.getExternalFilesDir("apk"), "update.apk");
        if(target.exists()) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
        }

        DownloadManager dm = (DownloadManager) mActivity.getSystemService(Context.DOWNLOAD_SERVICE);
        if(dm == null) {
            Toast.makeText(mActivity, R.string.update_failed, Toast.LENGTH_LONG).show();
            return;
        }

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl));
        req.setTitle(mActivity.getString(R.string.app_name));
        req.setDescription(mActivity.getString(R.string.update_downloading));
        req.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        req.setDestinationInExternalFilesDir(mActivity, "apk", "update.apk");
        req.setMimeType("application/vnd.android.package-archive");
        final long downloadId = dm.enqueue(req);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long received = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if(received != downloadId) return;
                try {
                    context.unregisterReceiver(this);
                } catch(IllegalArgumentException ignored) { }
                install(target);
            }
        };
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // system broadcast -> must be flagged as exported on Android 13+
            mActivity.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            mActivity.registerReceiver(receiver, filter);
        }
    }

    /** Broadcast action the PackageInstaller session reports its result to. */
    private static final String INSTALL_ACTION = "systems.sieber.fsclock.INSTALL_COMPLETE";

    private void install(File file) {
        if(file == null || !file.exists()) {
            Toast.makeText(mActivity, R.string.update_failed, Toast.LENGTH_LONG).show();
            return;
        }

        // Preferred path: the modern PackageInstaller "session" API. It replaces
        // the app in place AND — unlike the old ACTION_VIEW intent — reports the
        // real reason when it fails (signature mismatch, downgrade, …) so the
        // shop owner sees why instead of a blank "App not installed".
        PackageInstaller.Session session = null;
        try {
            PackageInstaller installer = mActivity.getPackageManager().getPackageInstaller();
            PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(
                    PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(mActivity.getPackageName());

            int sessionId = installer.createSession(params);
            session = installer.openSession(sessionId);

            OutputStream out = session.openWrite("update.apk", 0, file.length());
            InputStream in = new FileInputStream(file);
            try {
                byte[] buf = new byte[65536];
                int n;
                while((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            } finally {
                try { out.close(); } catch(Exception ignored) { }
                try { in.close(); } catch(Exception ignored) { }
            }

            registerInstallResultReceiver();

            Intent statusIntent = new Intent(INSTALL_ACTION).setPackage(mActivity.getPackageName());
            int piFlags = (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                    ? PendingIntent.FLAG_MUTABLE : 0;
            PendingIntent pi = PendingIntent.getBroadcast(mActivity, sessionId, statusIntent, piFlags);
            session.commit(pi.getIntentSender());
            session.close();
            session = null;
        } catch(Exception e) {
            if(session != null) {
                try { session.abandon(); } catch(Exception ignored) { }
            }
            // Fall back to the legacy installer if the session API is unavailable.
            legacyInstall(file);
        }
    }

    /** Listens for the PackageInstaller session outcome and surfaces it to the user. */
    private void registerInstallResultReceiver() {
        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                        PackageInstaller.STATUS_FAILURE);
                if(status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                    // System is asking the user to confirm the install — launch it.
                    Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                    if(confirm != null) {
                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        try { mActivity.startActivity(confirm); } catch(Exception ignored) { }
                    }
                    return; // keep listening for the final result
                }
                try { context.unregisterReceiver(this); } catch(IllegalArgumentException ignored) { }
                if(status == PackageInstaller.STATUS_SUCCESS) {
                    return; // app is being replaced; nothing to show
                }
                String reason = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE);
                int msgRes = (status == PackageInstaller.STATUS_FAILURE_CONFLICT)
                        ? R.string.update_signature_mismatch
                        : R.string.update_failed;
                String text = mActivity.getString(msgRes);
                if(reason != null && !reason.isEmpty()
                        && status != PackageInstaller.STATUS_FAILURE_CONFLICT) {
                    text += "\n(" + reason + ")";
                }
                Toast.makeText(mActivity, text, Toast.LENGTH_LONG).show();
            }
        };
        IntentFilter filter = new IntentFilter(INSTALL_ACTION);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            mActivity.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            mActivity.registerReceiver(receiver, filter);
        }
    }

    /** Pre-session fallback: hand the APK to the system installer via a view intent. */
    private void legacyInstall(File file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Uri uri;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            uri = FileProvider.getUriForFile(mActivity,
                    mActivity.getPackageName() + ".fileprovider", file);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            uri = Uri.fromFile(file);
        }
        intent.setDataAndType(uri, "application/vnd.android.package-archive");
        try {
            mActivity.startActivity(intent);
        } catch(Exception e) {
            Toast.makeText(mActivity, R.string.update_failed, Toast.LENGTH_LONG).show();
        }
    }
}
