package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.content.Intent;
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
 * 连接手机（无线调试）页面。所有状态实时可见：开发者选项、无线调试、通知权限、
 * mDNS 发现的配对/连接端口、adb 连接状态和命令日志。配对码可在页面内或通知里输入。
 */
public class CcmPairActivity extends AppCompatActivity {

    private CcmUi ui;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
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
        ui = CcmUi.page(this, t("连接手机"));
        LinearLayout c = ui.content;

        ui.heading(c, t("让 AI 操作这台手机"));
        ui.hint(c, t("通过本机「无线调试」获得点击、输入、截图等能力。只需配对一次，之后打开 App 会自动重连。"));

        // 状态
        ui.section(c, t("当前状态"));
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

        if (!CcmAdb.adbInstalled()) {
            mResult.setText(t("开发环境还没装好（缺少 adb），请先完成安装。"));
            mResult.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.ccm_error));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        CcmAdb.startDiscovery(this);
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
