package com.alxarafe.erbas.wms.application;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import com.alxarafe.erbas.wms.domain.StorageAttribute;

public final class StorageAttributeCatalogue {
    private final Store store;

    public StorageAttributeCatalogue(Store store) { this.store = store; }

    public CreateResult create(String code, String name) {
        final StorageAttribute attribute;
        try { attribute = new StorageAttribute(UUID.randomUUID(), code, name); }
        catch (IllegalArgumentException invalid) { return new CreateResult(CreateOutcome.INVALID_REQUEST, null); }
        return store.insertIfCodeAvailable(attribute)
                ? new CreateResult(CreateOutcome.CREATED, attribute)
                : new CreateResult(CreateOutcome.CODE_CONFLICT, null);
    }

    public Window list(BigInteger offset, int limit) { return store.list(offset, limit); }

    public interface Store {
        boolean insertIfCodeAvailable(StorageAttribute attribute);
        Window list(BigInteger offset, int limit);
    }

    public record Window(List<StorageAttribute> items, long total) {
        public Window { items = List.copyOf(items); }
    }
    public enum CreateOutcome { CREATED, INVALID_REQUEST, CODE_CONFLICT }
    public record CreateResult(CreateOutcome outcome, StorageAttribute attribute) { }
}
