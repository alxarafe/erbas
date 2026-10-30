package com.alxarafe.erbas.navigation;

import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.resources.*;
import com.alxarafe.erbas.resources.validation.ResourceConformanceConfiguration;
import com.alxarafe.erbas.navigation.http.NavigationController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.StaticMessageSource;
import org.springframework.http.*;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;
import static com.alxarafe.erbas.resources.validation.ResourceConformanceConfiguration.*;

class NavigationTests {
    private static final UserIdentity FIRST = new UserIdentity(7, "first@example.test", true, true);
    private static final UserIdentity SECOND = new UserIdentity(8, "second@example.test", true, true);
    private static final String LABEL = "fixture.label";
    private final Map<Long, UserIdentity> users = new HashMap<>(Map.of(FIRST.userId(), FIRST, SECOND.userId(), SECOND));
    private final AtomicBoolean permitted = new AtomicBoolean(true);
    private final AtomicReference<String> context = new AtomicReference<>(NavigationContext.PLATFORM);
    private final Map<String, Boolean> persisted = new HashMap<>();
    private final ModuleRegistry modules = new ModuleRegistry(List.of(() -> List.of(new ErbasModule() {
        public ModuleDescriptor descriptor() { return new ModuleDescriptor(ConformanceModulesConfiguration.MATH_ID, ModuleApi.CURRENT_VERSION, List.of()); }
        public void initialize() { }
    })));
    private final ModuleStateService states = new ModuleStateService(modules, new ModuleStateStore() {
        public Optional<Boolean> find(String id) { return Optional.ofNullable(persisted.get(id)); }
        public void update(Consumer<Map<String, Boolean>> change) { change.accept(persisted); }
    });
    private final ResourceRegistry resources = new ResourceRegistry(List.of(() -> List.of(ResourceConformanceConfiguration.definition())),
            List.of(new ProbeAdapter()), List.of(), modules);
    private final ResourceRuntime runtime = new ResourceRuntime(resources, states, id -> Optional.ofNullable(users.get(id)),
            List.of((user, permission) -> user.admin() && permitted.get() && PERMISSION.equals(permission)));
    private final StaticMessageSource messages = new StaticMessageSource();
    NavigationTests() { states.synchronize(); messages.addMessage(LABEL, Locale.ENGLISH, "Catalogue"); messages.addMessage(LABEL, Locale.of("es"), "Catálogo"); }
    private NavigationContribution.Entry entry(String id, int order, NavigationContribution.Target target, List<NavigationContribution.Entry> children) {
        return new NavigationContribution.Entry(id, LABEL, order, null, target, children, List.of());
    }
    private NavigationRegistry registry(List<NavigationContribution.Entry> entries) {
        return new NavigationRegistry(List.of(() -> List.of(new NavigationContribution(ConformanceModulesConfiguration.MATH_ID, entries))), modules, resources);
    }
    private NavigationController controller(NavigationRegistry registry) {
        return new NavigationController(new EffectiveNavigation(registry, resources, runtime, states, messages, user -> context.get()), runtime);
    }
    private NavigationRegistry normal() {
        return registry(List.of(entry("group", 10, null, List.of(
                entry("second", 20, new NavigationContribution.RouteTarget("/settings"), List.of()),
                entry("z", 10, new NavigationContribution.ResourceTarget(RESOURCE), List.of()),
                entry("a", 10, new NavigationContribution.ResourceTarget(RESOURCE), List.of())))));
    }
    @ParameterizedTest @ValueSource(strings = {"http://example.test", "https://example.test", "//external", "/a?query", "/a#fragment", "/../x", "/a/..", "/%2e%2e/x", "/a\\b", "/a//b"})
    void rejectsUnsafeRoutesAtRegistration(String route) {
        assertThatThrownBy(() -> registry(List.of(entry("id", 0, new NavigationContribution.RouteTarget(route), List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsEmptyDuplicateAndImpossibleDeclarationsAndUnknownResources() {
        assertThatThrownBy(() -> registry(List.of(entry("group", 0, null, List.of())))).isInstanceOf(IllegalArgumentException.class);
        var leaf = entry("same", 0, new NavigationContribution.RouteTarget("/"), List.of());
        assertThatThrownBy(() -> registry(List.of(leaf, leaf))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry(List.of(entry("", 0, new NavigationContribution.RouteTarget("/"), List.of())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry(List.of(entry("id", 0, new NavigationContribution.ResourceTarget("missing"), List.of())))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> registry(List.of(entry("id", 0, new NavigationContribution.RouteTarget("/"), List.of(leaf))))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void effectiveHierarchyIsLocalizedOrderedAndContainsOnlyClosedPresentation() {
        var controller = controller(normal());
        var en = controller.get(FIRST, request(null), Locale.ENGLISH);
        var es = controller.get(FIRST, request(null), Locale.of("es"));
        var body = ResourceJson.JSON.valueToTree(en.getBody());
        assertThat(body.get("items").get(0).get("children")).extracting(node -> node.get("id").asString()).containsExactly("a", "z", "second");
        assertThat(ResourceJson.JSON.writeValueAsString(en.getBody())).doesNotContain(PERMISSION, "labelKey", "permission", "authorized", "fields", "enabled");
        assertThat(en.getHeaders().getVary()).contains(HttpHeaders.AUTHORIZATION, HttpHeaders.ACCEPT_LANGUAGE);
        assertThat(es.getHeaders().getETag()).isNotEqualTo(en.getHeaders().getETag());
        assertThat(ResourceJson.JSON.writeValueAsString(es.getBody())).contains("Catálogo");
    }
    @Test void pruningPermissionModuleAndCurrentPersistedUserAreRecomputed() {
        var onlyResource = registry(List.of(entry("group", 0, null, List.of(entry("resource", 0, new NavigationContribution.ResourceTarget(RESOURCE), List.of())))));
        var controller = controller(onlyResource);
        var initial = controller.get(FIRST, request(null), Locale.ENGLISH);
        permitted.set(false);
        var denied = controller.get(FIRST, request(initial.getHeaders().getETag()), Locale.ENGLISH);
        assertEmpty(denied); assertThat(denied.getStatusCode()).isEqualTo(HttpStatus.OK);
        permitted.set(true); states.disable(ConformanceModulesConfiguration.MATH_ID);
        assertEmpty(controller.get(FIRST, request(initial.getHeaders().getETag()), Locale.ENGLISH));
        states.enable(ConformanceModulesConfiguration.MATH_ID);
        users.put(FIRST.userId(), new UserIdentity(FIRST.userId(), FIRST.email(), true, false));
        assertEmpty(controller.get(FIRST, request(initial.getHeaders().getETag()), Locale.ENGLISH));
    }
    @Test void stableEtagAnd304AreBoundToUserAndTrustedContextEvenForIdenticalTrees() {
        var controller = controller(normal());
        var initial = controller.get(FIRST, request(null), Locale.ENGLISH); String etag = initial.getHeaders().getETag();
        assertThat(controller.get(FIRST, request(null), Locale.ENGLISH).getHeaders().getETag()).isEqualTo(etag);
        var unchanged = controller.get(FIRST, request(etag), Locale.ENGLISH);
        assertThat(unchanged.getStatusCode()).isEqualTo(HttpStatus.NOT_MODIFIED); assertThat(unchanged.getBody()).isNull();
        assertThat(unchanged.getHeaders().getCacheControl()).isEqualTo("private, no-cache");
        assertThat(controller.get(SECOND, request(etag), Locale.ENGLISH).getStatusCode()).isEqualTo(HttpStatus.OK);
        context.set("other-trusted-context");
        assertThat(controller.get(FIRST, request(etag), Locale.ENGLISH).getStatusCode()).isEqualTo(HttpStatus.OK);
    }
    @Test void authenticationPrecedesQueryAndEmptyTreeIsSuccessful() {
        var controller = controller(registry(List.of())); var request = request(null); request.addParameter("unknown", "1");
        assertThatThrownBy(() -> controller.get(null, request, Locale.ENGLISH)).isInstanceOf(ResourceFailure.class)
                .extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.unauthorized);
        assertThatThrownBy(() -> controller.get(FIRST, request, Locale.ENGLISH)).isInstanceOf(ResourceFailure.class)
                .extracting(failure -> ((ResourceFailure) failure).code()).isEqualTo(ResourceFailure.Code.invalid_request);
        assertEmpty(controller.get(FIRST, request(null), Locale.ENGLISH));
    }
    private void assertEmpty(ResponseEntity<?> response) { assertThat(ResourceJson.JSON.valueToTree(response.getBody()).get("items").isEmpty()).isTrue(); }
    private MockHttpServletRequest request(String etag) {
        var request = new MockHttpServletRequest("GET", NavigationController.PATH);
        if (etag != null) request.addHeader(HttpHeaders.IF_NONE_MATCH, etag); return request;
    }
}
