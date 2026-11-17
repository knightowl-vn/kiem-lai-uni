package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.PublicScreenCatalogItemDTO;
import com.universe.screen.application.dto.PublicScreenCatalogPageDTO;
import com.universe.screen.application.ports.ScreenCatalogImageDeliveryPort;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenPresentation;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("GetPublicScreenCatalogUseCase Unit Tests")
class GetPublicScreenCatalogUseCaseTest {

    @Mock
    private ScreenTitleRepositoryPort titleRepositoryPort;

    @Mock
    private ScreenEntryRepositoryPort entryRepositoryPort;

    @Mock
    private ScreenCatalogImageDeliveryPort catalogImageDeliveryPort;

    private GetPublicScreenCatalogUseCase useCase;

    private final Instant now = Instant.parse("2026-11-01T10:00:00Z");

    @BeforeEach
    void setUp() {
        useCase = new GetPublicScreenCatalogUseCase(titleRepositoryPort, entryRepositoryPort, catalogImageDeliveryPort);
    }

    @Test
    @DisplayName("Constructor rejects null repository ports and delivery port")
    void shouldRejectNullRepositoryPort() {
        assertThatNullPointerException()
                .isThrownBy(() -> new GetPublicScreenCatalogUseCase(null, entryRepositoryPort, catalogImageDeliveryPort))
                .withMessageContaining("ScreenTitleRepositoryPort cannot be null.");

        assertThatNullPointerException()
                .isThrownBy(() -> new GetPublicScreenCatalogUseCase(titleRepositoryPort, null, catalogImageDeliveryPort))
                .withMessageContaining("ScreenEntryRepositoryPort cannot be null.");

        assertThatNullPointerException()
                .isThrownBy(() -> new GetPublicScreenCatalogUseCase(titleRepositoryPort, entryRepositoryPort, null))
                .withMessageContaining("ScreenCatalogImageDeliveryPort cannot be null.");

        // Backward-compatible 2-arg constructor
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

    @Test
    @DisplayName("Should batch resolve posters in exactly 1 call with deduplication, null exclusion, and correct mapping")
    void shouldBatchResolvePostersInSingleCallWithDeduplicationAndExcludingNulls() {
        UUID posterA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID posterB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

        ScreenTitle title1 = ScreenTitle.createDraft(UUID.randomUUID(), "t-1", "Title 1", null, null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, posterA, null, now);
        title1.publish(now);
        ScreenTitle title2 = ScreenTitle.createDraft(UUID.randomUUID(), "t-2", "Title 2", null, null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, posterB, null, now);
        title2.publish(now);
        ScreenTitle title3 = ScreenTitle.createDraft(UUID.randomUUID(), "t-3", "Title 3", null, null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, posterA, null, now);
        title3.publish(now);
        ScreenTitle title4 = ScreenTitle.createDraft(UUID.randomUUID(), "t-4", "Title 4", null, null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, null, null, now);
        title4.publish(now);

        ScreenTitleRepositoryPort.ScreenTitlePage repoPage = new ScreenTitleRepositoryPort.ScreenTitlePage(
                List.of(title1, title2, title3, title4), 0, 12, 4L, 1
        );
        when(titleRepositoryPort.findPublishedTitles(0, 12)).thenReturn(repoPage);

        String urlA = "https://res.cloudinary.com/test/w300_a.webp";
        String urlB = "https://res.cloudinary.com/test/w300_b.webp";
        when(catalogImageDeliveryPort.resolvePublicImageUrls(List.of(posterA, posterB), 300, true))
                .thenReturn(Map.of(posterA, urlA, posterB, urlB));

        PublicScreenCatalogPageDTO result = useCase.execute(0, 12);

        assertThat(result.items()).hasSize(4);
        assertThat(result.items().get(0).directPosterUrl()).isEqualTo(urlA);
        assertThat(result.items().get(1).directPosterUrl()).isEqualTo(urlB);
        assertThat(result.items().get(2).directPosterUrl()).isEqualTo(urlA);
        assertThat(result.items().get(3).directPosterUrl()).isNull();

        // Exactly 1 batch call issued to catalogImageDeliveryPort
        verify(catalogImageDeliveryPort, times(1)).resolvePublicImageUrls(List.of(posterA, posterB), 300, true);
    }

    @Test
    @DisplayName("Should skip image resolution entirely when titles have no poster IDs")
    void shouldSkipImageResolutionWhenNoTitlesHavePosters() {
        ScreenTitle title1 = ScreenTitle.createDraft(UUID.randomUUID(), "t-1", "Title 1", null, null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, null, null, now);
        title1.publish(now);
        ScreenTitle title2 = ScreenTitle.createDraft(UUID.randomUUID(), "t-2", "Title 2", null, null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, null, null, now);
        title2.publish(now);

        ScreenTitleRepositoryPort.ScreenTitlePage repoPage = new ScreenTitleRepositoryPort.ScreenTitlePage(
                List.of(title1, title2), 0, 12, 2L, 1
        );
        when(titleRepositoryPort.findPublishedTitles(0, 12)).thenReturn(repoPage);

        PublicScreenCatalogPageDTO result = useCase.execute(0, 12);

        assertThat(result.items()).hasSize(2);
        assertThat(result.items().get(0).directPosterUrl()).isNull();
        assertThat(result.items().get(1).directPosterUrl()).isNull();

        // Zero calls to catalogImageDeliveryPort
        verify(catalogImageDeliveryPort, never()).resolvePublicImageUrls(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Should skip image resolution when catalog page is empty")
    void shouldSkipImageResolutionWhenCatalogIsEmpty() {
        ScreenTitleRepositoryPort.ScreenTitlePage emptyRepoPage = new ScreenTitleRepositoryPort.ScreenTitlePage(
                List.of(), 0, 12, 0L, 0
        );
        when(titleRepositoryPort.findPublishedTitles(0, 12)).thenReturn(emptyRepoPage);

        PublicScreenCatalogPageDTO result = useCase.execute(0, 12);

        assertThat(result.items()).isEmpty();
        verify(catalogImageDeliveryPort, never()).resolvePublicImageUrls(any(), any(), anyBoolean());
    }

    @Test
    @DisplayName("Should preserve title ordering and handle partial Media resolution safely")
    void shouldPreserveTitleOrderingAndHandlePartialMediaResolution() {
        UUID posterA = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID posterB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID posterC = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        ScreenTitle titleA = ScreenTitle.createDraft(UUID.randomUUID(), "slug-a", "Title A", null, null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, posterA, null, now);
        titleA.publish(now);
        ScreenTitle titleB = ScreenTitle.createDraft(UUID.randomUUID(), "slug-b", "Title B", null, null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, posterB, null, now);
        titleB.publish(now);
        ScreenTitle titleC = ScreenTitle.createDraft(UUID.randomUUID(), "slug-c", "Title C", null, null, ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, posterC, null, now);
        titleC.publish(now);

        ScreenTitleRepositoryPort.ScreenTitlePage repoPage = new ScreenTitleRepositoryPort.ScreenTitlePage(
                List.of(titleA, titleB, titleC), 0, 12, 3L, 1
        );
        when(titleRepositoryPort.findPublishedTitles(0, 12)).thenReturn(repoPage);

        // Media returns partial map containing only posterB
        String urlB = "https://res.cloudinary.com/test/w300_b.webp";
        when(catalogImageDeliveryPort.resolvePublicImageUrls(List.of(posterA, posterB, posterC), 300, true))
                .thenReturn(Map.of(posterB, urlB));

        PublicScreenCatalogPageDTO result = useCase.execute(0, 12);

        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).slug()).isEqualTo("slug-a");
        assertThat(result.items().get(0).directPosterUrl()).isNull();
        assertThat(result.items().get(1).slug()).isEqualTo("slug-b");
        assertThat(result.items().get(1).directPosterUrl()).isEqualTo(urlB);
        assertThat(result.items().get(2).slug()).isEqualTo("slug-c");
        assertThat(result.items().get(2).directPosterUrl()).isNull();
    }

    @ParameterizedTest(name = "Page with {0} distinct posters invokes batch resolver exactly once")
    @ValueSource(ints = {1, 12, 100})
    @DisplayName("Should invoke catalogImageDeliveryPort exactly once with width 300 and fallbackToSource=true for pages of 1, 12, and 100 poster IDs")
    void shouldInvokeCatalogImageDeliveryPortExactlyOnceForVariousPageSizes(int itemCount) {
        List<ScreenTitle> titles = new ArrayList<>();
        List<UUID> posterIds = new ArrayList<>();
        Map<UUID, String> expectedUrlMap = new LinkedHashMap<>();

        for (int i = 0; i < itemCount; i++) {
            UUID titleId = UUID.randomUUID();
            UUID posterId = UUID.randomUUID();
            String slug = "title-slug-" + i;
            String titleName = "Title " + i;
            String directUrl = "https://res.cloudinary.com/test/w300_" + i + ".webp";

            ScreenTitle title = ScreenTitle.createDraft(
                    titleId, slug, titleName, null, null,
                    ScreenStructure.FEATURE, ScreenPresentation.LIVE_ACTION, posterId, null, now
            );
            title.publish(now.plusSeconds(i));

            titles.add(title);
            posterIds.add(posterId);
            expectedUrlMap.put(posterId, directUrl);
        }

        ScreenTitleRepositoryPort.ScreenTitlePage repoPage = new ScreenTitleRepositoryPort.ScreenTitlePage(
                titles, 0, itemCount, (long) itemCount, 1
        );
        when(titleRepositoryPort.findPublishedTitles(0, itemCount)).thenReturn(repoPage);
        when(catalogImageDeliveryPort.resolvePublicImageUrls(posterIds, 300, true)).thenReturn(expectedUrlMap);

        PublicScreenCatalogPageDTO result = useCase.execute(0, itemCount);

        // Verify exactly 1 batch invocation with target width 300 and fallbackToSource=true
        verify(catalogImageDeliveryPort, times(1)).resolvePublicImageUrls(posterIds, 300, true);

        // Verify result size
        assertThat(result.items()).hasSize(itemCount);

        // Verify ordering preservation and 1:1 poster URL mapping
        for (int i = 0; i < itemCount; i++) {
            PublicScreenCatalogItemDTO item = result.items().get(i);
            assertThat(item.slug()).isEqualTo("title-slug-" + i);
            assertThat(item.title()).isEqualTo("Title " + i);
            assertThat(item.posterMediaAssetId()).isEqualTo(posterIds.get(i));
            assertThat(item.directPosterUrl()).isEqualTo(expectedUrlMap.get(posterIds.get(i)));
        }
    }
}
