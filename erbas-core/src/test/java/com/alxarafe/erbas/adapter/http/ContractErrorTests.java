package com.alxarafe.erbas.adapter.http;

import java.util.List;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.JsonNode;

import static org.assertj.core.api.Assertions.*;

class ContractErrorTests {
    private final JsonMapper json = new JsonMapper();

    @Test
    void legacyErrorIsExactlyTheRequiredClosedEnvelope() {
        assertThat(json.readTree(ContractError.json("unauthorized")))
                .isEqualTo(json.readTree("{\"code\":\"unauthorized\",\"errors\":[{\"code\":\"unauthorized\"}]}"));
    }

    @Test
    void optionalContextIsSerializedExactlyWithoutNullOrInternalFields() {
        var error = new ContractError("fixture_conflict", List.of(
                new ContractError.Detail("fixture_detail", "fixture_resource", "opaque", "/a~1b/~0value"),
                new ContractError.Detail("fixture_other", null, null, null)));
        JsonNode serialized = json.valueToTree(error);
        assertThat(serialized).isEqualTo(json.readTree("""
                {"code":"fixture_conflict","errors":[
                  {"code":"fixture_detail","resource":"fixture_resource","resourceId":"opaque","path":"/a~1b/~0value"},
                  {"code":"fixture_other"}]}
                """));
    }

    @Test
    void detailsCannotBeAbsentEmptyOrLackACode() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ContractError("fixture", List.of()));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContractError(null, List.of(new ContractError.Detail("fixture", null, null, null))));
        assertThatIllegalArgumentException().isThrownBy(() -> new ContractError.Detail(null, null, null, null));
    }
}
