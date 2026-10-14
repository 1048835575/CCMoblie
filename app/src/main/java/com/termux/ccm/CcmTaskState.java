package com.termux.ccm;

import static com.termux.ccm.CcmI18n.t;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 当前对话任务的进度摘要（单例，主线程读写）。
 * 聊天界面把 stream-json 事件喂进来，悬浮窗据此显示「正在做什么、进行到哪一步」。
 */
public final class CcmTaskState {

    public static final int STEP_RUNNING = 0, STEP_OK = 1, STEP_ERROR = 2;

    public static final class Step {
        public final String id, label, detail;
        public int state = STEP_RUNNING;

        Step(String id, String label, String detail) {
            this.id = id;
            this.label = label;
            this.detail = detail;
        }
    }

    public interface Listener { void onTaskChanged(); }

    /** 悬浮窗「继续委派」的接收方（主界面注册），在当前会话里发送下一条消息 */
    public interface FollowUp { void send(String text); }

    private static FollowUp sFollowUp;
    /** 任务进行中追加的消息，当前任务结束后依次发送 */
    private static final List<String> sQueue = new ArrayList<>();

    private static final List<Listener> sListeners = new CopyOnWriteArrayList<>();
    private static final List<Step> sSteps = new ArrayList<>();
    private static final StringBuilder sThinking = new StringBuilder();
    private static final StringBuilder sReply = new StringBuilder();

    private static boolean sRunning;
    private static boolean sError;
    private static String sHeadline = "";
    private static String sPrompt = "";
    private static String sProjectName = "", sProjectPath = "", sSessionId;
    private static long sStartMs, sEndMs;
    private static Runnable sStopAction;

    private CcmTaskState() {}

    // ---------------- 读 ----------------

    public static boolean isRunning() { return sRunning; }
    public static boolean hasTask() { return sStartMs > 0; }
    public static boolean isError() { return sError; }
    public static String headline() { return sHeadline; }
    public static String prompt() { return sPrompt; }
    public static String projectName() { return sProjectName; }
    public static String projectPath() { return sProjectPath; }
    public static String sessionId() { return sSessionId; }
    public static String reply() { return sReply.toString().trim(); }
    public static List<Step> steps() { return new ArrayList<>(sSteps); }

    public static long elapsedSeconds() {
        if (sStartMs == 0) return 0;
        return ((sRunning ? System.currentTimeMillis() : sEndMs) - sStartMs) / 1000;
    }

    public static void addListener(Listener l) { if (!sListeners.contains(l)) sListeners.add(l); }
    public static void removeListener(Listener l) { sListeners.remove(l); }

    public static void stop() {
        // 用户主动停止：排队的后续任务也一并取消
        sQueue.clear();
        if (sRunning && sStopAction != null) sStopAction.run();
        notifyChanged();
    }

    public static void setFollowUp(FollowUp f) { sFollowUp = f; }
    public static void clearFollowUp(FollowUp f) { if (sFollowUp == f) sFollowUp = null; }
    public static boolean canFollowUp() { return sFollowUp != null; }

    /** 悬浮窗提交下一步任务。没有接收方（主界面已被系统回收）时返回 false。 */
    public static boolean followUp(String text) {
        if (sFollowUp == null) return false;
        sFollowUp.send(text);
        return true;
    }

    public static void enqueue(String text) {
        sQueue.add(text);
        notifyChanged();
    }

    public static String pollQueued() {
        return sQueue.isEmpty() ? null : sQueue.remove(0);
    }

    public static int queuedCount() { return sQueue.size(); }


    // ---------------- 写 ----------------

    public static void begin(String projectName, String projectPath, String sessionId, String prompt, Runnable stopAction) {
        sSteps.clear();
        sThinking.setLength(0);
        sReply.setLength(0);
        sRunning = true;
        sError = false;
        sProjectName = projectName;
        sProjectPath = projectPath;
        sSessionId = sessionId;
        sPrompt = prompt == null ? "" : prompt;
        sHeadline = t("正在处理…");
        sStartMs = System.currentTimeMillis();
        sStopAction = stopAction;
        notifyChanged();
    }

