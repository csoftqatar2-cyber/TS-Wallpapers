package systems.sieber.fsclock;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

/**
 * Warm Aurora dialog whose complete view hierarchy, including its buttons, belongs to the app.
 *
 * <p>This intentionally wraps a plain {@link Dialog}. It never asks AlertDialog/AlertController
 * to create a framework button bar, so the button backgrounds are measured and drawn inside the
 * ordinary Button views that own them.</p>
 */
public final class AuroraDialog implements DialogInterface {

    public static final int BUTTON_POSITIVE = DialogInterface.BUTTON_POSITIVE;
    public static final int BUTTON_NEGATIVE = DialogInterface.BUTTON_NEGATIVE;
    public static final int BUTTON_NEUTRAL = DialogInterface.BUTTON_NEUTRAL;

    private final Context context;
    private final Dialog dialog;
    private final DialogLayout root;
    private final TextView titleView;
    private final TextView messageView;
    private final ScrollView messageScroll;
    private final FrameLayout content;
    private final ButtonFlowLayout buttonFlow;
    private Button positiveButton;
    private Button negativeButton;
    private Button neutralButton;
    private DialogInterface.OnDismissListener onDismissListener;

    private AuroraDialog(Builder builder) {
        context = builder.context;
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        root = new DialogLayout(context);
        root.setBackgroundResource(R.drawable.dialog_panel_bg);
        root.setPaddingRelative(dp(52), dp(38), dp(52), dp(32));
        LayoutInflater.from(context).inflate(R.layout.dialog_aurora, root, true);
        dialog.setContentView(root);

        Window window = dialog.getWindow();
        if(window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        titleView = root.findViewById(R.id.aurora_dialog_title);
        messageView = root.findViewById(android.R.id.message);
        messageScroll = root.findViewById(R.id.aurora_dialog_message_scroll);
        content = root.findViewById(R.id.aurora_dialog_content);
        FrameLayout buttonHost = root.findViewById(R.id.aurora_dialog_buttons);
        buttonFlow = new ButtonFlowLayout(context);
        buttonHost.addView(buttonFlow, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        bindText(titleView, builder.title);
        bindText(messageView, builder.message);
        messageScroll.setVisibility(messageView.getVisibility());

        View customView = builder.view;
        if(builder.items != null) {
            customView = buildList(builder.items, builder.checkedItem, builder.singleChoice,
                    builder.itemListener);
        }
        if(customView != null) {
            if(customView.getParent() instanceof ViewGroup) {
                ((ViewGroup) customView.getParent()).removeView(customView);
            }
            content.setVisibility(View.VISIBLE);
            content.addView(customView);
        }

        // Logical order mirrors cleanly in RTL: neutral, negative, positive in LTR becomes
        // positive, negative, neutral visually in RTL.
        neutralButton = addButton(builder.neutralText, builder.neutralListener, BUTTON_NEUTRAL,
                false);
        negativeButton = addButton(builder.negativeText, builder.negativeListener, BUTTON_NEGATIVE,
                false);
        positiveButton = addButton(builder.positiveText, builder.positiveListener, BUTTON_POSITIVE,
                true);
        buttonHost.setVisibility(buttonFlow.getChildCount() == 0 ? View.GONE : View.VISIBLE);

        dialog.setCancelable(builder.cancelable);
        dialog.setCanceledOnTouchOutside(builder.cancelable);
        setOnDismissListener(builder.onDismissListener);
    }

    private void bindText(TextView view, CharSequence text) {
        boolean visible = !TextUtils.isEmpty(text);
        view.setVisibility(visible ? View.VISIBLE : View.GONE);
        if(visible) view.setText(text);
    }

    private View buildList(final CharSequence[] labels, int checkedItem, final boolean singleChoice,
                           final DialogInterface.OnClickListener listener) {
        final ListView list = new ListView(context);
        list.setDivider(new ColorDrawable(ContextCompat.getColor(context, R.color.aurora_border)));
        list.setDividerHeight(dp(1));
        list.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        list.setChoiceMode(singleChoice ? ListView.CHOICE_MODE_SINGLE : ListView.CHOICE_MODE_NONE);
        list.setAdapter(new ArrayAdapter<CharSequence>(context, 0, labels) {
            @Override
            public View getView(int position, @Nullable View convertView, ViewGroup parent) {
                TextView row;
                if(singleChoice) {
                    RadioButton radio = convertView instanceof RadioButton
                            ? (RadioButton) convertView : new RadioButton(context);
                    radio.setButtonDrawable(R.drawable.dialog_radio);
                    radio.setClickable(false);
                    radio.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                    row = radio;
                } else {
                    row = convertView != null && !(convertView instanceof RadioButton)
                            ? (TextView) convertView : new TextView(context);
                    row.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
                }
                row.setText(getItem(position));
                row.setTextColor(ContextCompat.getColor(context, R.color.aurora_text));
                row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                row.setMinHeight(dp(52));
                row.setPaddingRelative(dp(16), dp(10), dp(16), dp(10));
                if(row instanceof RadioButton) {
                    ((RadioButton) row).setChecked(list.isItemChecked(position));
                }
                return row;
            }
        });
        if(singleChoice && checkedItem >= 0 && checkedItem < labels.length) {
            list.setItemChecked(checkedItem, true);
        }
        list.setOnItemClickListener((parent, view, position, id) -> {
            if(listener != null) listener.onClick(AuroraDialog.this, position);
            if(!singleChoice && isShowing()) dismiss();
        });
        return list;
    }

    private Button addButton(CharSequence text, final DialogInterface.OnClickListener listener,
                             final int which, boolean primary) {
        if(TextUtils.isEmpty(text)) return null;
        Button button = new Button(context);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setGravity(Gravity.CENTER);
        button.setMinWidth(dp(104));
        button.setMinimumWidth(dp(104));
        button.setMinHeight(dp(52));
        button.setMinimumHeight(dp(52));
        button.setPaddingRelative(dp(24), dp(8), dp(24), dp(8));
        button.setTextColor(primary ? 0xFF1A1204
                : ContextCompat.getColor(context, R.color.aurora_text));
        button.setBackgroundResource(primary
                ? R.drawable.dialog_btn_primary : R.drawable.dialog_btn_secondary);
        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) button.setStateListAnimator(null);
        button.setOnClickListener(v -> {
            if(listener != null) listener.onClick(AuroraDialog.this, which);
            if(isShowing()) dismiss();
        });
        buttonFlow.addView(button, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(52)));
        return button;
    }

