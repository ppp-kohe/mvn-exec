package org.autogui;

import org.autogui.exec.ProcessShell;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;


public class ProcessShellTest {
    String javaCommand = "java";
    String testClassPath = "target" + File.separator + "test-classes";
    @Test
    public void test() {
        Assert.assertEquals("runToString", "finish:hello",
                ProcessShell.get(javaCommand, "-cp", testClassPath, TestMain.class.getName(), "hello")
                .echo()
                .runToString());
    }

    public static class TestMain {
        public static void main(String[] args) throws Exception {
            Thread.sleep(1000);
            System.out.println("finish:" + args[0]);
        }
    }
}
