#!/usr/bin/env bash
# ============================================================
#  衍生特征 Python 执行服务 - Linux 启动脚本（带崩溃自动重启）
#  用法：
#    chmod +x start-linux.sh
#    ./start-linux.sh              # 前台运行（调试）
#    nohup ./start-linux.sh &      # 后台运行
#  开机自启推荐使用 systemd，见 risk-python-runner.service
# ============================================================
set -u

# ---- 可按服务器实际环境修改 ----
PORT=${PORT:-8090}
WORKERS=${WORKERS:-2}

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
LOG_DIR="$SCRIPT_DIR/logs"
mkdir -p "$LOG_DIR"
cd "$SCRIPT_DIR"

# 优先使用 python3
PY=$(command -v python3 || command -v python || true)
if [ -z "$PY" ]; then
    echo "[ERROR] 未找到 python3，请先安装 Python 3.10+"
    exit 1
fi

# 存在 venv 则优先使用
if [ -f "$SCRIPT_DIR/venv/bin/python" ]; then
    PY="$SCRIPT_DIR/venv/bin/python"
fi

# 首次运行或依赖缺失时自动安装
if ! "$PY" -c "import fastapi, uvicorn" >/dev/null 2>&1; then
    echo "[INFO] 安装依赖..."
    "$PY" -m pip install -r "$SCRIPT_DIR/requirements.txt" || {
        echo "[ERROR] 依赖安装失败，请检查网络或 pip 源"
        exit 1
    }
fi

echo "[INFO] 启动衍生特征 Python 执行服务: http://127.0.0.1:$PORT (workers=$WORKERS)"
echo "[INFO] 日志目录: $LOG_DIR"

while true; do
    TS=$(date +%Y%m%d)
    "$PY" "$SCRIPT_DIR/runner.py" --port "$PORT" --workers "$WORKERS" \
        >> "$LOG_DIR/runner-$TS.log" 2>&1
    echo "[$(date '+%F %T')] 服务退出（exit=$?），5 秒后自动重启..." >> "$LOG_DIR/runner-$TS.log"
    echo "[WARN] 服务已退出，5 秒后自动重启（Ctrl+C 终止）"
    sleep 5
done
