package com.riskstrategy.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** 衍生特征 OP 引擎：可视化算子树的 JSON 求值 + 文本回显。 */
@Component
public class OpTreeEngine {

    private static final Set<String> KNOWN_TYPES = Set.of(
            "CONST", "REF", "CMP", "LOGIC", "ARITH", "CASE", "DICT", "FILLNULL");

    private final ObjectMapper mapper;

    public OpTreeEngine(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public EngineResult execute(String expr, Map<String, Object> refValues, Map<String, Object> testValues, long timeoutMs) {
        long start = System.nanoTime();
        EngineResult r = new EngineResult();
        r.setEngine("OP");
        r.setExecutedExpr(expr == null ? "" : expr);

        if (expr == null || expr.isBlank()) {
            r.setError("算子树为空，请先在 OP 编辑器里配置算子");
            r.setElapsedMs(EngineValues.msSince(start));
            return r;
        }
        if (!expr.trim().startsWith("{")) {
            r.setError("当前加工方式是 OP，但表达式不是算子树 JSON；请到 OP 编辑器重新配置");
            r.setElapsedMs(EngineValues.msSince(start));
            return r;
        }
        JsonNode root;
        try {
            root = mapper.readTree(expr);
        } catch (Exception e) {
            r.setError("算子树 JSON 解析失败：" + EngineValues.firstLine(e.getMessage()));
            r.setElapsedMs(EngineValues.msSince(start));
            return r;
        }
        String invalid = validateNode(root, "root");
        if (invalid != null) {
            r.setError("算子树结构不合法：" + invalid);
            r.setElapsedMs(EngineValues.msSince(start));
            return r;
        }

        EvalCtx ctx = new EvalCtx(refValues == null ? Map.of() : refValues, testValues == null ? Map.of() : testValues);
        try {
            Object value = normalize(eval(root, ctx, 0));
            r.setSuccess(true);
            r.setValue(value);
            r.setValueType(EngineResult.inferValueType(value));
        } catch (RuntimeException e) {
            r.setError("算子树执行失败：" + e.getMessage());
        }
        try {
            r.setExecutedExpr(render(root, ctx, 0));
        } catch (RuntimeException ignore) { /* 回显渲染失败不阻断 */ }
        r.getWarnings().addAll(ctx.warnings);
        r.setElapsedMs(EngineValues.msSince(start));
        return r;
    }

    /** 保存时的结构校验：返回 null 表示通过 */
    public String validate(String expr) {
        if (expr == null || expr.isBlank()) return "算子树为空";
        if (!expr.trim().startsWith("{")) return "必须是算子树 JSON（以 { 开头）";
        JsonNode root;
        try {
            root = mapper.readTree(expr);
        } catch (Exception e) {
            return "JSON 解析失败：" + EngineValues.firstLine(e.getMessage());
        }
        return validateNode(root, "root");
    }

    // =========================================================================
    //  求值
    // =========================================================================

    private Object eval(JsonNode n, EvalCtx ctx, int depth) {
        if (n == null || n.isNull() || n.isMissingNode()) throw illegal("节点缺失");
        if (depth > 50) throw illegal("算子树嵌套超过 50 层");
        if (++ctx.nodes > 500) throw illegal("算子树节点数超过 500，请拆分后再试");
        String type = n.path("type").asText("").trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "CONST":
                return constValue(n.get("value"));
            case "REF":
                return resolveRef(n, ctx);
            case "CMP": {
                String op = text(n, "op");
                Object a = eval(child(n, "left"), ctx, depth + 1);
                String upper = op.toUpperCase(Locale.ROOT);
                if (upper.equals("IS_NULL") || upper.equals("ISNULL") || upper.equals("NOT_NULL") || upper.equals("ISNOTNULL")) {
                    return compare(op, a, null, ctx);
                }
                return compare(op, a, eval(child(n, "right"), ctx, depth + 1), ctx);
            }
            case "LOGIC":
                return logicNode(n, ctx, depth);
            case "ARITH":
                return arithNode(n, ctx, depth);
            case "CASE":
                return caseNode(n, ctx, depth);
            case "DICT":
                return dictNode(n, ctx, depth);
            case "FILLNULL":
                return fillNullNode(n, ctx, depth);
            default:
                throw illegal("未知算子类型：" + (type.isEmpty() ? "（缺 type）" : type)
                        + "（支持 CONST/REF/CMP/LOGIC/ARITH/CASE/DICT/FILLNULL）");
        }
    }

