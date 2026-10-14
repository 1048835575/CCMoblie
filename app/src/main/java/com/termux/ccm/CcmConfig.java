package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.AssetManager;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** CCMobile 配置：保存在 SharedPreferences，并同步写成终端里可 source 的 config.env。 */
public final class CcmConfig {

    private static final String LOG_TAG = "CcmConfig";
    private static final String PREFS = "ccmobile";
    private static final String ASSET_ROOT = "ccmobile";

    /** 认证方式：api（API Key，第三方模型或 Anthropic API）/ subscription（Claude Pro / Max 账号登录） */
    public static final String KEY_AUTH_MODE = "auth_mode";
    /** 订阅模式下的主模型与可切换模型（Claude Code 的模型别名） */
    public static final String KEY_SUB_MODEL = "sub_model";
    public static final String KEY_SUB_MODELS = "sub_models";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_MODEL = "model";
    public static final String KEY_SMALL_MODEL = "small_model";
    public static final String KEY_APT_MIRROR = "apt_mirror";
    public static final String KEY_NPM_REGISTRY = "npm_registry";
    public static final String KEY_CC_VERSION = "cc_version";
    /** Claude Code 版本通道：stable（固定 2.1.112，Node 运行）/ latest（原生最新版，实验性，自动更新） */
    public static final String KEY_CC_CHANNEL = "cc_channel";
    /** 最新版（musl）在读不到系统 DNS 时使用的公共 DNS，最长 9 个字符 */
    public static final String KEY_NATIVE_DNS = "native_dns";
    public static final String[] NATIVE_DNS_CHOICES = {"223.5.5.5", "223.6.6.6", "8.8.8.8", "1.1.1.1", "9.9.9.9"};
    public static final File NATIVE_DIR = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".ccmobile/native");
    public static final String KEY_AUTOSTART = "autostart";
    public static final String KEY_LEAN = "lean";
    public static final String KEY_OVERLAY = "overlay";
    /** 主模型能否直接看图（deepseek-flash 等支持）；开启时截图直接交给主模型 */
    public static final String KEY_MAIN_VISION = "main_vision";
    /** 可在输入框切换的模型列表，逗号分隔 */
    public static final String KEY_MODELS = "models";
    public static final String KEY_PROXY = "proxy";
    public static final String KEY_PERMISSION_MODE = "permission_mode";
    public static final String KEY_EFFORT = "effort";
    public static final String KEY_MAX_OUTPUT = "max_output_tokens";
    public static final String KEY_VISION_BASE_URL = "vision_base_url";
    public static final String KEY_VISION_KEY = "vision_key";
    public static final String KEY_VISION_MODEL = "vision_model";
    /** 高级模式：允许通过无线调试（adb）控制手机，默认关闭；手机控制默认走无障碍服务 */
    public static final String KEY_ADB_ENABLED = "adb_enabled";
    /** 高级模式的到期时间（毫秒时间戳）：开启后最多保持 24 小时，到期需重新确认 */
    public static final String KEY_ADB_UNTIL = "adb_until";
    public static final long ADB_TTL_MS = 24L * 60 * 60 * 1000;
    /** 敏感应用（银行、支付等）包名，逗号分隔：无障碍服务在这些应用中拒绝读屏和操作 */
    public static final String KEY_SENSITIVE_APPS = "sensitive_apps";
    public static final String DEFAULT_SENSITIVE_APPS = "com.eg.android.AlipayGphone,com.unionpay,cmb.pb,com.icbc,"
        + "com.chinamworld.main,com.android.bankabc,com.chinamworld.bocmbci,com.bankcomm.Bankcomm,com.ecitic.bank.mobile,"
        + "com.cib.cibmb,com.spdbccc.app,cn.com.spdb.mobilebank.per,com.pingan.paces.ccms,com.yitong.mbank.psbc,"
        + "com.hxb.mobile.client,com.cmbc.cc.mbank,com.webank.wemoney,com.jd.jrapp,com.paypal.android.p2pmobile,"
        + "com.google.android.apps.walletnfcrel";

    public static final String DEFAULT_BASE_URL = "https://api.deepseek.com/anthropic";
    public static final String DEFAULT_MODEL = "deepseek-flash[1m]";
    public static final String DEFAULT_MODELS = "deepseek-flash[1m],deepseek-v4-pro[1m]";
    /** Claude Code 2.1.112 支持的权限模式（--permission-mode / permissions.defaultMode） */
    public static final String[] PERMISSION_MODES = {"auto", "acceptEdits", "bypassPermissions", "plan", "default"};
    public static final String[] PERMISSION_MODE_LABELS = {t("自动"), t("自动编辑"), t("完全放行"), t("只规划"), t("默认")};
    /** 2.1.113 起 npm 包改为原生二进制，无法在 Android 上运行；2.1.112 是最后一个纯 JS 版本。 */
    public static final String DEFAULT_CC_VERSION = "2.1.112";

    public static final File CCM_DIR = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".ccmobile");

    private CcmConfig() {}

    public static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static String get(Context context, String key) {
        SharedPreferences p = prefs(context);
        switch (key) {
            case KEY_BASE_URL: return p.getString(key, DEFAULT_BASE_URL);
            case KEY_MODEL: return p.getString(key, DEFAULT_MODEL);
            case KEY_SMALL_MODEL: return p.getString(key, p.getString(KEY_MODEL, DEFAULT_MODEL));
            case KEY_MODELS: return p.getString(key, DEFAULT_MODELS);
            case KEY_PERMISSION_MODE: return p.getString(key, "auto");
            case KEY_EFFORT: return p.getString(key, "high");
            case KEY_MAX_OUTPUT: return p.getString(key, "16384");
            case KEY_APT_MIRROR: return p.getString(key, "tuna");
            case KEY_NPM_REGISTRY: return p.getString(key, "npmmirror");
            case KEY_CC_VERSION: return p.getString(key, DEFAULT_CC_VERSION);
            case KEY_CC_CHANNEL: return p.getString(key, "stable");
            case KEY_AUTH_MODE: return p.getString(key, "api");
            case KEY_SUB_MODEL: return p.getString(key, "sonnet");
            case KEY_SUB_MODELS: return p.getString(key, "sonnet,opus,haiku");
            case KEY_NATIVE_DNS: return p.getString(key, NATIVE_DNS_CHOICES[0]);
            case KEY_SENSITIVE_APPS: return p.getString(key, DEFAULT_SENSITIVE_APPS);
            default: return p.getString(key, "");
        }
    }

    public static boolean getBool(Context context, String key) {
        boolean def = KEY_LEAN.equals(key) || KEY_OVERLAY.equals(key) || KEY_MAIN_VISION.equals(key);
        return prefs(context).getBoolean(key, def);
    }

    /** 高级模式（无线调试）是否开启且未过期。默认关闭，只能在「手机控制」页经多次确认后开启。 */
    public static boolean isAdbEnabled(Context context) {
        SharedPreferences p = prefs(context);
        if (!p.getBoolean(KEY_ADB_ENABLED, false)) return false;
        if (System.currentTimeMillis() < p.getLong(KEY_ADB_UNTIL, 0)) return true;
        p.edit().putBoolean(KEY_ADB_ENABLED, false).apply();
        return false;
    }

    public static void setAdbEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_ADB_ENABLED, on)
            .putLong(KEY_ADB_UNTIL, on ? System.currentTimeMillis() + ADB_TTL_MS : 0).apply();
    }

    public static boolean isSubscription(Context context) {
        return "subscription".equals(get(context, KEY_AUTH_MODE));
    }

    /** Claude 账号登录后 Claude Code 写入的凭据文件 */
    public static File credentialsFile() {
        return new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".claude/.credentials.json");
    }

    public static boolean isSubscriptionLoggedIn() {
        try {
            File f = credentialsFile();
            return f.exists() && readString(f).contains("accessToken");
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isConfigured(Context context) {
        if (isSubscription(context)) return isSubscriptionLoggedIn();
        return !get(context, KEY_API_KEY).trim().isEmpty();
    }

    /** 当前认证方式下的主模型 */
    public static String mainModel(Context context) {
        return (isSubscription(context) ? get(context, KEY_SUB_MODEL) : get(context, KEY_MODEL)).trim();
    }

    public static boolean isEnvironmentInstalled() {
        return new File(CCM_DIR, ".installed").exists();
    }

    /** 每次 App 启动时调用：释放脚本、写 profile 钩子和配置。很快，可在主线程调用。 */
    public static void installAssets(Context context) {
        try {
            copyAssetDir(context.getAssets(), ASSET_ROOT, CCM_DIR);
            File bin = new File(CCM_DIR, "bin");
            File[] files = bin.listFiles();
            if (files != null) for (File f : files) f.setExecutable(true, true);

            File profileDir = new File(TermuxConstants.TERMUX_PREFIX_DIR_PATH, "etc/profile.d");
            profileDir.mkdirs();
            copyFile(new File(CCM_DIR, "profile.sh"), new File(profileDir, "ccmobile.sh"));

            new File(CCM_DIR, "workspace").mkdirs();
            installWorkspaceInstructions();
            writeEnv(context);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "installAssets failed", e);
        }
        new Thread(() -> writeAppList(context)).start();
        writeResolvConf(context);
    }

    /** 写 config.env（终端 source）和 mcp.json（Claude Code 的 MCP 配置）。 */
    public static void writeEnv(Context context) throws IOException {
        CCM_DIR.mkdirs();
        StringBuilder sb = new StringBuilder(t("# 由 CCMobile 配置页生成，手动修改会被覆盖\n"));
        sb.append("unset ANTHROPIC_API_KEY\n");
        export(sb, "CCM_AUTH_MODE", get(context, KEY_AUTH_MODE));
        export(sb, "CCM_LANG", CcmI18n.lang());
        if (isSubscription(context)) {
            sb.append("unset ANTHROPIC_BASE_URL ANTHROPIC_AUTH_TOKEN ANTHROPIC_MODEL\n");
        } else {
            export(sb, "ANTHROPIC_BASE_URL", get(context, KEY_BASE_URL));
            export(sb, "ANTHROPIC_AUTH_TOKEN", get(context, KEY_API_KEY));
        }
        export(sb, "CCM_APT_MIRROR", get(context, KEY_APT_MIRROR));
        export(sb, "CCM_NPM_REGISTRY", get(context, KEY_NPM_REGISTRY));
        export(sb, "CCM_CC_VERSION", get(context, KEY_CC_VERSION));
        export(sb, "CCM_CC_CHANNEL", get(context, KEY_CC_CHANNEL));
        export(sb, "CCM_NATIVE_DNS", get(context, KEY_NATIVE_DNS));
        export(sb, "CCM_AUTOSTART", getBool(context, KEY_AUTOSTART) ? "1" : "0");
        export(sb, "CCM_PERMISSION_MODE", get(context, KEY_PERMISSION_MODE));
        export(sb, "CCM_LEAN", getBool(context, KEY_LEAN) ? "1" : "0");
        boolean adb = isAdbEnabled(context);
        export(sb, "CCM_ADB_ENABLED", adb ? "1" : "0");
        export(sb, "CCM_ADB_UNTIL", adb ? String.valueOf(prefs(context).getLong(KEY_ADB_UNTIL, 0)) : "0");
        export(sb, "CCM_SENSITIVE_APPS", get(context, KEY_SENSITIVE_APPS));
        export(sb, "CCM_MAIN_VISION", getBool(context, KEY_MAIN_VISION) ? "1" : "0");
        export(sb, "CCM_VISION_BASE_URL", get(context, KEY_VISION_BASE_URL));
        export(sb, "CCM_VISION_KEY", get(context, KEY_VISION_KEY));
        export(sb, "CCM_VISION_MODEL", get(context, KEY_VISION_MODEL));
        File env = new File(CCM_DIR, "config.env");
        writeString(env, sb.toString());
        env.setReadable(false, false);
        env.setReadable(true, true);

        try {
            JSONObject phone = new JSONObject()
                .put("command", "node")
                .put("args", new JSONArray().put(new File(CCM_DIR, "phone_mcp.js").getAbsolutePath()));
            JSONObject root = new JSONObject().put("mcpServers", new JSONObject().put("phone", phone));
            writeString(new File(CCM_DIR, "mcp.json"), root.toString(2));
            writeClaudeSettings(context);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException(e);
        }
    }

    /**
     * 按用户填的 API 地址和模型生成 ~/.claude/settings.json。
     * 与已有文件合并：只覆盖由 CCMobile 管理的字段，用户手动加的 hooks 等保持不变。
     */
    private static void writeClaudeSettings(Context context) throws Exception {
        File dir = new File(TermuxConstants.TERMUX_HOME_DIR_PATH, ".claude");
        dir.mkdirs();
        File file = new File(dir, "settings.json");
        JSONObject root = new JSONObject();
        if (file.exists()) {
            try {
                root = new JSONObject(readString(file));
            } catch (Exception e) {
                Logger.logError(LOG_TAG, t("settings.json 解析失败，将重新生成: ") + e.getMessage());
            }
        }
        boolean sub = isSubscription(context);
        String model = mainModel(context);
        String small = sub ? "haiku" : get(context, KEY_SMALL_MODEL).trim();
        boolean ctx1m = !sub && model.endsWith("[1m]");

        JSONObject env = root.optJSONObject("env");
        if (env == null) env = new JSONObject();
        env.put("CLAUDE_AUTOCOMPACT_PCT_OVERRIDE", "85");
        env.put("API_TIMEOUT_MS", "600000");
        env.put("DISABLE_TELEMETRY", "1");
        env.put("DISABLE_AUTOUPDATER", "1");
        env.put("CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC", "1");
        env.put("CLAUDE_CODE_EXPERIMENTAL_AGENT_TEAMS", "1");
        env.put("CLAUDE_CODE_ATTRIBUTION_HEADER", "0");
        env.put("ENABLE_TOOL_SEARCH", "true");
        env.put("USE_BUILTIN_RIPGREP", "0");
        // 第三方 / API 模式：用环境变量把所有模型档位映射到用户填的模型；订阅模式交给 Claude Code 默认处理
        String[] apiKeys = {"ANTHROPIC_BASE_URL", "ANTHROPIC_AUTH_TOKEN", "ANTHROPIC_MODEL", "ANTHROPIC_DEFAULT_SONNET_MODEL",
            "ANTHROPIC_DEFAULT_OPUS_MODEL", "ANTHROPIC_DEFAULT_HAIKU_MODEL", "CLAUDE_CODE_SUBAGENT_MODEL"};
        if (sub) {
            for (String k : apiKeys) env.remove(k);
        } else {
            env.put("ANTHROPIC_BASE_URL", get(context, KEY_BASE_URL).trim());
            env.put("ANTHROPIC_AUTH_TOKEN", get(context, KEY_API_KEY).trim());
            env.put("ANTHROPIC_MODEL", model);
            env.put("ANTHROPIC_DEFAULT_SONNET_MODEL", model);
            env.put("ANTHROPIC_DEFAULT_OPUS_MODEL", model);
            env.put("ANTHROPIC_DEFAULT_HAIKU_MODEL", small);
            env.put("CLAUDE_CODE_SUBAGENT_MODEL", small);
        }
        env.put("CLAUDE_CODE_MAX_OUTPUT_TOKENS", get(context, KEY_MAX_OUTPUT).trim());
        if (ctx1m) {
            env.put("CLAUDE_CODE_MAX_CONTEXT_TOKENS", "1000000");
            env.put("CLAUDE_CODE_AUTO_COMPACT_WINDOW", "950000");
        } else {
            env.remove("CLAUDE_CODE_MAX_CONTEXT_TOKENS");
            env.remove("CLAUDE_CODE_AUTO_COMPACT_WINDOW");
        }
        if (sub) env.remove("CLAUDE_CODE_AUTO_MODE_SERVER"); else env.put("CLAUDE_CODE_AUTO_MODE_SERVER", "0");
        String proxy = get(context, KEY_PROXY).trim();
        if (!proxy.isEmpty()) {
            env.put("HTTP_PROXY", proxy);
            env.put("HTTPS_PROXY", proxy);
        } else {
            env.remove("HTTP_PROXY");
            env.remove("HTTPS_PROXY");
        }
        root.put("$schema", "https://json.schemastore.org/claude-code-settings.json");
        root.put("env", env);
        root.put("attribution", new JSONObject().put("commit", "").put("pr", "").put("sessionUrl", false));

        JSONObject perms = root.optJSONObject("permissions");
        if (perms == null) perms = new JSONObject();
        JSONArray allow = perms.optJSONArray("allow");
        if (allow == null) allow = new JSONArray();
        boolean hasPhone = false;
        for (int i = 0; i < allow.length(); i++) if ("mcp__phone".equals(allow.optString(i))) hasPhone = true;
        if (!hasPhone) allow.put("mcp__phone");
        perms.put("allow", allow);
        perms.put("defaultMode", get(context, KEY_PERMISSION_MODE));
        if (!perms.has("additionalDirectories")) perms.put("additionalDirectories", new JSONArray());
        root.put("permissions", perms);

        root.put("language", CcmI18n.isChinese() ? "chinese" : "english");
        root.put("effortLevel", get(context, KEY_EFFORT));
        if (sub) {
            root.remove("modelSettings");
        } else {
            String bare = model.replace("[1m]", "");
            root.put("modelSettings", new JSONObject().put(bare, new JSONObject().put("effortLevel", get(context, KEY_EFFORT))));
        }
        root.put("skipAutoPermissionPrompt", true);
        root.put("autoMode", new JSONObject().put("allow", new JSONArray().put("*")));
        root.put("model", model);
        writeString(file, root.toString(2).replace("\\/", "/"));
    }

    /** 输入框可切换的模型（去重，主模型排第一）。 */
    public static List<String> modelChoices(Context context) {
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        set.add(mainModel(context));
        String list = isSubscription(context) ? get(context, KEY_SUB_MODELS) : get(context, KEY_MODELS);
        for (String m : list.split("[,，\\s]+")) if (!m.trim().isEmpty()) set.add(m.trim());
        return new java.util.ArrayList<>(set);
    }

    /**
     * 手机助手工作区的 CLAUDE.md 按界面语言放置；用户改过的（与两份模板都不同）不覆盖。
     */
    private static void installWorkspaceInstructions() {
        try {
            File dest = new File(CCM_DIR, "workspace/CLAUDE.md");
            String zh = readString(new File(CCM_DIR, "CLAUDE.md"));
            String en = readString(new File(CCM_DIR, "CLAUDE.en.md"));
            String cur = dest.exists() ? readString(dest) : null;
            String want = CcmI18n.isChinese() ? zh : en;
            if (cur == null || ((cur.equals(zh) || cur.equals(en)) && !cur.equals(want)) || isOldTemplate(cur)) writeString(dest, want);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "installWorkspaceInstructions failed", e);
        }
    }

    /** 0.9.0 的旧模板（无线调试为主），用户未补充内容时升级为新模板 */
    private static boolean isOldTemplate(String cur) {
        String t = cur.trim();
        boolean untouched = t.endsWith("## 用户习惯（可自行补充）") || t.endsWith("## User preferences (add your own)");
        return untouched && (t.contains("里重新连接 / 配对") || t.contains("reconnect / pair under"));
    }

    public static boolean isLatestChannel(Context context) {
        return "latest".equals(get(context, KEY_CC_CHANNEL));
    }

    /** 已安装的原生（最新版）Claude Code 版本号，未安装返回 null。 */
    public static String nativeVersion() {
        try {
            File f = new File(NATIVE_DIR, "current/VERSION");
            if (!f.exists() || !new File(NATIVE_DIR, "current/claude").exists()) return null;
            return readString(f).trim();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 最新版运行于 musl，读不到 Android 的系统 DNS。这里把当前网络的 DNS 服务器写成 resolv.conf，
     * 启动时作为 fd 9 交给 musl；最后附上用户选择的公共 DNS 兜底。
     */
    public static void writeResolvConf(Context context) {
        try {
            StringBuilder sb = new StringBuilder(t("# 由 CCMobile 按当前网络生成\n"));
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                android.net.LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
                if (lp != null) {
                    for (java.net.InetAddress a : lp.getDnsServers()) {
                        // musl 不支持带作用域的链路本地 IPv6，跳过
                        if (a instanceof java.net.Inet6Address && a.isLinkLocalAddress()) continue;
                        sb.append("nameserver ").append(a.getHostAddress()).append('\n');
                    }
                }
            }
            sb.append("nameserver ").append(get(context, KEY_NATIVE_DNS)).append('\n');
            sb.append("options timeout:3 attempts:2\n");
            NATIVE_DIR.mkdirs();
            writeString(new File(NATIVE_DIR, "resolv.conf"), sb.toString());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "writeResolvConf failed", e);
        }
    }

    /** 已安装应用「名称\t包名」列表，供 MCP 的 phone_launch 按中文名查找。 */
    private static void writeAppList(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(intent, 0);
            StringBuilder sb = new StringBuilder();
            for (ResolveInfo ri : list) {
                String label = String.valueOf(ri.loadLabel(pm)).replace('\t', ' ').replace('\n', ' ');
                sb.append(label).append('\t').append(ri.activityInfo.packageName).append('\n');
            }
            writeString(new File(CCM_DIR, "apps.tsv"), sb.toString());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "writeAppList failed", e);
        }
    }

    private static void export(StringBuilder sb, String name, String value) {
        sb.append("export ").append(name).append("='")
            .append(value == null ? "" : value.trim().replace("'", "'\\''")).append("'\n");
    }

    private static void copyAssetDir(AssetManager am, String assetPath, File dest) throws IOException {
        String[] children = am.list(assetPath);
        if (children == null || children.length == 0) {
            try (InputStream in = am.open(assetPath)) {
                writeStream(in, dest);
            }
            return;
        }
        dest.mkdirs();
        for (String child : children) copyAssetDir(am, assetPath + "/" + child, new File(dest, child));
    }

    private static void copyFile(File src, File dest) throws IOException {
        try (InputStream in = new java.io.FileInputStream(src)) {
            writeStream(in, dest);
        }
    }

    private static void writeStream(InputStream in, File dest) throws IOException {
        File tmp = new File(dest.getPath() + ".tmp");
        try (OutputStream out = new FileOutputStream(tmp)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        if (!tmp.renameTo(dest)) throw new IOException("rename failed: " + dest);
    }

    public static String readString(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toString("UTF-8");
        }
    }

    static void writeString(File dest, String s) throws IOException {
        writeStream(new java.io.ByteArrayInputStream(s.getBytes(StandardCharsets.UTF_8)), dest);
    }
}
