package com.ncwu.common.domain.vo;

import lombok.Data;
import java.io.Serial;
import java.io.Serializable;

/** Standard response envelope retained for Dubbo wire compatibility. */
@Data
public final class Result<T> implements Serializable {
    @Serial private static final long serialVersionUID = 1L;
    private String code;
    private String message;
    private T data;
    private Result() { }
    public static <T> Result<T> ok(T data) { return ok(data, "200", "success"); }
    public static <T> Result<T> ok(T data, String code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        result.data = data;
        return result;
    }
    public static <T> Result<T> ok(String code, String message) { return ok(null, code, message); }
    public static <T> Result<T> fail(T data) { return ok(data); }
    public static <T> Result<T> fail(String code, String message) { return fail(null, code, message); }
    public static <T> Result<T> fail(T data, String message) {
        Result<T> result = new Result<>();
        result.message = message;
        result.data = data;
        return result;
    }
    public static <T> Result<T> fail(T data, String code, String message) {
        Result<T> result = new Result<>();
        result.code = code;
        result.message = message;
        result.data = data;
        return result;
    }
}
