package com.iflytek.sample.view;

import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.FocusFinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * Dictation IME with a remote-friendly keyboard.
 *
 * The remote's voice key ends up here as vendor keycode 4113. The microphone is
 * owned by the iFlytek assistant while it records, so this service does not try
 * to record: it waits for the transcript that xiri pushes onto its voice-view
 * channel (intercepted by {@link RelayService}) and commits it into the focused
 * text field.
 *
 * The keyboard page exists because a dictation-only IME leaves the owner unable
 * to type an address, a password or a search term at all.
 */
public class VkImeService extends InputMethodService {

    static final String TAG = "RELAY";
    static final int KEY_VOICE = 4113;

    static final int WATCH_MS = 20000;
    static final int STABLE_MS = 800;

    private static final int BG = 0xF0181818;
    private static final int KEY_BG = 0x33FFFFFF;
    private static final int KEY_FOCUS = 0x66A0C8FF;
    private static final int ACCENT = 0xFF8FC7FF;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private TextView status;
    private volatile boolean watching;
    private volatile long cooldownUntil;

    private View voicePage;
    private View keyPage;
    private LinearLayout keyRows;
    private ViewGroup panelRoot;
    private TextView firstKey;
    private TextView tabVoice;
    private TextView tabKeys;
    private boolean numeric = true;
    private boolean keysShown;
    private long menuDownAt;
    private boolean menuLong;

