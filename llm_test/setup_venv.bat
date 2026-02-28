@echo off
REM ============================================================
REM  setup_venv.bat
REM  Run this ONCE after installing Python to create the venv
REM  and install dependencies.
REM ============================================================

SET SCRIPT_DIR=%~dp0
SET VENV_DIR=%SCRIPT_DIR%test_client\venv

echo Checking Python installation...
python --version >nul 2>&1
IF %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Python not found. Please install Python 3.8+ from:
    echo         https://www.python.org/downloads/
    echo         Make sure to check "Add Python to PATH" during install.
    pause
    exit /b 1
)

FOR /F "tokens=*" %%i IN ('python --version') DO SET PYVER=%%i
echo [OK] Found %PYVER%

IF EXIST "%VENV_DIR%\Scripts\activate.bat" (
    echo [INFO] Virtual environment already exists at test_client\venv
    echo        Delete it manually if you want to recreate it.
    goto install
)

echo Creating virtual environment at test_client\venv ...
python -m venv "%VENV_DIR%"
IF %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Failed to create virtual environment.
    pause
    exit /b 1
)
echo [OK] Virtual environment created.

:install
echo Installing dependencies...
call "%VENV_DIR%\Scripts\activate.bat"
pip install --upgrade pip --quiet
pip install -r "%SCRIPT_DIR%test_client\requirements.txt"

IF %ERRORLEVEL% NEQ 0 (
    echo [ERROR] pip install failed.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  Setup complete! You can now run:
echo    run.bat          (health check + chat)
echo  Or manually:
echo    cd test_client
echo    venv\Scripts\activate
echo    python check_ollama.py
echo ============================================================
pause
