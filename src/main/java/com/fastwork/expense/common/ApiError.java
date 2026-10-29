package com.fastwork.expense.common;

import java.util.List;

public record ApiError(String code, String message, List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {}

    public static ApiError of(String code, String message) {
        return new ApiError(code, message, null);
    }

    public static ApiError of(String code, String message, List<FieldError> fieldErrors) {
        return new ApiError(
                code, message, fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors);
    }
}
