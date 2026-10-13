package com.termux.ccm;

import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;

import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.os.LocaleListCompat;

import com.termux.shared.logger.Logger;

import org.json.JSONObject;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 界面多语言（gettext 风格）：代码中以中文原文作为键，用 {@link #t(String)} 包裹；
 * 其他语言的译文放在 assets/ccmobile/i18n/&lt;语言&gt;.json（中文原文 → 译文），缺失时回退中文。
 * 布局 XML 中的文字使用标准资源（values / values-zh），由 AppCompat 的应用语言统一切换。
 */
public final class CcmI18n {

    private static final String LOG_TAG = "CcmI18n";
    public static final String PREF_LANGUAGE = "app_language";
    /** 设置项取值：auto 跟随系统 */
    public static final String[] LANGUAGES = {"auto", "zh", "en"};
    public static final String[] LANGUAGE_LABELS = {"跟随系统 / Auto", "简体中文", "English"};

    private static String sLang = "zh";
    private static Map<String, String> sTable = new HashMap<>();

    private CcmI18n() {}

    /** 在 Application 启动时调用：确定语言、加载译文、同步 AppCompat 应用语言。 */
    public static void init(Context context) {
        sLang = resolve(context);
        sTable = sLang.equals("zh") ? new HashMap<>() : load(context, sLang);
        LocaleListCompat target = LocaleListCompat.forLanguageTags(sLang.equals("zh") ? "zh-CN" : sLang);
        if (!target.equals(AppCompatDelegate.getApplicationLocales())) AppCompatDelegate.setApplicationLocales(target);
    }

    /** 当前界面语言：zh / en */
    public static String lang() {
        return sLang;
    }

    public static boolean isChinese() {
        return "zh".equals(sLang);
    }

    /** 翻译：传入中文原文，返回当前语言的译文。 */
    public static String t(String zh) {
        if (zh == null || sTable.isEmpty()) return zh;
        String v = sTable.get(zh);
        return v == null || v.isEmpty() ? zh : v;
    }

    public static String preference(Context context) {
        // 不经过 CcmConfig：避免它的静态文案在译文加载前就被初始化
        return context.getSharedPreferences("ccmobile", Context.MODE_PRIVATE).getString(PREF_LANGUAGE, "auto");
    }

    /** 切换语言并重启 App，让所有界面（含静态文案）立即生效。 */
    public static void change(Context context, String pref) {
        context.getSharedPreferences("ccmobile", Context.MODE_PRIVATE).edit().putString(PREF_LANGUAGE, pref).commit();
        init(context);
        try { CcmConfig.writeEnv(context); } catch (Exception ignored) {}
        Intent i = new Intent(context, CcmHomeActivity.class)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(i);
        Runtime.getRuntime().exit(0);
    }

    private static String resolve(Context context) {
        String pref = preference(context);
        if (!"auto".equals(pref)) return pref;
        Locale sys = Resources.getSystem().getConfiguration().locale;
        return sys != null && "zh".equals(sys.getLanguage()) ? "zh" : "en";
    }

    private static Map<String, String> load(Context context, String lang) {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = context.getAssets().open("ccmobile/i18n/" + lang + ".json")) {
            byte[] buf = new byte[in.available()];
            int off = 0, n;
            while (off < buf.length && (n = in.read(buf, off, buf.length - off)) > 0) off += n;
            JSONObject o = new JSONObject(new String(buf, 0, off, StandardCharsets.UTF_8));
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                String k = it.next();
                map.put(k, o.optString(k));
            }
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "load " + lang + " failed: " + e.getMessage());
        }
        return map;
    }
}
