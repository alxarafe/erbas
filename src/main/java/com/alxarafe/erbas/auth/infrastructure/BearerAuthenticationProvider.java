package com.alxarafe.erbas.auth.infrastructure;

import java.util.List;

import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.preauth.PreAuthenticatedAuthenticationToken;

public final class BearerAuthenticationProvider implements AuthenticationProvider {

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
        return UsernamePasswordAuthenticationToken.authenticated(Long.toString(identity.userId()), null, List.of());
    }

    @Override
    public boolean supports(Class<?> authentication) {
        return PreAuthenticatedAuthenticationToken.class.isAssignableFrom(authentication);
    }
}
