@echo off
REM Run the HRI data analysis pipeline
REM Step 1: Parse logs -> behavioral_data.csv
REM Step 2: Run analysis -> figures/ + analysis_results.txt

cd /d "%~dp0"

echo ================================
echo  HRI Reciprocity Study Analysis
echo ================================

REM Check for virtual environment
if not exist "venv" (
    echo Creating virtual environment...
    python -m venv venv
)

echo Activating virtual environment...
call venv\Scripts\activate.bat

echo Installing dependencies...
pip install -q -r requirements.txt

echo.
echo [Step 1/2] Parsing conversation logs...
python parse_logs.py

echo.
echo [Step 2/2] Running statistical analysis...
python analyze_study.py

echo.
echo ================================
echo  Done! Check:
echo    - behavioral_data.csv
echo    - analysis_results.txt
echo    - summary_table.csv
echo    - figures\  (all plots)
echo ================================
pause
