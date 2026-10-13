package com.riskstrategy.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 决策条件表达式求值器（试算中心用）。
 *
 * <p>策略编排里节点/规则的准入条件以字符串形式存放（如
 * {@code feat.user_overdue_level == 'H' and view.risk_user_info.overdue_cnt >= 3}）。
 * 试算要给出「为什么是这个结论」，就必须真的把条件跑一遍，而不是按固定剧本输出。
 *
 * <p><b>语法支持</b>（刻意保持最小集合，够用且不会误判）：
 * <ul>
 *   <li>逻辑：{@code and} / {@code &&}（全真才真）、{@code or} / {@code ||}（一真即真），不支持括号嵌套</li>
 *   <li>比较：{@code ==} {@code =} {@code !=} {@code <>} {@code >=} {@code <=} {@code >} {@code <}</li>
 *   <li>操作数：数字、单/双引号字符串、布尔字面量、变量名（{@code feat.x} / {@code view.a.b} / {@code param.x} / 裸名）</li>
 * </ul>
 *
 * <p><b>不支持的表达式一律返回 {@code null}</b>（既非命中也非未命中），由调用方决定如何降级，
 * 避免把「解析不了」误判成「条件不成立」而给出错误结论。
 */
public final class ConditionEvaluator {

    private ConditionEvaluator() {
    }

    /**
     * 求值三态结果。
     */
    public enum Result {
        /** 条件成立 */
        TRUE,
        /** 条件不成立 */
        FALSE,
        /** 表达式为空、语法不支持、或引用了未知变量 —— 结论未知 */
        UNKNOWN
    }

    public static Result evaluate(String expr, Map<String, Object> vars) {
        if (expr == null || expr.trim().isEmpty()) return Result.UNKNOWN;
        try {
            return evalOr(expr.trim(), vars);
        } catch (RuntimeException e) {
            return Result.UNKNOWN;
        }
    }

    /** 便捷方法：只有明确成立才算命中 */
    public static boolean isTrue(String expr, Map<String, Object> vars) {
        return evaluate(expr, vars) == Result.TRUE;
    }

    // ------------------------------------------------------------------ 逻辑层

    private static Result evalOr(String expr, Map<String, Object> vars) {
        List<String> parts = splitTop(expr, new String[]{" or ", " || "});
        if (parts.size() > 1) {
            boolean anyUnknown = false;
            for (String p : parts) {
                Result r = evalAnd(p, vars);
                if (r == Result.TRUE) return Result.TRUE;
                if (r == Result.UNKNOWN) anyUnknown = true;
            }
            return anyUnknown ? Result.UNKNOWN : Result.FALSE;
        }
        return evalAnd(expr, vars);
    }

    private static Result evalAnd(String expr, Map<String, Object> vars) {
        List<String> parts = splitTop(expr, new String[]{" and ", " && "});
        if (parts.size() > 1) {
            boolean anyUnknown = false;
            for (String p : parts) {
                Result r = evalCompare(p, vars);
                if (r == Result.FALSE) return Result.FALSE;
                if (r == Result.UNKNOWN) anyUnknown = true;
            }
            return anyUnknown ? Result.UNKNOWN : Result.TRUE;
        }
        return evalCompare(expr, vars);
    }

    // ------------------------------------------------------------------ 比较层

    /** 按「长操作符优先」顺序匹配，避免 `>=` 被 `>` 抢先切开 */
    private static final String[] OPERATORS = {">=", "<=", "!=", "<>", "==", "=", ">", "<"};

    private static Result evalCompare(String raw, Map<String, Object> vars) {
        String expr = stripOuterParen(raw.trim());
        if (expr.isEmpty()) return Result.UNKNOWN;

        // 裸布尔：条件本身就是 true/false
        if ("true".equalsIgnoreCase(expr)) return Result.TRUE;
        if ("false".equalsIgnoreCase(expr)) return Result.FALSE;

        // 单变量布尔：条件直接写了一个标志位变量
        if (expr.matches("[A-Za-z_][A-Za-z0-9_.]*") && vars != null) {
            Object v = vars.get(expr);
            if (v == null) {
                int dot = expr.lastIndexOf('.');
                if (dot > 0) v = vars.get(expr.substring(dot + 1));
            }
            if (v instanceof Boolean b) return b ? Result.TRUE : Result.FALSE;
        }

        for (String op : OPERATORS) {
            int idx = indexOfOperator(expr, op);
            if (idx <= 0) continue;
            String lhs = expr.substring(0, idx).trim();
            String rhs = expr.substring(idx + op.length()).trim();
            if (lhs.isEmpty() || rhs.isEmpty()) continue;

            Object lv = resolveOperand(lhs, vars);
            Object rv = resolveOperand(rhs, vars);
            if (lv == UNRESOLVED || rv == UNRESOLVED) return Result.UNKNOWN;
            return compare(lv, rv, op) ? Result.TRUE : Result.FALSE;
        }
        return Result.UNKNOWN;
    }

