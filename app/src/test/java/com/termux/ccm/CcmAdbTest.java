package com.termux.ccm;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
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
