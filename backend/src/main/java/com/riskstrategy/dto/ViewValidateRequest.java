package com.riskstrategy.dto;

import lombok.Data;

import java.util.List;

/**
 * 视图脚本验证（试跑）请求：不落库，用测试入参实际执行一次脚本。
 */
@Data
public class ViewValidateRequest {
    /** 编辑场景下的视图 id；新建场景为 null（脚本以 scriptContent 为准） */
    private Long viewId;
    private String sourceType;
    private String readMode;
    private String scriptContent;

    private List<InputValue> inputs;
    private List<OutputSpec> outputs;

    @Data
    public static class InputValue {
        private String varCode;
        private String varType;
        private Boolean required;
        /** 用户在验证弹窗中填写的测试值（字符串，按 varType 转换绑定） */
        private String value;
    }

    @Data
    public static class OutputSpec {
        private String fieldName;
        private String fieldType;
        private String defaultValue;
        private Integer sort;
    }
}
