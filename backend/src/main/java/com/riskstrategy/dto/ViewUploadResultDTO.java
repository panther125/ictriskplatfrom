package com.riskstrategy.dto;

import lombok.Data;

import java.util.List;

/**
 * CSV / PMML 视图文件上传结果：文件信息（内容内嵌，不落盘）+ 自动解析结果 + 建议的 scriptContent。
 */
@Data
public class ViewUploadResultDTO {
    private String fileName;
    private long sizeBytes;
    private String sourceType;
    /** 建议写入视图 scriptContent 的 JSON 文本（内嵌文件内容与解析配置） */
    private String scriptContent;
    private CsvMeta csv;
    private PmmlMeta pmml;
    /** 自动解析出的出参 */
    private List<ParsedField> fields;
    /** 自动解析出的入参（PMML：模型字段 ← 视图变量） */
    private List<ParsedInput> inputs;
    private List<String> warnings;

    @Data
    public static class CsvMeta {
        private String delimiter;
        private String encoding;
        private boolean hasHeader;
        private int rowCount;
        private int columnCount;
        /** 预览行（含表头行），最多 6 行 */
        private List<List<String>> previewRows;
    }

    @Data
    public static class PmmlMeta {
        private String modelName;
        private String algorithm;
        private String functionName;
        private String pmmlVersion;
    }

    @Data
    public static class ParsedField {
        private String fieldName;
        private String fieldType;
        private String description;
    }

    @Data
    public static class ParsedInput {
        private String varCode;
        private String varType;
        /** PMML 模型字段原名（视图变量映射来源） */
        private String sourceField;
    }
}
