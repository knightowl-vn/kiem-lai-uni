package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.PublicScreenEntryDTO;
import com.universe.screen.application.dto.PublicScreenWatchDTO;
import com.universe.screen.application.dto.ScreenPlaybackDeliveryInfo;
import com.universe.screen.application.exceptions.PublishedScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.PublishedScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.application.ports.ScreenVideoDeliveryPort;
import com.universe.screen.domain.EffectiveScreenTimeline;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Public query use case for retrieving playback composition and watch-page metadata.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Only {@link com.universe.screen.domain.ScreenPublicationStatus#PUBLISHED} titles resolve;</li>
 *   <li>Only {@code PUBLISHED} entries belonging to the exact title resolve;</li>
 *   <li>Unknown, DRAFT, ARCHIVED, or mismatched titles/entries throw source-consistent exceptions without data leakage;</li>
 *   <li>Playable Media reference is validated against authoritative lifecycle, visibility, and media type rules;</li>
 *   <li>Deterministic SERIES previous/next navigation for EPISODE entries (skips DRAFT/ARCHIVED);</li>
 *   <li>SPECIAL, TRAILER, and FEATURE entries safely omit previous/next episode navigation;</li>
 *   <li>Zero exposure of JPA entities, storage provider keys, or private internal infrastructure.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class GetPublicScreenWatchUseCase {

    private final ScreenTitleRepositoryPort titleRepositoryPort;
    private final ScreenEntryRepositoryPort entryRepositoryPort;
    private final ScreenVideoDeliveryPort videoDeliveryPort;

    public GetPublicScreenWatchUseCase(
            ScreenTitleRepositoryPort titleRepositoryPort,
            ScreenEntryRepositoryPort entryRepositoryPort,
            ScreenVideoDeliveryPort videoDeliveryPort
    ) {
        this.titleRepositoryPort = Objects.requireNonNull(titleRepositoryPort, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepositoryPort = Objects.requireNonNull(entryRepositoryPort, "ScreenEntryRepositoryPort cannot be null.");
        this.videoDeliveryPort = Objects.requireNonNull(videoDeliveryPort, "ScreenVideoDeliveryPort cannot be null.");
    }

    public PublicScreenWatchDTO execute(String titleSlug, String entrySlug) {
        if (titleSlug == null || titleSlug.isBlank()) {
            throw new PublishedScreenTitleNotFoundException(titleSlug);
        }
        if (entrySlug == null || entrySlug.isBlank()) {
            throw new PublishedScreenEntryNotFoundException(entrySlug);
        }

        String normalizedTitleSlug = titleSlug.trim().toLowerCase(Locale.ROOT);
        String normalizedEntrySlug = entrySlug.trim().toLowerCase(Locale.ROOT);

        ScreenTitle title = titleRepositoryPort.findPublishedBySlug(normalizedTitleSlug)
                .orElseThrow(() -> new PublishedScreenTitleNotFoundException(titleSlug));

        ScreenEntry entry = entryRepositoryPort.findPublishedByTitleIdAndSlug(title.getId(), normalizedEntrySlug)
                .orElseThrow(() -> new PublishedScreenEntryNotFoundException(entrySlug));

        // 1. Playable video reference resolution
        boolean isPlayable = false;
        String videoDeliveryUrl = null;
        String videoMimeType = null;
        if (entry.getVideoMediaAssetId() != null) {
            Optional<ScreenPlaybackDeliveryInfo> deliveryInfoOpt =
                    videoDeliveryPort.resolvePlaybackDelivery(entry.getVideoMediaAssetId());
            if (deliveryInfoOpt.isPresent()) {
                ScreenPlaybackDeliveryInfo deliveryInfo = deliveryInfoOpt.get();
                isPlayable = true;
                videoDeliveryUrl = deliveryInfo.deliveryUrl();
                videoMimeType = deliveryInfo.mimeType();
            }
        }

        // 2. Playlist resolution (all published entries for this title, deterministically ordered)
        List<ScreenEntry> publishedEntries = entryRepositoryPort.findPublishedByTitleId(title.getId());
        List<PublicScreenEntryDTO> playlist = publishedEntries.stream()
                .sorted(GetPublicScreenTitleDetailUseCase.ENTRY_ORDER_COMPARATOR)
                .map(GetPublicScreenWatchUseCase::toEntryDTO)
                .toList();

        // 3. Navigation resolution (previous/next episode controls)
        PublicScreenEntryDTO previousEntry = null;
        PublicScreenEntryDTO nextEntry = null;

        if (title.getStructure() == ScreenStructure.SERIES && entry.getType() == ScreenEntryType.EPISODE) {
            List<ScreenEntry> publishedEpisodes = entryRepositoryPort.findPublishedEpisodesByTitleId(title.getId());
            int currentIndex = -1;
            for (int i = 0; i < publishedEpisodes.size(); i++) {
                if (publishedEpisodes.get(i).getId().equals(entry.getId())) {
                    currentIndex = i;
                    break;
                }
            }

            if (currentIndex > 0) {
                previousEntry = toEntryDTO(publishedEpisodes.get(currentIndex - 1));
            }
            if (currentIndex >= 0 && currentIndex < publishedEpisodes.size() - 1) {
                nextEntry = toEntryDTO(publishedEpisodes.get(currentIndex + 1));
            }
        }

        EffectiveScreenTimeline effectiveTimeline = entry.resolveEffectiveTimeline(title);

        return new PublicScreenWatchDTO(
                title.getId(),
                title.getSlug(),
                title.getTitle(),
                title.getStructure(),
                title.getPresentation(),
                title.getPosterMediaAssetId(),
                title.getBackdropMediaAssetId(),
                entry.getId(),
                entry.getSlug(),
                entry.getType(),
                entry.getNumber(),
                entry.getTitle(),
                entry.getSynopsis(),
                entry.getDurationSeconds(),
                entry.getThumbnailMediaAssetId(),
                isPlayable,
                videoDeliveryUrl,
                videoMimeType,
                previousEntry,
                nextEntry,
                playlist,
                effectiveTimeline.introStartSeconds(),
                effectiveTimeline.introEndSeconds(),
                effectiveTimeline.hasIntro(),
                effectiveTimeline.outroStartSeconds(),
                effectiveTimeline.outroEndSeconds(),
                effectiveTimeline.hasOutro()
        );
    }

    private static PublicScreenEntryDTO toEntryDTO(ScreenEntry entry) {
        return new PublicScreenEntryDTO(
                entry.getId(),
                entry.getSlug(),
                entry.getType(),
                entry.getNumber(),
                entry.getTitle(),
                entry.getSynopsis(),
                entry.getDurationSeconds(),
                entry.getThumbnailMediaAssetId(),
                entry.getVideoMediaAssetId() != null
        );
    }
}
