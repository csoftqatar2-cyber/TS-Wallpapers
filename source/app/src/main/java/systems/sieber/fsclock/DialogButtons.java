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

    /** Dress every button this dialog actually has. Safe on a dialog with none. */
    static void apply(AlertDialog dlg) {
        if(dlg == null) return;
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
        b.setBackgroundResource(bg);
        b.setTextColor(textColor);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        b.setPadding(px(24, d), px(10, d), px(24, d), px(10, d));
        b.setMinWidth(px(104, d));
        b.setMinimumWidth(px(104, d));
        // An explicit height, not a minimum: the bar was sizing the view and the background
        // apart, and a fixed height is what makes them one number. 52dp is a finger target on a
        // head unit and still leaves the bar short enough not to run off a small dialog.
        // The bar paints its children into a slot shorter than the button it laid out — the
        // measurement that finally showed it: a 69px-tall button, 63px of it drawn, the missing
        // 6px exactly the top. A 6dp corner is 9px on this screen, so two thirds of the arc fell
        // in the clipped strip and the button read as square-topped. Letting the bar draw
        // outside its own bounds is what puts the corner back.
        if(b.getParent() instanceof ViewGroup) {
            ViewGroup bar = (ViewGroup) b.getParent();
            bar.setClipChildren(false);
            bar.setClipToPadding(false);
            if(bar.getParent() instanceof ViewGroup) {
                ViewGroup outer = (ViewGroup) bar.getParent();
                outer.setClipChildren(false);
                outer.setClipToPadding(false);
            }
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

    private static int px(int dp, float density) {
        return Math.round(dp * density);
    }
}
