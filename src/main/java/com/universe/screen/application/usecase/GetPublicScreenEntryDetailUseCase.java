package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.PublicScreenEntryDetailDTO;
import com.universe.screen.application.exceptions.PublishedScreenEntryNotFoundException;
import com.universe.screen.application.exceptions.PublishedScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.Objects;

/**
 * Public query use case for retrieving a specific published Screen entry under a published title.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Parent title must be PUBLISHED;</li>
 *   <li>Requested entry must be PUBLISHED and belong to the parent title;</li>
 *   <li>Unknown, DRAFT, ARCHIVED, or mismatched entries throw {@link PublishedScreenEntryNotFoundException};</li>
 *   <li>Zero exposure of unpublished storage or media keys.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class GetPublicScreenEntryDetailUseCase {

    private final ScreenTitleRepositoryPort titleRepositoryPort;
    private final ScreenEntryRepositoryPort entryRepositoryPort;

    public GetPublicScreenEntryDetailUseCase(
            ScreenTitleRepositoryPort titleRepositoryPort,
            ScreenEntryRepositoryPort entryRepositoryPort
    ) {
        this.titleRepositoryPort = Objects.requireNonNull(titleRepositoryPort, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepositoryPort = Objects.requireNonNull(entryRepositoryPort, "ScreenEntryRepositoryPort cannot be null.");
    }

    public PublicScreenEntryDetailDTO execute(String titleSlug, String entrySlug) {
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

        return new PublicScreenEntryDetailDTO(
                title.getId(),
                title.getSlug(),
                title.getTitle(),
                title.getStructure(),
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
