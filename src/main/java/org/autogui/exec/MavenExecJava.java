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

    protected List<File> projectPaths = new ArrayList<>();
    protected String mainClass = null;
    protected List<String> arguments = new ArrayList<>();
    protected List<Map.Entry<String,String>> propertySettings = new ArrayList<>();
    protected LinkedHashSet<ExecMode> modes = new LinkedHashSet<>();
    protected boolean compile;
    protected boolean completeWorkingDirectory = false;
    protected boolean autoCompile = true;
    protected String logLevel = "error";

    protected boolean debug;

    public static String MAVEN_OPTS_LOG_LEVEL = "-Dorg.slf4j.simpleLogger.defaultLogLevel=";
    public static String MAVEN_EXEC_DEBUG = "org.autogui.exec.debug";

    protected List<String> mvnOptions = new ArrayList<>();
    protected List<String> jvmOptions = new ArrayList<>();
    protected boolean execExec = true;
    protected String mvnCommand;

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
         initOptions();
    }

    protected void initOptions() {
        mvnOptions.addAll(Arrays.asList("--color", "always")); //stop modifying ANSI coloring (by org.fusesource.jansi) for stdout/err by always enabling coloring (?)
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

        checkProjectsPom();

        if (compile) {
            compileProjects();
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

    public void checkProjectsPom() {
        log("check pom.xml in %s", projectPaths);
        projectPaths.removeIf(path ->
                !new File(path, "pom.xml").isFile());
        if (projectPaths.isEmpty()) {
            throw new RuntimeException("no pom.xml in " + projectPaths);
        }
    }

    public void compileProjects() {
        log("compile");
        projectPaths.forEach(this::compileProject);
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
            MainClassInfo main = findMainClassFromProjects(mainClass);
            if (main == null) {
                throw new NoSuchMainClassException(mainClass);
            }
            ProcessShell<?> sh = getCommand(main.getPath(), main.getName(), arguments);
            log("command %s", sh);
            if (def) {
                sh.echo();
            }
            sh.runToReturnCode();
        }
    }

    public void executeGetCommand() {
        if (mainClass != null) {
            MainClassInfo main = findMainClassFromProjects(mainClass);
            if (main == null) {
                throw new NoSuchMainClassException(mainClass);
            }
            ProcessShell<?> sh = getCommand(main.getPath(), main.getName(), arguments);
            System.out.println(sh.getEchoString());
        }
    }

    public void executeFindMainClass() {
        if (mainClass != null) {
            MainClassInfo main = findMainClassFromProjects(mainClass);
            if (main == null) {
                throw new NoSuchMainClassException(mainClass);
            }
            System.out.println(main.getName());
        }
    }

    public static class NoSuchMainClassException extends RuntimeException {
        public NoSuchMainClassException(String mainClass) {
            super("not-found: " + mainClass);
        }
    }

    public void executeListMainClass() {
        listMainClassesFromProjects();
    }


    public void parseArgs(String... args) {
        boolean argsPart = false;
        for (int i = 0, l = args.length; i < l; ++i) {
            String arg = args[i];
            if (!argsPart) {
                if (arg.equals("-p") || arg.equals("--project")) {
                    ++i;
                    projectPaths.add(0, new File(args[i]));
                } else if (arg.equals("-pr") || arg.equals("--projectReset")) {
                    ++i;
                    projectPaths.clear();
                    projectPaths.add(0, new File(args[i]));
                } else if (arg.equals("-f") || arg.equals("--find")) {
                    modes.add(ExecMode.FindMainClass);
                } else if (arg.equals("-l") || arg.equals("--list")) {
                    modes.add(ExecMode.ListMainClass);
                } else if (arg.equals("-g") || arg.equals("--get")) {
                    modes.add(ExecMode.GetCommand);
                } else if (arg.equals("-r") || arg.equals("--run")) {
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
                } else if (arg.equals("--complete")) {
                    completeWorkingDirectory = true;
                } else if (arg.equals("-w") || arg.equals("--logWarn")) {
                    logLevel = "warn";
                } else if (arg.equals("--logOff")) {
                    logLevel = "off";
                } else if (arg.startsWith("-D") && arg.length() > "-D".length()) {
                    String prop = arg.substring("-D".length());
                    parseArgProp(prop);
                } else if (arg.equals("--debug")) {
                    setDebug();
                } else if (arg.equals("-e")) {
                    mvnOptions.add("-e");
                    logLevel = "warn";
                } else if (arg.equals("-X")) {
                    mvnOptions.add("-X");
                    logLevel = "warn";
                    setDebug();
                } else if (arg.equals("--execJava")) {
                    execExec = false;
                    completeWorkingDirectory = true;
                } else if (arg.startsWith("-J")) {
                    jvmOptions.add(arg.substring(2));
                } else if (arg.startsWith("-M")) {
                    mvnOptions.add(arg.substring(2));
                } else if (arg.equals("--mvn")) {
                    ++i;
                    mvnCommand = args[i];
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
        if (projectPaths.isEmpty()) {
            projectPaths.add(new File("."));
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

    protected void setDebug() {
        debug = true;
        System.setProperty(MAVEN_EXEC_DEBUG, "true");
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
                "     -p  | --project <path>      :  add a maven project directory. repeatable. later items have high precedence.\n" +
                "     -pr | --projectReset <path> :  clear existing project directories and add a directory.\n" +
                "     -f  | --find       :  show the matched main-class name.\n" +
                "     -l  | --list       :  show list of main-classes.\n" +
                "     -g  | --get        :  show the command line.\n" +
                "     -r  | --run        :  execute the command line. automatically set (with showing the command line) if no -f,-l or -g.\n" +
                "     -c  | --compile    :  \"mvn compile\" before execution.\n" +
                "     --execJava         :  use \"exec:java\" instead of \"exec:exec\". it enables completion of relative path.\n" +
                "     -sac| --suppressAutoCompile :  suppress checking target directory and executing \"mvn compile\".\n" +
                "     --complete                  :  turn on completion of relative path for arguments.\n" +
                "     -sc | --suppressComplete    :  suppress completion of relative path for arguments.\n" +
                "                 The completion is enabled by --execJava. The subsequent -sc can disables the completion. \n" +
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
                "     -w  | --logWarn    :  set the log-level to \"warn\", meaning \"-Dorg.slf4j.simpleLogger.defaultLogLevel=warn\" instead of \"error\". \n" +
                "     -e                 :  -w and pass -e to maven to show errors.\n" +
                "     -J<opt>            :  pass opt to JVM. e.g. -J-Xmx10g \n" +
                "     -M<opt>            :  pass opt to Maven. " +
                "     --logOff           :  set the log-level to \"off\".\n" +
                "     -D<name>[=<value>] :  set a system-property to exec JVM. repeatable.\n" +
                "     --debug            :  show debugging messages.\n" +
                "     -X                 :  --debug and pass -X to mvn.\n" +
                "     --mvn <mvnCommand> :  set the command name of maven. the default is \"mvn\" or \"mvn.cmd\" for Windows.\n" +
                "     --                 :  indicate the start of mainClass and/or arguments.s\n";
        System.out.println(helpMessage);
    }

    public MainClassInfo findMainClassFromProjects(String name) {
        Pattern namePattern = MainFinder.getPatternFromString(name);

        log("findMainClass pattern: %s", namePattern);
        int projectScale = (projectPaths.isEmpty() ? 0 : (int) Math.log10(projectPaths.size())) + 1;
        int scoreFactor = (int) Math.pow(10, projectScale);
        log("projectScale: %d, scoreFactor: %d", projectScale, scoreFactor);

        List<MainClassInfo> totalMains = new ArrayList<>();
        int i = 0;
        for (File path : projectPaths) {
            List<MainClassInfo> main = findMainClass(path, namePattern);
            //score by project order
            int fi = projectPaths.size() - i;
            main = main.stream()
                    .map(m -> m.withScore(m.getScore() * scoreFactor + fi))
                    .collect(Collectors.toList());
            totalMains.addAll(main);
            ++i;
        }
        totalMains.sort(
                Comparator.comparingInt(MainClassInfo::getScore)
                        .reversed());
        totalMains.forEach(m -> log("sorted %s", m));
        return totalMains.isEmpty() ? null : totalMains.get(0);
    }

    public static class MainClassInfo {
        protected File path;
        protected String name;
        protected int score;

        public MainClassInfo(File path, String name, int score) {
            this.path = path;
            this.name = name;
            this.score = score;
        }

        public File getPath() {
            return path;
        }

        public String getName() {
            return name;
        }

        public int getScore() {
            return score;
        }

        public MainClassInfo withPath(File path) {
            return new MainClassInfo(path, name, score);
        }

        public MainClassInfo withScore(int score) {
            return new MainClassInfo(path, name, score);
        }

        @Override
        public String toString() {
            return "MainClassInfo{" +
                    "path=" + path +
                    ", name='" + name + '\'' +
                    ", score=" + score +
                    '}';
        }
    }

    public void listMainClassesFromProjects() {
        projectPaths.forEach(this::listMainClasses);
    }

    public void listMainClasses(File projectPath) {
        for (File dir : getClassesDirectories(projectPath)) {
            if (dir.isDirectory()) {
                try (Stream<Path> paths = findClassFromClassesDir(dir)) {
                    paths.map(p -> matchClass(p, null))
                            .filter(Objects::nonNull)
                            .map(MainClassInfo::getName)
                            .forEach(System.out::println);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }
        }
    }

    public List<MainClassInfo> findMainClass(File projectDir, Pattern namePattern) {
        return getClassesDirectories(projectDir).stream()
                .filter(File::isDirectory)
                .map(dir -> findMainClassFromClassesDir(dir, namePattern))
                .flatMap(l -> l.stream()
                            .map(main -> main.withPath(projectDir)))
                .collect(Collectors.toList());
    }

    public List<File> getClassesDirectories(File projectDir) {
        boolean canHaveTarget = !projectMayHaveNoTarget(projectDir);
        File targetDir = new File(projectDir, "target");

        if (autoCompile && !targetDir.isDirectory() && canHaveTarget) {
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
        List<String> command = new ArrayList<>();
        command.add(getMavenCommandName());
        command.addAll(mvnOptions);
        command.add("compile");
        ProcessShell.get(command)
                .set(p -> {
                    p.directory(projectDir);
                    p.environment().put("MAVEN_OPTS", "-Dorg.slf4j.simpleLogger.defaultLogLevel=error");
                })
                .setRedirectToInherit()
                .echo().runToReturnCode();
    }

    public List<MainClassInfo> findMainClassFromClassesDir(File classesDir, Pattern namePattern) {
        try (Stream<Path> paths = findClassFromClassesDir(classesDir)) {
            return paths.map(p -> matchClass(p, namePattern))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public Stream<Path> findClassFromClassesDir(File classesDir) throws IOException {
        return Files.walk(classesDir.toPath())
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".class"));
    }

    public MainClassInfo matchClass(Path classFile, Pattern namePattern) {
        try {
            MainFinder finder = new MainFinder(namePattern);
            String name = finder.matchClass(classFile);
            if (name != null) {
                return new MainClassInfo(classFile.toFile(), name, finder.getNameMatchedScore());
            } else {
                return null;
            }
        } catch (Exception ex) {
            System.err.println("matchClass: " + classFile + " : " + ex);
            ex.printStackTrace();
            return null;
        }
    }

    public ProcessShell<?> getCommand(File projectPath, String mainClass, List<String> args) {
        Instant commandCreationTime = Instant.now();
        return ProcessShell.get(execExec ? getMavenCommandExec(mainClass, args) : getMavenCommandExecJava(mainClass, args))
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

    public String getMavenCommandName() {
        if (mvnCommand == null) {
            if (System.getProperty("os.name", "").contains("Windows")) {
                mvnCommand = "mvn.cmd";
            } else {
                mvnCommand = "mvn";
            }
        }
        return mvnCommand;
    }

    public List<String> getMavenCommandExec(String mainClass, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(getMavenCommandName());
        command.addAll(mvnOptions);
        command.add("exec:exec");
        command.add("-Dexec.classpathScope=test");
        command.add("-Dexec.executable=java");
        command.add("-Dexec.workingdir=" + new File(".").getAbsolutePath());
        command.add("-Dexec.args=" + getCommandArgumentsForExec(mainClass, args));
        return command;
    }

    public String getCommandArgumentsForExec(String mainClass, List<String> args) {
        String cp = "-cp %classpath"; //-cp %classpath
        String mp = "-p %modulepath"; //-p %modulepath
        try {
            if (Integer.parseInt(System.getProperty("java.version", "0").split("\\.")[0]) < 9) { //1.8...
                mp = ""; //no module path
            }
        } catch (Exception ex) {
            log("version error: java.version=%s : %s", System.getProperty("java.version"), ex);
        }
        String jvmOpts = jvmOptions.stream()
                .map(this::getCommandArgumentWithoutCompletion)
                .collect(Collectors.joining(" "));

        String propOpts = propertySettings.stream()
                .map(this::getPropertySetting)
                .map(this::getCommandArgumentWithoutCompletion)
                .collect(Collectors.joining(" "));

        String argStr = args.stream()
                .map(this::getCommandArgumentWithoutCompletion)
                .collect(Collectors.joining(" "));
        return String.join(" ", jvmOpts, propOpts, cp, mp, mainClass, argStr);
    }

    static Pattern spaces = Pattern.compile("\\s");

    public String getCommandArgumentWithoutCompletion(String arg) {
        if (spaces.matcher(arg).find()) {
            if (!arg.contains("'")) {
                return "'" + arg + "'";
            } else {
                return "\"" + arg + "\""; //TODO escape
            }
        } else {
            return arg;
        }
    }

    public List<String> getMavenCommandExecJava(String mainClass, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(getMavenCommandName());
        command.addAll(mvnOptions);
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
            opts += " " + MAVEN_OPTS_LOG_LEVEL + logLevel;
        }
        if (!execExec) { //for exec:java
            String jvmOpts = jvmOptions.stream()
                    .map(this::getCommandArgument)
                    .collect(Collectors.joining(" "));
            opts += " " + jvmOpts;
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
            if (!arg.contains("'")) {
                return "'" + arg + "'";
            } else if (!arg.contains("\"")) {
                return "\"" + arg + "\"";
            } else { //both " ' space are included in the arg: <a" 'c> -> <a'"'' '"'">
                StringBuilder buf = new StringBuilder();
                for (char c : arg.toCharArray()) {
                    if (c == '\'') {
                        buf.append("\"").append(c).append("\"");
                    } else if (c == ' ' || c == '\"') {
                        buf.append("'").append(c).append("'");
                    } else {
                        buf.append(c);
                    }
                }
                return buf.toString();
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