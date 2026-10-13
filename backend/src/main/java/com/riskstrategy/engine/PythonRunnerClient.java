package com.riskstrategy.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.riskstrategy.dto.ResolvedInputDTO;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 衍生特征 Python 引擎：调用独立 runner 服务（python-runner/runner.py）执行 apply。 */
@Component
public class PythonRunnerClient {

    /** 只做数据加工，不导入系统 / 网络 / 文件相关模块，也不允许动态求值 */
    private static final Pattern DANGEROUS = Pattern.compile(
            "(\\bimport\\s+(os|sys|subprocess|socket|shutil|ctypes|pathlib|importlib|multiprocessing|signal|pickle|marshal|builtins)\\b"
                    + "|\\bfrom\\s+(os|sys|subprocess|socket|shutil|ctypes|pathlib|importlib|multiprocessing|signal|pickle|marshal|builtins)\\b"
                    + "|__import__|\\beval\\s*\\(|\\bexec\\s*\\(|\\bcompile\\s*\\(|\\bopen\\s*\\(|\\bglobals\\s*\\(|\\blocals\\s*\\("
                    + "|\\bgetattr\\s*\\(|\\bsetattr\\s*\\(|\\bdelattr\\s*\\(|__builtins__|__class__|__subclasses__|__globals__|__mro__)");

    private final ObjectMapper mapper;
    private final HttpClient http;
    private final String baseUrl;

    public PythonRunnerClient(ObjectMapper mapper,
                              @Value("${risk.python-runner.url:http://127.0.0.1:8090}") String baseUrl) {
        this.mapper = mapper;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // uvicorn 不支持 h2c 升级，必须固定 HTTP/1.1，否则请求会被 400 拒绝
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public EngineResult execute(String expr, List<ResolvedInputDTO> inputs, Map<String, Object> testValues,
                                Map<String, Object> refValues, long timeoutMs) {
        long start = System.nanoTime();
        EngineResult r = new EngineResult();
        r.setEngine("PYTHON");

        String code = expr == null ? "" : expr;
        Matcher danger = DANGEROUS.matcher(code);
        if (danger.find()) {
            r.setExecutedExpr(code);
            r.setError("脚本包含被禁止的调用「" + danger.group().trim()
                    + "」：衍生特征脚本只做数据加工，不允许导入系统 / 网络 / 文件相关模块，也不允许动态求值");
            r.setElapsedMs(EngineValues.msSince(start));
            return r;
        }

        String substituted = ExprLiterals.replace(code, ExprLiterals.Lang.PYTHON, refValues, testValues);
        r.setExecutedExpr(substituted);

        Map<String, Object> inValues = new LinkedHashMap<>();
        for (ResolvedInputDTO iv : inputs) {
            inValues.put(iv.getName(), EngineValues.coerce(testValues.get(iv.getName())));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("script", substituted);
        body.put("inputs", inValues);
        body.put("timeoutMs", timeoutMs);

        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/run"))
                    .timeout(Duration.ofMillis(timeoutMs + 5000))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                r.setError("Python 执行服务返回 HTTP " + resp.statusCode() + "：" + EngineValues.firstLine(resp.body()));
                r.setElapsedMs(EngineValues.msSince(start));
                return r;
            }
            JsonNode node = mapper.readTree(resp.body());
            r.setSuccess(node.path("success").asBoolean(false));
            if (r.isSuccess()) {
                JsonNode value = node.get("value");
                r.setValue(value == null || value.isNull() ? null : mapper.convertValue(value, Object.class));
                r.setValueType(node.path("valueType").asText("STRING"));
            } else {
                r.setError(node.path("error").asText("Python 执行失败"));
            }
            String out = node.path("stdout").asText("");
            if (!out.isBlank()) r.getWarnings().add("脚本输出：" + out.trim());
        } catch (java.net.ConnectException e) {
            r.setError("Python 执行服务未启动（" + baseUrl + "）。请先启动：python python-runner/runner.py");
        } catch (HttpTimeoutException e) {
            r.setError("Python 执行服务响应超时（超过 " + (timeoutMs + 5000) + " ms）");
        } catch (Exception e) {
            r.setError("调用 Python 执行服务失败：" + EngineValues.firstLine(e.getMessage()));
        }
        r.setElapsedMs(EngineValues.msSince(start));
        return r;
    }
}