    /** Show and return the same live object, matching AlertDialog.Builder.show(). */
    public AuroraDialog show() {
        dialog.show();
        Window window = dialog.getWindow();
        if(window != null) {
            int screenWidth = context.getResources().getDisplayMetrics().widthPixels;
            int screenHeight = context.getResources().getDisplayMetrics().heightPixels;
            boolean landscape = screenWidth > screenHeight;
            int target = Math.round(screenWidth * (landscape ? 0.50f : 0.70f));
            target = Math.max(dp(320), Math.min(target, screenWidth - dp(16)));
            window.setLayout(target, WindowManager.LayoutParams.WRAP_CONTENT);
        }
        return this;
    }

    @Override
    public void dismiss() {
        dialog.dismiss();
    }

    @Override
    public void cancel() {
        dialog.cancel();
    }

    public boolean isShowing() {
        return dialog.isShowing();
    }

    public Button getButton(int which) {
        if(which == BUTTON_POSITIVE) return positiveButton;
        if(which == BUTTON_NEGATIVE) return negativeButton;
        if(which == BUTTON_NEUTRAL) return neutralButton;
        return null;
    }

    public Window getWindow() {
        return dialog.getWindow();
    }

    public Context getContext() {
        return context;
    }

    public <T extends View> T findViewById(int id) {
        return dialog.findViewById(id);
    }

