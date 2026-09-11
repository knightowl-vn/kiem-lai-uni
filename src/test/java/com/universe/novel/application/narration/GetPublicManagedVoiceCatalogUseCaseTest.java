package com.universe.novel.application.narration;

import com.universe.novel.contracts.dto.narration.PublicManagedVoiceCatalogDTO;
import com.universe.novel.contracts.dto.narration.PublicNarrationVoiceDTO;
import com.universe.novel.infrastructure.cache.CaffeinePublicManagedVoiceCatalogCache;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetPublicManagedVoiceCatalogUseCaseTest {

    @Mock
    private LoadPublicManagedVoiceCatalogUseCase catalogLoader;

    private GetPublicManagedVoiceCatalogUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetPublicManagedVoiceCatalogUseCase(
                new CaffeinePublicManagedVoiceCatalogCache(),
                catalogLoader
        );
    }

    @Test
    void coldMissLoadsOnceAndWarmHitBypassesLoader() {
        GetPublicManagedVoiceCatalogQuery query = new GetPublicManagedVoiceCatalogQuery();
        PublicManagedVoiceCatalogDTO catalog = new PublicManagedVoiceCatalogDTO(List.of(
                new PublicNarrationVoiceDTO("north-default", "North Default", true)
        ));
        when(catalogLoader.execute(query)).thenReturn(catalog);

        PublicManagedVoiceCatalogDTO coldResult = useCase.execute(query);
        PublicManagedVoiceCatalogDTO warmResult = useCase.execute(query);

        assertThat(coldResult).isSameAs(catalog);
        assertThat(warmResult).isSameAs(catalog);
        verify(catalogLoader).execute(query);
        verifyNoMoreInteractions(catalogLoader);
    }

    @Test
    void emptySuccessfulCatalogIsCached() {
        GetPublicManagedVoiceCatalogQuery query = new GetPublicManagedVoiceCatalogQuery();
        PublicManagedVoiceCatalogDTO emptyCatalog = new PublicManagedVoiceCatalogDTO(List.of());
        when(catalogLoader.execute(query)).thenReturn(emptyCatalog);

        assertThat(useCase.execute(query).voices()).isEmpty();
        assertThat(useCase.execute(query).voices()).isEmpty();

        verify(catalogLoader).execute(query);
        verifyNoMoreInteractions(catalogLoader);
    }
}
