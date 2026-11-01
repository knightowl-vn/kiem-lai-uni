package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.PublicScreenCatalogItemDTO;
import com.universe.screen.application.dto.PublicScreenCatalogPageDTO;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetPublicScreenCatalogUseCase Unit Tests")
class GetPublicScreenCatalogUseCaseTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepositoryPort;

    @Mock
    private ScreenEntryRepositoryPort entryRepositoryPort;

    private GetPublicScreenCatalogUseCase useCase;

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetPublicScreenCatalogUseCase(titleRepositoryPort, entryRepositoryPort);
    }

    @Test
    @DisplayName("Constructor rejects null repository ports")
    void shouldRejectNullRepositoryPort() {
        assertThatNullPointerException()
                .isThrownBy(() -> new GetPublicScreenCatalogUseCase(null, entryRepositoryPort))
                .withMessageContaining("ScreenTitleRepositoryPort cannot be null.");

        assertThatNullPointerException()
                .isThrownBy(() -> new GetPublicScreenCatalogUseCase(titleRepositoryPort, null))
                .withMessageContaining("ScreenEntryRepositoryPort cannot be null.");
    }

    @Test
    @DisplayName("Should retrieve published catalog titles and map correctly to public DTOs")
    void shouldRetrieveAndMapPublishedTitles() {
        UUID id1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID posterId = UUID.fromString("11111111-1111-1111-1111-111111111111");

        ScreenTitle title1 = ScreenTitle.createDraft(
                id1, "kiem-lai-1", "Kiếm Lai 1", "The Great Ruler", "Hành trình thiếu niên",
                ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, posterId, null, now
        );
        title1.publish(now.plusSeconds(100));

        ScreenTitleRepositoryPort.ScreenTitlePage repoPage = new ScreenTitleRepositoryPort.ScreenTitlePage(
                List.of(title1), 0, 12, 1L, 1
        );

        when(titleRepositoryPort.findPublishedTitles(0, 12)).thenReturn(repoPage);
        when(entryRepositoryPort.findLatestPublishedEpisodeNumbersByTitleIds(List.of(id1))).thenReturn(Map.of(id1, 26));

        PublicScreenCatalogPageDTO result = useCase.execute(0, 12);

        assertThat(result.page()).isEqualTo(0);
        assertThat(result.size()).isEqualTo(12);
        assertThat(result.totalElements()).isEqualTo(1L);
        assertThat(result.totalPages()).isEqualTo(1);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
        assertThat(result.items()).hasSize(1);

        PublicScreenCatalogItemDTO item = result.items().get(0);
        assertThat(item.slug()).isEqualTo("kiem-lai-1");
        assertThat(item.title()).isEqualTo("Kiếm Lai 1");
        assertThat(item.originalTitle()).isEqualTo("The Great Ruler");
        assertThat(item.synopsis()).isEqualTo("Hành trình thiếu niên");
        assertThat(item.structure()).isEqualTo(ScreenStructure.SERIES);
        assertThat(item.presentation()).isEqualTo(ScreenPresentation.ANIMATION_3D);
        assertThat(item.posterMediaAssetId()).isEqualTo(posterId);
        assertThat(item.publishedAt()).isEqualTo(now.plusSeconds(100));
        assertThat(item.latestPublishedEpisodeNumber()).isEqualTo(26);

        verify(titleRepositoryPort).findPublishedTitles(0, 12);
        verify(entryRepositoryPort).findLatestPublishedEpisodeNumbersByTitleIds(List.of(id1));
    }

    @Test
    @DisplayName("Should batch query latest published episode for SERIES and skip query for FEATURE")
    void shouldBatchCountPublishedEpisodesForSeriesAndOmitFeatures() {
        UUID series1Id = UUID.randomUUID();
        UUID series2Id = UUID.randomUUID();
        UUID featureId = UUID.randomUUID();

        ScreenTitle series1 = ScreenTitle.createDraft(series1Id, "series-1", "Series 1", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now);
        series1.publish(now);
        ScreenTitle series2 = ScreenTitle.createDraft(series2Id, "series-2", "Series 2", null, null, ScreenStructure.SERIES, ScreenPresentation.ANIMATION_3D, null, null, now);
        series2.publish(now);
        ScreenTitle feature = ScreenTitle.createDraft(featureId, "feature-1", "Feature 1", null, null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, null, null, now);
        feature.publish(now);

        ScreenTitleRepositoryPort.ScreenTitlePage repoPage = new ScreenTitleRepositoryPort.ScreenTitlePage(
                List.of(series1, series2, feature), 0, 12, 3L, 1
        );
        when(titleRepositoryPort.findPublishedTitles(0, 12)).thenReturn(repoPage);
        when(entryRepositoryPort.findLatestPublishedEpisodeNumbersByTitleIds(List.of(series1Id, series2Id)))
                .thenReturn(Map.of(series1Id, 12));

        PublicScreenCatalogPageDTO result = useCase.execute(0, 12);
        assertThat(result.items()).hasSize(3);

        assertThat(result.items().get(0).latestPublishedEpisodeNumber()).isEqualTo(12);
        assertThat(result.items().get(1).latestPublishedEpisodeNumber()).isNull();
        assertThat(result.items().get(2).latestPublishedEpisodeNumber()).isNull();

        verify(entryRepositoryPort).findLatestPublishedEpisodeNumbersByTitleIds(List.of(series1Id, series2Id));
    }

    @Test
    @DisplayName("Should handle missing poster gracefully with null posterMediaAssetId")
    void shouldHandleMissingPosterGracefully() {
        UUID id = UUID.randomUUID();
        ScreenTitle titleWithoutPoster = ScreenTitle.createDraft(
                id, "movie-kiem-lai", "Kiếm Lai Movie", null, null,
                ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, null, null, now
        );
        titleWithoutPoster.publish(now.plusSeconds(50));

        ScreenTitleRepositoryPort.ScreenTitlePage repoPage = new ScreenTitleRepositoryPort.ScreenTitlePage(
                List.of(titleWithoutPoster), 0, 12, 1L, 1
        );

        when(titleRepositoryPort.findPublishedTitles(0, 12)).thenReturn(repoPage);

        PublicScreenCatalogPageDTO result = useCase.execute(0, 12);

        assertThat(result.items()).hasSize(1);
        PublicScreenCatalogItemDTO item = result.items().get(0);
        assertThat(item.posterMediaAssetId()).isNull();
        assertThat(item.structure()).isEqualTo(ScreenStructure.FEATURE);
        assertThat(item.presentation()).isEqualTo(ScreenPresentation.LIVE_ACTION);
    }

    @Test
    @DisplayName("Should handle empty catalog result safely")
    void shouldHandleEmptyCatalog() {
        ScreenTitleRepositoryPort.ScreenTitlePage emptyRepoPage = new ScreenTitleRepositoryPort.ScreenTitlePage(
                List.of(), 0, 12, 0L, 0
        );

        when(titleRepositoryPort.findPublishedTitles(0, 12)).thenReturn(emptyRepoPage);

        PublicScreenCatalogPageDTO result = useCase.execute(0, 12);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(0L);
        assertThat(result.totalPages()).isEqualTo(0);
        assertThat(result.first()).isTrue();
        assertThat(result.last()).isTrue();
    }

    @Test
    @DisplayName("Should clamp negative page to 0")
    void shouldClampNegativePageToZero() {
        ScreenTitleRepositoryPort.ScreenTitlePage emptyPage = new ScreenTitleRepositoryPort.ScreenTitlePage(
                List.of(), 0, 12, 0L, 0
        );

        when(titleRepositoryPort.findPublishedTitles(0, 12)).thenReturn(emptyPage);

        PublicScreenCatalogPageDTO result = useCase.execute(-5, 12);

        assertThat(result.page()).isEqualTo(0);
        verify(titleRepositoryPort).findPublishedTitles(0, 12);
    }

    @Test
    @DisplayName("Should clamp invalid size to default or maximum allowed bounds")
    void shouldClampSizeBounds() {
        ScreenTitleRepositoryPort.ScreenTitlePage page = new ScreenTitleRepositoryPort.ScreenTitlePage(
                List.of(), 0, 12, 0L, 0
        );
        when(titleRepositoryPort.findPublishedTitles(anyInt(), anyInt())).thenReturn(page);

        // size <= 0 defaults to DEFAULT_PAGE_SIZE (12)
        useCase.execute(0, 0);
        verify(titleRepositoryPort).findPublishedTitles(0, 12);

        org.mockito.Mockito.clearInvocations(titleRepositoryPort);
        useCase.execute(0, -10);
        verify(titleRepositoryPort).findPublishedTitles(0, 12);

        // size > 100 clamped to MAX_PAGE_SIZE (100)
        useCase.execute(0, 500);
        verify(titleRepositoryPort).findPublishedTitles(0, 100);
    }

    @Test
    @DisplayName("Should correctly calculate first and last flags across multiple pages")
    void shouldCalculateFirstAndLastFlags() {
        // Page 0 of 3: first = true, last = false
        when(titleRepositoryPort.findPublishedTitles(0, 12)).thenReturn(
                new ScreenTitleRepositoryPort.ScreenTitlePage(List.of(), 0, 12, 36L, 3)
        );
        PublicScreenCatalogPageDTO p0 = useCase.execute(0, 12);
        assertThat(p0.first()).isTrue();
        assertThat(p0.last()).isFalse();

        // Page 1 of 3: first = false, last = false
        when(titleRepositoryPort.findPublishedTitles(1, 12)).thenReturn(
                new ScreenTitleRepositoryPort.ScreenTitlePage(List.of(), 1, 12, 36L, 3)
        );
        PublicScreenCatalogPageDTO p1 = useCase.execute(1, 12);
        assertThat(p1.first()).isFalse();
        assertThat(p1.last()).isFalse();

        // Page 2 of 3: first = false, last = true
        when(titleRepositoryPort.findPublishedTitles(2, 12)).thenReturn(
                new ScreenTitleRepositoryPort.ScreenTitlePage(List.of(), 2, 12, 36L, 3)
        );
        PublicScreenCatalogPageDTO p2 = useCase.execute(2, 12);
        assertThat(p2.first()).isFalse();
        assertThat(p2.last()).isTrue();
    }
}
