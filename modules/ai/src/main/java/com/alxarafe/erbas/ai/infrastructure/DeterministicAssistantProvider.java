package com.alxarafe.erbas.ai.infrastructure;

import java.util.List;
import java.util.Locale;
import java.util.ArrayList;
import com.alxarafe.erbas.ai.AiAssistantProvider;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import tools.jackson.databind.json.JsonMapper;

/** Validation-only fixture intents. All ERP facts come from successful CORE results. */
public final class DeterministicAssistantProvider implements AiAssistantProvider {
    public static final String UOMS_LIST = "wms.uoms.list";
    public static final String STORAGE_ATTRIBUTES_LIST = "wms.storage-attributes.list";
    public static final String ITEM_FAMILIES_LIST = "wms.item-families.list";
    public static final String ONE_READ_REQUEST = "List the available units of measure.";
    public static final String MULTIPLE_READ_REQUEST = "List the available units of measure, storage attributes and item families.";
    public static final String GREETING_REQUEST = "Reply with a short greeting.";
    private static final List<Intent> INTENTS = List.of(
            new Intent("units of measure", UOMS_LIST),
            new Intent("storage attributes", STORAGE_ATTRIBUTES_LIST),
            new Intent("item families", ITEM_FAMILIES_LIST));
    private static final String ITEMS = "items";
    private static final String TOTAL = "total";
    private static final String CODE = "code";
    private static final String DESCRIPTION = "description";
    private static final String NAME = "name";
    private static final String GREETING = "ai.conformance.greeting";
    private static final String UNOBTAINABLE = "ai.conformance.unobtainable";
    private static final String CATALOGUE = "ai.conformance.catalogue";
    private final MessageSource messages;
    private final JsonMapper json = new JsonMapper();
    public DeterministicAssistantProvider(MessageSource messages) { this.messages = messages; }

    @Override public Turn respond(String message, List<Tool> tools, List<ToolResult> results) {
        if (GREETING_REQUEST.equals(message)) return new Answer(text(GREETING));
        var requested = INTENTS.stream().filter(intent -> message.toLowerCase(Locale.ROOT).contains(intent.phrase())).toList();
        var planned = requested.stream().flatMap(intent -> tools.stream()
                .filter(tool -> tool.kind() == Kind.CAPABILITY && tool.id().equals(intent.id()))).toList();
        if (results.size() < planned.size()) {
            return new ToolCall(planned.get(results.size()).id(), json.createObjectNode());
        }
        if (results.isEmpty()) return new Answer(text(UNOBTAINABLE));
        var summaries = new ArrayList<String>();
        for (var result : results) {
            var descriptor = planned.stream().filter(tool -> tool.kind() == result.kind() && tool.id().equals(result.id())).findFirst().orElseThrow();
            var entries = new ArrayList<String>();
            for (var item : result.output().get(ITEMS)) {
                String label = item.has(DESCRIPTION) ? item.get(DESCRIPTION).asString() : item.get(NAME).asString();
                entries.add(item.get(CODE).asString() + " (" + label + ")");
            }
            summaries.add(text(CATALOGUE, descriptor.name(), result.output().get(TOTAL).asString(), String.join(", ", entries)));
        }
        if (planned.size() != requested.size()) summaries.add(text(UNOBTAINABLE));
        return new Answer(String.join(" ", summaries));
    }
    private String text(String key, Object... arguments) {
        return messages.getMessage(key, arguments, LocaleContextHolder.getLocale());
    }
    private record Intent(String phrase, String id) { }
}