    private Object resolveRef(JsonNode n, EvalCtx ctx) {
        String ref = text(n, "ref");
        if (ref.isEmpty()) throw illegal("REF 节点缺少 ref 字段");
        Object v = resolveRefQuiet(n, ctx);
        boolean known = ref.startsWith("@@")
                ? ctx.refs.containsKey(ref.substring(2))
                : ref.startsWith(":param.")
                    ? (ctx.values.containsKey("param." + ref.substring(7)) || ctx.values.containsKey(ref.substring(7)))
                    : (ctx.refs.containsKey(ref) || ctx.values.containsKey(ref));
        if (!known) ctx.warn("引用 " + ref + " 没有测试值，按 NULL 处理");
        return v;
    }

    private Object resolveRefQuiet(JsonNode n, EvalCtx ctx) {
        String ref = n.path("ref").asText("");
        if (ref.startsWith("@@")) return ctx.refs.get(ref.substring(2));
        if (ref.startsWith(":param.")) {
            String name = ref.substring(7);
            Object v = ctx.values.get("param." + name);
            return v == null ? ctx.values.get(name) : v;
        }
        return ctx.refs.containsKey(ref) ? ctx.refs.get(ref) : ctx.values.get(ref);
    }

    private Object compare(String op, Object a, Object b, EvalCtx ctx) {
        String o = op == null ? "" : op.trim().toUpperCase(Locale.ROOT);
        switch (o) {
            case "EQ": return eq(a, b);
            case "NE": return !eq(a, b);
            case "GT": case "GTE": case "LT": case "LTE": {
                if (a == null || b == null) {
                    ctx.warn("空值参与大小比较，按不成立处理");
                    return false;
                }
                int c = cmp(a, b);
                return switch (o) {
                    case "GT" -> c > 0;
                    case "GTE" -> c >= 0;
                    case "LT" -> c < 0;
                    default -> c <= 0;
                };
            }
            case "IS_NULL", "ISNULL": return a == null;
            case "NOT_NULL", "ISNOTNULL": return a != null;
            case "IN", "NOT_IN": {
                List<?> list = b instanceof List<?> l ? l : (b == null ? List.of() : List.of(b));
                boolean has = list.stream().anyMatch(x -> eq(a, x));
                return "IN".equals(o) ? has : !has;
            }
            default:
                throw illegal("未知比较算子 " + op + "（支持 EQ/NE/GT/GTE/LT/LTE/IN/NOT_IN/IS_NULL/NOT_NULL）");
        }
    }

    private boolean eq(Object a, Object b) {
        if (a == null || b == null) return a == null && b == null;
        BigDecimal na = numeric(a);
        BigDecimal nb = numeric(b);
        if (na != null && nb != null) return na.compareTo(nb) == 0;
        return String.valueOf(a).equals(String.valueOf(b));
    }

    private int cmp(Object a, Object b) {
        BigDecimal na = numeric(a);
        BigDecimal nb = numeric(b);
        if (na != null && nb != null) return na.compareTo(nb);
        return String.valueOf(a).compareTo(String.valueOf(b));
    }

    private Object logicNode(JsonNode n, EvalCtx ctx, int depth) {
        String op = text(n, "op").toUpperCase(Locale.ROOT);
        JsonNode args = n.get("args");
        if (args == null || !args.isArray() || args.isEmpty()) throw illegal("LOGIC 节点需要非空 args 数组");
        if ("NOT".equals(op)) {
            if (args.size() != 1) throw illegal("NOT 只接受一个子节点");
            return !truthy(eval(args.get(0), ctx, depth + 1));
        }
        if ("AND".equals(op)) {
            for (JsonNode a : args) {
                if (!truthy(eval(a, ctx, depth + 1))) return false;
            }
            return true;
        }
        if ("OR".equals(op)) {
            for (JsonNode a : args) {
                if (truthy(eval(a, ctx, depth + 1))) return true;
            }
            return false;
        }
        throw illegal("未知逻辑算子 " + op + "（支持 AND / OR / NOT）");
    }

