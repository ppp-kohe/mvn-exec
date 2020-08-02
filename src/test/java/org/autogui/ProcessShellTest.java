package org.autogui;

import org.autogui.exec.ProcessShell;
import org.junit.Assert;
import org.junit.Test;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;


public class ProcessShellTest {
    static String javaCommand = "java";
    static String testClassPath;

    static {
        testClassPath = System.getProperty("java.class.path");
        System.err.println("class-path: " + testClassPath);
    }

    @Test
    public void testRunToReturnCode() {
        Assert.assertEquals("runToReturnCode",
            123,
            ProcessShell.get(javaCommand, "-cp", testClassPath, TestMainExit.class.getName(), "hello")
                .echo()
                .runToReturnCode());
    }

    @Test
    public void testRunToString() {
        Assert.assertEquals("runToString", "finish:hello",
                ProcessShell.get(javaCommand, "-cp", testClassPath, TestMain.class.getName(), "hello")
                .echo()
                .runToString());
    }

    @Test
    public void testRunToLines() {
        Assert.assertEquals("runToLines", Arrays.asList("finish:hello", "world", "", ""),
                ProcessShell.get(javaCommand, "-cp", testClassPath, TestMain.class.getName(), "hello\nworld\n\n")
                        .echo()
                        .runToLines());
    }

    @Test
    public void testSet() {
        Assert.assertEquals("set",
            "HELLO=hello-world",
            ProcessShell.get(javaCommand, "-cp", testClassPath, TestMainEnv.class.getName())
                .echo()
                .set(b -> b.environment().put("HELLO", "hello-world"))
                .runToString());
    }

    @Test
    public void testSetInputLines() {
        Assert.assertEquals("setInputLines",
            "<hello>\n<world>\n<>\n<>",
            ProcessShell.get(javaCommand, "-cp", testClassPath, TestMainRead.class.getName())
                .setInputLines(Arrays.asList("hello", "world", "", ""))
                .runToString());
    }

    @Test
    public void testSetInputString() {
        Assert.assertEquals("setInputLines",
                "<hello>\n<world>\n<>\n<>",
                ProcessShell.get(javaCommand, "-cp", testClassPath, TestMainRead.class.getName())
                        .setInputString("hello\nworld\n\n\n")
                        .runToString());
    }

    @Test
    public void testSetInputStream() {
        ByteBuffer buf = ByteBuffer.allocate(8200);
        buf.order(ByteOrder.BIG_ENDIAN);
        int num = 0;
        while (buf.position() + 4 < buf.limit()) {
            buf.putInt(num);
            ++num;
        }
        buf.position(0);
        StringBuilder expect = new StringBuilder();
        while (buf.position() < buf.limit()) {
            expect.append("<").append(buf.get()).append(">");
        }
        Assert.assertEquals("setInputStream",
                expect.toString(),
                ProcessShell.get(javaCommand, "-cp", testClassPath, TestMainBytes.class.getName())
                        .setInputBytes(buf.array())
                        .runToString());
    }

    @Test
    public void testSetOutput() throws Exception {
        Assert.assertEquals("setOutput", 123L,
                ProcessShell.get(javaCommand, "-cp", testClassPath, TestMain.class.getName(), "hello")
                .<Long>setOutput((proc, tgt) -> {
                    tgt.accept(123L);
                })
                .startOutput().get().longValue());
    }

    @Test
    public void testSetOutputException() throws Exception {
        Assert.assertEquals("setOutput and get when exception raised", "error",
                ProcessShell.get(javaCommand, "-cp", testClassPath, TestMain.class.getName(), "hello")
                        .setOutput((proc, tgt) -> {
                            throw new RuntimeException("error");
                        })
                        .startOutput().handle((v,e) -> e)
                        .get().getMessage());
    }

    @Test
    public void testSetOutputStream() {
        ByteArrayOutputStream bout = ProcessShell.get(javaCommand, "-cp", testClassPath, TestMain.class.getName(), "hello")
                .setOutputStream(new ByteArrayOutputStream())
                .startAndGet();

        String s = StandardCharsets.UTF_8.decode(
                ByteBuffer.wrap(bout.toByteArray()))
            .toString();
        Assert.assertEquals("setOutputStream",
                "finish:hello\n",
                s);
    }

