package com.riskstrategy.engine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 衍生特征脚本（Java）的执行上下文：读入参 + 运行信息。 */
public class Ctx {

    private final Map<String, Object> inputs;
    private final Map<String, Object> params;
    private final long startedAtNanos = System.nanoTime();

    public Ctx(Map<String, Object> inputs, Map<String, Object> params) {
        this.inputs = inputs == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }

    /** 按入参名读取（@@ 引用带入的视图入参 + :param 决策入参，键与登记清单一致） */
    public Object get(String name) {
        return inputs.get(name);
    }

    public boolean has(String name) {
        return inputs.get(name) != null;
    }

    public Map<String, Object> inputs() {
        return inputs;
    }

    /** 只按裸名读取决策入参（:param.uid → ctx.param("uid")） */
    public Object param(String name) {
        return params.get(name);
    }

    public Map<String, Object> params() {
        return params;
    }

    public long elapsedMs() {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }
}
