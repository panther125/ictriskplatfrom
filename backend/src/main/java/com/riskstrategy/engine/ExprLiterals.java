package com.riskstrategy.engine;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把衍生特征表达式里的 {@code @@基础特征编码} 与 {@code :param.x} 引用
 * 替换成目标语言的字面量（SQL / Java / Python 写法各不相同）。
 */
public final class ExprLiterals {

    public static final Pattern REF_PATTERN = Pattern.compile("@@([a-zA-Z_][a-zA-Z0-9_.]{0,127})");
    public static final Pattern PARAM_PATTERN = Pattern.compile(":param\\.([a-zA-Z_][a-zA-Z0-9_]*)");

    public enum Lang { SQL, JAVA, PYTHON }

    private ExprLiterals() {}

    /** 替换表达式中的全部 @@code 与 :param.x */
    public static String replace(String expr, Lang lang, Map<String, Object> refValues, Map<String, Object> testValues) {
        if (expr == null || expr.isEmpty()) return "";
        return replaceParams(replaceRefs(expr, lang, refValues), lang, testValues);
    }

    /** 只替换 @@code（值来自被引用基础特征的实测值） */
    public static String replaceRefs(String expr, Lang lang, Map<String, Object> refValues) {
        Matcher m = REF_PATTERN.matcher(expr == null ? "" : expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            Object v = refValues == null ? null : refValues.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(literal(lang, v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** 只替换 :param.x（键同时支持 "param.x" 与裸名 "x"） */
    public static String replaceParams(String expr, Lang lang, Map<String, Object> testValues) {
        Matcher m = PARAM_PATTERN.matcher(expr == null ? "" : expr);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String name = m.group(1);
            Object v = null;
            if (testValues != null) {
                v = testValues.get("param." + name);
                if (v == null) v = testValues.get(name);
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(literal(lang, v)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    public static String literal(Lang lang, Object v) {
        return switch (lang) {
            case SQL -> sql(v);
            case JAVA -> javaLiteral(v);
            case PYTHON -> python(v);
        };
    }

    /** SQL 字面量：NULL / TRUE / FALSE / 数字原样 / DATE|TIMESTAMP '...' / '字符串'（'' 转义） */
    public static String sql(Object v) {
        if (v == null) return "NULL";
        if (v instanceof Boolean b) return b ? "TRUE" : "FALSE";
        if (v instanceof Number) return numberText(v);
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return "NULL";
        if (isNumberText(s)) return s;
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return s.toUpperCase(Locale.ROOT);
        if (s.matches("\\d{4}-\\d{2}-\\d{2}([ T]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?)?")) {
            return (s.length() > 10 ? "TIMESTAMP '" : "DATE '") + s.replace('T', ' ') + "'";
        }
        return "'" + s.replace("'", "''") + "'";
    }

    /** Java 字面量：null / true|false / 数字原样 / "字符串"（转义） */
    public static String javaLiteral(Object v) {
        if (v == null) return "null";
        if (v instanceof Boolean b) return b ? "true" : "false";
        if (v instanceof Number) return numberText(v);
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return "null";
        if (isNumberText(s)) return s;
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) return s.toLowerCase(Locale.ROOT);
        return "\"" + escapeJava(String.valueOf(v)) + "\"";
    }

    /** Python 字面量：None / True|False / 数字原样 / '字符串'（转义） */
    public static String python(Object v) {
        if (v == null) return "None";
        if (v instanceof Boolean b) return b ? "True" : "False";
        if (v instanceof Number) return numberText(v);
        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return "None";
        if (isNumberText(s)) return s;
        if (s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false")) {
            return s.substring(0, 1).toUpperCase(Locale.ROOT) + s.substring(1).toLowerCase(Locale.ROOT);
        }
        return "'" + escapePython(String.valueOf(v)) + "'";
    }

    /** 前导零的数字串（如 007）按字符串处理，避免被当成八进制 / 丢失前导零 */
    private static boolean isNumberText(String s) {
        if (!s.matches("-?\\d+(\\.\\d+)?")) return false;
        String t = s.startsWith("-") ? s.substring(1) : s;
        return !(t.length() > 1 && t.charAt(0) == '0' && Character.isDigit(t.charAt(1)));
    }

    private static String numberText(Object v) {
        if (v instanceof java.math.BigDecimal d) return d.toPlainString();
        return String.valueOf(v);
    }

    private static String escapeJava(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '"' -> b.append("\\\"");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    private static String escapePython(String s) {
        StringBuilder b = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> b.append("\\\\");
                case '\'' -> b.append("\\'");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                default -> b.append(c);
            }
        }
        return b.toString();
    }
}
