package com.alxarafe.erbas.auth.infrastructure;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

public final class BearerAuthenticationFilter extends OncePerRequestFilter {

    private static final Pattern BEARER = Pattern.compile("(?i:Bearer) +([A-Za-z0-9._~+/-]+=*)");
    private final AuthenticationManager authenticationManager;
    private final RequestMatcher publicEndpoints;

    public BearerAuthenticationFilter(AuthenticationManager authenticationManager, RequestMatcher publicEndpoints) {
        this.authenticationManager = authenticationManager;
        this.publicEndpoints = publicEndpoints;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Public liveness never performs token/database lookup, even with Authorization supplied.
        return publicEndpoints.matches(request);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getServletPath();
        if (path.equals("/api/auth/me") || path.equals("/api/users") || path.startsWith("/api/users/")) {
            response.setHeader("Cache-Control", "no-store");
        }
        String header = request.getHeader("Authorization");
        if (header != null) {
            var matcher = BEARER.matcher(header);
            if (matcher.matches()) {
                var authentication = new PreAuthenticatedAuthenticationToken(null, matcher.group(1));
                try {
                    var context = SecurityContextHolder.createEmptyContext();
                    context.setAuthentication(authenticationManager.authenticate(authentication));
                    SecurityContextHolder.setContext(context);
                } catch (AuthenticationException invalidToken) {
                    SecurityContextHolder.clearContext();
                } finally {
                    authentication.eraseCredentials();
                }
            }
        }
        chain.doFilter(request, response);
    }
}
