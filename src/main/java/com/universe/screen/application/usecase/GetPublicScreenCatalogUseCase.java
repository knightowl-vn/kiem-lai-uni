package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.PublicScreenCatalogItemDTO;
import com.universe.screen.application.dto.PublicScreenCatalogPageDTO;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Public catalog query usecase for retrieving paginated published Screen titles.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Only {@link com.universe.screen.domain.ScreenPublicationStatus#PUBLISHED} titles are queried;</li>
 *   <li>Deterministic ordering by {@code publishedAt DESC, id ASC};</li>
 *   <li>Safe pagination boundaries (page >= 0, size bounded);</li>
 *   <li>Safe mapping to public card DTOs with no internal persistence or storage exposure.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class GetPublicScreenCatalogUseCase {

    public static final int DEFAULT_PAGE_SIZE = 12;
    public static final int MAX_PAGE_SIZE = 100;

    private final ScreenTitleRepositoryPort titleRepositoryPort;
    private final ScreenEntryRepositoryPort entryRepositoryPort;

    public GetPublicScreenCatalogUseCase(
            ScreenTitleRepositoryPort titleRepositoryPort,
            ScreenEntryRepositoryPort entryRepositoryPort
    ) {
        this.titleRepositoryPort = Objects.requireNonNull(titleRepositoryPort, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepositoryPort = Objects.requireNonNull(entryRepositoryPort, "ScreenEntryRepositoryPort cannot be null.");
    }

    public PublicScreenCatalogPageDTO execute(int page, int size) {
        int effectivePage = Math.max(page, 0);
        int effectiveSize = (size <= 0) ? DEFAULT_PAGE_SIZE : Math.min(size, MAX_PAGE_SIZE);

        ScreenTitleRepositoryPort.ScreenTitlePage result = titleRepositoryPort.findPublishedTitles(effectivePage, effectiveSize);

        List<UUID> seriesTitleIds = result.items().stream()
                .filter(t -> t.getStructure() == ScreenStructure.SERIES)
                .map(ScreenTitle::getId)
                .toList();

        Map<UUID, Integer> latestEpisodes = seriesTitleIds.isEmpty()
                ? Map.of()
                : entryRepositoryPort.findLatestPublishedEpisodeNumbersByTitleIds(seriesTitleIds);

        List<PublicScreenCatalogItemDTO> items = result.items().stream()
                .map(title -> toItemDTO(
                        title,
                        title.getStructure() == ScreenStructure.SERIES
                                ? latestEpisodes.get(title.getId())
                                : null
                ))
                .toList();

        boolean first = effectivePage == 0;
        boolean last = result.totalPages() == 0 || effectivePage >= result.totalPages() - 1;

        return new PublicScreenCatalogPageDTO(
                items,
                effectivePage,
                effectiveSize,
                result.totalElements(),
                result.totalPages(),
                first,
                last
        );
    }

    private static PublicScreenCatalogItemDTO toItemDTO(ScreenTitle title, Integer latestPublishedEpisodeNumber) {
        return new PublicScreenCatalogItemDTO(
                title.getSlug(),
                title.getTitle(),
                title.getOriginalTitle(),
                title.getSynopsis(),
                title.getStructure(),
                title.getPresentation(),
                title.getPosterMediaAssetId(),
                title.getPublishedAt(),
                latestPublishedEpisodeNumber,
                title.getTotalEpisodeCount()
        );
    }
}
