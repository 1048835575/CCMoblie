package com.termux.ccm;

import android.app.Activity;
import android.content.Context;

import com.termux.app.TermuxInstaller;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.termux.TermuxConstants;

import java.io.File;

/**
 * 共享存储访问（等同 termux-setup-storage）：提前申请文件访问权限，授权后自动建立
 * ~/storage/shared、downloads、dcim 等链接，AI 生成的文件可以放到用户能直接打开的位置。
 */
public final class CcmStorage {

    public static final int REQUEST_CODE = PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;
    private static final String PREF_ASKED = "storage_asked";
    private static long sLinkingSince;

    private CcmStorage() {}

    public static boolean isGranted(Context context) {
        return PermissionUtils.checkStoragePermission(context, PermissionUtils.isLegacyExternalStoragePossible(context));
    }

    /** 弹出系统授权（已授权则直接建立链接）。结果在 Activity 的权限回调里交给 {@link #onPermissionResult}。 */
    public static void request(Activity activity) {
        CcmConfig.prefs(activity).edit().putBoolean(PREF_ASKED, true).apply();
        if (PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(activity, REQUEST_CODE, false)) {
            ensureLinks(activity);
        }
    }

    /** 只在从没问过时申请一次，用户拒绝后不反复打扰。 */
    public static void requestOnce(Activity activity) {
        if (isGranted(activity)) { ensureLinks(activity); return; }
        if (CcmConfig.prefs(activity).getBoolean(PREF_ASKED, false)) return;
        request(activity);
    }

    /** 在 onRequestPermissionsResult / onActivityResult 中调用。 */
    public static void onPermissionResult(Context context, int requestCode) {
        if (requestCode == REQUEST_CODE && isGranted(context)) ensureLinks(context);
    }

    /** 已授权、环境已装好但还没有 ~/storage/shared 时建立链接（环境没装好时等装好后再建）。 */
    public static void ensureLinks(Context context) {
        if (!isGranted(context) || !CcmConfig.isEnvironmentInstalled()) return;
        if (new File(TermuxConstants.TERMUX_STORAGE_HOME_DIR, "shared").exists()) return;
        // setupStorageSymlinks 在后台线程执行，避免短时间内重复触发
        long now = System.currentTimeMillis();
        if (now - sLinkingSince < 10_000) return;
        sLinkingSince = now;
        TermuxInstaller.setupStorageSymlinks(context.getApplicationContext());
    }
}
