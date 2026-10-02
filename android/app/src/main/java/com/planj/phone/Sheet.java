package com.planj.phone;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.function.Consumer;

/**
 * planj's bottom sheet, used for every confirmation, choice and quick edit in place of the
 * system's grey dialog: an optional outlined icon, a title in the display face, a line of
 * explanation, then the app's own buttons or rows.
 */
final class Sheet {
    /** One row in a choice sheet. */
    static final class Option {
        final int icon;
        final String label;
        final boolean danger;
        final Runnable run;

        Option(int icon, String label, boolean danger, Runnable run) {
            this.icon = icon;
            this.label = label;
            this.danger = danger;
            this.run = run;
        }
    }

    private final Activity a;
    private final Dialog dialog;
    private final View root;

    private Sheet(Activity a, int icon, String title, String message) {
        this.a = a;
        dialog = new Dialog(a);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        root = a.getLayoutInflater().inflate(R.layout.sheet, null, false);
        dialog.setContentView(root);
        Window w = dialog.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setWindowAnimations(R.style.SheetAnimation);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.6f);
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        ImageView iv = root.findViewById(R.id.sheet_icon);
        if (icon != 0) {
            iv.setImageResource(icon);
            iv.setImageTintList(android.content.res.ColorStateList.valueOf(a.getColor(R.color.text)));
            iv.setVisibility(View.VISIBLE);
        }
        ((TextView) root.findViewById(R.id.sheet_title)).setText(title);
        TextView msg = root.findViewById(R.id.sheet_message);
        if (message != null && !message.isEmpty()) {
            msg.setText(message);
            msg.setVisibility(View.VISIBLE);
        }
        root.findViewById(R.id.sheet_no).setOnClickListener(v -> dialog.dismiss());
    }

    /** Asks before doing something; a danger action gets the red button. */
    static void confirm(Activity a, int icon, String title, String message, String yes, boolean danger, Runnable onYes) {
        confirm(a, icon, title, message, yes, "Cancel", danger, onYes);
    }

    static void confirm(Activity a, int icon, String title, String message, String yes, String no, boolean danger, Runnable onYes) {
        Sheet s = new Sheet(a, icon, title, message);
        Button y = s.root.findViewById(R.id.sheet_yes);
        y.setText(yes);
        if (danger) {
            y.setBackgroundResource(R.drawable.btn_danger);
            y.setTextColor(0xFFFFFFFF);
        }
        y.setOnClickListener(v -> {
            s.dialog.dismiss();
            onYes.run();
        });
        ((Button) s.root.findViewById(R.id.sheet_no)).setText(no);
        s.dialog.show();
    }

    /** A short list of things to do with something, as rows. */
    static void choose(Activity a, String title, String subtitle, Option... options) {
        Sheet s = new Sheet(a, 0, title, subtitle);
        s.root.findViewById(R.id.sheet_yes).setVisibility(View.GONE);
        LinearLayout list = s.root.findViewById(R.id.sheet_options);
        list.setVisibility(View.VISIBLE);
        for (Option o : options) {
            ListRow row = new ListRow(a);
            row.setIcon(o.icon);
            row.setTitle(o.label);
            if (o.danger) row.setTint(a.getColor(R.color.bad));
            row.setOnClickListener(v -> {
                s.dialog.dismiss();
                o.run.run();
            });
            list.addView(row);
        }
        s.dialog.show();
    }

    /** A sheet holding any view (a mood picker, say). Returns the dialog, to close it when done. */
    static Dialog custom(Activity a, String title, String subtitle, View content) {
        Sheet s = new Sheet(a, 0, title, subtitle);
        s.root.findViewById(R.id.sheet_yes).setVisibility(View.GONE);
        LinearLayout list = s.root.findViewById(R.id.sheet_options);
        list.setVisibility(View.VISIBLE);
        list.addView(content);
        s.dialog.show();
        return s.dialog;
    }

    /** A quick edit of one short piece of text, with a live count against its limit. */
    static void input(Activity a, int icon, String title, String message, String current, String hint, int max,
                      Consumer<String> onSave) {
        Sheet s = new Sheet(a, icon, title, message);
        s.root.findViewById(R.id.sheet_input).setVisibility(View.VISIBLE);
        EditText field = s.root.findViewById(R.id.sheet_field);
        TextView count = s.root.findViewById(R.id.sheet_count);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        field.setHint(hint);
        field.setText(current);
        field.setSelection(field.getText().length());
        Runnable counted = () -> count.setText(field.getText().length() + " / " + max);
        counted.run();
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence x, int b, int c, int d) {}
            public void onTextChanged(CharSequence x, int b, int c, int d) {}
            public void afterTextChanged(Editable e) { counted.run(); }
        });
        Button y = s.root.findViewById(R.id.sheet_yes);
        y.setText("Save");
        Runnable save = () -> {
            s.dialog.dismiss();
            onSave.accept(field.getText().toString().trim());
        };
        y.setOnClickListener(v -> save.run());
        field.setOnEditorActionListener((v, id, ev) -> {
            if (id == EditorInfo.IME_ACTION_DONE) {
                save.run();
                return true;
            }
            return false;
        });
        s.dialog.setOnShowListener(d -> {
            field.requestFocus();
            s.dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        });
        s.dialog.show();
    }
}
