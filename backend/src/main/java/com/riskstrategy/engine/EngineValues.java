package com.riskstrategy.engine;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** 引擎间共用的小工具：测试值还原 / 参数提取 / 错误信息截断。 */
final class EngineValues {

    private EngineValues() {}

    /** 表单测试值是字符串，尽量还原成数字 / 布尔，方便脚本直接算术比较 */
    static Object coerce(Object raw) {
        if (raw == null) return null;
        if (!(raw instanceof String s)) return raw;
        String t = s.trim();
        if (t.isEmpty()) return null;
        if (t.matches("-?\\d+")) {
            try { return Long.parseLong(t); } catch (NumberFormatException ignore) { /* 落到字符串 */ }
        } else if (t.matches("-?\\d+\\.\\d+")) {
            try { return new BigDecimal(t); } catch (NumberFormatException ignore) { /* 落到字符串 */ }
        } else if (t.equalsIgnoreCase("true") || t.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(t.toLowerCase(Locale.ROOT));
        }
        return s;
    }

    /** 从测试值里提取决策入参（:param.uid → uid） */
    static Map<String, Object> paramsOf(Map<String, Object> testValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        if (testValues == null) return params;
        for (Map.Entry<String, Object> e : testValues.entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith("param.")) {
                params.put(e.getKey().substring(6), e.getValue());
            }
        }
        return params;
    }

    static String firstLine(String s) {
        if (s == null) return "";
        String t = s.trim();
        int i = t.indexOf('\n');
        return i < 0 ? t : t.substring(0, i).trim();
    }

    static long msSince(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
