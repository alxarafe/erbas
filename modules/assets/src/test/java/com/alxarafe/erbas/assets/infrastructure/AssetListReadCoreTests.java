package com.alxarafe.erbas.assets.infrastructure;

import java.math.BigInteger;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import com.alxarafe.erbas.assets.AssetResource;
import com.alxarafe.erbas.assets.AssetsModule;
import com.alxarafe.erbas.assets.domain.AssetLifecycle;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.modularity.*;
import com.alxarafe.erbas.read.*;
import com.alxarafe.erbas.resources.*;
import com.alxarafe.erbas.security.FunctionalPermissionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import static com.alxarafe.erbas.assets.infrastructure.AssetListReadPrimitive.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real Core routing, authorization and projection; only the persistence boundary is simulated. */
class AssetListReadCoreTests {
    private static final UserIdentity READER = new UserIdentity(7, "asset-reader@example.invalid", true, false);
    private static final String PRIVATE_NAME = "Private fixture asset";
    private final JsonMapper json = new JsonMapper();
    private final AtomicReference<UserIdentity> currentUser = new AtomicReference<>(READER);
    private final Set<String> grants = new HashSet<>(Set.of(AssetResource.READ));
    private final List<ReadPrimitiveAuditSink.Entry> audit = new ArrayList<>();
    private final Map<String, Boolean> storedModules = new HashMap<>();
    private final ModuleRegistry modules = new ModuleRegistry(List.of(() -> List.of(new AssetsModule())));
    private final ModuleStateService states = new ModuleStateService(modules, new ModuleStateStore() {
        public Optional<Boolean> find(String id) { return Optional.ofNullable(storedModules.get(id)); }
        public void update(Consumer<Map<String, Boolean>> change) { change.accept(storedModules); }
    });
    private final ResourceExecutionAdapter persistence = mock(ResourceExecutionAdapter.class);
    private final ReadPrimitiveRouter router;
    private final ReadPrimitiveRouter.Origin origin = new ReadPrimitiveRouter.Origin("fixture-parent", null, 1);

    AssetListReadCoreTests() {
        states.synchronize();
        when(persistence.resourceId()).thenReturn(AssetResource.RESOURCE);
        when(persistence.contextAllowed(any())).thenReturn(true);
        var resources = new ResourceRegistry(List.of(() -> List.of(AssetResource.definition())), List.of(persistence), List.of(), modules);
        List<FunctionalPermissionPolicy> policies = List.of((user, permission) -> grants.contains(permission));
        var runtime = new ResourceRuntime(resources, states, id -> Optional.ofNullable(currentUser.get()), policies);
        var primitive = new AssetsConfiguration().assetListReadPrimitive(runtime);
        router = new ReadPrimitiveRouter(new ReadPrimitiveRegistry(List.of(primitive), modules), states,
                id -> Optional.ofNullable(currentUser.get()), policies, audit::add);
        clearInvocations(persistence);
    }

    private Map<String, Object> row(String name) {
        return Map.of(AssetResource.ID, UUID.randomUUID(), AssetResource.CODE, "fixture-code", AssetResource.NAME, name,
                AssetResource.LIFECYCLE, AssetLifecycle.DRAFT.name(), AssetResource.CREATED, Instant.EPOCH, AssetResource.UPDATED, Instant.EPOCH);
    }

    private ReadPrimitiveRouter.Result invoke() { return router.invoke(READER.userId(), ID, json.createObjectNode(), origin); }

