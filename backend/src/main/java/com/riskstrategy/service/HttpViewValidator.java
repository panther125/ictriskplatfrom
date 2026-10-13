package com.riskstrategy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskstrategy.dto.ViewValidateRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP 视图脚本在线验证器（只读取数接口做一次真实试跑，不落库）。
 *
 * <p>脚本形态为前端表单产出的请求配置 JSON，例：
 * <pre>{@code
 * { "method":"POST", "url":"https://api.xxx.com/risk/blacklist?biz=$biz",
 *   "query":{}, "headers":{"Accept":"application/json"}, "body":{"mobile":"$mobile"},
 *   "auth":{"type":"BEARER","token":"..."},
 *   "timeout":{"connectMs":300,"readMs":800},
 *   "retry":{"count":0,"idempotent":false},
 *   "successCriteria":{"httpStatus":[200,204],"businessCodePath":"$.code","businessCodeValue":"0000"},
 *   "failurePolicy":{"transport":"DEFAULT_VALUE","business":"NULL","parse":"ALERT_NULL"} }
 * }</pre>
 *
 * <p>失败处理分三层，对应表单里的「失败语义」三分类：
 * <ul>
 *   <li>传输失败（连不上 / 超时 / 状态码不在白名单）：验证阶段一律 success=false，暴露问题；</li>
 *   <li>业务失败（状态码正常但业务码不匹配）：按 failurePolicy.business 降级，仅 ABORT 判失败；</li>
 *   <li>解析失败（出参在响应中取不到）：按 failurePolicy.parse 降级，仅 ABORT 判失败。</li>
 * </ul>
 */
@Service
public class HttpViewValidator {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final Pattern VAR = Pattern.compile(
            "\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)}|\\$([a-zA-Z_][a-zA-Z0-9_]*)");
    private static final Pattern FUNC = Pattern.compile("\\$\\{\\s*([a-zA-Z]+)\\s*\\(\\s*\\)\\s*}");

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");
    private static final Set<String> SENSITIVE_HEADERS =
            Set.of("authorization", "x-api-key", "x-signature", "x-access-key", "proxy-authorization", "signature");

    private static final int MAX_PREVIEW = 1200;
    private static final int MAX_BODY_BYTES = 256 * 1024;
    private static final long MIN_TIMEOUT_MS = 100L;
    private static final long MAX_CONNECT_MS = 3000L;
    private static final long MAX_READ_MS = 8000L;

    private final boolean blockPrivateHost;

    public HttpViewValidator(@Value("${risk.http-validator.block-private-host:false}") boolean blockPrivateHost) {
        this.blockPrivateHost = blockPrivateHost;
    }

    // ------------------------------------------------------------------ 入口

    public Map<String, Object> validate(ViewValidateRequest req) {
        Map<String, Object> resp = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        String raw = req.getScriptContent();
        if (raw == null || raw.isBlank()) {
            return fail(resp, Map.of(), "取数脚本为空，无法验证");
        }

        JsonNode spec;
        String resolvedScript;
        try {
            resolvedScript = bindAndResolve(raw, req.getInputs());
            spec = JSON.readTree(resolvedScript);
            if (spec == null || !spec.isObject()) {
                throw new JsonProcessingException("顶层必须是 JSON 对象") {};
            }
        } catch (JsonProcessingException e) {
            return fail(resp, Map.of(), "HTTP 请求配置 JSON 解析失败：变量替换后不是合法 JSON（" + e.getOriginalMessage()
                    + "）。请在表单模式下填写，或点「查看 JSON」检查格式");
        } catch (IllegalArgumentException e) {
            return fail(resp, Map.of(), e.getMessage());
        }

        String method = spec.path("method").asText("GET").toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            return fail(resp, Map.of(), "不支持的请求方法 " + method + "（可选：" + METHODS + "）");
        }

