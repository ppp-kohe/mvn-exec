package org.autogui.exec;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MavenExecJava {

    public static void main(String[] args) {
        new MavenExecJava().run(args);
    }

    protected File projectPath = new File(".");
    protected String mainClass;
    protected List<String> arguments = new ArrayList<>();
    protected LinkedHashSet<ExecMode> modes = new LinkedHashSet<>();
    protected boolean compile;
    protected boolean completeWorkingDirectory;

    protected boolean debug = System.getProperty("autogui.lib.exec.debug", "false").equals("true");

    public static String MAVEN_OPTS_LOG_LEVEL = "-Dorg.slf4j.simpleLogger.defaultLogLevel=";

    public enum ExecMode {
        Execute,
        FindMainClass,
        ListMainClass,
        GetCommand,
        Help,
    }

    public void log(String fmt, Object... args) {
        if (debug) {
            System.err.printf("[%s]:  %s\n", LocalDateTime.now(), String.format(fmt, args));
        }
    }

    public void run(String... args) {
        log("start");
        parseArgs(args);

        if (modes.contains(ExecMode.Help)) {
            help();
            return;
        }

        log("check %s/pom.xml", projectPath);
        if (!new File(projectPath, "pom.xml").isFile()) {
            throw new RuntimeException("no pom.xml in " + projectPath);
        }

        if (compile) {
            log("compile");
            compileProject(projectPath);
        }

        for (ExecMode mode : modes) {
            log("%s", mode);
            switch (mode) {
                case Execute: {
                    String name = findMainClass(projectPath, mainClass);
                    ProcessShell sh = getCommand(name, arguments);
                    log("command %s", sh);
                    sh.echo().runToReturnCode();
                    break;
                }
                case GetCommand: {
                    String name = findMainClass(projectPath, mainClass);
                    ProcessShell sh = getCommand(name, arguments);
                    System.out.println(sh.getEchoString());
                    break;
                }
                case FindMainClass: {
                    String name = findMainClass(projectPath, mainClass);
                    System.out.println(name);
                    break;
                }
                case ListMainClass: {
                    listMainClasses(projectPath);
                    break;
                }
                case Help: {
                    help();
                    break;
                }
            }
        }
        log("finish");
    }

    public void parseArgs(String... args) {
        boolean argsPart = false;
        for (int i = 0, l = args.length; i < l; ++i) {
            String arg = args[i];
            if (!argsPart) {
                if (arg.equals("-p") || arg.endsWith("--project")) {
                    ++i;
                    projectPath = new File(args[i]);
                } else if (arg.equals("-f") || arg.equals("--find")) {
                    modes.add(ExecMode.FindMainClass);
                } else if (arg.equals("-l") || arg.equals("--list")) {
                    modes.add(ExecMode.ListMainClass);
                } else if (arg.equals("-g") || arg.equals("--get")) {
                    modes.add(ExecMode.GetCommand);
                } else if (arg.equals("-e") || arg.equals("--exec")) {
                    modes.add(ExecMode.Execute);
                } else if (arg.equals("-h") || arg.equals("--help")) {
                    modes.add(ExecMode.Help);
                    break;
                } else if (arg.equals("-c") || arg.equals("--compile")) {
                    compile = true;

                } else if (arg.equals("--")) {
                    argsPart = true;
                } else {
                    if (mainClass == null) {
                        mainClass = arg;
                    } else {
                        arguments.add(arg);
                    }
                }
            } else {
                if (mainClass == null) {
                    mainClass = arg;
                } else {
                    arguments.add(arg);
                }
            }
        }
        if (modes.isEmpty()) {
            modes.add(ExecMode.Execute);
        }
    }

    public void help() {
        String helpMessage = "" +
                getClass().getName() + " [options] <mainClass> <arguments>...\n" +
                "     <mainClass>   :  fully qualified name or sub-sequence of characters.\n" +
                "             In the latter case, \"Abc\" becomes the pattern \"A.*?[bB].*?[cC]\".\n" +
                "     -p | --project <path> : set the maven project directory\n" +
                "     -f | --find   :  show the matched class name\n" +
                "     -l | --list   :  show list of main-classes\n" +
                "     -g | --get    :  show the command line\n" +
                "     -e | --exec   :  execute the command line. automatically set if no -f,-l or -g.\n" +
                "     -c | --compile:  mvn compile before execution.\n" +
                "     --            :  indicate the start of mainClass and/or arguments\n";
        System.out.println(helpMessage);
    }

    public void listMainClasses(File projectPath) {
        for (File dir : getClassesDirectories(projectPath)) {
            if (dir.isDirectory()) {
                try (Stream<Path> paths = findClassFromClassesDir(dir)) {
                    paths.map(p -> matchClass(p, null))
                            .filter(Objects::nonNull)
                            .forEach(System.out::println);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    public String findMainClass(File projectDir, String name) {
        if (name.contains(".")) {
            return name;
        } else {
            for (File dir : getClassesDirectories(projectDir)) {
                if (dir.isDirectory()) {
                    String mainName = findMainClassFromClassesDir(dir, name);
                    if (mainName != null) {
                        log("found %s from %s", mainName, dir);
                        return mainName;
                    }
                }
            }
            return null;
        }
    }

    public List<File> getClassesDirectories(File projectDir) {
        File targetDir = new File(projectDir, "target");
        if (!targetDir.isDirectory()) {
            compileProject(projectDir);
        }
        if (!targetDir.isDirectory()) {
            throw new RuntimeException("fail: no " + targetDir);
        }

        File classesDir = new File(targetDir, "classes");
        File testClassesDir = new File(targetDir, "test-classes");
        return Arrays.asList(classesDir, testClassesDir);
    }

    public void compileProject(File projectDir) {
        ProcessShell.get("mvn", "compile")
                .set(p -> {
                    p.directory(projectDir);
                    p.environment().put("MAVEN_OPTS", "-Dorg.slf4j.simpleLogger.defaultLogLevel=error");
                })
                .setRedirectToInherit()
                .echo().runToReturnCode();
    }

    public String findMainClassFromClassesDir(File classesDir, String name) {
        try (Stream<Path> paths = findClassFromClassesDir(classesDir)) {
            return paths.map(p -> matchClass(p, name))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public Stream<Path> findClassFromClassesDir(File classesDir) throws IOException {
        return Files.walk(classesDir.toPath())
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".class"));
    }

    public String matchClass(Path classFile, String name) {
        try {
            ClassReader r = new ClassReader(Files.newInputStream(classFile));

            MainFinder finder = new MainFinder(name);
            r.accept(finder, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
            if (finder.hasMain() && finder.isNameMatched()) {
                return finder.getClassName();
            }
            return null;
        } catch (Exception ex) {
            System.err.println("matchClass: " + classFile + " : " + ex);
            ex.printStackTrace();
            return null;
        }
    }

    public ProcessShell<?> getCommand(String mainClass, List<String> args) {
        Instant commandCreationTime = Instant.now();
        return ProcessShell.get(getMavenCommand(mainClass, args))
                .set(p -> {
                    p.directory(projectPath);
                    p.environment().put("MAVEN_OPTS", getMavenOpts());
                    if (debug) {
                        p.environment().put("MAVEN_EXEC_DEBUG_INIT_TIME", commandCreationTime.toString());
                    }
                })
                .setRedirectToInherit();
    }

    public List<String> getMavenCommand(String mainClass, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add("mvn");
        command.add("exec:java");
        command.add("-Dexec.classpathScope=test");
        command.add("-Dexec.mainClass=" + mainClass);
        command.add("-Dexec.args=" + getCommandArgumentList(args));
        return command;
    }

    public String getMavenOpts() {
        String opts = System.getenv("MAVEN_OPTS");
        if (opts == null) {
            opts = "";
        }
        if (!opts.contains(MAVEN_OPTS_LOG_LEVEL)) {
            opts += " " + MAVEN_OPTS_LOG_LEVEL + "off";
        }
        return opts;
    }

    public String getCommandArgumentList(List<String> args) {
        return args.stream()
                .map(this::getCommandArgument)
                .collect(Collectors.joining(" "));
    }


    public String getCommandArgument(String arg) {
        if (arg.contains(" ")) {
            if (!arg.contains("\'")) {
                return "'" + arg + "'";
            } else {
                return "\"" + arg + "\"";
            }
        } else {
            return arg;
        }
    }

    public static class MainFinder extends ClassVisitor {
        protected String name;
        protected String className;
        protected boolean hasMain;
        protected boolean nameMatched;

        public MainFinder(String name) {
            super(Opcodes.ASM6);
            this.name = name;
        }

        public boolean hasMain() {
            return hasMain;
        }

        public String getClassName() {
            return className;
        }

        public boolean isNameMatched() {
            return nameMatched;
        }

        @Override
        public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
            className = name.replace('/', '.');
            if (this.name != null) {
                name = name.replace('$', '/');

                int dot = name.lastIndexOf('/');
                String lastName = name;
                if (dot != -1) {
                    lastName = name.substring(dot + 1);
                }
                nameMatched = getNamePattern().matcher(lastName).matches();
            } else {
                nameMatched = true;
            }
        }

        public Pattern getNamePattern() {
            StringBuilder buf = new StringBuilder();
            for (char c : this.name.toCharArray()) {
                char upper = Character.toUpperCase(c);
                if (c == '*') {
                    buf.append(".*?");
                } else if (Character.isUpperCase(c) || upper == c) {
                    buf.append(c);
                } else {
                    buf.append("[").append(c).append(upper).append("]");
                }
                buf.append(".*?");
            }
            return Pattern.compile(buf.toString());
        }

        @Override
        public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
            if (name.equals("main") &&
                    ((access & Opcodes.ACC_STATIC) != 0) && ((access & Opcodes.ACC_PUBLIC) != 0) &&
                    descriptor.equals("([Ljava/lang/String;)V")) {
                hasMain = true;
            }
            return super.visitMethod(access, name, descriptor, signature, exceptions);
        }
    }
}