package com.termux.ccm;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class CcmChatRendererToolsTest {

    private static final String HOME = TermuxConstants.TERMUX_HOME_DIR_PATH;

    private static String summary(String name, JSONObject in) {
        return CcmChatRenderer.toolSummary(name, in, "/proj");
    }

    @Test
    public void testToolLabel() {
        Assert.assertEquals("点击", CcmChatRenderer.toolLabel("mcp__phone__phone_tap"));
        Assert.assertEquals("点击", CcmChatRenderer.toolLabel("phone_tap"));
        Assert.assertEquals("adb shell", CcmChatRenderer.toolLabel("mcp__phone__phone_shell"));
        Assert.assertEquals("子任务", CcmChatRenderer.toolLabel("Task"));
        Assert.assertEquals("子任务", CcmChatRenderer.toolLabel("Agent"));
        Assert.assertEquals("Bash", CcmChatRenderer.toolLabel("Bash"));
        Assert.assertEquals("github · get_me", CcmChatRenderer.toolLabel("mcp__github__get_me"));
    }

    @Test
    public void testFileToolsShowPathRelativeToProject() throws Exception {
        Assert.assertEquals("src/Main.java", summary("Read", new JSONObject().put("file_path", "/proj/src/Main.java")));
        Assert.assertEquals("~/notes.md", summary("Write", new JSONObject().put("file_path", HOME + "/notes.md")));
        Assert.assertEquals("/etc/hosts", summary("Edit", new JSONObject().put("file_path", "/etc/hosts")));
        Assert.assertEquals("a.ipynb", summary("NotebookEdit", new JSONObject().put("notebook_path", "/proj/a.ipynb")));
        // 前缀相同但不在项目内的路径不应被截断
        Assert.assertEquals("/project2/x", summary("Read", new JSONObject().put("file_path", "/project2/x")));
    }

    @Test
    public void testBashPrefersDescription() throws Exception {
        Assert.assertEquals("List files", summary("Bash", new JSONObject().put("command", "ls").put("description", "List files")));
        Assert.assertEquals("ls -la", summary("Bash", new JSONObject().put("command", "ls -la")));
    }

    @Test
    public void testGrepAppendsPath() throws Exception {
        Assert.assertEquals("TODO", summary("Grep", new JSONObject().put("pattern", "TODO")));
        Assert.assertEquals("TODO  src", summary("Grep", new JSONObject().put("pattern", "TODO").put("path", "/proj/src")));
    }

    @Test
    public void testTodoWriteCountsCompleted() throws Exception {
        JSONArray todos = new JSONArray()
            .put(new JSONObject().put("status", "completed"))
            .put(new JSONObject().put("status", "in_progress"))
            .put(new JSONObject().put("status", "completed"));
        Assert.assertEquals("2/3 已完成", summary("TodoWrite", new JSONObject().put("todos", todos)));
        Assert.assertEquals("0/0 已完成", summary("TodoWrite", new JSONObject()));
    }

    @Test
    public void testPhoneTools() throws Exception {
        Assert.assertEquals("[7]", summary("mcp__phone__phone_tap", new JSONObject().put("id", 7)));
        Assert.assertEquals("长按 (10, 20)", summary("mcp__phone__phone_tap", new JSONObject().put("x", 10).put("y", 20).put("long", true)));
        Assert.assertEquals("「hi」 并发送", summary("mcp__phone__phone_input", new JSONObject().put("text", "hi").put("submit", true)));
        Assert.assertEquals("2 个动作", summary("mcp__phone__phone_batch", new JSONObject().put("actions", new JSONArray().put(1).put(2))));
        Assert.assertEquals("0 个动作", summary("mcp__phone__phone_batch", new JSONObject()));
        Assert.assertEquals("", summary("mcp__phone__phone_screen", new JSONObject()));
    }

    @Test
    public void testAskUserQuestionShowsFirstQuestion() throws Exception {
        JSONArray qs = new JSONArray().put(new JSONObject().put("question", "Which one?")).put(new JSONObject().put("question", "Second"));
        Assert.assertEquals("Which one?", summary("AskUserQuestion", new JSONObject().put("questions", qs)));
        Assert.assertEquals("", summary("AskUserQuestion", new JSONObject()));
    }

    @Test
    public void testToolSearchListsToolLabels() throws Exception {
        JSONObject in = new JSONObject().put("query", "select:mcp__phone__phone_screen, mcp__phone__phone_tap,,Bash");
        Assert.assertEquals("读取屏幕、点击、Bash", summary("ToolSearch", in));
    }

    @Test
    public void testUnknownToolFallsBackToEllipsizedJson() throws Exception {
        JSONObject small = new JSONObject().put("a", 1);
        Assert.assertEquals(small.toString(), summary("Mystery", small));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) sb.append('x');
        String s = summary("Mystery", new JSONObject().put("a", sb.toString()));
        Assert.assertEquals(81, s.length());
        Assert.assertTrue(s.endsWith("…"));
    }

    @Test
    public void testToolResultText() throws Exception {
        JSONArray arr = new JSONArray()
            .put(new JSONObject().put("type", "text").put("text", "line1 "))
            .put(new JSONObject().put("type", "image"))
            .put("not an object")
            .put(new JSONObject().put("type", "text").put("text", "line2"));
        Assert.assertEquals("line1 [image]line2", CcmChatRenderer.toolResultText(arr));
        Assert.assertEquals("plain", CcmChatRenderer.toolResultText("plain"));
        Assert.assertEquals("", CcmChatRenderer.toolResultText(null));
    }

    @Test
    public void testEllipsize() {
        Assert.assertEquals("", CcmChatRenderer.ellipsize(null, 5));
        Assert.assertEquals("abc", CcmChatRenderer.ellipsize("abc", 3));
        Assert.assertEquals("ab…", CcmChatRenderer.ellipsize("abc", 2));
    }
}
