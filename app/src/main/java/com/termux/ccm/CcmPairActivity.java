package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.content.Intent;
import android.view.View;
import androidx.appcompat.widget.SwitchCompat;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationManagerCompat;

import com.google.android.material.button.MaterialButton;
import com.termux.R;

/**
 * 手机控制页面。默认通过无障碍服务（{@link CcmA11yService}）控制手机：不开放网络端口、无需配对，
 * 敏感应用不操作、敏感按钮需用户确认。
 * 无线调试（adb）作为「高级模式」默认关闭，用户确认风险后才显示配对界面。所有状态实时可见：开发者选项、
 * 无线调试、通知权限、mDNS 发现的配对/连接端口、adb 连接状态和命令日志。
 */
public class CcmPairActivity extends AppCompatActivity {

    private CcmUi ui;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private TextView[] mA11yRow, mShotRow;
    private SwitchCompat mAdbSwitch;
    private LinearLayout mAdbBox;
    private EditText mSensitive;
    private TextView[] mDevRow, mWifiRow, mNotifyRow, mPairPortRow, mConnectRow;
    private EditText mCode, mPort;
    private MaterialButton mPairButton;
    private TextView mLog, mResult;
    private volatile boolean mConnected;
    private boolean mBusy;
    private long mLastConnectCheck;

    private final Runnable mTick = new Runnable() {
        @Override public void run() {
            refresh();
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = CcmUi.page(this, t("手机控制"));
        LinearLayout root = ui.content;

        ui.heading(root, t("让 AI 操作这台手机"));
        ui.hint(root, t("开启「CCMobile 手机控制」无障碍服务后，AI 可以读取屏幕上的控件、点击、输入文字、滑动和截图。")
            + t("服务只接受本 App 内的调用，不开放任何网络端口。"));

        ui.section(root, t("无障碍服务（推荐）"));
        LinearLayout a = ui.card(root);
        mA11yRow = ui.statusRow(a, t("CCMobile 手机控制"));
        mShotRow = ui.statusRow(a, t("截图"));
        ui.hint(a, t("在系统设置 → 无障碍 →「已下载的应用」（或「已安装的服务」）中找到「CCMobile 手机控制」并开启。"));
        LinearLayout ar = ui.row(a);
        ui.primary(ar, t("打开无障碍设置"), v -> openA11ySettings());
        if (Build.VERSION.SDK_INT >= 33) {
            ui.outlined(ar, t("应用信息"), v -> openAppDetails());
            ui.hint(a, t("如果开关是灰色、提示「受限设置」：打开应用信息 → 右上角菜单 →「允许受限设置」，再回来开启。"));
        }
        ui.hint(a, t("小米 / 红米等系统可能在后台关闭服务：建议在应用信息里允许「自启动」，省电策略选「无限制」。"));

        ui.section(root, t("安全保护"));
        LinearLayout sec = ui.card(root);
        ui.hint(sec, t("· 点击「支付、转账、删除」等敏感按钮前，会在屏幕上弹框请你确认，60 秒不处理自动拒绝。\n")
            + t("· 下列敏感应用（银行、支付等）中，AI 不读取也不操作，需要你自己完成。包名用逗号分隔："));
        mSensitive = ui.field(sec, t("敏感应用"), CcmConfig.get(this, CcmConfig.KEY_SENSITIVE_APPS), false);
        mSensitive.setSingleLine(false);
        mSensitive.setMaxLines(6);
        LinearLayout sr = ui.row(sec);
        ui.primary(sr, t("保存"), v -> saveSensitive(mSensitive.getText().toString()));
        ui.outlined(sr, t("恢复默认"), v -> {
            mSensitive.setText(CcmConfig.DEFAULT_SENSITIVE_APPS);
            saveSensitive(CcmConfig.DEFAULT_SENSITIVE_APPS);
        });

        // 高级：无线调试
        ui.section(root, t("高级：无线调试（默认关闭）"));
        LinearLayout adv = ui.card(root);
        ui.hint(adv, t("无线调试（adb）的权限远大于无障碍：可以执行系统命令、修改分辨率和系统设置、安装卸载应用，")
            + t("同一 Wi-Fi 下的设备也可能尝试连接。误操作可能让手机无法正常使用。只在你清楚风险时开启。"));
        mAdbSwitch = new SwitchCompat(this);
        mAdbSwitch.setText(t("启用无线调试模式"));
        mAdbSwitch.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.ccm_text));
        mAdbSwitch.setPadding(0, 8 * ui.dp, 0, 8 * ui.dp);
        mAdbSwitch.setChecked(CcmConfig.isAdbEnabled(this));
        mAdbSwitch.setOnCheckedChangeListener((b, on) -> {
            if (b.isPressed()) onAdbToggle(on);
        });
        adv.addView(mAdbSwitch);

