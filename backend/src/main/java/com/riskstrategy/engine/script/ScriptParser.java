package com.riskstrategy.engine.script;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则脚本解析器：policy.rl 文本 → 预编译 DAG（{@link PolicyModel}）。
 *
 * <p>解析期完成全部「查表」工作：跳转目标解析、块结构闭合、入口定位、
 * 每节点的 varReads / featureRefs 标注——运行期只做遍历与求值
 * （见 docs/trial-canvas-engine.md §4.1 步骤③④）。
 *
 * <p>语法（P0，由 {@link ScriptCompiler} 生成，不面向手写）：
 * <pre>
 *   [nodeId] TYPE 标题
 *   requires a, b
 *   if &lt;expr&gt; then goto L [else goto M]
 *   goto L
 *   if &lt;expr&gt; then
 *     o.name = &lt;rhs&gt;
 *   end
 *   emit o.a, o.b
 *   # 行尾注释（作为语句注记保留，供 trace 展示）
 * </pre>
 *
 * <p>校验：块结构闭合、跳转目标存在、节点非空。不通过一律 {@link ScriptException}
 * 并带行号——脚本是编译产物，出现结构性错误说明上游生成有 bug，必须暴露。
 */
public final class ScriptParser {

    private ScriptParser() {
    }

    private static final Pattern HEAD = Pattern.compile("^\\[([A-Za-z0-9_\\-]+)]\\s+(\\S+)\\s*(.*)$");
    private static final Pattern IDENT = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    /** 变量引用扫描：与 TrialService 解析入参的引用口径一致（短前缀 + 长前缀别名） */
    private static final Pattern REF = Pattern.compile(
            "(?<![A-Za-z0-9_.@])(param|c|feat|f|o|node|view)\\.([A-Za-z_][A-Za-z0-9_]*)");

