package com.alxarafe.erbas.capabilities;

import java.util.*;
import java.util.function.Consumer;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import static org.assertj.core.api.Assertions.*;
import static com.alxarafe.erbas.capabilities.CapabilityTests.*;
import static com.alxarafe.erbas.capabilities.CapabilityRegistryTests.modules;

class CapabilityInvocationTests {
    private static final UserIdentity USER = new UserIdentity(7, "private@example.invalid", true, true);
    private static final String BODY = "{\"input\":{}}";
    private final List<CapabilityInvocationAuditSink.Entry> audit = new ArrayList<>();
    private final Map<String, Boolean> persisted = new HashMap<>();
    private final ModuleRegistry modules = modules(OWNER);
    private final ModuleStateService states = new ModuleStateService(modules, new ModuleStateStore() {
        public Optional<Boolean> find(String id) { return Optional.ofNullable(persisted.get(id)); }
        public void update(Consumer<Map<String, Boolean>> change) { change.accept(persisted); }
    });
    private boolean granted = true;
    private final CapabilityRegistry descriptors = registry(capability(FIRST, List.of(PERMISSION)));
    private final CapabilityDiscovery discovery = new CapabilityDiscovery(descriptors, states,
            List.of((user, permission) -> granted));
    private final Handler handler = new Handler();

