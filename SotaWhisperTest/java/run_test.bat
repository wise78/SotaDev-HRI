@echo off
SET JAVA_HOME=C:\Users\interact-ai-001\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_21.0.10.v20260205-0638\jre
SET JAVA=%JAVA_HOME%\bin\java.exe

SET SERVER=%1
SET WAV=%2
IF "%SERVER%"=="" SET SERVER=localhost
IF "%WAV%"=="" SET WAV=..\audio\test_english.wav

echo Running TestWhisperSota...
echo   Server: %SERVER%
echo   WAV:    %WAV%
echo.

"%JAVA%" -cp . TestWhisperSota %SERVER% %WAV%
pause
