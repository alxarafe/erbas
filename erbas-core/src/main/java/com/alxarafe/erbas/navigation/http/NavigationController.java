package com.alxarafe.erbas.navigation.http;

import java.util.Locale;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.navigation.EffectiveNavigation;
import com.alxarafe.erbas.resources.ResourceRuntime;
import com.alxarafe.erbas.resources.http.ResourceController;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
public final class NavigationController {
    public static final String PATH = "/api/meta/navigation";
    private final EffectiveNavigation navigation;
    private final ResourceRuntime runtime;
    public NavigationController(EffectiveNavigation navigation, ResourceRuntime runtime) { this.navigation = navigation; this.runtime = runtime; }
    @GetMapping(PATH)
    public ResponseEntity<?> get(@AuthenticationPrincipal UserIdentity user, HttpServletRequest request, Locale locale) {
        runtime.current(user); ResourceController.noQuery(request);
        var effective = navigation.resolve(user, locale); String version = (String) effective.get("version");
        boolean unchanged = ResourceController.matches(request.getHeader(HttpHeaders.IF_NONE_MATCH), version);
        return ResponseEntity.status(unchanged ? HttpStatus.NOT_MODIFIED : HttpStatus.OK).contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache").varyBy(HttpHeaders.AUTHORIZATION, HttpHeaders.ACCEPT_LANGUAGE)
                .eTag(version).body(unchanged ? null : effective);
    }
}
