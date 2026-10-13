package com.riskstrategy.engine;

import lombok.Data;

import java.math.BigDecimal;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/** 衍生特征引擎的统一执行结果。 */
@Data
public class EngineResult {

    /** 实际执行的引擎：SQL / JAVA / PYTHON / OP */
    private String engine;
    private boolean success;
    /** @@ / :param 已替换为字面量后的可执行表达式（回显用） */
    private String executedExpr;
    private Object value;
    private String valueType;
    private long elapsedMs;
    private String error;
    private final List<String> warnings = new ArrayList<>();

    public static EngineResult fail(String engine, String error) {
        EngineResult r = new EngineResult();
        r.setEngine(engine);
        r.setSuccess(false);
        r.setError(error);
        return r;
    }

    /** 按运行时值推断平台数据类型 */
    public static String inferValueType(Object v) {
        if (v == null) return "STRING";
        if (v instanceof Boolean) return "BOOLEAN";
        if (v instanceof BigDecimal || v instanceof Double || v instanceof Float) return "DECIMAL";
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return "INTEGER";
        if (v instanceof Long) return "LONG";
        if (v instanceof Temporal || v instanceof Date) return "DATETIME";
        return "STRING";
    }
}
