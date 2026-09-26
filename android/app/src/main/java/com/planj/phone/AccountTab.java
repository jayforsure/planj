package com.planj.phone;

import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import java.util.regex.Pattern;

/** Sign in / create account. Both derive the same key locally; "create" only adds a confirm step. */
final class AccountTab {
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final int MIN_PASSWORD = 8;

    private final MainActivity a;
    private final View root;
    private final TextView state, phoneDetail, pcDetail;
    private final View cardSignin, cardDevices, dotPc;

    private final TextView segSignin, segCreate, authTitle, authBlurb, authError;
    private final EditText email, password, confirm;
    private final TextView emailHint, passwordHint, confirmHint, strengthLabel;
    private final View strengthRow, confirmBlock, progress;
    private final View[] strength = new View[4];
    private final ImageButton eye;
    private final Button submit;

    private boolean creating = false, showing = false, busy = false;
    private boolean emailTouched = false, passwordTouched = false, confirmTouched = false;

    AccountTab(MainActivity a, ViewGroup container) {
        this.a = a;
        root = a.getLayoutInflater().inflate(R.layout.tab_account, container, false);
        container.addView(root);
        state = root.findViewById(R.id.account_state);
        cardSignin = root.findViewById(R.id.card_signin);
        cardDevices = root.findViewById(R.id.card_devices);
        phoneDetail = root.findViewById(R.id.phone_detail);
        pcDetail = root.findViewById(R.id.pc_detail);
        dotPc = root.findViewById(R.id.dot_pc);

        segSignin = root.findViewById(R.id.seg_signin);
        segCreate = root.findViewById(R.id.seg_create);
        authTitle = root.findViewById(R.id.auth_title);
        authBlurb = root.findViewById(R.id.auth_blurb);
        authError = root.findViewById(R.id.auth_error);
        email = root.findViewById(R.id.email);
        password = root.findViewById(R.id.password);
        confirm = root.findViewById(R.id.confirm);
        emailHint = root.findViewById(R.id.email_hint);
        passwordHint = root.findViewById(R.id.password_hint);
        confirmHint = root.findViewById(R.id.confirm_hint);
        strengthRow = root.findViewById(R.id.strength_row);
        strengthLabel = root.findViewById(R.id.strength_label);
        strength[0] = root.findViewById(R.id.str1);
        strength[1] = root.findViewById(R.id.str2);
        strength[2] = root.findViewById(R.id.str3);
        strength[3] = root.findViewById(R.id.str4);
        confirmBlock = root.findViewById(R.id.confirm_block);
        progress = root.findViewById(R.id.auth_progress);
        eye = root.findViewById(R.id.btn_eye);
        submit = root.findViewById(R.id.btn_signin);

        segSignin.setOnClickListener(v -> setMode(false));
        segCreate.setOnClickListener(v -> setMode(true));
        eye.setOnClickListener(v -> togglePassword());
        submit.setOnClickListener(v -> submit());
        root.findViewById(R.id.btn_signout).setOnClickListener(v -> {
            RelaySync.signOut(a);
            a.toast("Signed out — this phone keeps its data, and stops syncing");
            a.refresh();
        });

        TextWatcher revalidate = new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            public void onTextChanged(CharSequence s, int st, int b, int c) {}
            public void afterTextChanged(Editable s) { validate(); }
        };
        email.addTextChangedListener(revalidate);
        password.addTextChangedListener(revalidate);
        confirm.addTextChangedListener(revalidate);
        // Errors appear once a field has been left, not while typing the first character.
        email.setOnFocusChangeListener((v, has) -> { if (!has) { emailTouched = true; validate(); } });
        password.setOnFocusChangeListener((v, has) -> { if (!has) { passwordTouched = true; validate(); } });
        confirm.setOnFocusChangeListener((v, has) -> { if (!has) { confirmTouched = true; validate(); } });

        TextView.OnEditorActionListener done = (v, id, ev) -> {
            boolean enter = ev != null && ev.getKeyCode() == KeyEvent.KEYCODE_ENTER && ev.getAction() == KeyEvent.ACTION_DOWN;
            if (id == EditorInfo.IME_ACTION_DONE || enter) {
                if (submit.isEnabled()) submit();
                return true;
            }
            return false;
        };
        password.setOnEditorActionListener(done);
        confirm.setOnEditorActionListener(done);

