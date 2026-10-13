package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

import androidx.core.app.NotificationCompat;
import androidx.core.app.RemoteInput;

import com.termux.R;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxConstants;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 本机无线调试的配对与连接（思路同 Shizuku）：
 * 用 NsdManager 发现本机广播的 _adb-tls-pairing / _adb-tls-connect 服务拿到端口，
 * 用户只需在通知里输入 6 位配对码，App 调用 Termux 里的 adb 完成 pair + connect。
 */
public final class CcmAdb {

    private static final String LOG_TAG = "CcmAdb";
    private static final String CHANNEL_ID = "ccmobile_adb";
    private static final int NOTIFICATION_ID = 0xCC01;
    private static final String TYPE_PAIRING = "_adb-tls-pairing._tcp";
    private static final String TYPE_CONNECT = "_adb-tls-connect._tcp";
    static final String ACTION_PAIR_REPLY = "com.termux.ccm.PAIR_REPLY";
    static final String KEY_REPLY = "pair_code";

    private static volatile int sPairingPort = -1;
    private static volatile int sConnectPort = -1;
    /** mDNS 发现的所有连接端口（含非本机地址的，作为候选） */
    private static final java.util.Set<Integer> sConnectPorts =
        java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
    private static NsdManager.DiscoveryListener sPairingListener, sConnectListener;
    private static final Handler sHandler = new Handler(Looper.getMainLooper());
    /** NsdManager 同一时间只允许一个 resolve，排队处理。 */
    private static final List<NsdServiceInfo> sResolveQueue = new ArrayList<>();
    private static boolean sResolving = false;

    /** 配对页显示的操作日志 */
    private static final StringBuilder sLog = new StringBuilder();

    /** 连接成功消息的固定前缀（翻译后仍用同一函数判断） */
    private static final String CONNECTED = t("已连接");

    private CcmAdb() {}

    public static boolean isConnectedMessage(String msg) {
        return msg != null && msg.startsWith(CONNECTED);
    }

    public interface Callback { void onResult(boolean ok, String message); }

    public static int getPairingPort() { return sPairingPort; }

    public static int getConnectPort() { return sConnectPort; }

    public static String getLog() {
        synchronized (sLog) { return sLog.toString(); }
    }

    static void log(String line) {
        synchronized (sLog) {
            sLog.append(new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(new java.util.Date()))
                .append("  ").append(line.trim()).append('\n');
            if (sLog.length() > 20000) sLog.delete(0, 10000);
        }
        Logger.logInfo(LOG_TAG, line);
    }

    /** 已配对且 adb 有在线设备。阻塞调用，勿在主线程。 */
    public static boolean isConnected() {
        if (!adbInstalled()) return false;
        return Pattern.compile("(?m)^\\S+\\s+device$").matcher(runAdb(8000, "devices")).find();
    }

    /** 发出可直接回复配对码的通知（需要通知权限）。 */
    public static void sendPairNotification(Context context) {
        showNotification(context.getApplicationContext(), t("无线调试配对"),
            t("在「无线调试 → 使用配对码配对设备」看到 6 位配对码后，在这里回复"), true);
    }

    // ---------------- 对外入口 ----------------

    /** 开始配对：开启 mDNS 发现、发出可直接回复配对码的通知，并打开开发者选项。 */
    public static void startPairing(Context context) {
        Context app = context.getApplicationContext();
        if (!adbInstalled()) {
            showNotification(app, t("环境还没安装完成"), t("请等终端里的首次安装结束后再配对"), false);
            return;
        }
        sPairingPort = -1;
        startDiscovery(app);
        showNotification(app, t("无线调试配对"),
            t("在「无线调试」里点「使用配对码配对设备」，然后在这条通知里回复 6 位配对码"), true);
        openDeveloperSettings(context);
        // 10 分钟后停止发现，省电
        sHandler.postDelayed(() -> stopDiscovery(app), 10 * 60 * 1000);
    }

    /** 已配对过时使用：发现连接端口并 adb connect（重启手机后端口会变）。 */
    public static void reconnect(Context context, Callback callback) {
        Context app = context.getApplicationContext();
        if (!adbInstalled()) {
            callback.onResult(false, t("环境还没安装完成（缺少 adb）"));
            return;
        }
        startDiscovery(app);
        new Thread(() -> {
            String result = connectWithWait(8000);
            boolean ok = isConnectedMessage(result);
            // 不在这里关闭 mDNS 发现：配对页可能正依赖它
            sHandler.post(() -> callback.onResult(ok, result));
        }).start();
    }

