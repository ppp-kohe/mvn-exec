package org.autogui.exec;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
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
    protected String mainClass = null;
    protected List<String> arguments = new ArrayList<>();
    protected List<Map.Entry<String,String>> propertySettings = new ArrayList<>();
    protected LinkedHashSet<ExecMode> modes = new LinkedHashSet<>();
    protected boolean compile;
    protected boolean completeWorkingDirectory = true;
    protected boolean autoCompile = true;

    protected boolean debug;

    public static String MAVEN_OPTS_LOG_LEVEL = "-Dorg.slf4j.simpleLogger.defaultLogLevel=";
    public static String MAVEN_EXEC_DEBUG = "org.autogui.exec.debug";

    public enum ExecMode {
        Execute,
        ExecuteDefault,
        FindMainClass,
        ListMainClass,
        GetCommand,
        Help,
    }

    public MavenExecJava() {
         updateDebug();
    }

    public void log(String fmt, Object... args) {
        if (debug) {
            System.err.printf("[%s]:  %s\n", LocalDateTime.now(), String.format(fmt, args));
        }
    }

    public void run(String... args) {
        parseArgs(args);
        log("start %s", modes);

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
                    executeExecute();
                    break;
                }
                case ExecuteDefault: {
                    executeExecuteDefault();
                    break;
                }
                case GetCommand: {
                    executeGetCommand();
                    break;
                }
                case FindMainClass: {
                    executeFindMainClass();
                    break;
                }
                case ListMainClass: {
                    executeListMainClass();
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

    private void updateDebug() {
        debug = System.getProperty(MAVEN_EXEC_DEBUG, "false").equals("true");
    }

    public void executeExecute() {
        executeExecute(false);
    }

    public void executeExecuteDefault() {
        executeExecute(true);
    }

    public void executeExecute(boolean def) {
        if (mainClass != null) {
            String name = findMainClass(projectPath, mainClass);
            ProcessShell sh = getCommand(name, arguments);
            log("command %s", sh);
            if (def) {
                sh.echo();
            }
            sh.runToReturnCode();
        }
    }

    public void executeGetCommand() {
        if (mainClass != null) {
            String name = findMainClass(projectPath, mainClass);
            ProcessShell sh = getCommand(name, arguments);
            System.out.println(sh.getEchoString());
        }
    }

    public void executeFindMainClass() {
        if (mainClass != null) {
            String name = findMainClass(projectPath, mainClass);
            System.out.println(name);
        }
    }

    public void executeListMainClass() {
        listMainClasses(projectPath);
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
                } else if (arg.equals("-e") || arg.equals("--execute")) {
                    modes.add(ExecMode.Execute);
                } else if (arg.equals("-h") || arg.equals("--help")) {
                    modes.add(ExecMode.Help);
                    break;
                } else if (arg.equals("-c") || arg.equals("--compile")) {
                    compile = true;
                } else if (arg.equals("-sac") || arg.equals("--suppressAutoCompile")) {
                    autoCompile = false;
                } else if (arg.equals("-sc") || arg.equals("--suppressComplete")) {
                    completeWorkingDirectory = false;
                } else if (arg.startsWith("-D") && arg.length() > "-D".length()) {
                    String prop = arg.substring("-D".length());
                    parseArgProp(prop);
                } else if (arg.equals("--debug")) {
                    debug = true;
                    System.setProperty(MAVEN_EXEC_DEBUG, "true");
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
            if (mainClass == null) {
                modes.add(ExecMode.Help);
            } else {
                modes.add(ExecMode.ExecuteDefault);
            }
        }
        updateDebug();
    }

    public void parseArgProp(String prop) { //name=value
        int n = prop.indexOf('=');
        String name;
        String value;
        if (n >= 0) {
            name = prop.substring(0, n);
            value = prop.substring(n + 1);
        } else {
            name = prop;
            value = "";
        }
        System.setProperty(name, value);
        propertySettings.add(new AbstractMap.SimpleEntry<>(name, value));
    }

    public void help() {
        String ps = File.pathSeparator;
        String sep = File.separator;

        String helpMessage = "" +
                getClass().getName() + " [options] <mainClass> <arguments>...\n" +
                "     <mainClass>        :  fully qualified name or sub-sequence of characters.\n" +
                "                           In the latter case, \"Abc\" becomes the pattern \"A.*?[bB].*?[cC]\".\n" +
                "     -p  | --project <path> :  set the maven project directory. repeatable, but only the last one is used.\n" +
                "     -f  | --find       :  show the matched main-class name.\n" +
                "     -l  | --list       :  show list of main-classes.\n" +
                "     -g  | --get        :  show the command line.\n" +
                "     -e  | --execute    :  execute the command line. automatically set (with showing the command line) if no -f,-l or -g.\n" +
                "     -c  | --compile    :  \"mvn compile\" before execution.\n" +
                "     -sac| --suppressAutoCompile :  suppress checking target directory and executing \"mvn compile\".\n" +
                "     -sc | --suppressComplete    :  suppress completion of relative path for arguments.\n" +
                "                 Default completion behavior recognize the following patterns as relative path:\n" +
                "                  1. a relative path: <p1>\n" +
                "                  2. a path list: <p1>" + ps + "<p2>...\n" +
                "                  3. a path list of value of property-like arg: -...=<p1>" + ps + "<p1>...\n" +
                "                 If a path <pN> starts with non-\"/\" (Paths.get(p).isAbsolute()==false) \n" +
                "                   and the first one component of the path exists, then <pN> will be completed. \n" +
                "                   i.e. \"existing" + sep + "nonExisting\" will be completed as \"" + sep + "path" + sep + "to" + sep + "existing" + sep + "nonExisting\".\n" +
                "               Note: \"mvn exec:java\" cannot change the working directory other than the target project dir.\n" +
                "                   Thus, we need to provide the absolute path for an outer path of the project\n" +
                "                    as arguments for the executed program. \n" +
                "     -D<name>[=<value>] :  set a system-property. repeatable.\n" +
                "     --debug            :  show debugging messages." +
                "     --                 :  indicate the start of mainClass and/or arguments.s\n";
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
        if (name != null && name.contains(".")) {
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
        boolean noTarget = !projectMayHaveNoTarget(projectDir);
        File targetDir = new File(projectDir, "target");

        if (autoCompile && !targetDir.isDirectory() && noTarget) {
            compileProject(projectDir);
        }
        if (!targetDir.isDirectory()) {
            log("no %s", targetDir);
        }
        File classesDir = new File(targetDir, "classes");
        File testClassesDir = new File(targetDir, "test-classes");
        return Arrays.asList(classesDir, testClassesDir);
    }

    public boolean projectMayHaveNoTarget(File projectDir) {
        File pomFile = new File(projectDir, "pom.xml");
        if (!pomFile.exists()) {
            return true;
        }
        try {
            for (String line : Files.readAllLines(pomFile.toPath())) {
                //quick checking
                if (line.contains("<packaging>pom</packaging>")) { //aggregation of sub-modules
                    log("project have <packaging>pom</packaging>");
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            log("error %s", ex);
            ex.printStackTrace();
            return true;
        }
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
            return new MainFinder(name).matchClass(classFile);
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
                    String mavenOpts = getMavenOpts();
                    log("MAVEN_OPTS: %s", mavenOpts);
                    p.environment().put("MAVEN_OPTS", mavenOpts);
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
        propertySettings.stream()
                .map(this::getPropertySetting)
                .forEach(command::add);
        return command;
    }

    public String getPropertySetting(Map.Entry<String, String> prop) {
        if (prop.getValue().isEmpty()) {
            return "-D" + prop.getKey();
        } else {
            String value = prop.getValue();
            if (completeWorkingDirectory) {
                value = getCompletedPath(value);
            }
            return "-D" + prop.getKey() + "=" + value;
        }
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
        if (completeWorkingDirectory) {
            arg = getCompletedPath(arg);
        }
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

    public String getCompletedPath(String arg) {
        String[] pathList = arg.split(Pattern.quote(File.pathSeparator));
        List<String> comp = new ArrayList<>(pathList.length);
        boolean first = true;
        for (String path : pathList) {
            try {
                Path p = Paths.get(path);
                if (p.isAbsolute()) {
                    comp.add(path);
                } else if (p.getNameCount() >= 1 &&
                            Files.exists(p.getName(0))) {
                    //actually a relative path
                    String absPath = p.toAbsolutePath().normalize().toString();
                    log("complete: %s -> %s", p, absPath);
                    comp.add(absPath);
                } else {
                    comp.add(first ? getCompletedPathOfPropertySet(path) : path);
                }
            } catch (InvalidPathException ipe) {
                comp.add(first ? getCompletedPathOfPropertySet(path) : path);
            }

            first = false;
        }
        return String.join(File.pathSeparator, comp);
    }

    public String getCompletedPathOfPropertySet(String path) { //path is already separated by File.pathSeparator
        if (path.startsWith("-") && path.contains("=")) { //-...=p1
            int i = path.indexOf('=');
            String rest = path.substring(i + 1); //...=p1
            Path rp = Paths.get(rest);
            if (!rp.isAbsolute() && rp.getNameCount() >= 1 &&
                    Files.exists(rp.getName(0))) {
                String absPath = rp.normalize().toAbsolutePath().toString();
                log("complete: %s -> %s", rp, absPath);
                return path.substring(0, i) + absPath;
            } else {
                return path;
            }
        } else {
            return path;
        }
    }

}