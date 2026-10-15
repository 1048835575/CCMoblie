package com.termux.ccm;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RunWith(RobolectricTestRunner.class)
public class CcmTaskStateTest {

    private final AtomicInteger mStops = new AtomicInteger();
    private final AtomicInteger mChanges = new AtomicInteger();
    private final CcmTaskState.Listener mListener = mChanges::incrementAndGet;

    @Before
    public void setUp() {
        CcmTaskState.begin("demo", "/proj", "sess-1", "do it", mStops::incrementAndGet);
        CcmTaskState.addListener(mListener);
    }

    @After
    public void tearDown() {
        CcmTaskState.removeListener(mListener);
        CcmTaskState.end(false, null);
        CcmTaskState.stop();
    }

    private static JSONObject toolUse(String id, String name, JSONObject input) throws Exception {
        JSONObject c = new JSONObject().put("type", "tool_use").put("id", id).put("name", name).put("input", input);
        return new JSONObject().put("type", "assistant")
            .put("message", new JSONObject().put("content", new JSONArray().put(c)));
    }

    private static JSONObject toolResult(String id, boolean error) throws Exception {
        JSONObject c = new JSONObject().put("type", "tool_result").put("tool_use_id", id).put("is_error", error);
        return new JSONObject().put("type", "user")
            .put("message", new JSONObject().put("content", new JSONArray().put(c)));
    }

    private static JSONObject delta(JSONObject d) throws Exception {
        return new JSONObject().put("type", "stream_event")
            .put("event", new JSONObject().put("type", "content_block_delta").put("delta", d));
    }

    @Test
    public void testBeginResetsState() {
        Assert.assertTrue(CcmTaskState.isRunning());
        Assert.assertTrue(CcmTaskState.hasTask());
        Assert.assertFalse(CcmTaskState.isError());
        Assert.assertEquals("demo", CcmTaskState.projectName());
        Assert.assertEquals("/proj", CcmTaskState.projectPath());
        Assert.assertEquals("sess-1", CcmTaskState.sessionId());
        Assert.assertEquals("do it", CcmTaskState.prompt());
        Assert.assertEquals("正在处理…", CcmTaskState.headline());
        Assert.assertTrue(CcmTaskState.steps().isEmpty());

        CcmTaskState.begin("demo", "/proj", null, null, null);
        Assert.assertEquals("", CcmTaskState.prompt());
    }

    @Test
    public void testToolUseAddsStepAndResultMarksIt() throws Exception {
        CcmTaskState.onEvent(toolUse("t1", "mcp__phone__phone_tap", new JSONObject().put("id", 3)));
        List<CcmTaskState.Step> steps = CcmTaskState.steps();
        Assert.assertEquals(1, steps.size());
        Assert.assertEquals("点击", steps.get(0).label);
        Assert.assertEquals("[3]", steps.get(0).detail);
        Assert.assertEquals(CcmTaskState.STEP_RUNNING, steps.get(0).state);
        Assert.assertEquals("点击：[3]", CcmTaskState.headline());

        CcmTaskState.onEvent(toolUse("t2", "mcp__phone__phone_screen", new JSONObject()));
        Assert.assertEquals("读取屏幕", CcmTaskState.headline());

        CcmTaskState.onEvent(toolResult("t1", false));
        CcmTaskState.onEvent(toolResult("t2", true));
        steps = CcmTaskState.steps();
        Assert.assertEquals(CcmTaskState.STEP_OK, steps.get(0).state);
        Assert.assertEquals(CcmTaskState.STEP_ERROR, steps.get(1).state);
    }

    @Test
    public void testToolResultWithStringContentIsIgnored() throws Exception {
        CcmTaskState.onEvent(toolUse("t1", "Bash", new JSONObject().put("command", "ls")));
        CcmTaskState.onEvent(new JSONObject().put("type", "user")
            .put("message", new JSONObject().put("content", "plain text")));
        Assert.assertEquals(CcmTaskState.STEP_RUNNING, CcmTaskState.steps().get(0).state);
    }

    @Test
    public void testStepsAreCappedAt60() throws Exception {
        for (int i = 0; i < 65; i++) CcmTaskState.onEvent(toolUse("t" + i, "Bash", new JSONObject().put("command", "c" + i)));
        List<CcmTaskState.Step> steps = CcmTaskState.steps();
        Assert.assertEquals(60, steps.size());
        Assert.assertEquals("t5", steps.get(0).id);
        Assert.assertEquals("t64", steps.get(59).id);
    }

    @Test
    public void testTextDeltasBuildReplyAndToolUseClearsIt() throws Exception {
        CcmTaskState.onEvent(delta(new JSONObject().put("type", "text_delta").put("text", "Hello ")));
        CcmTaskState.onEvent(delta(new JSONObject().put("type", "text_delta").put("text", "world  ")));
        Assert.assertEquals("Hello world", CcmTaskState.reply());

        CcmTaskState.onEvent(toolUse("t1", "Bash", new JSONObject().put("command", "ls")));
        Assert.assertEquals("", CcmTaskState.reply());
    }

