package com.alxarafe.erbas.assets.infrastructure;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import com.alxarafe.erbas.assets.AssetResource;
import com.alxarafe.erbas.assets.AssetsModule;
import com.alxarafe.erbas.capabilities.Capability;
import com.alxarafe.erbas.read.ReadPrimitive;
import com.alxarafe.erbas.read.ReadPrimitiveExecutor;
import com.alxarafe.erbas.resources.ResourceOperation;
import com.alxarafe.erbas.resources.ResourceQuery;
import com.alxarafe.erbas.resources.ResourceRuntime;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Governed first-page read; resource authorization and projection remain in Core. */
public final class AssetListReadPrimitive implements ReadPrimitiveExecutor {
    public static final String ID = AssetResource.RESOURCE + "." + ResourceOperation.list.name();
    public static final String ITEMS = "items", OFFSET = "offset", LIMIT = "limit", TOTAL = "total";
    private final ResourceRuntime runtime;

    public AssetListReadPrimitive(ResourceRuntime runtime) { this.runtime = runtime; }

    @Override public ReadPrimitive descriptor() {
        var mapper = new JsonMapper();
        var schema = mapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", mapper.createObjectNode());
        schema.put("additionalProperties", false);
        return new ReadPrimitive(ID, AssetsModule.ID, "List assets",
                "Lists up to %d assets from the first page.".formatted(ResourceQuery.DEFAULT_LIMIT),
                Capability.READ, schema, List.of(AssetResource.READ));
    }

    @Override public Object validateInput(JsonNode input) {
        if (input == null || !input.isObject() || !input.isEmpty()) throw new InvalidInput();
        return Boolean.TRUE;
    }

    @Override public Object execute(Context context, Object input) {
        var query = new ResourceQuery.Transport(BigInteger.ZERO, ResourceQuery.DEFAULT_LIMIT, null, List.of(), List.of());
        return runtime.execute(context.subject(), AssetResource.definition(), ResourceOperation.list, null, null, query);
    }

    @Override public boolean validOutput(Object output, Object input) {
        if (!(output instanceof Map<?, ?> page)
                || !(page.get(ITEMS) instanceof List<?> items)
                || !(page.get(OFFSET) instanceof BigInteger offset)
                || !(page.get(LIMIT) instanceof Integer limit)) return false;
        // ResourceRuntime omits total when the adapter cannot supply it; explicit null is not its projection.
        if (page.containsKey(TOTAL) && (!(page.get(TOTAL) instanceof BigInteger total)
                || total.compareTo(BigInteger.valueOf(items.size())) < 0)) return false;
        return offset.signum() == 0 && limit == ResourceQuery.DEFAULT_LIMIT && items.size() <= limit
                && items.stream().allMatch(item -> item instanceof JsonNode node && node.isObject());
    }
}