    private Object arithNode(JsonNode n, EvalCtx ctx, int depth) {
        String op = text(n, "op").toUpperCase(Locale.ROOT);
        JsonNode args = n.get("args");
        if (args == null || !args.isArray() || args.isEmpty()) throw illegal("ARITH 节点需要非空 args 数组");
        BigDecimal acc = toNumber(eval(args.get(0), ctx, depth + 1), op);
        for (int i = 1; i < args.size(); i++) {
            BigDecimal v = toNumber(eval(args.get(i), ctx, depth + 1), op);
            acc = switch (op) {
                case "ADD" -> acc.add(v);
                case "SUB" -> acc.subtract(v);
                case "MUL" -> acc.multiply(v);
                case "DIV" -> {
                    if (v.signum() == 0) throw illegal("除法除数为 0");
                    yield acc.divide(v, 6, RoundingMode.HALF_UP);
                }
                case "MOD" -> {
                    if (v.signum() == 0) throw illegal("取模除数为 0");
                    yield acc.remainder(v);
                }
                default -> throw illegal("未知算术算子 " + op + "（支持 ADD / SUB / MUL / DIV / MOD）");
            };
        }
        return acc;
    }

    private Object caseNode(JsonNode n, EvalCtx ctx, int depth) {
        JsonNode branches = n.get("branches");
        if (branches == null || !branches.isArray() || branches.isEmpty()) throw illegal("CASE 节点需要非空 branches 数组");
        for (JsonNode br : branches) {
            JsonNode when = br.get("when");
            JsonNode then = br.get("then");
            if (when == null || then == null) throw illegal("CASE 分支缺少 when / then");
            if (truthy(eval(when, ctx, depth + 1))) return eval(then, ctx, depth + 1);
        }
        JsonNode els = n.get("else");
        return els == null || els.isNull() ? null : eval(els, ctx, depth + 1);
    }

    private Object dictNode(JsonNode n, EvalCtx ctx, int depth) {
        Object src = eval(child(n, "source"), ctx, depth + 1);
        JsonNode mapping = n.get("mapping");
        if (mapping == null || !mapping.isObject()) throw illegal("DICT 节点需要 mapping 对象");
        if (src != null) {
            String key = keyOf(src);
            if (mapping.has(key)) return constValue(mapping.get(key));
            for (Iterator<Map.Entry<String, JsonNode>> it = mapping.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                if (e.getKey().trim().equalsIgnoreCase(key)) return constValue(e.getValue());
            }
        }
        JsonNode def = n.get("default");
        if (def == null || def.isNull()) return null;
        return def.isObject() && def.has("type") ? eval(def, ctx, depth + 1) : constValue(def);
    }

    private Object fillNullNode(JsonNode n, EvalCtx ctx, int depth) {
        Object v = eval(child(n, "source"), ctx, depth + 1);
        if (v == null || (v instanceof String s && s.isBlank())) return eval(child(n, "fallback"), ctx, depth + 1);
        return v;
    }

    private boolean truthy(Object v) {
        if (v == null) return false;
        if (v instanceof Boolean b) return b;
        BigDecimal n = numeric(v);
        if (n != null) return n.signum() != 0;
        String s = String.valueOf(v).trim();
        return !s.isEmpty() && !s.equalsIgnoreCase("false");
    }

    private BigDecimal toNumber(Object v, String op) {
        BigDecimal d = v == null ? null : numeric(v);
        if (d == null) throw illegal("算术算子 " + op + " 的操作数不是数字：" + v);
        return d;
    }

    private BigDecimal numeric(Object o) {
        if (o instanceof BigDecimal d) return d;
        if (o instanceof Number n) return new BigDecimal(String.valueOf(n));
        String s = String.valueOf(o).trim();
        if (s.matches("-?\\d+(\\.\\d+)?")) return new BigDecimal(s);
        return null;
    }

