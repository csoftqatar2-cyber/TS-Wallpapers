package systems.sieber.fsclock;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.content.res.ResourcesCompat;

/**
 * The in-app manual: one dialog, sections chosen at runtime by {@link OperatingMode}.
 *
 * A Leopard car and an Others car are two different products sharing one APK (see the note on
 * OperatingMode), so one fixed help page would describe a clock screen to a driver who has never
 * seen one, or a picker to a driver who has never left the slideshow. The cards are therefore
 * assembled per mode: the mode's own meaning first, then whichever of the picker / clock screen
 * this car actually shows, the sources, the editor, the folder mirror where one runs, then the
 * list of every mode, Settings, Updates, and the contact card.
 *
 * Look: TS Link's help sheet (owner's call 2026-09-09, spec «زر وحوار المساعدة - TS Link»).
 * A centred dialog, 92% wide up to 720dp and at most 620dp tall, opaque #171208 with a 24dp
 * radius and a faint gold border; the title and the button row are fixed and only the body
 * scrolls. The body is CARDS: a numbered "method card" per feature, a "mode card" with an icon
 * per operating mode this build offers, and the «لأي مساعدة» contact card with the WhatsApp QR
 * last. One «موافق» button at the end of the row, AuroraDialog's primary style, just dismisses.
 *
 * Reached from the picker's top bar in the hand-off modes and from the Settings header in every
 * mode. The text lives in {@code help_*} strings (English in values/, Arabic in values-ar/);
 * every sentence there describes what the code does today, so a screen change means a string
 * change — nothing here is invented.
 */
final class HelpDialog {

    private HelpDialog() { }

    // Sheet chrome, to the spec's numbers.
    private static final int SHEET_BG = 0xFF171208;
    private static final int SHEET_BORDER = 0x33FFD27A;
    private static final int TITLE = 0xFFF5EEE4;
    private static final int GOLD = 0xFFFFD27A;
    private static final int BODY = 0xFFB0A48F;
    private static final int CARD_FILL = 0x991C160E;
    private static final int CARD_BORDER = 0x59FF9A3D;
    private static final int ICON_BOX = 0x21FF9A3D;
    private static final int BUTTON_TEXT = 0xFF1A1204;

    /** Show the manual for the mode this car is in. */
    static void show(Activity activity) {
        SharedPreferences prefs = activity.getSharedPreferences(
                BaseSettingsActivity.SHARED_PREF_DOMAIN, Context.MODE_PRIVATE);
        final int mode = OperatingMode.get(prefs);
        final Context c = activity;
        final float d = c.getResources().getDisplayMetrics().density;
        final boolean rtl = LocaleHelper.LANG_ARABIC.equals(LocaleHelper.resolved(c));
        final int dir = rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR;

        final Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        // ---- the sheet: title / scrolling body / button row ----
        SheetLayout sheet = new SheetLayout(c);
        sheet.setOrientation(LinearLayout.VERTICAL);
        sheet.setLayoutDirection(dir);
        sheet.setPadding(dp(d, 24), dp(d, 24), dp(d, 24), dp(d, 24));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(SHEET_BG);
        bg.setCornerRadius(24 * d);
        bg.setStroke(dp(d, 1), SHEET_BORDER);
        sheet.setBackground(bg);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) sheet.setElevation(12 * d);

        TextView title = text(c, c.getString(R.string.help_title), 24, TITLE, rtl);
        title.setTypeface(font(c, R.font.cairo_bold, Typeface.BOLD));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.bottomMargin = dp(d, 10);
        sheet.addView(title, tlp);

        LinearLayout column = new LinearLayout(c);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setLayoutDirection(dir);
        build(c, mode, column, rtl);

