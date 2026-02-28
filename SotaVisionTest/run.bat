@echo off
REM ============================================================
REM  SotaVisionTest — Compile & Run
REM  One-click: compile lalu jalankan Social Interaction System
REM ============================================================

set JAVA_HOME=C:\Users\interact-ai-001\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_21.0.10.v20260205-0638\jre
set JAVAC=%JAVA_HOME%\bin\javac.exe
set JAVA=%JAVA_HOME%\bin\java.exe

cd /d "%~dp0"

set LIB_DIR=..\lib
set CLASSPATH=%LIB_DIR%\sotalib.jar;%LIB_DIR%\SRClientHelper.jar;%LIB_DIR%\opencv-310.jar;%LIB_DIR%\jna-4.1.0.jar
set BIN_DIR=bin

echo.
echo ============================================================
echo   SotaVisionTest — Social Interaction System
echo ============================================================
echo.

REM Check if compiled
if not exist "%BIN_DIR%\jp\vstone\sotavisiontest\MainController.class" (
    echo [INFO] Not compiled yet. Running compile.bat first...
    call compile.bat
    if errorlevel 1 (
        echo [ERROR] Compilation failed. Cannot run.
        pause
        exit /b 1
    )
)

echo.
echo Ollama URL options:
echo   1. localhost    (Ollama running on this machine)
echo   2. Custom URL   (Ollama running on another machine)
echo.
set /p CHOICE="Select (1 or 2): "

set OLLAMA_URL=http://localhost:11434
if "%CHOICE%"=="2" (
    set /p OLLAMA_URL="Enter Ollama URL (e.g. http://192.168.11.5:11434): "
)

echo.
echo ============================================================
echo   Starting Sota Social Interaction System
echo   Ollama: %OLLAMA_URL%
echo   Press Ctrl+C to stop
echo ============================================================
echo.

"%JAVA%" -cp "%BIN_DIR%;%CLASSPATH%" -Dfile.encoding=UTF-8 jp.vstone.sotavisiontest.MainController %OLLAMA_URL%

echo.
pause
