@echo off
rem Build + recompile the mod jar (also downloads MC 26.2 dev client on first run)
setlocal
cd /d "%~dp0"
set "JAVA_HOME=O:\MohistServer\JDK25\jdk-25.0.1"
call gradlew.bat build
endlocal
