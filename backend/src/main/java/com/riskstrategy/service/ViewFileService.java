package com.riskstrategy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.riskstrategy.dto.ViewUploadResultDTO;
import com.riskstrategy.dto.ViewValidateRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * CSV / PMML 视图文件的解析与校验：
 * 文件内容内嵌进视图配置（scriptContent.content，不会因服务器清理文件而丢失），
 * 上传时做格式（扩展名 / 文本可解析）与大小（≤20MB）校验，自动识别列/字段、核对出参。
 */
@Service
public class ViewFileService {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern INT_RE = Pattern.compile("-?(0|[1-9]\\d*)");
    private static final Pattern DEC_RE = Pattern.compile("-?(0|[1-9]\\d*)(\\.\\d+)?");
    private static final Pattern DATE_RE = Pattern.compile(
            "\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}([ T]\\d{1,2}:\\d{2}(:\\d{2})?)?");
    private static final Set<String> PMML_NON_MODEL_ELEMENTS = Set.of(
            "PMML", "Header", "DataDictionary", "TransformationDictionary",
            "MiningBuildTask", "Extension", "LocalTransformations");
    private static final int TYPE_INFER_SAMPLE = 50;
    private static final int PREVIEW_DATA_ROWS = 5;
    private static final long MAX_FILE_BYTES = 20L * 1024 * 1024;

    private final Path uploadRoot;

    public ViewFileService(@Value("${risk.upload-dir:uploads}") String uploadDir) {
        this.uploadRoot = Paths.get(uploadDir).toAbsolutePath().normalize();
    }

    // ------------------------------------------------------------------ 上传

