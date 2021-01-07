package org.autogui.exec;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * A wrapper for creating and executing a {@link Process}.
 * <ul>
 *  <li>it's object can be obtained by {@link #get(String...)} or {@link #get(Iterable)} </li>
 *  <li>the argument type &lt;OutType&gt; is the output type of the process which can be determined later by
 *       setting output methods like {@link #setOutputLines()} </li>
 *  <li>there are convenient methods for directly obtaining outputs with starting a process, like {@link #runToLines()} </li>
 *  <li>construction of process can be configured by {@link #set(Processor)}</li>
 *  <li>inputs can be set by setting input methods like {@link #setInputLines(Iterable)}</li>
 *  </ul>
 * <pre>
 *     ProcessShell.get("command", "arg1", "arg2", ...)
 *         .setRedirectToInherit()
 *         .echo()
 *         .runToReturnCode();
 * </pre>
 *
 * <pre>
 *     String output = ProcessShell.get("command", "arg1", "arg2", ...)
 *        .set(builder -> builder.directory(workDir))
 *        .setInputString(inputStr)
 *        .echo()
 *        .runToString();
 * </pre>
 *
 * <pre>
 *     byte[] output = ProcessShell.get("command", ...)
 *         .setOutputBytes()
 *         .startAndGet()
 * </pre>
 *
 * <pre>
 *     ProcessShell.get("command", ...)
 *        .setInput(process -> ...)
 *        .setOutput(process -> ...)
 *        .startAndGet();
 * </pre>
 * @param <OutType> the result type
 */
public class ProcessShell<OutType> {
    public interface Processor<InType> {
        void process(InType in) throws Exception;
    }

    public interface ProcessorForOutput<NewOutType> {
        void process(Process process, Consumer<NewOutType> outputTarget) throws Exception;
    }

    protected ProcessBuilder builder = new ProcessBuilder();
    protected Processor<Process> inputProcess;
    protected Processor<Process> errorProcess;
    protected Charset encoding = StandardCharsets.UTF_8;
    protected List<Processor<Process>> processors = new ArrayList<>();

    protected ProcessorForOutput<OutType> outputProcess;
    protected List<Thread> executedThreads = Collections.synchronizedList(new ArrayList<>());
    protected List<CompletableFuture<?>> executedThreadsWaits = Collections.synchronizedList(new ArrayList<>());

    protected boolean waitThreads = true;

    public static ProcessShell<?> get(String... command) {
        return new ProcessShell<>()
                .set(b -> b.command(command));
    }

    public static ProcessShell<?> get(Iterable<String> command) {
        List<String> array = new ArrayList<>();
        command.forEach(array::add);
        return get(array.toArray(new String[0]));
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + String.join(" ", builder.command()) + ")";
    }

    public ProcessBuilder getBuilder() {
        return builder;
    }

    public ProcessShell<OutType> set(Processor<ProcessBuilder> setter) {
        try {
            setter.process(builder);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return this;
    }

    public ProcessShell<OutType> setRedirectToInherit() {
        return set(p ->
            p.redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectInput(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT));
    }

    public ProcessShell<OutType> echo() {
        return echo("> ");
    }

    public ProcessShell<OutType> echo(String before) {
        return echo(before, "");
    }

    public ProcessShell<OutType> echo(String before, String after) {
        System.err.println(getEchoString(before, after));
        return this;
    }

    public String getEchoString() {
        return String.join(" ", builder.command());
    }

    public String getEchoString(String before, String after) {
        return before + getEchoString() + after;
    }


    ///////////////////

    public ProcessShell<OutType> setInputLines(Iterable<String> lines) {
        return setInput(p -> {
            try (OutputStreamWriter out = new OutputStreamWriter(
                    p.getOutputStream(), encoding)) {
                for (String line : lines) {
                    out.write(line);
                    out.write('\n');
                }
            }
        });
    }

    public ProcessShell<OutType> setInputString(String data) {
        return setInputBytes(data.getBytes(encoding));
    }

    public ProcessShell<OutType> setInputBytes(byte[] data) {
        return setInput(p -> {
            try (OutputStream out = p.getOutputStream()) {
                out.write(data);
            }
        });
    }

    public ProcessShell<OutType> setInputFile(Path inputFile) {
        return setInput(p -> {
            try (InputStream input = new BufferedInputStream(
                    Files.newInputStream(inputFile));
                 OutputStream out = p.getOutputStream()) {
                transfer(input, out);
            }
        });
    }

    public ProcessShell<OutType> setInputStream(InputStream input) {
        return setInput(p -> {
            try (OutputStream out = p.getOutputStream()) {
                transfer(input, out);
            } finally {
                input.close();
            }
        });
    }

    public void transfer(InputStream input, OutputStream out) throws IOException {
        byte[] buffer = new byte[8192];
        while (true) {
            int len = input.read(buffer);
            if (len < 0) {
                break;
            }
            out.write(buffer, 0, len);
        }
    }

    public ProcessShell<OutType> setInput(Processor<Process> inputProcess) {
        this.inputProcess = inputProcess;
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        return this;
    }

    public ProcessShell<OutType> setEncoding(Charset encoding) {
        this.encoding = encoding;
        return this;
    }

    public Charset getEncoding() {
        return encoding;
    }

    ///////////////////

    public ProcessShell<List<String>> setOutputLines() {
        return setOutput((p, target) -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(p.getInputStream(), encoding))) {
                    List<String> list = new ArrayList<>();
                    String line;
                    while ((line = reader.readLine()) != null) {
                        list.add(line);
                    }
                    target.accept(list);
                }
            });
    }

    public ProcessShell<String> setOutputString() {
        return setOutput((p, target) -> {
            try (InputStream in = p.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                transfer(in, out);
                target.accept(StandardCharsets.UTF_8.decode(
                        ByteBuffer.wrap(out.toByteArray()))
                        .toString());
            }
        });
    }

    public ProcessShell<byte[]> setOutputBytes() {
        return setOutput((p, target) -> {
            try (InputStream in = p.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                transfer(in, out);
                target.accept(out.toByteArray());
            }
        });
    }

    public ProcessShell<Path> setOutputFile(Path outFile) {
        return setOutput((p, dest) -> {
            try (InputStream in = p.getInputStream();
                OutputStream out = new BufferedOutputStream(
                        Files.newOutputStream(outFile))) {
                transfer(in, out);
            } finally {
                dest.accept(outFile);
            }
        });
    }

    public <StreamType extends OutputStream> ProcessShell<StreamType> setOutputStream(StreamType out) {
        return setOutput((p, dest) -> {
            try (InputStream in = p.getInputStream()) {
                transfer(in, out);
            } finally {
                out.close();
                dest.accept(out);
            }
        });
    }

    public ProcessShell<BlockingQueue<String>> setOutputLinesQueue() {
        ArrayBlockingQueue<String> q = new ArrayBlockingQueue<>(100);
        return setOutput((p, dest) -> {
            dest.accept(q);
            transferLinesQueue(p.getInputStream(), q);
        });
    }

    public void transferLinesQueue(InputStream src, BlockingQueue<String> dst) throws IOException, InterruptedException {
        try (BufferedReader in = new BufferedReader(
                new InputStreamReader(src, getEncoding()))) {
            String line;
            while ((line = in.readLine()) != null) {
                dst.put(line);
            }
        } finally {
            dst.put("\n");
        }
    }

    public static void forEachLinesQueue(BlockingQueue<String> q, Consumer<String> f) {
        try {
            while (true) {
                String line = q.take();
                if (line.equals("\n")) {
                    break;
                } else {
                    f.accept(line);
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public static boolean forEachLinesQueuePoll(BlockingQueue<String> q, Consumer<String> f, long timeout, TimeUnit timeUnit) {
        try {
            while (true) {
                String line = q.poll(timeout, timeUnit);
                if (line == null) {
                    return false;
                } else if (line.equals("\n")) {
                    break;
                } else {
                    f.accept(line);
                }
            }
            return true;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public ProcessShell<Processor<Process>> setOutput(Processor<Process> outputProcess) {
        return setOutput((process, dest) -> {
            outputProcess.process(process);
            dest.accept(outputProcess);
        });
    }

    @SuppressWarnings("unchecked")
    public <NewOutType> ProcessShell<NewOutType> setOutput(ProcessorForOutput<NewOutType> outputProcess) {
        ProcessShell<NewOutType> newThis = (ProcessShell<NewOutType>) this;
        newThis.outputProcess = outputProcess;
        newThis.builder.redirectOutput(ProcessBuilder.Redirect.PIPE);
        return newThis;
    }

    ///////////////////

    public ProcessShell<OutType> setErrorFile(Path errorFile) {
        return setError((p) -> {
            try (OutputStream out = new BufferedOutputStream(
                    Files.newOutputStream(errorFile));
                 InputStream in = p.getErrorStream()) {
                transfer(in, out);
            }
        });
    }

    public ProcessShell<OutType> setErrorLines(Consumer<Iterable<String>> lines) {
        return setError(p -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), getEncoding()))) {
                List<String> list = new ArrayList<>();
                String line;
                while ((line = reader.readLine()) != null) {
                    list.add(line);
                }
                lines.accept(list);
            }
        });
    }

    public ProcessShell<OutType> setErrorString(Consumer<String> r) {
        return setErrorBytes(out ->
            r.accept(StandardCharsets.UTF_8.decode(
                    ByteBuffer.wrap(out))
                    .toString()));
    }

    public ProcessShell<OutType> setErrorBytes(Consumer<byte[]> r) {
        return setError((p) -> {
            try (ByteArrayOutputStream out = new ByteArrayOutputStream();
                 InputStream in = p.getErrorStream()) {
                transfer(in, out);
                r.accept(out.toByteArray());
            }
        });
    }

    public ProcessShell<OutType> setErrorStream(OutputStream out) {
        return setError((p) -> {
            try (InputStream in = p.getErrorStream()) {
                transfer(in, out);
            } finally {
                out.close();
            }
        });
    }

    public ProcessShell<OutType> setErrorLine(boolean receiveEnd, Consumer<String> queue) {
        return setError((p) -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getErrorStream(), getEncoding()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    queue.accept(line);
                }
            } finally {
                queue.accept("\n");
            }
        });
    }

    public ProcessShell<OutType> setErrorLinesQueue(BlockingQueue<String> queue) {
        return setError((p) ->
                transferLinesQueue(p.getErrorStream(), queue));
    }


    public ProcessShell<OutType> setError(Processor<Process> p) {
        errorProcess = p;
        builder.redirectError(ProcessBuilder.Redirect.PIPE);
        return this;
    }

    ///////////////////

    public ProcessShell<OutType> addProcessor(Processor<Process> p) {
        processors.add(p);
        return this;
    }

    ///////////////////

    public Process start() {
        try {
            Process p = builder.start();
            if (inputProcess != null) {
                executeTask(true, () -> processInput(p));
            }
            if (errorProcess != null) {
                executeTask(true, () -> processError(p));
            }
            processors.forEach(prc ->
                    executeTask(true, () -> processProcessor(p, prc)));
            return p;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public CompletableFuture<OutType> startOutput() {
        return startDestinationThread(start());
    }

    public CompletableFuture<List<String>> startToLines() {
        ProcessShell<List<String>> lines = setOutputLines();
        Process p = lines.start();
        CompletableFuture<List<String>> retLines = lines.startDestinationThread(p);
        try {
            p.waitFor();
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
        return retLines;
    }

    public CompletableFuture<String> startToString() {
        ProcessShell<String> lines = setOutputString();
        Process p = lines.start();
        CompletableFuture<String> retLines = lines.startDestinationThread(p);
        try {
            p.waitFor();
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
        return retLines;
    }

    public CompletableFuture<byte[]> startToBytes() {
        ProcessShell<byte[]> bs = setOutputBytes();
        Process p = bs.start();
        CompletableFuture<byte[]> retBs = bs.startDestinationThread(p);
        try {
            p.waitFor();
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
        return retBs;
    }


    public CompletableFuture<Integer> startToReturnCode() {
        Process p = start();
        try {
            startDestinationThread(p).get();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return startWaitForThread(p);
    }

    public CompletableFuture<BlockingQueue<String>> startToLinesQueue() {
        ProcessShell<BlockingQueue<String>> ls = setOutputLinesQueue();
        Process p = ls.start();
        CompletableFuture<BlockingQueue<String>> retLs = ls.startDestinationThread(p);
        try {
            p.waitFor();
        } catch (Throwable ex) {
            throw new RuntimeException(ex);
        }
        return retLs;
    }

    protected CompletableFuture<Integer> startWaitForThread(Process p) {
        CompletableFuture<Integer> retCode = new CompletableFuture<>();
        executeTask(true, () -> {
            try {
                retCode.complete(p.waitFor());
            } catch (Throwable ex) {
                retCode.completeExceptionally(ex);
            }
        });
        return retCode;
    }


    public OutType startAndGet() {
        Process p = start();
        try {
            CompletableFuture<OutType> dest = startDestinationThread(p);
            p.waitFor();

            return dest.get();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    protected CompletableFuture<OutType> startDestinationThread(Process p) {
        CompletableFuture<OutType> dest = new CompletableFuture<>();
        executeTask(false, () -> processOutput(p, dest));
        return dest;
    }

    protected void executeTask(boolean includeWaits, Runnable r) {
        if (includeWaits) { //it creates an CompletableFuture and add to the list; the future can be used for synchronize processes
            CompletableFuture<?> tf = new CompletableFuture<>();
            Thread t = new Thread(() -> {
                try {
                    r.run();
                    tf.complete(null);
                } catch (Throwable ex) {
                    tf.completeExceptionally(ex);
                }
            });
            executedThreadsWaits.add(tf);
            executedThreads.add(t);
            t.start();
        } else { //the task is used for the last output
            Thread t = new Thread(r);
            executedThreads.add(t);
            t.start();
        }
    }

    public OutType startAndGet(long timeout, TimeUnit unit) {
        return startAndGet(timeout, toChronoUnit(unit));
    }

    public ChronoUnit toChronoUnit(TimeUnit unit) {
        switch (unit) {
            case DAYS:
                return ChronoUnit.DAYS;
            case HOURS:
                return ChronoUnit.HOURS;
            case MINUTES:
                return ChronoUnit.MINUTES;
            case SECONDS:
                return ChronoUnit.SECONDS;
            case MILLISECONDS:
                return ChronoUnit.MILLIS;
            case MICROSECONDS:
                return ChronoUnit.MICROS;
            case NANOSECONDS:
                return ChronoUnit.NANOS;
        }
        return null;
    }

    public TimeUnit toTimeUnit(ChronoUnit unit) {
        switch (unit) {
            case DAYS:
                return TimeUnit.DAYS;
            case HOURS:
                return TimeUnit.HOURS;
            case MINUTES:
                return TimeUnit.MINUTES;
            case SECONDS:
                return TimeUnit.SECONDS;
            case MILLIS:
                return TimeUnit.MILLISECONDS;
            case MICROS:
                return TimeUnit.MICROSECONDS;
            case NANOS:
                return TimeUnit.NANOSECONDS;
        }
        throw new IllegalArgumentException("unsupported: " + unit);
    }

    public OutType startAndGet(long timeout, ChronoUnit unit) {
        Process p = start();
        try {
            CompletableFuture<OutType> dest = startDestinationThread(p);
            Instant time = Instant.now();
            waitFor(p, timeout, unit);
            Duration elapsed = Duration.between(time, Instant.now());


            Duration remaining = Duration.of(timeout, unit)
                    .minus(elapsed);

            if (remaining.isNegative() || remaining.isZero()) {
                if (dest.isDone()) {
                    throw new TimeoutException();
                }
                return dest.getNow(null);
            } else {
                long secs = remaining.getSeconds();
                if (secs == 0) {
                    return dest.get(remaining.getNano(), TimeUnit.NANOSECONDS);
                } else if (secs >= 1000) {
                    return dest.get(secs, TimeUnit.SECONDS);
                } else {
                    return dest.get(secs * 1000L +
                            remaining.getNano() / 1000_000L, TimeUnit.MILLISECONDS);
                }
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public void waitFor(Process p, long timeout, ChronoUnit unit) throws Exception {
        try {
            p.waitFor(timeout, toTimeUnit(unit));
        } catch (IllegalArgumentException ex) {
            Duration d = unit.getDuration();
            long sec = d.getSeconds();
            long nano = d.getNano();
            if (sec > 0) {
                p.waitFor(sec, TimeUnit.SECONDS);
            }
            if (nano > 0) {
                p.waitFor(d.getNano(), TimeUnit.NANOSECONDS);
            }
        }
    }

    public int runToReturnCode() {
        Process p = start();
        try {
            startDestinationThread(p);
            return p.waitFor();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public List<String> runToLines() {
        return setOutputLines().startAndGet();
    }

    public String runToString() {
        return setOutputString().startAndGet();
    }

    public byte[] runToBytes() {
        return setOutputBytes().startAndGet();
    }

    public BlockingQueue<String> runToLinesQueue() {
        return setOutputLinesQueue().startAndGet();
    }

    public void processInput(Process p) {
        processProcessor(p, inputProcess);
    }

    public void processProcessor(Process p, Processor<Process> prc) {
        try {
            prc.process(p);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public void processOutput(Process p, CompletableFuture<OutType> f) {
        try {
            Consumer<OutType> t = f::complete;
            if (waitThreads) { //before complete the output, waits other processes (inputs and error-outputs)
                t = (c) -> {
                    try {
                        getExecutedThreadsWaitsFuture().get();
                    } catch (Throwable ex) {
                        f.completeExceptionally(ex);
                    }
                    f.complete(c);
                };
            }
            if (outputProcess != null) {
                outputProcess.process(p, t);
            } else {
                t.accept(null);
            }
        } catch (Throwable ex) {
            f.completeExceptionally(ex);
        }
    }

    public void processError(Process p) {
        processProcessor(p, errorProcess);
    }

    public List<Thread> getExecutedThreads() {
        return executedThreads;
    }

    public List<CompletableFuture<?>> getExecutedThreadsWaits() {
        return executedThreadsWaits;
    }

    public CompletableFuture<?> getExecutedThreadsWaitsFuture() {
        return CompletableFuture.allOf(
                executedThreadsWaits.toArray(new CompletableFuture<?>[0]));
    }

    /**
     * @param waitThreads if true (default), it synchronize processes
     *                    including error-outputs and inputs before completion of outputs.
     * @return this
     */
    public ProcessShell<OutType> setWaitThreads(boolean waitThreads) {
        this.waitThreads = waitThreads;
        return this;
    }

    public boolean isWaitThreads() {
        return waitThreads;
    }
}
