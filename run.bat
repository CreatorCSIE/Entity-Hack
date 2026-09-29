@echo off
rem ============================================================
rem  Entity Hack run script (JRE only, no JDK needed)
rem  Usage:
rem    run.bat                     打开注入器 GUI
rem    run.bat --attach PID        直接注入指定 JVM
rem    run.bat probe ^<jar 或版本名^>   离线结构探针（不需要开游戏，验证定界逻辑）
rem    run.bat probe all           对分析文档矩阵里的 7 个版本逐个探针
rem    run.bat mock                跑 Mock 端到端验证（需先 compile.bat test）
rem ============================================================
setlocal EnableDelayedExpansion

set "ROOT=%~dp0"
cd /d "%ROOT%"

rem Java detection order: JAVA_HOME -> %ProgramFiles%\Java\*1.8* -> PATH
set "JAVA="
if defined JAVA_HOME if exist "!JAVA_HOME!\bin\java.exe" set "JAVA=!JAVA_HOME!\bin\java.exe"
if not defined JAVA (
    for /d %%d in ("%ProgramFiles%\Java\jdk1.8*" "%ProgramFiles%\Java\jre1.8*") do (
        if not defined JAVA if exist "%%d\bin\java.exe" set "JAVA=%%d\bin\java.exe"
    )
)
if not defined JAVA (
    where java >nul 2>nul
    if errorlevel 1 (
        echo [ERROR] Java not found. Please install Java 8 or newer.
        exit /b 1
    )
    set "JAVA=java"
)

rem ---- mock: end-to-end verification against the fake worlds -------------
rem      "run.bat mock" is headless logic only; "run.bat mock gui" additionally
rem      opens the real EntityWindow and checks that the 1.2s refresh loop does
rem      not wipe selection / cell editors / the type dropdown.
if /i "%~1"=="mock" (
    if not exist "build-test\mock\MockMain.class" (
        echo [ERROR] Mock tests are not compiled yet. Run: compile.bat test
        exit /b 1
    )
    "%JAVA%" -cp "build;build-test" mock.MockMain %~2
    if errorlevel 1 (
        echo Mock verification FAILED.
        exit /b 1
    )
    exit /b 0
)

set "JAR=%ROOT%entity-hack.jar"
if not exist "%JAR%" (
    echo [ERROR] %JAR% not found. Run compile.bat first.
    exit /b 1
)
if not exist "lib\tools.jar" (
    echo [ERROR] lib\tools.jar not found. Run compile.bat first.
    exit /b 1
)

rem java.library.path=natives 让 System.loadLibrary("attach") 找到 attach.dll
"%JAVA%" -Djava.library.path=natives -cp "%JAR%;lib\tools.jar" org.github.creatorcsie.entityhack.loader.Main %*
exit /b %errorlevel%