        setMode(false);
    }

    View view() {
        return root;
    }

    // ---- mode / validation -------------------------------------------------

    private void setMode(boolean create) {
        creating = create;
        segSignin.setSelected(!create);
        segCreate.setSelected(create);
        authTitle.setText(create ? "Create your account" : "Welcome back");
        authBlurb.setText(create
                ? "One email and password for every device. Your key is derived from them on this phone."
                : "Use the same email and password as on your other devices.");
        submit.setText(create ? "Create account" : "Sign in");
        confirmBlock.setVisibility(create ? View.VISIBLE : View.GONE);
        strengthRow.setVisibility(create ? View.VISIBLE : View.GONE);
        password.setImeOptions(create ? EditorInfo.IME_ACTION_NEXT : EditorInfo.IME_ACTION_DONE);
        authError.setVisibility(View.GONE);
        validate();
    }

    private void validate() {
        String e = email.getText().toString().trim();
        String p = password.getText().toString();
        String c = confirm.getText().toString();

        String emailErr = e.isEmpty() ? "Enter your email" : !EMAIL.matcher(e).matches() ? "That does not look like an email address" : null;
        String passErr = p.isEmpty() ? "Enter your password"
                : creating && p.length() < MIN_PASSWORD ? "Use at least " + MIN_PASSWORD + " characters" : null;
        String confirmErr = !creating ? null : c.isEmpty() ? "Type your password again" : !c.equals(p) ? "Passwords do not match" : null;

        showHint(email, emailHint, emailTouched ? emailErr : null);
        showHint(password, passwordHint, passwordTouched ? passErr : null);
        showHint(confirm, confirmHint, confirmTouched ? confirmErr : null);
        if (creating) showStrength(p);

        submit.setEnabled(!busy && emailErr == null && passErr == null && confirmErr == null);
    }

    private void showHint(EditText field, TextView hint, String err) {
        hint.setText(err);
        hint.setVisibility(err == null ? View.GONE : View.VISIBLE);
        field.setBackgroundResource(err == null ? R.drawable.field_bg : R.drawable.field_bg_error);
    }

    /** Rough strength: length plus character variety. Not a policy, just a nudge. */
    private void showStrength(String p) {
        int score = 0;
        if (p.length() >= MIN_PASSWORD) score++;
        if (p.length() >= 12) score++;
        boolean lower = false, upper = false, digit = false, other = false;
        for (char ch : p.toCharArray()) {
            if (Character.isLowerCase(ch)) lower = true;
            else if (Character.isUpperCase(ch)) upper = true;
            else if (Character.isDigit(ch)) digit = true;
            else other = true;
        }
        int kinds = (lower ? 1 : 0) + (upper ? 1 : 0) + (digit ? 1 : 0) + (other ? 1 : 0);
        if (kinds >= 2) score++;
        if (kinds >= 3 && p.length() >= 10) score++;
        if (p.isEmpty()) score = 0;

        int color = score <= 1 ? R.color.bad : score == 2 ? R.color.warn : R.color.accent;
        for (int i = 0; i < 4; i++) {
            strength[i].getBackground().mutate().setTint(a.getColor(i < score ? color : R.color.border));
        }
        strengthLabel.setText(p.isEmpty() ? "" : score <= 1 ? "Weak" : score == 2 ? "Okay" : score == 3 ? "Good" : "Strong");
        strengthLabel.setTextColor(a.getColor(p.isEmpty() ? R.color.muted : color));
    }

    private void togglePassword() {
        showing = !showing;
        int sel = password.getSelectionEnd();
        password.setTransformationMethod(showing ? HideReturnsTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
        password.setSelection(sel);
        eye.setImageResource(showing ? R.drawable.ic_eye_off : R.drawable.ic_eye);
        eye.setContentDescription(showing ? "Hide password" : "Show password");
    }

    // ---- submit ------------------------------------------------------------

    private void submit() {
        emailTouched = passwordTouched = confirmTouched = true;
        validate();
        if (!submit.isEnabled()) return;
        String e = email.getText().toString(), p = password.getText().toString();
        setBusy(true);
        new Thread(() -> {
            String error = null;
            try {
                String code = AccountKey.pairingCode(e, p);
                RelaySync.pair(a, code);
                RelaySync.setSignedInEmail(a, AccountKey.normaliseEmail(e));
            } catch (IllegalArgumentException ex) {
                error = ex.getMessage();
            } catch (Exception ex) {
                error = "Could not sign in: " + ex.getMessage();
            }
            String err = error;
            a.runOnUiThread(() -> {
                setBusy(false);
                if (err != null) {
                    authError.setText(err);
                    authError.setVisibility(View.VISIBLE);
                    return;
                }
                password.setText("");
                confirm.setText("");
                emailTouched = passwordTouched = confirmTouched = false;
                a.toast(creating ? "Account created on this phone" : "Signed in");
                a.syncInBackground();
                a.refresh();
            });
        }).start();
    }

    private void setBusy(boolean b) {
        busy = b;
        submit.setText(b ? "" : creating ? "Create account" : "Sign in");
        progress.setVisibility(b ? View.VISIBLE : View.GONE);
        for (EditText f : new EditText[]{email, password, confirm}) f.setEnabled(!b);
        eye.setEnabled(!b);
        if (b) {
            authError.setVisibility(View.GONE);
            InputMethodManager imm = a.getSystemService(InputMethodManager.class);
            if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
        }
        validate();
    }

    // ---- signed-in state ---------------------------------------------------

    void refresh() {
        String signedIn = RelaySync.signedInEmail(a);
        boolean in = signedIn != null || RelaySync.pairedCode(a) != null;
        cardSignin.setVisibility(in ? View.GONE : View.VISIBLE);
        cardDevices.setVisibility(in ? View.VISIBLE : View.GONE);
        if (!in) {
            state.setText("Not signed in — this phone records for itself only.");
            return;
        }
        state.setText(signedIn != null ? signedIn : "Joined with an older key — sign out, then sign in with your account on both devices.");
        long synced = RelaySync.lastSyncMs(a);
        phoneDetail.setText(synced == 0 ? "not synced yet" : "synced " + Fmt.clock(synced));
        long confirmed = RelaySync.confirmedMs(a);
        String error = RelaySync.lastError(a);
        if (error != null) {
            dotPc.getBackground().mutate().setTint(a.getColor(R.color.warn));
            pcDetail.setText("retrying");
        } else if (confirmed > 0) {
            dotPc.getBackground().mutate().setTint(a.getColor(R.color.ok));
            pcDetail.setText("confirmed " + Fmt.clock(confirmed));
        } else if (RelaySync.confirmationOverdue(a)) {
            dotPc.getBackground().mutate().setTint(a.getColor(R.color.warn));
            pcDetail.setText("not answering — signed in there?");
        } else {
            dotPc.getBackground().mutate().setTint(a.getColor(R.color.idle));
            pcDetail.setText("waiting");
        }
    }
}