    public static void setSessionId(String id) { sSessionId = id; }

    /** Claude 在等用户回答问题 / 确认权限 / 批准计划 */
    public static void waitingForUser(String what) {
        sHeadline = "⚠ " + what + t("（点开 App 处理）");
        notifyChanged();
    }

    public static void resumeWorking() {
        sHeadline = t("继续执行…");
        notifyChanged();
    }

    public static void end(boolean error, String message) {
        sRunning = false;
        sError = error;
        sEndMs = System.currentTimeMillis();
        for (Step s : sSteps) if (s.state == STEP_RUNNING) s.state = error ? STEP_ERROR : STEP_OK;
        if (message != null) sHeadline = message;
        else if (!error) sHeadline = t("已完成");
        sStopAction = null;
        notifyChanged();
    }

    public static void onEvent(JSONObject ev) {
        switch (ev.optString("type")) {
            case "stream_event": {
                JSONObject e = ev.optJSONObject("event");
                if (e == null) return;
                String t = e.optString("type");
                if ("content_block_start".equals(t)) {
                    JSONObject cb = e.optJSONObject("content_block");
                    String bt = cb == null ? "" : cb.optString("type");
                    if ("thinking".equals(bt)) { sThinking.setLength(0); setHeadline(t("思考中…")); }
                    else if ("text".equals(bt)) setHeadline(t("正在回复…"));
                } else if ("content_block_delta".equals(t)) {
                    JSONObject d = e.optJSONObject("delta");
                    if (d == null) return;
                    if ("thinking_delta".equals(d.optString("type"))) {
                        sThinking.append(d.optString("thinking"));
                        String th = sThinking.toString().replace('\n', ' ').trim();
                        setHeadline(t("思考：") + (th.length() > 60 ? "…" + th.substring(th.length() - 60) : th));
                    } else if ("text_delta".equals(d.optString("type"))) {
                        sReply.append(d.optString("text"));
                    }
                }
                break;
            }
            case "assistant": {
                JSONObject msg = ev.optJSONObject("message");
                JSONArray content = msg == null ? null : msg.optJSONArray("content");
                for (int i = 0; content != null && i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c == null || !"tool_use".equals(c.optString("type"))) continue;
                    String name = c.optString("name");
                    String label = CcmChatRenderer.toolLabel(name);
                    String detail = CcmChatRenderer.toolSummary(name, c.optJSONObject("input") == null ? new JSONObject() : c.optJSONObject("input"), sProjectPath);
                    sSteps.add(new Step(c.optString("id"), label, detail));
                    if (sSteps.size() > 60) sSteps.remove(0);
                    // 新一轮工具调用开始，清掉之前的回复片段
                    sReply.setLength(0);
                    setHeadline(label + (detail.isEmpty() ? "" : t("：") + detail));
                }
                break;
            }
            case "user": {
                JSONObject msg = ev.optJSONObject("message");
                Object content = msg == null ? null : msg.opt("content");
                if (!(content instanceof JSONArray)) return;
                JSONArray arr = (JSONArray) content;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject c = arr.optJSONObject(i);
                    if (c == null || !"tool_result".equals(c.optString("type"))) continue;
                    for (Step s : sSteps) {
                        if (s.id.equals(c.optString("tool_use_id"))) s.state = c.optBoolean("is_error") ? STEP_ERROR : STEP_OK;
                    }
                }
                notifyChanged();
                break;
            }
            case "result":
                end(ev.optBoolean("is_error"), ev.optBoolean("is_error") ? t("出错：") + CcmChatRenderer.ellipsize(ev.optString("result"), 60) : t("已完成"));
                break;
        }
    }

    private static void setHeadline(String h) {
        sHeadline = h;
        notifyChanged();
    }

    private static void notifyChanged() {
        for (Listener l : sListeners) l.onTaskChanged();
    }
}