        ScrollView scroll = new ScrollView(c);
        scroll.setLayoutDirection(dir);
        scroll.setFillViewport(false);
        scroll.setVerticalScrollBarEnabled(true);
        scroll.addView(column, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        // wrap_content + weight: the body takes what it needs and is the one child that shrinks
        // when the sheet hits its 620dp cap (LinearLayout hands the negative excess to the weight).
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        sheet.addView(scroll, slp);

        // «موافق» alone, at the END of the row (left in Arabic) — AuroraDialog's primary button.
        Button ok = new Button(c);
        ok.setText(R.string.ok);
        ok.setAllCaps(false);
        ok.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        ok.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        ok.setGravity(Gravity.CENTER);
        ok.setMinWidth(dp(d, 104));
        ok.setMinimumWidth(dp(d, 104));
        ok.setMinHeight(dp(d, 52));
        ok.setMinimumHeight(dp(d, 52));
        ok.setPaddingRelative(dp(d, 24), dp(d, 8), dp(d, 24), dp(d, 8));
        ok.setTextColor(BUTTON_TEXT);
        ok.setBackgroundResource(R.drawable.dialog_btn_primary);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) ok.setStateListAnimator(null);
        ok.setOnClickListener(v -> dialog.dismiss());
        FrameLayout row = new FrameLayout(c);
        row.setLayoutDirection(dir);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(d, 52));
        blp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
        row.addView(ok, blp);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(d, 18);
        sheet.addView(row, rlp);

        dialog.setContentView(sheet);
        dialog.setCancelable(true);
        dialog.setCanceledOnTouchOutside(true);
        Window window = dialog.getWindow();
        if(window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.70f);   // scrim #B3000000
        }
        dialog.show();
        if(window != null) {
            int screenWidth = c.getResources().getDisplayMetrics().widthPixels;
            int width = Math.min(Math.round(screenWidth * 0.92f), dp(d, 720));
            window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    // ------------------------------------------------------------------ content

    /** Every card, in order, for one mode value. */
    static void build(Context c, int mode, LinearLayout column, boolean rtl) {
        final boolean handoff = OperatingMode.isHandoffMode(mode);
        int n = 1;

        // 1. What this mode is — the one thing that differs most between cars.
        methodCard(c, column, n++, c.getString(R.string.help_sec_mode_title, c.getString(modeName(mode))),
                c.getString(R.string.help_sub_mode), c.getString(modeBody(mode)), rtl);

        // (Owner's rule, 2026-09-08: the help never talks about activation — the program is already
        // activated by the time anyone reads it.)

        // 2. The screen this car actually shows, and how a wallpaper is set on it.
        if(handoff) {
            methodCard(c, column, n++, c.getString(R.string.help_sec_picker_title),
                    c.getString(R.string.help_sub_picker), c.getString(R.string.help_sec_picker_body), rtl);
            methodCard(c, column, n++, c.getString(R.string.help_sec_filters_title),
                    c.getString(R.string.help_sub_filters), c.getString(R.string.help_sec_filters_body_handoff), rtl);
            methodCard(c, column, n++, c.getString(R.string.help_sec_apply_title),
                    c.getString(R.string.help_sub_apply), c.getString(applyBody(mode)), rtl);
        } else {
            methodCard(c, column, n++, c.getString(R.string.help_sec_screen_title),
                    c.getString(R.string.help_sub_screen), c.getString(R.string.help_sec_screen_body), rtl);
        }

        // 3. Where the pictures come from: the cloud library, the device, a phone, the editor.
        methodCard(c, column, n++, c.getString(R.string.help_sec_library_title),
                c.getString(R.string.help_sub_library),
                c.getString(R.string.help_library_common) + "\n"
                        + c.getString(handoff ? R.string.help_library_handoff : R.string.help_library_clock), rtl);
        if(!handoff) {
            methodCard(c, column, n++, c.getString(R.string.help_sec_filters_title),
                    c.getString(R.string.help_sub_filters), c.getString(R.string.help_sec_filters_body_clock), rtl);
        }
        methodCard(c, column, n++, c.getString(R.string.help_sec_local_title),
                c.getString(R.string.help_sub_local),
                c.getString(handoff ? R.string.help_local_handoff : R.string.help_local_clock), rtl);
        methodCard(c, column, n++, c.getString(R.string.help_sec_phone_title),
                c.getString(R.string.help_sub_phone),
                c.getString(R.string.help_sec_phone_body, c.getString(handoff
                        ? R.string.help_phone_where_handoff : R.string.help_phone_where_clock)), rtl);
        String fit = c.getString(R.string.help_sec_fit_body);
        if(handoff) fit += "\n" + c.getString(R.string.help_fit_handoff_note);
        methodCard(c, column, n++, c.getString(R.string.help_sec_fit_title),
                c.getString(R.string.help_sub_fit), fit, rtl);

        // 4. The folder mirror, on the three modes that run one (see FolderMirror).
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
            methodCard(c, column, n++, c.getString(R.string.help_sec_mirror_title, name),
                    c.getString(R.string.help_sub_mirror),
                    c.getString(R.string.help_sec_mirror_body, name, c.getString(target), c.getString(when)), rtl);
        }

        // 5. Every mode this build offers, one card each, the current one tagged.
        sectionHeading(c, column, c.getString(R.string.help_sec_modes_title),
                c.getString(R.string.help_modes_intro), rtl);
        int[] modes = { OperatingMode.NORMAL, OperatingMode.FSE, OperatingMode.LEOPARD,
                OperatingMode.DENZA, OperatingMode.ICAR03T, OperatingMode.GWM,
                OperatingMode.LYNKCO, OperatingMode.JETOUR };
        for(int m : modes) {
            String label = c.getString(modeName(m));
            if(m == mode) label += " · " + c.getString(R.string.help_mode_this_car);
            modeCard(c, column, modeIcon(m), label, c.getString(modeDesc(m)), rtl);
        }

        // 6. Settings, updates.
        String settings = c.getString(handoff ? R.string.help_settings_handoff : R.string.help_settings_clock);
        if(mode == OperatingMode.LYNKCO) settings += "\n" + c.getString(R.string.help_settings_lynkco_note);
        methodCard(c, column, 0, c.getString(R.string.help_sec_settings_title),
                c.getString(R.string.help_sub_settings), settings, rtl);
        methodCard(c, column, 0, c.getString(R.string.help_sec_updates_title),
                c.getString(R.string.help_sub_updates),
                c.getString(R.string.help_sec_updates_body, c.getString(handoff
                        ? R.string.help_updates_dot_handoff : R.string.help_updates_dot_clock)), rtl);

        // 7. «لأي مساعدة» — always the last card.
        contactCard(c, column, rtl);
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

    /** The one-line description the mode radio in Settings shows under each option. */
    private static int modeDesc(int mode) {
        switch(mode) {
            case OperatingMode.FSE:     return R.string.mode_fse_desc;
            case OperatingMode.LEOPARD: return R.string.mode_leopard_desc;
            case OperatingMode.DENZA:   return R.string.mode_denza_desc;
            case OperatingMode.ICAR03T: return R.string.mode_icar03t_desc;
            case OperatingMode.GWM:     return R.string.mode_gwm_desc;
            case OperatingMode.LYNKCO:  return R.string.mode_lynkco_desc;
            case OperatingMode.JETOUR:  return R.string.mode_jetour_desc;
            default:                    return R.string.mode_normal_desc;
        }
    }

    /** One glyph per kind of product: clock screen, wide window, hand-off, folder, theme app. */
    private static int modeIcon(int mode) {
        switch(mode) {
            case OperatingMode.FSE:     return R.drawable.ic_mode_wide_24dp;
            case OperatingMode.LEOPARD:
            case OperatingMode.DENZA:   return R.drawable.ic_mode_wallpaper_24dp;
            case OperatingMode.ICAR03T: return R.drawable.ic_mode_image_24dp;
            case OperatingMode.GWM:
            case OperatingMode.JETOUR:  return R.drawable.ic_mode_folder_24dp;
            case OperatingMode.LYNKCO:  return R.drawable.ic_mode_palette_24dp;
            default:                    return R.drawable.ic_mode_clock_24dp;
        }
    }

    /** How the hand-off ends on this car: Android's screen, the Flyme theme app, or a folder. */
    private static int applyBody(int mode) {
        if(mode == OperatingMode.LYNKCO) return R.string.help_apply_lynkco;
        if(mode == OperatingMode.ICAR03T) return R.string.help_apply_icar03t;
        return R.string.help_apply_leopard;   // Leopard and Denza: the WallpaperManager family
    }

    // ------------------------------------------------------------------ cards

    /**
     * A numbered feature card: «١ · title — sub» in gold, then the body. {@code number} 0 means
     * the card is not part of the numbered walk-through (Settings, Updates).
     */
    private static void methodCard(Context c, LinearLayout column, int number, String title,
                                   String sub, String body, boolean rtl) {
        final float d = c.getResources().getDisplayMetrics().density;
        LinearLayout card = card(c, rtl);
        card.setPadding(dp(d, 16), dp(d, 16), dp(d, 16), dp(d, 16));

        String heading = (number > 0 ? digits(number, rtl) + " · " : "") + title
                + (sub != null && !sub.isEmpty() ? " — " + sub : "");
        TextView t = text(c, heading, 16, GOLD, rtl);
        t.setTypeface(font(c, R.font.cairo_semibold, Typeface.BOLD));
        card.addView(t, match());

        TextView b = text(c, body, 14, BODY, rtl);
        b.setLineSpacing(0, 1.22f);
        LinearLayout.LayoutParams blp = match();
        blp.topMargin = dp(d, 6);
        card.addView(b, blp);

        column.addView(card, cardParams(d));
    }

    /** A card per operating mode: icon box at the start, name and one-line description after it. */
    private static void modeCard(Context c, LinearLayout column, int icon, String title,
                                 String desc, boolean rtl) {
        final float d = c.getResources().getDisplayMetrics().density;
        LinearLayout card = card(c, rtl);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(dp(d, 96));
        card.setPadding(dp(d, 16), dp(d, 12), dp(d, 16), dp(d, 12));

        FrameLayout box = new FrameLayout(c);
        GradientDrawable boxBg = new GradientDrawable();
        boxBg.setColor(ICON_BOX);
        boxBg.setCornerRadius(15 * d);
        box.setBackground(boxBg);
        ImageView glyph = new ImageView(c);
        glyph.setImageResource(icon);
        glyph.setColorFilter(GOLD);
        glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        FrameLayout.LayoutParams glp = new FrameLayout.LayoutParams(dp(d, 25), dp(d, 25));
        glp.gravity = Gravity.CENTER;
        box.addView(glyph, glp);
        card.addView(box, new LinearLayout.LayoutParams(dp(d, 48), dp(d, 48)));

        LinearLayout col = new LinearLayout(c);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        TextView t = text(c, title, 16, TITLE, rtl);
        t.setTypeface(font(c, R.font.cairo_semibold, Typeface.BOLD));
        col.addView(t, match());
        TextView b = text(c, desc, 13, BODY, rtl);
        LinearLayout.LayoutParams blp = match();
        blp.topMargin = dp(d, 2);
        col.addView(b, blp);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        clp.setMarginStart(dp(d, 12));
        card.addView(col, clp);

        column.addView(card, cardParams(d));
    }

    /** The heading over the mode cards, with its one-paragraph intro. */
    private static void sectionHeading(Context c, LinearLayout column, String title, String intro, boolean rtl) {
        final float d = c.getResources().getDisplayMetrics().density;
        TextView t = text(c, title, 16, TITLE, rtl);
        t.setTypeface(font(c, R.font.cairo_semibold, Typeface.BOLD));
        LinearLayout.LayoutParams tlp = match();
        tlp.topMargin = dp(d, 20);
        column.addView(t, tlp);
        if(intro != null && !intro.isEmpty()) {
            TextView b = text(c, intro, 14, BODY, rtl);
            LinearLayout.LayoutParams blp = match();
            blp.topMargin = dp(d, 4);
            column.addView(b, blp);
        }
    }

    /**
     * «لأي مساعدة», same shape as the controller's: heading, hint and the number on the start
     * side, a WhatsApp QR (the same wa.me link the activation screen and the Store use) on an
     * explicit white plate at the end — a QR on the dark theme cannot be scanned. The number is
     * written LTR so it never flips.
     */
    private static void contactCard(Context c, LinearLayout column, boolean rtl) {
        final float d = c.getResources().getDisplayMetrics().density;
        LinearLayout card = card(c, rtl);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(d, 16), dp(d, 16), dp(d, 16), dp(d, 16));

        LinearLayout textCol = new LinearLayout(c);
        textCol.setOrientation(LinearLayout.VERTICAL);
        textCol.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        TextView heading = text(c, c.getString(R.string.help_contact_title), 16, GOLD, rtl);
        heading.setTypeface(font(c, R.font.cairo_semibold, Typeface.BOLD));
        textCol.addView(heading, match());
        TextView hint = text(c, c.getString(R.string.help_contact_hint), 14, BODY, rtl);
        LinearLayout.LayoutParams hlp = match();
        hlp.topMargin = dp(d, 6);
        textCol.addView(hint, hlp);
        TextView number = text(c, Support.PHONE_DISPLAY, 20, TITLE, rtl);
        number.setTypeface(null, Typeface.BOLD);
        number.setTextDirection(View.TEXT_DIRECTION_LTR);
        LinearLayout.LayoutParams nlp = match();
        nlp.topMargin = dp(d, 6);
        textCol.addView(number, nlp);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tp.setMarginEnd(dp(d, 14));
        card.addView(textCol, tp);

        // The QR: bitmap generation may fail on an exotic unit — then the number alone remains.
        int narrow = c.getResources().getDisplayMetrics().widthPixels < 720 * d ? 180 : 208;
        int box = dp(d, narrow), pad = dp(d, 10);
        Bitmap bmp = null;
        try { bmp = QrCode.generate(Support.WHATSAPP_URL, box - 2 * pad); } catch (Throwable ignored) { bmp = null; }
        if(bmp != null) {
            ImageView qr = new ImageView(c);
            qr.setImageBitmap(bmp);
            qr.setContentDescription(c.getString(R.string.help_contact_title));
            GradientDrawable white = new GradientDrawable();
            white.setColor(0xFFFFFFFF);
            white.setCornerRadius(14 * d);
            qr.setBackground(white);
            qr.setPadding(pad, pad, pad, pad);
            card.addView(qr, new LinearLayout.LayoutParams(box, box));
        }

        column.addView(card, cardParams(d));
    }

    // ------------------------------------------------------------------ helpers

    /** The glass card every block sits on: 18dp radius, faint fill, 35% accent hairline. */
    private static LinearLayout card(Context c, boolean rtl) {
        final float d = c.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(CARD_FILL);
        bg.setCornerRadius(18 * d);
        bg.setStroke(dp(d, 1), CARD_BORDER);
        card.setBackground(bg);
        return card;
    }

    /** Cards are 12dp apart. */
    private static LinearLayout.LayoutParams cardParams(float d) {
        LinearLayout.LayoutParams lp = match();
        lp.topMargin = dp(d, 12);
        return lp;
    }

    private static LinearLayout.LayoutParams match() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    /**
     * A text view that lays out in the APP's language, not the head unit's locale: the app prints
     * Arabic on ROMs stuck in English, and a paragraph that opens with a Latin word (a mode name,
     * "GIF") would otherwise run LTR and ragged-left.
     */
    private static TextView text(Context c, CharSequence s, int sp, int color, boolean rtl) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        t.setTextDirection(rtl ? View.TEXT_DIRECTION_RTL : View.TEXT_DIRECTION_LTR);
        t.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        t.setGravity(Gravity.START);
        return t;
    }

    /** Cairo where it is available; the platform face with the same weight when it is not. */
    private static Typeface font(Context c, int fontRes, int style) {
        try {
            Typeface tf = ResourcesCompat.getFont(c, fontRes);
            if(tf != null) return tf;
        } catch (Throwable ignored) { }
        return Typeface.defaultFromStyle(style);
    }

    /** «١» in Arabic, «1» otherwise — the card number in the reader's own digits. */
    private static String digits(int n, boolean rtl) {
        String s = String.valueOf(n);
        if(!rtl) return s;
        StringBuilder out = new StringBuilder(s.length());
        for(char ch : s.toCharArray()) {
            out.append(ch >= '0' && ch <= '9' ? (char) ('٠' + (ch - '0')) : ch);
        }
        return out.toString();
    }

    private static int dp(float density, int value) {
        return Math.round(value * density);
    }

    /**
     * The sheet is at most 620dp tall (and never more than 90% of the display): the title and the
     * button row keep their size and the weighted ScrollView between them takes the cut.
     */
    private static final class SheetLayout extends LinearLayout {
        private final int cap;

        SheetLayout(Context context) {
            super(context);
            float d = context.getResources().getDisplayMetrics().density;
            cap = Math.min(Math.round(620 * d),
                    Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.90f));
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int mode = MeasureSpec.getMode(heightMeasureSpec);
            int size = MeasureSpec.getSize(heightMeasureSpec);
            int limit = mode == MeasureSpec.UNSPECIFIED ? cap : Math.min(cap, size);
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }
}