    @Test void readsChangingResourceDataWithCoreProjectionAndFreshPayloadFreeAudit() {
        when(persistence.list(eq(READER), any())).thenAnswer(call -> {
            ResourceQuery query = call.getArgument(1);
            return new ResourceExecutionAdapter.ResourcePage(List.of(row(PRIVATE_NAME)), query.offset(), query.limit(), BigInteger.ONE);
        });
        assertThat(router.effective(READER.userId())).extracting(ReadPrimitive::id).containsExactly(ID);
        var first = invoke();
        assertThat(first.failure()).isNull();
        var output = first.execution().output();
        assertThat(output.path(ITEMS).get(0).path(AssetResource.NAME).asString()).isEqualTo(PRIVATE_NAME);
        assertThat(output.path(OFFSET).bigIntegerValue()).isEqualTo(BigInteger.ZERO);
        assertThat(output.path(LIMIT).intValue()).isEqualTo(ResourceQuery.DEFAULT_LIMIT);
        assertThat(output.path(TOTAL).bigIntegerValue()).isEqualTo(BigInteger.ONE);
        when(persistence.list(eq(READER), any())).thenAnswer(call -> {
            ResourceQuery query = call.getArgument(1);
            return new ResourceExecutionAdapter.ResourcePage(List.of(), query.offset(), query.limit(), null);
        });
        var second = invoke();
        assertThat(second.failure()).isNull();
        assertThat(second.execution().output().path(ITEMS).isEmpty()).isTrue();
        assertThat(second.execution().output().has(TOTAL)).isFalse();
        assertThat(second.execution().invocationId()).isNotEqualTo(first.execution().invocationId());
        verify(persistence, times(2)).list(eq(READER), argThat(query -> query.offset().signum() == 0 && query.limit() == ResourceQuery.DEFAULT_LIMIT));
        assertThat(audit).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.primitiveId()).isEqualTo(ID);
            assertThat(entry.module()).isEqualTo(AssetsModule.ID);
            assertThat(entry.subject()).isEqualTo(READER.getName());
            assertThat(entry.origin()).isEqualTo(origin);
            assertThat(entry.outcome()).isEqualTo(ReadPrimitiveAuditSink.Outcome.completed);
            assertThat(entry.failure()).isNull();
            assertThat(entry.timestamp()).isNotNull();
            assertThat(entry.duration().isNegative()).isFalse();
            assertThat(entry.toString()).doesNotContain(PRIVATE_NAME, READER.email());
        });
        assertThat(audit.getFirst().invocationId()).isEqualTo(first.execution().invocationId());
        assertThat(audit.getLast().invocationId()).isEqualTo(second.execution().invocationId());
    }

    @Test void permissionRevocationAfterDiscoveryBlocksExecutionAndIsAudited() {
        assertThat(router.effective(READER.userId())).hasSize(1);
        grants.clear(); grants.add(AssetResource.MAINTAIN);
        assertDenied(ReadPrimitiveRouter.Failure.UNAUTHORIZED);
    }

    @Test void moduleDisablementAfterDiscoveryBlocksExecutionAndEnablementRestoresDiscovery() {
        assertThat(router.effective(READER.userId())).hasSize(1);
        states.disable(AssetsModule.ID);
        assertDenied(ReadPrimitiveRouter.Failure.MODULE_DISABLED);
        states.enable(AssetsModule.ID);
        assertThat(router.effective(READER.userId())).hasSize(1);
    }

    @Test void disabledIdentityAfterDiscoveryCannotExecute() {
        assertThat(router.effective(READER.userId())).hasSize(1);
        currentUser.set(new UserIdentity(READER.userId(), READER.email(), false, false));
        assertDenied(ReadPrimitiveRouter.Failure.UNAUTHENTICATED);
    }

    private void assertDenied(ReadPrimitiveRouter.Failure failure) {
        assertThat(router.effective(READER.userId())).isEmpty();
        var result = invoke();
        assertThat(result.failure()).isEqualTo(failure);
        assertThat(result.execution()).isNull();
        verifyNoInteractions(persistence);
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo(ReadPrimitiveAuditSink.Outcome.denied);
            assertThat(entry.failure()).isEqualTo(failure);
            assertThat(entry.origin()).isEqualTo(origin);
        });
    }

    @Test void invalidArgumentsNeverReachResourcePersistenceAndAreAudited() {
        var result = router.invoke(READER.userId(), ID, json.createObjectNode().put(LIMIT, ResourceQuery.DEFAULT_LIMIT), origin);
        assertThat(result.failure()).isEqualTo(ReadPrimitiveRouter.Failure.INVALID_INPUT);
        verifyNoInteractions(persistence);
        assertThat(audit.getLast().outcome()).isEqualTo(ReadPrimitiveAuditSink.Outcome.denied);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void persistenceFailureOrInvalidResourceRowHasNoSuccessfulExecution(boolean invalidRow) {
        if (invalidRow) when(persistence.list(any(), any())).thenReturn(new ResourceExecutionAdapter.ResourcePage(
                List.of(Map.of(AssetResource.NAME, PRIVATE_NAME)), BigInteger.ZERO, ResourceQuery.DEFAULT_LIMIT, BigInteger.ONE));
        else when(persistence.list(any(), any())).thenThrow(new IllegalStateException(PRIVATE_NAME));
        var result = invoke();
        assertThat(result.failure()).isEqualTo(ReadPrimitiveRouter.Failure.EXECUTION_FAILED);
        assertThat(result.execution()).isNull();
        assertThat(audit).singleElement().satisfies(entry -> {
            assertThat(entry.outcome()).isEqualTo(ReadPrimitiveAuditSink.Outcome.failed);
            assertThat(entry.failure()).isEqualTo(ReadPrimitiveRouter.Failure.EXECUTION_FAILED);
            assertThat(entry.toString()).doesNotContain(PRIVATE_NAME);
        });
    }
}
