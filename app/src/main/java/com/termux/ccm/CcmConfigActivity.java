package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.termux.R;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 设置页。保存时生成：
 * ~/.claude/settings.json（Claude Code 配置：API、模型、权限、上下文等）、
 * ~/.ccmobile/config.env（终端与 MCP 使用）。
 */
public class CcmConfigActivity extends AppCompatActivity {

    /** {名称, Base URL, 主模型, 可选模型} —— 模型名以各家文档为准，可手动修改 */
    private static final String[][] PRESETS = {
        {"DeepSeek", "https://api.deepseek.com/anthropic", "deepseek-flash[1m]", "deepseek-flash[1m],deepseek-v4-pro[1m]"},
        {t("智谱"), "https://open.bigmodel.cn/api/anthropic", "glm-4.6", "glm-4.6"},
        {"Kimi", "https://api.moonshot.cn/anthropic", "kimi-k2-turbo-preview", "kimi-k2-turbo-preview"},
        {"Anthropic", "https://api.anthropic.com", "claude-sonnet-5-5", "claude-sonnet-5-5,claude-opus-5-5"},
    };
    private static final String[] APT_MIRRORS = {"tuna", "ustc", "aliyun", "official"};
    private static final String[] APT_MIRROR_LABELS = {t("清华 TUNA"), t("中科大 USTC"), t("阿里云"), t("官方（境外）")};
    private static final String[] NPM_REGISTRIES = {"npmmirror", "official"};
    private static final String[] NPM_REGISTRY_LABELS = {t("npmmirror（国内）"), t("npmjs 官方")};
    private static final String[] EFFORTS = {"low", "medium", "high", "xhigh"};
    private static final String[] EFFORT_LABELS = {t("低"), t("中"), t("高"), t("极高")};

    private CcmUi ui;
    private final Map<String, EditText> mFields = new LinkedHashMap<>();
    private final Map<String, CheckBox> mChecks = new LinkedHashMap<>();
    private Spinner mAptSpinner, mNpmSpinner, mModeSpinner, mEffortSpinner, mChannelSpinner, mDnsSpinner;
    private TextView mCcStatus, mSubStatus;
    private Spinner mAuthSpinner;
    private LinearLayout mApiCard, mSubCard;
    private static final String[] AUTH_MODES = {"api", "subscription"};
    private static final String[] AUTH_MODE_LABELS = {t("API Key（DeepSeek 等第三方 / Anthropic API）"), t("Claude 订阅账号（Pro / Max）")};
    private static final String REPO_URL = "https://github.com/1048835575/CCMoblie";
    private static final String[] CHANNELS = {"stable", "latest"};
    private static final String[] CHANNEL_LABELS = {t("稳定版（2.1.112）"), t("最新版（实验性，自动更新）")};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ui = CcmUi.page(this, t("设置"));
        LinearLayout c = ui.content;

