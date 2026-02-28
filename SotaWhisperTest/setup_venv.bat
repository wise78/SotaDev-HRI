@echo off
REM ============================================================
REM  setup_venv.bat — SotaWhisperTest
REM  Run this ONCE to create the venv and install dependencies.
REM  IMPORTANT: PyTorch with CUDA must be installed BEFORE whisper.
REM ============================================================

SET SCRIPT_DIR=%~dp0
SET VENV_DIR=%SCRIPT_DIR%venv

echo ============================================================
echo   SotaWhisperTest — Environment Setup
echo ============================================================
echo.

REM --- Check Python 3.12 (required for PyTorch CUDA wheels) ---
echo Checking Python 3.12...
SET PYTHON_CMD=py -3.12
%PYTHON_CMD% --version >nul 2>&1
IF %ERRORLEVEL% NEQ 0 (
    echo [WARN] py -3.12 not found, trying python3.12...
    SET PYTHON_CMD=python3.12
    %PYTHON_CMD% --version >nul 2>&1
    IF %ERRORLEVEL% NEQ 0 (
        echo [ERROR] Python 3.12 not found. PyTorch CUDA wheels require Python 3.10-3.12.
        echo         Python 3.13+ does NOT have PyTorch GPU support yet.
        echo         Install: winget install Python.Python.3.12
        pause
        exit /b 1
    )
)
FOR /F "tokens=*" %%i IN ('%PYTHON_CMD% --version') DO SET PYVER=%%i
echo [OK] Found %PYVER% (using: %PYTHON_CMD%)

REM --- Check ffmpeg ---
echo.
echo Checking ffmpeg (required by Whisper)...
ffmpeg -version >nul 2>&1
IF %ERRORLEVEL% NEQ 0 goto ffmpeg_missing
echo [OK] ffmpeg found.
goto ffmpeg_done
:ffmpeg_missing
echo [WARN] ffmpeg not found! Whisper requires ffmpeg.
echo        Install: winget install Gyan.FFmpeg
echo        After installing, restart this terminal and run setup_venv.bat again.
echo        Continuing setup anyway...
:ffmpeg_done

REM --- Check NVIDIA GPU ---
echo.
echo Checking NVIDIA GPU...
nvidia-smi >nul 2>&1
IF %ERRORLEVEL% NEQ 0 (
    echo [WARN] nvidia-smi not found. CUDA may not work.
    echo        PyTorch will fall back to CPU mode.
) ELSE (
    echo [OK] NVIDIA GPU detected:
    nvidia-smi --query-gpu=name,driver_version --format=csv,noheader 2>nul
)

REM --- Create venv ---
echo.
IF EXIST "%VENV_DIR%\Scripts\activate.bat" (
    echo [INFO] Virtual environment already exists at venv\
    echo        Delete venv\ manually if you want to recreate it.
    goto install
)

echo Creating virtual environment with %PYTHON_CMD%...
%PYTHON_CMD% -m venv "%VENV_DIR%"
IF %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Failed to create virtual environment.
    echo         If permission denied, try running as Administrator.
    pause
    exit /b 1
)
echo [OK] Virtual environment created.

:install
echo.
call "%VENV_DIR%\Scripts\activate.bat"
pip install --upgrade pip --quiet

REM --- Step 1: Install PyTorch with CUDA 12.1 FIRST ---
echo.
echo [Step 1/2] Installing PyTorch with CUDA 12.1 support (this may take a while)...
pip install torch torchaudio --index-url https://download.pytorch.org/whl/cu121
IF %ERRORLEVEL% NEQ 0 (
    echo [ERROR] PyTorch installation failed.
    echo         If network error, check your internet connection.
    echo         If permission error, try: pip install --user torch torchaudio --index-url https://download.pytorch.org/whl/cu121
    pause
    exit /b 1
)

REM --- Step 2: Install remaining packages ---
echo.
echo [Step 2/2] Installing Whisper, Flask, pyttsx3...
pip install openai-whisper flask pyttsx3
IF %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Package installation failed.
    pause
    exit /b 1
)

echo.
echo ============================================================
echo  Setup complete! Next steps:
echo    1. python generate_test_audio.py   (create test WAV files)
echo    2. python test_whisper_local.py     (verify GPU + Whisper)
echo    3. start_server.bat                 (launch HTTP server)
echo    4. python test_server.py            (smoke test server)
echo ============================================================
pause
