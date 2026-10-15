package com.termux.ccm;

import org.junit.Assert;
import org.junit.Test;

public class CcmProcessTest {

    @Test
    public void testStripAnsi() {
        Assert.assertEquals("plain", CcmProcess.stripAnsi("plain"));
        Assert.assertEquals("red text", CcmProcess.stripAnsi("\u001B[31mred\u001B[0m text"));
        Assert.assertEquals("bold", CcmProcess.stripAnsi("\u001B[1;32mbold\u001B[m"));
        Assert.assertEquals("cursor", CcmProcess.stripAnsi("\u001B[?25lcursor\u001B[?25h"));
        Assert.assertEquals("progress", CcmProcess.stripAnsi("prog\rress"));
        Assert.assertEquals("", CcmProcess.stripAnsi("\u001B[2K\r"));
    }
}
