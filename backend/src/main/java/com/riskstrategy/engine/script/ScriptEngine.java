package com.riskstrategy.engine.script;

import com.riskstrategy.engine.ConditionEvaluator;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 脚本运行器：在预编译 DAG（{@link PolicyModel}）上遍历执行，执行态全部落在私有 {@link Session}。
 *
 * <p>运行期职责（见 docs/trial-canvas-engine.md §4.3）：
 * 从 entry 起执行节点语句块 → jump 推进执行点 → END 注册输出槽；
 * 条件求值统一复用 {@link ConditionEvaluator}（三态：TRUE / FALSE / UNKNOWN，
 * UNKNOWN 一律按不命中处理并挂 ⚠，绝不当成成立）。
 *
 * <p>防护：步数上限 = 节点数 × 2 + 16（编译期环检测之外的双保险）。
 */
public final class ScriptEngine {

    private ScriptEngine() {
    }

    /** 变量读取快照（nodeDetails / trace 用）：visit 时解析，取不到标 missing */
    public static final class VarRead {
        public final String name;
        public final Object value;
        public final boolean missing;

        VarRead(String name, Object value) {
            this.name = name;
            this.value = value;
            this.missing = value == null;
        }
    }

    /** 单节点执行明细 */
    public static final class NodeRun {
        public final String nodeId;
        public final String title;
        public final String nodeType;
        public String result;
        public String resultText;
        public String detail;
        public String expr;
        public final List<VarRead> varsRead = new ArrayList<>();

        NodeRun(PolicyModel.Node node) {
            this.nodeId = node.nodeId;
            this.title = node.title;
            this.nodeType = node.type.name();
        }
    }

    /** 一次执行的完整产物 */
    public static final class Execution {
        /** 访问顺序的节点执行明细（未走到的节点由调用方按 SKIPPED 补齐） */
        public final Map<String, NodeRun> runs = new LinkedHashMap<>();
        public final List<String> warnings = new ArrayList<>();
        /** 最后一个实际产生赋值的节点（hitRule 口径） */
        public String assignNodeId;
        /** 路径上首个 BRANCH（无赋值节点时的 hitRule 回退） */
        public String firstBranchNodeId;
        /** 是否走到 END */
        public boolean finished;
        public int steps;
    }

    public static Execution run(PolicyModel model, Session session) {
        Execution ex = new Execution();
        int maxSteps = model.nodes.size() * 2 + 16;
        PolicyModel.Node cur = model.node(model.entryId);
        while (cur != null) {
            if (++ex.steps > maxSteps) {
                session.warn("执行步数超过上限 " + maxSteps + "，已截断（疑似画布环）");
                break;
            }
            NodeRun run = new NodeRun(cur);
            for (String ref : cur.varReads) run.varsRead.add(new VarRead(ref, resolveVar(ref, session.vars)));

            String next = null;
            switch (cur.type) {
                case START -> {
                    run.result = "INFO";
                    int emits = model.endNode() == null ? 0 : model.endNode().emit.size();
                    run.resultText = "入参 " + cur.requires.size() + " 个 · 出参 " + emits + " 个";
                    next = firstTarget(cur, session);
                }
                case BRANCH -> {
                    if (ex.firstBranchNodeId == null) ex.firstBranchNodeId = cur.nodeId;
                    next = runBranch(cur, run, session);
                }
                case CASE, NORMAL, CROSS -> next = runStatements(cur, run, session, ex);
                case END -> {
                    run.result = "OUTPUT";
                    for (String name : cur.emit) session.registerOutput(name);
                    run.resultText = "组装输出 " + cur.emit.size() + " 个出参";
                    ex.finished = true;
                }
                default -> {
                    run.result = "INFO";
                    run.resultText = "直通";
                    next = firstTarget(cur, session);
                }
            }
            ex.runs.put(cur.nodeId, run);
            cur = next == null ? null : model.node(next);
        }
        ex.warnings.addAll(session.warnings);
        return ex;
    }

    // ------------------------------------------------------------------ 各节点类型

    /** 条件跳转：逐条求值，首个 TRUE 跳其目标；无条件跳转（编译产物的 else 端口）直接命中 */
    private static String runBranch(PolicyModel.Node node, NodeRun run, Session session) {
        List<String> trail = new ArrayList<>();
        boolean anyUnknown = false;
        for (PolicyModel.Jump j : node.jumps) {
            String label = j.note != null ? j.note : (j.expr != null ? j.expr : "无条件跳转");
            if (j.expr == null) {
                trail.add(label + " → 无条件跳转 " + j.thenTarget);
                run.result = "HIT";
                run.resultText = "命中·" + label;
                run.detail = joinTrail(trail);
                return j.thenTarget;
            }
            ConditionEvaluator.Result r = ConditionEvaluator.evaluate(j.expr, session.vars);
            if (r == ConditionEvaluator.Result.TRUE) {
                trail.add(label + " → true，跳转 " + j.thenTarget);
                run.expr = j.expr;
                run.result = "HIT";
                run.resultText = "命中·" + label;
                run.detail = joinTrail(trail);
                return j.thenTarget;
            }
            if (r == ConditionEvaluator.Result.FALSE) {
                trail.add(label + " → false");
            } else {
                trail.add(label + " → 无法求值");
                session.warn("节点 " + node.nodeId + "：条件「" + j.expr + "」无法求值（变量缺失或语法不支持），按不命中处理");
                anyUnknown = true;
            }
        }
        run.result = anyUnknown ? "EVAL_FAIL" : "COND_FALSE";
        run.resultText = anyUnknown ? "条件无法求值" : "全部条件未命中";
        if (run.expr == null && !node.jumps.isEmpty()) run.expr = node.jumps.get(0).expr;
        run.detail = joinTrail(trail);
        session.warn("节点 " + node.nodeId + " 全部条件未命中且无兜底去向，执行终止");
        return null;
    }

