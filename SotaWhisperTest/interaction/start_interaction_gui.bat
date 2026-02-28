@echo off
REM start_interaction_gui.bat — Launch Sota Interaction Monitor GUI
REM Double-click this file to start the monitoring dashboard.
REM No venv needed — uses only Python built-in modules (tkinter, urllib).

cd /d "%~dp0"
echo Starting Sota Interaction Monitor...
echo.
python interaction_gui.py %*
pause
