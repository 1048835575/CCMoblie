package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import com.termux.app.TermuxService;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_SERVICE;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** 在 Termux 终端里打开指定项目 / 会话的交互式 Claude Code（ccm --resume）。 */
public final class CcmTerminal {

    private CcmTerminal() {}

    /**
     * @param sessionId  要接续的会话，null 表示新会话
     * @param taskRunning 后台任务仍在运行时用 --fork-session 分叉，避免两个进程同时写同一会话
     */
    public static void open(Context context, String projectName, String projectPath, String sessionId, boolean taskRunning) {
        if (!CcmConfig.isEnvironmentInstalled()) {
            Toast.makeText(context, t("开发环境还没安装完成"), Toast.LENGTH_LONG).show();
            return;
        }
        List<String> args = new ArrayList<>();
        if (sessionId != null && !sessionId.isEmpty()) {
            args.add("--resume");
            args.add(sessionId);
            if (taskRunning) args.add("--fork-session");
        }
        if (runScript(context, "bin/ccm", args.toArray(new String[0]), projectPath, "Claude Code · " + projectName) && taskRunning) {
            Toast.makeText(context, t("任务仍在后台运行，终端里打开的是会话的分支副本"), Toast.LENGTH_LONG).show();
        }
    }

    /** 在新的终端会话中运行 ~/.ccmobile 下的脚本并切到终端界面。 */
    public static boolean runScript(Context context, String script, String[] args, String workDir, String label) {
        Intent i = new Intent(TERMUX_SERVICE.ACTION_SERVICE_EXECUTE)
            .setClass(context, TermuxService.class)
            .setData(Uri.fromFile(new File(CcmConfig.CCM_DIR, script)))
            .putExtra(TERMUX_SERVICE.EXTRA_ARGUMENTS, args)
            .putExtra(TERMUX_SERVICE.EXTRA_WORKDIR, workDir)
            .putExtra(TERMUX_SERVICE.EXTRA_SESSION_ACTION,
                String.valueOf(TERMUX_SERVICE.VALUE_EXTRA_SESSION_ACTION_SWITCH_TO_NEW_SESSION_AND_OPEN_ACTIVITY))
            .putExtra(TERMUX_SERVICE.EXTRA_SHELL_NAME, label)
            .putExtra(TERMUX_SERVICE.EXTRA_COMMAND_LABEL, label);
        try {
            context.startService(i);
            return true;
        } catch (Exception e) {
            Toast.makeText(context, t("打开终端失败：") + e.getMessage(), Toast.LENGTH_LONG).show();
            return false;
        }
    }
}
