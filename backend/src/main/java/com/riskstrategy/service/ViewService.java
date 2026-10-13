package com.riskstrategy.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.riskstrategy.dto.DatasourceHealthDTO;
import com.riskstrategy.dto.PageResult;
import com.riskstrategy.dto.ViewCreateRequest;
import com.riskstrategy.dto.ViewDegradeConfigDTO;
import com.riskstrategy.dto.ViewDetailDTO;
import com.riskstrategy.dto.ViewLineageDTO;
import com.riskstrategy.dto.ViewValidateRequest;
import com.riskstrategy.entity.DataSourceView;
import com.riskstrategy.entity.ViewDegradeConfig;
import com.riskstrategy.entity.ViewField;
import com.riskstrategy.entity.ViewInputVar;
import com.riskstrategy.mapper.DataSourceViewMapper;
import com.riskstrategy.mapper.ViewDegradeConfigMapper;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public class ViewService {

    private static final Set<String> SOURCE_TYPES = Set.of("MySQL", "CSV", "HTTP", "PMML");
    private static final Set<String> READ_MODES = Set.of("SINGLE", "LIST", "SCALAR");
    private static final Set<String> FIELD_TYPES = Set.of(
            "STRING", "INTEGER", "LONG", "DECIMAL", "BOOLEAN", "DATETIME", "ENUM", "JSON");
    private static final Pattern SCRIPT_VAR = Pattern.compile(
            "\\$\\{([a-zA-Z_][a-zA-Z0-9_]*)}|\\$([a-zA-Z_][a-zA-Z0-9_]*)");
    // 普通出参名限中英文/数字/下划线；HTTP 视图出参允许 $ 开头的 JSONPath（如 $、$.data.metrics[0].value）
    private static final Pattern FIELD_NAME_RE = Pattern.compile("^(\\$[A-Za-z0-9_\\[\\].]*|[\\p{L}_][\\p{L}\\p{N}_]{0,63})$");

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<List<ViewField>> FIELDS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<ViewInputVar>> INPUTS_TYPE = new TypeReference<>() {};

    private final DataSourceViewMapper viewMapper;
    private final ViewDegradeConfigMapper degradeConfigMapper;
    private final JdbcTemplate jdbcTemplate;
    private final HttpViewValidator httpViewValidator;
    private final ViewFileService viewFileService;

    public ViewService(DataSourceViewMapper viewMapper,
                       ViewDegradeConfigMapper degradeConfigMapper,
                       JdbcTemplate jdbcTemplate,
                       HttpViewValidator httpViewValidator,
                       ViewFileService viewFileService) {
        this.viewMapper = viewMapper;
        this.degradeConfigMapper = degradeConfigMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.httpViewValidator = httpViewValidator;
        this.viewFileService = viewFileService;
    }

    public PageResult<DataSourceView> list(int page, int size) {
        return list(page, size, null);
    }

    /** 视图列表；status 非空时按状态过滤（如 PUBLISHED，用于基础特征只选已发布视图）。 */
    public PageResult<DataSourceView> list(int page, int size, String status) {
        Page<DataSourceView> p = new Page<>(page, size);
        LambdaQueryWrapper<DataSourceView> qw = new LambdaQueryWrapper<>();
        // 列表不下发 script_content：CSV/PMML 视图内嵌了文件内容（最大 20MB），随列表返回会拖垮页面
        qw.select(DataSourceView.class, f -> !"scriptContent".equals(f.getProperty()));
        if (status != null && !status.isBlank()) {
            qw.eq(DataSourceView::getStatus, status.trim().toUpperCase(Locale.ROOT));
        }
        return PageResult.of(viewMapper.selectPage(p, qw));
    }

    /**
     * 发布态写保护：一旦视图发布上线，其口径即为线上生效版本，禁止再改动定义。
     * 如需修改，必须先走下线流程，避免线上口径被静默篡改。
     */
    private void assertEditable(DataSourceView view) {
        if ("PUBLISHED".equalsIgnoreCase(String.valueOf(view.getStatus()))) {
            throw new RuntimeException("该视图已发布上线（" + view.getCode()
                    + "），发布态口径不可编辑；如需修改请先在视图列表将其「下线」，再编辑重新发布");
        }
    }

    public ViewDetailDTO detail(Long id) {
        DataSourceView view = viewMapper.selectById(id);
        if (view == null) throw new RuntimeException("视图不存在: " + id);
        List<ViewField> fields = parseFields(view.getOutputFieldsJson());
        List<ViewInputVar> inputs = parseInputs(view.getInputVarsJson());
        return new ViewDetailDTO(view, fields, inputs);
    }

    /** 按视图编码取详情（基础特征绑定用，避免前端强依赖数字 id） */
    public ViewDetailDTO detailByCode(String code) {
        if (code == null || code.isBlank()) throw new RuntimeException("视图编码为空");
        DataSourceView view = viewMapper.selectByCode(code);
        if (view == null) throw new RuntimeException("视图不存在或已下线: " + code);
        return new ViewDetailDTO(view, parseFields(view.getOutputFieldsJson()), parseInputs(view.getInputVarsJson()));
    }

    // ---- JSON 解析 ----
    private List<ViewField> parseFields(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, FIELDS_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("出参 JSON 解析失败: " + e.getMessage());
        }
    }

    private List<ViewInputVar> parseInputs(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return JSON.readValue(json, INPUTS_TYPE);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("入参 JSON 解析失败: " + e.getMessage());
        }
    }

    private String toJson(Object obj) {
        try {
            return JSON.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("JSON 序列化失败: " + e.getMessage());
        }
    }

    // ---- 脚本占位符解析（后端权威） ----
    public List<String> parseScriptVars(String scriptContent) {
        if (scriptContent == null || scriptContent.isBlank()) return List.of();
        Matcher m = SCRIPT_VAR.matcher(scriptContent);
        LinkedHashSet<String> vars = new LinkedHashSet<>();
        while (m.find()) {
            String name = m.group(1) != null ? m.group(1) : m.group(2);
            vars.add(name);
        }
        return new ArrayList<>(vars);
    }

    /**
     * 视图入参变量解析：
     * CSV 视图的入参来自 scriptContent JSON 的 inputColumns（用户从文件表头选择作为过滤条件的列）；
     * PMML 视图来自 JSON 的 inputs 映射（模型字段 → $变量）；
     * 其余类型仍按 $变量名 占位符解析。
     * 注意：CSV/PMML 配置内嵌了文件内容，不能对整段脚本做 $ 占位符扫描（会把文件数据误识别为入参）。
     */
    private List<String> parseViewVars(String sourceType, String scriptContent) {
        boolean fileConfig = "CSV".equals(sourceType) || "PMML".equals(sourceType);
        if (fileConfig && scriptContent != null && !scriptContent.isBlank()) {
            try {
                JsonNode cfg = JSON.readTree(scriptContent);
                if (cfg.isObject() && (cfg.has("file") || cfg.has("content"))) {
                    if ("CSV".equals(sourceType)) {
                        List<String> out = new ArrayList<>();
                        JsonNode cols = cfg.get("inputColumns");
                        if (cols != null && cols.isArray()) {
                            for (JsonNode c : cols) {
                                String name = c.asText("");
                                if (!name.isBlank()) out.add(name);
                            }
                        }
                        return out;
                    }
                    List<String> out = new ArrayList<>();
                    JsonNode inputs = cfg.get("inputs");
                    if (inputs != null && inputs.isObject()) {
                        inputs.fields().forEachRemaining(en -> {
                            String var = en.getValue().asText("");
                            if (var.startsWith("$")) var = var.substring(1);
                            if (!var.isBlank()) out.add(var);
                        });
                    }
                    return out;
                }
            } catch (Exception ignored) {
                // 非 JSON 脚本回退到占位符解析
            }
        }
        return parseScriptVars(scriptContent);
    }

    public Map<String, Object> parseScript(String sourceType, String scriptContent) {
        List<String> vars = parseScriptVars(scriptContent);
        List<Map<String, String>> inputs = vars.stream()
                .map(v -> Map.of("varCode", v, "varType", "STRING"))
                .collect(Collectors.toList());
        return Map.of("inputs", inputs);
    }

    // ---- 脚本验证（只读试跑，不落库） ----
    public Map<String, Object> validate(ViewValidateRequest req) {
        Map<String, Object> resp = new LinkedHashMap<>();
        String script = req.getScriptContent();
        if (isBlank(script)) return validateFail(resp, "取数脚本为空，无法验证");
        List<ViewValidateRequest.OutputSpec> outputs = req.getOutputs();
        if (outputs == null || outputs.isEmpty()) return validateFail(resp, "请先至少登记 1 个出参后再验证");

        // HTTP 数据源：由 HttpViewValidator 发一次真实只读请求做取数核对
        if ("HTTP".equals(req.getSourceType())) {
            return httpViewValidator.validate(req);
        }
        // CSV / PMML：读取已上传的本地文件做解析核对
        if ("CSV".equals(req.getSourceType())) {
            return viewFileService.validateCsv(req);
        }
        if ("PMML".equals(req.getSourceType())) {
            return viewFileService.validatePmml(req);
        }
        if (!"MySQL".equals(req.getSourceType())) {
            return validateFail(resp, req.getSourceType()
                    + " 类型脚本的在线验证暂未开放（当前支持 MySQL / HTTP / CSV / PMML），请保存草稿后通过试算中心验证");
        }

        // 1. $name / ${name} 按出现顺序改写为 ? 预编译占位符（禁止拼接，防注入）
        List<String> varNames = new ArrayList<>();
        StringBuffer sqlBuf = new StringBuffer();
        Matcher m = SCRIPT_VAR.matcher(script);
        while (m.find()) {
            varNames.add(m.group(1) != null ? m.group(1) : m.group(2));
            m.appendReplacement(sqlBuf, "?");
        }
        m.appendTail(sqlBuf);
        String sql = sqlBuf.toString().trim();
        if (sql.endsWith(";")) sql = sql.substring(0, sql.length() - 1).trim();
        if (sql.contains(";")) return validateFail(resp, "验证脚本仅允许单条语句（检测到多余的分号）");
        String head = sql.toUpperCase(Locale.ROOT);
        if (!head.startsWith("SELECT") && !head.startsWith("WITH")) {
            return validateFail(resp, "仅支持 SELECT / WITH 查询语句的在线验证");
        }

        // 2. 按 varType 转换并绑定测试入参
        Map<String, ViewValidateRequest.InputValue> inputMap = new HashMap<>();
        if (req.getInputs() != null) {
            for (ViewValidateRequest.InputValue iv : req.getInputs()) {
                inputMap.put(iv.getVarCode(), iv);
            }
        }
        List<Object> params = new ArrayList<>();
        List<String> paramLiterals = new ArrayList<>();
        for (String name : varNames) {
            ViewValidateRequest.InputValue iv = inputMap.get(name);
            String raw = iv == null ? null : iv.getValue();
            boolean required = iv == null || iv.getRequired() == null || iv.getRequired();
            if (isBlank(raw)) {
                if (required) return validateFail(resp, "必填入参 " + name + " 未提供测试值");
                params.add(null);
                paramLiterals.add("NULL");
                continue;
            }
            try {
                Object converted = convertBindValue(raw, iv.getVarType());
                params.add(converted);
                paramLiterals.add(converted instanceof String ? "'" + converted + "'" : String.valueOf(converted));
            } catch (IllegalArgumentException e) {
                return validateFail(resp, e.getMessage());
            }
        }

        // 3. 只读执行：CONCUR_READ_ONLY + 超时 + 行数上限，仅取首行用于出参核对
        int maxRows = "LIST".equals(req.getReadMode()) ? 100 : 1;
        final String execSql = sql;
        long start = System.nanoTime();
        List<Map<String, Object>> resultRows = new ArrayList<>();
        try {
            jdbcTemplate.query(con -> {
                PreparedStatement ps = con.prepareStatement(
                        execSql, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                ps.setMaxRows(maxRows);
                ps.setQueryTimeout(5);
                for (int i = 0; i < params.size(); i++) {
                    ps.setObject(i + 1, params.get(i));
                }
                return ps;
            }, (ResultSetExtractor<Void>) rs -> {
                ResultSetMetaData md = rs.getMetaData();
                Map<String, String> cols = new HashMap<>();
                for (int i = 1; i <= md.getColumnCount(); i++) {
                    String label = md.getColumnLabel(i);
                    if (label != null) cols.put(label.toLowerCase(Locale.ROOT), label);
                }
                boolean hasRow = rs.next();
                for (ViewValidateRequest.OutputSpec o : outputs) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("fieldName", o.getFieldName());
                    row.put("fieldType", o.getFieldType());
                    String label = cols.get(o.getFieldName().toLowerCase(Locale.ROOT));
                    if (label == null) {
                        throw new SQLException("出参 " + o.getFieldName()
                                + " 未在查询结果列中返回，请检查 SQL 别名是否与出参名一致");
                    }
                    Object value = hasRow ? rs.getObject(label) : null;
                    boolean defaultUsed = false;
                    if (value == null && !isBlank(o.getDefaultValue())) {
                        value = o.getDefaultValue().trim();
                        defaultUsed = true;
                    }
                    row.put("value", value == null ? null : String.valueOf(value));
                    row.put("defaultValueUsed", defaultUsed);
                    resultRows.add(row);
                }
                return null;
            });
        } catch (DataAccessException e) {
            Throwable cause = e.getMostSpecificCause();
            String msg = cause != null && cause.getMessage() != null ? cause.getMessage() : e.getMessage();
            return validateFail(resp, "脚本执行失败：" + abbreviate(msg, 400));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        resp.put("success", true);
        resp.put("elapsedMs", elapsedMs);
        resp.put("results", resultRows);
        resp.put("resolvedScript", sql + "\n-- params: [" + String.join(", ", paramLiterals) + "]");
        return resp;
    }

    private Map<String, Object> validateFail(Map<String, Object> resp, String error) {
        resp.put("success", false);
        resp.put("error", error);
        resp.put("results", List.of());
        return resp;
    }

    private Object convertBindValue(String raw, String type) {
        String v = raw.trim();
        String t = type == null ? "STRING" : type;
        try {
            return switch (t) {
                case "INTEGER" -> Integer.valueOf(v);
                case "LONG" -> Long.valueOf(v);
                case "DECIMAL" -> new BigDecimal(v);
                case "BOOLEAN" -> {
                    if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                        throw new IllegalArgumentException("入参测试值 \"" + raw + "\" 必须是 true/false（类型 BOOLEAN）");
                    }
                    yield Boolean.valueOf(v);
                }
                default -> raw;
            };
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("入参测试值 \"" + raw + "\" 无法转换为类型 " + t);
        }
    }

    private String abbreviate(String s, int max) {
        if (s == null) return "未知错误";
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    // ---- 创建视图 ----
    @Transactional
    public DataSourceView create(ViewCreateRequest req) {
        if (isBlank(req.getCode())) {
            throw new RuntimeException("创建失败：视图编码不能为空，请填写后重试");
        }
        if (isBlank(req.getName())) {
            throw new RuntimeException("创建失败：视图名称不能为空，请填写后重试");
        }
        String code = req.getCode().trim();
        if (!code.matches("[a-z][a-z0-9_]{1,63}")) {
            throw new RuntimeException("创建失败：视图编码只能包含小写字母、数字和下划线，且以字母开头（2-64 位），例如 risk_user_info_view");
        }
        if (!SOURCE_TYPES.contains(req.getSourceType())) {
            throw new RuntimeException("创建失败：数据源类型只能是 MySQL / CSV / HTTP / PMML");
        }
        if (!READ_MODES.contains(req.getReadMode())) {
            throw new RuntimeException("创建失败：取数模式只能是 SINGLE / LIST / SCALAR");
        }
        if (viewMapper.existsByCode(code)) {
            throw new RuntimeException("创建失败：视图编码 '" + code + "' 已存在，请更换一个编码");
        }
        if (isBlank(req.getScriptContent())) {
            throw new RuntimeException("创建失败：取数脚本不能为空");
        }

        List<String> scriptVars = parseViewVars(req.getSourceType(), req.getScriptContent());
        Set<String> scriptVarSet = new LinkedHashSet<>(scriptVars);

        List<ViewCreateRequest.InputVarDTO> reqInputs = req.getInputs() != null ? req.getInputs() : List.of();
        Set<String> reqVarCodes = reqInputs.stream()
                .map(ViewCreateRequest.InputVarDTO::getVarCode)
                .collect(Collectors.toSet());

        for (String rv : scriptVarSet) {
            if (!reqVarCodes.contains(rv)) {
                throw new RuntimeException("创建失败：脚本中存在未登记入参 " + rv);
            }
        }
        for (String rv : reqVarCodes) {
            if (!scriptVarSet.contains(rv)) {
                throw new RuntimeException("创建失败：入参 " + rv + " 未在脚本中引用");
            }
        }

        List<ViewCreateRequest.OutputFieldDTO> outputs = req.getOutputs();
        if (outputs == null || outputs.isEmpty()) {
            throw new RuntimeException("创建失败：至少需要登记 1 个出参");
        }
        Set<String> fieldNames = new HashSet<>();
        for (ViewCreateRequest.OutputFieldDTO o : outputs) {
            if (isBlank(o.getFieldName())) {
                throw new RuntimeException("创建失败：出参字段名不能为空");
            }
            if (!FIELD_NAME_RE.matcher(o.getFieldName()).matches()) {
                throw new RuntimeException("创建失败：出参名称 " + o.getFieldName()
                        + " 格式不合法（限中英文/数字/下划线且以字母或下划线开头；HTTP 视图出参可填 $ 开头的 JSONPath，如 $ 或 $.data，最长64位）");
            }
            if (!fieldNames.add(o.getFieldName())) {
                throw new RuntimeException("创建失败：出参名称重复 " + o.getFieldName());
            }
            if (!FIELD_TYPES.contains(o.getFieldType())) {
                throw new RuntimeException("创建失败：出参类型 " + o.getFieldType() + " 不合法（可选：" + FIELD_TYPES + "）");
            }
            validateDefaultValue(o.getDefaultValue(), o.getFieldType(), o.getFieldName());
        }

        for (ViewCreateRequest.InputVarDTO inp : reqInputs) {
            if (!FIELD_TYPES.contains(inp.getVarType())) {
                throw new RuntimeException("创建失败：入参类型 " + inp.getVarType() + " 不合法");
            }
            validateDefaultValue(inp.getDefaultValue(), inp.getVarType(), inp.getVarCode());
        }

        // 构建入参 JSON
        Map<String, ViewCreateRequest.InputVarDTO> reqInputMap = reqInputs.stream()
                .collect(Collectors.toMap(ViewCreateRequest.InputVarDTO::getVarCode, i -> i));
        List<ViewInputVar> inputVars = new ArrayList<>();
        int sort = 0;
        for (String varCode : scriptVars) {
            ViewCreateRequest.InputVarDTO dto = reqInputMap.get(varCode);
            ViewInputVar v = new ViewInputVar();
            v.setVarCode(varCode);
            v.setVarType(dto.getVarType() != null ? dto.getVarType() : "STRING");
            v.setRequired(dto.getRequired());
            v.setDefaultValue(dto.getDefaultValue());
            v.setSort(sort++);
            inputVars.add(v);
        }

        // 构建出参 JSON
        List<ViewField> viewFields = new ArrayList<>();
        for (int i = 0; i < outputs.size(); i++) {
            ViewCreateRequest.OutputFieldDTO o = outputs.get(i);
            ViewField f = new ViewField();
            f.setFieldName(o.getFieldName());
            f.setFieldType(o.getFieldType());
            f.setDescription(o.getDescription());
            f.setDefaultValue(o.getDefaultValue());
            f.setCategory("OUTPUT");
            f.setIsPrimary(0);
            f.setSort(o.getSort() != null ? o.getSort() : i);
            viewFields.add(f);
        }

        DataSourceView view = new DataSourceView();
        view.setCode(code);
        view.setName(req.getName().trim());
        view.setDescription(req.getDescription());
        view.setSourceType(req.getSourceType());
        view.setReadMode(req.getReadMode());
        view.setOwner(req.getOwner());
        view.setBusinessDomain(req.getBusinessDomain());
        view.setScriptContent(req.getScriptContent());
        view.setInputVarsJson(toJson(inputVars));
        view.setOutputFieldsJson(toJson(viewFields));
        view.setVersion("v1");
        view.setStatus("DRAFT");
        view.setRefCount(0);
        view.setFieldCount(outputs.size());
        view.setPiiCount(0);
        LocalDateTime now = LocalDateTime.now();
        view.setCreatedAt(now);
        view.setUpdatedAt(now);
        view.setDeleted(0L);
        viewMapper.insert(view);

        return view;
    }

    private void validateDefaultValue(String defaultValue, String fieldType, String name) {
        if (defaultValue == null || defaultValue.isBlank()) return;
        try {
            switch (fieldType) {
                case "INTEGER" -> Integer.parseInt(defaultValue);
                case "LONG" -> Long.parseLong(defaultValue);
                case "DECIMAL" -> new BigDecimal(defaultValue);
                case "BOOLEAN" -> {
                    if (!"true".equalsIgnoreCase(defaultValue) && !"false".equalsIgnoreCase(defaultValue)) {
                        throw new RuntimeException("创建失败：" + name + " 的默认值必须是 true/false");
                    }
                }
            }
        } catch (NumberFormatException e) {
            throw new RuntimeException("创建失败：" + name + " 的默认值与类型 " + fieldType + " 不匹配");
        }
    }

    /** 编辑态通用校验：默认值需与声明类型匹配（DATETIME/ENUM/JSON 及历史非标类型不做格式限制）。 */
    private void validateFieldDefaultValue(String defaultValue, String fieldType, String name) {
        if (defaultValue == null || defaultValue.isBlank()) return;
        String v = defaultValue.trim();
        try {
            switch (fieldType == null ? "STRING" : fieldType) {
                case "INTEGER" -> Integer.parseInt(v);
                case "LONG" -> Long.parseLong(v);
                case "DECIMAL" -> new BigDecimal(v);
                case "BOOLEAN" -> {
                    if (!"true".equalsIgnoreCase(v) && !"false".equalsIgnoreCase(v)) {
                        throw new RuntimeException("保存失败：" + name + " 的默认值必须是 true/false");
                    }
                }
                default -> { }
            }
        } catch (NumberFormatException e) {
            throw new RuntimeException("保存失败：" + name + " 的默认值与类型 " + fieldType + " 不匹配");
        }
    }

    @Transactional
    public void publish(Long id) {
        DataSourceView view = viewMapper.selectById(id);
        if (view == null) {
            throw new RuntimeException("发布失败：视图不存在或已下线（id=" + id + "），请刷新列表后重试");
        }
        int n = viewMapper.publishById(id);
        if (n == 0) {
            throw new RuntimeException("发布失败：视图状态已变化，请刷新列表后重试");
        }
    }

    @Transactional
    public void offline(Long id) {
        DataSourceView view = viewMapper.selectById(id);
        if (view == null) {
            throw new RuntimeException("下线失败：视图不存在或已下线（id=" + id + "），请刷新列表后重试");
        }
        int n = viewMapper.offlineById(id);
        if (n == 0) {
            throw new RuntimeException("下线失败：视图状态已变化，请刷新列表后重试");
        }
    }

    public DataSourceView update(Long id, DataSourceView patch) {
        DataSourceView existing = viewMapper.selectById(id);
        if (existing == null) throw new RuntimeException("视图不存在: " + id);
        assertEditable(existing);
        if (patch.getName() != null) existing.setName(patch.getName());
        if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
        // 状态流转不经过编辑接口（只能走 publish / offline），避免绕过发布态保护把线上视图改回草稿
        if (patch.getVersion() != null) existing.setVersion(patch.getVersion());
        if (patch.getOwner() != null) existing.setOwner(patch.getOwner());
        if (patch.getSourceType() != null) existing.setSourceType(patch.getSourceType());
        if (patch.getReadMode() != null) existing.setReadMode(patch.getReadMode());
        if (patch.getBusinessDomain() != null) existing.setBusinessDomain(patch.getBusinessDomain());
        if (patch.getScriptContent() != null) {
            existing.setScriptContent(patch.getScriptContent());
            // 脚本变更后按新占位符重建入参定义，保留同名变量已登记的类型/必填/默认值
            Map<String, ViewInputVar> oldInputs = parseInputs(existing.getInputVarsJson()).stream()
                    .collect(Collectors.toMap(ViewInputVar::getVarCode, x -> x, (a, b) -> a));
            String mergedSourceType = patch.getSourceType() != null ? patch.getSourceType() : existing.getSourceType();
            List<ViewInputVar> rebuilt = new ArrayList<>();
            int sort = 0;
            for (String varCode : parseViewVars(mergedSourceType, patch.getScriptContent())) {
                ViewInputVar v = oldInputs.getOrDefault(varCode, new ViewInputVar());
                v.setVarCode(varCode);
                if (v.getVarType() == null) v.setVarType("STRING");
                if (v.getRequired() == null) v.setRequired(true);
                v.setSort(sort++);
                rebuilt.add(v);
            }
            existing.setInputVarsJson(toJson(rebuilt));
        }
        existing.setUpdatedAt(LocalDateTime.now());
        viewMapper.updateById(existing);
        return existing;
    }

    @Transactional
    public List<ViewField> updateFields(Long viewId, List<ViewField> fields) {
        DataSourceView view = viewMapper.selectById(viewId);
        if (view == null) throw new RuntimeException("视图不存在: " + viewId);
        assertEditable(view);

        List<ViewField> existing = parseFields(view.getOutputFieldsJson());
        Map<String, ViewField> existingByFieldName = new LinkedHashMap<>();
        for (ViewField f : existing) {
            existingByFieldName.put(f.getFieldName(), f);
        }

        for (ViewField patch : fields) {
            if (isBlank(patch.getFieldName())) {
                throw new RuntimeException("保存失败：出参字段名不能为空");
            }
            String fieldName = patch.getFieldName().trim();
            ViewField target = existingByFieldName.get(fieldName);
            if (target == null) {
                // 编辑态新增出参：校验名称格式 / 类型 / 与已有出参重名
                if (!FIELD_NAME_RE.matcher(fieldName).matches()) {
                    throw new RuntimeException("保存失败：出参名称 " + fieldName
                            + " 格式不合法（限中英文/数字/下划线且以字母或下划线开头；HTTP 视图出参可填 $ 开头的 JSONPath，如 $ 或 $.data，最长64位）");
                }
                boolean dup = existingByFieldName.keySet().stream()
                        .anyMatch(n -> n.equalsIgnoreCase(fieldName));
                if (dup) {
                    throw new RuntimeException("保存失败：出参名称重复 " + fieldName);
                }
                ViewField added = new ViewField();
                added.setViewId(viewId);
                added.setFieldName(fieldName);
                added.setFieldType(patch.getFieldType() != null && FIELD_TYPES.contains(patch.getFieldType())
                        ? patch.getFieldType() : "STRING");
                added.setDescription(patch.getDescription());
                added.setDefaultValue(patch.getDefaultValue());
                added.setCategory("OUTPUT");
                added.setIsPrimary(0);
                added.setSort(patch.getSort() != null ? patch.getSort() : existingByFieldName.size());
                validateFieldDefaultValue(added.getDefaultValue(), added.getFieldType(), fieldName);
                existingByFieldName.put(fieldName, added);
                continue;
            }
            // 已有出参：直接覆盖可编辑属性（null/空串表示清空默认值/描述，故不走非空跳过）
            if (patch.getFieldType() != null) target.setFieldType(patch.getFieldType());
            target.setDescription(patch.getDescription());
            target.setDefaultValue(patch.getDefaultValue());
            if (patch.getMasking() != null) target.setMasking(patch.getMasking());
            if (patch.getPiiLevel() != null) target.setPiiLevel(patch.getPiiLevel());
            if (patch.getSort() != null) target.setSort(patch.getSort());
            validateFieldDefaultValue(target.getDefaultValue(), target.getFieldType(), fieldName);
        }

        List<ViewField> merged = new ArrayList<>(existingByFieldName.values());
        view.setOutputFieldsJson(toJson(merged));
        view.setFieldCount(merged.size());
        view.setUpdatedAt(LocalDateTime.now());
        viewMapper.updateById(view);
        return merged;
    }

    /**
     * 编辑态保存入参元信息（脚本变量名由 PUT /{id} 按脚本重建，这里按 varCode 匹配覆盖）。
     * 当前前端只开放默认值编辑；varType / required 如传入也一并更新。
     */
    @Transactional
    public List<ViewInputVar> updateInputs(Long viewId, List<ViewInputVar> inputs) {
        DataSourceView view = viewMapper.selectById(viewId);
        if (view == null) throw new RuntimeException("视图不存在: " + viewId);
        assertEditable(view);
        if (inputs == null || inputs.isEmpty()) return parseInputs(view.getInputVarsJson());

        Map<String, ViewInputVar> byCode = new LinkedHashMap<>();
        for (ViewInputVar v : parseInputs(view.getInputVarsJson())) {
            byCode.put(v.getVarCode(), v);
        }
        for (ViewInputVar patch : inputs) {
            if (isBlank(patch.getVarCode())) continue;
            ViewInputVar target = byCode.get(patch.getVarCode().trim());
            if (target == null) continue; // 脚本中已删除的变量忽略
            target.setDefaultValue(patch.getDefaultValue());
            if (patch.getVarType() != null) target.setVarType(patch.getVarType());
            if (patch.getRequired() != null) target.setRequired(patch.getRequired());
            validateFieldDefaultValue(target.getDefaultValue(), target.getVarType(), target.getVarCode());
        }
        List<ViewInputVar> merged = new ArrayList<>(byCode.values());
        view.setInputVarsJson(toJson(merged));
        view.setUpdatedAt(LocalDateTime.now());
        viewMapper.updateById(view);
        return merged;
    }

    // ---- gap: datasource-health ----
    public List<DatasourceHealthDTO> getDatasourceHealth(String range) {
        return List.of(
                new DatasourceHealthDTO("ds_risk_mysql · 风控主库", "42%", 12, "正常"),
                new DatasourceHealthDTO("ds_order_mysql · 订单库", "38%", 8, "正常"),
                new DatasourceHealthDTO("ds_ext_blacklist · 外部黑名单", "65%", 45, "慢"),
                new DatasourceHealthDTO("ds_device_risk · 设备指纹", "21%", 15, "正常")
        );
    }

    // ---- gap: lineage ----
    public ViewLineageDTO getLineage(Long id) {
        DataSourceView view = viewMapper.selectById(id);
        if (view == null) throw new RuntimeException("视图不存在: " + id);
        ViewLineageDTO dto = new ViewLineageDTO();
        dto.setTotalRefs(view.getRefCount() != null ? view.getRefCount() : 0);
        dto.setBaseFeatures(6);
        dto.setDerivedFeatures(9);
        dto.setStrategies(3);
        dto.setTree("view." + view.getCode() + " → 基础特征 → 衍生特征 → 策略");
        return dto;
    }

    // ---- gap: degrade-config ----
    public ViewDegradeConfigDTO getDegradeConfig(Long viewId) {
        ViewDegradeConfig cfg = degradeConfigMapper.selectByViewId(viewId);
        if (cfg == null) return null;
        return toDTO(cfg);
    }

    public ViewDegradeConfigDTO updateDegradeConfig(Long viewId, ViewDegradeConfigDTO dto) {
        ViewDegradeConfig existing = degradeConfigMapper.selectByViewId(viewId);
        if (existing != null) {
            if (dto.getDegradeMode() != null) existing.setDegradeMode(dto.getDegradeMode());
            if (dto.getOnFallback() != null) existing.setOnFallback(dto.getOnFallback());
            if (dto.getCacheL1() != null) existing.setCacheL1(dto.getCacheL1());
            if (dto.getCacheL2() != null) existing.setCacheL2(dto.getCacheL2());
            if (dto.getCircuitBreaker() != null) {
                existing.setCbEnabled(dto.getCircuitBreaker().getEnabled() ? 1 : 0);
                existing.setCbErrorRate(dto.getCircuitBreaker().getErrorRate());
            }
            existing.setUpdatedAt(LocalDateTime.now());
            degradeConfigMapper.updateById(existing);
            return toDTO(existing);
        }
        ViewDegradeConfig cfg = new ViewDegradeConfig();
        cfg.setViewId(viewId);
        cfg.setDegradeMode(dto.getDegradeMode());
        cfg.setOnFallback(dto.getOnFallback());
        cfg.setCacheL1(dto.getCacheL1());
        cfg.setCacheL2(dto.getCacheL2());
        if (dto.getCircuitBreaker() != null) {
            cfg.setCbEnabled(dto.getCircuitBreaker().getEnabled() ? 1 : 0);
            cfg.setCbErrorRate(dto.getCircuitBreaker().getErrorRate());
        }
        cfg.setUpdatedAt(LocalDateTime.now());
        degradeConfigMapper.insert(cfg);
        return toDTO(cfg);
    }

    private ViewDegradeConfigDTO toDTO(ViewDegradeConfig cfg) {
        ViewDegradeConfigDTO dto = new ViewDegradeConfigDTO();
        dto.setDegradeMode(cfg.getDegradeMode());
        dto.setOnFallback(cfg.getOnFallback());
        dto.setCacheL1(cfg.getCacheL1());
        dto.setCacheL2(cfg.getCacheL2());
        ViewDegradeConfigDTO.CircuitBreakerConfig cb = new ViewDegradeConfigDTO.CircuitBreakerConfig();
        cb.setEnabled(cfg.getCbEnabled() != null && cfg.getCbEnabled() == 1);
        cb.setErrorRate(cfg.getCbErrorRate());
        dto.setCircuitBreaker(cb);
        return dto;
    }

    private boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
