package com.riskstrategy.engine;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** 引擎执行超时守卫：脚本统一跑在守护线程池里，超时中断。 */
final class ExecGuard {

    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "derived-engine");
        t.setDaemon(true);
        return t;
    });

    private ExecGuard() {}

    static <T> T call(long timeoutMs, Callable<T> task) throws Exception {
        Future<T> future = POOL.submit(task);
        try {
            return future.get(Math.max(timeoutMs, 1), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw e;
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception ex) throw ex;
            if (cause instanceof Error err) throw err;
            throw e;
        }
    }
}