        mAdbBox = new LinearLayout(this);
        mAdbBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(mAdbBox, CcmUi.matchWrap());
        LinearLayout c = mAdbBox;

        // 状态
        ui.section(c, t("无线调试状态"));
        LinearLayout status = ui.card(c);
        mDevRow = ui.statusRow(status, t("开发者选项"));
        mWifiRow = ui.statusRow(status, t("无线调试"));
        mNotifyRow = ui.statusRow(status, t("通知权限（用于通知栏输入配对码）"));
        mPairPortRow = ui.statusRow(status, t("配对端口"));
        mConnectRow = ui.statusRow(status, t("连接状态"));

        // 第 1 步
        ui.section(c, t("第一步：打开无线调试"));
        LinearLayout s1 = ui.card(c);
        ui.hint(s1, t("进入「开发者选项」，打开「无线调试」（需连接 Wi-Fi）。\n")
            + t("小米 / 红米还要打开「USB 调试（安全设置）」，否则 AI 无法点击和输入。\n")
            + t("没有开发者选项：设置 → 我的设备 → 全部参数 → 连续点击「OS 版本」7 次。"));
        LinearLayout r1 = ui.row(s1);
        ui.primary(r1, t("打开开发者选项"), v -> openDevSettings(false));
        ui.outlined(r1, t("分屏打开"), v -> openDevSettings(true));

        // 第 2 步
        ui.section(c, t("第二步：输入配对码"));
        LinearLayout s2 = ui.card(c);
        ui.hint(s2, t("在「无线调试」里点「使用配对码配对设备」，弹窗会显示 6 位配对码。")
            + t("弹窗关掉配对码就失效，所以请用下面任一方式输入：\n")
            + t("· 通知栏输入：下拉通知栏直接回复，最方便\n")
            + t("· 分屏 / 小窗：一边看配对码，一边在下面输入"));
        LinearLayout r2 = ui.row(s2);
        ui.primary(r2, t("发送通知输入框"), v -> sendNotification());
        ui.outlined(r2, t("通知权限"), v -> openNotificationSettings());

        mCode = ui.field(s2, t("配对码"), "", false);
        mCode.setHint(t("6 位数字"));
        mCode.setInputType(InputType.TYPE_CLASS_NUMBER);
        mPort = ui.field(s2, t("配对端口（自动发现，一般无需填写）"), "", false);
        mPort.setHint(t("自动"));
        mPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        mPairButton = ui.primary(s2, t("配对并连接"), v -> pair());

        // 第 3 步
        ui.section(c, t("已经配对过？"));
        LinearLayout s3 = ui.card(c);
        ui.hint(s3, t("重启手机或切换 Wi-Fi 后端口会变化，点这里重新连接即可，无需再次配对。"));
        ui.outlined(s3, t("重新连接"), v -> reconnect());

        mResult = ui.text("", 14, R.color.ccm_text);
        mResult.setPadding(4 * ui.dp, 14 * ui.dp, 4 * ui.dp, 0);
        c.addView(mResult);

        ui.section(c, t("日志"));
        mLog = ui.logView(c);

        ui.section(c, t("用完后"));
        LinearLayout off = ui.card(c);
        ui.hint(off, t("不再需要时，建议关闭上方开关，并在开发者选项中关闭「无线调试」。"));
        ui.outlined(off, t("打开开发者选项"), v -> openDevSettings(false));

