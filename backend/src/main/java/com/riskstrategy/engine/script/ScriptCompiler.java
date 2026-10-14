package com.riskstrategy.engine.script;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskstrategy.entity.DecisionBranch;
import com.riskstrategy.entity.Rule;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 画布编译器：草稿画布（decision + rule + decision_branch）→ 规则脚本（policy.rl）。
 *
 * <p>纯函数 + 确定性：同一画布（元素相同）→ 字节级相同的脚本主体 →
 * 相同的 checksum（脚本主体 sha256 前 12 位）。节段顺序按 rule.id、连线按 branch.id，
 * 与内存遍历顺序无关（见 docs/trial-canvas-engine.md §3.1）。
 *
 * <p>六类节点映射：START → requires + goto；BRANCH → 逐条件端口跳转 + else 跳转；
 * CASE / NORMAL → 守卫赋值块（首个命中即止）；CROSS → 行 × 列判定守卫块链；
 * END → emit 出参契约；未知类型 → STEP 直通 + ⚠。
 *
 * <p>编译期归一化（尾空格 / 前缀误写 / 未声明出参）与校验（端口未连线 / 无出边 /
 * 跳转目标缺失 / 环）见文档 §3.5 —— 归一化静默修正 + ⚠，结构错误直接编译失败。
 */
public final class ScriptCompiler {

