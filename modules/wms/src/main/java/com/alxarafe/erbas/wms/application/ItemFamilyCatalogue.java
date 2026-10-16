package com.alxarafe.erbas.wms.application;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import com.alxarafe.erbas.wms.domain.ItemFamily;

public final class ItemFamilyCatalogue {
    private final Store store;

    public ItemFamilyCatalogue(Store store) { this.store = store; }

    public CreateResult create(String code, String name) {
        final ItemFamily family;
        try { family = new ItemFamily(UUID.randomUUID(), code, name); }
        catch (IllegalArgumentException invalid) { return new CreateResult(CreateOutcome.INVALID_REQUEST, null); }
        return store.insertIfCodeAvailable(family)
                ? new CreateResult(CreateOutcome.CREATED, family)
                : new CreateResult(CreateOutcome.CODE_CONFLICT, null);
    }

    public Window list(BigInteger offset, int limit) { return store.list(offset, limit); }

    public interface Store {
        boolean insertIfCodeAvailable(ItemFamily family);
        Window list(BigInteger offset, int limit);
    }

    public record Window(List<ItemFamily> items, long total) {
        public Window { items = List.copyOf(items); }
    }
    public enum CreateOutcome { CREATED, INVALID_REQUEST, CODE_CONFLICT }
    public record CreateResult(CreateOutcome outcome, ItemFamily family) { }
}
