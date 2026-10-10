package com.alxarafe.erbas.auth.infrastructure;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

import com.alxarafe.erbas.auth.application.LoginUseCase;
import com.alxarafe.erbas.auth.application.UserAdministration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class AuthenticationConfiguration {

    @Bean
    public PasswordEncoder passwordEncoder() {
        String id = "pbkdf2-sha256-600000";
        var pbkdf2 = new Pbkdf2PasswordEncoder("", 16, 600_000,
                Pbkdf2PasswordEncoder.SecretKeyFactoryAlgorithm.PBKDF2WithHmacSHA256);
        return new DelegatingPasswordEncoder(id, Map.of(id, pbkdf2));
    }

    @Bean
    public SpringPasswordVerifier passwordVerifier(PasswordEncoder encoder) {
        return new SpringPasswordVerifier(encoder);
    }

    @Bean
    public Clock authenticationClock() {
        return Clock.systemUTC();
    }

    @Bean
    public OpaqueAccessTokens accessTokens(JdbcAuthenticationStore store, Clock authenticationClock,
            @Value("${erbas.auth.access-token-ttl}") Duration ttl) {
        return new OpaqueAccessTokens(store, authenticationClock, ttl);
    }

    @Bean
    public LoginUseCase loginUseCase(JdbcAuthenticationStore store, SpringPasswordVerifier passwords,
            OpaqueAccessTokens tokens) {
        return new LoginUseCase(store, passwords, tokens);
    }

    @Bean
    public UserAdministration userAdministration(JdbcAuthenticationStore store, PasswordEncoder encoder) {
        return new UserAdministration(store, encoder::encode);
    }

    @Bean
    public AuthenticationManager bearerAuthenticationManager(OpaqueAccessTokens tokens) {
        return new ProviderManager(new BearerAuthenticationProvider(tokens));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, AuthenticationManager bearerAuthenticationManager)
            throws Exception {
        var paths = PathPatternRequestMatcher.withDefaults();
        var publicEndpoints = new OrRequestMatcher(
                paths.matcher(HttpMethod.POST, "/api/auth/login"),
                paths.matcher(HttpMethod.GET, "/health"),
                paths.matcher(HttpMethod.GET, "/actuator/health"),
                paths.matcher(HttpMethod.GET, "/actuator/health/liveness"),
                paths.matcher(HttpMethod.GET, "/actuator/health/readiness"));
        return http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        .requestMatchers(publicEndpoints).permitAll()
                        .requestMatchers(paths.matcher("/api/users"), paths.matcher("/api/users/**"))
                            .hasAuthority(BearerAuthenticationProvider.ADMIN_AUTHORITY)
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, failure) -> {
                    response.setStatus(401);
                    response.setHeader("WWW-Authenticate", "Bearer");
                    response.setHeader("Cache-Control", "no-store");
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"unauthorized\"}");
                }).accessDeniedHandler((request, response, failure) -> {
                    response.setStatus(403);
                    response.setHeader("Cache-Control", "no-store");
                    response.setContentType("application/json");
                    response.getWriter().write("{\"code\":\"forbidden\"}");
                }))
                .addFilterBefore(new BearerAuthenticationFilter(bearerAuthenticationManager, publicEndpoints),
                        AnonymousAuthenticationFilter.class)
                .build();
    }
}
