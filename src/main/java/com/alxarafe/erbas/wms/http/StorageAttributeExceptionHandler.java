package com.alxarafe.erbas.wms.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = StorageAttributeController.class)
public class StorageAttributeExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> unreadableRequest() {
        return StorageAttributeController.error(HttpStatus.BAD_REQUEST, StorageAttributeController.INVALID_REQUEST);
    }
}