    private CapabilityRegistry registry(Capability capability) {
        return new CapabilityRegistry(List.of(() -> List.of(capability)), modules, SCHEMAS);
    }
    private CapabilityInvocation service() {
        states.synchronize();
        return new CapabilityInvocation(discovery, new CapabilityInvocationRegistry(descriptors, List.of(handler)), audit::add);
    }
    @Test void generatesDistinctOpaqueIdsAndTrustedContextAndSafeCompletedAudit() {
        var service = service();
        var first = service.invoke(USER, FIRST, () -> BODY);
        var second = service.invoke(USER, FIRST, () -> BODY);
        assertThat(first.errorCode()).isNull();
        assertThat(first.envelope().invocationId()).isNotBlank().isNotEqualTo(second.envelope().invocationId());
        assertThat(first.envelope().capabilityId()).isEqualTo(FIRST);
        assertThat(handler.context.invocationId()).isEqualTo(second.envelope().invocationId());
        assertThat(handler.context.subject()).isEqualTo(USER);
        assertThat(handler.context.module()).isEqualTo(OWNER);
        assertThat(handler.context.capabilityId()).isEqualTo(FIRST);
        assertThat(handler.context.caller()).isEqualTo(CapabilityInvocationContext.USER_CALLER);
        assertThat(handler.context.effectivePermissions()).containsExactly(PERMISSION);
        assertThat(handler.context.correlationId()).isNull();
        assertThat(audit).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.outcome()).isEqualTo(CapabilityInvocationAuditSink.Outcome.completed);
            assertThat(entry.subject()).isEqualTo(USER.getName());
            assertThat(entry.timestamp()).isNotNull();
            assertThat(entry.duration().isNegative()).isFalse();
            assertThat(entry.toString()).doesNotContain(USER.email(), BODY, "secret-provider", "private-output");
        });
    }
    @Test void providerExceptionAndKnownInvalidOutputHaveIdenticalSafeFailureAndInternalIds() {
        var service = service();
        handler.failure = true;
        var exception = service.invoke(USER, FIRST, () -> BODY);
        handler.failure = false; handler.valid = false;
        var invalid = service.invoke(USER, FIRST, () -> BODY);
        assertThat(exception).isEqualTo(invalid);
        assertThat(exception.errorCode()).isEqualTo(CapabilityInvocation.FAILED);
        assertThat(exception.envelope()).isNull();
        assertThat(audit).allSatisfy(entry -> {
            assertThat(entry.invocationId()).isNotBlank();
            assertThat(entry.outcome()).isEqualTo(CapabilityInvocationAuditSink.Outcome.failed);
            assertThat(entry.errorCode()).isEqualTo(CapabilityInvocation.FAILED);
            assertThat(entry.toString()).doesNotContain("secret-provider", "private-output");
        });
    }
    @Test void controllerReturnsExactClosedSafeFailureForExceptionAndInvalidOutput() {
        var controller = new com.alxarafe.erbas.capabilities.http.CapabilityInvocationController(service());
        var request = new org.springframework.mock.web.MockHttpServletRequest();
        request.setContent(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var json = new tools.jackson.databind.json.JsonMapper();
        handler.failure = true;
        var exception = controller.invoke(USER, FIRST, request);
        handler.failure = false; handler.valid = false;
        var fresh = new org.springframework.mock.web.MockHttpServletRequest();
        fresh.setContent(BODY.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var invalid = controller.invoke(USER, FIRST, fresh);
        assertThat(exception.getStatusCode()).isEqualTo(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(invalid.getStatusCode()).isEqualTo(exception.getStatusCode());
        assertThat(json.<JsonNode>valueToTree(exception.getBody())).isEqualTo(json.readTree(
                "{\"code\":\"capability_execution_failed\",\"errors\":[{\"code\":\"capability_execution_failed\"}]}"));
        assertThat(json.<JsonNode>valueToTree(invalid.getBody())).isEqualTo(json.<JsonNode>valueToTree(exception.getBody()));
        assertThat(exception.getHeaders().getContentType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_JSON);
        assertThat(exception.getHeaders().getCacheControl()).isEqualTo("no-store");
        assertThat(exception.getHeaders().containsHeader(org.springframework.http.HttpHeaders.WWW_AUTHENTICATE)).isFalse();
    }
    @Test void reauthorizesAndRechecksModuleOnEveryInvocationBeforeTouchingBody() {
        var service = service();
        assertThat(discovery.findEffective(USER, FIRST)).isPresent();
        states.disable(OWNER);
        assertThat(service.invoke(USER, FIRST, () -> { throw new AssertionError("body accessed"); }).errorCode())
                .isEqualTo(CapabilityInvocation.NOT_FOUND);
        assertThat(audit.getLast().module()).isNull();
        states.enable(OWNER); granted = false;
        assertThat(service.invoke(USER, FIRST, () -> { throw new AssertionError("body accessed"); }).errorCode())
                .isEqualTo(CapabilityInvocation.NOT_FOUND);
        granted = true;
        assertThat(service.invoke(USER, FIRST, () -> BODY).errorCode()).isNull();
        assertThat(audit.getFirst().outcome()).isEqualTo(CapabilityInvocationAuditSink.Outcome.denied);
    }
    @Test void authenticationAndIdPrecedeLookupAndParsingWithoutInventingAuditIdentity() {
        var service = service();
        assertThat(service.invoke(null, "INVALID", () -> { throw new AssertionError(); }).errorCode())
                .isEqualTo(CapabilityInvocation.UNAUTHORIZED);
        assertThat(audit.getLast().subject()).isNull();
        assertThat(audit.getLast().module()).isNull();
        assertThat(service.invoke(USER, "INVALID", () -> { throw new AssertionError(); }).errorCode())
                .isEqualTo(CapabilityInvocation.INVALID_REQUEST);
        assertThat(handler.calls).isZero();
    }
    @Test void validatesExplicitUniqueOwnedReadRegistration() {
        assertThat(new CapabilityInvocationRegistry(descriptors, List.of(handler)).handler(FIRST)).isSameAs(handler);
        assertThatThrownBy(() -> new CapabilityInvocationRegistry(descriptors, List.of(handler, handler)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapabilityInvocationRegistry(descriptors, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        handler.id = SECOND;
        assertThatThrownBy(() -> new CapabilityInvocationRegistry(descriptors, List.of(handler)))
                .isInstanceOf(IllegalArgumentException.class);
        handler.id = FIRST; handler.owner = "other.module";
        assertThatThrownBy(() -> new CapabilityInvocationRegistry(descriptors, List.of(handler)))
                .isInstanceOf(IllegalArgumentException.class);
        handler.owner = OWNER;
        var read = capability(FIRST, List.of());
        var write = new Capability(read.id(), read.module(), read.version(), read.name(), read.description(), Capability.WRITE,
                read.inputSchema(), read.outputSchema(), read.requiredPermissions());
        assertThatThrownBy(() -> new CapabilityInvocationRegistry(registry(write), List.of(handler)))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void futureWriteDescriptorCannotExecuteOrReadBody() {
        states.synchronize();
        var read = capability(FIRST, List.of());
        var write = new Capability(read.id(), read.module(), read.version(), read.name(), read.description(), Capability.WRITE,
                read.inputSchema(), read.outputSchema(), read.requiredPermissions());
        var declarations = registry(write);
        var writes = new CapabilityInvocation(new CapabilityDiscovery(declarations, states, List.of()),
                new CapabilityInvocationRegistry(declarations, List.of()), audit::add);
        assertThat(writes.invoke(USER, FIRST, () -> { throw new AssertionError("WRITE body read"); }).errorCode())
                .isEqualTo(CapabilityInvocation.NOT_FOUND);
        assertThat(audit.getLast().module()).isNull();
    }
    @Test void auditFailureCannotExposeAnUnexpectedHttpEnvelope() {
        states.synchronize();
        var service = new CapabilityInvocation(discovery, new CapabilityInvocationRegistry(descriptors, List.of(handler)),
                entry -> { throw new IllegalStateException("private-audit-failure"); });
        var result = service.invoke(USER, FIRST, () -> BODY);
        assertThat(result.errorCode()).isEqualTo(CapabilityInvocation.FAILED);
        assertThat(result.envelope()).isNull();
    }
    private static final class Handler implements CapabilityInvocationHandler {
        String id = FIRST;
        String owner = OWNER;
        boolean failure;
        boolean valid = true;
        int calls;
        CapabilityInvocationContext context;
        public String capabilityId() { return id; }
        public String module() { return owner; }
        public Object validateInput(JsonNode input) { return CapabilityListInput.parse(input); }
        public Object execute(CapabilityInvocationContext context, Object input) {
            calls++; this.context = context;
            if (failure) throw new IllegalStateException("secret-provider");
            return Map.of("fixture", "private-output");
        }
        public boolean validOutput(Object output, Object input) { return valid; }
    }
}
