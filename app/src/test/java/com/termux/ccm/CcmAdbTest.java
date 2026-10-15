package com.termux.ccm;

import android.app.Application;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** 使用空 Application：TermuxApplication 会按系统语言加载译文，测试断言的是中文原文。 */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class CcmAdbTest {

    @Test
    public void testIsConnectedMessage() {
        Assert.assertTrue(CcmAdb.isConnectedMessage("已连接 127.0.0.1:40000"));
        Assert.assertFalse(CcmAdb.isConnectedMessage("配对失败"));
        Assert.assertFalse(CcmAdb.isConnectedMessage(null));
    }

    @Test
    public void testPairRejectsMalformedCodesBeforeRunningAdb() {
        for (String input : new String[]{"12345", "1234567", "abcdef", "40000 12a456", "  "}) {
            String msg = CcmAdb.pairAndConnect(input);
            Assert.assertTrue(input + " -> " + msg, msg.startsWith("配对码应为 6 位数字，收到："));
            Assert.assertTrue(msg.endsWith(input));
        }
    }
}
