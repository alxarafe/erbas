package com.alxarafe.erbas.adapter.http;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Only Bean Validation failures; existing closed shared-contract errors are unchanged. */
@RestControllerAdvice
public class ValidationExceptionHandler {
    public static final String VALIDATION_CODE = "validation_error";
    private final MessageSource messages;

    public ValidationExceptionHandler(MessageSource messages) { this.messages = messages; }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail invalid(MethodArgumentNotValidException failure, Locale locale) {
        var problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle(messages.getMessage("core.error.validation.title", null, locale));
        problem.setDetail(messages.getMessage("core.error.validation.detail", null, locale));
        problem.setProperty("code", VALIDATION_CODE);
        var errors = new LinkedHashMap<String, List<String>>();
        failure.getBindingResult().getAllErrors().forEach(error -> {
            String path = error instanceof FieldError field ? field.getField() : error.getObjectName();
            errors.computeIfAbsent(path, ignored -> new ArrayList<>()).add(messages.getMessage(error, locale));
        });
        problem.setProperty("errors", errors);
        return problem;
    }
}
