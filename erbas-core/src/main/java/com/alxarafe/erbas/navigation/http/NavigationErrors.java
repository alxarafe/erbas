package com.alxarafe.erbas.navigation.http;

import com.alxarafe.erbas.resources.http.ResourceErrors;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = NavigationController.class)
public final class NavigationErrors {
    @ExceptionHandler(Exception.class)
    public ResponseEntity<?> failure(Exception exception, HttpServletRequest request) { return ResourceErrors.response(exception, request); }
}