    public void setOnDismissListener(@Nullable DialogInterface.OnDismissListener listener) {
        onDismissListener = listener;
        dialog.setOnDismissListener(d -> {
            if(onDismissListener != null) onDismissListener.onDismiss(AuroraDialog.this);
        });
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /** Builder-compatible surface used by the mechanically migrated call sites. */
    public static final class Builder {
        private final Context context;
        private CharSequence title;
        private CharSequence message;
        private View view;
        private CharSequence positiveText;
        private CharSequence negativeText;
        private CharSequence neutralText;
        private DialogInterface.OnClickListener positiveListener;
        private DialogInterface.OnClickListener negativeListener;
        private DialogInterface.OnClickListener neutralListener;
        private DialogInterface.OnDismissListener onDismissListener;
        private boolean cancelable = true;
        private CharSequence[] items;
        private int checkedItem = -1;
        private boolean singleChoice;
        private DialogInterface.OnClickListener itemListener;

        public Builder(Context context) {
            this.context = context;
        }

        public Builder setTitle(int titleId) { return setTitle(context.getText(titleId)); }
        public Builder setTitle(CharSequence title) { this.title = title; return this; }
        public Builder setMessage(int messageId) { return setMessage(context.getText(messageId)); }
        public Builder setMessage(CharSequence message) { this.message = message; return this; }
        public Builder setView(View view) { this.view = view; return this; }

        public Builder setPositiveButton(int textId, DialogInterface.OnClickListener listener) {
            return setPositiveButton(context.getText(textId), listener);
        }
        public Builder setPositiveButton(CharSequence text, DialogInterface.OnClickListener listener) {
            positiveText = text; positiveListener = listener; return this;
        }
        public Builder setNegativeButton(int textId, DialogInterface.OnClickListener listener) {
            return setNegativeButton(context.getText(textId), listener);
        }
        public Builder setNegativeButton(CharSequence text, DialogInterface.OnClickListener listener) {
            negativeText = text; negativeListener = listener; return this;
        }
        public Builder setNeutralButton(int textId, DialogInterface.OnClickListener listener) {
            return setNeutralButton(context.getText(textId), listener);
        }
        public Builder setNeutralButton(CharSequence text, DialogInterface.OnClickListener listener) {
            neutralText = text; neutralListener = listener; return this;
        }
        public Builder setCancelable(boolean cancelable) { this.cancelable = cancelable; return this; }
        public Builder setOnDismissListener(DialogInterface.OnDismissListener listener) {
            onDismissListener = listener; return this;
        }
        public Builder setItems(CharSequence[] labels, DialogInterface.OnClickListener listener) {
            items = labels; itemListener = listener; singleChoice = false; checkedItem = -1; return this;
        }
        public Builder setSingleChoiceItems(CharSequence[] labels, int checked,
                                            DialogInterface.OnClickListener listener) {
            items = labels; checkedItem = checked; itemListener = listener; singleChoice = true;
            return this;
        }
        public AuroraDialog create() { return new AuroraDialog(this); }
        public AuroraDialog show() { return create().show(); }
    }

    /**
     * Measures the body only after reserving the title and complete button area. A large custom
     * view therefore scrolls or shrinks inside its allocation instead of pushing actions below
     * the bottom edge. The cap is 90% of the display unless a caller sets an explicit height.
     */
    private static final class DialogLayout extends ViewGroup {
        private final int displayCap;

        DialogLayout(Context context) {
            super(context);
            displayCap = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.90f);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            if(MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) width = dp(560);
            int innerWidth = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int childWidth = MeasureSpec.makeMeasureSpec(innerWidth, MeasureSpec.EXACTLY);

            View title = getChildAt(0);
            View body = getChildAt(1);
            View buttons = getChildAt(2);
            measureVisible(title, childWidth,
                    MeasureSpec.makeMeasureSpec(displayCap, MeasureSpec.AT_MOST));
            measureVisible(buttons, childWidth,
                    MeasureSpec.makeMeasureSpec(displayCap, MeasureSpec.AT_MOST));

            int chrome = getPaddingTop() + getPaddingBottom()
                    + measuredHeightWithMargins(title) + measuredHeightWithMargins(buttons);
            int parentSize = MeasureSpec.getSize(heightMeasureSpec);
            int limit = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED
                    ? displayCap : Math.min(displayCap, parentSize);
            int bodyLimit = Math.max(0, limit - chrome);
            if(MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                    && body.getVisibility() != GONE) {
                MarginLayoutParams lp = (MarginLayoutParams) body.getLayoutParams();
                int bodyWidth = MeasureSpec.makeMeasureSpec(Math.max(0,
                        innerWidth - lp.leftMargin - lp.rightMargin), MeasureSpec.EXACTLY);
                body.measure(bodyWidth, MeasureSpec.makeMeasureSpec(Math.max(0,
                        bodyLimit - lp.topMargin - lp.bottomMargin), MeasureSpec.EXACTLY));
            } else {
                measureVisible(body, childWidth,
                        MeasureSpec.makeMeasureSpec(bodyLimit, MeasureSpec.AT_MOST));
            }

            int desired = chrome + measuredHeightWithMargins(body);
            int height = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.EXACTLY
                    ? parentSize : Math.min(desired, limit);
            setMeasuredDimension(resolveSize(width, widthMeasureSpec), height);
        }

        private void measureVisible(View child, int widthSpec, int heightSpec) {
            if(child.getVisibility() == GONE) return;
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            int w = MeasureSpec.makeMeasureSpec(Math.max(0,
                    MeasureSpec.getSize(widthSpec) - lp.leftMargin - lp.rightMargin),
                    MeasureSpec.EXACTLY);
            int h = getChildMeasureSpec(heightSpec, lp.topMargin + lp.bottomMargin, lp.height);
            child.measure(w, h);
        }

        private int measuredHeightWithMargins(View child) {
            if(child.getVisibility() == GONE) return 0;
            MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
            return child.getMeasuredHeight() + lp.topMargin + lp.bottomMargin;
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int y = getPaddingTop();
            int childLeft = getPaddingLeft();
            int childRight = right - left - getPaddingRight();
            for(int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if(child.getVisibility() == GONE) continue;
                MarginLayoutParams lp = (MarginLayoutParams) child.getLayoutParams();
                y += lp.topMargin;
                child.layout(childLeft + lp.leftMargin, y,
                        childRight - lp.rightMargin, y + child.getMeasuredHeight());
                y += child.getMeasuredHeight() + lp.bottomMargin;
            }
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }

        @Override protected LayoutParams generateDefaultLayoutParams() {
            return new MarginLayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        }
        @Override public LayoutParams generateLayoutParams(AttributeSet attrs) {
            return new MarginLayoutParams(getContext(), attrs);
        }
        @Override protected LayoutParams generateLayoutParams(LayoutParams source) {
            return new MarginLayoutParams(source);
        }
        @Override protected boolean checkLayoutParams(LayoutParams p) {
            return p instanceof MarginLayoutParams;
        }
    }

