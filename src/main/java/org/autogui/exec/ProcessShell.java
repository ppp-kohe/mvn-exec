package org.autogui.exec;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    protected Charset encoding = StandardCharsets.UTF_8;

    protected ProcessorForOutput<OutType> outputProcess;

    public static ProcessShell<?> get(String... command) {
        return new ProcessShell<>()
                .set(b -> b.command(command));
    }

    public static ProcessShell<?> get(Iterable<String> command) {
        List<String> array = new ArrayList<>();
        command.forEach(array::add);
        return get(array.toArray(new String[0]));
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
        return set(p -> {
            p.redirectError(ProcessBuilder.Redirect.INHERIT)
                    .redirectOutput(ProcessBuilder.Redirect.INHERIT);
        });
    };

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

    public ProcessShell<OutType> setInputStream(InputStream input) {
        return setInput(p -> {
            try (OutputStream out = p.getOutputStream()) {
                transfer(input, out);
            } finally {
                input.close();
            }
        });
    }

    private void transfer(InputStream input, OutputStream out) throws IOException {
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

    public ProcessShell<byte[]> setOutputBytes() {
        return setOutput((p, target) -> {
            try (InputStream in = p.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                transfer(in, out);
                target.accept(out.toByteArray());
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

    public Process start() {
        try {
            Process p = builder.start();
            if (inputProcess != null) {
                new Thread(() -> processInput(p)).start();
            }
            return p;
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }


    public OutType startAndGet() {
        Process p = start();
        try {
            ArrayBlockingQueue<OutType> dest = startDestinationThread(p);
            p.waitFor();

            return dest.take();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    protected ArrayBlockingQueue<OutType> startDestinationThread(Process p) {
        ArrayBlockingQueue<OutType> dest = new ArrayBlockingQueue<>(1);
        if (outputProcess != null) {
            new Thread(() -> processOutput(p, dest::add)).start();
        }
        return dest;
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
            ArrayBlockingQueue<OutType> dest = startDestinationThread(p);
            Instant time = Instant.now();
            waitFor(p, timeout, unit);
            Duration elapsed = Duration.between(time, Instant.now());


            Duration remaining = Duration.of(timeout, unit)
                    .minus(elapsed);

            if (remaining.isNegative() || remaining.isZero()) {
                if (dest.isEmpty()) {
                    throw new TimeoutException();
                }
                return dest.peek();
            } else {
                long secs = remaining.getSeconds();
                if (secs == 0) {
                    return dest.poll(remaining.getNano(), TimeUnit.NANOSECONDS);
                } else if (secs >= 1000) {
                    return dest.poll(secs, TimeUnit.SECONDS);
                } else {
                    return dest.poll(secs * 1000L +
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
        return String.join("\n", runToLines());
    }

    public void processInput(Process p) {
        try {
            inputProcess.process(p);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public void processOutput(Process p, Consumer<OutType> o) {
        try {
            outputProcess.process(p, o);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

}
