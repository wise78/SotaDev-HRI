@echo off
REM ============================================================
REM  VisionTestServer — Compile
REM  Kompilasi VisionTestServer.java untuk test vision Sota
REM ============================================================

set JAVA_HOME=C:\Users\interact-ai-001\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_21.0.10.v20260205-0638\jre
set JAVAC=%JAVA_HOME%\bin\javac.exe

cd /d "%~dp0"

REM Paths (relative to vision_test_server/)
set LIB_DIR=..\..\lib
set CLASSPATH=%LIB_DIR%\sotalib.jar;%LIB_DIR%\SRClientHelper.jar;%LIB_DIR%\opencv-310.jar;%LIB_DIR%\jna-4.1.0.jar
set BIN_DIR=..\bin
set SRC_FILE=..\src\jp\vstone\sotavisiontest\VisionTestServer.java

if not exist "%BIN_DIR%" mkdir "%BIN_DIR%"

echo.
echo ============================================================
echo   Compiling VisionTestServer.java
echo ============================================================
echo.

"%JAVAC%" -source 8 -target 8 -cp "%CLASSPATH%" -d "%BIN_DIR%" "%SRC_FILE%"

if errorlevel 1 (
    echo.
    echo [ERROR] Compilation failed!
    echo.
    pause
    exit /b 1
)

echo [OK] VisionTestServer compiled successfully!
echo     Output: %BIN_DIR%\jp\vstone\sotavisiontest\VisionTestServer.class
echo.
pause
exit /b 0