    @Test
    public void testStart() throws Exception {
        Process proc = ProcessShell.get(javaCommand, "-cp", testClassPath, TestMainExit.class.getName(), "hello")
                .start();
        Assert.assertEquals("start and waitFor", 123,
                proc.waitFor());
    }

    @Test
    public void testStartOutput() throws Exception {
        CompletableFuture<String> res = ProcessShell.get(javaCommand, "-cp", testClassPath, TestMain.class.getName(), "hello")
                .setOutputString()
                .startOutput();
        Assert.assertEquals("startOutput",
                "finish:hello\n",
                res.get());
    }

    @Test
    public void testStartToString() throws Exception {
        Assert.assertEquals("startToString",
                "finish:hello\n",
                ProcessShell.get(javaCommand, "-cp", testClassPath, TestMain.class.getName(), "hello")
                .startToString()
                .get());
    }

    @Test
    public void testStartToLines() throws Exception {
        Assert.assertEquals("startToLines",
                Arrays.asList("finish:hello", "world", ""),
                ProcessShell.get(javaCommand, "-cp", testClassPath, TestMain.class.getName(), "hello\nworld\n")
                        .startToLines()
                        .get());
    }

    @Test
    public void testStartToReturnCode() throws Exception {
        Assert.assertEquals("startToReturnCode",
                123L,
                (long) ProcessShell.get(javaCommand, "-cp", testClassPath, TestMainExit.class.getName(), "hello")
                        .startToReturnCode()
                        .get());
    }

    @Test
    public void testRedirectError() {
        Assert.assertEquals("redirectError",
                "error:hello\nout:world",
                ProcessShell.get(javaCommand, "-cp", testClassPath, TestMainError.class.getName(), "hello", "world")
                    .set(b -> b.redirectErrorStream(true))
                    .runToString());
    }

    @Test
    public void testStartToBytes() throws Exception {
        Assert.assertEquals("startToBytes",
                Arrays.toString("finish:hello\n".getBytes(StandardCharsets.UTF_8)),
                Arrays.toString(ProcessShell.get(javaCommand, "-cp", testClassPath, TestMain.class.getName(), "hello")
                        .startToBytes()
                        .get()));
    }

    @Test
    public void testSetErrorString() throws Exception{
        List<String> buf = new ArrayList<>();
        ProcessShell<?> sh = ProcessShell.get(javaCommand, "-cp", testClassPath, TestMainError.class.getName(), "hello", "world")
                .setErrorString(s -> {
                    System.err.println(s);
                    buf.add(s);
                });
        sh.runToReturnCode();
        Assert.assertEquals("setErrorString",
                "error:hello\n",
                buf.get(0));
    }

    public static class TestMainExit {
        public static void main(String[] args) throws Exception {
            Thread.sleep(1000);
            System.out.println("finish:" + args[0]);
            System.exit(123);
        }
    }

    public static class TestMain {
        public static void main(String[] args) throws Exception {
            Thread.sleep(1000);
            System.out.println("finish:" + args[0]);
        }
    }

    public static class TestMainEnv {
        public static void main(String[] args) {
            System.out.println("HELLO="
                    + System.getenv("HELLO"));
        }
    }

    public static class TestMainRead {
        public static void main(String[] args) throws Exception {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("<" + line + ">");
                }
            }
        }
    }

    public static class TestMainBytes {
        public static void main(String[] args) throws Exception {
            byte[] bs = new byte[10_000];
            int len = System.in.read(bs);
            StringBuilder buf = new StringBuilder();
            for (int i = 0; i < len; ++i) {
                byte b = bs[i];
                buf.append("<").append(b).append(">");
            }
            System.out.println(buf.toString());
        }
    }

    public static class TestMainError {
        public static void main(String[] args) throws Exception {
            System.err.println("error:" + args[0]);
            Thread.sleep(1000);
            System.out.println("out:" + args[1]);
        }
    }
}
