package com.alxarafe.erbas.ai.http;

import java.io.IOException;
import com.alxarafe.erbas.ai.AssistantInvocation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.filter.OncePerRequestFilter;

/** Wraps security only to audit rejected authentication; never reads bearer credentials or body. */
public final class AssistantAuthenticationAuditFilter extends OncePerRequestFilter {
    private final AssistantInvocation assistant;
    public AssistantAuthenticationAuditFilter(AssistantInvocation assistant) { this.assistant = assistant; }
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !HttpMethod.POST.matches(request.getMethod()) || !AssistantController.PATH.equals(request.getServletPath());
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        chain.doFilter(request, response);
        if (response.getStatus() == HttpStatus.UNAUTHORIZED.value()) {
            assistant.invoke(null, () -> { throw new IllegalStateException("Unauthenticated body access"); });
        }
    }
}
