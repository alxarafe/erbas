package com.alxarafe.erbas.wms;

import java.math.BigInteger;

import com.alxarafe.erbas.wms.application.StorageAttributeCatalogue;
import com.alxarafe.erbas.wms.domain.StorageAttribute;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static com.alxarafe.erbas.wms.application.StorageAttributeCatalogue.CreateOutcome.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StorageAttributeCatalogueTests {
    private final StorageAttributeCatalogue.Store store = mock(StorageAttributeCatalogue.Store.class);
    private final StorageAttributeCatalogue catalogue = new StorageAttributeCatalogue(store);

    @Test
    void createsAServerIdentityAndPersistsOnlyNormalizedText() {
        when(store.insertIfCodeAvailable(any())).thenReturn(true);
        var created = catalogue.create("EA", " Cold storage ");
        assertThat(created.outcome()).isEqualTo(CREATED);
        var value = ArgumentCaptor.forClass(StorageAttribute.class);
        verify(store).insertIfCodeAvailable(value.capture());
        assertThat(value.getValue()).isEqualTo(created.attribute());
        assertThat(created.attribute().name()).isEqualTo("Cold storage");
        assertThat(created.attribute().id()).isNotNull();
        assertThat(catalogue.create("BOX", "Box").attribute().id()).isNotEqualTo(created.attribute().id());
    }

    @Test
    void invalidDomainInputNeverReachesPersistence() {
        assertThat(catalogue.create("box", "Box").outcome()).isEqualTo(INVALID_REQUEST);
        assertThat(catalogue.create("EA", " \t").outcome()).isEqualTo(INVALID_REQUEST);
        verifyNoInteractions(store);
    }

    @Test
    void onlyAnExplicitUniqueCodeConflictBecomesConflict() {
        assertThat(catalogue.create("EA", "Cold storage").outcome()).isEqualTo(CODE_CONFLICT);
        var failure = new IllegalStateException("fixture storage failure");
        when(store.insertIfCodeAvailable(any())).thenThrow(failure);
        assertThatThrownBy(() -> catalogue.create("EA", "Cold storage")).isSameAs(failure);
    }

    @Test
    void paginationRemainsAStoreOperationAndDoesNotTruncateLargeOffsets() {
        var offset = BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE);
        catalogue.list(offset, 1);
        verify(store).list(offset, 1);
    }
}
