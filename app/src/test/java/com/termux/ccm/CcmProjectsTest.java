package com.termux.ccm;

import android.app.Application;

import com.termux.shared.termux.TermuxConstants;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** 使用空 Application：TermuxApplication 会按系统语言加载译文，测试断言的是中文原文。 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class CcmProjectsTest {

    @Rule
    public TemporaryFolder mTmp = new TemporaryFolder();

    private static File write(File f, String content) throws Exception {
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void testUserText() throws Exception {
        Assert.assertNull(CcmProjects.userText(null));
        Assert.assertEquals("hi", CcmProjects.userText(new JSONObject().put("content", "hi")));

        JSONArray blocks = new JSONArray()
            .put(new JSONObject().put("type", "text").put("text", "a"))
            .put(new JSONObject().put("type", "tool_result").put("content", "skipped"))
            .put(new JSONObject().put("type", "text").put("text", "b"));
        Assert.assertEquals("ab", CcmProjects.userText(new JSONObject().put("content", blocks)));

        JSONArray onlyResults = new JSONArray().put(new JSONObject().put("type", "tool_result"));
        Assert.assertNull(CcmProjects.userText(new JSONObject().put("content", onlyResults)));
        Assert.assertNull(CcmProjects.userText(new JSONObject().put("content", 42)));
    }

    @Test
    public void testDisplayPath() {
        String home = TermuxConstants.TERMUX_HOME_DIR_PATH;
        Assert.assertEquals("~/projects/demo", CcmProjects.displayPath(home + "/projects/demo"));
        Assert.assertEquals("内部存储/Download", CcmProjects.displayPath("/storage/emulated/0/Download"));
        Assert.assertEquals("/opt/x", CcmProjects.displayPath("/opt/x"));
    }

    @Test
    public void testLoadSessionKeepsOnlyRealUserAndAssistantRecords() throws Exception {
        File f = write(mTmp.newFile("s.jsonl"), String.join("\n",
            "{\"type\":\"summary\",\"summary\":\"x\"}",
            "{\"type\":\"user\",\"message\":{\"content\":\"one\"}}",
            "{\"type\":\"user\",\"isMeta\":true,\"message\":{\"content\":\"meta\"}}",
            "{\"type\":\"assistant\",\"isSidechain\":true,\"message\":{}}",
            "{\"type\":\"user\" broken json",
            "{\"type\":\"assistant\",\"message\":{\"content\":[]}}",
            ""));
        List<JSONObject> out = CcmProjects.loadSession(f);
        Assert.assertEquals(2, out.size());
        Assert.assertEquals("user", out.get(0).optString("type"));
        Assert.assertEquals("assistant", out.get(1).optString("type"));
    }

    @Test
    public void testLoadSessionMissingFileIsEmpty() {
        Assert.assertTrue(CcmProjects.loadSession(new File(mTmp.getRoot(), "missing.jsonl")).isEmpty());
    }

    @Test
    public void testMeasureCountsFilesAndBytes() throws Exception {
        File root = mTmp.newFolder("m");
        write(new File(root, "a.txt"), "12345");
        File sub = new File(root, "sub");
        Assert.assertTrue(sub.mkdirs());
        write(new File(sub, "b.txt"), "123");

        long[] r = CcmProjects.measure(root);
        Assert.assertEquals(2, r[0]);
        Assert.assertEquals(8, r[1]);
    }

    @Test
    public void testMeasureDoesNotFollowSymlinks() throws Exception {
        File outside = mTmp.newFolder("outside");
        write(new File(outside, "big.bin"), "0123456789");
        File root = mTmp.newFolder("withlink");
        write(new File(root, "a.txt"), "12");
        try {
            Files.createSymbolicLink(new File(root, "link").toPath(), outside.toPath());
        } catch (UnsupportedOperationException | java.io.IOException e) {
            Assume.assumeNoException(e);
        }

        long[] r = CcmProjects.measure(root);
        // 链接本身计为 1 个文件，不统计其指向目录的内容
        Assert.assertEquals(2, r[0]);
        Assert.assertEquals(2, r[1]);
    }

    @Test
    public void testDeleteRefusal() throws Exception {
        Assert.assertNotNull(CcmProjects.deleteRefusal(
            new CcmProjects.Project("phone", CcmProjects.PHONE_WORKSPACE.getAbsolutePath())));
        Assert.assertNotNull(CcmProjects.deleteRefusal(
            new CcmProjects.Project("gone", new File(mTmp.getRoot(), "missing").getAbsolutePath())));
        Assert.assertNotNull(CcmProjects.deleteRefusal(new CcmProjects.Project("root", "/")));

        File deep = new File(mTmp.newFolder("a"), "b/c");
        Assert.assertTrue(deep.mkdirs());
        Assert.assertNull(CcmProjects.deleteRefusal(new CcmProjects.Project("ok", deep.getAbsolutePath())));
    }
}
