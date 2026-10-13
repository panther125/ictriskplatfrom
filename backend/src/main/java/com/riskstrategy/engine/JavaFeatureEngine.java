package com.riskstrategy.engine;

import com.riskstrategy.dto.ResolvedInputDTO;
import org.codehaus.commons.compiler.CompileException;
import org.codehaus.janino.SimpleCompiler;
import org.springframework.stereotype.Component;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 衍生特征 Java 引擎：@@/:param 替换为 Java 字面量 → Janino 编译 → 独立 ClassLoader 执行 apply。 */
@Component
public class JavaFeatureEngine {

    private static final String CLASS_NAME = "DrvTrialFeature";
    /** 脚本按方法体包装时，用户脚本第 1 行在拼装源码里的行号偏移 */
    private static final int BODY_LINE_OFFSET = 7;

    private static final String PRELUDE = """
            import java.util.*;
            import java.math.*;
            import java.time.*;
            import com.riskstrategy.engine.Ctx;

            public class DrvTrialFeature {
            """;

    /** 脚本只做数据加工，不需要触达运行时 / 文件 / 网络 / 反射，命中即拒绝执行 */
    private static final Pattern DANGEROUS = Pattern.compile(
            "(Runtime\\s*\\.|ProcessBuilder|System\\s*\\.\\s*(exit|getenv|getProperties|setProperty|load)"
                    + "|Class\\s*\\.\\s*forName|getClassLoader|java\\s*\\.\\s*(net|io|nio)\\s*\\.|Files\\s*\\.|new\\s+File"
                    + "|Socket|Unsafe|java\\s*\\.\\s*lang\\s*\\.\\s*reflect|ScriptEngine|javax\\s*\\.\\s*naming|Thread)");

    public EngineResult execute(String expr, List<ResolvedInputDTO> inputs, Map<String, Object> testValues,
                                Map<String, Object> refValues, long timeoutMs) {
        long start = System.nanoTime();
        EngineResult r = new EngineResult();
        r.setEngine("JAVA");

        String code = expr == null ? "" : expr;
        Matcher danger = DANGEROUS.matcher(code);
        if (danger.find()) {
            r.setExecutedExpr(code);
            r.setError("脚本包含被禁止的调用「" + danger.group().trim()
                    + "」：衍生特征脚本只做数据加工，不允许访问运行时 / 文件 / 网络 / 反射");
            r.setElapsedMs(EngineValues.msSince(start));
            return r;
        }

        String substituted = ExprLiterals.replace(code, ExprLiterals.Lang.JAVA, refValues, testValues);
        r.setExecutedExpr(substituted);

        boolean wrapped = !substituted.contains("apply(");
        String source = wrapped
                ? PRELUDE + "    public static Object apply(java.util.Map<String, Object> in, Ctx ctx) {\n"
                    + indent(substituted) + "\n    }\n}\n"
                : PRELUDE + substituted + "\n}\n";

        try {
            SimpleCompiler compiler = new SimpleCompiler();
            compiler.setParentClassLoader(getClass().getClassLoader());
            compiler.cook(source);
            Class<?> clazz = compiler.getClassLoader().loadClass(CLASS_NAME);

            Method apply;
            try {
                apply = clazz.getMethod("apply", Map.class, Ctx.class);
            } catch (NoSuchMethodException e) {
                r.setError("未找到 public static Object apply(Map<String, Object> in, Ctx ctx) 方法，请按帮助示例的签名编写");
                r.setElapsedMs(EngineValues.msSince(start));
                return r;
            }

            Map<String, Object> in = new LinkedHashMap<>();
            for (ResolvedInputDTO iv : inputs) {
                in.put(iv.getName(), EngineValues.coerce(testValues.get(iv.getName())));
            }
            Ctx ctx = new Ctx(in, EngineValues.paramsOf(testValues));

            Object value = ExecGuard.call(timeoutMs, () -> apply.invoke(null, in, ctx));
            r.setSuccess(true);
            r.setValue(value);
            r.setValueType(EngineResult.inferValueType(value));
        } catch (CompileException e) {
            r.setError(compileErrorMsg(e, wrapped));
        } catch (TimeoutException e) {
            r.setError("Java 执行超时（超过 " + timeoutMs + " ms），已中断。请检查脚本是否存在死循环");
        } catch (Exception e) {
            Throwable cause = e instanceof InvocationTargetException ite && ite.getCause() != null ? ite.getCause() : e;
            r.setError("Java 执行失败：" + cause.getClass().getSimpleName()
                    + (cause.getMessage() == null ? "" : ": " + EngineValues.firstLine(cause.getMessage())));
        }
        r.setElapsedMs(EngineValues.msSince(start));
        return r;
    }

    private String compileErrorMsg(CompileException e, boolean wrapped) {
        String msg = EngineValues.firstLine(e.getMessage());
        if (wrapped && e.getLocation() != null && e.getLocation().getLineNumber() > 0) {
            int userLine = Math.max(1, e.getLocation().getLineNumber() - BODY_LINE_OFFSET);
            return "Java 编译失败（脚本第 " + userLine + " 行附近）：" + msg;
        }
        return "Java 编译失败：" + msg;
    }

    private String indent(String body) {
        if (body == null || body.isEmpty()) return "";
        StringBuilder b = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            b.append("        ").append(line).append('\n');
        }
        return b.toString();
    }
}
