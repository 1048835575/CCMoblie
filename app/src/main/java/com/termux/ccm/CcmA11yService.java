package com.termux.ccm;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.graphics.PixelFormat;
import android.view.Display;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import com.termux.shared.logger.Logger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 手机控制的主通道：无障碍服务。比 adb 方案权限更小、不暴露网络端口、无需配对。
 *
 * 通过抽象命名空间的 Unix socket（{@link #SOCKET_NAME}）对外提供接口，每个连接一行 JSON 请求、一行 JSON 响应。
 * 只接受与本 App 同一 uid 的调用方（即 App 内 Termux 环境里运行的手机控制 MCP），其他应用的连接直接拒绝。
 * 处于敏感应用（银行、支付等，见 {@link CcmConfig#KEY_SENSITIVE_APPS}）时拒绝读取和操作。
 */
public class CcmA11yService extends AccessibilityService {

    private static final String LOG_TAG = "CcmA11yService";
    public static final String SOCKET_NAME = "ccmobile.a11y";
    private static final int MAX_NODES = 3000;
    /** 点击这些按钮前必须由用户在屏幕上确认（服务端强制，AI 无法绕过） */
    private static final Pattern SENSITIVE_LABEL = Pattern.compile(
        "支付|付款|付钱|转账|汇款|提现|收款码|红包|充值|购买|下单|提交订单|结算|删除|清空|注销|卸载|格式化|恢复出厂|解绑|"
            + "\\b(pay|payment|transfer|send money|withdraw|purchase|buy|checkout|place order|delete|erase|uninstall|"
            + "factory reset|remove account|unbind)\\b", Pattern.CASE_INSENSITIVE);
    private static final long CONFIRM_TIMEOUT_MS = 60_000;
    /** 确认框显示期间拒绝一切操作，防止确认框被自动点掉 */
    private static volatile boolean sConfirming;

    private static volatile CcmA11yService sInstance;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile long mLastChange = SystemClock.uptimeMillis();
    /**
     * 本地接口在进程内只建一次、不随服务实例销毁而关闭：系统可能反复重建无障碍服务，
     * 而 LocalServerSocket.close() 在 accept() 阻塞时不会释放抽象地址，重建时会 "Address already in use"。
     * 请求总是转给当前存活的服务实例（{@link #sInstance}）。
     */
    private static Thread sServerThread;

    /** 最近一次读屏的节点（按序号），供按序号点击 / 输入 */
    private final Object mLock = new Object();
    private final List<AccessibilityNodeInfo> mNodes = new ArrayList<>();
    private int mGen;

    // ======================= 生命周期 =======================

    public static boolean isRunning() {
        return sInstance != null;
    }

    /** 系统设置里是否已开启本服务（服务可能尚未连接）。 */
    public static boolean isEnabled(Context context) {
        String list = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (list == null) return false;
        ComponentName me = new ComponentName(context, CcmA11yService.class);
        for (String s : list.split(":")) {
            ComponentName c = ComponentName.unflattenFromString(s);
            if (me.equals(c)) return true;
        }
        return false;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        sInstance = this;
        startServer();
        Logger.logInfo(LOG_TAG, "connected");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        mLastChange = SystemClock.uptimeMillis();
    }

    @Override
    public void onInterrupt() {}

    @Override
    public void onDestroy() {
        if (sInstance == this) sInstance = null;
        super.onDestroy();
    }

    // ======================= 本地接口 =======================

    private static synchronized void startServer() {
        if (sServerThread != null && sServerThread.isAlive()) return;
        LocalServerSocket server;
        try {
            server = new LocalServerSocket(SOCKET_NAME);
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "bind failed", e);
            return;
        }
        sServerThread = new Thread(() -> {
            while (true) {
                try {
                    LocalSocket s = server.accept();
                    new Thread(() -> handle(s), "ccm-a11y-conn").start();
                } catch (Exception e) {
                    Logger.logError(LOG_TAG, "accept: " + e.getMessage());
                    SystemClock.sleep(200);
                }
            }
        }, "ccm-a11y-server");
        sServerThread.setDaemon(true);
        sServerThread.start();
    }

    private static void handle(LocalSocket socket) {
        try (LocalSocket s = socket) {
            // 只允许本 App（同一 uid）的进程调用
            if (s.getPeerCredentials().getUid() != Process.myUid()) {
                Logger.logError(LOG_TAG, "rejected uid " + s.getPeerCredentials().getUid());
                return;
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            String line = r.readLine();
            if (line == null) return;
            JSONObject res;
            CcmA11yService svc = sInstance;
            try {
                res = svc == null ? new JSONObject().put("ok", false).put("error", "accessibility service not running")
                    : svc.dispatch(new JSONObject(line));
            } catch (Exception e) {
                res = new JSONObject().put("ok", false).put("error", String.valueOf(e.getMessage()));
            }
            OutputStream out = s.getOutputStream();
            out.write((res.toString() + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (Exception e) {
            Logger.logError(LOG_TAG, "conn: " + e.getMessage());
        }
    }

    private JSONObject dispatch(JSONObject req) throws Exception {
        String cmd = req.optString("cmd");
        if ("status".equals(cmd)) return status();
        // 高级模式（adb）点击敏感按钮前，也借用这里的确认框
        if ("confirm".equals(cmd)) {
            return new JSONObject().put("ok", true).put("allowed", askUser(req.optString("label"), req.optString("reason")));
        }
        // 读屏和操作前统一检查敏感应用
        String pkg = activePackage();
        if (sConfirming && !"dump".equals(cmd)) {
            return new JSONObject().put("ok", false).put("error", "waiting for the user to answer a confirmation dialog");
        }
        // 敏感应用里只允许返回 / 回桌面等离开操作
        boolean leaving = "global".equals(cmd) && !"enter".equals(req.optString("action"));
        if (isSensitive(pkg) && !leaving) {
            return new JSONObject().put("ok", false).put("blocked", true).put("package", pkg)
                .put("error", "sensitive app: " + pkg);
        }
        switch (cmd) {
            case "dump": {
                // 页面切换中常读到空窗口：等一下再读，最多 3 次
                JSONObject r = null;
                for (int attempt = 0; attempt < 3; attempt++) {
                    r = dump(req.optLong("idleMs", 300), req.optLong("maxWaitMs", 2500));
                    if (r.getJSONArray("nodes").length() > 3) break;
                    SystemClock.sleep(500);
                }
                return r;
            }
            case "click": return click(req);
            case "tap": {
                int x = req.getInt("x"), y = req.getInt("y");
                JSONObject denied = confirmIfSensitive(clickTarget(nodeAtPoint(x, y)), req.optString("reason"));
                if (denied != null) return denied;
                return gestureResult(tapPath(x, y), req.optBoolean("long") ? 800 : 60);
            }
            case "swipe": {
                Path p = new Path();
                p.moveTo(req.getInt("x1"), req.getInt("y1"));
                p.lineTo(req.getInt("x2"), req.getInt("y2"));
                return gestureResult(p, req.optInt("ms", 300));
            }
            case "scroll": return scroll(req);
            case "input": return input(req);
            case "global": return global(req.optString("action"));
            case "launch": return launch(req.optString("package"));
            case "screenshot": return screenshot(req.optInt("maxSide", 1280));
            default: return new JSONObject().put("ok", false).put("error", "unknown cmd " + cmd);
        }
    }

    // ======================= 读屏 =======================

    private JSONObject status() throws Exception {
        DisplayMetrics dm = realMetrics();
        return new JSONObject().put("ok", true).put("sdk", Build.VERSION.SDK_INT)
            .put("screenshot", Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            .put("w", dm.widthPixels).put("h", dm.heightPixels).put("package", activePackage());
    }

    @SuppressWarnings("deprecation")
    private DisplayMetrics realMetrics() {
        DisplayMetrics dm = new DisplayMetrics();
        ((WindowManager) getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(dm);
        return dm;
    }

    private String activePackage() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        return root == null || root.getPackageName() == null ? "" : root.getPackageName().toString();
    }

    private boolean isSensitive(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        for (String s : CcmConfig.get(this, CcmConfig.KEY_SENSITIVE_APPS).split("[,，\\s]+")) {
            if (!s.isEmpty() && pkg.equals(s.trim())) return true;
        }
        return false;
    }

    /** 等界面安静 idleMs（没有内容变化事件）再读，最长等 maxWaitMs。 */
    private void waitIdle(long idleMs, long maxWaitMs) {
        long deadline = SystemClock.uptimeMillis() + maxWaitMs;
        while (SystemClock.uptimeMillis() < deadline && SystemClock.uptimeMillis() - mLastChange < idleMs) {
            SystemClock.sleep(50);
        }
    }

    private JSONObject dump(long idleMs, long maxWaitMs) throws Exception {
        waitIdle(idleMs, maxWaitMs);
        List<AccessibilityNodeInfo> roots = interactiveRoots();
        JSONArray arr = new JSONArray();
        List<AccessibilityNodeInfo> nodes = new ArrayList<>();
        Rect b = new Rect();
        for (AccessibilityNodeInfo root : roots) {
            // 深度优先，保持文档顺序；记录父节点序号
            List<Object[]> stack = new ArrayList<>();
            stack.add(new Object[]{root, -1});
            while (!stack.isEmpty() && nodes.size() < MAX_NODES) {
                Object[] top = stack.remove(stack.size() - 1);
                AccessibilityNodeInfo n = (AccessibilityNodeInfo) top[0];
                if (n == null || !n.isVisibleToUser()) continue;
                int index = nodes.size();
                nodes.add(n);
                n.getBoundsInScreen(b);
                JSONObject o = new JSONObject().put("p", top[1])
                    .put("b", new JSONArray().put(b.left).put(b.top).put(b.right).put(b.bottom));
                if (n.getText() != null && !(Build.VERSION.SDK_INT >= 26 && n.isShowingHintText()) && !n.isPassword()) {
                    o.put("t", n.getText().toString());
                }
                if (n.getContentDescription() != null) o.put("d", n.getContentDescription().toString());
                if (Build.VERSION.SDK_INT >= 26 && n.getHintText() != null) o.put("h", n.getHintText().toString());
                if (n.getViewIdResourceName() != null) o.put("id", n.getViewIdResourceName());
                if (n.getClassName() != null) o.put("c", n.getClassName().toString());
                StringBuilder f = new StringBuilder();
                if (n.isClickable()) f.append('C');
                if (n.isLongClickable()) f.append('L');
                if (n.isScrollable()) f.append('S');
                if (n.isEditable()) f.append('E');
                if (n.isCheckable()) f.append('K');
                if (n.isChecked()) f.append('k');
                if (n.isSelected()) f.append('s');
                if (n.isFocused()) f.append('F');
                if (!n.isEnabled()) f.append('D');
                if (n.isPassword()) f.append('P');
                o.put("f", f.toString());
                arr.put(o);
                for (int i = n.getChildCount() - 1; i >= 0; i--) {
                    AccessibilityNodeInfo c = n.getChild(i);
                    if (c != null) stack.add(new Object[]{c, index});
                }
            }
        }
        synchronized (mLock) {
            mNodes.clear();
            mNodes.addAll(nodes);
            mGen++;
        }
        DisplayMetrics dm = realMetrics();
        return new JSONObject().put("ok", true).put("gen", mGen).put("package", activePackage())
            .put("w", dm.widthPixels).put("h", dm.heightPixels).put("nodes", arr);
    }

    /** 当前可交互的窗口：活动窗口、同应用的弹出窗口和系统对话框；自上而下排列，不含本 App 的悬浮窗。 */
    private List<AccessibilityNodeInfo> interactiveRoots() {
        List<AccessibilityNodeInfo> roots = new ArrayList<>();
        AccessibilityNodeInfo active = getRootInActiveWindow();
        String activePkg = active == null || active.getPackageName() == null ? "" : active.getPackageName().toString();
        List<AccessibilityWindowInfo> windows = new ArrayList<>(getWindows());
        Collections.sort(windows, (a, c) -> Integer.compare(c.getLayer(), a.getLayer()));
        Set<Integer> seen = new HashSet<>();
        for (AccessibilityWindowInfo w : windows) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null || r.getPackageName() == null) continue;
            String pkg = r.getPackageName().toString();
            boolean ours = pkg.equals(getPackageName());
            boolean include = w.isActive() || w.isFocused()
                || (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && pkg.equals(activePkg));
            // 本 App 的悬浮窗不应出现在给 AI 的屏幕里（除非用户正在本 App 中）
            if (ours && !activePkg.equals(getPackageName())) include = false;
            if (include && seen.add(w.getId())) roots.add(r);
        }
        if (roots.isEmpty() && active != null) roots.add(active);
        return roots;
    }

    private AccessibilityNodeInfo nodeAt(JSONObject req) {
        synchronized (mLock) {
            if (req.optInt("gen", -1) != mGen) return null;
            int i = req.optInt("i", -1);
            if (i < 0 || i >= mNodes.size()) return null;
            AccessibilityNodeInfo n = mNodes.get(i);
            n.refresh();
            return n;
        }
    }

    // ======================= 操作 =======================

    private JSONObject click(JSONObject req) throws Exception {
        AccessibilityNodeInfo n = nodeAt(req);
        if (n == null) return new JSONObject().put("ok", false).put("error", "stale node, read the screen again");
        boolean lng = req.optBoolean("long");
        JSONObject denied = confirmIfSensitive(clickTarget(n), req.optString("reason"));
        if (denied != null) return denied;
        int action = lng ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK;
        for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
            if ((lng ? p.isLongClickable() : p.isClickable()) && p.isEnabled()) {
                if (p.performAction(action)) return new JSONObject().put("ok", true).put("method", "action");
                break;
            }
        }
        // 没有可点击的祖先或执行失败：按坐标手势点击
        Rect b = new Rect();
        n.getBoundsInScreen(b);
        JSONObject r = gestureResult(tapPath(b.centerX(), b.centerY()), lng ? 800 : 60);
        return r.put("method", "gesture");
    }

    // ======================= 敏感操作确认 =======================

    /** 实际响应点击的节点：最近的可点击祖先，没有则为自身。 */
    private static AccessibilityNodeInfo clickTarget(AccessibilityNodeInfo n) {
        for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) if (p.isClickable()) return p;
        return n;
    }

    /** 坐标处最深的可见节点 */
    private AccessibilityNodeInfo nodeAtPoint(int x, int y) {
        Rect b = new Rect();
        for (AccessibilityNodeInfo root : interactiveRoots()) {
            AccessibilityNodeInfo hit = null;
            AccessibilityNodeInfo cur = root;
            while (cur != null) {
                cur.getBoundsInScreen(b);
                if (!b.contains(x, y)) break;
                hit = cur;
                AccessibilityNodeInfo next = null;
                for (int i = cur.getChildCount() - 1; i >= 0 && next == null; i--) {
                    AccessibilityNodeInfo c = cur.getChild(i);
                    if (c == null || !c.isVisibleToUser()) continue;
                    c.getBoundsInScreen(b);
                    if (b.contains(x, y)) next = c;
                }
                cur = next;
            }
            if (hit != null) return hit;
        }
        return null;
    }

    /** 节点及其子节点上的文字（按钮文字常在子节点里），最多 80 字 */
    private static String labelOf(AccessibilityNodeInfo n) {
        StringBuilder sb = new StringBuilder();
        collectLabel(n, sb, 0);
        String s = sb.toString().trim();
        return s.length() > 80 ? s.substring(0, 80) : s;
    }

    private static void collectLabel(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || depth > 4 || sb.length() > 80) return;
        if (n.getText() != null) sb.append(n.getText()).append(' ');
        if (n.getContentDescription() != null) sb.append(n.getContentDescription()).append(' ');
        if (n.getViewIdResourceName() != null) {
            String id = n.getViewIdResourceName();
            sb.append(id.substring(id.indexOf('/') + 1).replace('_', ' ')).append(' ');
        }
        for (int i = 0; i < n.getChildCount(); i++) collectLabel(n.getChild(i), sb, depth + 1);
    }

    /** 敏感按钮：弹出确认框等用户决定。允许返回 null，拒绝或超时返回错误结果。 */
    private JSONObject confirmIfSensitive(AccessibilityNodeInfo target, String reason) throws Exception {
        if (target == null) return null;
        String label = labelOf(target);
        if (!SENSITIVE_LABEL.matcher(label).find()) return null;
        if (askUser(label, reason)) return null;
        return new JSONObject().put("ok", false).put("declined", true).put("label", label)
            .put("error", "the user declined tapping a sensitive button (" + label + "); do not retry, ask the user");
    }

    private boolean askUser(String label, String reason) throws InterruptedException {
        synchronized (CcmA11yService.class) {
            if (sConfirming) return false;
            sConfirming = true;
        }
        try {
            return showConfirm(label, reason);
        } finally {
            sConfirming = false;
        }
    }

    private boolean showConfirm(String label, String reason) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean allowed = new AtomicBoolean(false);
        AtomicReference<LinearLayout> shown = new AtomicReference<>();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        mMain.post(() -> {
            float dp = getResources().getDisplayMetrics().density;
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            int pad = (int) (20 * dp);
            box.setPadding(pad, pad, pad, pad);
            android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
            bg.setColor(0xFF1E2622);
            bg.setCornerRadius(16 * dp);
            bg.setStroke((int) (2 * dp), 0xFFE5A23C);
            box.setBackground(bg);

            TextView title = new TextView(this);
            title.setText(CcmI18n.t("AI 请求点击敏感按钮"));
            title.setTextColor(0xFFFFC56B);
            title.setTextSize(17);
            title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            box.addView(title);

            TextView body = new TextView(this);
            String msg = CcmI18n.t("按钮：") + label;
            if (reason != null && !reason.trim().isEmpty()) msg += "\n" + CcmI18n.t("原因：") + reason.trim();
            msg += "\n\n" + CcmI18n.t("涉及付款、转账或删除等操作，请确认是否允许。60 秒内不处理将自动拒绝。");
            body.setText(msg);
            body.setTextColor(0xFFE6ECE9);
            body.setTextSize(15);
            body.setPadding(0, (int) (10 * dp), 0, (int) (14 * dp));
            box.addView(body);

            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.END);
            Button deny = new Button(this);
            deny.setText(CcmI18n.t("拒绝"));
            Button allow = new Button(this);
            allow.setText(CcmI18n.t("允许这一次"));
            deny.setOnClickListener(v -> done.countDown());
            allow.setOnClickListener(v -> { allowed.set(true); done.countDown(); });
            row.addView(deny);
            row.addView(allow);
            box.addView(row);

            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT);
            lp.dimAmount = 0.5f;
            lp.gravity = Gravity.CENTER;
            lp.horizontalMargin = 0.04f;
            try {
                wm.addView(box, lp);
                shown.set(box);
            } catch (Exception e) {
                Logger.logError(LOG_TAG, "confirm overlay: " + e.getMessage());
                done.countDown();
            }
        });
        done.await(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        mMain.post(() -> {
            LinearLayout v = shown.get();
            if (v != null) try { wm.removeView(v); } catch (Exception ignored) {}
        });
        // 等确认框消失，避免手势落在确认框上
        SystemClock.sleep(250);
        return allowed.get();
    }

    private JSONObject scroll(JSONObject req) throws Exception {
        AccessibilityNodeInfo n = nodeAt(req);
        String dir = req.optString("dir");
        for (AccessibilityNodeInfo p = n; p != null; p = p.getParent()) {
            if (!p.isScrollable()) continue;
            // dir 表示手指方向：up / left = 向后翻（看下面 / 右边的内容）
            int action = dir.equals("up") || dir.equals("left") ? AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                : AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD;
            if (p.performAction(action)) return new JSONObject().put("ok", true).put("method", "action");
            break;
        }
        return new JSONObject().put("ok", false).put("error", "not scrollable");
    }

    /** 输入文字：直接设置输入框内容，不切换输入法；不支持时退到剪贴板粘贴。 */
    private JSONObject input(JSONObject req) throws Exception {
        AccessibilityNodeInfo n = req.has("i") ? nodeAt(req) : null;
        AccessibilityNodeInfo target = findEditable(n);
        // 未指定输入框：等焦点落到输入框（刚点击、页面刚打开时常需要片刻），仍没有则用屏幕上唯一的输入框
        for (int i = 0; target == null && i < 10; i++) {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            AccessibilityNodeInfo f = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) target = f;
            else if (i == 9 && root != null) target = onlyEditable(root);
            else SystemClock.sleep(150);
        }
        if (target == null || !target.isEditable()) {
            return new JSONObject().put("ok", false).put("error", "no editable field focused; tap an input box first");
        }
        String text = req.optString("text");
        boolean clear = req.optBoolean("clear");
        CharSequence cur = target.getText();
        boolean hint = Build.VERSION.SDK_INT >= 26 && target.isShowingHintText();
        String value = clear || cur == null || hint ? text : cur + text;
        target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        String method = "set_text";
        // 输入框刚出现（页面动画中）时常会拒绝，稍等重试
        // via=ime：诊断用，跳过 SET_TEXT 直接走输入法通道
        boolean forceIme = "ime".equals(req.optString("via"));
        boolean set = !forceIme && target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        for (int i = 0; !set && !forceIme && i < 4; i++) {
            SystemClock.sleep(300);
            target.refresh();
            set = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        }
        if (!set) {
            // 自绘输入框常拒绝 SET_TEXT：Android 13+ 以无障碍输入法通道直接提交文字（不切换用户键盘），再不行用剪贴板粘贴
            if (Build.VERSION.SDK_INT >= 33 && imeCommit(target, text, clear)) {
                method = "ime";
            } else {
                method = "paste";
                setClipboard(text);
                if (!target.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                    return new JSONObject().put("ok", false).put("error", "this field rejects text input");
                }
            }
        }
        boolean submitted = false;
        if (req.optBoolean("submit") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            target.refresh();
            submitted = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        }
        return new JSONObject().put("ok", true).put("method", method).put("submitted", submitted);
    }

    /** 通过无障碍服务的输入法连接向当前焦点输入框提交文字（需 flagInputMethodEditor，Android 13+）。 */
    @androidx.annotation.RequiresApi(33)
    private boolean imeCommit(AccessibilityNodeInfo target, String text, boolean clear) {
        if (!target.isFocused()) {
            target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            SystemClock.sleep(300);
        }
        android.accessibilityservice.InputMethod im = getInputMethod();
        android.accessibilityservice.InputMethod.AccessibilityInputConnection ic = im == null ? null : im.getCurrentInputConnection();
        if (ic == null) return false;
        if (clear) ic.performContextMenuAction(android.R.id.selectAll);
        ic.commitText(text, 1, null);
        return true;
    }

    /** 屏幕上只有一个可见输入框时返回它 */
    private static AccessibilityNodeInfo onlyEditable(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        List<AccessibilityNodeInfo> stack = new ArrayList<>();
        stack.add(root);
        int seen = 0;
        while (!stack.isEmpty() && seen++ < MAX_NODES && found.size() < 2) {
            AccessibilityNodeInfo n = stack.remove(stack.size() - 1);
            if (n == null || !n.isVisibleToUser()) continue;
            if (n.isEditable()) found.add(n);
            for (int i = 0; i < n.getChildCount(); i++) stack.add(n.getChild(i));
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    private static AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n) {
        if (n == null) return null;
        if (n.isEditable()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo c = findEditable(n.getChild(i));
            if (c != null) return c;
        }
        for (AccessibilityNodeInfo p = n.getParent(); p != null; p = p.getParent()) if (p.isEditable()) return p;
        return null;
    }

    private void setClipboard(String text) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        mMain.post(() -> {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("CCMobile", text));
            done.countDown();
        });
        done.await(2, TimeUnit.SECONDS);
    }

    private JSONObject global(String action) throws Exception {
        int a;
        switch (action) {
            case "back": a = GLOBAL_ACTION_BACK; break;
            case "home": a = GLOBAL_ACTION_HOME; break;
            case "recent": a = GLOBAL_ACTION_RECENTS; break;
            case "notifications": a = GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": a = GLOBAL_ACTION_QUICK_SETTINGS; break;
            case "power": a = GLOBAL_ACTION_POWER_DIALOG; break;
            case "enter": {
                // 对当前焦点输入框执行输入法的「回车 / 搜索 / 发送」
                AccessibilityNodeInfo root = getRootInActiveWindow();
                AccessibilityNodeInfo f = root == null ? null : root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
                if (f == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    return new JSONObject().put("ok", false).put("error", "no focused input (or Android < 11)");
                }
                return new JSONObject().put("ok", f.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId()));
            }
            case "lock":
                if (Build.VERSION.SDK_INT < 28) return new JSONObject().put("ok", false).put("error", "needs Android 9+");
                a = GLOBAL_ACTION_LOCK_SCREEN;
                break;
            default: return new JSONObject().put("ok", false).put("error", "unsupported key " + action);
        }
        return new JSONObject().put("ok", performGlobalAction(a));
    }

    private JSONObject launch(String pkg) throws Exception {
        Intent i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) return new JSONObject().put("ok", false).put("error", "no launchable activity for " + pkg);
        if (isSensitive(pkg)) return new JSONObject().put("ok", false).put("blocked", true).put("package", pkg);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        startActivity(i);
        return new JSONObject().put("ok", true);
    }

    // ======================= 手势 =======================

    private static Path tapPath(int x, int y) {
        Path p = new Path();
        p.moveTo(x, y);
        return p;
    }

    private JSONObject gestureResult(Path path, long durationMs) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            return new JSONObject().put("ok", false).put("error", "gestures need Android 7+");
        }
        GestureDescription g = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, Math.max(1, durationMs))).build();
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean ok = new AtomicBoolean(false);
        mMain.post(() -> {
            boolean sent = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { ok.set(true); done.countDown(); }
                @Override public void onCancelled(GestureDescription d) { done.countDown(); }
            }, null);
            if (!sent) done.countDown();
        });
        done.await(durationMs + 3000, TimeUnit.MILLISECONDS);
        return new JSONObject().put("ok", ok.get());
    }

    // ======================= 截图 =======================

    private JSONObject screenshot(int maxSide) throws Exception {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return new JSONObject().put("ok", false).put("error", "screenshot needs Android 11+");
        }
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bitmap> out = new AtomicReference<>();
        AtomicReference<String> err = new AtomicReference<>();
        takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
            @Override public void onSuccess(ScreenshotResult r) {
                Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                if (hw != null) out.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                r.getHardwareBuffer().close();
                done.countDown();
            }

            @Override public void onFailure(int code) {
                err.set("screenshot failed: " + code);
                done.countDown();
            }
        });
        done.await(5, TimeUnit.SECONDS);
        Bitmap bm = out.get();
        if (bm == null) return new JSONObject().put("ok", false).put("error", err.get() == null ? "screenshot timeout" : err.get());
        int w = bm.getWidth(), h = bm.getHeight();
        int scale = Math.max(1, (int) Math.ceil(Math.max(w, h) / (double) maxSide));
        Bitmap small = scale > 1 ? Bitmap.createScaledBitmap(bm, w / scale, h / scale, true) : bm;
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.PNG, 100, png);
        return new JSONObject().put("ok", true).put("w", w).put("h", h).put("scale", scale)
            .put("png", Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP));
    }
}
