#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""衍生特征 Python 执行服务（FastAPI + 独立工作进程池 + 超时熔断）。

启动（在仓库根目录或任意目录均可）：
    python python-runner/runner.py [--port 8090] [--workers 2]

依赖安装：
    pip install -r python-runner/requirements.txt

协议（后端 risk.python-runner.url 调用，与 Java 侧 PythonRunnerClient 对齐）：
    POST /run   {"script": "...", "inputs": {...}, "timeoutMs": 300}
                → {"success": true, "value": ..., "valueType": "STRING",
                   "elapsedMs": 12, "stdout": "", "error": null}
    GET  /health → {"status": "ok", "pandas": "2.2.3", "numpy": "1.26.4", ...}
    GET  /docs   → FastAPI 自带调试文档（仅本机回环）

脚本约定：定义 def apply(inp: dict, ctx) -> object（或单参 def apply(inp)）。
预置全局：json / math / re / datetime / decimal / pd(pandas) / np(numpy)。

只监听 127.0.0.1，不做鉴权；用户脚本按「数据加工」约定运行，
危险模块 import / 动态求值由后端侧黑名单先行拦截，本服务再加进程与超时边界：
用户脚本在独立工作子进程中执行，超时直接杀进程并自动重启，不影响 HTTP 服务本身。
"""
import hashlib
import inspect
import io
import json
import math
import os
import queue
import re
import subprocess
import sys
import threading
import time
import traceback
import datetime as datetime_module
import decimal as decimal_module
from decimal import Decimal

# pandas / numpy 为可选增强：缺失时服务照常启动，/health 中标注，脚本用到时报清晰错误
try:
    import pandas as pd
    import numpy as np
    PANDAS_VERSION = pd.__version__
    NUMPY_VERSION = np.__version__
except Exception:  # noqa: BLE001 - 依赖缺失不应阻止服务启动
    pd = None
    np = None
    PANDAS_VERSION = None
    NUMPY_VERSION = None


# ---------------------------------------------------------------------------
#  工作进程（--worker）：JSON Lines 协议，一次一行请求 / 一行响应
# ---------------------------------------------------------------------------

class Ctx:
    """传给 apply(inp, ctx) 的执行上下文"""

    def __init__(self, inputs, params):
        self._inputs = dict(inputs)
        self._params = dict(params)
        self._t0 = time.time()

    def get(self, name, default=None):
        return self._inputs.get(name, default)

    def param(self, name, default=None):
        return self._params.get(name, default)

    @property
    def inputs(self):
        return dict(self._inputs)

    @property
    def params(self):
        return dict(self._params)

    def elapsed_ms(self):
        return int((time.time() - self._t0) * 1000)


def _jsonable(v):
    if v is None or isinstance(v, (bool, int, float, str)):
        return v
    if isinstance(v, Decimal):
        return float(v)
    # numpy 标量 / pandas 值 → 原生类型
    if np is not None and isinstance(v, np.generic):
        return _jsonable(v.item())
    if np is not None and isinstance(v, np.ndarray):
        return [_jsonable(x) for x in v.tolist()]
    if pd is not None and isinstance(v, pd.Series):
        return [_jsonable(x) for x in v.tolist()]
    if pd is not None and isinstance(v, pd.DataFrame):
        return _jsonable(v.to_dict(orient="records"))
    if pd is not None and isinstance(v, pd.Timestamp):
        return v.isoformat()
    if isinstance(v, (datetime_module.datetime, datetime_module.date)):
        return v.isoformat()
    if isinstance(v, (list, tuple)):
        return [_jsonable(x) for x in v]
    if isinstance(v, dict):
        return {str(k): _jsonable(x) for k, x in v.items()}
    return str(v)


def _py_type(v):
    if v is None:
        return "STRING"
    if isinstance(v, bool):
        return "BOOLEAN"
    if isinstance(v, int):
        return "INTEGER" if -2147483648 <= v <= 2147483647 else "LONG"
    if isinstance(v, (float, Decimal)):
        return "DECIMAL"
    if np is not None and isinstance(v, np.generic):
        return _py_type(v.item())
    return "STRING"


def _resp(ok, value, error, started, buf):
    return {
        "success": bool(ok),
        "value": _jsonable(value) if ok else None,
        "valueType": _py_type(value) if ok else None,
        "error": error,
        "elapsedMs": int((time.time() - started) * 1000),
        "stdout": buf.getvalue()[:2000],
    }


def _worker_globals():
    glb = {
        "__name__": "feature_script",
        # 常用模块预置，脚本里可直接用；也可自行 import（危险模块由后端黑名单拦截）
        "json": json,
        "math": math,
        "re": re,
        "datetime": datetime_module,
        "decimal": decimal_module,
    }
    if pd is not None:
        glb["pd"] = pd
        glb["pandas"] = pd
    if np is not None:
        glb["np"] = np
        glb["numpy"] = np
    return glb


def _run_job(job, cache):
    script = job.get("script") or ""
    inputs = job.get("inputs") or {}
    started = time.time()
    buf = io.StringIO()
    saved = sys.stdout
    try:
        key = hashlib.sha1(script.encode("utf-8")).hexdigest()
        code = cache.get(key)
        if code is None:
            code = compile(script, "<feature>", "exec")
            if len(cache) >= 64:
                cache.clear()
            cache[key] = code
        glb = _worker_globals()
        sys.stdout = buf
        exec(code, glb)
        fn = glb.get("apply")
        if not callable(fn):
            return _resp(False, None, "脚本中未定义 apply 函数，请按帮助示例编写 def apply(inp, ctx)", started, buf)
        try:
            nargs = len([p for p in inspect.signature(fn).parameters.values()
                         if p.kind in (p.POSITIONAL_ONLY, p.POSITIONAL_OR_KEYWORD) and p.default is p.empty])
        except (TypeError, ValueError):
            nargs = 2
        params = {k[6:]: v for k, v in inputs.items() if isinstance(k, str) and k.startswith("param.")}
        ctx = Ctx(inputs, params)
        value = fn(inputs, ctx) if nargs >= 2 else fn(inputs)
        return _resp(True, value, None, started, buf)
    except SyntaxError as e:
        return _resp(False, None, "语法错误（第 %s 行）：%s" % (e.lineno, e.msg), started, buf)
    except Exception:
        return _resp(False, None, traceback.format_exc(limit=6).strip(), started, buf)
    finally:
        sys.stdout = saved


def worker_main():
    cache = {}
    out = sys.stdout
    while True:
        line = sys.stdin.readline()
        if not line:
            break
        line = line.strip()
        if not line:
            continue
        try:
            job = json.loads(line)
        except Exception:
            continue
        resp = _run_job(job, cache)
        out.write(json.dumps(resp, ensure_ascii=False) + "\n")
        out.flush()


# ---------------------------------------------------------------------------
#  进程池（服务进程侧）：借出工作进程 → 写入请求 → 等待响应，超时杀进程重启
# ---------------------------------------------------------------------------

class WorkerPool:

    def __init__(self, workers):
        self._q = queue.Queue()
        for _ in range(workers):
            self._q.put(self._spawn())

    def _spawn(self):
        env = dict(os.environ)
        env["PYTHONUTF8"] = "1"
        env["PYTHONIOENCODING"] = "utf-8"
        proc = subprocess.Popen(
            [sys.executable, os.path.abspath(__file__), "--worker"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            text=True, encoding="utf-8", bufsize=1, env=env)
        slot = {"proc": proc, "responses": queue.Queue()}
        threading.Thread(target=self._reader, args=(slot,), daemon=True).start()
        return slot

    @staticmethod
    def _reader(slot):
        try:
            for line in slot["proc"].stdout:
                slot["responses"].put(line)
        except Exception:
            pass
        finally:
            slot["responses"].put(None)

    @staticmethod
    def _kill(slot):
        try:
            slot["proc"].kill()
        except Exception:
            pass

    def run(self, job, timeout_ms):
        slot = self._q.get()
        try:
            resp, alive = self._exchange(slot, job, timeout_ms)
            if not alive:
                self._kill(slot)
                slot = self._spawn()
            if resp is None:
                # 写管道失败 / 进程已死：换新进程重试一次
                resp, alive = self._exchange(slot, job, timeout_ms)
                if not alive:
                    self._kill(slot)
                    slot = self._spawn()
            if resp is None:
                return {"success": False, "error": "Python 工作进程异常退出（已自动重启，请重试）", "elapsedMs": 0}
            return resp
        finally:
            self._q.put(slot)

    def _exchange(self, slot, job, timeout_ms):
        try:
            slot["proc"].stdin.write(json.dumps(job, ensure_ascii=False) + "\n")
            slot["proc"].stdin.flush()
        except Exception:
            return None, False
        budget = timeout_ms / 1000.0 + 2.0
        try:
            line = slot["responses"].get(timeout=budget)
        except queue.Empty:
            return {"success": False,
                    "error": "Python 执行超时（超过 %d ms），工作进程已被终止" % timeout_ms,
                    "elapsedMs": int(timeout_ms)}, False
        if line is None:
            return None, False
        try:
            return json.loads(line), True
        except Exception:
            return None, False


# ---------------------------------------------------------------------------
#  HTTP 服务（FastAPI）：仅在工作进程模式下不加载，避免子进程重复初始化
# ---------------------------------------------------------------------------

def _create_app(pool):
    from fastapi import FastAPI
    from pydantic import BaseModel

    class RunRequest(BaseModel):
        script: str = ""
        inputs: dict = {}
        timeoutMs: int = 300

    app = FastAPI(title="衍生特征 Python 执行服务", version="2.0.0")

    @app.post("/run")
    def run(req: RunRequest):
        timeout_ms = max(1, int(req.timeoutMs or 300))
        job = {"script": req.script or "",
               "inputs": req.inputs or {},
               "timeoutMs": timeout_ms}
        try:
            return pool.run(job, timeout_ms)
        except Exception as e:  # noqa: BLE001 - 服务侧兜底，保持协议形状
            return {"success": False, "error": "runner 内部错误：%s" % e, "elapsedMs": 0}

    @app.get("/health")
    def health():
        return {
            "status": "ok",
            "python": sys.version.split()[0],
            "pandas": PANDAS_VERSION,
            "numpy": NUMPY_VERSION,
        }

    return app


def _force_utf8():
    for stream in (sys.stdout, sys.stderr, sys.stdin):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except Exception:
            pass


def main():
    _force_utf8()
    args = sys.argv[1:]
    if "--worker" in args:
        worker_main()
        return

    port, workers = 8090, 2
    for i, a in enumerate(args):
        if a == "--port" and i + 1 < len(args):
            port = int(args[i + 1])
        if a == "--workers" and i + 1 < len(args):
            workers = int(args[i + 1])

    import uvicorn
    pool = WorkerPool(workers)
    app = _create_app(pool)
    print("衍生特征 Python 执行服务已启动: http://127.0.0.1:%d (workers=%d, pandas=%s)"
          % (port, workers, PANDAS_VERSION or "不可用"), flush=True)
    uvicorn.run(app, host="127.0.0.1", port=port, log_level="warning")


if __name__ == "__main__":
    main()
