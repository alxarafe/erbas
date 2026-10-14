package com.alxarafe.erbas.wms.application;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;

import com.alxarafe.erbas.wms.domain.Uom;

public final class UomCatalogue {
    private final Store store;

    public UomCatalogue(Store store) { this.store = store; }

    public CreateResult create(String code, String description) {
        final Uom uom;
        try { uom = new Uom(UUID.randomUUID(), code, description); }
        catch (IllegalArgumentException invalid) { return new CreateResult(CreateOutcome.INVALID_REQUEST, null); }
        return store.insertIfCodeAvailable(uom)
                ? new CreateResult(CreateOutcome.CREATED, uom)
                : new CreateResult(CreateOutcome.CODE_CONFLICT, null);
    }

    public Window list(BigInteger offset, int limit) { return store.list(offset, limit); }

    public interface Store {
        boolean insertIfCodeAvailable(Uom uom);
        Window list(BigInteger offset, int limit);
    }

    public record Window(List<Uom> items, long total) {
        public Window { items = List.copyOf(items); }
    }
    public enum CreateOutcome { CREATED, INVALID_REQUEST, CODE_CONFLICT }
    public record CreateResult(CreateOutcome outcome, Uom uom) { }
}
