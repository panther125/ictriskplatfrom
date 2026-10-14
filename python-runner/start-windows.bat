@echo off
REM ============================================================
REM  Derived-Feature Python Runner - Windows launcher
REM  (auto-install deps, auto-restart on crash)
REM
REM  Usage: double-click, or put a shortcut into Startup folder:
REM         Win+R -> shell:startup
REM  For unattended boot start, register a Scheduled Task once
REM  in an elevated CMD (see bottom of this file).
REM ============================================================
setlocal EnableDelayedExpansion

REM ---- Adjust for your server if needed ----
set PORT=8090
set WORKERS=2
set LOG_DIR=%~dp0logs

cd /d "%~dp0"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM Check Python
where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] python not found. Please install Python 3.10+ and add it to PATH
    pause
    exit /b 1
)

REM Install dependencies on first run or when missing
python -c "import fastapi, uvicorn" >nul 2>nul
if errorlevel 1 (
    echo [INFO] Installing dependencies...
    python -m pip install -r "%~dp0requirements.txt"
    if errorlevel 1 (
        echo [ERROR] pip install failed. Check network or pip mirror
        pause
        exit /b 1
    )
)

echo [INFO] Starting Python runner: http://127.0.0.1:%PORT% (workers=%WORKERS%)
echo [INFO] Log directory: %LOG_DIR%

:RESTART
set TS=%date:~0,4%%date:~5,2%%date:~8,2%
python "%~dp0runner.py" --port %PORT% --workers %WORKERS% >> "%LOG_DIR%\runner-%TS%.log" 2>&1
echo [%date% %time%] runner exited (code=%ERRORLEVEL%), restarting in 5s... >> "%LOG_DIR%\runner-%TS%.log"
echo [WARN] runner exited, restarting in 5s... (Ctrl+C to stop)
timeout /t 5 /nobreak >nul
goto RESTART

REM ============================================================
REM  Unattended auto-start at boot (run once in elevated CMD):
REM
REM  schtasks /create /tn "RiskPythonRunner" /sc onstart /ru SYSTEM ^
REM      /tr "\"%~dp0start-windows.bat\"" /rl highest
REM
REM  Remove : schtasks /delete /tn "RiskPythonRunner" /f
REM  Run now: schtasks /run    /tn "RiskPythonRunner"
REM ============================================================