        URI uri;
        Map<String, String> headers;
        String bodyText = "";
        try {
            String url = spec.path("url").asText("").trim();
            if (url.isEmpty()) return fail(resp, Map.of(), "请先在表单中填写请求 URL 再执行验证");
            if (!url.matches("(?i)^https?://.+")) {
                return fail(resp, Map.of(), "请求 URL 必须以 http:// 或 https:// 开头");
            }
            Map<String, String> query = toFlatMap(spec.get("query") != null ? spec.get("query") : spec.get("params"));
            uri = composeUrl(url, query);
            assertSafe(uri);

            headers = toFlatMap(spec.get("headers"));
            String contentType = headers.entrySet().stream()
                    .filter(e -> e.getKey().equalsIgnoreCase("Content-Type"))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(spec.path("contentType").asText("application/json"));
            headers.remove("Content-Type");
            headers.remove("content-type");

            bodyText = BODY_METHODS.contains(method) ? buildBody(spec.get("body"), contentType) : "";
            if (!bodyText.isEmpty()) headers.put("Content-Type", contentType);
            applyAuth(headers, bodyText, method, uri, spec.get("auth"), warnings);
        } catch (IllegalArgumentException e) {
            return fail(resp, Map.of(), e.getMessage());
        } catch (Exception e) {
            return fail(resp, Map.of(), "HTTP 请求配置项不合法：" + e.getMessage());
        }

        long connectMs = clamp(spec.path("timeout").path("connectMs").asLong(300), MIN_TIMEOUT_MS, MAX_CONNECT_MS);
        long readMs = clamp(spec.path("timeout").path("readMs").asLong(800), MIN_TIMEOUT_MS, MAX_READ_MS);
        int retry = (int) spec.path("retry").path("count").asLong(0);
        if (retry > 0) {
            warnings.add("配置了重试 " + retry + " 次，在线验证只发 1 次请求；重试将在运行时按幂等配置生效");
        }

        List<String> statusList = new ArrayList<>();
        JsonNode statusNode = spec.path("successCriteria").path("httpStatus");
        if (statusNode.isArray()) {
            for (JsonNode n : statusNode) statusList.add(String.valueOf(n.asInt()));
        } else if (!statusNode.isMissingNode()) {
            String rawStatuses = statusNode.asText("200,204");
            for (String s : rawStatuses.split("[,，\\s]+")) {
                if (!s.isEmpty()) statusList.add(s);
            }
        }
        if (statusList.isEmpty()) statusList.add("200");

        String businessPath = spec.path("successCriteria").path("businessCodePath").asText("").trim();
        String businessValue = spec.path("successCriteria").path("businessCodeValue").asText("").trim();
        String transportPolicy = spec.path("failurePolicy").path("transport").asText("DEFAULT_VALUE");
        String businessPolicy = spec.path("failurePolicy").path("business").asText("NULL");
        String parsePolicy = spec.path("failurePolicy").path("parse").asText("ALERT_NULL");

        // ---- 发起一次真实请求（不跟进重定向，避免绕过主机白名单） ----
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("requestLine", method + " " + uri);
        meta.put("resolvedScript", renderRequestPreview(method, uri, headers, bodyText));

        HttpClient client = HttpClient.newBuilder()
                // 强制 HTTP/1.1：JDK 默认 HTTP/2 会对明文 URL 先发 h2c Upgrade 握手，
                // Node/Vite 等服务器会把 Upgrade 请求交给 WebSocket 处理而不响应，导致读取超时
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofMillis(connectMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(readMs));
        headers.forEach(builder::header);
        builder.method(method, bodyText.isEmpty()
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(bodyText, StandardCharsets.UTF_8));