        ui.section(c, "语言 / Language");
        LinearLayout langCard = ui.card(c);
        Spinner lang = spinner(langCard, null, CcmI18n.LANGUAGE_LABELS, CcmI18n.LANGUAGES, CcmI18n.preference(this));
        final int initialLang = lang.getSelectedItemPosition();
        lang.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) {
                if (pos != initialLang) CcmI18n.change(CcmConfigActivity.this, CcmI18n.LANGUAGES[pos]);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        ui.section(c, t("账号与模型"));
        LinearLayout auth = ui.card(c);
        mAuthSpinner = spinner(auth, t("认证方式"), AUTH_MODE_LABELS, AUTH_MODES, CcmConfig.get(this, CcmConfig.KEY_AUTH_MODE));
        mAuthSpinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, android.view.View v, int pos, long id) { updateAuthCards(); }
            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
        });

        // Claude 订阅：账号登录
        mSubCard = ui.card(c);
        mSubStatus = ui.text("", 14, R.color.ccm_text);
        mSubCard.addView(mSubStatus);
        ui.hint(mSubCard, t("使用 Claude Pro / Max 订阅额度，无需 API Key。点「登录」会打开终端运行官方登录流程，")
            + t("在浏览器完成授权后回到 App 即可。凭据由 Claude Code 保存在本机，并自动续期。"));
        LinearLayout subRow = ui.row(mSubCard);
        ui.primary(subRow, t("登录 Claude 账号"), v -> {
            CcmConfig.prefs(this).edit().putString(CcmConfig.KEY_AUTH_MODE, "subscription").apply();
            try { CcmConfig.writeEnv(this); } catch (Exception ignored) {}
            if (!CcmConfig.isEnvironmentInstalled()) {
                Toast.makeText(this, t("请先完成开发环境安装"), Toast.LENGTH_LONG).show();
                return;
            }
            CcmTerminal.runScript(this, "bin/ccm-login", new String[0], CcmProjects.PHONE_WORKSPACE.getAbsolutePath(), t("Claude 登录"));
        });
        ui.outlined(subRow, t("退出登录"), v -> logoutSubscription());
        field(mSubCard, CcmConfig.KEY_SUB_MODEL, t("主模型（sonnet / opus / haiku 或完整模型名）"), false);
        field(mSubCard, CcmConfig.KEY_SUB_MODELS, t("输入框可切换的模型（逗号分隔）"), false);

        // API Key：任意 Anthropic 兼容接口
        LinearLayout api = ui.card(c);
        mApiCard = api;
        ui.hint(api, t("任意 Anthropic 兼容接口。点下方预设可一键填好地址和模型。"));
        LinearLayout presets = ui.row(api);
        for (String[] p : PRESETS) {
            ui.outlined(presets, p[0], v -> {
                mFields.get(CcmConfig.KEY_BASE_URL).setText(p[1]);
                mFields.get(CcmConfig.KEY_MODEL).setText(p[2]);
                mFields.get(CcmConfig.KEY_SMALL_MODEL).setText(p[2]);
                mFields.get(CcmConfig.KEY_MODELS).setText(p[3]);
            }).setTextSize(12);
        }
        field(api, CcmConfig.KEY_BASE_URL, t("API 地址 (ANTHROPIC_BASE_URL)"), false);
        field(api, CcmConfig.KEY_API_KEY, "API Key (ANTHROPIC_AUTH_TOKEN)", true);
        field(api, CcmConfig.KEY_MODEL, t("主模型 (ANTHROPIC_MODEL)，结尾 [1m] 表示 1M 上下文"), false);
        field(api, CcmConfig.KEY_SMALL_MODEL, t("轻量模型 (Haiku / 子代理)"), false);
        field(api, CcmConfig.KEY_MODELS, t("输入框可切换的模型（逗号分隔）"), false);

        updateAuthCards();

        ui.section(c, t("Claude Code 版本"));
        LinearLayout ver = ui.card(c);
        mChannelSpinner = spinner(ver, t("运行版本"), CHANNEL_LABELS, CHANNELS, CcmConfig.get(this, CcmConfig.KEY_CC_CHANNEL));
        mCcStatus = ui.hint(ver, "");
        ui.hint(ver, t("最新版是官方原生程序（约 110MB），通过内置的 musl 加载器在 Android 上运行，App 会每天检查并自动更新。")
            + t("出现问题时切回稳定版即可，稳定版始终保留。"));
        mDnsSpinner = spinner(ver, t("最新版备用 DNS（读不到当前网络 DNS 时使用）"), CcmConfig.NATIVE_DNS_CHOICES,
            CcmConfig.NATIVE_DNS_CHOICES, CcmConfig.get(this, CcmConfig.KEY_NATIVE_DNS));
        ui.outlined(ver, t("立即安装 / 检查更新最新版"), v -> {
            persistChannelAndDns();
            runNativeInstall(false);
        });
        refreshCcStatus();

        ui.section(c, t("Claude Code 行为"));
        LinearLayout beh = ui.card(c);
        mModeSpinner = spinner(beh, t("默认权限模式 (permissions.defaultMode)"), CcmConfig.PERMISSION_MODE_LABELS,
            CcmConfig.PERMISSION_MODES, CcmConfig.get(this, CcmConfig.KEY_PERMISSION_MODE));
        mEffortSpinner = spinner(beh, t("思考强度 (effortLevel)"), EFFORT_LABELS, EFFORTS, CcmConfig.get(this, CcmConfig.KEY_EFFORT));
        EditText maxOut = field(beh, CcmConfig.KEY_MAX_OUTPUT, t("最大输出 token (CLAUDE_CODE_MAX_OUTPUT_TOKENS)"), false);
        maxOut.setInputType(InputType.TYPE_CLASS_NUMBER);
        field(beh, CcmConfig.KEY_PROXY, t("HTTP 代理（可选，如 http://127.0.0.1:7897）"), false);
        check(beh, CcmConfig.KEY_LEAN, t("精简模式：屏蔽手机上少用的工具，减少每轮 token"));
        check(beh, CcmConfig.KEY_AUTOSTART, t("打开终端时自动启动 Claude Code"));
        check(beh, CcmConfig.KEY_OVERLAY, t("后台运行时显示悬浮窗（进度、停止、终端）"));
        ui.outlined(beh, t("悬浮窗权限"), v -> {
            try {
                startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:" + getPackageName())));
            } catch (Exception e) {
                Toast.makeText(this, t("请在系统设置中允许显示悬浮窗"), Toast.LENGTH_LONG).show();
            }
        });

        ui.section(c, t("看图能力"));
        LinearLayout vis = ui.card(c);
        check(vis, CcmConfig.KEY_MAIN_VISION, t("主模型支持识图（deepseek-flash 等），截图直接交给主模型"));
        ui.hint(vis, t("以下为可选：只有主模型不支持图片时才需要填写，填写后截图会改为交给这个 OpenAI 兼容的视觉模型描述成文字（例如通义千问 qwen-vl-max）。"));
        field(vis, CcmConfig.KEY_VISION_BASE_URL, "Base URL", false);
        field(vis, CcmConfig.KEY_VISION_KEY, "API Key", true);
        field(vis, CcmConfig.KEY_VISION_MODEL, t("模型"), false);

        ui.section(c, t("安装源"));
        LinearLayout src = ui.card(c);
        mAptSpinner = spinner(src, t("Termux 软件源"), APT_MIRROR_LABELS, APT_MIRRORS, CcmConfig.get(this, CcmConfig.KEY_APT_MIRROR));
        mNpmSpinner = spinner(src, t("npm 源"), NPM_REGISTRY_LABELS, NPM_REGISTRIES, CcmConfig.get(this, CcmConfig.KEY_NPM_REGISTRY));
        field(src, CcmConfig.KEY_CC_VERSION, t("稳定版 Claude Code 版本号"), false);
        ui.hint(src, t("稳定版通过 Node 运行，2.1.113 起官方 npm 包不再包含 JS 版本，请保持 ") + CcmConfig.DEFAULT_CC_VERSION + t("。"));
        ui.outlined(src, t("重新安装开发环境"), v -> {
            new File(CcmConfig.CCM_DIR, ".installed").delete();
            startActivity(new Intent(this, CcmSetupActivity.class));
        });

        ui.section(c, t("其他"));
        LinearLayout other = ui.card(c);
        ui.outlined(other, t("环境与连接向导"), v -> startActivity(new Intent(this, CcmSetupActivity.class)));
        ui.outlined(other, t("手机控制"), v -> startActivity(new Intent(this, CcmPairActivity.class)));
        TextView path = ui.hint(other, t("配置文件：") + CcmProjects.displayPath(TermuxConstants.TERMUX_HOME_DIR_PATH + "/.claude/settings.json"));
        path.setTextIsSelectable(true);

        ui.section(c, t("关于"));
        LinearLayout about = ui.card(c);
        TextView appVer = ui.text("CCMobile " + com.termux.BuildConfig.VERSION_NAME, 15, R.color.ccm_text);
        appVer.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        about.addView(appVer);
        TextView repo = ui.hint(about, t("开源地址：") + REPO_URL);
        repo.setAutoLinkMask(android.text.util.Linkify.WEB_URLS);
        repo.setText(t("开源地址：") + REPO_URL);
        repo.setLinkTextColor(ContextCompat.getColor(this, R.color.ccm_accent));
        ui.hint(about, t("本应用是非官方的社区项目，与 Anthropic 无关，未获其认可或赞助。")
            + t("Claude 和 Claude Code 是 Anthropic 的商标。Claude Code 由用户在安装时从 npm 下载，")
            + t("其使用受 Anthropic 条款约束，本应用不包含、也不分发 Claude Code。"));
        ui.hint(about, t("本应用基于 Termux（GPLv3）修改，以 GPLv3 开源。使用的开源组件：\n")
            + "· Termux App — GPLv3\n"
            + "· terminal-emulator / terminal-view — Apache-2.0\n"
            + t("· termux-shared — MIT（部分文件 GPLv3）\n")
            + t("· Termux 软件包（bash、apt、nodejs 等）— 各自开源许可证\n")
            + t("· musl libc 加载器 — MIT\n")
            + t("· Markwon、AndroidX、Material Components — Apache-2.0"));

        ui.primary(c, t("保存"), v -> save());
    }

    private void save() {
        android.content.SharedPreferences.Editor e = CcmConfig.prefs(this).edit();
        for (Map.Entry<String, EditText> f : mFields.entrySet()) e.putString(f.getKey(), f.getValue().getText().toString().trim());
        for (Map.Entry<String, CheckBox> ch : mChecks.entrySet()) e.putBoolean(ch.getKey(), ch.getValue().isChecked());
        e.putString(CcmConfig.KEY_APT_MIRROR, APT_MIRRORS[mAptSpinner.getSelectedItemPosition()]);
        e.putString(CcmConfig.KEY_NPM_REGISTRY, NPM_REGISTRIES[mNpmSpinner.getSelectedItemPosition()]);
        e.putString(CcmConfig.KEY_PERMISSION_MODE, CcmConfig.PERMISSION_MODES[mModeSpinner.getSelectedItemPosition()]);
        e.putString(CcmConfig.KEY_EFFORT, EFFORTS[mEffortSpinner.getSelectedItemPosition()]);
        e.putString(CcmConfig.KEY_CC_CHANNEL, CHANNELS[mChannelSpinner.getSelectedItemPosition()]);
        e.putString(CcmConfig.KEY_AUTH_MODE, AUTH_MODES[mAuthSpinner.getSelectedItemPosition()]);
        e.putString(CcmConfig.KEY_NATIVE_DNS, CcmConfig.NATIVE_DNS_CHOICES[mDnsSpinner.getSelectedItemPosition()]);
        // 主模型变了就让输入框跟随新的默认值
        e.remove("chat_model");
        e.apply();
        try {
            CcmConfig.writeEnv(this);
            CcmConfig.writeResolvConf(this);
            Toast.makeText(this, t("已保存，并更新了 settings.json"), Toast.LENGTH_SHORT).show();
            // 选了最新版但还没装：先安装，失败自动回退稳定版
            if (CcmConfig.isLatestChannel(this) && CcmConfig.nativeVersion() == null && CcmConfig.isEnvironmentInstalled()) {
                runNativeInstall(true);
                return;
            }
            finish();
        } catch (Exception ex) {
            Toast.makeText(this, t("写入配置失败：") + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从终端登录回来后刷新登录状态
        if (mSubStatus != null) updateAuthCards();
    }

    private void updateAuthCards() {
        boolean sub = mAuthSpinner.getSelectedItemPosition() == 1;
        mSubCard.setVisibility(sub ? android.view.View.VISIBLE : android.view.View.GONE);
        mApiCard.setVisibility(sub ? android.view.View.GONE : android.view.View.VISIBLE);
        boolean in = CcmConfig.isSubscriptionLoggedIn();
        mSubStatus.setText(in ? t("✓ 已登录 Claude 账号") : t("未登录"));
        mSubStatus.setTextColor(ContextCompat.getColor(this, in ? R.color.ccm_ok : R.color.ccm_text_secondary));
    }

    private void logoutSubscription() {
        try {
            CcmProcess.start(getApplicationContext(), null, null, new CcmProcess.Listener() {
                @Override public void onLine(String line) {}
                @Override public void onExit(int code) {
                    // 无论命令结果如何，删除本机凭据确保退出
                    CcmConfig.credentialsFile().delete();
                    runOnUiThread(() -> {
                        updateAuthCards();
                        Toast.makeText(CcmConfigActivity.this, t("已退出 Claude 账号"), Toast.LENGTH_SHORT).show();
                    });
                }
            }, new java.io.File(CcmConfig.CCM_DIR, "bin/ccm-login").getAbsolutePath(), "--logout");
        } catch (Exception ex) {
            CcmConfig.credentialsFile().delete();
            updateAuthCards();
        }
    }

    private void persistChannelAndDns() {
        CcmConfig.prefs(this).edit()
            .putString(CcmConfig.KEY_CC_CHANNEL, CHANNELS[mChannelSpinner.getSelectedItemPosition()])
            .putString(CcmConfig.KEY_NATIVE_DNS, CcmConfig.NATIVE_DNS_CHOICES[mDnsSpinner.getSelectedItemPosition()])
            .apply();
        try { CcmConfig.writeEnv(this); } catch (Exception ignored) {}
        CcmConfig.writeResolvConf(this);
    }

    private void refreshCcStatus() {
        String nv = CcmConfig.nativeVersion();
        boolean latest = CcmConfig.isLatestChannel(this);
        mCcStatus.setText(t("当前使用：") + (latest && nv != null ? t("最新版 ") + nv : t("稳定版 ") + CcmConfig.get(this, CcmConfig.KEY_CC_VERSION))
            + (latest && nv == null ? t("（最新版尚未安装成功）") : "")
            + (nv != null ? t("\n已下载的最新版：") + nv : ""));
    }

    /** 运行 ccm-native 安装 / 更新最新版，弹窗显示实时日志。revertOnFail：失败时把版本切回稳定版。 */
    private void runNativeInstall(boolean revertOnFail) {
        if (!CcmConfig.isEnvironmentInstalled()) {
            Toast.makeText(this, t("请先完成开发环境安装"), Toast.LENGTH_LONG).show();
            return;
        }
        TextView log = ui.text(t("准备中…"), 12, R.color.ccm_text_secondary);
        log.setTypeface(android.graphics.Typeface.MONOSPACE);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.setPadding(22 * ui.dp, 8 * ui.dp, 22 * ui.dp, 0);
        sv.addView(log);
        androidx.appcompat.app.AlertDialog dialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
            .setTitle(t("安装最新版 Claude Code")).setView(sv).setCancelable(false)
            .setPositiveButton(t("后台运行"), null).create();
        dialog.show();
        final StringBuilder sb = new StringBuilder();
        final String[] result = {null, null}; // {版本, 错误}
        try {
            CcmProcess.start(getApplicationContext(), null, null, new CcmProcess.Listener() {
                @Override public void onLine(String line) {
                    if (line.startsWith("CCM_NATIVE_VERSION=")) result[0] = line.substring(19);
                    else if (line.startsWith("CCM_NATIVE_ERROR=")) result[1] = line.substring(17);
                    else if (!line.startsWith("CCM_NATIVE_")) {
                        sb.append(line).append('\n');
                        runOnUiThread(() -> { log.setText(sb); sv.post(() -> sv.fullScroll(android.view.View.FOCUS_DOWN)); });
                    }
                }

                @Override public void onExit(int code) {
                    runOnUiThread(() -> {
                        boolean ok = code == 0 && result[0] != null && !result[0].isEmpty();
                        if (!ok && revertOnFail) {
                            CcmConfig.prefs(CcmConfigActivity.this).edit().putString(CcmConfig.KEY_CC_CHANNEL, "stable").apply();
                            try { CcmConfig.writeEnv(CcmConfigActivity.this); } catch (Exception ignored) {}
                            mChannelSpinner.setSelection(0);
                        }
                        if (!isFinishing()) {
                            refreshCcStatus();
                            dialog.setTitle(ok ? t("最新版已就绪：") + result[0] : t("安装失败"));
                            log.setText(sb + (ok ? t("\n下一轮对话起生效。") : "\n" + (result[1] == null ? t("请查看上面的日志") : result[1])
                                + (revertOnFail ? t("\n已切回稳定版 2.1.112。") : "")));
                            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setText(t("完成"));
                        }
                    });
                }
            }, new java.io.File(CcmConfig.CCM_DIR, "bin/ccm-native").getAbsolutePath(), "install");
        } catch (Exception ex) {
            dialog.dismiss();
            Toast.makeText(this, t("无法启动安装：") + ex.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private EditText field(LinearLayout parent, String key, String label, boolean secret) {
        EditText et = ui.field(parent, label, CcmConfig.get(this, key), secret);
        mFields.put(key, et);
        if (secret) {
            CheckBox show = new CheckBox(this);
            show.setText(t("显示"));
            show.setTextColor(ContextCompat.getColor(this, R.color.ccm_text_secondary));
            show.setOnCheckedChangeListener((b, checked) -> {
                et.setInputType(InputType.TYPE_CLASS_TEXT | (checked ? InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD : InputType.TYPE_TEXT_VARIATION_PASSWORD));
                et.setSelection(et.getText().length());
            });
            parent.addView(show);
        }
        return et;
    }

    private void check(LinearLayout parent, String key, String label) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(ContextCompat.getColor(this, R.color.ccm_text));
        cb.setChecked(CcmConfig.getBool(this, key));
        cb.setPadding(4 * ui.dp, 8 * ui.dp, 0, 8 * ui.dp);
        parent.addView(cb);
        mChecks.put(key, cb);
    }

    private Spinner spinner(LinearLayout parent, String label, String[] labels, String[] values, String current) {
        if (label != null) {
            TextView l = ui.text(label, 13, R.color.ccm_text_secondary);
            l.setPadding(2 * ui.dp, 12 * ui.dp, 0, 6 * ui.dp);
            parent.addView(l);
        }
        Spinner sp = new Spinner(this);
        sp.setBackgroundResource(R.drawable.ccm_bg_field);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        sp.setAdapter(adapter);
        for (int i = 0; i < values.length; i++) if (values[i].equals(current)) sp.setSelection(i);
        parent.addView(sp, CcmUi.matchWrap());
        return sp;
    }
}