    private static boolean compare(Object lv, Object rv, String op) {
        BigDecimal ln = toNumber(lv);
        BigDecimal rn = toNumber(rv);
        if (ln != null && rn != null) {
            int c = ln.compareTo(rn);
            return switch (op) {
                case "==", "=" -> c == 0;
                case "!=", "<>" -> c != 0;
                case ">=" -> c >= 0;
                case "<=" -> c <= 0;
                case ">" -> c > 0;
                default -> c < 0;
            };
        }
        // 非数值：仅支持等值/不等，大小比较无意义 → 视为不成立
        String ls = String.valueOf(lv).trim();
        String rs = String.valueOf(rv).trim();
        boolean eq = ls.equalsIgnoreCase(rs);
        return switch (op) {
            case "==", "=" -> eq;
            case "!=", "<>" -> !eq;
            default -> false;
        };
    }

    // ------------------------------------------------------------------ 操作数

    private static final Object UNRESOLVED = new Object();

    private static Object resolveOperand(String token, Map<String, Object> vars) {
        String t = token.trim();
        if (t.isEmpty()) return UNRESOLVED;
        if ((t.startsWith("'") && t.endsWith("'") && t.length() >= 2)
                || (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2)) {
            return t.substring(1, t.length() - 1);
        }
        if ("true".equalsIgnoreCase(t)) return Boolean.TRUE;
        if ("false".equalsIgnoreCase(t)) return Boolean.FALSE;
        if ("null".equalsIgnoreCase(t)) return null;
        if (t.matches("-?\\d+(\\.\\d+)?")) return new BigDecimal(t);

        // 变量查找：先精确匹配，再退化到末段名（`feat.a.b` → `b`），最后忽略大小写
        if (vars != null && !vars.isEmpty()) {
            if (vars.containsKey(t)) return vars.get(t);
            int dot = t.lastIndexOf('.');
            if (dot > 0) {
                String tail = t.substring(dot + 1);
                if (vars.containsKey(tail)) return vars.get(tail);
            }
            for (Map.Entry<String, Object> e : vars.entrySet()) {
                if (e.getKey().equalsIgnoreCase(t)) return e.getValue();
            }
        }
        // 裸标识符（如 overdue_cnt 未登记）当字符串常量处理：`H`、`LOAN_APPLY` 这类枚举值很常见
        return t.matches("[A-Za-z_][A-Za-z0-9_.]*") && t.indexOf('.') < 0 ? t : UNRESOLVED;
    }

    private static BigDecimal toNumber(Object v) {
        if (v == null) return null;
        if (v instanceof BigDecimal b) return b;
        if (v instanceof Number n) return new BigDecimal(n.toString());
        if (v instanceof Boolean) return null;
        String s = String.valueOf(v).trim();
        return s.matches("-?\\d+(\\.\\d+)?") ? new BigDecimal(s) : null;
    }

    // ------------------------------------------------------------------ 工具

    /**
     * 只在「顶层」按分隔符切分 —— 引号内的内容不参与切分，
     * 否则 {@code channel == 'A or B'} 会被错误拆成两段。
     */
    private static List<String> splitTop(String expr, String[] seps) {
        for (String sep : seps) {
            List<Integer> hits = new ArrayList<>();
            int depth = 0;
            char quote = 0;
            for (int i = 0; i + sep.length() <= expr.length(); i++) {
                char c = expr.charAt(i);
                if (quote != 0) {
                    if (c == quote) quote = 0;
                    continue;
                }
                if (c == '\'' || c == '"') {
                    quote = c;
                    continue;
                }
                if (c == '(') depth++;
                if (c == ')') depth = Math.max(0, depth - 1);
                if (depth == 0 && expr.regionMatches(true, i, sep, 0, sep.length())) {
                    hits.add(i);
                    i += sep.length() - 1;
                }
            }
            if (!hits.isEmpty()) {
                List<String> out = new ArrayList<>();
                int from = 0;
                for (int h : hits) {
                    out.add(expr.substring(from, h));
                    from = h + sep.length();
                }
                out.add(expr.substring(from));
                return out;
            }
        }
        return List.of(expr);
    }

    private static int indexOfOperator(String expr, String op) {
        char quote = 0;
        int depth = 0;
        for (int i = 0; i + op.length() <= expr.length(); i++) {
            char c = expr.charAt(i);
            if (quote != 0) {
                if (c == quote) quote = 0;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            if (c == '(') { depth++; continue; }
            if (c == ')') { depth--; continue; }
            if (depth != 0) continue;
            if (!expr.regionMatches(i, op, 0, op.length())) continue;
            // `=` 不能命中 `>=` / `<=` / `!=` / `==` 的第二个字符
            if (op.equals("=") && i > 0) {
                char prev = expr.charAt(i - 1);
                if (prev == '>' || prev == '<' || prev == '!' || prev == '=') continue;
            }
            if (op.equals(">") && i + 1 < expr.length() && expr.charAt(i + 1) == '=') continue;
            if (op.equals("<") && i + 1 < expr.length()
                    && (expr.charAt(i + 1) == '=' || expr.charAt(i + 1) == '>')) continue;
            return i;
        }
        return -1;
    }

    private static String stripOuterParen(String s) {
        String cur = s;
        while (cur.length() > 1 && cur.charAt(0) == '(' && cur.charAt(cur.length() - 1) == ')') {
            int depth = 0;
            boolean wraps = true;
            for (int i = 0; i < cur.length(); i++) {
                if (cur.charAt(i) == '(') depth++;
                else if (cur.charAt(i) == ')') {
                    depth--;
                    if (depth == 0 && i < cur.length() - 1) { wraps = false; break; }
                }
            }
            if (!wraps) break;
            cur = cur.substring(1, cur.length() - 1).trim();
        }
        return cur;
    }
}