    public ViewUploadResultDTO upload(MultipartFile file, String sourceType) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择要上传的文件");
        }
        if (!"CSV".equals(sourceType) && !"PMML".equals(sourceType)) {
            throw new IllegalArgumentException("仅 CSV / PMML 类型支持文件上传");
        }
        if (file.getSize() > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件过大（上限 20MB），请压缩或拆分后重新上传");
        }
        String raw = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().replace('\\', '/');
        int slash = raw.lastIndexOf('/');
        String originalName = slash >= 0 ? raw.substring(slash + 1) : raw;
        if (originalName.isBlank()) originalName = "upload";
        checkExtension(originalName, sourceType);

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (Exception e) {
            throw new IllegalArgumentException("读取上传文件失败：" + e.getMessage());
        }

        ViewUploadResultDTO dto = new ViewUploadResultDTO();
        dto.setFileName(originalName);
        dto.setSizeBytes(bytes.length);
        dto.setSourceType(sourceType);
        dto.setWarnings(new ArrayList<>());

        if ("CSV".equals(sourceType)) {
            parseCsvInto(dto, bytes);
        } else {
            Document doc = parseXml(bytes);
            parsePmmlDoc(dto, doc, decodeText(bytes, detectCharset(bytes)));
        }
        return dto;
    }

    private void checkExtension(String name, String sourceType) {
        String lower = name.toLowerCase(Locale.ROOT);
        if ("CSV".equals(sourceType) && !lower.endsWith(".csv")) {
            throw new IllegalArgumentException("CSV 视图仅支持 .csv 文件（当前文件：" + name + "）");
        }
        if ("PMML".equals(sourceType) && !lower.endsWith(".pmml") && !lower.endsWith(".xml")) {
            throw new IllegalArgumentException("PMML 视图仅支持 .pmml / .xml 文件（当前文件：" + name + "）");
        }
    }

    // ------------------------------------------------------------------ CSV 解析

    private void parseCsvInto(ViewUploadResultDTO dto, byte[] bytes) {
        CsvData data = parseCsvBytes(bytes);
        List<String> warnings = dto.getWarnings();

        ViewUploadResultDTO.CsvMeta meta = new ViewUploadResultDTO.CsvMeta();
        meta.setDelimiter(String.valueOf(data.delimiter));
        meta.setEncoding(data.encoding);
        meta.setHasHeader(data.hasHeader);
        meta.setRowCount(data.rows.size());
        meta.setColumnCount(data.columnNames.size());
        meta.setPreviewRows(data.previewRows());
        dto.setCsv(meta);

        if (!data.hasHeader) {
            warnings.add("未检测到表头行，列名按 c1..c" + data.columnNames.size() + " 生成；如首行确为表头，请检查文件编码后重新上传");
        }

        List<ViewUploadResultDTO.ParsedField> fields = new ArrayList<>();
        for (int i = 0; i < data.columnNames.size(); i++) {
            ViewUploadResultDTO.ParsedField f = new ViewUploadResultDTO.ParsedField();
            String rawHeader = data.columnNames.get(i) == null ? "" : data.columnNames.get(i).trim();
            String name = sanitizeFieldName(data.columnNames.get(i), i);
            f.setFieldName(name);
            f.setFieldType(inferType(data.columnValues(i)));
            // 列名被规范化时保留原始表头，便于对照文件
            f.setDescription(name.equals(rawHeader) || rawHeader.isEmpty() ? "" : "原表头：" + rawHeader);
            if (!name.equals(data.columnNames.get(i))) {
                warnings.add("列名「" + data.columnNames.get(i) + "」含特殊字符，已规范化为 " + name);
            }
            fields.add(f);
        }
        dto.setFields(fields);
        dto.setInputs(List.of());

        ObjectNode cfg = JSON.createObjectNode();
        cfg.put("fileName", dto.getFileName());
        cfg.put("sizeBytes", dto.getSizeBytes());
        // 文件内容内嵌进视图配置：不再依赖服务器磁盘文件，避免被清理后无法验证
        cfg.put("content", data.content());
        cfg.put("delimiter", String.valueOf(data.delimiter));
        cfg.put("encoding", data.encoding);
        cfg.put("hasHeader", data.hasHeader);
        // 过滤入参列（用户从表头选择）；验证时按这些列的入参值过滤数据行
        cfg.putArray("inputColumns");
        dto.setScriptContent(pretty(cfg));
    }

    private record CsvData(String encoding, char delimiter, boolean hasHeader,
                           List<String> columnNames, List<List<String>> rows, String content) {
        List<List<String>> previewRows() {
            List<List<String>> out = new ArrayList<>();
            out.add(columnNames);
            for (int i = 0; i < Math.min(PREVIEW_DATA_ROWS, rows.size()); i++) {
                out.add(rows.get(i));
            }
            return out;
        }

        List<String> columnValues(int index) {
            List<String> out = new ArrayList<>();
            for (List<String> row : rows) {
                if (index < row.size()) out.add(row.get(index));
            }
            return out;
        }
    }

    private CsvData parseCsvBytes(byte[] bytes) {
        rejectBinary(bytes);
        Charset cs = detectCharset(bytes);
        return parseCsvText(decodeText(bytes, cs), cs.name());
    }

    /** 保护：非 UTF-16 内容中出现 NUL 字节视为二进制文件，直接拒收（文本 CSV 不应含空字节）。 */
    private void rejectBinary(byte[] bytes) {
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF;
            if ((b0 == 0xFF && b1 == 0xFE) || (b0 == 0xFE && b1 == 0xFF)) return;
        }
        int n = Math.min(bytes.length, 8192);
        for (int i = 0; i < n; i++) {
            if (bytes[i] == 0) {
                throw new IllegalArgumentException("文件疑似二进制内容（检测到空字节），请确认上传的是文本 CSV 文件");
            }
        }
    }

    /** 字节 → 文本（按已识别编码解码），并剥离开头的 BOM。 */
    private String decodeText(byte[] bytes, Charset cs) {
        byte[] content = bytes;
        if (bytes.length >= 3 && (bytes[0] & 0xFF) == 0xEF && (bytes[1] & 0xFF) == 0xBB && (bytes[2] & 0xFF) == 0xBF) {
            content = Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        String text = new String(content, cs);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1);
        return text;
    }

    /** 解析已解码的 CSV 文本：上传解码后与验证读内嵌 content 时走同一套算法。 */
    private CsvData parseCsvText(String text, String encoding) {
        List<String> lines = splitLines(text);
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("CSV 文件为空，无有效内容");
        }
        char delimiter = detectDelimiter(lines);
        boolean hasHeader = detectHeader(lines, delimiter);

        List<List<String>> all = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() && all.isEmpty()) continue;
            if (line.isBlank()) continue;
            all.add(splitCsvLine(line, delimiter));
        }
        if (all.isEmpty()) {
            throw new IllegalArgumentException("CSV 文件为空，无有效内容");
        }

        List<String> names;
        List<List<String>> rows;
        if (hasHeader) {
            names = all.get(0);
            rows = all.subList(1, all.size());
        } else {
            int cols = all.stream().mapToInt(List::size).max().orElse(0);
            names = new ArrayList<>();
            for (int i = 0; i < cols; i++) names.add("c" + (i + 1));
            rows = all;
        }
        if (names.size() < 1) {
            throw new IllegalArgumentException("CSV 未解析出任何列");
        }
        return new CsvData(encoding, delimiter, hasHeader, names, new ArrayList<>(rows), text);
    }

    /** UTF-8（严格）优先，失败回退 GB18030；识别 UTF-16 BOM。 */
    private Charset detectCharset(byte[] bytes) {
        if (bytes.length >= 2) {
            int b0 = bytes[0] & 0xFF, b1 = bytes[1] & 0xFF;
            if (b0 == 0xFF && b1 == 0xFE) return StandardCharsets.UTF_16LE;
            if (b0 == 0xFE && b1 == 0xFF) return StandardCharsets.UTF_16BE;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            decoder.decode(ByteBuffer.wrap(bytes));
            return StandardCharsets.UTF_8;
        } catch (CharacterCodingException e) {
            // Windows Excel 导出的中文 CSV 常见 GBK/GB18030
            return Charset.forName("GB18030");
        }
    }

    private List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') inQuotes = !inQuotes;
            if (!inQuotes && (c == '\n' || c == '\r')) {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                lines.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    /** 在 , ; \t | 中选列数最多且各行列数最一致的分隔符。 */
    private char detectDelimiter(List<String> lines) {
        char[] candidates = {',', ';', '\t', '|'};
        char best = ','; int bestScore = -1;
        List<String> sample = lines.stream().filter(l -> !l.isBlank()).limit(5).toList();
        for (char cand : candidates) {
            List<Integer> sizes = new ArrayList<>();
            for (String line : sample) sizes.add(splitCsvLine(line, cand).size());
            int cols = sizes.isEmpty() ? 0 : Collections.max(sizes);
            if (cols < 2) continue;
            long distinct = sizes.stream().distinct().count();
            int score = cols * 10 - (int) distinct;
            if (score > bestScore) { bestScore = score; best = cand; }
        }
        return best;
    }

    private List<String> splitCsvLine(String line, char delimiter) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') { cur.append('"'); i++; }
                    else inQuotes = false;
                } else cur.append(c);
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == delimiter) {
                out.add(cur.toString().trim());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        out.add(cur.toString().trim());
        return out;
    }

    private boolean detectHeader(List<String> lines, char delimiter) {
        List<String> nonBlank = lines.stream().filter(l -> !l.isBlank()).toList();
        if (nonBlank.isEmpty()) return true;
        List<String> first = splitCsvLine(nonBlank.get(0), delimiter);
        boolean anyNumeric1 = first.stream().anyMatch(this::looksNumeric);
        if (nonBlank.size() == 1) return !anyNumeric1;
        List<String> second = splitCsvLine(nonBlank.get(1), delimiter);
        boolean anyNumeric2 = second.stream().anyMatch(this::looksNumeric);
        return !anyNumeric1 && (anyNumeric2 || allUniqueNonEmpty(first));
    }

    private boolean allUniqueNonEmpty(List<String> cells) {
        Set<String> seen = new HashSet<>();
        for (String c : cells) {
            if (c.isBlank() || !seen.add(c)) return false;
        }
        return !cells.isEmpty();
    }

    private boolean looksNumeric(String s) {
        String v = s == null ? "" : s.trim();
        return DEC_RE.matcher(v).matches() && !(v.length() > 1 && v.startsWith("0"))
                && !v.isEmpty();
    }

    private String sanitizeFieldName(String raw, int index) {
        String name = raw == null ? "" : raw.trim();
        // 保留中英文（\p{L}）、数字与下划线，其余字符（括号 / 百分号 / 空格等）折为下划线
        name = name.replaceAll("[^\\p{L}\\p{N}_]", "_");
        name = name.replaceAll("_+", "_");
        name = name.replaceAll("_+$", "");
        if (name.isBlank()) name = "c" + (index + 1);
        if (!name.matches("[\\p{L}_].*")) name = "f_" + name;
        if (name.length() > 64) name = name.substring(0, 64);
        return name;
    }

    private String inferType(List<String> values) {
        boolean intOk = true, longOk = true, decOk = true, boolOk = true, dateOk = true;
        boolean seen = false;
        for (String v : values) {
            String s = v == null ? "" : v.trim();
            if (s.isEmpty()) continue;
            seen = true;
            if (intOk) {
                boolean ok = INT_RE.matcher(s).matches() && fitsInt(s);
                if (!ok) intOk = false;
            }
            if (longOk) {
                boolean ok = INT_RE.matcher(s).matches() && fitsLong(s);
                if (!ok) longOk = false;
            }
            if (decOk && !DEC_RE.matcher(s).matches()) decOk = false;
            if (boolOk && !s.equalsIgnoreCase("true") && !s.equalsIgnoreCase("false")) boolOk = false;
            if (dateOk && !DATE_RE.matcher(s).matches()) dateOk = false;
        }
        if (!seen) return "STRING";
        if (intOk) return "INTEGER";
        if (longOk) return "LONG";
        if (decOk) return "DECIMAL";
        if (boolOk) return "BOOLEAN";
        if (dateOk) return "DATETIME";
        return "STRING";
    }

    private boolean fitsInt(String s) {
        try { Integer.parseInt(s); return true; } catch (NumberFormatException e) { return false; }
    }

    private boolean fitsLong(String s) {
        try { Long.parseLong(s); return true; } catch (NumberFormatException e) { return false; }
    }

    // ------------------------------------------------------------------ PMML 解析

    private void parsePmmlDoc(ViewUploadResultDTO dto, Document doc, String contentText) {
        Element root = doc.getDocumentElement();
        List<String> warnings = dto.getWarnings();

        ViewUploadResultDTO.PmmlMeta meta = new ViewUploadResultDTO.PmmlMeta();
        meta.setPmmlVersion(root.getAttribute("version"));

        Element modelEl = findModelElement(root);
        if (modelEl == null) {
            warnings.add("未在文件中识别到模型节点（如 RegressionModel / TreeModel），请检查模型是否完整");
        } else {
            meta.setAlgorithm(modelEl.getLocalName() != null ? modelEl.getLocalName() : modelEl.getTagName());
            meta.setModelName(modelEl.getAttribute("modelName"));
            meta.setFunctionName(modelEl.getAttribute("functionName"));
        }
        dto.setPmml(meta);

        // DataDictionary → 模型输入字段
        Map<String, Element> dataFields = new LinkedHashMap<>();
        Element dictEl = firstChild(root, "DataDictionary");
        if (dictEl != null) {
            for (Element e : children(dictEl, "DataField")) {
                String name = e.getAttribute("name");
                if (!name.isBlank()) dataFields.put(name, e);
            }
        }

        // MiningSchema 的 target + Output/OutputField → 模型输出字段
        Set<String> targetNames = new LinkedHashSet<>();
        if (modelEl != null) {
            Element schema = firstChild(modelEl, "MiningSchema");
            if (schema != null) {
                for (Element mf : children(schema, "MiningField")) {
                    if ("target".equalsIgnoreCase(mf.getAttribute("usageType"))) {
                        String name = mf.getAttribute("name");
                        if (!name.isBlank()) targetNames.add(name);
                    }
                }
            }
        }
        List<Element> outputFields = new ArrayList<>();
        if (modelEl != null) {
            Element outputEl = firstChild(modelEl, "Output");
            if (outputEl != null) outputFields.addAll(children(outputEl, "OutputField"));
        }

        List<ViewUploadResultDTO.ParsedField> fields = new ArrayList<>();
        Set<String> usedNames = new HashSet<>();
        if (!outputFields.isEmpty()) {
            for (Element of : outputFields) {
                String name = of.getAttribute("name");
                if (name.isBlank()) continue;
                fields.add(parsedField(uniqueName(name, usedNames),
                        mapPmmlType(of.getAttribute("optype"), of.getAttribute("dataType"))));
            }
        } else if (!targetNames.isEmpty()) {
            for (String name : targetNames) {
                Element df = dataFields.get(name);
                fields.add(parsedField(uniqueName(name, usedNames),
                        df == null ? "STRING" : mapPmmlType(df.getAttribute("optype"), df.getAttribute("dataType"))));
            }
        } else {
            warnings.add("未识别到模型输出字段（Output / target），请手动登记出参");
        }
        dto.setFields(fields);

        // 输入字段：DataDictionary 中排除 target 与输出字段
        Set<String> fieldNames = new HashSet<>();
        for (ViewUploadResultDTO.ParsedField f : fields) fieldNames.add(f.getFieldName().toLowerCase(Locale.ROOT));
        List<ViewUploadResultDTO.ParsedInput> inputs = new ArrayList<>();
        Set<String> usedVars = new HashSet<>();
        ObjectNode inputMap = JSON.createObjectNode();
        for (Map.Entry<String, Element> entry : dataFields.entrySet()) {
            String sourceName = entry.getKey();
            if (targetNames.contains(sourceName)) continue;
            if (fieldNames.contains(sourceName.toLowerCase(Locale.ROOT))) continue;
            Element df = entry.getValue();
            String var = uniqueVar(toVarName(sourceName), usedVars);
            ViewUploadResultDTO.ParsedInput in = new ViewUploadResultDTO.ParsedInput();
            in.setVarCode(var);
            in.setVarType(mapPmmlType(df.getAttribute("optype"), df.getAttribute("dataType")));
            in.setSourceField(sourceName);
            inputs.add(in);
            inputMap.put(sourceName, "$" + var);
            if (!var.equals(sourceName)) {
                warnings.add("模型字段「" + sourceName + "」已映射为入参 $" + var);
            }
        }
        dto.setInputs(inputs);

        ObjectNode cfg = JSON.createObjectNode();
        cfg.put("fileName", dto.getFileName());
        cfg.put("sizeBytes", dto.getSizeBytes());
        // 文件内容内嵌进视图配置：不再依赖服务器磁盘文件，避免被清理后无法验证
        cfg.put("content", contentText);
        if (meta.getModelName() != null && !meta.getModelName().isBlank()) cfg.put("modelName", meta.getModelName());
        if (meta.getAlgorithm() != null) cfg.put("algorithm", meta.getAlgorithm());
        if (meta.getFunctionName() != null && !meta.getFunctionName().isBlank()) cfg.put("functionName", meta.getFunctionName());
        if (!inputMap.isEmpty()) cfg.set("inputs", inputMap);
        dto.setScriptContent(pretty(cfg));
    }

    private ViewUploadResultDTO.ParsedField parsedField(String name, String type) {
        ViewUploadResultDTO.ParsedField f = new ViewUploadResultDTO.ParsedField();
        f.setFieldName(name);
        f.setFieldType(type);
        f.setDescription("");
        return f;
    }

    private String uniqueName(String raw, Set<String> used) {
        String name = sanitizeFieldName(raw, used.size());
        String base = name;
        int i = 2;
        while (!used.add(name.toLowerCase(Locale.ROOT))) {
            name = base + "_" + i++;
        }
        return name;
    }

    private String toVarName(String fieldName) {
        String s = fieldName.replaceAll("[^a-zA-Z0-9_]", "_");
        s = s.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        s = s.replaceAll("_+", "_");
        if (s.endsWith("_")) s = s.substring(0, s.length() - 1);
        if (s.isBlank()) s = "var";
        if (!s.matches("[a-zA-Z_].*")) s = "f_" + s;
        if (s.length() > 64) s = s.substring(0, 64);
        return s;
    }

    private String uniqueVar(String base, Set<String> used) {
        String name = base;
        int i = 2;
        while (!used.add(name)) name = base + "_" + i++;
        return name;
    }

    private String mapPmmlType(String optype, String dataType) {
        if ("categorical".equalsIgnoreCase(optype)) return "ENUM";
        if (dataType == null || dataType.isBlank()) return "STRING";
        String dt = dataType.toLowerCase(Locale.ROOT);
        if (dt.startsWith("integer")) return "INTEGER";
        if (dt.startsWith("float") || dt.startsWith("double") || dt.startsWith("real") || dt.startsWith("decimal")) {
            return "DECIMAL";
        }
        if (dt.startsWith("boolean")) return "BOOLEAN";
        if (dt.startsWith("date") || dt.startsWith("time")) return "DATETIME";
        return "STRING";
    }

    private Document parseXml(byte[] bytes) {
        try {
            return secureFactory().newDocumentBuilder().parse(new ByteArrayInputStream(bytes));
        } catch (Exception e) {
            throw new IllegalArgumentException("PMML 文件解析失败，请确认是合法的 PMML/XML 文件：" + e.getMessage());
        }
    }

    /** 从内嵌文本解析 XML：以字符流交给解析器，原文件的编码声明不再参与解码（内容已解码为 Unicode）。 */
    private Document parseXmlText(String text) {
        try {
            return secureFactory().newDocumentBuilder().parse(new InputSource(new StringReader(text)));
        } catch (Exception e) {
            throw new IllegalArgumentException("PMML 内容解析失败，请确认文件是合法的 PMML/XML：" + e.getMessage());
        }
    }

    private DocumentBuilderFactory secureFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setExpandEntityReferences(false);
        factory.setNamespaceAware(false);
        return factory;
    }

    /** 线性回归在线打分（RegressionModel，单 RegressionTable）；不支持的模型结构返回 null。 */
    private Double scoreRegressionModel(Document doc, Map<String, String> valueByField, List<String> warnings) {
        Element modelEl = findModelElement(doc.getDocumentElement());
        if (modelEl == null) return null;
        List<Element> tables = children(modelEl, "RegressionTable");
        if (tables.isEmpty()) return null;
        if (tables.size() > 1) {
            warnings.add("模型含多个 RegressionTable（多分类 / 多目标），在线验证暂不支持打分");
            return null;
        }
        Element table = tables.get(0);
        double score = parseDoubleSafe(table.getAttribute("intercept"));
        for (Element np : children(table, "NumericPredictor")) {
            String name = np.getAttribute("name");
            double coef = parseDoubleSafe(np.getAttribute("coefficient"));
            String raw = valueByField.get(name);
            if (raw == null || raw.isBlank()) {
                warnings.add("模型字段 " + name + " 未提供测试值，预测时按 0 处理");
                continue;
            }
            try {
                score += coef * Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                warnings.add("模型字段 " + name + " 的值「" + raw + "」不是数值，预测时按 0 处理");
            }
        }
        Map<String, List<Element>> catByName = new LinkedHashMap<>();
        for (Element cp : children(table, "CategoricalPredictor")) {
            catByName.computeIfAbsent(cp.getAttribute("name"), k -> new ArrayList<>()).add(cp);
        }
        for (Map.Entry<String, List<Element>> e : catByName.entrySet()) {
            String name = e.getKey();
            String raw = valueByField.get(name);
            if (raw == null || raw.isBlank()) {
                warnings.add("模型字段 " + name + " 未提供测试值，预测时按 0 处理");
                continue;
            }
            boolean hit = false;
            for (Element cp : e.getValue()) {
                if (raw.trim().equalsIgnoreCase(cp.getAttribute("value"))) {
                    score += parseDoubleSafe(cp.getAttribute("coefficient"));
                    hit = true;
                    break;
                }
            }
            if (!hit) {
                warnings.add("模型字段 " + name + " 的值「" + raw.trim() + "」不在模型类别中，预测时按 0 处理");
            }
        }
        return score;
    }

    /** 表达式模型在线打分（SimpleMathModel 的 MathExpression）；不支持的节点 / 函数返回 null。 */
    private Double scoreSimpleMathModel(Document doc, Map<String, String> valueByField, List<String> warnings) {
        Element modelEl = findModelElement(doc.getDocumentElement());
        if (modelEl == null) return null;
        Element mathExpr = firstChild(modelEl, "MathExpression");
        if (mathExpr == null) {
            warnings.add("模型中未找到 MathExpression，无法在线打分");
            return null;
        }
        List<Element> roots = childElements(mathExpr);
        if (roots.isEmpty()) {
            warnings.add("MathExpression 为空，无法在线打分");
            return null;
        }
        return evalExpr(roots.get(0), valueByField, warnings);
    }

    /** PMML 表达式求值：Apply / FieldRef / Constant 递归。 */
    private Double evalExpr(Element el, Map<String, String> valueByField, List<String> warnings) {
        String tag = el.getTagName();
        if ("Constant".equals(tag)) {
            String v = el.getAttribute("value");
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                warnings.add("表达式常量「" + v + "」不是数值，无法在线打分");
                return null;
            }
        }
        if ("FieldRef".equals(tag)) {
            String field = el.getAttribute("field");
            String raw = valueByField.get(field);
            if (raw == null || raw.isBlank()) {
                warnings.add("模型字段 " + field + " 未提供测试值，预测时按 0 处理");
                return 0d;
            }
            try {
                return Double.parseDouble(raw.trim());
            } catch (NumberFormatException e) {
                warnings.add("模型字段 " + field + " 的值「" + raw.trim() + "」不是数值，预测时按 0 处理");
                return 0d;
            }
        }
        if ("Apply".equals(tag)) {
            String fn = el.getAttribute("function");
            List<Double> args = new ArrayList<>();
            for (Element sub : childElements(el)) {
                Double v = evalExpr(sub, valueByField, warnings);
                if (v == null) return null;
                args.add(v);
            }
            if (args.isEmpty()) {
                warnings.add("表达式函数 " + fn + " 缺少参数，无法在线打分");
                return null;
            }
            return applyFn(fn, args, warnings);
        }
        warnings.add("表达式节点 " + tag + " 暂不支持在线打分");
        return null;
    }

    /** 表达式内置函数求值；数值域出错或函数不支持时返回 null 并告警。 */
    private Double applyFn(String fn, List<Double> args, List<String> warnings) {
        double first = args.get(0);
        switch (fn) {
            case "+": {
                double s = 0;
                for (double a : args) s += a;
                return s;
            }
            case "-": {
                if (args.size() == 1) return -first;
                double s = first;
                for (int i = 1; i < args.size(); i++) s -= args.get(i);
                return s;
            }
            case "*": {
                double p = 1;
                for (double a : args) p *= a;
                return p;
            }
            case "/": {
                double s = first;
                for (int i = 1; i < args.size(); i++) {
                    if (args.get(i) == 0d) {
                        warnings.add("表达式中出现除数为 0，无法在线打分");
                        return null;
                    }
                    s /= args.get(i);
                }
                return s;
            }
            case "min": {
                double m = first;
                for (double a : args) m = Math.min(m, a);
                return m;
            }
            case "max": {
                double m = first;
                for (double a : args) m = Math.max(m, a);
                return m;
            }
            case "abs": return Math.abs(first);
            case "floor": return Math.floor(first);
            case "ceil": return Math.ceil(first);
            case "round": return (double) Math.round(first);
            case "exp": return Math.exp(first);
            case "sqrt":
                if (first < 0) {
                    warnings.add("sqrt 的参数为负数（" + fmtScore(first) + "），无法在线打分");
                    return null;
                }
                return Math.sqrt(first);
            case "ln":
                if (first <= 0) {
                    warnings.add("ln 的参数须为正数（当前 " + fmtScore(first) + "），无法在线打分");
                    return null;
                }
                return Math.log(first);
            case "log10":
                if (first <= 0) {
                    warnings.add("log10 的参数须为正数（当前 " + fmtScore(first) + "），无法在线打分");
                    return null;
                }
                return Math.log10(first);
            case "pow":
                if (args.size() != 2) {
                    warnings.add("pow 需要 2 个参数，无法在线打分");
                    return null;
                }
                return Math.pow(args.get(0), args.get(1));
            case "modulo":
                if (args.size() != 2 || args.get(1) == 0d) {
                    warnings.add("modulo 参数非法（需要 2 个参数且除数不为 0），无法在线打分");
                    return null;
                }
                return args.get(0) % args.get(1);
            default:
                warnings.add("表达式函数 " + fn + " 暂不支持在线打分（支持 + - * / min max abs sqrt exp ln log10 pow floor ceil round modulo）");
                return null;
        }
    }

    private List<Element> childElements(Element parent) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE) out.add((Element) n);
        }
        return out;
    }

    private double parseDoubleSafe(String s) {
        try {
            return Double.parseDouble(s == null ? "" : s.trim());
        } catch (NumberFormatException e) {
            return 0d;
        }
    }

    private String fmtScore(double v) {
        if (!Double.isNaN(v) && !Double.isInfinite(v) && v == Math.rint(v) && Math.abs(v) < 1e15) {
            return String.valueOf((long) v);
        }
        return java.math.BigDecimal.valueOf(v).stripTrailingZeros().toPlainString();
    }

    private Element findModelElement(Element root) {
        NodeList children = root.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() != Node.ELEMENT_NODE) continue;
            String name = n.getNodeName();
            if (PMML_NON_MODEL_ELEMENTS.contains(name)) continue;
            return (Element) n;
        }
        return null;
    }

    private Element firstChild(Element parent, String name) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) return (Element) n;
        }
        return null;
    }

    private List<Element> children(Element parent, String name) {
        List<Element> out = new ArrayList<>();
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node n = children.item(i);
            if (n.getNodeType() == Node.ELEMENT_NODE && name.equals(n.getNodeName())) out.add((Element) n);
        }
        return out;
    }

    // ------------------------------------------------------------------ CSV / PMML 在线验证

    public Map<String, Object> validateCsv(ViewValidateRequest req) {
        Map<String, Object> resp = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            JsonNode cfg = JSON.readTree(req.getScriptContent());
            String content = cfg.path("content").asText("");
            String fileName = cfg.path("fileName").asText("");
            // 优先用内嵌文件内容（不受服务器文件清理影响）；旧视图仅有 file 路径时回退磁盘读取
            CsvData data;
            boolean embedded = !content.isEmpty();
            String file = embedded ? "" : cfg.path("file").asText("");
            if (embedded) {
                data = parseCsvText(content, cfg.path("encoding").asText("UTF-8"));
            } else {
                if (file.isBlank()) {
                    return fail(resp, "脚本缺少文件内容：请先在编辑页上传 CSV 文件");
                }
                Path p = resolveStored(file);
                if (!Files.exists(p)) {
                    return fail(resp, "文件不存在：" + file + "（可能已被清理），请重新上传");
                }
                data = parseCsvBytes(Files.readAllBytes(p));
            }

            List<Map<String, Object>> results = new ArrayList<>();
            List<String> warnings = new ArrayList<>();
            if (!data.hasHeader) {
                warnings.add("文件未检测到表头，出参按列序 c1..cN 核对");
            }
            // 列匹配：优先生成与上传解析一致的表头字段名（同算法），再回退原始表头 / 洗白名
            Map<String, Integer> byFieldName = new LinkedHashMap<>();
            Map<String, Integer> byRawHeader = new LinkedHashMap<>();
            Map<String, Integer> byNorm = new LinkedHashMap<>();
            for (int i = 0; i < data.columnNames.size(); i++) {
                String raw = data.columnNames.get(i) == null ? "" : data.columnNames.get(i).trim();
                byFieldName.putIfAbsent(sanitizeFieldName(raw, i).toLowerCase(Locale.ROOT), i);
                if (!raw.isEmpty()) byRawHeader.putIfAbsent(raw.toLowerCase(Locale.ROOT), i);
                String norm = normalizeName(raw);
                if (!norm.isEmpty()) byNorm.putIfAbsent(norm, i);
            }

            // 入参过滤：各入参以表头同名列作为过滤条件，仅保留列值等于入参值的数据行
            List<List<String>> matched = data.rows;
            List<String> filterDescs = new ArrayList<>();
            if (req.getInputs() != null) {
                for (ViewValidateRequest.InputValue iv : req.getInputs()) {
                    String raw = iv.getValue();
                    if (raw == null || raw.isBlank()) continue;
                    Integer idx = lookupColumn(iv.getVarCode(), byFieldName, byRawHeader, byNorm);
                    if (idx == null) {
                        warnings.add("入参 " + iv.getVarCode() + " 未在 CSV 表头中找到，未参与过滤");
                        continue;
                    }
                    int col = idx;
                    matched = matched.stream()
                            .filter(r -> col < r.size() && valueEquals(r.get(col), raw))
                            .toList();
                    filterDescs.add(iv.getVarCode() + "=\"" + raw.trim() + "\"");
                }
            }
            boolean filtered = !filterDescs.isEmpty();
            if (filtered) {
                warnings.add("按入参过滤：" + String.join("，", filterDescs) + " → 匹配 " + matched.size() + " 行"
                        + (matched.isEmpty() ? "（出参按空值 / 默认值返回）" : matched.size() > 1 ? "（出参取首个匹配行）" : ""));
            }

            List<String> firstRow = matched.isEmpty() ? List.of() : matched.get(0);
            for (ViewValidateRequest.OutputSpec o : req.getOutputs()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fieldName", o.getFieldName());
                row.put("fieldType", o.getFieldType());
                Integer idx = lookupColumn(o.getFieldName(), byFieldName, byRawHeader, byNorm);
                if (idx == null) {
                    warnings.add("出参 " + o.getFieldName() + " 未在 CSV 表头中找到");
                    row.put("value", null);
                    row.put("defaultValueUsed", false);
                } else {
                    String value = idx < firstRow.size() ? firstRow.get(idx) : null;
                    boolean defaultUsed = false;
                    if ((value == null || value.isEmpty()) && o.getDefaultValue() != null && !o.getDefaultValue().isBlank()) {
                        value = o.getDefaultValue().trim();
                        defaultUsed = true;
                    }
                    row.put("value", value);
                    row.put("defaultValueUsed", defaultUsed);
                }
                results.add(row);
            }
            long elapsedMs = (System.nanoTime() - start) / 1_000_000;

            resp.put("success", true);
            resp.put("elapsedMs", elapsedMs);
            resp.put("results", results);
            if (!warnings.isEmpty()) resp.put("warnings", warnings);
            resp.put("resolvedScript", "file: " + (embedded ? "内嵌内容" + (fileName.isBlank() ? "" : "（" + fileName + "）") : file)
                    + " · " + data.rows.size() + " 行 × "
                    + data.columnNames.size() + " 列 · 分隔符 \"" + data.delimiter + "\" · 编码 " + data.encoding
                    + " · 表头 " + (data.hasHeader ? "是" : "否")
                    + (filtered ? " · 过滤 " + String.join("，", filterDescs) : ""));
            resp.put("responsePreview", filtered
                    ? csvPreview(data, matched, "…（匹配 " + matched.size() + " 行）")
                    : csvPreview(data, data.rows, data.rows.size() > PREVIEW_DATA_ROWS
                            ? "…（共 " + data.rows.size() + " 行数据，仅预览前 " + PREVIEW_DATA_ROWS + " 行）" : null));
            return resp;
        } catch (IllegalArgumentException e) {
            return fail(resp, e.getMessage());
        } catch (Exception e) {
            return fail(resp, "CSV 验证失败：" + e.getMessage());
        }
    }

    public Map<String, Object> validatePmml(ViewValidateRequest req) {
        Map<String, Object> resp = new LinkedHashMap<>();
        long start = System.nanoTime();
        try {
            JsonNode cfg = JSON.readTree(req.getScriptContent());
            String content = cfg.path("content").asText("");
            String fileName = cfg.path("fileName").asText("");
            boolean embedded = !content.isEmpty();
            String file = embedded ? "" : cfg.path("file").asText("");
            // 优先用内嵌文件内容（不受服务器文件清理影响）；旧视图仅有 file 路径时回退磁盘读取
            Document doc;
            ViewUploadResultDTO parsed = new ViewUploadResultDTO();
            parsed.setWarnings(new ArrayList<>());
            if (embedded) {
                doc = parseXmlText(content);
                parsePmmlDoc(parsed, doc, content);
            } else {
                if (file.isBlank()) {
                    return fail(resp, "脚本缺少文件内容：请先在编辑页上传 PMML 文件");
                }
                Path p = resolveStored(file);
                if (!Files.exists(p)) {
                    return fail(resp, "文件不存在：" + file + "（可能已被清理），请重新上传");
                }
                byte[] bytes = Files.readAllBytes(p);
                doc = parseXml(bytes);
                parsePmmlDoc(parsed, doc, decodeText(bytes, detectCharset(bytes)));
            }

            List<String> warnings = new ArrayList<>(parsed.getWarnings());

            // 测试入参：脚本 inputs 映射（模型字段 → $变量）→ 本次请求的变量值
            Map<String, String> valueByVar = new HashMap<>();
            if (req.getInputs() != null) {
                for (ViewValidateRequest.InputValue iv : req.getInputs()) {
                    if (iv.getVarCode() != null) valueByVar.put(iv.getVarCode(), iv.getValue());
                }
            }
            Map<String, String> valueByField = new LinkedHashMap<>();
            cfg.path("inputs").fields().forEachRemaining(en -> {
                String var = en.getValue().asText("");
                if (var.startsWith("$")) var = var.substring(1);
                valueByField.put(en.getKey(), valueByVar.get(var));
            });

            // 模型结构：目标字段与各输出字段的 feature（doc 已在上方解析，避免重复解析）
            Element modelEl = findModelElement(doc.getDocumentElement());
            String modelTag = modelEl == null ? "" : modelEl.getTagName();
            Set<String> targetNames = new LinkedHashSet<>();
            Set<String> predictedOutNames = new LinkedHashSet<>();
            Map<String, String> outputFeature = new LinkedHashMap<>();
            if (modelEl != null) {
                Element schema = firstChild(modelEl, "MiningSchema");
                if (schema != null) {
                    for (Element mf : children(schema, "MiningField")) {
                        if ("target".equalsIgnoreCase(mf.getAttribute("usageType"))) {
                            String n = mf.getAttribute("name");
                            if (!n.isBlank()) {
                                targetNames.add(n);
                                predictedOutNames.add(n);
                                outputFeature.putIfAbsent(normalizeName(n), "predictedValue");
                            }
                        }
                    }
                }
                Element outputEl = firstChild(modelEl, "Output");
                if (outputEl != null) {
                    for (Element of : children(outputEl, "OutputField")) {
                        String n = of.getAttribute("name");
                        if (n.isBlank()) continue;
                        String feature = of.getAttribute("feature");
                        if (feature.isBlank() || "predictedValue".equals(feature)) predictedOutNames.add(n);
                        outputFeature.putIfAbsent(normalizeName(n), feature.isBlank() ? "predictedValue" : feature);
                    }
                }
            }

            // 在线打分：支持线性回归（RegressionModel 单表）与表达式模型（SimpleMathModel）
            Double predicted = null;
            if ("RegressionModel".equals(modelTag)) {
                predicted = scoreRegressionModel(doc, valueByField, warnings);
            } else if ("SimpleMathModel".equals(modelTag)) {
                predicted = scoreSimpleMathModel(doc, valueByField, warnings);
            } else if (modelEl != null) {
                warnings.add("模型类型 " + modelTag + " 暂不支持在线打分；仅做模型解析与出参核对，打分结果由运行时引擎按模型计算");
            }
            String predictedText = predicted == null ? null : fmtScore(predicted);
            Set<String> predictedNames = new HashSet<>();
            outputFeature.forEach((k, f) -> {
                if ("predictedValue".equals(f)) predictedNames.add(k);
            });

            Set<String> outputNames = new HashSet<>();
            for (ViewUploadResultDTO.ParsedField f : parsed.getFields()) {
                outputNames.add(normalizeName(f.getFieldName()));
            }
            List<Map<String, Object>> results = new ArrayList<>();
            for (ViewValidateRequest.OutputSpec o : req.getOutputs()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("fieldName", o.getFieldName());
                row.put("fieldType", o.getFieldType());
                String key = normalizeName(o.getFieldName());
                if (!outputNames.contains(key)) {
                    warnings.add("出参 " + o.getFieldName() + " 不在模型输出字段中");
                }
                String value = null;
                if (predictedText != null && predictedNames.contains(key)) {
                    value = predictedText;
                } else if (predictedText != null && outputFeature.containsKey(key)) {
                    warnings.add("输出 " + o.getFieldName() + "（feature=" + outputFeature.get(key)
                            + "）为衍生转换字段，在线验证暂不计算，由运行时引擎按模型转换");
                }
                row.put("value", value);
                row.put("defaultValueUsed", false);
                results.add(row);
            }

            long elapsedMs = (System.nanoTime() - start) / 1_000_000;
            resp.put("success", true);
            resp.put("elapsedMs", elapsedMs);
            resp.put("results", results);
            resp.put("warnings", warnings);

            ViewUploadResultDTO.PmmlMeta meta = parsed.getPmml();
            String modelDesc = (meta.getModelName() == null || meta.getModelName().isBlank() ? "（未命名）" : meta.getModelName());
            Set<String> predDisplay = !targetNames.isEmpty() ? targetNames : predictedOutNames;
            resp.put("resolvedScript", "model: " + (embedded ? "内嵌内容" + (fileName.isBlank() ? "" : "（" + fileName + "）") : file)
                    + " · " + modelDesc + " · " + meta.getAlgorithm()
                    + (meta.getFunctionName().isBlank() ? "" : " · " + meta.getFunctionName())
                    + " · 输入 " + parsed.getInputs().size() + " 字段 → 输出 " + parsed.getFields().size() + " 字段"
                    + (predictedText != null && !predDisplay.isEmpty()
                            ? " · 预测值 " + String.join("/", predDisplay) + "=" + predictedText : ""));

            ObjectNode preview = JSON.createObjectNode();
            preview.put("modelName", meta.getModelName());
            preview.put("algorithm", meta.getAlgorithm());
            preview.put("functionName", meta.getFunctionName());
            if (predictedText != null) preview.put("predictedValue", predictedText);
            preview.set("inputs", JSON.valueToTree(parsed.getInputs()));
            preview.set("outputs", JSON.valueToTree(parsed.getFields()));
            resp.put("responsePreview", pretty(preview));
            return resp;
        } catch (IllegalArgumentException e) {
            return fail(resp, e.getMessage());
        } catch (Exception e) {
            return fail(resp, "PMML 验证失败：" + e.getMessage());
        }
    }

    private String csvPreview(CsvData data, List<List<String>> rows, String caption) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.join(" | ", data.columnNames())).append('\n');
        for (int i = 0; i < Math.min(PREVIEW_DATA_ROWS, rows.size()); i++) {
            sb.append(String.join(" | ", rows.get(i))).append('\n');
        }
        if (caption != null) sb.append(caption);
        else if (rows.isEmpty()) sb.append("（无数据行）");
        return sb.toString().trim();
    }

    /** 过滤值比对：先精确 / 忽略大小写匹配，数值型再按数值等价（如 50000 与 50000.00）。 */
    private boolean valueEquals(String actual, String expected) {
        String a = actual == null ? "" : actual.trim();
        String b = expected == null ? "" : expected.trim();
        if (a.equals(b) || a.equalsIgnoreCase(b)) return true;
        try {
            return new java.math.BigDecimal(a).compareTo(new java.math.BigDecimal(b)) == 0;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    private Path resolveStored(String storedPath) {
        Path p = Paths.get(storedPath);
        Path abs = (p.isAbsolute() ? p : Paths.get("").toAbsolutePath().resolve(storedPath)).normalize();
        if (!abs.startsWith(uploadRoot)) {
            throw new IllegalArgumentException("文件路径不在本地上传目录内：" + storedPath);
        }
        return abs;
    }

    /** 按「上传字段名 → 原始表头 → 洗白名」优先级在 CSV 列中查找。 */
    private Integer lookupColumn(String name, Map<String, Integer> byFieldName,
                                 Map<String, Integer> byRawHeader, Map<String, Integer> byNorm) {
        if (name == null || name.isBlank()) return null;
        String key = name.trim().toLowerCase(Locale.ROOT);
        Integer idx = byFieldName.get(key);
        if (idx == null) idx = byRawHeader.get(key);
        if (idx == null) {
            String norm = normalizeName(name);
            if (!norm.isEmpty()) idx = byNorm.get(norm);
        }
        return idx;
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> fail(Map<String, Object> resp, String error) {
        resp.put("success", false);
        resp.put("error", error);
        resp.put("results", List.of());
        return resp;
    }

    private String pretty(JsonNode node) {
        try {
            return JSON.writerWithDefaultPrettyPrinter().writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }
}