    /** 守卫赋值块（CASE / NORMAL / CROSS 同构）：首个命中执行其赋值，不命中则记录轨迹 */
    private static String runStatements(PolicyModel.Node node, NodeRun run, Session session, Execution ex) {
        List<String> trail = new ArrayList<>();
        boolean anyUnknown = false;
        for (int i = 0; i < node.stmts.size(); i++) {
            PolicyModel.Stmt stmt = node.stmts.get(i);
            String label = stmt.note != null ? stmt.note : "规则 " + (i + 1);
            if (stmt.cond != null) {
                ConditionEvaluator.Result r = ConditionEvaluator.evaluate(stmt.cond, session.vars);
                if (r == ConditionEvaluator.Result.FALSE) {
                    trail.add(label + " → false");
                    continue;
                }
                if (r == ConditionEvaluator.Result.UNKNOWN) {
                    trail.add(label + " → 无法求值");
                    session.warn("节点 " + node.nodeId + "：条件「" + stmt.cond + "」无法求值（变量缺失或语法不支持），按不命中处理");
                    anyUnknown = true;
                    continue;
                }
                run.expr = stmt.cond;
            }
            // 命中：执行赋值（不可求值的右值跳过 + ⚠）
            StringBuilder hit = new StringBuilder(label).append(" → true");
            List<String> assigns = new ArrayList<>();
            for (PolicyModel.Assign a : stmt.body) {
                Object v = evalRhs(a.rhs, node.nodeId, a.line, session);
                if (v == UNSUPPORTED) continue;
                session.assign(a.target, v);
                assigns.add("o." + a.target + " = " + display(v));
            }
            if (!assigns.isEmpty()) {
                ex.assignNodeId = node.nodeId;
                hit.append(" · ").append(String.join(" · ", assigns));
            }
            trail.add(hit.toString());
            run.result = "HIT";
            run.resultText = "命中·" + label;
            run.detail = joinTrail(trail);
            return firstTarget(node, session);
        }
        run.result = anyUnknown ? "EVAL_FAIL" : "COND_FALSE";
        run.resultText = anyUnknown ? "规则无法求值" : "规则未命中";
        if (run.expr == null && !node.stmts.isEmpty()) run.expr = node.stmts.get(0).cond;
        run.detail = joinTrail(trail);
        return firstTarget(node, session);
    }

    private static String firstTarget(PolicyModel.Node node, Session session) {
        if (node.jumps.isEmpty()) {
            session.warn("节点 " + node.nodeId + " 无跳转语句，执行终止");
            return null;
        }
        if (node.jumps.size() > 1) {
            session.warn("节点 " + node.nodeId + " 有多条跳转，取第一条");
        }
        return node.jumps.get(0).thenTarget;
    }

    // ------------------------------------------------------------------ 右值求值

    private static final Object UNSUPPORTED = new Object();

    /** 赋值右值（P0）：字面量 | 变量引用（精确 → 末段名 → 忽略大小写；裸标识符当字符串常量） */
    private static Object evalRhs(String rhs, String nodeId, int line, Session session) {
        String t = rhs.trim();
        if ((t.startsWith("'") && t.endsWith("'") && t.length() >= 2)
                || (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2)) {
            return t.substring(1, t.length() - 1);
        }
        if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
        if ("null".equalsIgnoreCase(t)) return null;
        if (t.matches("-?\\d+(\\.\\d+)?")) return new BigDecimal(t);
        if (t.matches("[A-Za-z_][A-Za-z0-9_.]*")) {
            Object v = lookup(t, session.vars);
            if (v != UNRESOLVED) return v;
            if (t.indexOf('.') < 0) return t;
            session.warn("节点 " + nodeId + " 第 " + line + " 行：变量 " + t + " 未定义，赋值已跳过");
            return UNSUPPORTED;
        }
        session.warn("节点 " + nodeId + " 第 " + line + " 行：不支持的右值「"
                + (t.length() > 32 ? t.substring(0, 32) + "…" : t) + "」，赋值已跳过（P0 仅支持字面量 / 变量引用）");
        return UNSUPPORTED;
    }

    private static final Object UNRESOLVED = new Object();

    /** 变量查找：精确 → 末段名 → 忽略大小写（与 ConditionEvaluator 同口径） */
    private static Object lookup(String name, Map<String, Object> vars) {
        if (vars.containsKey(name)) return vars.get(name);
        int dot = name.lastIndexOf('.');
        if (dot > 0) {
            String tail = name.substring(dot + 1);
            if (vars.containsKey(tail)) return vars.get(tail);
        }
        for (Map.Entry<String, Object> e : vars.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) return e.getValue();
        }
        return UNRESOLVED;
    }

    private static Object resolveVar(String ref, Map<String, Object> vars) {
        Object v = lookup(ref, vars);
        return v == UNRESOLVED ? null : v;
    }

    // ------------------------------------------------------------------ 工具

    private static String joinTrail(List<String> trail) {
        return trail.isEmpty() ? null : String.join("；", trail);
    }

    private static String display(Object v) {
        if (v == null) return "null";
        if (v instanceof String s) return "'" + s + "'";
        if (v instanceof BigDecimal b) return b.toPlainString();
        return String.valueOf(v);
    }
}
