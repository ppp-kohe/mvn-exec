# mvn-exec

This is a small utility for executing a main class in a Maven project with short command line.

## Building and installation

The project uses [apache-maven](http://maven.apache.org) and depends on Java 8 or later.

Add the script `mvn-exec` to your `PATH` and execute the script.
The script will automatically compile the project with Maven.

You can also build the utility with `mvn compile package ` 
and then you can execute `java -jar target/mvn-exec-1.4.jar`

## Usage

* specify your maven project path with `-p` option.
* supply a class-name in your project you want to launch.

e.g. suppose `my-maven-project` contains `my.pack.MyMainClass` as an executable main-class.

```bash
  % mvn-exec -p path/to/my-maven-project MyMainClass arg1 arg2 arg3
```

The script launch the class via the following command line:

```bash
  > mvn exec:exec -Dexec.classpathScope=test \
     -Dexec.executable=java \
     -Dexec.workingdir=your/current/path/. \
     -Dexec.args="-cp %classpath -p %modulepath my.pack.MyMainClass arg1 arg2 arg3"
```

The tool relies on [exec-maven-plugin](https://www.mojohaus.org/exec-maven-plugin/) for launching a class.

* `-g` just gets the command line without launching and outputs it to the standard output.

```bash
  % mvn-exec -p path/to/my-maven-project -g MyMainClass 
  mvn exec:exec -Dexec.classpathScope=test -Dexec.executable=java -Dexec.workingdir=your/current/path/. -Dexec.args="-cp %classpath -p %modulepath my.pack.MyMainClass"
```

* `-f` can find the specified class-name and show the qualified name

```bash
  % mvn-exec -p path/to/my-maven-project MyMainClass -f
  my.pack.MyMainClass
```

* `-l` can list executable main classes in your project

```bash
  % mvn-exec -p path/to/my-maven-project -l
  my.pack.MyMainClass
  ...

```

* `--` can explicitly split options for `mvn-exec` and program arguments

```bash
  % mvn-exec -p path/to/my-maven-project MyMainClass -- -f -l -p 
  > mvn exec:exec -Dexec.classpathScope=test \
     -Dexec.executable=java \
     -Dexec.workingdir=your/current/path/. \
     -Dexec.args="-cp %classpath -p %modulepath my.pack.MyMainClass -f -l -p"
```

* Also, `-r` can launch command line same as regular execution other than not showing the `> mvn exec:exec ...` line to the standard error.

### Comiling the target project before execution

* append `-c` in order to run `mvn compile` before execution if you have edited some code in your project

```bash
  % mvn-exec -p path/to/my-maven-project -c MyMainClass arg1 arg2 arg3
  > mvn compile
  > mvn exec:exec -Dexec.classpathScope=test \
    -Dexec.executable=java \
    -Dexec.workingdir=your/current/path/. \
    -Dexec.args="-cp %classpath -p %modulepath my.pack.MyMainClass arg1 arg2 arg3"
```

If the project directory does not have `target/classes` sub-directory, 
then the tool also runs `mvn compile`.

* `-sac` suppresses the automatic comilation for creating `target/classes`.


### Class name completion 

The tool searches main classes of a target project thanks to [ASM](https://asm.ow2.io). 

You can specify a main class by a fully qualified name or a sub-sequence of characters.
In the latter case, `Abc` becomes the pattern `A.*?[bB].*?[cC]`.

e.g. `my.pack.MyMainClass` can be specifyed by `mmc`, `MMC`, `Mmc`, `MyMaCl` and so on.

Sometimes, a longer name is selected. e.g.  `MyMainClass` selects `my.pack.MyMainClassTest` instead of `my.pack.MyMainClass`. 
For selecting the shorter name,  use dot prefix like  `.MyMainClass`.

### Relative path issue (for exec:java)

By default, the tool launches program by `mvn exec:exec -Dexec.executable=java ...`. 
The option `--execJava` can switch to `exec:java`. 
The `exec:exec` launches a new  JVM process for program. 
On the other hand, the `exec:java` runs the program in the same proecess of Maven.

`mvn exec:java` seems to be designed for executing under the project directory of current working directory.
So, the launched class cannot handle relative paths of the working directory launching the `mvn-exec` script.
To solve the problem, the utility automatically converts relative paths in arguments to absolute paths.

* `-sc` suppresses the path compilation. 

Default completion behavior recognize the following patterns as candidates of relative path:

  1. a relative path: `<p1>`
  2. a path list: `<p1>:<p2>...`
  3. a path list of value of property-like arg: `-...=<p1>:<p1>...`

If a path `<pN>` starts with non-`/` (`Paths.get(p).isAbsolute()==false`)
and the first one component of the path exists, then `<pN>` will be completed.
i.e. `existing/nonExisting` will be completed as `/path/to/existing/nonExisting`.

In Windows, `/` becomes `\` and `:` becoms `;`.

Note: `mvn exec:java` cannot change the working directory other than the target project dir. Thus, we need to provide the absolute path for an outer path of the project as arguments for the executed program.

### Showing errors

`exec-maven-plugin` outputs some kind of errors through maven's logger. 
The tool suppresses those logging messages because of invading the standard-output of the execution of command line.
This is realized by supplying `-Dorg.slf4j.simpleLogger.defaultLogLevel=error` via the environment variable `MAVEN_OPTS`.
The log-level only shows `[ERROR]` lines to the standard-output, which indicate occurrence of errors by compilation failure or unhandled exceptions.

* `--logOff` completely turns off log-lines including those error messages, realized by `-Dorg.slf4j.simpleLogger.defaultLogLevel=off`.

The suppressed messages include JVM launching errors such as `ClassNotFoundException` and `UnsupportedClassVersionError`. 
Thus, if you launch a program compiled in Java 11 by the tool under Java 8, 
then JVM causes `UnsupportedClassVersionError` but the tool silently exits.

* `-w` show WARNING and ERROR messages caused by maven execution including JVM failures, realized by `-Dorg.slf4j.simpleLogger.defaultLogLevel=warn`.

