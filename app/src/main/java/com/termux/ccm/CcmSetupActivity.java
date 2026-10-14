package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.termux.R;
import com.termux.app.TermuxInstaller;
import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

/** 环境与连接向导：安装开发环境 → 配置模型 → 开启手机控制。安装任务放在静态字段，页面重建后可接上进度。 */
public class CcmSetupActivity extends AppCompatActivity {

    private static CcmProcess sSetupProcess;
    private static final StringBuilder sSetupLog = new StringBuilder();
    private static volatile String sSetupStep = "";
    private static volatile int sSetupProgress = 0;
    private static volatile String sSetupError = null;
    private static volatile Runnable sUiRefresh;

    private CcmUi ui;
    private TextView mEnvStatus, mApiStatus, mPhoneStatus, mStepText, mLog;
    private ProgressBar mProgress;
    private MaterialButton mInstallButton, mStartButton;
    private volatile boolean mPhoneConnected;

    public static boolean bootstrapInstalled() {
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "bash").exists();
    }

    public static boolean isInstalling() {
        return sSetupProcess != null && sSetupProcess.isRunning();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try { startService(new Intent(this, TermuxService.class)); } catch (Exception ignored) {}
        ui = CcmUi.page(this, t("环境与连接"));
        LinearLayout c = ui.content;
        // 首次进入即可切换语言（默认已跟随系统）
        LinearLayout langRow = new LinearLayout(this);
        langRow.setGravity(android.view.Gravity.END);
        for (int i = 1; i < CcmI18n.LANGUAGES.length; i++) {
            final String code = CcmI18n.LANGUAGES[i];
            TextView chip = ui.text(CcmI18n.LANGUAGE_LABELS[i], 13,
                code.equals(CcmI18n.lang()) ? R.color.ccm_accent : R.color.ccm_text_secondary);
            chip.setBackgroundResource(R.drawable.ccm_bg_chip);
            chip.setPadding(12 * ui.dp, 5 * ui.dp, 12 * ui.dp, 5 * ui.dp);
            if (code.equals(CcmI18n.lang())) chip.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            chip.setOnClickListener(v -> { if (!code.equals(CcmI18n.lang())) CcmI18n.change(this, code); });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.leftMargin = 8 * ui.dp;
            langRow.addView(chip, lp);
        }
        c.addView(langRow);
        ui.heading(c, t("欢迎使用 CCMobile"));
        ui.hint(c, t("在手机上运行完整的 Claude Code：写代码、管理项目，也能直接操作这台手机。完成下面三步即可开始。"));

        // 1. 环境
        LinearLayout c1 = ui.card(c);
        mEnvStatus = ui.stepHeader(c1, 1, t("安装开发环境"));
        ui.hint(c1, t("Linux 环境 + Node.js + adb + Claude Code ") + CcmConfig.get(this, CcmConfig.KEY_CC_VERSION) + t("，约 1~10 分钟，需联网。"));
        mProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgress.setMax(100);
        mProgress.setProgressTintList(ContextCompat.getColorStateList(this, R.color.ccm_accent));
        c1.addView(mProgress);
        mStepText = ui.hint(c1, "");
        mInstallButton = ui.primary(c1, t("开始安装"), v -> startSetup());
        TextView toggle = ui.text(t("查看安装日志"), 13, R.color.ccm_accent);
        toggle.setPadding(0, 10 * ui.dp, 0, 0);
        c1.addView(toggle);
        mLog = ui.logView(c1);
        mLog.setMaxLines(30);
        mLog.setVisibility(View.GONE);
        toggle.setOnClickListener(v -> mLog.setVisibility(mLog.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));

        // 2. 模型
        LinearLayout c2 = ui.card(c);
        mApiStatus = ui.stepHeader(c2, 2, t("登录或配置模型"));
        ui.hint(c2, t("二选一：登录 Claude Pro / Max 订阅账号；或填写 API 地址和 Key（DeepSeek、智谱、Kimi 等 Anthropic 兼容接口）。自动生成 Claude Code 的 settings.json。"));
        ui.outlined(c2, t("去配置"), v -> startActivity(new Intent(this, CcmConfigActivity.class)));

        // 3. 手机
        LinearLayout c3 = ui.card(c);
        mPhoneStatus = ui.stepHeader(c3, 3, t("开启手机控制（可选）"));
        ui.hint(c3, t("开启无障碍服务，让 AI 能点击、输入、截图。只写代码可以跳过。"));
        ui.outlined(c3, t("去开启"), v -> startActivity(new Intent(this, CcmPairActivity.class)));

        mStartButton = ui.primary(c, t("开始使用"), v -> {
            // 主界面是 singleTask：已存在则回到它，否则新建
            startActivity(new Intent(this, CcmHomeActivity.class));
            finish();
        });
        ((LinearLayout.LayoutParams) mStartButton.getLayoutParams()).topMargin = 24 * ui.dp;

        // 首次进入自动开始安装，不用再找按钮
        if (savedInstanceState == null && !CcmConfig.isEnvironmentInstalled() && !isInstalling() && sSetupError == null) {
            startSetup();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        sUiRefresh = () -> runOnUiThread(this::refresh);
        refresh();
        mPhoneConnected = CcmA11yService.isRunning();
        refresh();
    }

    @Override
    protected void onPause() {
        super.onPause();
        sUiRefresh = null;
    }

    private void refresh() {
        boolean env = CcmConfig.isEnvironmentInstalled();
        boolean api = CcmConfig.isConfigured(this);
        boolean installing = isInstalling();

        setStatus(mEnvStatus, env ? t("✓ 已安装") : installing ? sSetupProgress + "%" : sSetupError != null ? t("✗ 失败") : t("未安装"),
            env ? R.color.ccm_ok : sSetupError != null ? R.color.ccm_error : R.color.ccm_text_secondary);
        mProgress.setVisibility(installing ? View.VISIBLE : View.GONE);
        mProgress.setProgress(sSetupProgress);
        mStepText.setVisibility(installing || sSetupError != null ? View.VISIBLE : View.GONE);
        mStepText.setText(sSetupError != null && !installing ? sSetupError : sSetupStep);
        mStepText.setTextColor(ContextCompat.getColor(this, sSetupError != null && !installing ? R.color.ccm_error : R.color.ccm_text_secondary));
        mInstallButton.setVisibility(env || installing ? View.GONE : View.VISIBLE);
        mInstallButton.setText(sSetupError != null ? t("重试安装") : t("开始安装"));
        synchronized (sSetupLog) {
            int len = sSetupLog.length();
            mLog.setText(len > 6000 ? sSetupLog.substring(len - 6000) : sSetupLog.toString());
        }

        setStatus(mApiStatus, api ? "✓ " + (CcmConfig.isSubscription(this) ? t("Claude 订阅 · ") : "") + CcmConfig.mainModel(this) : (CcmConfig.isSubscription(this) ? t("未登录") : t("未填写")),
            api ? R.color.ccm_ok : R.color.ccm_text_secondary);
        setStatus(mPhoneStatus, mPhoneConnected ? t("✓ 已开启") : t("未开启"), mPhoneConnected ? R.color.ccm_ok : R.color.ccm_text_secondary);
        mStartButton.setEnabled(env && api);
        mStartButton.setText(env && api ? t("开始使用") : t("完成第 1、2 步后开始使用"));
    }

    private void setStatus(TextView tv, String s, int colorRes) {
        tv.setText(s);
        tv.setTextColor(ContextCompat.getColor(this, colorRes));
    }

    private void startSetup() {
        if (isInstalling()) return;
        mInstallButton.setEnabled(false);
        // 先解压内置的 Linux 环境（Termux bootstrap），完成后再跑安装脚本
        TermuxInstaller.setupBootstrapIfNeeded(this, () -> {
            mInstallButton.setEnabled(true);
            CcmConfig.installAssets(this);
            synchronized (sSetupLog) { sSetupLog.setLength(0); }
            sSetupError = null;
            sSetupProgress = 2;
            sSetupStep = t("准备中…");
            try {
                sSetupProcess = CcmProcess.start(getApplicationContext(), null, new SetupListener(),
                    new File(CcmConfig.CCM_DIR, "setup.sh").getAbsolutePath());
            } catch (Exception e) {
                sSetupError = t("无法启动安装：") + e.getMessage();
            }
            refresh();
        });
    }

    /** 只更新静态状态，再通知当前可见的页面刷新。 */
    private static class SetupListener implements CcmProcess.Listener {
        private long mLastUi;

        @Override
        public void onLine(String line) {
            if (line.startsWith("##STEP ")) {
                String[] p = line.substring(7).split(" ", 2);
                String[] frac = p[0].split("/");
                try { sSetupProgress = Integer.parseInt(frac[0]) * 100 / (Integer.parseInt(frac[1]) + 1); } catch (Exception ignored) {}
                sSetupStep = t("第 ") + p[0] + t(" 步 · ") + (p.length > 1 ? p[1] : "");
            } else if (line.startsWith("##DONE")) {
                sSetupProgress = 100;
            } else {
                synchronized (sSetupLog) {
                    sSetupLog.append(line).append('\n');
                    if (sSetupLog.length() > 200_000) sSetupLog.delete(0, 100_000);
                }
                if (line.startsWith(t("安装失败：")) || line.startsWith("Install failed:")) sSetupError = line;
            }
            long now = System.currentTimeMillis();
            if (now - mLastUi > 300 || line.startsWith("##")) {
                mLastUi = now;
                Runnable r = sUiRefresh;
                if (r != null) r.run();
            }
        }

        @Override
        public void onExit(int exitCode) {
            if (exitCode == 0) sSetupError = null;
            else if (sSetupError == null) sSetupError = t("安装脚本退出码 ") + exitCode + t("，请查看日志");
            Runnable r = sUiRefresh;
            if (r != null) r.run();
        }
    }
}
