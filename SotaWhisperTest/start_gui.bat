@echo off
REM ============================================================
REM  start_gui.bat — Launch Whisper STT Test GUI
REM  Double-click this file or run from PowerShell.
REM ============================================================

SET SCRIPT_DIR=%~dp0
SET VENV_ACTIVATE=%SCRIPT_DIR%venv\Scripts\activate.bat

IF NOT EXIST "%VENV_ACTIVATE%" (
    echo [ERROR] venv not found. Run setup_venv.bat first.
    pause
    exit /b 1
)

call "%VENV_ACTIVATE%"

echo Starting Whisper STT GUI...
python "%SCRIPT_DIR%whisper_gui.py"

pause
