package com.alxarafe.erbas.ai;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import com.alxarafe.erbas.ai.infrastructure.DeterministicAssistantProvider;
import com.alxarafe.erbas.capabilities.Capability;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.context.support.ResourceBundleMessageSource;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static com.alxarafe.erbas.ai.infrastructure.DeterministicAssistantProvider.*;

class DeterministicAssistantProviderTests {
    private final JsonMapper json = new JsonMapper();
    private final DeterministicAssistantProvider provider;
    DeterministicAssistantProviderTests() {
        var messages = new ResourceBundleMessageSource();
        messages.setBasename("i18n/ai/messages"); messages.setDefaultLocale(Locale.ENGLISH); messages.setFallbackToSystemLocale(false);
        provider = new DeterministicAssistantProvider(messages);
        LocaleContextHolder.setLocale(Locale.ENGLISH);
    }
    @AfterEach void resetRequestLocale() { LocaleContextHolder.resetLocaleContext(); }
    @Test void greetingAndUnknownRequestsDoNotClaimCurrentBusinessState() {
        assertThat(provider.respond(GREETING_REQUEST, List.of(), List.of())).isInstanceOf(AiAssistantProvider.Answer.class);
        var unavailable = answer(provider.respond(ONE_READ_REQUEST, List.of(), List.of()));
        assertThat(unavailable).contains("cannot obtain");
        assertThat(answer(provider.respond("a".repeat(AiText.MESSAGE_MAXIMUM_SCALARS), List.of(), List.of())))
                .isEqualTo(unavailable);
    }
    @Test void obtainsFactsThroughToolsAndBuildsAnswerFromReturnedData() {
        var tools = List.of(descriptor(UOMS_LIST));
        var call = provider.respond(ONE_READ_REQUEST, tools, List.of());
        assertThat(call).isInstanceOf(AiAssistantProvider.ToolCall.class);
        assertThat(((AiAssistantProvider.ToolCall) call).capabilityId()).isEqualTo(UOMS_LIST);
        assertThat(((AiAssistantProvider.ToolCall) call).input().size()).isZero();
        String first = answer(provider.respond(ONE_READ_REQUEST, tools, List.of(result(UOMS_LIST, "FIRST", "Fresh database fact"))));
        String changed = answer(provider.respond(ONE_READ_REQUEST, tools, List.of(result(UOMS_LIST, "SECOND", "Changed database fact"))));
        assertThat(first).contains("FIRST", "Fresh database fact").doesNotContain("SECOND");
        assertThat(changed).contains("SECOND", "Changed database fact").doesNotContain("FIRST");
    }
    @Test void multipleToolsFollowFixtureIntentOrderAndRespectEffectiveCatalogue() {
        var tools = List.of(descriptor(ITEM_FAMILIES_LIST), descriptor(STORAGE_ATTRIBUTES_LIST), descriptor(UOMS_LIST));
        var results = new java.util.ArrayList<AiAssistantProvider.ToolResult>();
        for (String id : List.of(UOMS_LIST, STORAGE_ATTRIBUTES_LIST, ITEM_FAMILIES_LIST)) {
            var call = (AiAssistantProvider.ToolCall) provider.respond(MULTIPLE_READ_REQUEST, tools, results);
            assertThat(call.capabilityId()).isEqualTo(id);
            results.add(result(id, "DATA", "From capability"));
        }
        assertThat(provider.respond(MULTIPLE_READ_REQUEST, tools, results)).isInstanceOf(AiAssistantProvider.Answer.class);
        var empty = provider.respond(MULTIPLE_READ_REQUEST, List.of(), List.of());
        assertThat(answer(empty)).contains("cannot obtain");
        var partial = List.of(descriptor(STORAGE_ATTRIBUTES_LIST));
        assertThat(((AiAssistantProvider.ToolCall) provider.respond(MULTIPLE_READ_REQUEST, partial, List.of())).capabilityId())
                .isEqualTo(STORAGE_ATTRIBUTES_LIST);
        assertThat(answer(provider.respond(MULTIPLE_READ_REQUEST, partial, List.of(result(STORAGE_ATTRIBUTES_LIST, "DATA", "Actual fact")))))
                .contains("Actual fact", "cannot obtain");
    }
    @Test void nativeLocaleNegotiationResourcesHaveDeterministicEnglishFallback() {
        String english = answer(provider.respond(GREETING_REQUEST, List.of(), List.of()));
        LocaleContextHolder.setLocale(Locale.of("es", "ES"));
        String spanish = answer(provider.respond(GREETING_REQUEST, List.of(), List.of()));
        assertThat(spanish).isNotBlank().isNotEqualTo(english);
        LocaleContextHolder.setLocale(Locale.FRENCH);
        assertThat(answer(provider.respond(GREETING_REQUEST, List.of(), List.of()))).isEqualTo(english);
    }
    private String answer(AiAssistantProvider.Turn turn) { return ((AiAssistantProvider.Answer) turn).text(); }
    private AiAssistantProvider.Tool descriptor(String id) {
        return new AiAssistantProvider.Tool(AiAssistantProvider.Kind.CAPABILITY, id,
                "Fixture catalogue", "Read actual fixture data.", json.createObjectNode());
    }
    private AiAssistantProvider.ToolResult result(String id, String code, String label) {
        return new AiAssistantProvider.ToolResult(AiAssistantProvider.Kind.CAPABILITY, id, json.valueToTree(Map.of("total", 1,
                "items", List.of(Map.of("code", code, "name", label)))));
    }
}
