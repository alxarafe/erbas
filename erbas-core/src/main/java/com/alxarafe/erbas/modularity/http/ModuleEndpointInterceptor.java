package com.alxarafe.erbas.modularity.http;

import java.nio.charset.StandardCharsets;

import com.alxarafe.erbas.adapter.http.ContractError;

import com.alxarafe.erbas.modularity.ModuleDisabledException;
import com.alxarafe.erbas.modularity.ModuleStateService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/** HTTP boundary only; Spring Security evaluates authentication/authorization first. */
public final class ModuleEndpointInterceptor implements HandlerInterceptor {
    public static final String DISABLED_CODE = "module_disabled";
    private static final String DISABLED_JSON = ContractError.json(DISABLED_CODE);
    private static final String CHECKED_MODULE = ModuleEndpointInterceptor.class.getName();
    private final ModuleStateService states;

    public ModuleEndpointInterceptor(ModuleStateService states) { this.states = states; }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!(handler instanceof HandlerMethod method)) return true;
        var module = AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), ModuleEndpoint.class);
        if (module == null) return true;
        // MVC may redispatch an asynchronous request; retain only this request's boundary decision.
        if (module.value().equals(request.getAttribute(CHECKED_MODULE))) return true;
        try {
            states.requireEnabled(module.value());
        } catch (ModuleDisabledException disabled) {
            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
            response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.setCharacterEncoding(StandardCharsets.UTF_8.name());
            response.getWriter().write(DISABLED_JSON);
            return false;
        }
        request.setAttribute(CHECKED_MODULE, module.value());
        return true;
    }
}