    @Test
    public void testThinkingHeadlineKeepsTail() throws Exception {
        CcmTaskState.onEvent(new JSONObject().put("type", "stream_event").put("event", new JSONObject()
            .put("type", "content_block_start").put("content_block", new JSONObject().put("type", "thinking"))));
        Assert.assertEquals("思考中…", CcmTaskState.headline());

        CcmTaskState.onEvent(delta(new JSONObject().put("type", "thinking_delta").put("thinking", "short\nthought")));
        Assert.assertEquals("思考：short thought", CcmTaskState.headline());

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append((char) ('a' + i % 26));
        CcmTaskState.onEvent(delta(new JSONObject().put("type", "thinking_delta").put("thinking", sb.toString())));
        String all = "short thought" + sb;
        Assert.assertEquals("思考：…" + all.substring(all.length() - 60), CcmTaskState.headline());

        CcmTaskState.onEvent(new JSONObject().put("type", "stream_event").put("event", new JSONObject()
            .put("type", "content_block_start").put("content_block", new JSONObject().put("type", "text"))));
        Assert.assertEquals("正在回复…", CcmTaskState.headline());
    }

    @Test
    public void testSuccessResultEndsTask() throws Exception {
        CcmTaskState.onEvent(toolUse("t1", "Bash", new JSONObject().put("command", "ls")));
        CcmTaskState.onEvent(new JSONObject().put("type", "result").put("is_error", false).put("result", "ok"));
        Assert.assertFalse(CcmTaskState.isRunning());
        Assert.assertFalse(CcmTaskState.isError());
        Assert.assertEquals("已完成", CcmTaskState.headline());
        Assert.assertEquals(CcmTaskState.STEP_OK, CcmTaskState.steps().get(0).state);
    }

    @Test
    public void testErrorResultEndsTaskWithMessage() throws Exception {
        CcmTaskState.onEvent(toolUse("t1", "Bash", new JSONObject().put("command", "ls")));
        CcmTaskState.onEvent(new JSONObject().put("type", "result").put("is_error", true).put("result", "boom"));
        Assert.assertFalse(CcmTaskState.isRunning());
        Assert.assertTrue(CcmTaskState.isError());
        Assert.assertEquals("出错：boom", CcmTaskState.headline());
        Assert.assertEquals(CcmTaskState.STEP_ERROR, CcmTaskState.steps().get(0).state);
    }

    @Test
    public void testWaitingAndResumeHeadlines() {
        CcmTaskState.waitingForUser("需要确认");
        Assert.assertEquals("⚠ 需要确认（点开 App 处理）", CcmTaskState.headline());
        CcmTaskState.resumeWorking();
        Assert.assertEquals("继续执行…", CcmTaskState.headline());
    }

    @Test
    public void testStopRunsStopActionOnlyWhileRunningAndClearsQueue() {
        CcmTaskState.enqueue("next 1");
        CcmTaskState.enqueue("next 2");
        Assert.assertEquals(2, CcmTaskState.queuedCount());

        CcmTaskState.stop();
        Assert.assertEquals(1, mStops.get());
        Assert.assertEquals(0, CcmTaskState.queuedCount());
        Assert.assertNull(CcmTaskState.pollQueued());

        // 任务结束后 stopAction 已被清除
        CcmTaskState.end(false, null);
        CcmTaskState.stop();
        Assert.assertEquals(1, mStops.get());
    }

    @Test
    public void testQueueIsFifo() {
        CcmTaskState.enqueue("a");
        CcmTaskState.enqueue("b");
        Assert.assertEquals("a", CcmTaskState.pollQueued());
        Assert.assertEquals("b", CcmTaskState.pollQueued());
        Assert.assertNull(CcmTaskState.pollQueued());
    }

    @Test
    public void testFollowUp() {
        List<String> sent = new ArrayList<>();
        CcmTaskState.FollowUp receiver = sent::add;
        CcmTaskState.FollowUp other = text -> {};

        Assert.assertFalse(CcmTaskState.canFollowUp());
        Assert.assertFalse(CcmTaskState.followUp("ignored"));

        CcmTaskState.setFollowUp(receiver);
        Assert.assertTrue(CcmTaskState.followUp("hello"));
        Assert.assertEquals(1, sent.size());
        Assert.assertEquals("hello", sent.get(0));

        // 只有当前接收方自己才能注销
        CcmTaskState.clearFollowUp(other);
        Assert.assertTrue(CcmTaskState.canFollowUp());
        CcmTaskState.clearFollowUp(receiver);
        Assert.assertFalse(CcmTaskState.canFollowUp());
    }

    @Test
    public void testListenersAreNotifiedOnceEach() {
        CcmTaskState.addListener(mListener);
        mChanges.set(0);
        CcmTaskState.resumeWorking();
        Assert.assertEquals(1, mChanges.get());

        CcmTaskState.removeListener(mListener);
        CcmTaskState.resumeWorking();
        Assert.assertEquals(1, mChanges.get());
    }
}
