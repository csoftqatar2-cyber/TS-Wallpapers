package systems.sieber.fsclock;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;

import androidx.core.content.ContextCompat;

/**
 * The in-app manual: one dialog, sections chosen at runtime by {@link OperatingMode}.
 *
 * A Leopard car and an Others car are two different products sharing one APK (see the note on
 * OperatingMode), so one fixed help page would describe a clock screen to a driver who has never
 * seen one, or a picker to a driver who has never left the slideshow. The sections are therefore
 * assembled per mode: the mode's own meaning first, then activation, then whichever of the
 * picker / clock screen this car actually shows, and so on down to updates and support.
 *
 * Reached from the picker's top bar in the hand-off modes and from the Settings header in every
 * mode. The text lives in {@code help_*} strings (English in values/, Arabic in values-ar/);
 * every sentence there describes what the code does today, so a screen change means a string
 * change — nothing here is invented.
 */
final class HelpDialog {

    private HelpDialog() { }

    /** Show the manual for the mode this car is in. */
    static void show(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(
                BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        new AuroraDialog.Builder(activity)
                .setTitle(R.string.help_title)
                .setMessage(build(activity, OperatingMode.get(prefs)))
                .setPositiveButton(R.string.ok, null)
                .show();
    }

    /** The whole text, headings styled, for one mode value. */
    static CharSequence build(Context c, int mode) {
        final boolean handoff = OperatingMode.isHandoffMode(mode);
        SpannableStringBuilder out = new SpannableStringBuilder();

        // 1. What this mode is — the one thing that differs most between cars.
        section(c, out, c.getString(R.string.help_sec_mode_title, c.getString(modeName(mode))),
                c.getString(modeBody(mode)));

        // 2. Activation is the same screen on every car.
        section(c, out, c.getString(R.string.help_sec_activation_title),
                c.getString(R.string.help_sec_activation_body));

        // 3. The screen this car actually shows, and how a wallpaper is set on it.
        if(handoff) {
            section(c, out, c.getString(R.string.help_sec_picker_title),
                    c.getString(R.string.help_sec_picker_body));
            section(c, out, c.getString(R.string.help_sec_filters_title),
                    c.getString(R.string.help_sec_filters_body_handoff));
            section(c, out, c.getString(R.string.help_sec_apply_title),
                    c.getString(applyBody(mode)));
        } else {
            section(c, out, c.getString(R.string.help_sec_screen_title),
                    c.getString(R.string.help_sec_screen_body));
        }

        // 4. Where the pictures come from: the cloud library, the device, a phone, the editor.
        section(c, out, c.getString(R.string.help_sec_library_title),
                c.getString(R.string.help_library_common) + "\n"
                        + c.getString(handoff ? R.string.help_library_handoff
                                              : R.string.help_library_clock));
        if(!handoff) {
            section(c, out, c.getString(R.string.help_sec_filters_title),
                    c.getString(R.string.help_sec_filters_body_clock));
        }
        section(c, out, c.getString(R.string.help_sec_local_title),
                c.getString(handoff ? R.string.help_local_handoff : R.string.help_local_clock));
        section(c, out, c.getString(R.string.help_sec_phone_title),
                c.getString(R.string.help_sec_phone_body, c.getString(handoff
                        ? R.string.help_phone_where_handoff : R.string.help_phone_where_clock)));
        String fit = c.getString(R.string.help_sec_fit_body);
        if(handoff) fit += "\n" + c.getString(R.string.help_fit_handoff_note);
        section(c, out, c.getString(R.string.help_sec_fit_title), fit);

        // 5. The folder mirror, on the three modes that run one (see FolderMirror).
        if(mode == OperatingMode.GWM || mode == OperatingMode.JETOUR || mode == OperatingMode.LEOPARD) {
            int sectionName = mode == OperatingMode.GWM ? R.string.gwm_section
                    : mode == OperatingMode.JETOUR ? R.string.jetour_section
                    : R.string.leopard_dash_section;
            int target = mode == OperatingMode.GWM ? R.string.help_mirror_target_gwm
                    : mode == OperatingMode.JETOUR ? R.string.help_mirror_target_jetour
                    : R.string.help_mirror_target_leopard;
            // Leopard has no clock screen, so its mirror rides the picker's open instead of the
            // 5-minute timer (LeopardPickerActivity.kickFolderMirror).
            int when = mode == OperatingMode.LEOPARD ? R.string.help_mirror_when_picker
                    : R.string.help_mirror_when_clock;
            String name = c.getString(sectionName);
            section(c, out, c.getString(R.string.help_sec_mirror_title, name),
                    c.getString(R.string.help_sec_mirror_body, name, c.getString(target),
                            c.getString(when)));
        }

        // 6. Settings, updates, support.
        String settings = c.getString(handoff ? R.string.help_settings_handoff
                                              : R.string.help_settings_clock);
        if(mode == OperatingMode.LYNKCO) settings += "\n" + c.getString(R.string.help_settings_lynkco_note);
        section(c, out, c.getString(R.string.help_sec_settings_title), settings);
        section(c, out, c.getString(R.string.help_sec_updates_title),
                c.getString(R.string.help_sec_updates_body, c.getString(handoff
                        ? R.string.help_updates_dot_handoff : R.string.help_updates_dot_clock)));
        section(c, out, c.getString(R.string.help_sec_support_title),
                c.getString(R.string.help_sec_support_body, Support.PHONE_DISPLAY));
        return out;
    }

    /** The label the mode carries everywhere else in the app (radio buttons, chips). */
    private static int modeName(int mode) {
        switch(mode) {
            case OperatingMode.FSE:     return R.string.mode_fse;
            case OperatingMode.LEOPARD: return R.string.mode_leopard;
            case OperatingMode.DENZA:   return R.string.mode_denza;
            case OperatingMode.ICAR03T: return R.string.mode_icar03t;
            case OperatingMode.GWM:     return R.string.mode_gwm;
            case OperatingMode.LYNKCO:  return R.string.mode_lynkco;
            case OperatingMode.JETOUR:  return R.string.mode_jetour;
            default:                    return R.string.mode_normal;
        }
    }

    private static int modeBody(int mode) {
        switch(mode) {
            case OperatingMode.FSE:     return R.string.help_mode_fse;
            case OperatingMode.LEOPARD: return R.string.help_mode_leopard;
            case OperatingMode.DENZA:   return R.string.help_mode_denza;
            case OperatingMode.ICAR03T: return R.string.help_mode_icar03t;
            case OperatingMode.GWM:     return R.string.help_mode_gwm;
            case OperatingMode.LYNKCO:  return R.string.help_mode_lynkco;
            case OperatingMode.JETOUR:  return R.string.help_mode_jetour;
            default:                    return R.string.help_mode_normal;
        }
    }

    /** How the hand-off ends on this car: Android's screen, the Flyme theme app, or a folder. */
    private static int applyBody(int mode) {
        if(mode == OperatingMode.LYNKCO) return R.string.help_apply_lynkco;
        if(mode == OperatingMode.ICAR03T) return R.string.help_apply_icar03t;
        return R.string.help_apply_leopard;   // Leopard and Denza: the WallpaperManager family
    }

    /** A gold heading in the dialog's own title colour, then the body. Sections are blank-line separated. */
    private static void section(Context c, SpannableStringBuilder out, CharSequence title, CharSequence body) {
        if(out.length() > 0) out.append("\n\n");
        int start = out.length();
        out.append(title);
        int end = out.length();
        out.setSpan(new StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new RelativeSizeSpan(1.1f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new ForegroundColorSpan(ContextCompat.getColor(c, R.color.gold)), start, end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.append("\n").append(body);
    }
}
