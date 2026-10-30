package com.alxarafe.erbas.read;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.capabilities.Capability;
import com.alxarafe.erbas.modularity.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class ReadPrimitiveTests {
    private static final String OWNER = "example.inventory";
    private static final String ID = "example.inventory.list";
    private static final String PERMISSION = "example.inventory.read";
    private static final String PRIVATE_PAYLOAD = "private-input-output-secret";
    private static final UserIdentity ADMIN = new UserIdentity(7, "private@example.invalid", true, true);
    private final JsonMapper json = new JsonMapper();
    private final AtomicReference<UserIdentity> persistedUser = new AtomicReference<>(ADMIN);
    private final Map<String, Boolean> persistedModules = new HashMap<>();
    private final List<ReadPrimitiveAuditSink.Entry> audit = new ArrayList<>();
    private final ModuleRegistry modules = new ModuleRegistry(List.of(() -> List.of(new ErbasModule() {
        public ModuleDescriptor descriptor() { return new ModuleDescriptor(OWNER, ModuleApi.CURRENT_VERSION, List.of()); }
        public void initialize() { }
    })));
    private final ModuleStateService states = new ModuleStateService(modules, new ModuleStateStore() {
        public Optional<Boolean> find(String id) { return Optional.ofNullable(persistedModules.get(id)); }
        public void update(Consumer<Map<String, Boolean>> change) { change.accept(persistedModules); }
    });
    private final Executor executor = new Executor();
    private boolean granted = true;
    private ReadPrimitiveRouter router() {
        states.synchronize();
        return new ReadPrimitiveRouter(new ReadPrimitiveRegistry(List.of(executor), modules), states,
                id -> Optional.ofNullable(persistedUser.get()),
                List.of((user, permission) -> user.admin() && granted && PERMISSION.equals(permission)), audit::add);
    }
    private ReadPrimitive descriptor(String id, String owner, String effect) {
        return new ReadPrimitive(id, owner, "Fixture inventory", "Read fixture state", effect,
                json.readTree("{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}"), List.of(PERMISSION));
    }
    @Test void registrationIsExplicitDeterministicAndEmptyProductionInventoryIsSupported() {
        var registry = new ReadPrimitiveRegistry(List.of(executor), modules);
        assertThat(registry.primitives()).containsExactly(executor.descriptor());
        assertThat(new ReadPrimitiveRegistry(List.of(), modules).primitives()).isEmpty();
        assertThatThrownBy(() -> new ReadPrimitiveRegistry(List.of(executor, executor), modules))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Duplicate");
        executor.owner = "unknown.module";
        assertThatThrownBy(() -> new ReadPrimitiveRegistry(List.of(executor), modules)).isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest @ValueSource(strings = {"", "UPPER.inventory.list", "inventory.list", "a.b.c.d", "a-.b.c", "https://example.test", "/api/wms/uoms"})
    void invalidIdentityCannotRegisterOrDispatch(String id) {
        assertThatThrownBy(() -> descriptor(id, OWNER, Capability.READ)).isInstanceOf(IllegalArgumentException.class);
        assertThat(router().invoke(ADMIN.userId(), id, json.createObjectNode(), null).failure()).isEqualTo(ReadPrimitiveRouter.Failure.INVALID_ID);
        assertThat(executor.calls).isZero();
    }
    @Test void writeDeclarationsAreRejectedAndSchemaCannotBeMutated() {
        assertThatThrownBy(() -> descriptor(ID, OWNER, Capability.WRITE)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReadPrimitive(ID, OWNER, "Fixture", "Fixture", Capability.READ,
                json.createObjectNode(), List.of())).isInstanceOf(IllegalArgumentException.class);
        var declaration = executor.descriptor();
        ((tools.jackson.databind.node.ObjectNode) declaration.inputSchema()).removeAll();
        assertThat(declaration.inputSchema().isEmpty()).isFalse();
    }
    @Test void discoveryReadsCurrentPersistedIdentityAndChecksPermissionAndModule() {
        var router = router();
        assertThat(router.effective(ADMIN.userId())).extracting(ReadPrimitive::id).containsExactly(ID);
        granted = false;
        assertThat(router.effective(ADMIN.userId())).isEmpty();
        granted = true;
        persistedUser.set(new UserIdentity(ADMIN.userId(), ADMIN.email(), true, false));
        assertThat(router.effective(ADMIN.userId())).isEmpty();
        persistedUser.set(ADMIN);
        states.disable(OWNER);
        assertThat(router.effective(ADMIN.userId())).isEmpty();
        states.enable(OWNER);
        assertThat(router.effective(ADMIN.userId())).hasSize(1);
        persistedUser.set(null);
        assertThat(router.effective(ADMIN.userId())).isEmpty();
    }
    @ParameterizedTest @ValueSource(strings = {"revocation", "demotion", "disabled", "deleted", "module"})
    void discoveryDoesNotAuthorizeLaterExecution(String change) {
        var router = router();
        assertThat(router.effective(ADMIN.userId())).hasSize(1);
        switch (change) {
            case "revocation" -> granted = false;
            case "demotion" -> persistedUser.set(new UserIdentity(ADMIN.userId(), ADMIN.email(), true, false));
            case "disabled" -> persistedUser.set(new UserIdentity(ADMIN.userId(), ADMIN.email(), false, true));
            case "deleted" -> persistedUser.set(null);
            case "module" -> states.disable(OWNER);
            default -> throw new AssertionError();
        }
        var denied = router.invoke(ADMIN.userId(), ID, json.createObjectNode(), null);
        assertThat(denied.failure()).isNotNull();
        assertThat(denied.execution()).isNull();
        assertThat(executor.calls).isZero();
        assertThat(audit.getLast().outcome()).isEqualTo(ReadPrimitiveAuditSink.Outcome.denied);
        assertThat(audit.getLast().module()).isNull();
    }
    @Test void unknownIdentityAndUnknownPrimitiveNeverExecuteRegisteredCode() {
        var router = router();
        assertThat(router.invoke(ADMIN.userId() + 1, ID, json.createObjectNode(), null).failure())
                .isEqualTo(ReadPrimitiveRouter.Failure.UNAUTHENTICATED);
        assertThat(router.invoke(ADMIN.userId(), "example.unknown.list", json.createObjectNode(), null).failure())
                .isEqualTo(ReadPrimitiveRouter.Failure.UNKNOWN);
        assertThat(executor.calls).isZero();
    }
    @ParameterizedTest @ValueSource(strings = {"null", "[]", "1", "\"input\"", "{\"user\":7}", "{\"url\":\"https://example.test\"}"})
    void untrustedInputMustPassRegisteredSchemaValidator(String input) {
        assertThat(router().invoke(ADMIN.userId(), ID, json.readTree(input), null).failure())
                .isEqualTo(ReadPrimitiveRouter.Failure.INVALID_INPUT);
        assertThat(executor.calls).isZero();
    }
    @Test void repeatedExecutionHasFreshIdentityTrustedContextAndPayloadFreeAudit() {
        var router = router();
        var origin = new ReadPrimitiveRouter.Origin("ai-parent", null, 1);
        var first = router.invoke(ADMIN.userId(), ID, json.createObjectNode(), origin);
        var second = router.invoke(ADMIN.userId(), ID, json.createObjectNode(), origin);
        assertThat(first.failure()).isNull();
        assertThat(first.execution().output().get("value").asString()).isEqualTo(PRIVATE_PAYLOAD);
        assertThat(first.execution().invocationId()).isNotBlank().isNotEqualTo(second.execution().invocationId());
        assertThat(executor.context.subject()).isEqualTo(ADMIN);
        assertThat(executor.context.origin()).isEqualTo(origin);
        assertThat(executor.context.module()).isEqualTo(OWNER);
        assertThat(executor.context.primitiveId()).isEqualTo(ID);
        assertThat(audit).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.invocationId()).isNotBlank();
            assertThat(entry.primitiveId()).isEqualTo(ID);
            assertThat(entry.subject()).isEqualTo(ADMIN.getName());
            assertThat(entry.origin()).isEqualTo(origin);
            assertThat(entry.outcome()).isEqualTo(ReadPrimitiveAuditSink.Outcome.completed);
            assertThat(entry.timestamp()).isNotNull();
            assertThat(entry.duration().isNegative()).isFalse();
            assertThat(entry.toString()).doesNotContain(PRIVATE_PAYLOAD, ADMIN.email(), "private-exception");
        });
    }
    @ParameterizedTest @ValueSource(strings = {"exception", "invalid-output", "null-output"})
    void failuresHaveSafeAuditAndNoSuccessfulExecution(String fault) {
        executor.fault = fault;
        var result = router().invoke(ADMIN.userId(), ID, json.createObjectNode(), null);
        assertThat(result.failure()).isEqualTo(ReadPrimitiveRouter.Failure.EXECUTION_FAILED);
        assertThat(result.execution()).isNull();
        assertThat(audit.getLast().outcome()).isEqualTo(ReadPrimitiveAuditSink.Outcome.failed);
        assertThat(audit.getLast().toString()).doesNotContain(PRIVATE_PAYLOAD, "private-exception");
    }
    private final class Executor implements ReadPrimitiveExecutor {
        String owner = OWNER;
        String fault = "";
        int calls;
        Context context;
        public ReadPrimitive descriptor() { return ReadPrimitiveTests.this.descriptor(ID, owner, Capability.READ); }
        public Object validateInput(JsonNode input) {
            if (input == null || !input.isObject() || !input.isEmpty()) throw new InvalidInput();
            return input;
        }
        public Object execute(Context context, Object input) {
            this.context = context; calls++;
            if (fault.equals("exception")) throw new IllegalStateException("private-exception");
            return fault.equals("null-output") ? null : Map.of("value", PRIVATE_PAYLOAD);
        }
        public boolean validOutput(Object output, Object input) { return !fault.equals("invalid-output"); }
    }
}