    public static void openDeveloperSettings(Context context) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(i);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "open developer settings failed", e);
        }
    }

    // ---------------- 通知回复 ----------------

    public static class PairReplyReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            Bundle results = RemoteInput.getResultsFromIntent(intent);
            CharSequence reply = results == null ? null : results.getCharSequence(KEY_REPLY);
            if (reply == null) return;
            Context app = context.getApplicationContext();
            PendingResult pending = goAsync();
            showNotification(app, t("正在配对…"), reply.toString(), false);
            new Thread(() -> {
                try {
                    String msg = pairAndConnect(reply.toString());
                    boolean ok = isConnectedMessage(msg);
                    showNotification(app, ok ? t("无线调试已连接") : t("配对未完成"), msg, !ok);
                    if (ok) stopDiscovery(app);
                } finally {
                    pending.finish();
                }
            }).start();
        }
    }

    /** 配对页直接调用：port <= 0 时使用 mDNS 发现的端口。阻塞调用。 */
    public static String pairAndConnect(String code, int port) {
        return pairAndConnect((port > 0 ? port + " " : "") + code);
    }

    /** 输入可以是「配对码」或「端口 配对码」（mDNS 不可用时手动填端口）。 */
    static String pairAndConnect(String input) {
        String[] parts = input.trim().split("[\\s:：,，]+");
        String code;
        int port = sPairingPort;
        if (parts.length >= 2) {
            try { port = Integer.parseInt(parts[0]); } catch (NumberFormatException ignored) {}
            code = parts[parts.length - 1];
        } else {
            code = parts[0];
        }
        if (!code.matches("\\d{6}")) return t("配对码应为 6 位数字，收到：") + input;
        if (port <= 0) {
            // 等 mDNS 最多 5 秒
            for (int i = 0; i < 25 && sPairingPort <= 0; i++) sleep(200);
            port = sPairingPort;
        }
        List<Integer> pairPorts = new ArrayList<>();
        if (port > 0) {
            pairPorts.add(port);
        } else {
            // mDNS 没发现配对端口：扫描本机开放端口逐个尝试（配对弹窗打开期间端口才开放）
            log(t("mDNS 未发现配对端口，正在扫描本机端口…"));
            pairPorts = scanLocalPorts(30000, 65535);
            log(t("开放端口：") + pairPorts);
            if (pairPorts.size() > 10) pairPorts = pairPorts.subList(0, 10);
        }
        if (pairPorts.isEmpty()) return t("没找到配对端口：请保持「使用配对码配对设备」弹窗处于打开状态再试");

        String out = "";
        for (int p : pairPorts) {
            out = runAdb(port > 0 ? 30000 : 8000, "pair", "127.0.0.1:" + p, code);
            if (out.toLowerCase().contains("success")) break;
        }
        if (!out.toLowerCase().contains("success")) return t("配对失败：") + out.trim() + t("\n请确认配对码弹窗仍然打开、配对码正确");
        log(t("配对成功，正在自动连接…"));
        String connected = connectWithWait(6000);
        return isConnectedMessage(connected) ? connected + t("（配对成功）") : t("配对成功，但自动连接失败：") + connected;
    }

    private static String connectWithWait(long timeoutMs) {
        // 已有设备在线就不必再连
        String devices = runAdb(10000, "devices");
        Matcher dm = Pattern.compile("(?m)^(\\S+)\\s+device$").matcher(devices);
        if (dm.find()) return CONNECTED + " " + dm.group(1);

        // 依次尝试：mDNS 发现的端口 → 上次成功的端口 → adb 自带 mDNS → 扫描本机端口
        java.util.Set<Integer> tried = new java.util.HashSet<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && sConnectPort <= 0 && sConnectPorts.isEmpty()) sleep(200);

        List<Integer> candidates = new ArrayList<>();
        if (sConnectPort > 0) candidates.add(sConnectPort);
        synchronized (sConnectPorts) { candidates.addAll(sConnectPorts); }
        candidates.add(readSavedPort());
        String r = tryConnect(candidates, tried, 15000);
        if (r != null) return r;

        log(t("mDNS 未找到可用端口，尝试 adb mdns"));
        Matcher mm = Pattern.compile("_adb-tls-connect\\S*\\s+\\S+:(\\d+)").matcher(runAdb(8000, "mdns", "services"));
        candidates.clear();
        while (mm.find()) candidates.add(Integer.parseInt(mm.group(1)));
        r = tryConnect(candidates, tried, 15000);
        if (r != null) return r;

        log(t("正在扫描本机端口查找无线调试…"));
        List<Integer> open = scanLocalPorts(30000, 65535);
        open.remove(Integer.valueOf(sPairingPort));
        log(t("开放端口：") + open);
        if (open.size() > 12) open = open.subList(0, 12);
        r = tryConnect(open, tried, 6000);
        if (r != null) return r;
        return t("没找到无线调试连接端口：请确认已连接 Wi-Fi、「无线调试」已打开，然后点「重新连接」");
    }

    private static String tryConnect(List<Integer> ports, java.util.Set<Integer> tried, long timeoutMs) {
        for (int port : ports) {
            if (port <= 0 || !tried.add(port)) continue;
            String out = runAdb(timeoutMs, "connect", "127.0.0.1:" + port);
            if (out.contains("connected to")) {
                try {
                    CcmConfig.writeString(new File(CcmConfig.CCM_DIR, "adb_port"), port + "\n");
                } catch (Exception ignored) {}
                return CONNECTED + " 127.0.0.1:" + port;
            }
            // 失败的 connect 会在 adb devices 里留下 offline 条目，清掉
            runAdb(3000, "disconnect", "127.0.0.1:" + port);
        }
        return null;
    }

    /** 并发探测本机 TCP 端口（本机连接被拒绝是瞬时的，整段扫描通常只需几秒）。 */
    private static List<Integer> scanLocalPorts(int from, int to) {
        final List<Integer> open = java.util.Collections.synchronizedList(new ArrayList<>());
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(64);
        for (int p = from; p <= to; p++) {
            final int port = p;
            pool.execute(() -> {
                try (java.net.Socket sock = new java.net.Socket()) {
                    sock.connect(new java.net.InetSocketAddress("127.0.0.1", port), 300);
                    open.add(port);
                } catch (Exception ignored) {}
            });
        }
        pool.shutdown();
        try { pool.awaitTermination(60, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        List<Integer> sorted = new ArrayList<>(open);
        java.util.Collections.sort(sorted);
        return sorted;
    }

    // ---------------- mDNS 发现 ----------------

    public static synchronized void startDiscovery(Context context) {
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        if (sPairingListener == null) {
            sPairingListener = newListener(nsd);
            try { nsd.discoverServices(TYPE_PAIRING, NsdManager.PROTOCOL_DNS_SD, sPairingListener); }
            catch (Exception e) { sPairingListener = null; log(t("mDNS 发现启动失败：") + e.getMessage()); }
        }
        if (sConnectListener == null) {
            sConnectListener = newListener(nsd);
            try { nsd.discoverServices(TYPE_CONNECT, NsdManager.PROTOCOL_DNS_SD, sConnectListener); }
            catch (Exception e) { sConnectListener = null; }
        }
    }

    public static synchronized void stopDiscovery(Context context) {
        NsdManager nsd = (NsdManager) context.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return;
        for (NsdManager.DiscoveryListener l : new NsdManager.DiscoveryListener[]{sPairingListener, sConnectListener}) {
            if (l != null) try { nsd.stopServiceDiscovery(l); } catch (Exception ignored) {}
        }
        sPairingListener = null;
        sConnectListener = null;
    }

    private static NsdManager.DiscoveryListener newListener(NsdManager nsd) {
        return new NsdManager.DiscoveryListener() {
            @Override public void onServiceFound(NsdServiceInfo info) { enqueueResolve(nsd, info); }
            @Override public void onServiceLost(NsdServiceInfo info) {}
            @Override public void onDiscoveryStarted(String type) {}
            @Override public void onDiscoveryStopped(String type) {}
            @Override public void onStartDiscoveryFailed(String type, int code) { Logger.logError(LOG_TAG, "discovery failed " + type + " " + code); }
            @Override public void onStopDiscoveryFailed(String type, int code) {}
        };
    }

    private static void enqueueResolve(NsdManager nsd, NsdServiceInfo info) {
        synchronized (sResolveQueue) {
            sResolveQueue.add(info);
        }
        resolveNext(nsd);
    }

    @SuppressWarnings("deprecation")
    private static void resolveNext(NsdManager nsd) {
        NsdServiceInfo next;
        synchronized (sResolveQueue) {
            if (sResolving || sResolveQueue.isEmpty()) return;
            next = sResolveQueue.remove(0);
            sResolving = true;
        }
        final boolean pairing = next.getServiceType().contains("pairing");
        try {
            nsd.resolveService(next, new NsdManager.ResolveListener() {
                @Override
                public void onResolveFailed(NsdServiceInfo info, int errorCode) {
                    done();
                }

                @Override
                public void onServiceResolved(NsdServiceInfo info) {
                    // 本机地址优先；非本机地址的也记为候选（部分 ROM 解析出的地址与网卡地址对不上）
                    boolean local = isLocalAddress(info.getHost());
                    if (pairing) {
                        if (local || sPairingPort <= 0) sPairingPort = info.getPort();
                    } else {
                        if (local) sConnectPort = info.getPort();
                        sConnectPorts.add(info.getPort());
                    }
                    log((pairing ? t("发现配对端口 ") : t("发现连接端口 ")) + info.getPort() + (local ? "" : t("（") + info.getHost() + t("）")));
                    done();
                }

                private void done() {
                    synchronized (sResolveQueue) { sResolving = false; }
                    sHandler.postDelayed(() -> resolveNext(nsd), 100);
                }
            });
        } catch (Exception e) {
            synchronized (sResolveQueue) { sResolving = false; }
        }
    }

    /** 局域网里其它手机也会广播同类服务，只认本机地址。 */
    private static boolean isLocalAddress(InetAddress host) {
        if (host == null) return false;
        if (host.isLoopbackAddress()) return true;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a.equals(host)) return true;
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ---------------- 执行 adb ----------------

    static boolean adbInstalled() {
        return new File(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH, "adb").canExecute();
    }

    private static int readSavedPort() {
        try {
            File f = new File(CcmConfig.CCM_DIR, "adb_port");
            if (!f.exists()) return -1;
            byte[] b = new byte[16];
            int n;
            try (InputStream in = new java.io.FileInputStream(f)) { n = in.read(b); }
            return Integer.parseInt(new String(b, 0, Math.max(n, 0)).trim());
        } catch (Exception e) {
            return -1;
        }
    }

    /** 以 Termux 环境运行 adb，带超时，返回合并后的输出。 */
    static String runAdb(long timeoutMs, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add(TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + "/adb");
        Collections.addAll(cmd, args);
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            Map<String, String> env = pb.environment();
            env.put("HOME", TermuxConstants.TERMUX_HOME_DIR_PATH);
            env.put("PREFIX", TermuxConstants.TERMUX_PREFIX_DIR_PATH);
            env.put("PATH", TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH + ":/system/bin");
            env.put("TMPDIR", TermuxConstants.TERMUX_PREFIX_DIR_PATH + "/tmp");
            env.put("LANG", "en_US.UTF-8");
            log("$ adb " + android.text.TextUtils.join(" ", args));
            final Process p = pb.start();
            p.getOutputStream().close();
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            Thread reader = new Thread(() -> {
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) synchronized (out) { out.write(buf, 0, n); }
                } catch (Exception ignored) {}
            });
            reader.start();
            // adb 服务器以守护进程方式常驻，可能一直持有输出管道，所以按超时读取而不是等 EOF
            long deadline = System.currentTimeMillis() + timeoutMs;
            while (System.currentTimeMillis() < deadline) {
                try { p.exitValue(); break; } catch (IllegalThreadStateException e) { sleep(100); }
            }
            reader.join(500);
            try { p.exitValue(); } catch (IllegalThreadStateException e) { p.destroy(); }
            String result;
            synchronized (out) { result = out.toString("UTF-8"); }
            if (!result.trim().isEmpty() && !"devices".equals(args[0])) log(result);
            return result;
        } catch (Exception e) {
            return t("执行 adb 失败：") + e.getMessage();
        }
    }

    // ---------------- 通知 ----------------

    private static void showNotification(Context context, String title, String text, boolean withReply) {
        NotificationManager nm = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, t("CCMobile 无线调试"), NotificationManager.IMPORTANCE_HIGH));
        }
        NotificationCompat.Builder b = new NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_service_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(false)
            .setAutoCancel(!withReply);
        if (withReply) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags |= PendingIntent.FLAG_MUTABLE;
            Intent intent = new Intent(context, PairReplyReceiver.class).setAction(ACTION_PAIR_REPLY);
            PendingIntent pi = PendingIntent.getBroadcast(context, 1, intent, flags);
            RemoteInput ri = new RemoteInput.Builder(KEY_REPLY).setLabel(t("6 位配对码")).build();
            b.addAction(new NotificationCompat.Action.Builder(R.drawable.ic_service_notification, t("输入配对码"), pi)
                .addRemoteInput(ri).build());
        }
        try {
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "notify failed", e);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}
