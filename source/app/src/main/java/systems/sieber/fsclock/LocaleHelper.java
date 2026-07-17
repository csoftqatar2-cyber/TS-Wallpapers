package systems.sieber.fsclock;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.Build;

import java.util.Locale;

/**
 * Forces the app's language independently of the head unit's system language.
 *
 * A car head unit is often stuck on whatever locale the ROM shipped with, and the installer
 * technician cannot change it. So the app carries its own language switch.
 *
 * appcompat is on 1.3.1 here, which predates AppCompatDelegate.setApplicationLocales(), so the
 * locale is applied the long way round: every activity wraps its base context on the way up.
 */
public class LocaleHelper {

    public static final String LANG_SYSTEM = "";
    public static final String LANG_ARABIC = "ar";
    public static final String LANG_ENGLISH = "en";

    private static final String PREF_KEY = "app-language";

    /** The language actually in force, resolving LANG_SYSTEM against the device locale. */
    public static String resolved(Context context) {
        String saved = saved(context);
        if(!LANG_SYSTEM.equals(saved)) return saved;
        return isSystemArabic(context) ? LANG_ARABIC : LANG_ENGLISH;
    }

    public static String saved(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        return prefs.getString(PREF_KEY, LANG_SYSTEM);
    }

    public static void save(Context context, String language) {
        context.getSharedPreferences(BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE)
                .edit().putString(PREF_KEY, language).apply();
    }

    private static boolean isSystemArabic(Context context) {
        Locale locale;
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locale = context.getResources().getConfiguration().getLocales().get(0);
        } else {
            locale = context.getResources().getConfiguration().locale;
        }
        return locale != null && LANG_ARABIC.equals(locale.getLanguage());
    }

    /**
     * Wrap a base context so it resolves resources in the chosen language. Call from
     * Activity.attachBaseContext(). Returns the context untouched when the user has not
     * overridden the language, so the device locale keeps winning by default.
     */
    public static Context wrap(Context context) {
        String language = saved(context);
        if(LANG_SYSTEM.equals(language)) return context;

        Locale locale = new Locale(language);
        Locale.setDefault(locale);

        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocale(locale);
        // Without this the strings flip but the layout keeps the old direction, which leaves
        // Arabic laid out left-to-right.
        config.setLayoutDirection(locale);
        return context.createConfigurationContext(config);
    }
}