        HttpResponse<String> response;
        long start = System.nanoTime();
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (java.net.ConnectException e) {
            meta.put("httpStatus", "N/A");
            String detail = isBlank(e.getMessage()) ? "目标端口未开放或网络不可达" : e.getMessage();
            return fail(resp, meta, "连接失败：" + abbreviate(detail, 300)
                    + "（运行时将按 transport 策略「" + policyLabel(transportPolicy) + "」处理）");
        } catch (java.io.UncheckedIOException | java.io.IOException e) {
            meta.put("httpStatus", "N/A");
            String reason = e instanceof java.net.http.HttpTimeoutException ? "读取超时"
                    : (e instanceof java.net.http.HttpConnectTimeoutException ? "连接超时" : e.getMessage());
            if (isBlank(reason)) reason = e.getClass().getSimpleName();
            return fail(resp, meta, "请求未成功返回：" + abbreviate(reason, 300)
                    + "（运行时将按 transport 策略「" + policyLabel(transportPolicy) + "」处理）");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fail(resp, meta, "验证被中断，请重试");
        } finally {
            client.close();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        String body = response.body() == null ? "" : response.body();
        if (body.getBytes(StandardCharsets.UTF_8).length > MAX_BODY_BYTES) {
            return fail(resp, meta, "响应体超过 " + (MAX_BODY_BYTES / 1024) + "KB，在线验证仅用于样例取数核对");
        }
        meta.put("httpStatus", String.valueOf(response.statusCode()));
        meta.put("elapsedMs", String.valueOf(elapsedMs));
        meta.put("responsePreview", abbreviate(body.replaceAll("\\s+", " ").trim(), MAX_PREVIEW));

        // ---- 传输层：状态码白名单 ----
        if (!statusList.contains(String.valueOf(response.statusCode()))) {
            String hint = response.statusCode() >= 300 && response.statusCode() < 400
                    ? "目标返回重定向（验证不跟进跳转），请确认 URL 是否为最终地址"
                    : "目标返回状态码 " + response.statusCode() + "，不在成功白名单 " + statusList + " 内";
            return fail(resp, meta, hint + "（运行时将按 transport 策略「" + policyLabel(transportPolicy) + "」处理）");
        }
        meta.put("requestLine", method + " " + uri);

        JsonNode root;
        try {
            root = JSON.readTree(body);
        } catch (JsonProcessingException e) {
            return fail(resp, meta, "响应体不是合法 JSON，无法取出参：" + abbreviate(body, 200));
        }
        meta.put("responseJson", "ok");

        // ---- 业务失败判定 ----
        boolean businessOk = true;
        if (!businessPath.isEmpty() && businessValue.isEmpty()) {
            warnings.add("已配置业务码 JSONPath 但未填期望值，本次跳过业务码判定");
        }
        if (!businessPath.isEmpty() && !businessValue.isEmpty()) {
            resolvedBusinessLabel:
            {
                JsonNode codeNode = evalPath(root, businessPath);
                String actual = codeNode == null || codeNode.isNull() ? "" : codeNode.asText();
                businessOk = businessValue.equals(actual);
                if (!businessOk) {
                    String msg = "业务码判定失败：" + businessPath + " = " + (actual.isEmpty() ? "缺失" : actual)
                            + "，期望 " + businessValue;
                    if ("ABORT".equalsIgnoreCase(businessPolicy)) {
                        return fail(resp, meta, msg + "，且业务失败策略为「中断决策并拒绝」");
                    }
                    warnings.add(msg + "，已按 business 策略「" + policyLabel(businessPolicy) + "」降级");
                    meta.put("policyApplied", "BUSINESS:" + businessPolicy);
                }
            }
        }

        Target target = locateTarget(root, req.getReadMode(), warnings);

        // ---- 出参取值（三类失败：解析） ----
        List<Map<String, Object>> results = new ArrayList<>();
        List<ViewValidateRequest.OutputSpec> outputs =
                req.getOutputs() == null ? List.of() : req.getOutputs();
        for (ViewValidateRequest.OutputSpec o : outputs) {
            String fieldName = o.getFieldName() == null ? "" : o.getFieldName().trim();
            Located found = businessOk ? locateField(target, root, fieldName) : null;
            String path = found == null ? null : found.path();
            boolean degraded = true;
            String value = null;

            if (!businessOk) {
                // 业务失败：一律降级（ABORT 已在上面返回）
                degraded = true;
            } else if (found == null || found.node() == null || found.node().isNull()) {
                degraded = true;
                warnings.add("出参 " + fieldName + " 未在响应中取到，已按 parse 策略「" + policyLabel(parsePolicy) + "」处理");
                if ("ABORT".equalsIgnoreCase(parsePolicy)) {
                    return fail(resp, meta, "出参 " + fieldName + " 未在响应中取到，且解析失败策略为「中断决策并拒绝」");
                }
            } else {
                JsonNode fn = found.node();
                value = fn.isValueNode() ? fn.asText() : fn.toString();
                degraded = false;
            }

            String defaultValue = o.getDefaultValue() == null ? "" : o.getDefaultValue().trim();
            boolean defaultUsed = false;
            if (degraded) {
                if ("DEFAULT_VALUE".equalsIgnoreCase(businessPolicy) && !businessOk && !defaultValue.isEmpty()) {
                    value = defaultValue;
                    defaultUsed = true;
                } else if ("DEFAULT_VALUE".equalsIgnoreCase(parsePolicy) && !defaultValue.isEmpty()) {
                    value = defaultValue;
                    defaultUsed = true;
                } else {
                    value = null; // NULL / ALERT_NULL：视为无数据
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("fieldName", fieldName);
            row.put("fieldType", o.getFieldType());
            row.put("value", value);
            row.put("defaultValueUsed", defaultUsed);
            row.put("jsonPath", path);
            results.add(row);
        }

        resp.put("success", true);
        resp.put("elapsedMs", elapsedMs);
        resp.put("httpStatus", response.statusCode());
        resp.put("requestLine", meta.get("requestLine"));
        resp.put("resolvedScript", meta.get("resolvedScript"));
        resp.put("responsePreview", meta.get("responsePreview"));
        resp.put("policyApplied", meta.getOrDefault("policyApplied", "NONE"));
        resp.put("warnings", warnings);
        resp.put("results", results);
        return resp;
    }

    // ------------------------------------------------------------------ 变量绑定

    /** 内置函数 → 入参绑定 → 占位符替换（替换结果仍需是合法 JSON） */
    private String bindAndResolve(String script, List<ViewValidateRequest.InputValue> inputs) {
        String text = applyBuiltins(script);
        Map<String, ViewValidateRequest.InputValue> inputMap = new HashMap<>();
        if (inputs != null) {
            for (ViewValidateRequest.InputValue iv : inputs) inputMap.put(iv.getVarCode(), iv);
        }
        Map<String, String> bound = new LinkedHashMap<>();
        Matcher scan = VAR.matcher(text);
        while (scan.find()) {
            String name = scan.group(1) != null ? scan.group(1) : scan.group(2);
            ViewValidateRequest.InputValue iv = inputMap.get(name);
            boolean required = iv == null || iv.getRequired() == null || iv.getRequired();
            String raw = iv == null ? null : iv.getValue();
            if (raw == null || raw.trim().isEmpty()) {
                if (required) throw new IllegalArgumentException("必填入参 " + name + " 未提供测试值");
                bound.put(name, "");
                continue;
            }
            bound.put(name, raw.trim());
        }
        StringBuffer sb = new StringBuffer();
        Matcher m = VAR.matcher(text);
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            m.appendReplacement(sb, Matcher.quoteReplacement(jsonSafe(bound.get(name))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String applyBuiltins(String text) {
        StringBuffer sb = new StringBuffer();
        Matcher m = FUNC.matcher(text);
        while (m.find()) {
            String fn = m.group(1);
            String value = switch (fn) {
                case "timestamp", "timestampSec" -> String.valueOf(System.currentTimeMillis() / 1000);
                case "timestampMs", "now" -> String.valueOf(System.currentTimeMillis());
                case "uuid" -> UUID.randomUUID().toString();
                case "nonce" -> String.valueOf(UUID.randomUUID().toString().replace("-", "").substring(0, 16));
                default -> null;
            };
            if (value == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(value));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String jsonSafe(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ 请求构造

    private Map<String, String> toFlatMap(JsonNode node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node == null || node.isNull() || node.isMissingNode()) return out;
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            JsonNode v = node.get(k);
            if (v == null) continue;
            out.put(k, v.isValueNode() ? v.asText() : v.toString());
        }
        return out;
    }

    private URI composeUrl(String rawUrl, Map<String, String> query) throws Exception {
        String url = rawUrl.split("#", 2)[0];
        String base = url;
        String existing = "";
        int q = base.indexOf('?');
        if (q >= 0) {
            existing = base.substring(q + 1);
            base = base.substring(0, q);
        }
        StringBuilder qs = new StringBuilder(existing);
        for (Map.Entry<String, String> e : query.entrySet()) {
            if (e.getKey().isBlank()) continue;
            if (qs.length() > 0) qs.append('&');
            qs.append(encode(e.getKey().trim())).append('=').append(encode(e.getValue() == null ? "" : e.getValue()));
        }
        String full = qs.length() > 0 ? base + "?" + qs : base;
        try {
            return new URI(full);
        } catch (URISyntaxException e) {
            // 兜底：让 JDK 对非法字符做百分号编码
            URI loose = new URI(full.replace(" ", "%20"));
            if (loose.getHost() == null) throw new URISyntaxException(full, "无法解析主机名");
            return loose;
        }
    }

    private String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** SSRF 兜底：只允许公网 http/https；内网地址默认放行（平台多部署在内网），可由配置收紧。 */
    private void assertSafe(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new IllegalArgumentException("只支持 http / https 协议");
        }
        if (uri.getHost() == null || uri.getHost().isBlank()) {
            throw new IllegalArgumentException("URL 缺少主机名：" + uri);
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("URL 中不允许携带用户名密码，请改用「鉴权方式」配置");
        }
        if (!blockPrivateHost) return;
        try {
            InetAddress[] all = InetAddress.getAllByName(uri.getHost());
            for (InetAddress a : all) {
                if (a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isAnyLocalAddress()
                        || isUniqueLocalV6(a)) {
                    throw new IllegalArgumentException(
                            "目标主机 " + uri.getHost() + " 解析到内网地址 " + a.getHostAddress() + "，已被在线验证的访问控制拦截");
                }
            }
        } catch (java.net.UnknownHostException e) {
            throw new IllegalArgumentException("域名解析失败：" + uri.getHost());
        }
    }

    private boolean isUniqueLocalV6(InetAddress a) {
        byte[] b = a.getAddress();
        return b.length == 16 && (b[0] & 0xfe) == 0xfc;
    }

    private String buildBody(JsonNode bodyNode, String contentType) {
        if (bodyNode == null || bodyNode.isNull() || bodyNode.isMissingNode()) return "";
        String ct = contentType.toLowerCase(Locale.ROOT);
        if (bodyNode.isTextual()) return bodyNode.asText();
        if (bodyNode.isContainerNode()) {
            if (ct.contains("x-www-form-urlencoded")) {
                StringBuilder sb = new StringBuilder();
                Iterator<String> it = bodyNode.fieldNames();
                while (it.hasNext()) {
                    String k = it.next();
                    if (sb.length() > 0) sb.append('&');
                    sb.append(encode(k)).append('=').append(encode(bodyNode.get(k).asText()));
                }
                return sb.toString();
            }
            return bodyNode.toString();
        }
        return bodyNode.asText();
    }

    private void applyAuth(Map<String, String> headers, String body, String method, URI uri,
                           JsonNode auth, List<String> warnings) {
        if (auth == null || auth.isNull() || auth.isMissingNode()) return;
        String type = auth.isTextual() ? auth.asText() : auth.path("type").asText("");
        type = type.toUpperCase(Locale.ROOT);
        if (type.isEmpty() || "NONE".equals(type)) return;
        switch (type) {
            case "BASIC" -> {
                String user = auth.path("username").asText("");
                String pass = auth.path("password").asText("");
                if (user.isEmpty() && pass.isEmpty()) {
                    warnings.add("鉴权方式为 BASIC 但未填写账号密码");
                    return;
                }
                String token = Base64.getEncoder().encodeToString((user + ":" + pass).getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + token);
            }
            case "BEARER" -> {
                String token = auth.path("token").asText("");
                if (token.isEmpty()) {
                    warnings.add("鉴权方式为 BEARER 但未填写 Token");
                    return;
                }
                headers.put("Authorization", "Bearer " + token);
            }
            case "AK_SK_HMAC" -> {
                String ak = auth.path("accessKey").asText("");
                String sk = auth.path("secretKey").asText("");
                String alg = auth.path("algorithm").asText("HmacSHA256");
                if (ak.isEmpty() || sk.isEmpty()) {
                    warnings.add("鉴权方式为 AK/SK 但未填全 AccessKey / SecretKey");
                    return;
                }
                long ts = System.currentTimeMillis() / 1000;
                String nonce = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
                String queryPart = uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery();
                String canonical = method + "\n" + uri.getRawPath() + queryPart + "\n" + ts + "\n" + nonce + "\n"
                        + sha256Hex(body);
                String sign = hmacHex(sk, canonical, alg);
                headers.put("X-Access-Key", ak);
                headers.put("X-Timestamp", String.valueOf(ts));
                headers.put("X-Nonce", nonce);
                headers.put("X-Signature", sign);
            }
            case "CUSTOM_SIGN" -> {
                String expr = auth.path("script").asText("");
                String evaluated = evalSignExpr(expr, auth);
                if (evaluated == null) {
                    warnings.add("自定义签名表达式「" + expr + "」在线验证无法求值，已跳过签名头（运行时以引擎实现为准）");
                    return;
                }
                headers.put("X-Signature", evaluated);
            }
            default -> warnings.add("未知鉴权类型 " + type + "，在线验证未附加鉴权头");
        }
    }

    /** 有限支持：md5(...) / sha256(...) / hmacSha256(...) 的拼接表达式，操作数可为字面量或 auth 中的同名字段 */
    private String evalSignExpr(String expr, JsonNode auth) {
        if (expr == null || expr.isBlank()) return null;
        Matcher m = Pattern.compile("(?i)^\\s*(md5|sha256|hmacSha256)\\s*\\((.*)\\)\\s*$").matcher(expr);
        if (!m.find()) return null;
        String alg = m.group(1).toLowerCase(Locale.ROOT);
        StringBuilder payload = new StringBuilder();
        for (String rawPart : m.group(2).split("\\+")) {
            String part = rawPart.trim();
            if (part.isEmpty()) continue;
            if ((part.startsWith("'") && part.endsWith("'")) || (part.startsWith("\"") && part.endsWith("\""))) {
                payload.append(part, 1, part.length() - 1);
            } else {
                JsonNode v = auth.get(part);
                payload.append(v == null ? part : v.asText());
            }
        }
        if ("md5".equals(alg)) return md5Hex(payload.toString());
        if ("sha256".equals(alg)) return sha256Hex(payload.toString());
        String sk = auth.path("secretKey").asText(auth.path("secret").asText(""));
        if (sk.isEmpty()) return null;
        return hmacHex(sk, payload.toString(), "HmacSHA256");
    }

    private String md5Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String sha256Hex(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String hmacHex(String secret, String data, String alg) {
        try {
            String macAlg = "HmacSHA256";
            if (alg != null && alg.toLowerCase(Locale.ROOT).contains("1")) macAlg = "HmacSHA1";
            else if (alg != null && alg.toLowerCase(Locale.ROOT).contains("512")) macAlg = "HmacSHA512";
            Mac mac = Mac.getInstance(macAlg);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), macAlg));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private String renderRequestPreview(String method, URI uri, Map<String, String> headers, String body) {
        StringBuilder sb = new StringBuilder(method).append(' ').append(uri).append('\n');
        for (Map.Entry<String, String> e : headers.entrySet()) {
            String v = SENSITIVE_HEADERS.contains(e.getKey().toLowerCase(Locale.ROOT)) ? "******（已掩码）" : e.getValue();
            sb.append(e.getKey()).append(": ").append(v).append('\n');
        }
        if (!body.isEmpty()) {
            sb.append('\n').append(abbreviate(body.replaceAll("\\s+", " ").trim(), 600));
        }
        return sb.toString().trim();
    }

    // ------------------------------------------------------------------ 响应取值

    /** 按取数模式定位数据节点：统一取到「单条记录」再取出参，并记住其 JSONPath 前缀 */
    private Target locateTarget(JsonNode root, String readMode, List<String> warnings) {
        if (root == null || root.isNull()) return new Target(null, "$.");
        String mode = readMode == null ? "SINGLE" : readMode.toUpperCase(Locale.ROOT);
        JsonNode data = root.isObject() ? root.get("data") : null;

        if ("SCALAR".equals(mode)) {
            if (root.isValueNode()) return new Target(root, "$");
            return data != null ? new Target(data, "$.data.") : new Target(root, "$.");
        }
        if (data != null && !data.isNull()) {
            if (data.isArray()) {
                if ("LIST".equals(mode)) {
                    warnings.add("LIST 模式仅取返回数组的首条记录作为核对样本");
                }
                return new Target(data.size() > 0 ? data.get(0) : null, "$.data[0].");
            }
            return new Target(data, "$.data.");
        }
        if (root.isArray()) return new Target(root.size() > 0 ? root.get(0) : null, "$[0].");
        return new Target(root, "$.");
    }

    private record Target(JsonNode node, String path) {}

    /** 出参取值：出参名以 $ 开头时按 JSONPath 对响应根直接取值（$ = 整段响应体，如 $.data.metrics[0].value）；否则先精确命中，再兼容驼峰/下划线与大小写差异，最后做 4 层以内递归兜底 */
    private Located locateField(Target target, JsonNode root, String fieldName) {
        if (fieldName == null || fieldName.isEmpty()) return null;
        if (fieldName.startsWith("$")) {
            JsonNode node = evalPath(root, fieldName);
            return node == null ? null : new Located(node, fieldName);
        }
        if (target == null || target.node() == null) return null;
        String prefix = target.path();
        JsonNode hit = matchKey(target.node(), fieldName);
        if (hit != null) return new Located(hit, prefix + fieldName);
        Located deep = searchDeep(target.node(), fieldName, prefix, 0);
        if (deep != null) return deep;
        JsonNode rootHit = matchKey(root, fieldName);
        if (rootHit != null) return new Located(rootHit, "$." + fieldName);
        return searchDeep(root, fieldName, "$.", 0);
    }

    private JsonNode matchKey(JsonNode node, String fieldName) {
        if (node == null || !node.isObject()) return null;
        JsonNode direct = node.get(fieldName);
        if (direct != null && !direct.isNull()) return direct;
        for (String candidate : variants(fieldName)) {
            JsonNode v = node.get(candidate);
            if (v != null && !v.isNull()) return v;
        }
        Iterator<String> it = node.fieldNames();
        while (it.hasNext()) {
            String k = it.next();
            if (k.equalsIgnoreCase(fieldName) || variants(fieldName).contains(k)) {
                JsonNode v = node.get(k);
                if (v != null && !v.isNull()) return v;
            }
        }
        return null;
    }

    private List<String> variants(String name) {
        List<String> out = new ArrayList<>();
        out.add(toCamel(name));
        out.add(toSnake(name));
        out.add(name.toLowerCase(Locale.ROOT));
        out.add(name.toUpperCase(Locale.ROOT));
        return out;
    }

    private Located searchDeep(JsonNode node, String fieldName, String path, int depth) {
        if (node == null || depth > 4) return null;
        if (node.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                JsonNode v = e.getValue();
                String p = path + e.getKey();
                if (keyMatches(e.getKey(), fieldName) && v != null && !v.isNull() && v.isValueNode()) {
                    return new Located(v, p);
                }
                Located sub = searchDeep(v, fieldName, p + ".", depth + 1);
                if (sub != null) return sub;
            }
        } else if (node instanceof ArrayNode arr) {
            for (int i = 0; i < arr.size(); i++) {
                Located sub = searchDeep(arr.get(i), fieldName, path + "[" + i + "].", depth + 1);
                if (sub != null) return sub;
            }
        }
        return null;
    }

    private boolean keyMatches(String key, String fieldName) {
        if (key.equalsIgnoreCase(fieldName)) return true;
        return variants(fieldName).stream().anyMatch(v -> v.equalsIgnoreCase(key));
    }

    private static String toCamel(String s) {
        StringBuilder sb = new StringBuilder();
        boolean up = false;
        for (char c : s.toCharArray()) {
            if (c == '_' || c == '-') { up = true; continue; }
            sb.append(up ? Character.toUpperCase(c) : c);
            up = false;
        }
        return sb.toString();
    }

    private static String toSnake(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (Character.isUpperCase(c)) sb.append('_').append(Character.toLowerCase(c));
            else sb.append(c);
        }
        return sb.toString();
    }

    /** 极简 JSONPath：仅支持 $.a.b 与 $.a[0].b */
    private JsonNode evalPath(JsonNode root, String path) {
        if (root == null || path == null || path.isBlank()) return null;
        String p = path.trim();
        if (p.startsWith("$")) p = p.substring(1);
        JsonNode cur = root;
        for (String raw : p.split("\\.")) {
            if (raw.isEmpty()) continue;
            String key = raw;
            int idx = -1;
            Matcher m = Pattern.compile("^([A-Za-z0-9_\\-]+)\\[(\\d+)]$").matcher(raw);
            if (m.find()) {
                key = m.group(1);
                idx = Integer.parseInt(m.group(2));
            }
            if (cur == null) return null;
            cur = key.startsWith("$") ? cur : cur.get(key);
            if (idx >= 0 && cur != null && cur.isArray()) cur = cur.get(idx);
        }
        return cur;
    }

    // ------------------------------------------------------------------ 工具

    private record Located(JsonNode node, String path) {}

    private long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }

    private String policyLabel(String policy) {
        if (policy == null) return "未配置";
        return switch (policy.toUpperCase(Locale.ROOT)) {
            case "DEFAULT_VALUE" -> "使用降级默认值";
            case "NULL" -> "视为无数据（null）";
            case "ALERT_NULL" -> "告警并置为 null";
            case "ABORT" -> "中断决策并拒绝";
            default -> policy;
        };
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }

    private Map<String, Object> fail(Map<String, Object> resp, Map<String, String> meta, String error) {
        resp.put("success", false);
        resp.put("error", error);
        resp.put("results", List.of());
        resp.putIfAbsent("policyApplied", "NONE");
        meta.forEach(resp::putIfAbsent);
        return resp;
    }
}
