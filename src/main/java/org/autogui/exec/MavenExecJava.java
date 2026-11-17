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
    protected String javaExecutable = "java";
    protected List<String> arguments = new ArrayList<>();
    protected List<Map.Entry<String,String>> propertySettings = new ArrayList<>();
    protected LinkedHashSet<ExecMode> modes = new LinkedHashSet<>();
    protected boolean compile;
    protected boolean completeWorkingDirectory = false;
    protected boolean autoCompile = true;
    protected String logLevel = "error";
    protected boolean useClasspath;
    protected boolean useModulepath;

    protected boolean debug;

    public static String MAVEN_OPTS_LOG_LEVEL = "-Dorg.slf4j.simpleLogger.defaultLogLevel=";
    public static String MAVEN_EXEC_DEBUG = "org.autogui.exec.debug";

    protected List<String> mvnOptions = new ArrayList<>();
    protected List<String> jvmOptions = new ArrayList<>();
    protected boolean execExec = true;
    protected String mvnCommand;

    protected ProcessShell.EchoQuote quote = ProcessShell.EchoQuoteDefault.UnixShell;

    public enum ExecMode {
        Execute,
        ExecuteDefault,
        FindMainClass,
        FindProject,
        ListMainClass,
        ListMainClassWithProject,
        GetCommand,
        Help,
        Version,
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
        checkEchoQuote();

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
                case FindProject: {
                    executeFindProject();
                    break;
                }
                case ListMainClass: {
                    executeListMainClass();
                    break;
                }
                case ListMainClassWithProject: {
                    executeListMainClassWithProject();
                    break;
                }
                case Help: {
                    help();
                    break;
                }
                case Version: {
                    version();
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

    public void checkEchoQuote() {
        log("check echo-quote for getting command");
        quote = echoQuoteFromParentProcess();
    }

    public static ProcessShell.EchoQuote echoQuoteFromParentProcess() {
        var parent = ProcessHandle.current().parent();
        var parentCommand = parent.map(p ->
                        p.info().command().orElse(""))
                .orElse("");
        if (parentCommand.endsWith("powershell.exe") || parentCommand.endsWith("pwsh.exe") ||
                parentCommand.endsWith("cmd.exe")) { //cmd.exe appears if it runs .bat in powershell
            return ProcessShell.EchoQuoteDefault.PowerShell;
        } else {
            return ProcessShell.EchoQuoteDefault.UnixShell;
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
            ProcessShell<?> sh = getCommand(main.getPath(), main.getModuleName(), main.getName(), arguments);
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
            ProcessShell<?> sh = getCommand(main.getPath(), main.getModuleName(), main.getName(), arguments);
            System.out.println(sh.getEchoString());
        }
    }

    public void executeFindMainClass() {
        if (mainClass != null) {
            MainClassInfo main = findMainClassFromProjects(mainClass);
            if (main == null) {
                throw new NoSuchMainClassException(mainClass);
            }
            System.out.println(getMainName(useModulepath, main.getModuleName(), main.getName()));
        }
    }

    public String getMainName(boolean useModuleName, String moduleName, String mainName) {
        return useModuleName && moduleName != null ? (moduleName + "/" + mainName) : mainName;
    }

    public void executeFindProject() {
        if (mainClass != null) {
            MainClassInfo main = findMainClassFromProjects(mainClass);
            if (main == null) {
                throw new NoSuchMainClassException(mainClass);
            }
            System.out.println(main.getPath().toString());
        }
    }

    public static class NoSuchMainClassException extends RuntimeException {
        public NoSuchMainClassException(String mainClass) {
            super("not-found: " + mainClass);
        }
    }

    public void executeListMainClass() {
        listMainClassesFromProjects(false);
    }

    public void executeListMainClassWithProject() {
        listMainClassesFromProjects(true);
    }

    public void parseArgs(String... args) {
        boolean argsPart = false;
        for (int i = 0, l = args.length; i < l; ++i) {
            String arg = args[i];
            if (!argsPart) {
                if (arg.equals("-p") || arg.equals("--project")) {
                    ++i;
                    projectPaths.addFirst(new File(args[i]));
                } else if (arg.equals("-pr") || arg.equals("--projectReset")) {
                    ++i;
                    projectPaths.clear();
                    projectPaths.addFirst(new File(args[i]));
                } else if (arg.equals("-f") || arg.equals("--find")) {
                    modes.add(ExecMode.FindMainClass);
                } else if (arg.equals("-fp") || arg.equals("--findProject")) {
                    modes.add(ExecMode.FindProject);
                } else if (arg.equals("-l") || arg.equals("--list")) {
                    modes.add(ExecMode.ListMainClass);
                } else if (arg.equals("-lp") || arg.equals("--listProject")) {
                    modes.add(ExecMode.ListMainClassWithProject);
                } else if (arg.equals("-g") || arg.equals("--get")) {
                    modes.add(ExecMode.GetCommand);
                } else if (arg.equals("-r") || arg.equals("--run")) {
                    modes.add(ExecMode.Execute);
                } else if (arg.equals("-h") || arg.equals("--help")) {
                    modes.add(ExecMode.Help);
                    break;
                } else if (arg.equals("--version")) {
                    modes.add(ExecMode.Version);
                    break;
                } else if (arg.equals("-c") || arg.equals("--compile")) {
                    compile = true;
                } else if (arg.equals("-sac") || arg.equals("--suppressAutoCompile")) {
                    autoCompile = false;
                } else if (arg.equals("-sc") || arg.equals("--suppressComplete")) {
                    completeWorkingDirectory = false;
                } else if (arg.equals("--complete")) {
                    completeWorkingDirectory = true;
                } else if (arg.equals("--java")) {
                    ++i;
                    javaExecutable = args[i];
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
                } else if (arg.equals("-uc") || arg.equals("--useClasspath")) {
                    useClasspath = true;
                } else if (arg.equals("-um") || arg.equals("--useModulepath")) {
                    useModulepath = true;
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

        String helpMessage =
                getClass().getName() + " [options] <mainClass> <arguments>...\n" +
                "     <mainClass>        :  fully qualified name or sub-sequence of characters.\n" +
                "                           In the latter case, \"Abc\" becomes the pattern \"A.*?[bB].*?[cC]\".\n" +
                "     -p  | --project <path>      :  add a maven project directory. repeatable. later items have high precedence.\n" +
                "     -pr | --projectReset <path> :  clear existing project directories and add a directory.\n" +
                "     -f  | --find       :  show the matched main-class name.\n" +
                "     -fp | --findProject:  show the project containing the matched main-class.\n" +
                "     -l  | --list       :  show list of main-classes.\n" +
                "     -lp | --listProject:  show list of main-classes with project-path joined by tab.\n" +
                "     -g  | --get        :  show the command line.\n" +
                "     -r  | --run        :  execute the command line. automatically set (with showing the command line) if no -f,-l or -g.\n" +
                "     -c  | --compile    :  \"mvn compile\" before execution.\n" +
                "     --java <javaExec>  :  specify the java command name or JDK Home for exec. the default is \"java\".\n" +
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
                "     -uc | --useClasspath  : use -cp %classpath for execution (default).\n" +
                "     -um | --useModulepath : use -p %modulepath for execution. If it specified, no -cp options unless -uc specified.\n" +
                "                               Also, -m mod/pack.Cls for specifying the main-class.\n" +
                "     -w  | --logWarn    :  set the log-level to \"warn\", meaning \"-Dorg.slf4j.simpleLogger.defaultLogLevel=warn\" instead of \"error\". \n" +
                "     -e                 :  -w and pass -e to maven to show errors.\n" +
                "     -J<opt>            :  pass opt to JVM. e.g. -J-Xmx10g \n" +
                "     -M<opt>            :  pass opt to Maven. \n" +
                "     --logOff           :  set the log-level to \"off\".\n" +
                "     -D<name>[=<value>] :  set a system-property to exec JVM. repeatable.\n" +
                "     --debug            :  show debugging messages.\n" +
                "     -X                 :  --debug and pass -X to mvn.\n" +
                "     -h | --help        :  show help messages.\n" +
                "     --version          :  show version info.\n" +
                "     --mvn <mvnCommand> :  set the command name of maven. the default is \"mvn\" or \"mvn.cmd\" for Windows.\n" +
                "     --                 :  indicate the start of mainClass and/or arguments.s\n";
        System.out.println(helpMessage);
    }

    public void version() {
        ProcessShell<?> sh = ProcessShell.get(getMavenCommandVersion())
                .setRedirectToInherit();
        log("version", sh);
        var mod = getClass().getModule();
        String mvnExecVer = "";
        if (mod != null) {
            mvnExecVer = mod.getDescriptor().version()
                    .map(Objects::toString)
                    .orElse("");
        }
        System.out.printf("mvn-exec %s: java.version=%s, java.class.version=%s, java.home=%s%n",
                mvnExecVer,
                System.getProperty("java.version"),
                System.getProperty("java.class.version"),
                System.getProperty("java.home"));
        System.out.println(sh.runToString());
    }

    public MainClassInfo findMainClassFromProjects(String name) {
        Pattern namePattern = MainFinder.getPatternFromString(name);

        log("findMainClass pattern: %s", namePattern);
        int projectScale = (projectPaths.isEmpty() ? 0 : (int) Math.log10(projectPaths.size())) + 1;
        int scoreFactor = (int) Math.pow(10, projectScale);
        log("projectScale: %d, scoreFactor: %d", (Integer) projectScale, (Integer) scoreFactor);

        List<MainClassInfo> totalMains = new ArrayList<>();
        int i = 0;
        for (File path : projectPaths) {
            List<MainClassInfo> main = findMainClass(path, namePattern);
            //score by project order
            int fi = projectPaths.size() - i;
            main = main.stream()
                    .map(m -> m.withScore(m.getScore() * scoreFactor + fi))
                    .toList();
            totalMains.addAll(main);
            ++i;
        }
        totalMains.sort(
                Comparator.comparingInt(MainClassInfo::getScore)
                        .reversed());
        totalMains.forEach(m -> log("sorted %s", m));
        return totalMains.isEmpty() ? null : totalMains.getFirst();
    }

    public static class MainClassInfo {
        protected File path;
        protected String moduleName;
        protected String name;
        protected int score;

        public MainClassInfo(File path, String moduleName, String name, int score) {
            this.path = path;
            this.moduleName = moduleName;
            this.name = name;
            this.score = score;
        }

        public File getPath() {
            return path;
        }

        public String getModuleName() {
            return moduleName;
        }

        public String getName() {
            return name;
        }

        public int getScore() {
            return score;
        }

        public MainClassInfo withPath(File path) {
            return new MainClassInfo(path, moduleName, name, score);
        }

        public MainClassInfo withScore(int score) {
            return new MainClassInfo(path, moduleName, name, score);
        }

        @Override
        public String toString() {
            return "MainClassInfo{" +
                    "path=" + path +
                    ", module=" + (moduleName == null ? "null" : ('\'' +  moduleName + '\'')) +
                    ", name='" + name + '\'' +
                    ", score=" + score +
                    '}';
        }
    }

    public void listMainClassesFromProjects(boolean withProject) {
        projectPaths.forEach(p -> listMainClasses(p, withProject));
    }

    public void listMainClasses(File projectPath, boolean withProject) {
        for (ProjectClassDirs dirs : getClassesDirectories(projectPath)) {
            for (File dir : dirs.classDirs()) {
                var packageToModules = findPackageToModulesFromClassesDir(dir);
                if (dir.isDirectory()) {
                    try (Stream<Path> paths = findClassFromClassesDir(dir)) {
                        paths.map(p -> matchClass(p, null, packageToModules))
                                .filter(Objects::nonNull)
                                .map(m -> withProject ?
                                        String.join("\t", getMainName(useModulepath, m.getModuleName(), m.getName()), dirs.projectDir().getPath()) :
                                        getMainName(useModulepath, m.getModuleName(), m.getName()))
                                .forEach(System.out::println);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        }
    }

    public List<MainClassInfo> findMainClass(File projectDir, Pattern namePattern) {
        var clsDirs = getClassesDirectories(projectDir);
        return clsDirs.stream()
                .flatMap(dirs -> findMainClasses(dirs, namePattern).stream())
                .toList();
    }

    public List<ProjectClassDirs> getClassesDirectories(File projectDir) {
        return getClassesDirectories(projectDir, true);
    }

    public List<ProjectClassDirs> getClassesDirectories(File projectDir, boolean recursive) {
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
        var list = new ArrayList<>(List.of(new ProjectClassDirs(projectDir, List.of(classesDir, testClassesDir))));
        if (recursive && !canHaveTarget) {
            try (var items = Files.list(projectDir.toPath())) {
                items.filter(this::isMavenProjectDir)
                        .flatMap(f -> getClassesDirectories(f.toFile(), false).stream())
                        .forEach(list::add);
            } catch (IOException ex) {
                log("error listing %s : %s", projectDir, ex);
            }
        }
        return list;
    }

    public record ProjectClassDirs(File projectDir, List<File> classDirs) {}

    protected boolean isMavenProjectDir(Path path) {
        return Files.isDirectory(path) &&
            Files.isRegularFile(path.resolve("pom.xml"));
    }

    public boolean projectMayHaveNoTarget(File projectDir) {
        File pomFile = new File(projectDir, "pom.xml");
        if (!pomFile.exists()) {
            return true;
        }
        try {
            for (String line : Files.readAllLines(pomFile.toPath())) {
                //quick checking
                if (line.contains("<packaging>pom</packaging>")) { //aggregation of submodules
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
        command.add(getMavenCommandName(projectDir));
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

    public List<MainClassInfo> findMainClasses(ProjectClassDirs dirs, Pattern namePattern) {
        return dirs.classDirs().stream()
                .filter(File::isDirectory)
                .flatMap(dir -> findMainClassFromClassesDir(dir, namePattern).stream())
                .map(main -> main.withPath(dirs.projectDir()))
                .toList();
    }

    public List<MainClassInfo> findMainClassFromClassesDir(File classesDir, Pattern namePattern) {
        var packageToModules = findPackageToModulesFromClassesDir(classesDir);
        try (Stream<Path> paths = findClassFromClassesDir(classesDir)) {
            return paths.map(p -> matchClass(p, namePattern, packageToModules))
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    public Map<String, String> findPackageToModulesFromClassesDir(File classesDir) {
        Map<String, String> packageToModules = new HashMap<>();
        try (Stream<Path> paths = findModuleInfoClassFromClassesDir(classesDir)) {
            paths.map(p -> {
                        try {
                            var m = new ModulePackageFinder();
                            if (m.process(p)) {
                                return m;
                            } else {
                                return null;
                            }
                        } catch (IOException ioe) {
                            throw new RuntimeException(ioe);
                        }
                    }).filter(Objects::nonNull)
                    .forEach(m ->
                        m.getExports().forEach(p -> packageToModules.put(p, m.getName())));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        log("findPackageToModulesFromClassesDir(%s) -> %s", classesDir, packageToModules);
        return packageToModules;
    }

    public Stream<Path> findModuleInfoClassFromClassesDir(File classesDir) throws IOException {
        if (!classesDir.isDirectory()) {
            return Stream.of();
        } else {
            return Files.walk(classesDir.toPath())
                    .filter(Files::isRegularFile)
                    .filter(f -> f.getFileName().toString().equals("module-info.class"));
        }
    }

    public Stream<Path> findClassFromClassesDir(File classesDir) throws IOException {
        return Files.walk(classesDir.toPath())
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().endsWith(".class"));
    }

    public MainClassInfo matchClass(Path classFile, Pattern namePattern, Map<String, String> packageToModules) {
        try {
            MainFinder finder = new MainFinder(namePattern);
            String name = finder.matchClass(classFile);
            if (name != null) {
                String moduleName = null;
                int i = name.lastIndexOf('.');
                if (i != -1) {
                    moduleName = packageToModules.get(name.substring(0, i));
                }
                return new MainClassInfo(classFile.toFile(), moduleName, name, finder.getNameMatchedScore());
            } else {
                return null;
            }
        } catch (Exception ex) {
            System.err.println("matchClass: " + classFile + " : " + ex);
            ex.printStackTrace();
            return null;
        }
    }

    public ProcessShell<?> getCommand(File projectPath, String moduleName, String mainClass, List<String> args) {
        Instant commandCreationTime = Instant.now();
        return ProcessShell.get(execExec ?
                        getMavenCommandExec(projectPath, moduleName, mainClass, args) :
                        getMavenCommandExecJava(projectPath, moduleName, mainClass, args))
                .set(p -> {
                    p.directory(projectPath);
                    String mavenOpts = getMavenOpts();
                    log("MAVEN_OPTS: %s", mavenOpts);
                    p.environment().put("MAVEN_OPTS", mavenOpts);
                    if (debug) {
                        p.environment().put("MAVEN_EXEC_DEBUG_INIT_TIME", commandCreationTime.toString());
                    }
                })
                .setEchoQuote(quote)
                .setRedirectToInherit();
    }

    public String getMavenCommandName(File projectDir) {
        if (mvnCommand == null) {
            if (System.getProperty("os.name", "").contains("Windows")) {
                if (projectDir != null && new File(projectDir, "mvnw.cmd").isFile()) {
                    return new File(".", "mvnw.cmd").getPath();
                }
                mvnCommand = "mvn.cmd";
            } else {
                if (projectDir != null && new File(projectDir, "mvnw").isFile()) {
                    return new File(".", "mvnw").getPath();
                }
                mvnCommand = "mvn";
            }
        }
        return mvnCommand;
    }

    public List<String> getMavenCommandVersion() {
        List<String> command = new ArrayList<>();
        command.add(getMavenCommandName(null));
        command.add("--version");
        return command;
    }

    public List<String> getMavenCommandExec(File projectPath, String moduleName, String mainClass, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(getMavenCommandName(projectPath));
        command.addAll(mvnOptions);
        command.add("exec:exec");
        command.add("-Dexec.classpathScope=test");
        command.add("-Dexec.executable=" + getMavenCommandExecJavaExecutable());
        command.add("-Dexec.workingdir=" + new File(".").getAbsolutePath());
        command.add("-Dexec.args=" + getCommandArgumentsForExec(moduleName, mainClass, args));
        return command;
    }

    private String getMavenCommandExecJavaExecutable() {
        var file = new File(javaExecutable);
        if (file.isFile()) {
            return javaExecutable;
        } else if (file.isDirectory()) {
            var binJava = new File(file, "bin" + File.separator + "java");
            if (binJava.exists()) {
                return binJava.getPath();
            } else {
                var exeJava = new File(file, "bin" + File.separator + "java.exe"); //Windows
                if (exeJava.exists()) {
                    return exeJava.getPath();
                }
            }
        }
        return javaExecutable;
    }

    public String getCommandArgumentsForExec(String moduleName, String mainClass, List<String> args) {
        String jvmOpts = quote.join(jvmOptions);

        String propOpts = quote.join(propertySettings.stream()
                        .map(this::getPropertySetting)
                        .toList());

        String codePath = getCodePathArgumentForExec();
        String argStr = quote.join(args);
        if (useModulepath && moduleName != null) {
            mainClass = "-m " + getMainName(true, moduleName, mainClass);
        }
        if (argStr.isEmpty()) {
            return String.join(" ", jvmOpts, propOpts, codePath, mainClass);
        } else {
            return String.join(" ", jvmOpts, propOpts, codePath, mainClass, argStr);
        }
    }

    public String getCodePathArgumentForExec() {
        String cp = "-cp %classpath"; //-cp %classpath
        String mp = "-p %modulepath"; //-p %modulepath
        try {
            if (Integer.parseInt(System.getProperty("java.version", "0").split("\\.")[0]) < 9) { //1.8...
                mp = ""; //no module path
            }
        } catch (Exception ex) {
            log("version error: java.version=%s : %s", System.getProperty("java.version"), ex);
        }
        List<String> codePath = new ArrayList<>(2);
        if (useClasspath) {
            codePath.add(cp);
        }
        if (useModulepath) {
            codePath.add(mp);
        }
        if (!useClasspath && !useModulepath) {
            codePath.add(cp);
        }
        return String.join(" ", codePath);
    }

    public List<String> getMavenCommandExecJava(File projectDir, String moduleName, String mainClass, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(getMavenCommandName(projectDir));
        command.addAll(mvnOptions);
        command.add("exec:java");
        command.add("-Dexec.classpathScope=test");
        command.add("-Dexec.mainClass=" + getMainName(useModulepath, moduleName, mainClass));
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