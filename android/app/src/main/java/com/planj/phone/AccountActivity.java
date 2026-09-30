package com.planj.phone;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.HideReturnsTransformationMethod;
import android.text.method.PasswordTransformationMethod;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Pattern;

/**
 * Every account page: welcome, sign in, create, verify, recovery code, forgot/reset, change
 * password, new recovery code, devices and delete. One activity, one page at a time, with
 * its own back stack, so each flow reads like a short guided journey.
 */
public class AccountActivity extends Activity {
    static final String EXTRA_SCREEN = "screen";

    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]{2,}$");
    private static final int MIN_PASSWORD = 8;
    private static final int RESEND_COOLDOWN_S = 60;

    private FrameLayout stage;
    private View back;
    private TextView step;
    private final Deque<String> stack = new ArrayDeque<>();
    private final Handler ui = new Handler(Looper.getMainLooper());

    // Flow state. The password lives here only while a flow is in progress.
    private String email, password, recovery;
    private AccountApi.Session session;
    private byte[] accountKey;
    private boolean verifyingAfterSignIn;
    private long resendAllowedAt;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_account);
        stage = findViewById(R.id.stage);
        back = findViewById(R.id.btn_back);
        step = findViewById(R.id.step);
        back.setOnClickListener(v -> onBackPressed());
        String first = getIntent().getStringExtra(EXTRA_SCREEN);
        show(first == null ? "welcome" : first);
    }

    @Override
    public void onBackPressed() {
        if ("recovery".equals(stack.peek())) return; // must be acknowledged, not skipped
        stack.pop();
        if (stack.isEmpty()) {
            finish();
            return;
        }
        render(stack.peek());
    }

    private void show(String name) {
        stack.push(name);
        render(name);
    }

    private void replace(String name) {
        stack.clear();
        show(name);
    }

    private void render(String name) {
        int layout = getResources().getIdentifier("screen_" + name, "layout", getPackageName());
        View v = LayoutInflater.from(this).inflate(layout, stage, false);
        stage.removeAllViews();
        stage.addView(v);
        v.startAnimation(android.view.animation.AnimationUtils.loadAnimation(this, R.anim.fade_up));
        back.setVisibility(!"recovery".equals(name) ? View.VISIBLE : View.INVISIBLE); // back closes the page when it is the first
        step.setText("");
        switch (name) {
            case "welcome": bindWelcome(v); break;
            case "signin": bindSignIn(v); break;
            case "create": bindCreate(v); break;
            case "verify": bindVerify(v); break;
            case "recovery": bindRecovery(v); break;
            case "forgot": bindForgot(v); break;
            case "reset": bindReset(v); break;
            case "recover": bindRecover(v); break;
            case "password": bindPassword(v); break;
            case "newrecovery": bindNewRecovery(v); break;
            case "devices": bindDevices(v); break;
            case "device": bindDevice(v); break;
            case "device_name": bindDeviceName(v); break;
            case "device_signout": bindDeviceSignOut(v); break;
            case "name": bindName(v); break;
            case "privacy": break; // words only
            case "delete": bindDelete(v); break;
        }
    }

    // ---- pages -------------------------------------------------------------

    private void bindWelcome(View v) {
        v.findViewById(R.id.btn_create).setOnClickListener(x -> show("create"));
        v.findViewById(R.id.btn_signin).setOnClickListener(x -> show("signin"));
    }

    private void bindSignIn(View v) {
        Form f = new Form(v);
        EditText em = f.edit(R.id.email), pw = f.edit(R.id.password);
        if (email != null) em.setText(email);
        f.eye(R.id.password_eye, pw);
        f.validate = () -> f.ok(f.email(em, R.id.email_hint) & f.present(pw, R.id.password_hint, "Enter your password"));
        f.watch(em, pw);
        f.submitOnDone(pw);
        v.findViewById(R.id.btn_forgot).setOnClickListener(x -> { email = em.getText().toString().trim(); show("forgot"); });
        v.findViewById(R.id.btn_create).setOnClickListener(x -> { email = em.getText().toString().trim(); replace("create"); });
        f.onSubmit = () -> {
            email = em.getText().toString().trim();
            password = pw.getText().toString();
            f.busy(true);
            run(() -> {
                try {
                    session = AccountApi.login(email, password, AccountStore.deviceName());
                } catch (AccountApi.Refused r) {
                    if (r.status != 403) throw r;
                    AccountApi.resend(email);
                    verifyingAfterSignIn = true;
                    ui.post(() -> show("verify"));
                    return;
                }
                finishSignIn();
            }, f);
        };
    }

    private void bindCreate(View v) {
        Form f = new Form(v);
        EditText em = f.edit(R.id.email), pw = f.edit(R.id.password), cf = f.edit(R.id.confirm);
        if (email != null) em.setText(email);
        f.eye(R.id.password_eye, pw);
        f.validate = () -> {
            f.strength(pw.getText().toString());
            f.ok(f.email(em, R.id.email_hint) & f.password(pw, R.id.password_hint) & f.confirm(pw, cf, R.id.confirm_hint));
        };
        f.watch(em, pw, cf);
        f.submitOnDone(cf);
        v.findViewById(R.id.btn_signin).setOnClickListener(x -> { email = em.getText().toString().trim(); replace("signin"); });
        f.onSubmit = () -> {
            email = em.getText().toString().trim();
            password = pw.getText().toString();
            f.busy(true);
            run(() -> {
                AccountApi.register(email, password);
                verifyingAfterSignIn = false;
                resendAllowedAt = System.currentTimeMillis() + RESEND_COOLDOWN_S * 1000L;
                ui.post(() -> show("verify"));
            }, f);
        };
    }

    private void bindVerify(View v) {
        Form f = new Form(v);
        EditText code = f.edit(R.id.code);
        ((TextView) v.findViewById(R.id.blurb)).setText("Code sent to " + AccountCrypto.normaliseEmail(email));
        f.validate = () -> f.ok(code.getText().length() == 6);
        f.watch(code);
        f.submitOnDone(code);
        Button resend = v.findViewById(R.id.btn_resend);
        countdown(resend, "Resend code");
        resend.setOnClickListener(x -> {
            resend.setEnabled(false);
            run(() -> {
                AccountApi.resend(email);
                resendAllowedAt = System.currentTimeMillis() + RESEND_COOLDOWN_S * 1000L;
                ui.post(() -> { toast("Code sent again"); countdown(resend, "Resend code"); });
            }, f);
        });
        f.onSubmit = () -> {
            f.busy(true);
            run(() -> {
                session = AccountApi.verify(email, code.getText().toString(), AccountStore.deviceName());
                finishSignIn();
            }, f);
        };
        code.requestFocus();
    }

    /** After a session exists: open or create the keybox, then join this phone to the record. */
    private void finishSignIn() throws IOException {
        JSONObject kb = AccountApi.keybox(session.token);
        if (kb == null) {
            accountKey = AccountCrypto.newAccountKey();
            recovery = AccountCrypto.newRecoveryCode();
            AccountApi.putKeybox(session.token,
                    AccountCrypto.wrap(accountKey, AccountCrypto.wrapKey(email, password), AccountCrypto.AAD_PW),
                    AccountCrypto.wrap(accountKey, AccountCrypto.recoveryWrapKey(email, recovery), AccountCrypto.AAD_RC));
            AccountStore.signIn(this, session, accountKey, true);
            password = null;
            ui.post(() -> replace("recovery"));
            return;
        }
        accountKey = AccountCrypto.unwrap(kb.optString("pw"), AccountCrypto.wrapKey(email, password), AccountCrypto.AAD_PW);
        AccountStore.signIn(this, session, accountKey, !kb.optString("rc").isEmpty());
        password = null;
        ui.post(() -> { toast("Signed in as " + session.email); done(); });
    }

    private void bindRecovery(View v) {
        ((TextView) v.findViewById(R.id.code)).setText(recovery);
        CheckBox saved = v.findViewById(R.id.saved);
        Button done = v.findViewById(R.id.btn_submit);
        done.setEnabled(false);
        saved.setOnCheckedChangeListener((b, on) -> done.setEnabled(on));
        v.findViewById(R.id.btn_copy).setOnClickListener(x -> {
            ClipboardManager cm = getSystemService(ClipboardManager.class);
            ClipData clip = ClipData.newPlainText("planj recovery code", recovery);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                android.os.PersistableBundle extras = new android.os.PersistableBundle();
                extras.putBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            cm.setPrimaryClip(clip);
            toast("Copied — paste it into your password manager now");
        });
        done.setOnClickListener(x -> { recovery = null; done(); });
    }

    private void bindForgot(View v) {
        Form f = new Form(v);
        EditText em = f.edit(R.id.email);
        if (email != null) em.setText(email);
        f.validate = () -> f.ok(f.email(em, R.id.email_hint));
        f.watch(em);
        f.submitOnDone(em);
        f.onSubmit = () -> {
            email = em.getText().toString().trim();
            f.busy(true);
            run(() -> {
                AccountApi.forgot(email);
                resendAllowedAt = System.currentTimeMillis() + RESEND_COOLDOWN_S * 1000L;
                ui.post(() -> show("reset"));
            }, f);
        };
    }

    private void bindReset(View v) {
        Form f = new Form(v);
        EditText code = f.edit(R.id.code), pw = f.edit(R.id.password), cf = f.edit(R.id.confirm);
        ((TextView) v.findViewById(R.id.blurb)).setText("Code sent to " + AccountCrypto.normaliseEmail(email));
        f.eye(R.id.password_eye, pw);
        f.validate = () -> {
            f.strength(pw.getText().toString());
            f.ok((code.getText().length() == 6) & f.password(pw, R.id.password_hint) & f.confirm(pw, cf, R.id.confirm_hint));
        };
        f.watch(code, pw, cf);
        f.submitOnDone(cf);
        Button resend = v.findViewById(R.id.btn_resend);
        countdown(resend, "Resend code");
        resend.setOnClickListener(x -> {
            resend.setEnabled(false);
            run(() -> {
                AccountApi.forgot(email);
                resendAllowedAt = System.currentTimeMillis() + RESEND_COOLDOWN_S * 1000L;
                ui.post(() -> { toast("Code sent again"); countdown(resend, "Resend code"); });
            }, f);
        });
        f.onSubmit = () -> {
            password = pw.getText().toString();
            f.busy(true);
            run(() -> {
                session = AccountApi.reset(email, code.getText().toString(), password, AccountStore.deviceName());
                if (session.hasKeybox) {
                    ui.post(() -> replace("recover"));
                } else {
                    startFresh();
                }
            }, f);
        };
    }

    /** A brand-new data key and recovery code, stored and joined. */
    private void startFresh() throws IOException {
        accountKey = AccountCrypto.newAccountKey();
        recovery = AccountCrypto.newRecoveryCode();
        AccountApi.putKeybox(session.token,
                AccountCrypto.wrap(accountKey, AccountCrypto.wrapKey(email, password), AccountCrypto.AAD_PW),
                AccountCrypto.wrap(accountKey, AccountCrypto.recoveryWrapKey(email, recovery), AccountCrypto.AAD_RC));
        AccountStore.signIn(this, session, accountKey, true);
        password = null;
        ui.post(() -> replace("recovery"));
    }

    private void bindRecover(View v) {
        Form f = new Form(v);
        EditText rc = f.edit(R.id.recovery);
        f.validate = () -> f.ok(rc.getText().toString().replaceAll("[^0-9A-Za-z]", "").length() == 20);
        f.watch(rc);
        f.submitOnDone(rc);
        f.onSubmit = () -> {
            f.busy(true);
            run(() -> {
                JSONObject kb = AccountApi.keybox(session.token);
                if (kb == null || kb.optString("rc").isEmpty()) throw new IOException("This account has no recovery code on file");
                accountKey = AccountCrypto.unwrap(kb.optString("rc"), AccountCrypto.recoveryWrapKey(email, rc.getText().toString()), AccountCrypto.AAD_RC);
                recovery = AccountCrypto.newRecoveryCode(); // the old one was just typed in; rotate it
                AccountApi.putKeybox(session.token,
                        AccountCrypto.wrap(accountKey, AccountCrypto.wrapKey(email, password), AccountCrypto.AAD_PW),
                        AccountCrypto.wrap(accountKey, AccountCrypto.recoveryWrapKey(email, recovery), AccountCrypto.AAD_RC));
                AccountStore.signIn(this, session, accountKey, true);
                password = null;
                ui.post(() -> { toast("Your data key is back"); replace("recovery"); });
            }, f);
        };
        v.findViewById(R.id.btn_fresh).setOnClickListener(x -> Sheet.confirm(this, R.drawable.ic_key, "Start fresh?",
                "Uploads other devices already made cannot be read with a new key. Data on this phone is kept and re-sent.",
                "Start fresh", true, () -> { f.busy(true); run(this::startFresh, f); }));
    }

    private void bindPassword(View v) {
        Form f = new Form(v);
        EditText cur = f.edit(R.id.current), pw = f.edit(R.id.password), cf = f.edit(R.id.confirm);
        f.eye(R.id.current_eye, cur);
        f.eye(R.id.password_eye, pw);
        f.validate = () -> {
            f.strength(pw.getText().toString());
            f.ok(f.present(cur, R.id.current_hint, "Enter your current password") & f.password(pw, R.id.password_hint) & f.confirm(pw, cf, R.id.confirm_hint));
        };
        f.watch(cur, pw, cf);
        f.submitOnDone(cf);
        f.onSubmit = () -> {
            f.busy(true);
            String e = AccountStore.email(this);
            run(() -> {
                AccountApi.changePassword(AccountStore.token(this), e, cur.getText().toString(), pw.getText().toString(), AccountStore.accountKey(this));
                ui.post(() -> { toast("Password changed"); done(); });
            }, f);
        };
    }

    private void bindNewRecovery(View v) {
        Form f = new Form(v);
        EditText pw = f.edit(R.id.password);
        f.eye(R.id.password_eye, pw);
        f.validate = () -> f.ok(f.present(pw, R.id.password_hint, "Enter your password"));
        f.watch(pw);
        f.submitOnDone(pw);
        f.onSubmit = () -> {
            f.busy(true);
            String e = AccountStore.email(this), token = AccountStore.token(this), typed = pw.getText().toString();
            run(() -> {
                JSONObject kb = AccountApi.keybox(token);
                if (kb == null) throw new IOException("No key on file for this account");
                byte[] key = AccountCrypto.unwrap(kb.optString("pw"), AccountCrypto.wrapKey(e, typed), AccountCrypto.AAD_PW); // proves the password
                recovery = AccountCrypto.newRecoveryCode();
                AccountApi.putKeybox(token, kb.optString("pw"),
                        AccountCrypto.wrap(key, AccountCrypto.recoveryWrapKey(e, recovery), AccountCrypto.AAD_RC));
                AccountStore.setHasRecovery(this, true);
                ui.post(() -> replace("recovery"));
            }, f);
        };
    }

    private void bindDevices(View v) {
        Form f = new Form(v);
        LinearLayout list = v.findViewById(R.id.list);
        View progress = v.findViewById(R.id.progress);
        String token = AccountStore.token(this);
        run(() -> {
            JSONObject me = AccountApi.me(token);
            JSONArray devs = me.getJSONArray("devices");
            DeviceNames.save(this, devs);
            ui.post(() -> {
                progress.setVisibility(View.GONE);
                list.removeAllViews();
                others = devs.length() - 1;
                for (int i = 0; i < devs.length(); i++) {
                    JSONObject d = devs.optJSONObject(i);
                    if (d != null) list.addView(deviceRow(d, f));
                }
            });
        }, f);
    }

    /** The device being looked at, from the Devices list, and how many devices besides this phone. */
    private JSONObject device;
    private int others;

    /** "Now" within two minutes, else the local time. */
    private String active(JSONObject d) {
        if (d.optBoolean("this")) return "Now";
        long ms = DeviceNames.lastActive(this, d, others);
        if (ms == 0) return "Not yet";
        if (System.currentTimeMillis() - ms < 120_000) return "Now";
        return java.time.Instant.ofEpochMilli(ms).atZone(java.time.ZoneId.systemDefault())
                .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"));
    }

    /** One device as a standard row: its name, what it is, and when it was last active. */
    private View deviceRow(JSONObject d, Form f) {
        boolean thisOne = d.optBoolean("this");
        ListRow row = new ListRow(this);
        row.setIcon(thisOne ? R.drawable.ic_phone : R.drawable.ic_monitor);
        row.setTitle(DeviceNames.display(d));
        String act = active(d);
        row.setSubtitle(thisOne ? d.optString("name") + " · this phone"
                : d.optString("name") + (act.equals("Now") ? " · active now" : " · active " + act));
        row.setOnClickListener(x -> {
            device = d;
            show("device");
        });
        return row;
    }

    /** A device's own page: what it is, since when, and what can be done with it. */
    private void bindDevice(View v) {
        if (device == null) {
            replace("devices");
            return;
        }
        boolean thisOne = device.optBoolean("this");
        String name = DeviceNames.display(device), model = device.optString("name");
        ((ImageView) v.findViewById(R.id.device_icon)).setImageResource(thisOne ? R.drawable.ic_phone : R.drawable.ic_monitor);
        ((ImageView) v.findViewById(R.id.device_icon)).setImageTintList(android.content.res.ColorStateList.valueOf(getColor(R.color.text)));
        ((TextView) v.findViewById(R.id.title)).setText(name);
        ((TextView) v.findViewById(R.id.blurb)).setText(thisOne ? "This phone. Its name titles its section on Today."
                : "Signed in to your account. Its name titles its section on Today.");
        ListRow rowName = v.findViewById(R.id.row_name);
        rowName.setSubtitle(name);
        rowName.setOnClickListener(x -> show("device_name"));
        ListRow type = v.findViewById(R.id.row_type);
        type.setIcon(thisOne ? R.drawable.ic_phone : R.drawable.ic_monitor);
        type.setSubtitle(thisOne ? "Phone" : "Computer");
        ListRow m = v.findViewById(R.id.row_model);
        m.setTitle(thisOne ? "Model" : "Computer name");
        m.setSubtitle(model);
        ((ListRow) v.findViewById(R.id.row_since)).setSubtitle(localTime(device.optString("created")));
        ((ListRow) v.findViewById(R.id.row_seen)).setSubtitle(active(device));
        for (int id : new int[]{R.id.row_type, R.id.row_model, R.id.row_since, R.id.row_seen}) {
            v.findViewById(id).setClickable(false);
            v.findViewById(id).setBackground(null);
        }
        v.findViewById(R.id.signout_section).setVisibility(thisOne ? View.GONE : View.VISIBLE);
        v.findViewById(R.id.row_signout).setVisibility(thisOne ? View.GONE : View.VISIBLE);
        v.findViewById(R.id.row_signout).setOnClickListener(x -> show("device_signout"));
    }

    /** Naming a device: a proper form, saved to the account so every device shows the same name. */
    private void bindDeviceName(View v) {
        if (device == null) {
            replace("devices");
            return;
        }
        Form f = new Form(v);
        EditText field = f.edit(R.id.name);
        TextView count = v.findViewById(R.id.count);
        int index = device.optInt("index");
        String fallback = "Device " + (index + 1);
        ((TextView) v.findViewById(R.id.blurb)).setText("Shown on Today and on your other devices. Leave it empty and it is called " + fallback + ".");
        field.setHint(fallback);
        field.setText(device.optString("label"));
        field.setSelection(field.getText().length());
        f.validate = () -> {
            count.setText(field.getText().length() + " / 30");
            f.ok(f.hint(field, R.id.name_hint, field.getText().toString().trim().length() > 30 ? "Use 30 characters or fewer" : null));
        };
        f.watch(field);
        f.submitOnDone(field);
        f.onSubmit = () -> save(f, index, field.getText().toString().trim());
        v.findViewById(R.id.btn_default).setOnClickListener(x -> save(f, index, ""));
        field.requestFocus();
    }

    private void save(Form f, int index, String label) {
        f.busy(true);
        run(() -> {
            AccountApi.labelDevice(AccountStore.token(this), index, label);
            JSONArray devs = AccountApi.me(AccountStore.token(this)).getJSONArray("devices");
            DeviceNames.save(this, devs);
            for (int i = 0; i < devs.length(); i++) {
                if (devs.getJSONObject(i).optInt("index") == index) device = devs.getJSONObject(i);
            }
            ui.post(() -> {
                toast(label.isEmpty() ? "Back to " + DeviceNames.display(device) : "Saved as " + label);
                onBackPressed(); // to the device page, now under its new name
            });
        }, f);
    }

    /** Signing another device out: said plainly, with the red button, and a way to keep it. */
    private void bindDeviceSignOut(View v) {
        if (device == null) {
            replace("devices");
            return;
        }
        Form f = new Form(v);
        String name = DeviceNames.display(device);
        ((TextView) v.findViewById(R.id.title)).setText("Sign out " + name + "?");
        ((TextView) v.findViewById(R.id.blurb)).setText(device.optString("name") + " stops syncing straight away. What it has recorded stays on it, "
                + "and it can join again by signing in with your password.");
        ((Button) v.findViewById(R.id.btn_submit)).setText("Sign out " + name);
        v.findViewById(R.id.btn_cancel).setOnClickListener(x -> onBackPressed());
        f.onSubmit = () -> {
            f.busy(true);
            int index = device.optInt("index");
            run(() -> {
                AccountApi.revokeDevice(AccountStore.token(this), index);
                DeviceNames.refresh(this);
                ui.post(() -> {
                    toast(name + " is signed out");
                    device = null;
                    replace("devices");
                });
            }, f);
        };
        f.validate = () -> f.ok(true);
        f.validate.run();
    }

    /** Your own name, on this phone only. */
    private void bindName(View v) {
        Form f = new Form(v);
        EditText field = f.edit(R.id.name);
        TextView count = v.findViewById(R.id.count);
        field.setText(Avatar.name(this));
        field.setSelection(field.getText().length());
        f.validate = () -> {
            count.setText(field.getText().length() + " / 40");
            f.ok(f.present(field, R.id.name_hint, "Enter a name"));
        };
        f.watch(field);
        f.submitOnDone(field);
        f.onSubmit = () -> {
            Avatar.setName(this, field.getText().toString().trim());
            toast("Saved");
            done();
        };
        field.requestFocus();
    }

    private void bindDelete(View v) {
        Form f = new Form(v);
        EditText pw = f.edit(R.id.password);
        f.eye(R.id.password_eye, pw);
        f.validate = () -> f.ok(f.present(pw, R.id.password_hint, "Enter your password"));
        f.watch(pw);
        f.onSubmit = () -> Sheet.confirm(this, R.drawable.ic_trash, "Delete your account?",
                "Other devices lose access immediately. This cannot be undone.", "Delete account", true, () -> {
                    f.busy(true);
                    String e = AccountStore.email(this), token = AccountStore.token(this), typed = pw.getText().toString();
                    run(() -> {
                        JSONObject kb = AccountApi.keybox(token);
                        if (kb != null) AccountCrypto.unwrap(kb.optString("pw"), AccountCrypto.wrapKey(e, typed), AccountCrypto.AAD_PW);
                        AccountApi.deleteAccount(token);
                        AccountStore.signOut(this);
                        ui.post(() -> { toast("Account deleted"); done(); });
                    }, f);
                });
    }

    // ---- helpers -----------------------------------------------------------

    private interface Work {
        void run() throws Exception;
    }

    /** Runs relay work off the main thread; failures land in the page's error banner. */
    private void run(Work work, Form f) {
        new Thread(() -> {
            try {
                work.run();
            } catch (Exception e) {
                String msg = e.getMessage() == null ? "Something went wrong" : e.getMessage();
                ui.post(() -> { f.busy(false); f.error(msg); });
            }
        }).start();
    }

    private void countdown(Button b, String label) {
        long left = (resendAllowedAt - System.currentTimeMillis()) / 1000;
        if (left <= 0) {
            b.setEnabled(true);
            b.setText(label);
            return;
        }
        b.setEnabled(false);
        b.setText(label + " in " + left + "s");
        ui.postDelayed(() -> { if (b.isAttachedToWindow()) countdown(b, label); }, 1000);
    }

    private void done() {
        setResult(RESULT_OK);
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /** An RFC 3339 instant from the relay, shown in the phone's own time zone. */
    private static String localTime(String iso) {
        try {
            return java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("d MMM, HH:mm"));
        } catch (RuntimeException e) {
            return iso.length() >= 16 ? iso.substring(0, 16).replace('T', ' ') : iso;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Validation, hints, the eye toggle, busy state and the error banner for one page. */
    private final class Form {
        final View root;
        final Button submit;
        final View progress;
        final TextView error;
        Runnable validate = () -> {};
        Runnable onSubmit = () -> {};
        boolean busy;
        boolean touched;

        Form(View root) {
            this.root = root;
            submit = root.findViewById(R.id.btn_submit);
            progress = root.findViewById(R.id.progress);
            error = root.findViewById(R.id.error);
            if (submit != null) submit.setOnClickListener(v -> { touched = true; validate.run(); if (submit.isEnabled()) onSubmit.run(); });
        }

        EditText edit(int id) {
            return root.findViewById(id);
        }

        void watch(EditText... fields) {
            TextWatcher w = new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) { validate.run(); }
            };
            for (EditText f : fields) {
                f.addTextChangedListener(w);
                f.setOnFocusChangeListener((v, has) -> { if (!has) { touched = true; validate.run(); } });
            }
            validate.run();
        }

        void submitOnDone(EditText last) {
            last.setOnEditorActionListener((v, id, ev) -> {
                if (id == EditorInfo.IME_ACTION_DONE) { touched = true; validate.run(); if (submit != null && submit.isEnabled()) onSubmit.run(); return true; }
                return false;
            });
        }

        void eye(int buttonId, EditText field) {
            ImageButton b = root.findViewById(buttonId);
            b.setOnClickListener(v -> {
                boolean showing = field.getTransformationMethod() instanceof PasswordTransformationMethod;
                int sel = field.getSelectionEnd();
                field.setTransformationMethod(showing ? HideReturnsTransformationMethod.getInstance() : PasswordTransformationMethod.getInstance());
                field.setSelection(sel);
                b.setImageResource(showing ? R.drawable.ic_eye_off : R.drawable.ic_eye);
            });
        }

        void ok(boolean valid) {
            if (submit != null) submit.setEnabled(valid && !busy);
        }

        boolean email(EditText f, int hintId) {
            String e = f.getText().toString().trim();
            return hint(f, hintId, e.isEmpty() ? "Enter your email" : !EMAIL.matcher(e).matches() ? "That does not look like an email address" : null);
        }

        boolean present(EditText f, int hintId, String msg) {
            return hint(f, hintId, f.getText().length() == 0 ? msg : null);
        }

        boolean password(EditText f, int hintId) {
            String p = f.getText().toString();
            return hint(f, hintId, p.isEmpty() ? "Choose a password" : p.length() < MIN_PASSWORD ? "Use at least " + MIN_PASSWORD + " characters" : null);
        }

        boolean confirm(EditText pw, EditText cf, int hintId) {
            String c = cf.getText().toString();
            return hint(cf, hintId, c.isEmpty() ? "Type your password again" : !c.equals(pw.getText().toString()) ? "Passwords do not match" : null);
        }

        /** Shows the message (once the user has left a field) and returns whether the field is valid. */
        boolean hint(EditText f, int hintId, String err) {
            TextView h = root.findViewById(hintId);
            boolean show = err != null && touched;
            h.setText(show ? err : "");
            h.setVisibility(show ? View.VISIBLE : View.GONE);
            f.setBackgroundResource(show ? R.drawable.field_bg_error : R.drawable.field_bg);
            return err == null;
        }

        void strength(String p) {
            View row = root.findViewById(R.id.strength_row);
            if (row == null) return;
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
            int[] bars = {R.id.str1, R.id.str2, R.id.str3, R.id.str4};
            for (int i = 0; i < 4; i++) {
                root.findViewById(bars[i]).getBackground().mutate().setTint(getColor(i < score ? color : R.color.border));
            }
            TextView label = root.findViewById(R.id.strength_label);
            label.setText(p.isEmpty() ? "" : score <= 1 ? "Weak" : score == 2 ? "Okay" : score == 3 ? "Good" : "Strong");
            label.setTextColor(getColor(p.isEmpty() ? R.color.muted : color));
        }

        void busy(boolean b) {
            busy = b;
            if (submit != null) {
                if (b) submit.setTag(submit.getText());
                submit.setText(b ? "" : (CharSequence) submit.getTag());
                submit.setEnabled(!b);
            }
            if (progress != null) progress.setVisibility(b ? View.VISIBLE : View.GONE);
            if (b) {
                if (error != null) error.setVisibility(View.GONE);
                InputMethodManager imm = getSystemService(InputMethodManager.class);
                if (imm != null) imm.hideSoftInputFromWindow(root.getWindowToken(), 0);
            } else {
                validate.run();
            }
        }

        void error(String msg) {
            if (error == null) { toast(msg); return; }
            error.setText(msg);
            error.setVisibility(View.VISIBLE);
        }
    }
}
