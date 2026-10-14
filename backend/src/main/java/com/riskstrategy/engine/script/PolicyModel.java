package com.riskstrategy.engine.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 规则脚本的解析产物：预编译 DAG（Flow / Node / Edge）。
 *
 * <p>加载期一次构建、运行期只读共享——跳转目标已在解析期解析为节点引用，
 * 运行期只做遍历与求值，不做字符串比较、不查图。每次执行新建 {@link Session} 承载私有状态，
 * 因此模型本身可以无锁并发复用（见 docs/trial-canvas-engine.md §四）。
 */
public final class PolicyModel {

    public enum NodeType { START, BRANCH, CASE, CROSS, NORMAL, END, STEP }

    /** 赋值语句：目标出参（已归一，不带 o. 前缀）+ 值表达式原文 */
    public static final class Assign {
        public final String target;
        public final String rhs;
        public final int line;

        public Assign(String target, String rhs, int line) {
            this.target = target;
            this.rhs = rhs;
            this.line = line;
        }
    }

    /** 节点内语句：{@code cond == null} 为无条件赋值组；否则是守卫块（条件命中才执行 body，首个命中即止） */
    public static final class Stmt {
        public final String cond;
        public final String note;
        public final List<Assign> body = new ArrayList<>();
        public final int line;

        public Stmt(String cond, String note, int line) {
            this.cond = cond;
            this.note = note;
            this.line = line;
        }
    }

    /** 跳转语句：{@code expr == null} 为无条件跳转；{@code elseTarget} 非空时条件不成立直接跳它 */
    public static final class Jump {
        public final String expr;
        public final String thenTarget;
        public final String elseTarget;
        /** 行尾注记（端口 / 条件标签），供 trace 展示 */
        public final String note;
        public final int line;

        public Jump(String expr, String thenTarget, String elseTarget, String note, int line) {
            this.expr = expr;
            this.thenTarget = thenTarget;
            this.elseTarget = elseTarget;
            this.note = note;
            this.line = line;
        }
    }

    /** 节点块：脚本里的一个 [nodeId] 段落 */
    public static final class Node {
        public final String nodeId;
        public final NodeType type;
        public final String title;
        public final int line;
        /** START 入参契约（requires 行） */
        public final List<String> requires = new ArrayList<>();
        public final List<Stmt> stmts = new ArrayList<>();
        public final List<Jump> jumps = new ArrayList<>();
        /** END 输出契约（emit 行，已归一为出参名） */
        public final List<String> emit = new ArrayList<>();
        /** 解析期扫描出的引用（如 f.dreiver_feature / c.testInput），供逐节点明细展示 */
        public final List<String> varReads = new ArrayList<>();
        public final List<String> featureRefs = new ArrayList<>();

        public Node(String nodeId, NodeType type, String title, int line) {
            this.nodeId = nodeId;
            this.type = type;
            this.title = title;
            this.line = line;
        }
    }

    public final String entryId;
    public final List<Node> nodes;
    public final Map<String, Node> byId;
    /** 解析期告警（不阻断执行） */
    public final List<String> warnings = new ArrayList<>();

    public PolicyModel(String entryId, List<Node> nodes) {
        this.entryId = entryId;
        this.nodes = nodes;
        this.byId = new LinkedHashMap<>();
        for (Node n : nodes) byId.put(n.nodeId, n);
    }

    public Node node(String nodeId) {
        return nodeId == null ? null : byId.get(nodeId);
    }

    /** 第一个 END 节点（输出契约的 emit 列表挂在它上面） */
    public Node endNode() {
        for (Node n : nodes) if (n.type == NodeType.END) return n;
        return null;
    }
}
