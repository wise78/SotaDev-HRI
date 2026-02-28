@echo off
REM ============================================================
REM  VisionTestServer — Start Python GUI Client
REM  Jalankan di laptop untuk kontrol dan monitoring Sota
REM  Requires Python 3.12 (TensorFlow/DeepFace not supported on 3.14)
REM ============================================================

cd /d "%~dp0"

echo.
echo ============================================================
echo   Sota Vision Test — GUI Client (DeepFace Edition)
echo ============================================================
echo.

REM Use Python 3.12 (required for TensorFlow/DeepFace)
set PY=py -3.12
REM Fix DeepFace emoji logging crash on Windows cp1252 console
set PYTHONIOENCODING=utf-8
REM Suppress TensorFlow info/warning spam
set TF_ENABLE_ONEDNN_OPTS=0
set TF_CPP_MIN_LOG_LEVEL=2

%PY% --version >nul 2>nul
if errorlevel 1 (
    echo [ERROR] Python 3.12 not found.
    echo   DeepFace requires Python 3.10-3.12 (TensorFlow not available on 3.13+)
    echo   Install: https://www.python.org/downloads/release/python-3120/
    pause
    exit /b 1
)

REM Check DeepFace + Pillow
%PY% -c "from deepface import DeepFace; from PIL import Image" >nul 2>nul
if errorlevel 1 (
    echo [INFO] Installing dependencies (DeepFace, TensorFlow, Pillow)...
    echo        This may take a few minutes on first run.
    %PY% -m pip install -r requirements.txt
    echo.
)

echo Starting GUI...
echo   Default Sota IP: 192.168.11.1
echo   Default Port: 8889
echo.
echo   Usage: start_gui.bat --ip 192.168.11.30 --port 8889
echo.

%PY% vision_test_gui.py %*

pause
