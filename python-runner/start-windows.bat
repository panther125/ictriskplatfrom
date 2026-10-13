@echo off
REM ============================================================
REM  衍生特征 Python 执行服务 - Windows 启动脚本（带崩溃自动重启）
REM  用法：双击运行，或放入「启动」文件夹实现开机自启：
REM        Win+R 输入 shell:startup，把本脚本的快捷方式放进去
REM  开机自启（无人值守）推荐用计划任务，见文件底部注释
REM ============================================================
setlocal EnableDelayedExpansion

REM ---- 可按服务器实际环境修改 ----
set PORT=8090
set WORKERS=2
REM 日志目录（默认脚本同级 logs）
set LOG_DIR=%~dp0logs

cd /d "%~dp0"
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

REM 检查 Python
where python >nul 2>nul
if errorlevel 1 (
    echo [ERROR] 未找到 python，请先安装 Python 3.10+ 并加入 PATH
    pause
    exit /b 1
)

REM 首次运行或依赖缺失时自动安装
python -c "import fastapi, uvicorn" >nul 2>nul
if errorlevel 1 (
    echo [INFO] 安装依赖...
    python -m pip install -r "%~dp0requirements.txt"
    if errorlevel 1 (
        echo [ERROR] 依赖安装失败，请检查网络或 pip 源
        pause
        exit /b 1
    )
)

echo [INFO] 启动衍生特征 Python 执行服务：http://127.0.0.1:%PORT% (workers=%WORKERS%)
echo [INFO] 日志目录：%LOG_DIR%

:RESTART
set TS=%date:~0,4%%date:~5,2%%date:~8,2%
python "%~dp0runner.py" --port %PORT% --workers %WORKERS% >> "%LOG_DIR%\runner-%TS%.log" 2>&1
echo [%date% %time%] 服务退出（exit=%ERRORLEVEL%），5 秒后自动重启... >> "%LOG_DIR%\runner-%TS%.log"
echo [WARN] 服务已退出，5 秒后自动重启（Ctrl+C 终止本窗口即停止）
timeout /t 5 /nobreak >nul
goto RESTART

REM ============================================================
REM  无人值守开机自启（管理员 CMD 执行一次即可，无需登录会话）：
REM
REM  schtasks /create /tn "RiskPythonRunner" /sc onstart /ru SYSTEM ^
REM      /tr "\"%~dp0start-windows.bat\"" /rl highest
REM
REM  删除任务：schtasks /delete /tn "RiskPythonRunner" /f
REM  手动触发：schtasks /run /tn "RiskPythonRunner"
REM ============================================================