    public static PolicyModel parse(String script) {
        if (script == null || script.trim().isEmpty()) {
            throw new ScriptException("规则脚本为空");
        }
        List<PolicyModel.Node> nodes = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        PolicyModel.Node cur = null;
        PolicyModel.Stmt openStmt = null;
        // 独立注释行：作为下一条语句/跳转的注记（编译器用它给兜底行等标注语义）
        String pendingNote = null;

        String[] lines = script.split("\\r?\\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int lineNo = i + 1;
            String[] cut = splitComment(lines[i]);
            String code = cut[0].trim();
            String note = cut[1];
            if (code.isEmpty()) {
                pendingNote = note.isEmpty() ? null : note;
                continue;
            }

            Matcher head = HEAD.matcher(code);
            if (head.matches()) {
                if (openStmt != null) {
                    throw new ScriptException("第 " + lineNo + " 行：上一节点的守卫块未闭合（缺 end）");
                }
                pendingNote = null;
                String nodeId = head.group(1);
                if (nodes.stream().anyMatch(n -> n.nodeId.equals(nodeId))) {
                    throw new ScriptException("第 " + lineNo + " 行：节点 " + nodeId + " 重复定义");
                }
                cur = new PolicyModel.Node(nodeId, parseType(head.group(2), lineNo), head.group(3).trim(), lineNo);
                nodes.add(cur);
                continue;
            }

            if (cur == null) {
                throw new ScriptException("第 " + lineNo + " 行：节点块之外出现语句 " + code);
            }

            if ("end".equals(code)) {
                if (openStmt == null) {
                    throw new ScriptException("第 " + lineNo + " 行：多余的 end（没有对应的 if ... then 守卫块）");
                }
                openStmt = null;
                continue;
            }

            if (openStmt != null) {
                openStmt.body.add(parseAssign(code, lineNo));
                continue;
            }

            if (startsWithWord(code, "requires")) {
                for (String t : code.substring(8).split(",")) {
                    String name = stripAnyPrefix(t.trim());
                    if (!name.isEmpty()) cur.requires.add(name);
                }
                continue;
            }
            if (startsWithWord(code, "emit")) {
                for (String t : code.substring(4).split(",")) {
                    String name = stripAnyPrefix(t.trim());
                    if (!name.isEmpty()) cur.emit.add(name);
                }
                continue;
            }
            if (code.startsWith("if ")) {
                int then = indexOfThen(code);
                if (then < 0) throw new ScriptException("第 " + lineNo + " 行：if 语句缺少 then");
                String expr = code.substring(3, then).trim();
                if (expr.isEmpty()) throw new ScriptException("第 " + lineNo + " 行：if 条件为空");
                String rest = code.substring(then + 5).trim();
                String stmtNote = note.isEmpty() ? pendingNote : note;
                pendingNote = null;
                if (rest.isEmpty()) {
                    openStmt = new PolicyModel.Stmt(expr, noteOrNull(stmtNote), lineNo);
                    cur.stmts.add(openStmt);
                } else {
                    cur.jumps.add(parseGoto(expr, rest, stmtNote, lineNo));
                }
                continue;
            }
            if (startsWithWord(code, "goto")) {
                cur.jumps.add(parseGoto(null, code, note.isEmpty() ? pendingNote : note, lineNo));
                pendingNote = null;
                continue;
            }

            // 剩下的只可能是赋值行（顶格赋值 = 无条件赋值组，连续多行合并为一个 Stmt）
            PolicyModel.Assign assign = parseAssign(code, lineNo);
            PolicyModel.Stmt last = cur.stmts.isEmpty() ? null : cur.stmts.get(cur.stmts.size() - 1);
            if (last == null || last.cond != null) {
                last = new PolicyModel.Stmt(null, noteOrNull(pendingNote), lineNo);
                cur.stmts.add(last);
            }
            pendingNote = null;
            last.body.add(assign);
        }

        if (openStmt != null) {
            throw new ScriptException("脚本结尾：守卫块未闭合（缺 end）");
        }
        if (nodes.isEmpty()) {
            throw new ScriptException("规则脚本没有任何节点块");
        }

        // 跳转目标校验（解析期完成，运行期不再查表）
        Set<String> ids = new LinkedHashSet<>();
        nodes.forEach(n -> ids.add(n.nodeId));
        for (PolicyModel.Node n : nodes) {
            for (PolicyModel.Jump j : n.jumps) {
                if (!ids.contains(j.thenTarget)) {
                    throw new ScriptException("第 " + j.line + " 行：节点 " + n.nodeId
                            + " 跳转目标不存在：" + j.thenTarget);
                }
                if (j.elseTarget != null && !ids.contains(j.elseTarget)) {
                    throw new ScriptException("第 " + j.line + " 行：节点 " + n.nodeId
                            + " else 跳转目标不存在：" + j.elseTarget);
                }
            }
        }

        // 入口：第一个 START；没有 START 则取首个节点（告警，如实执行）
        PolicyModel.Node entry = null;
        for (PolicyModel.Node n : nodes) {
            if (n.type == PolicyModel.NodeType.START) { entry = n; break; }
        }
        if (entry == null) {
            entry = nodes.get(0);
            warnings.add("脚本无 START 节点，入口取第一个节点 " + entry.nodeId);
        }

        annotateRefs(nodes);
        PolicyModel model = new PolicyModel(entry.nodeId, nodes);
        model.warnings.addAll(warnings);
        return model;
    }

    // ------------------------------------------------------------------ 语句解析

    private static PolicyModel.Jump parseGoto(String expr, String code, String note, int lineNo) {
        String[] parts = code.trim().split("\\s+");
        if (parts.length < 2 || !"goto".equals(parts[0])) {
            throw new ScriptException("第 " + lineNo + " 行：跳转语句格式错误 " + code);
        }
        String elseTarget = null;
        if (parts.length > 2) {
            if (parts.length == 5 && "else".equals(parts[2]) && "goto".equals(parts[3])) {
                elseTarget = parts[4];
            } else {
                throw new ScriptException("第 " + lineNo + " 行：else 跳转格式错误 " + code);
            }
        }
        return new PolicyModel.Jump(expr, parts[1], elseTarget, noteOrNull(note), lineNo);
    }

