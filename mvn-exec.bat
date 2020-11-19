@echo off

set PROJDIR="%~dp0"
if exist "%PROJDIR%\target\mods" (
    rem nothing
) else (
    set BACKDIR="%cd%"
    cd "%PROJDIR%"
    call mvn clean package dependency:copy-dependencies "-DoutputDirectory=${project.build.directory}/mods"
    copy target\mvn-exec-*.jar target\mods
    cd "%BACKDRIR%"
)

java -p "%~dp0\target\mods" -m "org.autogui.mvn_exec" %*
