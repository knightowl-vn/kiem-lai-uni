package com.universe.novel.application.narration;

import com.universe.novel.application.ports.PublicManagedVoiceCatalogQueryPort;
import com.universe.novel.application.ports.PublicManagedVoiceCatalogQueryPort.PublicManagedVoiceCatalogItem;
import com.universe.novel.contracts.dto.narration.PublicManagedVoiceCatalogDTO;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LoadPublicManagedVoiceCatalogUseCaseTest {

    @Mock
    private PublicManagedVoiceCatalogQueryPort catalogQueryPort;

    @Test
    void mapsPublicFieldsAndPreservesAuthoritativeOrder() {
        when(catalogQueryPort.findSelectableVoices()).thenReturn(List.of(
                new PublicManagedVoiceCatalogItem("north-default", "North Default", true),
                new PublicManagedVoiceCatalogItem("south-secondary", "South Secondary", false)
        ));
        LoadPublicManagedVoiceCatalogUseCase loader =
                new LoadPublicManagedVoiceCatalogUseCase(catalogQueryPort);

        PublicManagedVoiceCatalogDTO result = loader.execute(
                new GetPublicManagedVoiceCatalogQuery()
        );

        assertThat(result.voices())
                .extracting("voiceKey", "displayName", "defaultVoice")
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("north-default", "North Default", true),
                        org.assertj.core.groups.Tuple.tuple("south-secondary", "South Secondary", false)
                );
        verify(catalogQueryPort).findSelectableVoices();
    }

    @Test
    void returnsAnEmptyCatalogWithoutFallbackVoice() {
        when(catalogQueryPort.findSelectableVoices()).thenReturn(List.of());
        LoadPublicManagedVoiceCatalogUseCase loader =
                new LoadPublicManagedVoiceCatalogUseCase(catalogQueryPort);

        PublicManagedVoiceCatalogDTO result = loader.execute(
                new GetPublicManagedVoiceCatalogQuery()
        );

        assertThat(result.voices()).isEmpty();
    }
}
