# 衍生特征 Python 执行服务
# 构建上下文为仓库根目录： docker build -f docker/python-runner.Dockerfile .
FROM python:3.11-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 \
    PYTHONUTF8=1 \
    PY_RUNNER_HOST=0.0.0.0 \
    PY_RUNNER_PORT=8090 \
    TZ=Asia/Shanghai
COPY python-runner/requirements.txt ./
RUN pip install --no-cache-dir -i https://pypi.tuna.tsinghua.edu.cn/simple -r requirements.txt
COPY python-runner/runner.py ./
EXPOSE 8090
CMD ["python", "runner.py"]
