@echo off
REM ============================================================
REM  SotaVisionTest — Compile
REM  Compile semua file Java untuk Social Interaction System
REM  Pakai JDK dari Eclipse (tidak perlu install Java terpisah)
REM ============================================================

set JAVA_HOME=C:\Users\interact-ai-001\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_21.0.10.v20260205-0638\jre
set JAVAC=%JAVA_HOME%\bin\javac.exe

REM Pindah ke folder SotaVisionTest
cd /d "%~dp0"

REM Path ke library Sota SDK
set LIB_DIR=..\lib
set CLASSPATH=%LIB_DIR%\sotalib.jar;%LIB_DIR%\SRClientHelper.jar;%LIB_DIR%\opencv-310.jar;%LIB_DIR%\jna-4.1.0.jar

REM Output directory
set BIN_DIR=bin
if not exist "%BIN_DIR%" mkdir "%BIN_DIR%"

echo.
echo ============================================================
echo   SotaVisionTest — Compiling Social Interaction System
echo ============================================================
echo.

REM Compile semua file Java
echo [1/9] Compiling SocialState.java...
"%JAVAC%" -source 8 -target 8 -cp "%CLASSPATH%" -d "%BIN_DIR%" src\jp\vstone\sotavisiontest\SocialState.java
if errorlevel 1 goto :error

echo [2/9] Compiling UserProfile.java...
"%JAVAC%" -source 8 -target 8 -cp "%CLASSPATH%;%BIN_DIR%" -d "%BIN_DIR%" src\jp\vstone\sotavisiontest\UserProfile.java
if errorlevel 1 goto :error

echo [3/9] Compiling MemoryManager.java...
"%JAVAC%" -source 8 -target 8 -cp "%CLASSPATH%;%BIN_DIR%" -d "%BIN_DIR%" src\jp\vstone\sotavisiontest\MemoryManager.java
if errorlevel 1 goto :error

echo [4/9] Compiling SocialStateMachine.java...
"%JAVAC%" -source 8 -target 8 -cp "%CLASSPATH%;%BIN_DIR%" -d "%BIN_DIR%" src\jp\vstone\sotavisiontest\SocialStateMachine.java
if errorlevel 1 goto :error

echo [5/9] Compiling LlamaClient.java...
"%JAVAC%" -source 8 -target 8 -cp "%CLASSPATH%;%BIN_DIR%" -d "%BIN_DIR%" src\jp\vstone\sotavisiontest\LlamaClient.java
if errorlevel 1 goto :error

echo [6/9] Compiling SpeechManager.java...
"%JAVAC%" -source 8 -target 8 -cp "%CLASSPATH%;%BIN_DIR%" -d "%BIN_DIR%" src\jp\vstone\sotavisiontest\SpeechManager.java
if errorlevel 1 goto :error

echo [7/9] Compiling FaceManager.java...
"%JAVAC%" -source 8 -target 8 -cp "%CLASSPATH%;%BIN_DIR%" -d "%BIN_DIR%" src\jp\vstone\sotavisiontest\FaceManager.java
if errorlevel 1 goto :error

echo [8/9] Compiling ConversationManager.java...
"%JAVAC%" -source 8 -target 8 -cp "%CLASSPATH%;%BIN_DIR%" -d "%BIN_DIR%" src\jp\vstone\sotavisiontest\ConversationManager.java
if errorlevel 1 goto :error

echo [9/9] Compiling MainController.java...
"%JAVAC%" -source 8 -target 8 -cp "%CLASSPATH%;%BIN_DIR%" -d "%BIN_DIR%" src\jp\vstone\sotavisiontest\MainController.java
if errorlevel 1 goto :error

echo.
echo ============================================================
echo   [OK] All files compiled successfully!
echo   Output: %BIN_DIR%\jp\vstone\sotavisiontest\*.class
echo ============================================================
echo.
pause
exit /b 0

:error
echo.
echo [ERROR] Compilation failed! Check the error above.
echo.
pause
exit /b 1