    private ScriptCompiler() {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ASSIGN =
            Pattern.compile("^(?:o\\.)?([A-Za-z_][A-Za-z0-9_]*)\\s*=\\s*(.+)$");
    private static final Pattern NUMERIC = Pattern.compile("-?\\d+(\\.\\d+)?");
    private static final Pattern FEAT_REF =
            Pattern.compile("(?<![A-Za-z0-9_.@])(?:feat|f)\\.([A-Za-z_][A-Za-z0-9_]*)");

    public static final class CompileResult {
        public final String script;
        public final String checksum;
        public final PolicyModel model;
        public final List<String> warnings;

        CompileResult(String script, String checksum, PolicyModel model, List<String> warnings) {
            this.script = script;
            this.checksum = checksum;
            this.model = model;
            this.warnings = warnings;
        }
    }

    public static CompileResult compile(String policyCode, String sourceLabel,
                                        List<Rule> rules, List<DecisionBranch> branches) {
        List<Rule> sorted = new ArrayList<>(rules == null ? List.of() : rules);
        sorted.sort(Comparator.comparing(r -> r.getId() == null ? Long.MAX_VALUE : r.getId()));
        if (sorted.isEmpty()) throw new ScriptException("画布为空：没有任何节点，无法编译");

        Map<String, Rule> byKey = new LinkedHashMap<>();
        for (Rule r : sorted) {
            String key = str(r.getNodeKey());
            if (key.isEmpty()) throw new ScriptException("画布存在缺少 nodeKey 的节点（rule.id=" + r.getId() + "）");
            if (byKey.putIfAbsent(key, r) != null) throw new ScriptException("节点 key 重复：" + key);
        }

        List<DecisionBranch> edges = new ArrayList<>(branches == null ? List.of() : branches);
        edges.sort(Comparator.comparing(b -> b.getId() == null ? Long.MAX_VALUE : b.getId()));
        Map<String, List<DecisionBranch>> out = new LinkedHashMap<>();
        for (DecisionBranch e : edges) {
            out.computeIfAbsent(str(e.getFromNode()), k -> new ArrayList<>()).add(e);
        }

        List<String> warnings = new ArrayList<>();
        Set<String> declaredOutputs = new LinkedHashSet<>();
        List<String> requires = new ArrayList<>();
        Rule startRule = null;
        for (Rule r : sorted) {
            if (!"START".equals(typeOf(r))) continue;
            if (startRule == null) {
                startRule = r;
            } else {
                warnings.add("存在多个 START 节点，入口取 " + startRule.getNodeKey());
            }
        }
        if (startRule != null) {
            JsonNode cfg = configOf(startRule);
            for (JsonNode in : array(cfg, "inputs")) {
                String name = normalizeName(text(in, "name"), "入参名", warnings);
                if (!name.isEmpty() && !requires.contains(name)) requires.add(name);
            }
            for (JsonNode o : array(cfg, "outputs")) {
                String name = normalizeName(text(o, "name"), "出参名", warnings);
                if (name.isEmpty()) continue;
                if (!declaredOutputs.add(name)) {
                    throw new ScriptException("START 出参重名（归一后）：" + name);
                }
            }
        }

        Set<String> warnedUndeclared = new LinkedHashSet<>();
        List<String> sections = new ArrayList<>();
        for (Rule r : sorted) {
            String key = r.getNodeKey();
            String type = typeOf(r);
            StringBuilder sb = new StringBuilder();
            sb.append('[').append(key).append("] ").append(type);
            String title = str(r.getTitle()).trim();
            if (!title.isEmpty()) sb.append(' ').append(title);
            sb.append('\n');

            switch (type) {
                case "START" -> {
                    if (!requires.isEmpty()) sb.append("requires ").append(String.join(", ", requires)).append('\n');
                    sb.append("goto ").append(singleTarget(key, type, out.get(key), byKey, warnings)).append('\n');
                }
                case "BRANCH" -> appendBranch(sb, r, out.get(key), byKey, warnings);
                case "CASE" -> {
                    appendRows(sb, r, array(configOf(r), "rows"), true, declaredOutputs, warnedUndeclared, warnings);
                    sb.append("goto ").append(singleTarget(key, type, out.get(key), byKey, warnings)).append('\n');
                }
                case "NORMAL" -> {
                    appendRows(sb, r, array(configOf(r), "rules"), false, declaredOutputs, warnedUndeclared, warnings);
                    sb.append("goto ").append(singleTarget(key, type, out.get(key), byKey, warnings)).append('\n');
                }
                case "CROSS" -> {
                    appendCross(sb, r, declaredOutputs, warnedUndeclared, warnings);
                    sb.append("goto ").append(singleTarget(key, type, out.get(key), byKey, warnings)).append('\n');
                }
                case "END" -> {
                    if (out.containsKey(key) && !out.get(key).isEmpty()) {
                        warnings.add("END 节点 " + key + " 存在出边，已忽略");
                    }
                    if (!declaredOutputs.isEmpty()) {
                        sb.append("emit ").append(declaredOutputs.stream()
                                .map(n -> "o." + n).reduce((a, b) -> a + ", " + b).orElse("")).append('\n');
                    }
                }
                default -> {
                    warnings.add("节点 " + key + " 类型 " + type + " 暂不支持编译，按直通（STEP）处理");
                    sb.append("goto ").append(singleTarget(key, type, out.get(key), byKey, warnings)).append('\n');
                }
            }
            sections.add(trimTrailingNewline(sb));
        }

        String body = String.join("\n\n", sections);
        String checksum = sha256_12(body);

        List<String> header = new ArrayList<>();
        header.add("# policy: " + (policyCode == null || policyCode.isEmpty() ? "-" : policyCode)
                + "   source: " + (sourceLabel == null || sourceLabel.isEmpty() ? "-" : sourceLabel)
                + "   checksum: " + checksum);
        if (!requires.isEmpty()) header.add("# params: " + String.join(", ", requires));
        List<String> feats = new ArrayList<>();
        Matcher fm = FEAT_REF.matcher(body);
        while (fm.find()) if (!feats.contains(fm.group(1))) feats.add(fm.group(1));
        if (!feats.isEmpty()) header.add("# features: " + String.join(", ", feats));

        String script = String.join("\n", header) + "\n\n" + body + "\n";

        PolicyModel model = ScriptParser.parse(script);
        checkCycles(model);
        checkReachability(model, warnings);
        warnings.addAll(model.warnings);
        return new CompileResult(script, checksum, model, warnings);
    }

    // ------------------------------------------------------------------ 节点节段

    private static void appendBranch(StringBuilder sb, Rule r, List<DecisionBranch> out,
                                     Map<String, Rule> byKey, List<String> warnings) {
        String key = r.getNodeKey();
        JsonNode cfg = configOf(r);
        JsonNode conditions = cfg.path("conditions");
        if (!conditions.isArray() || conditions.isEmpty()) {
            throw new ScriptException("分支节点 " + key + " 未配置任何条件");
        }
        for (int i = 0; i < conditions.size(); i++) {
            JsonNode c = conditions.get(i);
            String expr = text(c, "expr").trim();
            String label = text(c, "label").trim();
            if (label.isEmpty()) label = "条件 " + (i + 1);
            if (expr.isEmpty()) {
                throw new ScriptException("分支节点 " + key + " 的「" + label + "」条件表达式为空");
            }
            String port = "c" + i;
            sb.append("if ").append(expr).append(" then goto ")
                    .append(portTarget(key, port, out, byKey, warnings))
                    .append("  # ").append(sanitize(label)).append("（端口 ").append(port).append("）\n");
        }
        String elseLabel = text(cfg, "elseLabel").trim();
        if (elseLabel.isEmpty()) elseLabel = "else";
        sb.append("goto ").append(portTarget(key, "else", out, byKey, warnings))
                .append("  # ").append(sanitize(elseLabel)).append("（端口 else）\n");
    }

    private static void appendRows(StringBuilder sb, Rule r, List<JsonNode> rows, boolean withNote,
                                   Set<String> declaredOutputs, Set<String> warnedUndeclared,
                                   List<String> warnings) {
        String key = r.getNodeKey();
        if (rows.isEmpty()) {
            warnings.add("节点 " + key + " 未配置规则行，编译为空操作");
            return;
        }
        for (int i = 0; i < rows.size(); i++) {
            JsonNode row = rows.get(i);
            String label = "规则 " + (i + 1);
            if (withNote) {
                String note = text(row, "note").trim();
                if (!note.isEmpty()) label += " · " + sanitize(note);
            }
            appendBlock(sb, text(row, "cond").trim(), label,
                    outputLines(key, text(row, "outputs"), label, declaredOutputs, warnedUndeclared, warnings));
        }
    }

    private static void appendCross(StringBuilder sb, Rule r, Set<String> declaredOutputs,
                                    Set<String> warnedUndeclared, List<String> warnings) {
        String key = r.getNodeKey();
        JsonNode cfg = configOf(r);
        String rowVar = text(cfg, "rowVar").trim();
        String colVar = text(cfg, "colVar").trim();
        if (rowVar.isEmpty() || colVar.isEmpty()) {
            throw new ScriptException("交叉节点 " + key + " 未配置行变量 / 列变量");
        }
        List<String> rowValues = strList(cfg.path("rowValues"));
        List<String> colValues = strList(cfg.path("colValues"));
        JsonNode cells = cfg.path("cells");
        if (rowValues.isEmpty() || colValues.isEmpty()) {
            warnings.add("交叉节点 " + key + " 无行列值，编译为空操作");
            return;
        }
        for (int ri = 0; ri < rowValues.size(); ri++) {
            for (int ci = 0; ci < colValues.size(); ci++) {
                String cell = "";
                if (cells.isArray() && ri < cells.size()) {
                    JsonNode row = cells.get(ri);
                    if (row.isArray() && ci < row.size()) cell = row.get(ci).asText("");
                }
                if (cell.trim().isEmpty()) continue;
                String litRow = literal(rowValues.get(ri));
                String litCol = literal(colValues.get(ci));
                if (litRow == null || litCol == null) {
                    warnings.add("交叉节点 " + key + " 的行/列值「" + rowValues.get(ri) + "」「"
                            + colValues.get(ci) + "」含引号无法编译，已跳过该单元格");
                    continue;
                }
                String label = "行 " + (ri + 1) + "「" + rowValues.get(ri) + "」× 列 " + (ci + 1) + "「" + colValues.get(ci) + "」";
                appendBlock(sb, rowVar + " == " + litRow + " and " + colVar + " == " + litCol, label,
                        outputLines(key, cell, label, declaredOutputs, warnedUndeclared, warnings));
            }
        }
    }

    /** 守卫赋值块：cond 非空写 `if <cond> then … end`，空 cond（兜底行）直写赋值 */
    private static void appendBlock(StringBuilder sb, String cond, String label, List<String> assigns) {
        if (cond == null || cond.isEmpty()) {
            sb.append("# ").append(sanitize(label)).append("（无条件兜底）\n");
            for (String a : assigns) sb.append(a).append('\n');
            return;
        }
        sb.append("if ").append(cond).append(" then  # ").append(sanitize(label)).append('\n');
        for (String a : assigns) sb.append("  ").append(a).append('\n');
        sb.append("end\n");
    }

    /** 出参加工：逐行解析 `o.x = rhs`，归一（补 o. 前缀 / 告警未声明出参 / 跳过不合法行） */
    private static List<String> outputLines(String nodeKey, String raw, String label,
                                            Set<String> declaredOutputs, Set<String> warnedUndeclared,
                                            List<String> warnings) {
        List<String> lines = new ArrayList<>();
        if (raw == null) return lines;
        for (String one : raw.split("\\r?\\n")) {
            String t = one.trim();
            if (t.isEmpty()) continue;
            Matcher m = ASSIGN.matcher(t);
            if (!m.matches()) {
                warnings.add("节点 " + nodeKey + "「" + sanitize(label) + "」的赋值行「"
                        + brief(t) + "」不合法，已跳过（P0 仅支持字面量 / 变量引用）");
                continue;
            }
            String target = m.group(1).trim();
            String rhs = m.group(2).trim();
            if (!declaredOutputs.contains(target) && warnedUndeclared.add(target)) {
                warnings.add("出参「" + target + "」未在开始节点声明，已自动登记");
            }
            lines.add("o." + target + " = " + rhs);
        }
        return lines;
    }

    // ------------------------------------------------------------------ 校验与工具

    /** 非分支节点的单出口：无出边 → 编译错误；多出边 → 取 id 最小 + ⚠ */
    private static String singleTarget(String nodeKey, String type, List<DecisionBranch> out,
                                       Map<String, Rule> byKey, List<String> warnings) {
        if (out == null || out.isEmpty()) {
            throw new ScriptException(type + " 节点 " + nodeKey + " 无出边（画布未连线），无法确定执行去向");
        }
        if (out.size() > 1) {
            warnings.add("节点 " + nodeKey + " 有多条出边，已取 id 最小的一条");
        }
        return checkedTarget(nodeKey, out.get(0), byKey);
    }

    /** BRANCH 指定端口的目标：端口未连线 → 编译错误；同端口多条 → 取 id 最小 + ⚠ */
    private static String portTarget(String nodeKey, String port, List<DecisionBranch> out,
                                     Map<String, Rule> byKey, List<String> warnings) {
        List<DecisionBranch> hits = new ArrayList<>();
        if (out != null) {
            for (DecisionBranch e : out) {
                if (port.equals(str(e.getFromPort()))) hits.add(e);
            }
        }
        if (hits.isEmpty()) {
            throw new ScriptException("分支节点 " + nodeKey + " 的端口 " + port + " 未连线");
        }
        if (hits.size() > 1) {
            warnings.add("节点 " + nodeKey + " 端口 " + port + " 有多条连线，已取 id 最小的一条");
        }
        return checkedTarget(nodeKey, hits.get(0), byKey);
    }

    private static String checkedTarget(String nodeKey, DecisionBranch edge, Map<String, Rule> byKey) {
        String to = str(edge.getToNode());
        if (!byKey.containsKey(to)) {
            throw new ScriptException("节点 " + nodeKey + " 存在指向不存在节点 " + to + " 的连线（branch.id=" + edge.getId() + "）");
        }
        return to;
    }

    private static void checkCycles(PolicyModel model) {
        Map<String, Integer> state = new HashMap<>();
        Deque<String> path = new ArrayDeque<>();
        dfs(model.node(model.entryId), model, state, path);
    }

    private static void dfs(PolicyModel.Node node, PolicyModel model, Map<String, Integer> state, Deque<String> path) {
        state.put(node.nodeId, 1);
        path.addLast(node.nodeId);
        for (Iterator<String> it = targetsOf(node).iterator(); it.hasNext(); ) {
            String t = it.next();
            PolicyModel.Node next = model.node(t);
            if (next == null) continue;
            Integer st = state.get(next.nodeId);
            if (st != null && st == 1) {
                List<String> cycle = new ArrayList<>(path);
                int from = cycle.indexOf(next.nodeId);
                throw new ScriptException("画布存在环：" + String.join(" → ", cycle.subList(from, cycle.size()))
                        + " → " + next.nodeId);
            }
            if (st == null) dfs(next, model, state, path);
        }
        state.put(node.nodeId, 2);
        path.removeLast();
    }

    private static void checkReachability(PolicyModel model, List<String> warnings) {
        Set<String> seen = new LinkedHashSet<>();
        Deque<PolicyModel.Node> queue = new ArrayDeque<>();
        PolicyModel.Node entry = model.node(model.entryId);
        seen.add(entry.nodeId);
        queue.add(entry);
        while (!queue.isEmpty()) {
            for (String t : targetsOf(queue.poll())) {
                PolicyModel.Node next = model.node(t);
                if (next != null && seen.add(next.nodeId)) queue.add(next);
            }
        }
        for (PolicyModel.Node n : model.nodes) {
            if (!seen.contains(n.nodeId)) warnings.add("节点 " + n.nodeId + " 不可达（执行路径不含此节点）");
        }
    }

    private static List<String> targetsOf(PolicyModel.Node node) {
        List<String> targets = new ArrayList<>();
        for (PolicyModel.Jump j : node.jumps) {
            targets.add(j.thenTarget);
            if (j.elseTarget != null) targets.add(j.elseTarget);
        }
        return targets;
    }

    private static JsonNode configOf(Rule r) {
        String raw = str(r.getConfigJson());
        if (raw.isEmpty()) return JSON.createObjectNode();
        try {
            JsonNode n = JSON.readTree(raw);
            return n != null && n.isObject() ? n : JSON.createObjectNode();
        } catch (Exception e) {
            throw new ScriptException("节点 " + r.getNodeKey() + " 的 config_json 不是合法 JSON：" + e.getMessage());
        }
    }

    private static List<JsonNode> array(JsonNode cfg, String field) {
        JsonNode n = cfg.path(field);
        List<JsonNode> list = new ArrayList<>();
        if (n.isArray()) n.forEach(list::add);
        return list;
    }

    private static List<String> strList(JsonNode n) {
        List<String> list = new ArrayList<>();
        if (n.isArray()) n.forEach(v -> list.add(v.asText("")));
        return list;
    }

    /** 名称归一化：trim + 剥命名空间前缀误写（`o.testInput` → `testInput`），有改动给 ⚠ */
    private static String normalizeName(String raw, String kind, List<String> warnings) {
        String name = str(raw).trim();
        for (String p : new String[]{"o.", "c.", "param.", "f.", "feat."}) {
            if (name.startsWith(p)) {
                String fixed = name.substring(p.length()).trim();
                warnings.add(kind + "「" + name + "」已归一为 " + fixed);
                return fixed;
            }
        }
        if (!name.equals(str(raw))) {
            warnings.add(kind + "「" + raw + "」含多余空格，已归一为 " + name);
        }
        return name;
    }

    /** CROSS 行列值字面量：数字裸写、字符串加引号；两种引号都含 → null（无法安全表达） */
    private static String literal(String v) {
        String t = str(v).trim();
        if (NUMERIC.matcher(t).matches()) return t;
        if ("true".equalsIgnoreCase(t) || "false".equalsIgnoreCase(t)) return t.toLowerCase();
        if (!t.contains("'")) return "'" + t + "'";
        if (!t.contains("\"")) return "\"" + t + "\"";
        return null;
    }

    /** 注释安全化：# 会破坏行注释结构、换行会破坏行结构，统一替换 */
    private static String sanitize(String s) {
        return str(s).replace("#", "＃").replaceAll("[\\r\\n]+", " ").trim();
    }

    private static String brief(String s) {
        String t = str(s);
        return t.length() > 40 ? t.substring(0, 40) + "…" : t;
    }

    private static String trimTrailingNewline(StringBuilder sb) {
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
        return sb.toString();
    }

    private static String sha256_12(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", hash[i]));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String text(JsonNode n, String field) {
        return n == null ? "" : n.path(field).asText("");
    }

    private static String typeOf(Rule r) {
        return str(r.getNodeType()).trim().toUpperCase();
    }

    private static String str(String s) {
        return s == null ? "" : s;
    }
}