    /** One line when it fits; otherwise one complete, safely measured action per line. */
    private static final class ButtonFlowLayout extends ViewGroup {
        private final int gap;
        private boolean stacked;

        ButtonFlowLayout(Context context) {
            super(context);
            gap = Math.round(10 * context.getResources().getDisplayMetrics().density);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
            int totalWidth = 0;
            int maxHeight = 0;
            for(int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                child.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(dp(52), MeasureSpec.EXACTLY));
                totalWidth += child.getMeasuredWidth();
                if(i > 0) totalWidth += gap;
                maxHeight = Math.max(maxHeight, child.getMeasuredHeight());
            }
            stacked = totalWidth > available;
            int height = stacked && getChildCount() > 0
                    ? getChildCount() * maxHeight + (getChildCount() - 1) * gap : maxHeight;
            setMeasuredDimension(resolveSize(width, widthMeasureSpec),
                    resolveSize(height + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int width = right - left;
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            if(stacked) {
                int y = getPaddingTop();
                for(int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    int x = rtl ? getPaddingLeft()
                            : width - getPaddingRight() - child.getMeasuredWidth();
                    child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
                    y += child.getMeasuredHeight() + gap;
                }
                return;
            }

            int total = 0;
            for(int i = 0; i < getChildCount(); i++) {
                total += getChildAt(i).getMeasuredWidth();
                if(i > 0) total += gap;
            }
            int x = rtl ? getPaddingLeft() + total : width - getPaddingRight() - total;
            for(int i = 0; i < getChildCount(); i++) {
                View child = getChildAt(i);
                if(rtl) {
                    x -= child.getMeasuredWidth();
                    child.layout(x, getPaddingTop(), x + child.getMeasuredWidth(),
                            getPaddingTop() + child.getMeasuredHeight());
                    x -= gap;
                } else {
                    child.layout(x, getPaddingTop(), x + child.getMeasuredWidth(),
                            getPaddingTop() + child.getMeasuredHeight());
                    x += child.getMeasuredWidth() + gap;
                }
            }
        }

        private int dp(int value) {
            return Math.round(value * getResources().getDisplayMetrics().density);
        }

        @Override protected LayoutParams generateDefaultLayoutParams() {
            return new LayoutParams(LayoutParams.WRAP_CONTENT, dp(52));
        }
    }
}
