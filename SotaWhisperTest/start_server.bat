@echo off
REM ============================================================
REM  start_server.bat — Launch Whisper STT server
REM  Server runs at http://0.0.0.0:5050
REM  Press Ctrl+C to stop.
REM ============================================================

SET SCRIPT_DIR=%~dp0
SET VENV_ACTIVATE=%SCRIPT_DIR%venv\Scripts\activate.bat

IF NOT EXIST "%VENV_ACTIVATE%" (
    echo [ERROR] venv not found. Run setup_venv.bat first.
    pause
    exit /b 1
)

call "%VENV_ACTIVATE%"
chcp 65001 >nul
set PYTHONUTF8=1
set PYTHONIOENCODING=utf-8
set PYTHONUNBUFFERED=1

echo.
echo ============================================================
echo   Sota Whisper STT Server
echo   Endpoint: http://0.0.0.0:5050
echo   Health:   http://localhost:5050/health
echo   Press Ctrl+C to stop
echo ============================================================
echo.

python "%SCRIPT_DIR%whisper_server.py"

echo.
pause
