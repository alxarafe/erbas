package com.alxarafe.erbas.wms;

import java.math.BigInteger;

import com.alxarafe.erbas.wms.application.UomCatalogue;
import com.alxarafe.erbas.wms.domain.Uom;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.alxarafe.erbas.wms.application.UomCatalogue.CreateOutcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class UomCatalogueTests {
    private final UomCatalogue.Store store = mock(UomCatalogue.Store.class);
    private final UomCatalogue catalogue = new UomCatalogue(store);

    @Test
    void createsAServerIdentityAndPersistsOnlyNormalizedText() {
        when(store.insertIfCodeAvailable(any())).thenReturn(true);
        var created = catalogue.create("EA", " Each ");
        assertThat(created.outcome()).isEqualTo(CREATED);
        var value = ArgumentCaptor.forClass(Uom.class);
        verify(store).insertIfCodeAvailable(value.capture());
        assertThat(value.getValue()).isEqualTo(created.uom());
        assertThat(created.uom().description()).isEqualTo("Each");
        assertThat(created.uom().id()).isNotNull();
        assertThat(catalogue.create("BOX", "Box").uom().id()).isNotEqualTo(created.uom().id());
    }

    @Test
    void invalidDomainInputNeverReachesPersistence() {
        assertThat(catalogue.create("box", "Box").outcome()).isEqualTo(INVALID_REQUEST);
        assertThat(catalogue.create("EA", " \t").outcome()).isEqualTo(INVALID_REQUEST);
        verifyNoInteractions(store);
    }

    @Test
    void onlyAnExplicitUniqueCodeConflictBecomesConflict() {
        assertThat(catalogue.create("EA", "Each").outcome()).isEqualTo(CODE_CONFLICT);
        var failure = new IllegalStateException("fixture storage failure");
        when(store.insertIfCodeAvailable(any())).thenThrow(failure);
        assertThatThrownBy(() -> catalogue.create("EA", "Each")).isSameAs(failure);
    }

    @Test
    void paginationRemainsAStoreOperationAndDoesNotTruncateLargeOffsets() {
        var offset = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        catalogue.list(offset, 1);
        verify(store).list(offset, 1);
    }
}
