package com.bigsinger.tvminesweeper.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bigsinger.tvminesweeper.tv.KeyHandler;

/** A remote-first modal used for difficulty, help, confirmation and results. */
public final class DifficultyDialog extends Dialog {
    /** Receives the selected row or cancellation. */
    public interface Listener {
        void onSelected(int position);
        void onClosed();
    }

    private static final int ACCENT = 0xff38e1c8;
    private final Listener listener;
    private final KeyHandler keys;
    private final TextView[] rows;
    private final float scale;
    private int selected;

    /** Builds a compact centered card and forwards every remote key through KeyHandler. */
    public DifficultyDialog(Context context, String title, String subtitle, String[] options,
            String hint, int initial, KeyHandler keys, Listener listener) {
        super(context);
        this.listener = listener;
        this.keys = keys;
        scale = Math.max(0.45f, context.getResources().getDisplayMetrics().heightPixels / 1080f);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setCancelable(false);
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setClipToPadding(false);
        panel.setClipChildren(false);
        panel.setPadding(px(42), px(31), px(42), px(28));
        panel.setBackground(background(0xff111d29, 0xff304857, 22));
        TextView heading = label(title, 35, 0xffe6edf3, true);
        heading.setGravity(Gravity.CENTER);
        panel.addView(heading, new LinearLayout.LayoutParams(-1, px(58)));
        TextView sub = label(subtitle, 23, 0xff9aacbc, false);
        sub.setGravity(Gravity.CENTER);
        sub.setPadding(0, 0, 0, px(20));
        panel.addView(sub, new LinearLayout.LayoutParams(-1, -2));
        rows = new TextView[options.length];
        for (int i = 0; i < rows.length; i++) {
            final int position = i;
            rows[i] = label(options[i], 27, 0xffe6edf3, true);
            rows[i].setGravity(Gravity.CENTER_VERTICAL);
            rows[i].setPadding(px(24), 0, px(20), 0);
            rows[i].setFocusable(false);
            rows[i].setSoundEffectsEnabled(false);
            rows[i].setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View view) {
                    selected = position;
                    redrawRows();
                    confirm();
                }
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, px(70));
            params.topMargin = px(10);
            panel.addView(rows[i], params);
        }
        if (hint != null && !hint.isEmpty()) {
            TextView footer = label(hint, 21, 0xff8da4b6, false);
            footer.setGravity(Gravity.CENTER);
            footer.setPadding(0, px(26), 0, 0);
            panel.addView(footer, new LinearLayout.LayoutParams(-1, -2));
        }
        setContentView(panel);
        selected = Math.max(0, Math.min(rows.length - 1, initial));
        redrawRows();
        setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override public void onDismiss(DialogInterface dialog) {
                DifficultyDialog.this.listener.onClosed();
            }
        });
        Window window = getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.8f;
            window.setAttributes(attributes);
        }
    }

    /** Route keys before focused children and preserve the opening MENU press's state. */
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        return keys.dispatch(event) || super.dispatchKeyEvent(event);
    }

    @Override public void show() {
        super.show();
        Window window = getWindow();
        if (window != null) {
            window.setLayout(Math.min(px(990), (int) (getContext().getResources().getDisplayMetrics().widthPixels * 0.88f)),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    /** Moves the highlighted row without changing the underlying game cursor. */
    public void move(int direction) {
        selected = Math.max(0, Math.min(rows.length - 1, selected + direction));
        redrawRows();
    }

    /** Activates the current option immediately (modal OK is never double-clicked). */
    public void confirm() {
        listener.onSelected(selected);
    }

    /** Updates a settings row after a sound toggle without dismissing the menu. */
    public void setOption(int index, String value) {
        if (index >= 0 && index < rows.length) { rows[index].setText(value); }
    }

    private void redrawRows() {
        for (int i = 0; i < rows.length; i++) {
            boolean active = i == selected;
            rows[i].setBackground(background(active ? 0xff16433f : 0xff101923,
                    active ? ACCENT : 0xff263745, 11));
            rows[i].setTextColor(active ? 0xffeffffb : 0xffcbd9e4);
            rows[i].setTranslationX(active ? px(5) : 0);
        }
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView label = new TextView(getContext());
        label.setText(value);
        label.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale);
        label.setTextColor(color);
        label.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        label.setFocusable(false);
        return label;
    }

    private GradientDrawable background(int color, int border, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(px(radius));
        result.setStroke(Math.max(1, px(2)), border);
        return result;
    }

    private int px(int value) { return Math.round(value * scale); }
}
