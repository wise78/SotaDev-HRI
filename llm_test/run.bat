@echo off
REM ============================================================
REM  run.bat — Sota LLM Pipeline Launcher
REM  Activates venv, runs health check, then starts chat.
REM ============================================================

SET SCRIPT_DIR=%~dp0
SET VENV_ACTIVATE=%SCRIPT_DIR%test_client\venv\Scripts\activate.bat

IF NOT EXIST "%VENV_ACTIVATE%" (
    echo [ERROR] Virtual environment not found.
    echo         Please run setup first:
    echo           cd test_client
    echo           python -m venv venv
    echo           venv\Scripts\activate
    echo           pip install -r requirements.txt
    pause
    exit /b 1
)

echo Activating virtual environment...
call "%VENV_ACTIVATE%"

echo.
echo ============================================================
echo   Step 1: Ollama Health Check
echo ============================================================
python "%SCRIPT_DIR%test_client\check_ollama.py"

IF %ERRORLEVEL% NEQ 0 (
    echo.
    echo [ERROR] Health check failed. Fix the issue above, then re-run.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo   Step 2: Starting Sota Chat
echo ============================================================
python "%SCRIPT_DIR%test_client\test_chat.py"

pause