    private Object constValue(JsonNode v) {
        if (v == null || v.isNull() || v.isMissingNode()) return null;
        if (v.isBoolean()) return v.booleanValue();
        if (v.isIntegralNumber()) {
            if (v.canConvertToInt()) return v.intValue();
            if (v.canConvertToLong()) return v.longValue();
            return v.bigIntegerValue();
        }
        if (v.isNumber()) return v.decimalValue();
        if (v.isTextual()) return v.textValue();
        if (v.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : v) list.add(constValue(item));
            return list;
        }
        if (v.isObject()) {
            Map<String, Object> map = new LinkedHashMap<>();
            for (Iterator<Map.Entry<String, JsonNode>> it = v.fields(); it.hasNext(); ) {
                Map.Entry<String, JsonNode> e = it.next();
                map.put(e.getKey(), constValue(e.getValue()));
            }
            return map;
        }
        return v.asText();
    }

    private String keyOf(Object v) {
        if (v instanceof BigDecimal d) {
            BigDecimal s = d.stripTrailingZeros();
            return s.scale() <= 0 ? s.toBigInteger().toString() : s.toPlainString();
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return String.valueOf(v);
        }
        return String.valueOf(v).trim();
    }

    private Object normalize(Object v) {
        if (v instanceof BigDecimal d) {
            BigDecimal s = d.stripTrailingZeros();
            if (s.scale() <= 0) {
                try {
                    return s.longValueExact();
                } catch (ArithmeticException ignore) {
                    return s.toPlainString();
                }
            }
            return s;
        }
        return v;
    }

    // =========================================================================
    //  文本回显（@@ 已替换为实测值）
    // =========================================================================

    private String render(JsonNode n, EvalCtx ctx, int depth) {
        if (n == null || n.isNull() || n.isMissingNode()) return "NULL";
        if (depth > 50) return "...";
        String type = n.path("type").asText("").trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "CONST":
                return ExprLiterals.sql(constValue(n.get("value")));
            case "REF":
                return ExprLiterals.sql(resolveRefQuiet(n, ctx));
            case "CMP": {
                String op = n.path("op").asText("").trim().toUpperCase(Locale.ROOT);
                String left = render(n.get("left"), ctx, depth + 1);
                if (op.equals("IS_NULL") || op.equals("ISNULL")) return "(" + left + " IS NULL)";
                if (op.equals("NOT_NULL") || op.equals("ISNOTNULL")) return "(" + left + " IS NOT NULL)";
                String sym = switch (op) {
                    case "EQ" -> " = ";
                    case "NE" -> " != ";
                    case "GT" -> " > ";
                    case "GTE" -> " >= ";
                    case "LT" -> " < ";
                    case "LTE" -> " <= ";
                    case "IN" -> " IN ";
                    case "NOT_IN" -> " NOT IN ";
                    default -> " " + op + " ";
                };
                return "(" + left + sym + render(n.get("right"), ctx, depth + 1) + ")";
            }
            case "LOGIC": {
                String op = n.path("op").asText("").trim().toUpperCase(Locale.ROOT);
                JsonNode args = n.path("args");
                if ("NOT".equals(op) && args.size() > 0) return "NOT (" + render(args.get(0), ctx, depth + 1) + ")";
                StringBuilder b = new StringBuilder("(");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) b.append(" ").append(op).append(" ");
                    b.append(render(args.get(i), ctx, depth + 1));
                }
                return b.append(")").toString();
            }
            case "ARITH": {
                String op = n.path("op").asText("").trim().toUpperCase(Locale.ROOT);
                String sym = switch (op) {
                    case "ADD" -> " + ";
                    case "SUB" -> " - ";
                    case "MUL" -> " * ";
                    case "DIV" -> " / ";
                    case "MOD" -> " % ";
                    default -> " " + op + " ";
                };
                JsonNode args = n.path("args");
                StringBuilder b = new StringBuilder("(");
                for (int i = 0; i < args.size(); i++) {
                    if (i > 0) b.append(sym);
                    b.append(render(args.get(i), ctx, depth + 1));
                }
                return b.append(")").toString();
            }
            case "CASE": {
                StringBuilder b = new StringBuilder("CASE");
                for (JsonNode br : n.path("branches")) {
                    b.append("\n  WHEN ").append(render(br.get("when"), ctx, depth + 1))
                            .append(" THEN ").append(render(br.get("then"), ctx, depth + 1));
                }
                JsonNode els = n.get("else");
                if (els != null && !els.isNull()) b.append("\n  ELSE ").append(render(els, ctx, depth + 1));
                return b.append("\nEND").toString();
            }
            case "DICT": {
                StringBuilder b = new StringBuilder("查表(");
                b.append(render(n.get("source"), ctx, depth + 1)).append(", {");
                boolean first = true;
                for (Iterator<Map.Entry<String, JsonNode>> it = n.path("mapping").fields(); it.hasNext(); ) {
                    Map.Entry<String, JsonNode> e = it.next();
                    if (!first) b.append(", ");
                    first = false;
                    b.append("'").append(e.getKey()).append("'→").append(ExprLiterals.sql(constValue(e.getValue())));
                }
                b.append("}");
                JsonNode def = n.get("default");
                if (def != null && !def.isNull()) {
                    b.append(", 默认 ").append(def.isObject() && def.has("type")
                            ? render(def, ctx, depth + 1) : ExprLiterals.sql(constValue(def)));
                }
                return b.append(")").toString();
            }
            case "FILLNULL":
                return "COALESCE(" + render(n.get("source"), ctx, depth + 1) + ", "
                        + render(n.get("fallback"), ctx, depth + 1) + ")";
            default:
                return n.toString();
        }
    }

    // =========================================================================
    //  结构校验
    // =========================================================================

    private String validateNode(JsonNode n, String path) {
        if (n == null || !n.isObject()) return path + " 必须是对象";
        String type = n.path("type").asText("").trim().toUpperCase(Locale.ROOT);
        if (!KNOWN_TYPES.contains(type)) {
            return path + ".type 缺失或未知：" + n.path("type").asText("(空)")
                    + "（支持 CONST/REF/CMP/LOGIC/ARITH/CASE/DICT/FILLNULL）";
        }
        switch (type) {
            case "REF":
                return n.path("ref").asText("").isBlank() ? path + " 缺少 ref" : null;
            case "CMP": {
                String op = n.path("op").asText("").trim().toUpperCase(Locale.ROOT);
                if (op.isEmpty()) return path + " 缺少 op";
                String e = validateNode(n.get("left"), path + ".left");
                if (e != null) return e;
                if (op.equals("IS_NULL") || op.equals("ISNULL") || op.equals("NOT_NULL") || op.equals("ISNOTNULL")) return null;
                return validateNode(n.get("right"), path + ".right");
            }
            case "LOGIC":
            case "ARITH": {
                if (n.path("op").asText("").isBlank()) return path + " 缺少 op";
                JsonNode args = n.get("args");
                if (args == null || !args.isArray() || args.isEmpty()) return path + " 需要非空 args 数组";
                for (int i = 0; i < args.size(); i++) {
                    String e = validateNode(args.get(i), path + ".args[" + i + "]");
                    if (e != null) return e;
                }
                return null;
            }
            case "CASE": {
                JsonNode branches = n.get("branches");
                if (branches == null || !branches.isArray() || branches.isEmpty()) return path + " 需要非空 branches 数组";
                for (int i = 0; i < branches.size(); i++) {
                    JsonNode br = branches.get(i);
                    String bp = path + ".branches[" + i + "]";
                    if (br == null || !br.isObject()) return bp + " 必须是对象";
                    String e = validateNode(br.get("when"), bp + ".when");
                    if (e != null) return e;
                    e = validateNode(br.get("then"), bp + ".then");
                    if (e != null) return e;
                }
                JsonNode els = n.get("else");
                return els == null || els.isNull() ? null : validateNode(els, path + ".else");
            }
            case "DICT": {
                String e = validateNode(n.get("source"), path + ".source");
                if (e != null) return e;
                return n.path("mapping").isObject() ? null : path + ".mapping 必须是对象";
            }
            case "FILLNULL": {
                String e = validateNode(n.get("source"), path + ".source");
                if (e != null) return e;
                return validateNode(n.get("fallback"), path + ".fallback");
            }
            default:
                return null; // CONST：任何 JSON 值都合法
        }
    }

    private JsonNode child(JsonNode n, String field) {
        JsonNode c = n.get(field);
        if (c == null || c.isNull() || c.isMissingNode()) throw illegal("节点缺少子字段 " + field);
        return c;
    }

    private String text(JsonNode n, String field) {
        return n.path(field).asText("").trim();
    }

    private IllegalArgumentException illegal(String msg) {
        return new IllegalArgumentException(msg);
    }

    private static final class EvalCtx {
        final Map<String, Object> refs;
        final Map<String, Object> values;
        final List<String> warnings = new ArrayList<>();
        int nodes = 0;

        EvalCtx(Map<String, Object> refs, Map<String, Object> values) {
            this.refs = refs;
            this.values = values;
        }

        void warn(String w) {
            if (warnings.size() < 20 && !warnings.contains(w)) warnings.add(w);
        }
    }
}
