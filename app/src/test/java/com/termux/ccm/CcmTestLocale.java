package com.termux.ccm;

import android.content.Context;

import org.robolectric.RuntimeEnvironment;

/** 测试断言的是中文原文：固定界面语言为中文，避免按 Robolectric 的英文环境加载译文。 */
final class CcmTestLocale {

    private CcmTestLocale() {}

    static void useChinese() {
        Context context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("ccmobile", Context.MODE_PRIVATE).edit()
            .putString(CcmI18n.PREF_LANGUAGE, "zh").commit();
        CcmI18n.init(context);
    }
}
