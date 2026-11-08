package com.alxarafe.erbas.assets.infrastructure;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.alxarafe.erbas.assets.AssetResource;
import com.alxarafe.erbas.assets.AssetsModule;
import com.alxarafe.erbas.auth.application.UserIdentity;
import com.alxarafe.erbas.capabilities.Capability;
import com.alxarafe.erbas.read.ReadPrimitive;
import com.alxarafe.erbas.read.ReadPrimitiveExecutor;
import com.alxarafe.erbas.resources.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.json.JsonMapper;
import static com.alxarafe.erbas.assets.infrastructure.AssetListReadPrimitive.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssetListReadPrimitiveTests {
    private final ResourceRuntime runtime = mock(ResourceRuntime.class);
    private final AssetListReadPrimitive primitive = new AssetListReadPrimitive(runtime);
    private final JsonMapper json = new JsonMapper();

    @Test void publishesClosedReadDescriptor() {
        var descriptor = primitive.descriptor();
        assertThat(descriptor.id()).isEqualTo(ID);
        assertThat(ReadPrimitive.validId(ID)).isTrue();
        assertThat(descriptor.module()).isEqualTo(AssetsModule.ID);
        assertThat(descriptor.effect()).isEqualTo(Capability.READ);
        assertThat(descriptor.requiredPermissions()).containsExactly(AssetResource.READ);
        assertThat(descriptor.description()).contains(Integer.toString(ResourceQuery.DEFAULT_LIMIT));
        assertThat(descriptor.inputSchema()).isEqualTo(json.readTree(
                "{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}"));
        assertThat(primitive.validateInput(json.createObjectNode())).isEqualTo(Boolean.TRUE);
        assertThatThrownBy(() -> primitive.validateInput(null)).isInstanceOf(ReadPrimitiveExecutor.InvalidInput.class);
    }

    @ParameterizedTest @ValueSource(strings = {"null", "[]", "true", "1", "\"text\"", "{\"limit\":10}", "{\"offset\":0}"})
    void rejectsEveryNonemptyOrNonobjectInput(String input) {
        assertThatThrownBy(() -> primitive.validateInput(json.readTree(input))).isInstanceOf(ReadPrimitiveExecutor.InvalidInput.class);
        verifyNoInteractions(runtime);
    }

    private Map<String, Object> page() {
        return new HashMap<>(Map.of(ITEMS, List.of(json.createObjectNode().put(AssetResource.NAME, "Fixture")),
                OFFSET, BigInteger.ZERO, LIMIT, ResourceQuery.DEFAULT_LIMIT, TOTAL, BigInteger.ONE));
    }

    @Test void acceptsRuntimePagesWithKnownOrUnavailableTotal() {
        var page = page();
        assertThat(primitive.validOutput(page, Boolean.TRUE)).isTrue();
        page.remove(TOTAL);
        assertThat(primitive.validOutput(page, Boolean.TRUE)).isTrue();
        page.put(ITEMS, List.of());
        assertThat(primitive.validOutput(page, Boolean.TRUE)).isTrue();
        page.put(TOTAL, BigInteger.ZERO);
        assertThat(primitive.validOutput(page, Boolean.TRUE)).isTrue();
    }

    @Test void rejectsMalformedAndInconsistentPages() {
        assertThat(primitive.validOutput(null, Boolean.TRUE)).isFalse();
        assertThat(primitive.validOutput(List.of(), Boolean.TRUE)).isFalse();
        for (String field : List.of(ITEMS, OFFSET, LIMIT)) {
            var page = page(); page.remove(field);
            assertThat(primitive.validOutput(page, Boolean.TRUE)).as("missing %s", field).isFalse();
        }
        var faults = List.of(Map.entry(OFFSET, BigInteger.ONE), Map.entry(OFFSET, BigInteger.ONE.negate()),
                Map.entry(OFFSET, 0), Map.entry(LIMIT, ResourceQuery.DEFAULT_LIMIT + 1),
                Map.entry(LIMIT, BigInteger.valueOf(ResourceQuery.DEFAULT_LIMIT)),
                Map.entry(TOTAL, BigInteger.ZERO), Map.entry(TOTAL, BigInteger.ONE.negate()), Map.entry(TOTAL, 1),
                Map.entry(ITEMS, List.of(Map.of(AssetResource.NAME, "Unprojected"))),
                Map.entry(ITEMS, List.of(json.createArrayNode())));
        for (var fault : faults) {
            var page = page(); page.put(fault.getKey(), fault.getValue());
            assertThat(primitive.validOutput(page, Boolean.TRUE)).as("invalid %s", fault.getKey()).isFalse();
        }
        var page = page(); page.put(TOTAL, null);
        assertThat(primitive.validOutput(page, Boolean.TRUE)).isFalse();
        var oversized = new ArrayList<>();
        for (int i = 0; i <= ResourceQuery.DEFAULT_LIMIT; i++) oversized.add(json.createObjectNode());
        page.put(ITEMS, oversized); page.put(TOTAL, BigInteger.valueOf(oversized.size()));
        assertThat(primitive.validOutput(page, Boolean.TRUE)).isFalse();
    }

    @Test void delegatesCurrentSubjectAndFixedFirstPageToResourceRuntime() {
        var user = new UserIdentity(1L, "test@example.invalid", true, false);
        var context = new ReadPrimitiveExecutor.Context("fixture-invocation", ID, AssetsModule.ID, user, null);
        var page = page();
        when(runtime.execute(same(user), any(), eq(ResourceOperation.list), isNull(), isNull(), any())).thenReturn(page);
        assertThat(primitive.execute(context, Boolean.TRUE)).isSameAs(page);
        var definition = ArgumentCaptor.forClass(ResourceDefinition.class);
        var query = ArgumentCaptor.forClass(ResourceQuery.Transport.class);
        verify(runtime).execute(same(user), definition.capture(), eq(ResourceOperation.list), isNull(), isNull(), query.capture());
        assertThat(definition.getValue().id()).isEqualTo(AssetResource.RESOURCE);
        assertThat(query.getValue()).isEqualTo(new ResourceQuery.Transport(
                BigInteger.ZERO, ResourceQuery.DEFAULT_LIMIT, null, List.of(), List.of()));
    }
}
