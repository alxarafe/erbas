package com.alxarafe.erbas.auth.infrastructure;

import java.util.List;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

public final class BearerAuthenticationProvider implements AuthenticationProvider {

    public static final String ADMIN_AUTHORITY = "ADMIN";
    private final OpaqueAccessTokens tokens;

    public BearerAuthenticationProvider(OpaqueAccessTokens tokens) {
        this.tokens = tokens;
    }

    @Override
    public Authentication authenticate(Authentication authentication) {
        if (!(authentication.getCredentials() instanceof String raw)) {
            throw new BadCredentialsException("Invalid bearer credentials");
        }
        var identity = tokens.findIdentity(raw)
                .orElseThrow(() -> new BadCredentialsException("Invalid bearer credentials"));
        var authorities = identity.admin() ? List.of(new SimpleGrantedAuthority(ADMIN_AUTHORITY))
                : List.<SimpleGrantedAuthority>of();
        return UsernamePasswordAuthenticationToken.authenticated(identity, null, authorities);
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return PreAuthenticatedAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