    private static PolicyModel.Assign parseAssign(String code, int lineNo) {
        int eq = indexOfAssignEq(code);
        if (eq < 0) throw new ScriptException("第 " + lineNo + " 行：无法识别的语句 " + code);
        String lhs = code.substring(0, eq).trim();
        String rhs = code.substring(eq + 1).trim();
        if (!lhs.startsWith("o.")) {
            throw new ScriptException("第 " + lineNo + " 行：赋值目标必须是 o.<出参名>：" + lhs);
        }
        String name = lhs.substring(2).trim();
        if (!IDENT.matcher(name).matches()) {
            throw new ScriptException("第 " + lineNo + " 行：出参名不合法 " + lhs);
        }
        if (rhs.isEmpty()) {
            throw new ScriptException("第 " + lineNo + " 行：赋值缺少右值 " + code);
        }
        return new PolicyModel.Assign(name, rhs, lineNo);
    }

    // ------------------------------------------------------------------ 元信息标注

    private static void annotateRefs(List<PolicyModel.Node> nodes) {
        for (PolicyModel.Node n : nodes) {
            Set<String> reads = new LinkedHashSet<>();
            Set<String> feats = new LinkedHashSet<>();
            for (PolicyModel.Jump j : n.jumps) scanRefs(j.expr, reads, feats);
            for (PolicyModel.Stmt s : n.stmts) {
                scanRefs(s.cond, reads, feats);
                for (PolicyModel.Assign a : s.body) scanRefs(a.rhs, reads, feats);
            }
            n.varReads.addAll(reads);
            n.featureRefs.addAll(feats);
        }
    }

    private static void scanRefs(String text, Set<String> reads, Set<String> feats) {
        if (text == null || text.isEmpty()) return;
        Matcher m = REF.matcher(text);
        while (m.find()) {
            String ns = m.group(1);
            String name = m.group(2);
            reads.add(ns + "." + name);
            if ("f".equals(ns) || "feat".equals(ns)) feats.add(name);
        }
    }

    // ------------------------------------------------------------------ 工具

    /** 返回 {@code {code, note}}：{@code #} 之后（引号外）为注记 */
    private static String[] splitComment(String line) {
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '#') return new String[]{line.substring(0, i), line.substring(i + 1).trim()};
        }
        return new String[]{line, ""};
    }

    /** 定位 ` then`（引号外、词边界），返回其前导空格的下标；找不到返回 -1 */
    private static int indexOfThen(String code) {
        char quote = 0;
        for (int i = 0; i + 4 <= code.length(); i++) {
            char c = code.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (i >= 1 && code.charAt(i - 1) == ' ' && code.regionMatches(i, "then", 0, 4)) {
                int after = i + 4;
                if (after == code.length() || code.charAt(after) == ' ') return i - 1;
            }
        }
        return -1;
    }

    /** 赋值行的顶层 `=`（引号外；不属于 >= / <= / != / == / <>） */
    private static int indexOfAssignEq(String code) {
        char quote = 0;
        for (int i = 0; i < code.length(); i++) {
            char c = code.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c != '=') continue;
            char prev = i > 0 ? code.charAt(i - 1) : 0;
            char next = i + 1 < code.length() ? code.charAt(i + 1) : 0;
            if (prev == '>' || prev == '<' || prev == '!' || prev == '=') continue;
            if (next == '=') continue;
            return i;
        }
        return -1;
    }

    private static PolicyModel.NodeType parseType(String token, int lineNo) {
        try {
            return PolicyModel.NodeType.valueOf(token);
        } catch (IllegalArgumentException e) {
            throw new ScriptException("第 " + lineNo + " 行：未知节点类型 " + token);
        }
    }

    private static boolean startsWithWord(String code, String word) {
        return code.startsWith(word)
                && (code.length() == word.length() || Character.isWhitespace(code.charAt(word.length())));
    }

    private static String stripAnyPrefix(String t) {
        for (String p : new String[]{"o.", "c.", "param.", "f.", "feat."}) {
            if (t.startsWith(p)) return t.substring(p.length()).trim();
        }
        return t;
    }

    private static String noteOrNull(String note) {
        return note == null || note.isEmpty() ? null : note;
    }
}
