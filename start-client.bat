@echo off
rem ============================================================
rem  Wikievery - quick dev client launcher (offline, debug build)
rem  1) forces JDK 25 (required by Minecraft 26.2)
rem  2) runs the Loom dev client from the "run/" directory.
rem     Microsoft authentication is OFF by default, so no login
rem     is needed for offline single-player testing.
rem     (To use an online account instead, run:  gradlew.bat login
rem      then add --profile for your profile name here.)
rem ============================================================
setlocal
cd /d "%~dp0"

set "JAVA_HOME=O:\MohistServer\JDK25\jdk-25.0.1"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK 25 not found at %JAVA_HOME%
    exit /b 1
)

echo [start-client] Using JAVA_HOME=%JAVA_HOME%
call gradlew.bat runClient
endlocal
