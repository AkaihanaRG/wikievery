@echo off
rem ============================================================
rem  Wikievery - local dev LAN server (offline mode)
rem  World: runServer/world (copy of the single-player save)
rem  Run this BEFORE starting the client; the client auto-joins
rem  localhost:25565 as a regular (non-host) player.
rem ============================================================
setlocal
cd /d "%~dp0"

set "JAVA_HOME=O:\MohistServer\JDK25\jdk-25.0.1"
if not exist "%JAVA_HOME%\bin\java.exe" (
    echo [ERROR] JDK 25 not found at %JAVA_HOME%
    exit /b 1
)

echo [start-server] Using JAVA_HOME=%JAVA_HOME%
call gradlew.bat runServer
endlocal
