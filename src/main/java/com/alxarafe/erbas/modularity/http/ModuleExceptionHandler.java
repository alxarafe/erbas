package com.alxarafe.erbas.modularity.http;

import com.alxarafe.erbas.modularity.ModuleDependencyConflictException;
import com.alxarafe.erbas.modularity.ModuleUnavailableException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = ModuleController.class)
public class ModuleExceptionHandler {
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<?> unreadableRequest() {
        return ModuleController.error(HttpStatus.BAD_REQUEST, ModuleController.INVALID_REQUEST);
    }

    @ExceptionHandler(ModuleUnavailableException.class)
    public ResponseEntity<?> unavailable() {
        return ModuleController.error(HttpStatus.NOT_FOUND, ModuleController.NOT_FOUND);
    }

    @ExceptionHandler(ModuleDependencyConflictException.class)
    public ResponseEntity<?> dependencyConflict() {
        return ModuleController.error(HttpStatus.CONFLICT, ModuleController.DEPENDENCY_CONFLICT);
    }
}
