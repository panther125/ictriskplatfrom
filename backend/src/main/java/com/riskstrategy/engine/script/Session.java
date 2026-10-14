package com.riskstrategy.engine.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次执行的私有状态：每次执行新建，绝不跨请求共享——
 * 这是「无锁并发」的关键（PolicyModel 只读共享，执行态全在 Session；见 docs/trial-canvas-engine.md §4.3）。
 */
public final class Session {

    /** 变量表：c./f./o. 与 param./feat./view. 别名同表混排（与 ConditionEvaluator 的查找口径一致） */
    public final Map<String, Object> vars;
    /** 出参槽：先按契约注册空槽（值 null），节点赋值写入 / 覆盖 */
    public final LinkedHashMap<String, Object> outputs = new LinkedHashMap<>();
    /** 本次执行产生的告警（表达式未求值 / 变量缺失等） */
    public final List<String> warnings = new ArrayList<>();
    /** 全局截止时间（nanoTime 基准）；P1 仅携带，超时强杀见文档 §七 */
    public final long deadlineNanos;

    public Session(Map<String, Object> vars, long deadlineNanos) {
        this.vars = vars;
        this.deadlineNanos = deadlineNanos;
    }

    public void registerOutput(String name) {
        outputs.putIfAbsent(name, null);
    }

    public void assign(String name, Object value) {
        outputs.put(name, value);
        vars.put("o." + name, value);
    }

    public Object output(String name) {
        return outputs.get(name);
    }

    public void warn(String message) {
        if (message != null && !message.isEmpty() && !warnings.contains(message)) {
            warnings.add(message);
        }
    }
}
