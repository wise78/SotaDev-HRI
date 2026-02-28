@echo off
REM ============================================================
REM  SotaLLMBridge — Compile & Run
REM  Double-click ini untuk compile + jalankan
REM  Tidak perlu install Java — pakai JDK dari Eclipse
REM ============================================================

set JAVA_HOME=C:\Users\interact-ai-001\.p2\pool\plugins\org.eclipse.justj.openjdk.hotspot.jre.full.win32.x86_64_21.0.10.v20260205-0638\jre
set JAVAC=%JAVA_HOME%\bin\javac.exe
set JAVA=%JAVA_HOME%\bin\java.exe

REM Pindah ke folder llm_test (lokasi file .java dan .class)
cd /d "%~dp0"

echo.
echo === Compiling SotaLLMBridge.java ===
"%JAVAC%" -source 8 -target 8 SotaLLMBridge.java
if errorlevel 1 (
    echo.
    echo [ERROR] Kompilasi gagal.
    pause
    exit /b 1
)
echo [OK] Berhasil dikompilasi.
echo.

echo Pilih mode:
echo   1. Benchmark  (10 pesan, ukur latency - tidak perlu input)
echo   2. Chat       (percakapan interaktif - kamu bisa ketik)
echo.
set /p MODE="Masukkan 1 atau 2: "

if "%MODE%"=="2" (
    echo.
    echo === Chat Mode -- ketik pesanmu, 'quit' untuk keluar ===
    echo.
    "%JAVA%" -cp . -Dfile.encoding=UTF-8 SotaLLMBridge --chat
) else (
    echo.
    echo === Benchmark Mode ===
    "%JAVA%" -cp . -Dfile.encoding=UTF-8 SotaLLMBridge
)

echo.
pause
