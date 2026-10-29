package com.fastwork.expense.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.lang.reflect.Method;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.validation.annotation.Validated;

class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void apiExceptionWithoutRetryAfterHasNoHeader() {
        ResponseEntity<ApiError> response =
                handler.handleApiException(ApiException.notFound("Tidak ditemukan"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNull();
        assertThat(response.getBody().code()).isEqualTo("NOT_FOUND");
    }

    @Test
    void rateLimitedApiExceptionCarriesRetryAfter() {
        ResponseEntity<ApiError> response =
                handler.handleApiException(ApiException.rateLimited(30, "Tunggu"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("30");
        assertThat(response.getBody().code()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void validationErrorsStripRpcPrefixFromDefaultMessages() throws Exception {
        Method method = Holder.class.getMethod("method", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult binding =
                new BeanPropertyBindingResult(new Holder(), "holder");
        binding.addError(new FieldError("holder", "name", null, false, null, null, "rpc.panjang"));
        binding.addError(
                new FieldError("holder", "other", null, false, null, null, "wajib diisi"));
        binding.addError(new FieldError("holder", "kosong", null, false, null, null, null));

        ResponseEntity<ApiError> response =
                handler.handleValidation(new MethodArgumentNotValidException(parameter, binding));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ApiError body = response.getBody();
        assertThat(body.code()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.fieldErrors())
                .extracting(ApiError.FieldError::message)
                .containsExactly("panjang", "wajib diisi", null);
    }

    @Test
    void handlerMethodValidationBecomesValidationError() throws Exception {
        Method method = Holder.class.getMethod("method", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        org.springframework.validation.method.ParameterValidationResult result =
                new org.springframework.validation.method.ParameterValidationResult(
                        parameter,
                        "",
                        java.util.List.of(
                                new org.springframework.context.support.DefaultMessageSourceResolvable(
                                        new String[] {"NotBlank"}, null, "wajib diisi")));
        org.springframework.validation.method.MethodValidationResult group =
                org.springframework.validation.method.MethodValidationResult.create(
                        new Holder(), method, java.util.List.of(result));

        ResponseEntity<ApiError> response =
                handler.handleParameterValidation(
                        new org.springframework.web.method.annotation.HandlerMethodValidationException(
                                group));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().code()).isEqualTo("VALIDATION_ERROR");
        assertThat(response.getBody().fieldErrors())
                .extracting(ApiError.FieldError::message)
                .containsExactly("wajib diisi");
    }

    @Test
    void bodyValidationBecomesValidationError() throws Exception {
        mockMvc()
                .perform(
                        post("/probe/body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"kode\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void malformedJsonBecomesMalformedRequest() throws Exception {
        mockMvc()
                .perform(
                        post("/probe/body")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{rusak"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void typeMismatchMissingParamAndMissingPartAndNotReadableAreMalformed() throws Exception {
        Method method = Holder.class.getMethod("method", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);

        assertThat(
                        handler.handleMalformed(
                                        new MethodArgumentTypeMismatchException(
                                                "abc", Long.class, "id", parameter, null))
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        handler.handleMalformed(
                                        new MissingServletRequestParameterException("q", "string"))
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        handler.handleMalformed(new MissingServletRequestPartException("file"))
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(
                        handler.handleMalformed(
                                        new HttpMessageNotReadableException(
                                                "x", (org.springframework.http.HttpInputMessage) null))
                                .getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void tooLargeUploadReturns413() {
        ResponseEntity<ApiError> response =
                handler.handleTooLarge(new MaxUploadSizeExceededException(10L));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().code()).isEqualTo("PAYLOAD_TOO_LARGE");
    }

    @Test
    void noHandlerAndNoResourceReturnNotFound() {
        assertThat(
                        handler.handleNoHandler(new NoHandlerFoundException("GET", "/x", new HttpHeaders()))
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(
                        handler.handleNoResource(new NoResourceFoundException(HttpMethod.GET, "/y"))
                                .getStatusCode())
                .isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void errorResponseExceptionMapsResolvableAndUnknownStatus() {
        ResponseEntity<ApiError> known =
                handler.handleErrorResponse(
                        new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "nope"));
        assertThat(known.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);

        ResponseEntity<ApiError> custom =
                handler.handleErrorResponse(
                        new ResponseStatusException(HttpStatusCode.valueOf(599)));
        assertThat(custom.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void responseStatusExceptionWithAndWithoutReason() {
        ResponseEntity<ApiError> withReason =
                handler.handleResponseStatus(
                        new ResponseStatusException(HttpStatus.NOT_FOUND, "Hilang"));
        assertThat(withReason.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(withReason.getBody().message()).isEqualTo("Hilang");

        ResponseEntity<ApiError> withoutReason =
                handler.handleResponseStatus(new ResponseStatusException(HttpStatusCode.valueOf(599)));
        assertThat(withoutReason.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(withoutReason.getBody().message())
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.getReasonPhrase());
    }

    @Test
    void accessDeniedReturnsForbidden() {
        ResponseEntity<ApiError> response = handler.handleAccessDenied(new AccessDeniedException("x"));
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().code()).isEqualTo("FORBIDDEN");
    }

    @Test
    void unexpectedExceptionReturnsServerErrorAndLogs() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        ResponseEntity<ApiError> response =
                handler.handleUnexpected(new IllegalStateException("boom"), request);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().code()).isEqualTo("INTERNAL_ERROR");
    }

    @Test
    void emptyFieldErrorListSerializesAsNull() {
        assertThat(ApiError.of("C", "M").fieldErrors()).isNull();
        assertThat(ApiError.of("C", "M", java.util.List.of()).fieldErrors()).isNull();
        assertThat(
                        ApiError.of("C", "M", java.util.List.of(new ApiError.FieldError("f", "m")))
                                .fieldErrors())
                .hasSize(1);
    }

    private MockMvc mockMvc() {
        return MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** Target refleksi untuk MethodParameter. */
    static class Holder {
        public String method(String name) {
            return name;
        }
    }

    @Validated
    @RestController
    static class ProbeController {

        @GetMapping("/probe/constrained")
        String constrained(@RequestParam @NotBlank String kode) {
            return kode;
        }

        @PostMapping("/probe/body")
        String body(@Valid @RequestBody Payload payload) {
            return payload.kode();
        }
    }

    record Payload(@NotBlank String kode) {}
}
