# mvn-exec

This is a small utility for executing a main class in a Maven project with short command line.


The project uses [apache-maven](http://maven.apache.org) and depends on Java 8 or later.

Add the script `mvn-exec` to your `PATH` and execute the script.
The script will automatically compile the project with Maven.

* specify your maven project path with `-p` option.
* supply a class-name in your project you want to launch.

e.g. suppose `my-maven-project` contains `my.pack.MyMainClass` as an executable main-class.

```bash
  % mvn-exec -p path/to/my-maven-project MyMainClass arg1 arg2 arg3
```

The script launch the class via the following command line:

```bash
  % mvn exec:java -Dexec.classpathScope=test \
     -Dexec.mainClass=my.pack.MyMainClass \
     -Dexec.args="arg1 arg2 arg3"
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

Note: `mvn exec:java` seems to be designed for executing under the project directory of current working directory.
So, the launched class cannot handle relative paths of the working directory launching the `mvn-exec` script.
To solve the problem, the utility automatically converts relative paths in arguments to absolute paths.
See the `--help` message for details.
