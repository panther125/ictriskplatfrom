package com.riskstrategy.engine;

import com.riskstrategy.dto.ResolvedInputDTO;
import com.riskstrategy.entity.Feature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 衍生特征执行门面：按加工方式分发到 SQL / Java / Python / OP 引擎。 */
@Component
public class DerivedExecutor {

    private final JavaFeatureEngine javaEngine;
    private final SqlFeatureEngine sqlEngine;
    private final OpTreeEngine opEngine;
    private final PythonRunnerClient pythonClient;
    private final long defaultTimeoutMs;

    public DerivedExecutor(JavaFeatureEngine javaEngine, SqlFeatureEngine sqlEngine, OpTreeEngine opEngine,
                           PythonRunnerClient pythonClient,
                           @Value("${risk.derived.timeout-ms:300}") long defaultTimeoutMs) {
        this.javaEngine = javaEngine;
        this.sqlEngine = sqlEngine;
        this.opEngine = opEngine;
        this.pythonClient = pythonClient;
        this.defaultTimeoutMs = defaultTimeoutMs;
    }

    /** 执行衍生特征表达式；refValues 为 @@ 引用的实测值（键为特征编码） */
    public EngineResult execute(Feature f, List<ResolvedInputDTO> inputs, Map<String, Object> testValues,
                                Map<String, Object> refValues) {
        String type = f.getComputeType() == null ? "SQL" : f.getComputeType().trim().toUpperCase(Locale.ROOT);
        long timeout = f.getTimeoutMs() != null && f.getTimeoutMs() > 0 ? f.getTimeoutMs() : defaultTimeoutMs;
        try {
            return switch (type) {
                case "JAVA" -> javaEngine.execute(f.getExpr(), inputs, testValues, refValues, timeout);
                case "SQL" -> sqlEngine.execute(f.getExpr(), inputs, testValues, refValues, timeout);
                case "OP" -> opEngine.execute(f.getExpr(), refValues, testValues, timeout);
                case "PYTHON" -> pythonClient.execute(f.getExpr(), inputs, testValues, refValues, timeout);
                case "REF" -> EngineResult.fail("REF",
                        "REF（直接引用）只能用于基础特征；衍生特征请选择 SQL / Java / Python / OP 加工方式");
                default -> EngineResult.fail(type,
                        "未知的加工方式：" + f.getComputeType() + "（支持 SQL / Java / Python / OP）");
            };
        } catch (Exception e) {
            return EngineResult.fail(type, "执行失败：" + EngineValues.firstLine(e.getMessage()));
        }
    }

    /** 保存时的表达式预校验（目前只对 OP 做结构校验）；返回 null 表示通过 */
    public String validateExpression(String computeType, String expr) {
        String type = computeType == null ? "" : computeType.trim().toUpperCase(Locale.ROOT);
        if ("OP".equals(type)) return opEngine.validate(expr);
        return null;
    }
}