    @Override
    public View onCreateInputView() {
        LinearLayout root = new LinearLayout(this);
        panelRoot = root;
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setPadding(24, 12, 24, 12);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        tabVoice = tab("\uD83C\uDFA4 语音", true);
        tabKeys = tab("\u2328 键盘", false);
        tabVoice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(false);
            }
        });
        tabKeys.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPage(true);
            }
        });
        bar.addView(tabVoice);
        bar.addView(tabKeys);

        TextView spacer = new TextView(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView swap = new TextView(this);
        swap.setText("切换输入法");
        swap.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        swap.setTextColor(0x88FFFFFF);
        swap.setPadding(16, 12, 16, 12);
        swap.setFocusable(true);
        swap.setFocusableInTouchMode(true);
        swap.setBackground(round(0x00000000));
        swap.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(round(hasFocus ? KEY_FOCUS : 0x00000000));
            }
        });
        swap.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    switchToNextInputMethod(false);
                } catch (Throwable e) {
                    setStatus("切换失败：" + e);
                }
            }
        });
        bar.addView(swap);
        root.addView(bar);

        FrameLayout content = new FrameLayout(this);
        root.addView(content, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        voicePage = buildVoicePage();
        keyPage = buildKeyPage();
        content.addView(voicePage);
        content.addView(keyPage);
        showPage(false);
        return root;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        showPage(false);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return true;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (!isInputViewShown()) {
            return super.onKeyDown(keyCode, event);
        }
        if (keyCode != KeyEvent.KEYCODE_DPAD_UP && keyCode != KeyEvent.KEYCODE_DPAD_DOWN
                && keyCode != KeyEvent.KEYCODE_DPAD_LEFT && keyCode != KeyEvent.KEYCODE_DPAD_RIGHT
                && keyCode != KeyEvent.KEYCODE_DPAD_CENTER) {
            Log.d(TAG, "key " + keyCode + " rc=" + event.getRepeatCount());
        }
        if (panelRoot != null && event.getRepeatCount() == 0 && panelRoot.findFocus() == null) {
            focusDefault();
        }
        if (panelRoot != null && event.getRepeatCount() == 0) {
            int dir = 0;
            if (keyCode == KeyEvent.KEYCODE_DPAD_UP) dir = View.FOCUS_UP;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_DOWN) dir = View.FOCUS_DOWN;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT) dir = View.FOCUS_LEFT;
            else if (keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) dir = View.FOCUS_RIGHT;
            if (dir != 0) {
                View cur = panelRoot.findFocus();
                if (cur == null) {
                    focusDefault();
                    return true;
                }
                View next = FocusFinder.getInstance().findNextFocus(panelRoot, cur, dir);
                if (next != null) next.requestFocus();
                return true;
            }
            if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
                View cur = panelRoot.findFocus();
                if (cur != null && cur.isClickable()) {
                    cur.performClick();
                    Log.d(TAG, "clicked -> keys=" + keysShown);
                    return true;
                }
            }
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (event.getRepeatCount() == 0) {
                menuDownAt = System.currentTimeMillis();
                menuLong = false;
            } else if (!menuLong && System.currentTimeMillis() - menuDownAt >= 600) {
                menuLong = true;
                Log.d(TAG, "menu long press -> switch ime");
                toggleIme();
            }
            return true;
        }
        if (keyCode == KEY_VOICE) {
            // One press produces a burst of these events; treat it as one trigger.
            long now = System.currentTimeMillis();
            if (watching || now < cooldownUntil) {
                return true;
            }
            Log.d(TAG, "voice key down -> watch");
            showPage(false);
            startWatching();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (!isInputViewShown()) {
            return super.onKeyUp(keyCode, event);
        }
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            if (!menuLong) {
                Log.d(TAG, "menu short press -> page " + (!keysShown));
                showPage(!keysShown);
            }
            menuLong = false;
            return true;
        }
        if (keyCode == KEY_VOICE) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    /** Jump straight to the pinyin keyboard, and back again, without cycling through every IME. */
    private void toggleIme() {
        final String ours = "com.iflytek.sample.view/.VkImeService";
        final String pinyin = "com.android.inputmethod.pinyin/.PinyinIME";
        try {
            String cur = Settings.Secure.getString(getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD);
            String target = ours.equals(cur) ? pinyin : ours;
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.DEFAULT_INPUT_METHOD, target);
            Log.d(TAG, "ime " + cur + " -> " + target);
        } catch (Throwable e) {
            Log.w(TAG, "secure settings failed: " + e);
            try {
                switchToNextInputMethod(false);
            } catch (Throwable e2) {
                setStatus("切换失败：" + e2);
            }
        }
    }

    private void focusDefault() {
        View v = (keysShown && firstKey != null) ? firstKey : tabKeys;
        if (v != null) v.requestFocus();
    }

    // ---- pages ---------------------------------------------------------------------------------

    private void showPage(boolean keys) {
        keysShown = keys;
        if (voicePage != null) voicePage.setVisibility(keys ? View.GONE : View.VISIBLE);
        if (keyPage != null) keyPage.setVisibility(keys ? View.VISIBLE : View.GONE);
        if (tabVoice != null) tabVoice.setTextColor(keys ? 0x88FFFFFF : ACCENT);
        if (tabKeys != null) tabKeys.setTextColor(keys ? ACCENT : 0x88FFFFFF);
        if (keys) {
            if (keyRows == null) return;
            buildKeys();
            if (firstKey != null) {
                firstKey.post(new Runnable() {
                    @Override
                    public void run() {
                        firstKey.requestFocus();
                    }
                });
            }
        } else if (tabKeys != null) {
            tabKeys.post(new Runnable() {
                @Override
                public void run() {
                    tabKeys.requestFocus();
                }
            });
        }
    }

    private View buildVoicePage() {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setGravity(Gravity.CENTER_VERTICAL);
        page.setPadding(12, 18, 12, 18);

        TextView mic = new TextView(this);
        mic.setText("\uD83C\uDFA4");
        mic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 34);
        mic.setPadding(0, 0, 28, 0);
        page.addView(mic);

        status = new TextView(this);
        status.setText("按遥控器语音键说话，或切到键盘输入");
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        status.setTextColor(0xFFFFFFFF);
        page.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return page;
    }

    private View buildKeyPage() {
        keyRows = new LinearLayout(this);
        keyRows.setOrientation(LinearLayout.VERTICAL);
        keyRows.setPadding(0, 10, 0, 4);
        return keyRows;
    }

    private void buildKeys() {
        if (keyRows == null) return;
        keyRows.removeAllViews();
        if (numeric) {
            row("1234567890");
            row(".:/-_@#%+=");
            actions(true);
        } else {
            row("qwertyuiop");
            row("asdfghjkl");
            row("zxcvbnm");
            actions(false);
        }
        firstKey = null;
        if (keyRows.getChildCount() > 0) {
            View r0 = keyRows.getChildAt(0);
            if (r0 instanceof LinearLayout && ((LinearLayout) r0).getChildCount() > 0) {
                View v0 = ((LinearLayout) r0).getChildAt(0);
                if (v0 instanceof TextView) firstKey = (TextView) v0;
            }
        }
    }

    private void row(String chars) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, 4, 0, 4);
        for (int i = 0; i < chars.length(); i++) {
            final String s = String.valueOf(chars.charAt(i));
            r.addView(key(s, 1f, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    InputConnection ic = getCurrentInputConnection();
                    if (ic != null) ic.commitText(s, 1);
                }
            }));
        }
        keyRows.addView(r);
    }

    private void actions(boolean numericPage) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(0, 4, 0, 4);

        r.addView(key(numericPage ? "ABC" : "123", 1f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                numeric = !numeric;
                buildKeys();
            }
        }));
        r.addView(key("空格", 2f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.commitText(" ", 1);
            }
        }));
        r.addView(key("\u232B 删除", 1.4f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) ic.deleteSurroundingText(1, 0);
            }
        }));
        r.addView(key("\u21B5 完成", 1.4f, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                }
            }
        }));
        keyRows.addView(r);
    }

    private TextView tab(String text, boolean active) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setTextColor(active ? ACCENT : 0x88FFFFFF);
        t.setPadding(18, 10, 18, 10);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setBackground(round(0x00000000));
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(round(hasFocus ? KEY_FOCUS : 0x00000000));
            }
        });
        return t;
    }

    private TextView key(String text, float weight, View.OnClickListener click) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTextColor(0xFFFFFFFF);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, 18, 0, 18);
        t.setFocusable(true);
        t.setFocusableInTouchMode(true);
        t.setBackground(round(KEY_BG));
        t.setOnClickListener(click);
        t.setOnFocusChangeListener(new View.OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                v.setBackground(round(hasFocus ? KEY_FOCUS : KEY_BG));
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight);
        lp.setMargins(4, 0, 4, 0);
        t.setLayoutParams(lp);
        return t;
    }

    private GradientDrawable round(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(8 * getResources().getDisplayMetrics().density);
        return d;
    }

    // ---- dictation -----------------------------------------------------------------------------

    private void setStatus(final String s) {
        ui.post(new Runnable() {
            @Override
            public void run() {
                if (status != null) {
                    status.setText(s);
                }
            }
        });
    }

    private void startWatching() {
        watching = true;
        RelayService.reset();
        setStatus("正在听…");
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    watch();
                } catch (Throwable e) {
                    Log.w(TAG, "watch failed", e);
                    setStatus("出错：" + e);
                } finally {
                    watching = false;
                    cooldownUntil = System.currentTimeMillis() + 1500;
                }
            }
        }, "vk-watch");
        t.start();
    }

    private void watch() throws InterruptedException {
        long deadline = System.currentTimeMillis() + WATCH_MS;
        String last = null;
        long lastChange = System.currentTimeMillis();

        while (System.currentTimeMillis() < deadline) {
            String t = RelayService.lastText;
            if (!TextUtils.isEmpty(t) && !t.equals(last)) {
                last = t;
                lastChange = System.currentTimeMillis();
                setStatus("识别中：" + t);
            }
            if (last != null && System.currentTimeMillis() - lastChange > STABLE_MS) {
                break;
            }
            Thread.sleep(100);
        }

        if (last == null) {
            setStatus("没识别到，再按一次");
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        for (int i = 0; ic == null && i < 20; i++) {
            Thread.sleep(100);
            ic = getCurrentInputConnection();
        }
        if (ic == null) {
            setStatus("输入框已失焦：" + last);
            return;
        }
        ic.commitText(last, 1);
        setStatus("已输入：" + last);
        Log.d(TAG, "committed " + last);
    }
}
