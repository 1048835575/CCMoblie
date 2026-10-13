package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.inputmethodservice.InputMethodService;
import android.util.Base64;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.nio.charset.StandardCharsets;

/**
 * 供 MCP 输入中文用的极简输入法：只在 phone_input 期间被临时切换为当前输入法，
 * 通过 `am broadcast -a com.termux.ccm.IME_INPUT --es b64 <base64>` 接收文字。
 * 成功时广播结果码为 1，MCP 据此判断是否需要回退。
 */
public class CcmIme extends InputMethodService {

    public static final String ACTION_INPUT = "com.termux.ccm.IME_INPUT";
    /** 处于当前输入法却没收到输入指令超过该时长，自动切回上一个输入法，避免用户键盘被"卡住" */
    private static final long IDLE_SWITCH_BACK_MS = 8000;

    private final android.os.Handler mHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable mSwitchBack = this::switchBack;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            InputConnection ic = getCurrentInputConnection();
            if (ic == null) {
                setResultCode(0);
                return;
            }
            if (intent.getBooleanExtra("clear", false)) {
                ic.performContextMenuAction(android.R.id.selectAll);
                ic.commitText("", 1);
            }
            String b64 = intent.getStringExtra("b64");
            if (b64 != null && !b64.isEmpty()) {
                ic.commitText(new String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8), 1);
            }
            if (intent.getBooleanExtra("submit", false)) submit(ic);
            setResultCode(1);
            scheduleSwitchBack(3000);
        }
    };

    private void submit(InputConnection ic) {
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_NONE : ei.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnterAction = ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        // shell（adb）发来的广播需要 exported 才能收到
        ContextCompat.registerReceiver(this, mReceiver, new IntentFilter(ACTION_INPUT), ContextCompat.RECEIVER_EXPORTED);
        scheduleSwitchBack(IDLE_SWITCH_BACK_MS);
    }

    private void scheduleSwitchBack(long delayMs) {
        mHandler.removeCallbacks(mSwitchBack);
        mHandler.postDelayed(mSwitchBack, delayMs);
    }

    /** 切回用户原来的输入法；失败时弹出系统输入法选择框。 */
    @SuppressWarnings("deprecation")
    private void switchBack() {
        boolean ok = false;
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                ok = switchToPreviousInputMethod();
            } else {
                android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
                android.os.IBinder token = getWindow() == null || getWindow().getWindow() == null ? null
                    : getWindow().getWindow().getAttributes().token;
                ok = imm != null && token != null && imm.switchToLastInputMethod(token);
            }
        } catch (Exception ignored) {}
        if (!ok) {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        }
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacks(mSwitchBack);
        unregisterReceiver(mReceiver);
        super.onDestroy();
    }

    @Override
    public View onCreateInputView() {
        float d = getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(this);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(0xFF262624);
        box.setPadding((int) (16 * d), (int) (14 * d), (int) (16 * d), (int) (14 * d));
        TextView tv = new TextView(this);
        tv.setText(t("CCMobile 正在自动输入…"));
        tv.setTextColor(Color.WHITE);
        tv.setGravity(Gravity.CENTER);
        box.addView(tv);
        // 万一停留在这个输入法上，用户可以一键切回自己的键盘
        TextView back = new TextView(this);
        back.setText(t("切换回原键盘"));
        back.setTextColor(Color.WHITE);
        back.setGravity(Gravity.CENTER);
        back.setPadding((int) (20 * d), (int) (10 * d), (int) (20 * d), (int) (10 * d));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(0xFF0F8B6F);
        bg.setCornerRadius(10 * d);
        back.setBackground(bg);
        back.setOnClickListener(v -> switchBack());
        back.setOnLongClickListener(v -> {
            android.view.inputmethod.InputMethodManager imm =
                (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
            return true;
        });
        android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
            android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = (int) (10 * d);
        box.addView(back, lp);
        return box;
    }
}
