@echo off
REM start_server.bat — Launch Whisper server + configure Ollama on laptop
REM Run this on the laptop before starting the robot interaction.
REM
REM Prerequisites:
REM   pip install faster-whisper flask deepface tf-keras Pillow
REM
REM Ollama should be running separately (ollama serve).
REM This script sets OLLAMA_KEEP_ALIVE=-1 so the model stays loaded in GPU memory.

echo ========================================
echo   Sota Interaction - Laptop Server
echo ========================================
echo.

REM Keep Ollama model loaded permanently (no auto-unload after 5min idle)
set OLLAMA_KEEP_ALIVE=-1
echo [Config] OLLAMA_KEEP_ALIVE=-1 (model stays in GPU memory)

REM Pre-load Ollama model so first request is fast
echo [Ollama] Pre-loading model...
curl -s -X POST http://localhost:11434/api/chat -d "{\"model\":\"llama3.2:3b\",\"keep_alive\":-1,\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"stream\":false,\"options\":{\"num_predict\":1}}" > NUL 2>&1
echo [Ollama] Model pre-loaded (keep_alive=-1)
echo.

REM Start Whisper server (faster-whisper)
echo [Whisper] Starting faster-whisper server on port 5050...
echo.
cd /d "%~dp0"
python whisper_server.py
pause
