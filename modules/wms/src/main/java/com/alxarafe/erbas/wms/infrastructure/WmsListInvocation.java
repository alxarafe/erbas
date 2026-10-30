package com.alxarafe.erbas.wms.infrastructure;

import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.function.Function;
import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.capabilities.*;
import com.alxarafe.erbas.wms.WmsModule;
import tools.jackson.databind.JsonNode;

/** Provider-owned typed catalogue adapter. Business execution never traverses HTTP. */
public final class WmsListInvocation<T> implements CapabilityInvocationHandler {
    public static final String CODE_FIELD = "code";
    public static final List<PagedCollection.Order> CODE_ASCENDING = List.of(new PagedCollection.Order(CODE_FIELD, "asc"));
    private final String id;
    private final Class<T> itemType;
    private final Function<T, String> code;
    private final Function<PagedCollection.Pagination, PagedCollection<T>> catalogue;
    public WmsListInvocation(String id, Class<T> itemType, Function<T, String> code,
            Function<PagedCollection.Pagination, PagedCollection<T>> catalogue) {
        this.id = id; this.itemType = itemType; this.code = code; this.catalogue = catalogue;
    }
    @Override public String capabilityId() { return id; }
    @Override public String module() { return WmsModule.ID; }
    @Override public Object validateInput(JsonNode input) { return CapabilityListInput.parse(input); }
    @Override public Object execute(CapabilityInvocationContext context, Object input) {
        return catalogue.apply((PagedCollection.Pagination) input);
    }
    @Override public boolean validOutput(Object output, Object input) {
        if (!(output instanceof PagedCollection<?> page) || !(input instanceof PagedCollection.Pagination requested)
                || page.items() == null || page.total() < 0 || !requested.offset().equals(page.offset())
                || requested.limit() != page.limit() || !CODE_ASCENDING.equals(page.order())) return false;
        var available = BigInteger.valueOf(page.total()).subtract(page.offset()).max(BigInteger.ZERO);
        if (page.items().size() != available.min(BigInteger.valueOf(page.limit())).intValueExact()) return false;
        String previous = null;
        var distinct = new HashSet<Object>();
        for (var item : page.items()) {
            if (!itemType.isInstance(item) || !distinct.add(item)) return false;
            String current = code.apply(itemType.cast(item));
            if (current == null || previous != null && previous.compareTo(current) >= 0) return false;
            previous = current;
        }
        // Items are final domain records whose constructors enforce all published lexical/identity constraints.
        return true;
    }
    public static <T> PagedCollection<T> page(List<T> items, long total, PagedCollection.Pagination pagination) {
        return new PagedCollection<>(items, pagination.offset(), pagination.limit(), total, CODE_ASCENDING);
    }
}
