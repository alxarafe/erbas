package com.alxarafe.erbas.wms;

import java.math.BigInteger;
import java.util.List;
import java.util.UUID;
import com.alxarafe.erbas.adapter.http.PagedCollection;
import com.alxarafe.erbas.wms.domain.Uom;
import com.alxarafe.erbas.wms.infrastructure.WmsListInvocation;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class WmsListInvocationTests {
    private final PagedCollection.Pagination request = new PagedCollection.Pagination(BigInteger.ZERO, PagedCollection.DEFAULT_LIMIT);
    private final Uom first = new Uom(UUID.randomUUID(), "AAA", "First");
    private final Uom second = new Uom(UUID.randomUUID(), "BBB", "Second");
    private final WmsListInvocation<Uom> handler = new WmsListInvocation<>(WmsCapabilities.UOMS_LIST, Uom.class, Uom::code,
            pagination -> WmsListInvocation.page(List.of(first, second), 2, pagination));

    @Test void validatesTypedCompleteOrderedCatalogueWindow() {
        assertThat(handler.validOutput(handler.execute(null, request), request)).isTrue();
        var beyond = new PagedCollection.Pagination(BigInteger.TEN, 1);
        assertThat(handler.validOutput(WmsListInvocation.page(List.of(), 2, beyond), beyond)).isTrue();
    }
    @Test void rejectsKnownInvalidCollectionsBeforeCoreCanEmitSuccess() {
        for (Object output : List.of("wrong", WmsListInvocation.page(List.of("wrong-item"), 1, request),
                WmsListInvocation.page(List.of(first), -1, request),
                WmsListInvocation.page(List.of(second, first), 2, request),
                WmsListInvocation.page(List.of(first, first), 2, request),
                WmsListInvocation.page(List.of(first), 2, request),
                new PagedCollection<>(List.of(first), BigInteger.ONE, request.limit(), 1, WmsListInvocation.CODE_ASCENDING),
                new PagedCollection<>(List.of(first), request.offset(), 1, 1, WmsListInvocation.CODE_ASCENDING),
                new PagedCollection<>(List.of(first), request.offset(), request.limit(), 1, List.of()))) {
            assertThat(handler.validOutput(output, request)).isFalse();
        }
        assertThat(handler.validOutput(null, request)).isFalse();
    }
}
