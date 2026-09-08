package systems.sieber.fsclock;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.core.graphics.Insets;

/**
 * The small "v7.16 (192)" stamp in the physical bottom-right corner of every main screen —
 * the wallpaper/clock screen, the hand-off picker and Settings — the way THABTHABA STORE
 * shows its build. It exists so a photo of a car's screen, or a glance in the workshop, says
 * which build the car runs without opening Settings → Updates.
 *
 * The label itself is {@code @+id/textViewVersionCorner} in each layout, anchored with
 * {@code right|bottom} (not {@code end}: end lands on the left in Arabic, and the corner must
 * be the same one on every car). Quiet grey, 11sp, not clickable, so it never steals a touch.
 */
final class BuildStamp {

    private BuildStamp() { }

    /** What the stamp says: {@code v<versionName> (<versionCode>)}. */
    static String label() {
        return "v" + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")";
    }

    /** Fill the corner label under {@code root}, if that layout carries one. */
    static TextView bind(View root) {
        TextView view = root == null ? null : root.findViewById(R.id.textViewVersionCorner);
        if(view != null) view.setText(label());
        return view;
    }

    static TextView bind(Activity activity) {
        return bind(activity.getWindow().getDecorView());
    }

    /**
     * Keep the stamp clear of the system bars. The activities that show it inset a SIBLING
     * container and return CONSUMED, so the label — a later sibling — never receives the insets
     * itself; the listener that consumes them hands them over here instead. The base 8dp margin
     * from the layout is preserved under the bar.
     */
    static void inset(View label, Insets in) {
        if(label == null || in == null) return;
        ViewGroup.LayoutParams lp = label.getLayoutParams();
        if(!(lp instanceof ViewGroup.MarginLayoutParams)) return;
        ViewGroup.MarginLayoutParams mlp = (ViewGroup.MarginLayoutParams) lp;
        int base = Math.round(8 * label.getResources().getDisplayMetrics().density);
        mlp.rightMargin = base + in.right;
        mlp.bottomMargin = base + in.bottom;
        label.setLayoutParams(mlp);
    }
}
