package com.universe.novel.infrastructure.persistence.voice;

import com.universe.novel.application.ports.PublicManagedVoiceCatalogQueryPort.PublicManagedVoiceCatalogItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicManagedVoiceCatalogQueryPersistenceAdapterTest {

    @Mock
    private SpringDataManagedVoiceJpaRepository repository;

    @Mock
    private PublicManagedVoiceCatalogProjection first;

    @Mock
    private PublicManagedVoiceCatalogProjection second;

    @Test
    void requestsOnlyActiveVoicesAndPreservesRepositoryOrdering() {
        when(first.getVoiceKey()).thenReturn("north-default");
        when(first.getDisplayName()).thenReturn("North Default");
        when(first.isDefaultVoice()).thenReturn(true);
        when(second.getVoiceKey()).thenReturn("south-secondary");
        when(second.getDisplayName()).thenReturn("South Secondary");
        when(second.isDefaultVoice()).thenReturn(false);
        when(repository.findPublicCatalogByStatus("ACTIVE")).thenReturn(List.of(first, second));

        PublicManagedVoiceCatalogQueryPersistenceAdapter adapter =
                new PublicManagedVoiceCatalogQueryPersistenceAdapter(repository);

        List<PublicManagedVoiceCatalogItem> result = adapter.findSelectableVoices();

        assertThat(result).containsExactly(
                new PublicManagedVoiceCatalogItem("north-default", "North Default", true),
                new PublicManagedVoiceCatalogItem("south-secondary", "South Secondary", false)
        );
        verify(repository).findPublicCatalogByStatus("ACTIVE");
    }

    @Test
    void repositoryUsesOneLightweightProjectionQueryWithStableOrdering() throws Exception {
        Method method = SpringDataManagedVoiceJpaRepository.class
                .getMethod("findPublicCatalogByStatus", String.class);
        String query = method.getAnnotation(Query.class).value();

        assertThat(query).contains(
                "SELECT v.voiceKey AS voiceKey",
                "v.displayName AS displayName",
                "v.defaultVoice AS defaultVoice",
                "WHERE v.status = :status",
                "ORDER BY v.displayOrder ASC, v.createdAt ASC, v.id ASC"
        );
        assertThat(query).doesNotContain(
                "providerVoiceId",
                "synthesisRevision",
                "ChapterNarrationSegment",
                "ChapterNarrationAudio",
                "Media"
        );
    }
}
