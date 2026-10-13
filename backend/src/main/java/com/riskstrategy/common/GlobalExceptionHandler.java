package com.riskstrategy.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import java.util.stream.Collectors;

@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务主动抛出的校验错误 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleIllegalArg(IllegalArgumentException e) {
        return ApiResponse.error(400, e.getMessage());
    }

    /** 字段级 @Valid 校验失败：逐字段提示 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleValid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(this::formatFieldError)
                .collect(Collectors.joining("；"));
        return ApiResponse.error(400, "提交内容有误：" + (msg.isEmpty() ? "请检查表单" : msg));
    }

    /** 请求体无法解析（JSON 格式错 / 字段类型不匹配） */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleNotReadable(HttpMessageNotReadableException e) {
        return ApiResponse.error(400, "请求数据格式有误：请检查字段类型是否正确（例如数字字段不能填写文字），并补全必填项");
    }

    /** 数据库唯一/非空/外键等约束冲突 */
    @ExceptionHandler(DataIntegrityViolationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleDataIntegrity(DataIntegrityViolationException e) {
        String root = e.getMostSpecificCause() != null ? e.getMostSpecificCause().getMessage() : "";
        String friendly = "数据未通过完整性校验，请检查必填项与字段长度后重试";
        if (root != null && (root.contains("Duplicate entry") || root.contains("uk_"))) {
            friendly = "该记录已存在（编码或唯一字段重复），请更换后重试";
        } else if (root != null && root.contains("cannot be null")) {
            friendly = "存在必填字段未填写，请补全后重试（" + extractColumn(root) + "）";
        }
        return ApiResponse.error(400, friendly);
    }

    /** 上传文件超过大小限制 */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleMaxUpload(MaxUploadSizeExceededException e) {
        return ApiResponse.error(400, "文件过大（上限 20MB），请压缩或拆分后重新上传");
    }

    /** 其余运行时异常（含业务里 new RuntimeException 的明确提示） */
    @ExceptionHandler(RuntimeException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<?> handleRuntime(RuntimeException e) {
        return ApiResponse.error(400, e.getMessage());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<?> handleGeneric(Exception e) {
        return ApiResponse.error(500, "服务器内部错误，请稍后重试；若持续出现请联系管理员");
    }

    private String formatFieldError(FieldError fe) {
        return fe.getField() + " " + fe.getDefaultMessage();
    }

    private String extractColumn(String msg) {
        int idx = msg.indexOf("Column '");
        if (idx >= 0) {
            int end = msg.indexOf("'", idx + 8);
            if (end > idx) return msg.substring(idx + 8, end);
        }
        return "必填项";
    }
}
