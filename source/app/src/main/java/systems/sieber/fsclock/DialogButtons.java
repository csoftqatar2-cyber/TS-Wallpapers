package systems.sieber.fsclock;

import android.app.AlertDialog;
import android.content.Context;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;

import androidx.core.content.ContextCompat;

/**
 * Re-dresses a dialog's buttons after it is shown.
 *
 * The styles alone were not enough. {@code AppDialog.Button} does give the platform button bar
 * our background, and the buttons come out gold — but the bar hands each one a background whose
 * bounds are taller than the view it lands in, anchored to the bottom, so the top of the
 * rounded rectangle falls outside the drawn area and the button appears sliced off along its
 * top edge. A stroke test made it plain: left, right and bottom edges drew, the top edge did
 * not exist. Neither a smaller corner radius, nor {@code minHeight 0dp}, nor more room above
 * the bar changed it — the clip travels with the button.
 *
 * Setting the background on the live view is what fixes it: a View sets its background's bounds
 * from its own measured size, so after this runs the drawable and the view agree and all four
 * corners are drawn. It has to happen after {@code show()}, because that is when the platform
 * creates the buttons at all.
 */
final class DialogButtons {

    private DialogButtons() {}

    /**
     * Dress every button this dialog actually has, and return it so this can wrap a builder.
     *
     * Works whether the dialog has been shown yet or not, because both spellings exist in this
     * codebase: {@code apply(builder.show())} dresses immediately, and {@code apply(b.create())}
     * waits for the show. The distinction is not cosmetic — the platform does not create the
     * buttons at all until the dialog is shown, so dressing early would silently do nothing.
     */
    static AlertDialog apply(final AlertDialog dlg) {
        if(dlg == null) return null;
        if(dlg.isShowing()) { dress(dlg); return dlg; }
        dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener() {
            @Override
            public void onShow(android.content.DialogInterface d) { dress(dlg); }
        });
        return dlg;
    }

    private static void dress(AlertDialog dlg) {
        Context c = dlg.getContext();
        dress(dlg.getButton(AlertDialog.BUTTON_POSITIVE), c,
                R.drawable.dialog_btn_primary, 0xFF1A1204);
        dress(dlg.getButton(AlertDialog.BUTTON_NEGATIVE), c,
                R.drawable.dialog_btn_secondary, ContextCompat.getColor(c, R.color.aurora_text));
        dress(dlg.getButton(AlertDialog.BUTTON_NEUTRAL), c,
                R.drawable.dialog_btn_secondary, ContextCompat.getColor(c, R.color.aurora_text));
    }

    private static void dress(Button b, Context c, int bg, int textColor) {
        if(b == null) return;
        float d = c.getResources().getDisplayMetrics().density;
        b.setBackground(background(c, bg == R.drawable.dialog_btn_primary));
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        b.setPadding(px(24, d), px(10, d), px(24, d), px(10, d));
        b.setMinWidth(px(104, d));
        b.setMinimumWidth(px(104, d));
        // An explicit height, not a minimum: the bar was sizing the view and the background
        // apart, and a fixed height is what makes them one number. 52dp is a finger target on a
        // head unit and still leaves the bar short enough not to run off a small dialog.
        // The button row, and ONLY the button row, is allowed to draw outside itself.
        //
        // It hands each button a slot ~6px shorter than the button it laid out, and the missing
        // strip is the top one — exactly where a 12dp corner lives, so the button came out square
        // along its top edge. Letting the row overflow puts the corner back.
        //
        // Its PARENT must keep clipping. Freeing that one too was a real regression: the dialog's
        // scrolling content (the wallpaper grid, the QR panel) then drew outside its own box as
        // well and ran underneath the buttons, which looked like the buttons were slicing the
        // content in half.
        if(b.getParent() instanceof ViewGroup) {
            ViewGroup bar = (ViewGroup) b.getParent();
            bar.setClipChildren(false);
            bar.setClipToPadding(false);
        }
        ViewGroup.LayoutParams lp = b.getLayoutParams();
        if(lp != null) {
            lp.height = px(52, d);
            if(lp instanceof LinearLayout.LayoutParams) {
                LinearLayout.LayoutParams ll = (LinearLayout.LayoutParams) lp;
                ll.setMargins(px(10, d), px(6, d), px(10, d), px(10, d));
            }
            b.setLayoutParams(lp);
        }
    }

    /**
     * The button's background, built here rather than inflated.
     *
     * The XML selector drew its left, right and bottom edges and never its top one — proven with
     * a temporary stroke — so the rounded top corners never appeared no matter what the bar was
     * told about clipping. A GradientDrawable made in code carries no inset and no wrapper, and
     * takes its bounds from the view it is set on, so all four corners are drawn.
     */
    private static android.graphics.drawable.Drawable background(Context c, boolean primary) {
        float d = c.getResources().getDisplayMetrics().density;
        int fill = primary
                ? ContextCompat.getColor(c, R.color.colorAccent)
                : ContextCompat.getColor(c, R.color.aurora_card);
        android.graphics.drawable.StateListDrawable sl =
                new android.graphics.drawable.StateListDrawable();
        sl.addState(new int[]{ android.R.attr.state_pressed },
                shape(fill, d, true, primary, c));
        sl.addState(new int[]{ android.R.attr.state_focused },
                shape(fill, d, true, primary, c));
        sl.addState(new int[0], shape(fill, d, false, primary, c));
        return sl;
    }

    private static android.graphics.drawable.GradientDrawable shape(
            int fill, float d, boolean highlight, boolean primary, Context c) {
        android.graphics.drawable.GradientDrawable g =
                new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        g.setColor(fill);
        g.setCornerRadius(px(12, d));   // 12dp — a corner you can actually see on a head unit
        if(highlight) {
            g.setStroke(px(2, d), ContextCompat.getColor(c, R.color.aurora_text));
        } else if(!primary) {
            g.setStroke(px(1, d), ContextCompat.getColor(c, R.color.aurora_border));
        }
        return g;
    }

    private static int px(int dp, float density) {
        return Math.round(dp * density);
    }
}