        if (!CcmAdb.adbInstalled()) {
            mResult.setText(t("开发环境还没装好（缺少 adb），请先完成安装。"));
            mResult.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.ccm_error));
        }
        mAdbBox.setVisibility(mAdbSwitch.isChecked() ? View.VISIBLE : View.GONE);
    }

    private void onAdbToggle(boolean on) {
        if (!on) {
            setAdbEnabled(false);
            // 断开已有的 adb 连接，AI 不再能通过它执行命令
            if (CcmAdb.adbInstalled()) new Thread(() -> {
                try { CcmAdb.runAdb(8000, "disconnect"); } catch (Exception ignored) {}
            }).start();
            toast(t("已关闭无线调试模式。也建议在开发者选项中关闭「无线调试」"));
            return;
        }
        mAdbSwitch.setChecked(false);
        confirmAdbRisk();
    }

    /** 第一道确认：风险说明，按钮倒计时 5 秒后才能点。 */
    private void confirmAdbRisk() {
        androidx.appcompat.app.AlertDialog d = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(t("开启无线调试模式？"))
            .setMessage(t("开启后 AI 可以通过 adb 执行系统命令（phone_shell）。CCMobile 会拒绝修改分辨率、锁屏、恢复出厂等危险命令，")
                + t("卸载应用、修改系统设置等需要你确认，但 adb 权限很大，仍可能误操作导致手机异常。\n\n")
                + t("大多数任务用无障碍服务就够了。确定要开启吗？"))
            .setNegativeButton(t("取消"), null)
            .setPositiveButton(t("继续"), (x, w) -> confirmAdbPhrase())
            .create();
        d.setOnShowListener(x -> {
            android.widget.Button ok = d.getButton(android.content.DialogInterface.BUTTON_POSITIVE);
            ok.setEnabled(false);
            for (int i = 5; i >= 0; i--) {
                final int left = i;
                mHandler.postDelayed(() -> {
                    ok.setText(left > 0 ? t("继续") + " (" + left + ")" : t("继续"));
                    ok.setEnabled(left == 0);
                }, (5 - i) * 1000L);
            }
        });
        d.show();
    }

    /** 第二道确认：输入确认短语。 */
    private void confirmAdbPhrase() {
        String phrase = t("我了解风险");
        EditText input = new EditText(this);
        input.setHint(phrase);
        input.setSingleLine(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(24 * ui.dp, 8 * ui.dp, 24 * ui.dp, 0);
        box.addView(input, CcmUi.matchWrap());
        androidx.appcompat.app.AlertDialog d = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(t("再次确认"))
            .setMessage(t("请输入「") + phrase + t("」以开启无线调试模式。开启后 24 小时自动关闭。"))
            .setView(box)
            .setNegativeButton(t("取消"), null)
            .setPositiveButton(t("开启"), null)
            .create();
        d.setOnShowListener(x -> d.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (!input.getText().toString().trim().equalsIgnoreCase(phrase)) {
                input.setError(t("输入不一致"));
                return;
            }
            d.dismiss();
            mAdbSwitch.setChecked(true);
            setAdbEnabled(true);
        }));
        d.show();
    }

    private void setAdbEnabled(boolean on) {
        CcmConfig.setAdbEnabled(this, on);
        try { CcmConfig.writeEnv(this); } catch (Exception ignored) {}
        mAdbBox.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) CcmAdb.startDiscovery(this); else CcmAdb.stopDiscovery(this);
        if (on) toast(t("已开启，24 小时后自动关闭。新对话生效"));
    }

    private void saveSensitive(String list) {
        String v = list.trim().replaceAll("[，\\s]+", ",").replaceAll(",+", ",");
        CcmConfig.prefs(this).edit().putString(CcmConfig.KEY_SENSITIVE_APPS, v).apply();
        try { CcmConfig.writeEnv(this); } catch (Exception ignored) {}
        toast(t("已保存，新对话生效"));
    }

    private void openA11ySettings() {
        try {
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            toast(t("找到「CCMobile 手机控制」并开启"));
        } catch (Exception e) {
            toast(t("无法打开无障碍设置，请在系统设置中搜索「无障碍」"));
        }
    }

    private void openAppDetails() {
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName())));
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (CcmConfig.isAdbEnabled(this)) {
            CcmAdb.startDiscovery(this);
        } else if (mAdbSwitch.isChecked()) {
            // 停留在本页期间到期
            mAdbSwitch.setChecked(false);
            mAdbBox.setVisibility(View.GONE);
        }
        mHandler.post(mTick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mTick);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        CcmAdb.stopDiscovery(this);
    }

    private void refresh() {
        boolean running = CcmA11yService.isRunning();
        boolean enabled = running || CcmA11yService.isEnabled(this);
        ui.setStatus(mA11yRow, running ? R.color.ccm_ok : enabled ? R.color.ccm_text_secondary : R.color.ccm_error,
            running ? t("运行中") : enabled ? t("已开启，等待系统启动服务…") : t("未开启"));
        boolean shot = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R;
        ui.setStatus(mShotRow, shot, shot ? t("支持") : t("需要 Android 11+"));
        if (mAdbBox.getVisibility() != View.VISIBLE) return;
        boolean dev = globalInt(Settings.Global.DEVELOPMENT_SETTINGS_ENABLED) == 1;
        ui.setStatus(mDevRow, dev, dev ? t("已开启") : t("未开启"));
        int wifi = globalInt("adb_wifi_enabled");
        if (wifi < 0) ui.setStatus(mWifiRow, R.color.ccm_text_tertiary, t("无法读取"));
        else ui.setStatus(mWifiRow, wifi == 1, wifi == 1 ? t("已开启") : t("未开启"));
        boolean notify = NotificationManagerCompat.from(this).areNotificationsEnabled();
        ui.setStatus(mNotifyRow, notify, notify ? t("已允许") : t("未允许"));
        int pp = CcmAdb.getPairingPort();
        ui.setStatus(mPairPortRow, pp > 0 ? R.color.ccm_ok : R.color.ccm_text_tertiary,
            pp > 0 ? String.valueOf(pp) : t("等待配对码弹窗…"));
        if (pp > 0 && mPort.getText().length() == 0) mPort.setHint(t("自动：") + pp);

        long now = System.currentTimeMillis();
        if (now - mLastConnectCheck > 4000 && CcmAdb.adbInstalled()) {
            mLastConnectCheck = now;
            new Thread(() -> mConnected = CcmAdb.isConnected()).start();
        }
        ui.setStatus(mConnectRow, mConnected, mConnected ? t("已连接") : t("未连接"));
        mLog.setText(CcmAdb.getLog().isEmpty() ? t("（暂无）") : CcmAdb.getLog());
    }

    private int globalInt(String key) {
        try {
            return Settings.Global.getInt(getContentResolver(), key);
        } catch (Exception e) {
            return -1;
        }
    }

    private void openDevSettings(boolean adjacent) {
        Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (adjacent) i.addFlags(Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        try {
            startActivity(i);
            if (adjacent) toast(t("若没有分屏，可从最近任务里把设置拖成小窗"));
        } catch (Exception e) {
            toast(t("无法打开开发者选项：") + e.getMessage() + t("。请先在「关于手机」中开启开发者选项"));
        }
    }

    private void sendNotification() {
        if (!CcmAdb.adbInstalled()) { toast(t("请先完成开发环境安装")); return; }
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) {
            toast(t("通知权限未开启，先允许通知"));
            openNotificationSettings();
            return;
        }
        CcmAdb.sendPairNotification(this);
        toast(t("已发送。打开配对码弹窗后，下拉通知栏回复配对码"));
        openDevSettings(false);
    }

    private void openNotificationSettings() {
        Intent i;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName());
        } else {
            i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
        }
        try { startActivity(i); } catch (Exception e) { toast(t("请在系统设置中允许 CCMobile 的通知")); }
    }

    private void pair() {
        if (mBusy) return;
        if (!CcmAdb.adbInstalled()) { toast(t("请先完成开发环境安装")); return; }
        String code = mCode.getText().toString().trim();
        if (!code.matches("\\d{6}")) { toast(t("请输入 6 位配对码")); return; }
        int port = -1;
        try { port = Integer.parseInt(mPort.getText().toString().trim()); } catch (Exception ignored) {}
        final int p = port;
        setBusy(true, t("正在配对…"));
        new Thread(() -> {
            String msg = CcmAdb.pairAndConnect(code, p);
            runOnUiThread(() -> finishAction(msg));
        }).start();
    }

    private void reconnect() {
        if (mBusy) return;
        setBusy(true, t("正在连接…"));
        CcmAdb.reconnect(this, (ok, msg) -> finishAction(msg));
    }

    private void setBusy(boolean busy, String msg) {
        mBusy = busy;
        mPairButton.setEnabled(!busy);
        mResult.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.ccm_text_secondary));
        mResult.setText(msg);
    }

    private void finishAction(String msg) {
        mBusy = false;
        mPairButton.setEnabled(true);
        boolean ok = CcmAdb.isConnectedMessage(msg);
        mConnected = ok;
        mResult.setTextColor(androidx.core.content.ContextCompat.getColor(this, ok ? R.color.ccm_ok : R.color.ccm_error));
        mResult.setText((ok ? "✓ " : "✗ ") + msg);
        mLastConnectCheck = 0;
        refresh();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
