package com.fastwork.expense.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/** Exception domain yang dipetakan ke HTTP status + kode error seragam. */
public class ApiException extends RuntimeException {

    private final HttpStatusCode status;
    private final String code;
    private final Long retryAfterSeconds;

    public ApiException(HttpStatusCode status, String code, String message) {
        this(status, code, message, null);
    }

    public ApiException(
            HttpStatusCode status, String code, String message, Long retryAfterSeconds) {
        super(message);
        this.status = status;
        this.code = code;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public HttpStatusCode status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    public static ApiException conflict(String message) {
        return new ApiException(HttpStatus.CONFLICT, "CONFLICT", message);
    }

    public static ApiException unprocessable(String message) {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "UNPROCESSABLE_ENTITY", message);
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException unauthorized(String message) {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", message);
    }

    public static ApiException rateLimited(long retryAfterSeconds, String message) {
        return new ApiException(
                HttpStatus.TOO_MANY_REQUESTS,
                "RATE_LIMITED",
                message,
                Long.valueOf(retryAfterSeconds));
    }
}
