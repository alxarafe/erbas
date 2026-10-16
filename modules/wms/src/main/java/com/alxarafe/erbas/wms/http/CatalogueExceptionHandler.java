package com.alxarafe.erbas.wms.http;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = {UomController.class, StorageAttributeController.class, ItemFamilyController.class, FamilyAssociationController.class})
public class CatalogueExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> unreadableRequest() {
        return CatalogueHttp.error(HttpStatus.BAD_REQUEST, CatalogueHttp.INVALID_REQUEST);
    }
}
