package com.termux.ccm;

import android.content.Context;

import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.shell.command.environment.TermuxShellEnvironment;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 在 Termux 环境里后台运行命令，逐行回调输出（stdout 与 stderr 合并）。回调在后台线程。 */
public final class CcmProcess {

    private static final String LOG_TAG = "CcmProcess";

    public interface Listener {
        void onLine(String line);
        void onExit(int exitCode);
    }

    private final Process mProcess;
    private volatile boolean mRunning = true;
    /** 保持打开的 stdin（用于 stream-json 双向通信），按顺序写入 */
    private OutputStream mStdin;
    private final java.util.concurrent.ExecutorService mWriter = java.util.concurrent.Executors.newSingleThreadExecutor();

    private CcmProcess(Process process) {
        mProcess = process;
    }

    /** 用 bash 执行 ~/.ccmobile/bin 下的脚本（或任意命令）。stdin 不为 null 时写入后关闭。 */
    public static CcmProcess start(Context context, String stdin, Listener listener, String... command) throws Exception {
        return start(context, null, stdin, listener, command);
    }

    /** extraEnv 会覆盖默认环境变量（如 CCM_CWD、CCM_MODEL）。 */
    public static CcmProcess start(Context context, Map<String, String> extraEnv, String stdin, Listener listener, String... command) throws Exception {
        return start(context, extraEnv, stdin, false, listener, command);
    }

    /** keepStdinOpen 为 true 时写入 stdin 后不关闭，之后可用 {@link #writeLine} 继续写、{@link #closeStdin} 关闭。 */
    public static CcmProcess start(Context context, Map<String, String> extraEnv, String stdin, boolean keepStdinOpen,
                                   Listener listener, String... command) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/bash");
        cmd.addAll(Arrays.asList(command));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true)
            .directory(new File(TermuxConstants.TERMUX_HOME_DIR_PATH));
        Map<String, String> env = pb.environment();
        env.clear();
        env.putAll(new TermuxShellEnvironment().getEnvironment(context, false));
        env.put("PATH", new File(CcmConfig.CCM_DIR, "bin").getAbsolutePath() + ":" + env.get("PATH"));
        env.put("TERM", "dumb");
        env.put("NO_COLOR", "1");
        if (extraEnv != null) env.putAll(extraEnv);

        CcmProcess p = new CcmProcess(pb.start());
        p.mStdin = p.mProcess.getOutputStream();
        if (stdin != null) p.writeLine(stdin);
        if (!keepStdinOpen) p.closeStdin();
        new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.mProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) listener.onLine(stripAnsi(line));
            } catch (Exception e) {
                Logger.logDebug(LOG_TAG, "reader closed: " + e.getMessage());
            }
            int code;
            try { code = p.mProcess.waitFor(); } catch (InterruptedException e) { code = -1; }
            p.mRunning = false;
            listener.onExit(code);
        }).start();
        return p;
    }

    /** 向 stdin 写一行（自动补换行），在后台线程按顺序执行。 */
    public void writeLine(String line) {
        mWriter.execute(() -> {
            try {
                mStdin.write((line.endsWith("\n") ? line : line + "\n").getBytes(StandardCharsets.UTF_8));
                mStdin.flush();
            } catch (Exception e) {
                Logger.logDebug(LOG_TAG, "stdin write failed: " + e.getMessage());
            }
        });
    }

    public void closeStdin() {
        mWriter.execute(() -> {
            try { mStdin.close(); } catch (Exception ignored) {}
        });
        mWriter.shutdown();
    }

    public boolean isRunning() {
        return mRunning;
    }

    public void stop() {
        mProcess.destroy();
    }

    static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "").replace("\r", "");
    }
}
