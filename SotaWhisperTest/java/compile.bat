@echo off
SET JAVA_HOME=C:\Users\interact-ai-001\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_21.0.10.v20260205-0638\jre
SET JAVAC=%JAVA_HOME%\bin\javac.exe

echo Compiling WhisperSTT.java + TestWhisperSota.java...
"%JAVAC%" -source 1.8 -target 1.8 WhisperSTT.java TestWhisperSota.java

IF %ERRORLEVEL% EQU 0 (
    echo [OK] Compiled successfully.
) ELSE (
    echo [FAIL] Compilation failed.
)
pause
