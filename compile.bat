@echo off
rem ============================================================
rem  Entity Hack build script (JDK 8)
rem  Usage:
rem    compile.bat        compile main sources and package entity-hack.jar
rem    compile.bat test   also compile mock tests into build-test\ for run.bat mock
rem ============================================================
setlocal EnableDelayedExpansion

rem JDK 8 detection order (no need to edit this script):
rem   1. ENTITY_HACK_JDK env var (explicit, highest priority)
rem   2. JAVA_HOME (used when it points to a JDK; CI's setup-java sets it)
rem   3. auto-probe %ProgramFiles%\Java for jdk1.8* / jdk8*
set "JDK8="
if defined ENTITY_HACK_JDK if exist "!ENTITY_HACK_JDK!\bin\javac.exe" set "JDK8=!ENTITY_HACK_JDK!"
if not defined JDK8 if defined JAVA_HOME if exist "!JAVA_HOME!\bin\javac.exe" set "JDK8=!JAVA_HOME!"
if not defined JDK8 (
    for /d %%d in ("%ProgramFiles%\Java\jdk1.8*" "%ProgramFiles%\Java\jdk8*") do (
        if not defined JDK8 if exist "%%d\bin\javac.exe" set "JDK8=%%d"
    )
)
if not defined JDK8 (
    echo [ERROR] Java 8 JDK not found - javac.exe is missing.
    echo         Set ENTITY_HACK_JDK to your JDK 8 home, or install JDK 8.
    exit /b 1
)
echo [0/6] Using JDK: %JDK8%

set "TOOLS_SRC=%JDK8%\lib\tools.jar"
set "DLL_SRC=%JDK8%\jre\bin\attach.dll"
set "ROOT=%~dp0"
cd /d "%ROOT%"

set "SRC=src\org\github\creatorcsie\entityhack"
set "BUILD=build"
set "OUT=%ROOT%entity-hack.jar"
set "TMPLIST=%TEMP%\entity-hack-sources.txt"
set "TMPMF=%TEMP%\entity-hack-manifest.mf"
set "TMPTESTLIST=%TEMP%\entity-hack-test-sources.txt"
set "LIBDIR=lib"
set "TOOLS=%LIBDIR%\tools.jar"
set "NATIVES=natives"
set "DLL=%NATIVES%\attach.dll"

echo [1/6] Preparing runtime dependencies (lib\tools.jar + natives\attach.dll)...
rem 两件 JDK 专属运行时资产必须随发布包一起走：
rem   1. lib\tools.jar   com.sun.tools.attach / sun.tools.attach（JRE 里没有）
rem   2. natives\attach.dll  WindowsAttachProvider 的 System.loadLibrary("attach")
rem 每次构建都从 JDK 强制覆盖，避免陈旧/半截文件导致 "%1 is not a valid Win32 application"。
if not exist "%LIBDIR%" mkdir "%LIBDIR%"
if not exist "%TOOLS_SRC%" (
    echo [ERROR] tools.jar not found in JDK: %TOOLS_SRC%
    exit /b 1
)
copy /y "%TOOLS_SRC%" "%TOOLS%" >nul
if errorlevel 1 (
    echo [ERROR] Failed to copy tools.jar
    exit /b 1
)
if not exist "%NATIVES%" mkdir "%NATIVES%"
if not exist "%DLL_SRC%" (
    echo [WARN] attach.dll not found in JDK, multiprocess attach will fail
) else (
    copy /y "%DLL_SRC%" "%DLL%" >nul
    if errorlevel 1 (
        echo [ERROR] Failed to copy attach.dll
        exit /b 1
    )
)

echo [2/6] Cleaning build dir...
if exist "%BUILD%" rmdir /s /q "%BUILD%" 2>nul
mkdir "%BUILD%" 2>nul

echo [3/6] Collecting sources...
dir /b /s /a-d "%SRC%\*.java" > "%TMPLIST%"

echo [4/6] Compiling (Java 7 bytecode for old Minecraft JVMs)...
"%JDK8%\bin\javac.exe" -encoding UTF-8 -source 1.7 -target 1.7 -cp "%TOOLS%" -d "%BUILD%" "@%TMPLIST%"
if errorlevel 1 (
    echo [ERROR] Compile failed
    exit /b 1
)

echo [5/6] Packaging %OUT%...
(
    echo Main-Class: org.github.creatorcsie.entityhack.loader.Main
    echo Agent-Class: org.github.creatorcsie.entityhack.agent.AgentMain
    echo.
) > "%TMPMF%"
rem 只打包 build\org（编译产物），sources.txt / MANIFEST 不进 jar。
"%JDK8%\bin\jar.exe" cfm "%OUT%" "%TMPMF%" -C "%BUILD%" org
if errorlevel 1 (
    echo [ERROR] Packaging failed
    exit /b 1
)
del "%TMPMF%" 2>nul

echo [6/6] Done: %OUT%
echo           release with:  %TOOLS%  and  %DLL%  (keep them next to the jar)

if /i "%~1"=="test" (
    echo.
    echo [TEST] Compiling mock tests...
    if exist "build-test" rmdir /s /q "build-test" 2>nul
    mkdir "build-test" 2>nul
    dir /b /s /a-d "test\*.java" > "%TMPTESTLIST%"
    "%JDK8%\bin\javac.exe" -encoding UTF-8 -source 1.7 -target 1.7 -cp "%BUILD%;%TOOLS%" -d "build-test" "@%TMPTESTLIST%"
    if errorlevel 1 (
        echo [ERROR] Mock compile failed
        exit /b 1
    )
    del "%TMPTESTLIST%" 2>nul
    echo [TEST] Done: build-test\
)

endlocal
