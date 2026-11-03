package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.PublicScreenEntryDTO;
import com.universe.screen.application.dto.PublicScreenFranchiseDTO;
import com.universe.screen.application.dto.PublicScreenFranchiseInstallmentDTO;
import com.universe.screen.application.dto.PublicScreenTitleDetailDTO;
import com.universe.screen.application.exceptions.PublishedScreenTitleNotFoundException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseMembershipRepositoryPort;
import com.universe.screen.application.ports.ScreenFranchiseRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenFranchise;
import com.universe.screen.domain.ScreenFranchiseMembership;
import com.universe.screen.domain.ScreenStructure;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Public query use case for retrieving full details of a published Screen title.
 *
 * <p>Enforces:
 * <ul>
 *   <li>Only {@link com.universe.screen.domain.ScreenPublicationStatus#PUBLISHED} titles resolve;</li>
 *   <li>Unknown, DRAFT, and ARCHIVED titles throw {@link PublishedScreenTitleNotFoundException};</li>
 *   <li>Only {@code PUBLISHED} entries are retrieved;</li>
 *   <li>Entries adhere to deterministic ordering contract;</li>
 *   <li>Franchise navigation queries only {@code PUBLISHED} sibling installments ordered by {@code displayOrder};</li>
 *   <li>Zero exposure of unpublished content, storage keys, or internal infrastructure.</li>
 * </ul>
 */
@Service
@Transactional(readOnly = true)
public class GetPublicScreenTitleDetailUseCase {

    private final ScreenTitleRepositoryPort titleRepositoryPort;
    private final ScreenEntryRepositoryPort entryRepositoryPort;
    private final ScreenFranchiseMembershipRepositoryPort franchiseMembershipRepositoryPort;
    private final ScreenFranchiseRepositoryPort franchiseRepositoryPort;

    public GetPublicScreenTitleDetailUseCase(
            ScreenTitleRepositoryPort titleRepositoryPort,
            ScreenEntryRepositoryPort entryRepositoryPort,
            ScreenFranchiseMembershipRepositoryPort franchiseMembershipRepositoryPort,
            ScreenFranchiseRepositoryPort franchiseRepositoryPort
    ) {
        this.titleRepositoryPort = Objects.requireNonNull(titleRepositoryPort, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepositoryPort = Objects.requireNonNull(entryRepositoryPort, "ScreenEntryRepositoryPort cannot be null.");
        this.franchiseMembershipRepositoryPort = Objects.requireNonNull(franchiseMembershipRepositoryPort, "ScreenFranchiseMembershipRepositoryPort cannot be null.");
        this.franchiseRepositoryPort = Objects.requireNonNull(franchiseRepositoryPort, "ScreenFranchiseRepositoryPort cannot be null.");
    }

    public PublicScreenTitleDetailDTO execute(String titleSlug) {
        if (titleSlug == null || titleSlug.isBlank()) {
            throw new PublishedScreenTitleNotFoundException(titleSlug);
        }

        String normalizedSlug = titleSlug.trim().toLowerCase(Locale.ROOT);
        ScreenTitle title = titleRepositoryPort.findPublishedBySlug(normalizedSlug)
                .orElseThrow(() -> new PublishedScreenTitleNotFoundException(titleSlug));

        Integer latestPublishedEpisodeNumber = title.getStructure() == ScreenStructure.SERIES
                ? entryRepositoryPort.findLatestPublishedEpisodeNumberByTitleId(title.getId())
                : null;

        List<ScreenEntry> publishedEntries = entryRepositoryPort.findPublishedByTitleId(title.getId());
        List<PublicScreenEntryDTO> sortedEntries = publishedEntries.stream()
                .sorted(ENTRY_ORDER_COMPARATOR)
                .map(GetPublicScreenTitleDetailUseCase::toEntryDTO)
                .toList();

        List<PublicScreenFranchiseDTO> franchises = resolveFranchiseNavigation(title);

        Integer averageDurationMinutes = calculateAverageDurationMinutes(title.getStructure(), publishedEntries);

        return new PublicScreenTitleDetailDTO(
                title.getId(),
                title.getSlug(),
                title.getTitle(),
                title.getOriginalTitle(),
                title.getSynopsis(),
                title.getStructure(),
                title.getPresentation(),
                title.getPosterMediaAssetId(),
                title.getBackdropMediaAssetId(),
                title.getPublishedAt(),
                latestPublishedEpisodeNumber,
                title.getTotalEpisodeCount(),
                sortedEntries,
                franchises,
                title.getAiringStatus(),
                title.getReleaseYear(),
                title.getCountry(),
                title.getOriginalAuthor(),
                title.getDirector(),
                title.getProductionStudio(),
                averageDurationMinutes
        );
    }

    /**
     * Dynamically computes the display duration in whole minutes from published entries:
     * <ul>
     *   <li>SERIES: Average of published EPISODE entries with durationSeconds > 0, rounded to nearest whole minute;</li>
     *   <li>FEATURE: Duration of published FEATURE entry with durationSeconds > 0, rounded to nearest whole minute;</li>
     *   <li>SPECIAL and TRAILER entries are strictly excluded;</li>
     *   <li>Returns null if no valid positive duration entry exists.</li>
     * </ul>
     */
    public static Integer calculateAverageDurationMinutes(ScreenStructure structure, List<ScreenEntry> publishedEntries) {
        if (structure == null || publishedEntries == null || publishedEntries.isEmpty()) {
            return null;
        }

        if (structure == ScreenStructure.FEATURE) {
            return publishedEntries.stream()
                    .filter(e -> e.getType() == ScreenEntryType.FEATURE)
                    .map(ScreenEntry::getDurationSeconds)
                    .filter(d -> d != null && d > 0)
                    .findFirst()
                    .map(d -> (int) Math.round(d / 60.0))
                    .orElse(null);
        } else if (structure == ScreenStructure.SERIES) {
            List<Long> episodeDurations = publishedEntries.stream()
                    .filter(e -> e.getType() == ScreenEntryType.EPISODE)
                    .map(ScreenEntry::getDurationSeconds)
                    .filter(d -> d != null && d > 0)
                    .toList();

            if (episodeDurations.isEmpty()) {
                return null;
            }

            double avgSeconds = episodeDurations.stream().mapToLong(Long::longValue).average().orElse(0.0);
            return (int) Math.round(avgSeconds / 60.0);
        }

        return null;
    }

    private List<PublicScreenFranchiseDTO> resolveFranchiseNavigation(ScreenTitle currentTitle) {
        List<ScreenFranchiseMembership> memberships = franchiseMembershipRepositoryPort.findByTitleId(currentTitle.getId());
        if (memberships.isEmpty()) {
            return List.of();
        }

        List<UUID> franchiseIds = memberships.stream()
                .map(ScreenFranchiseMembership::getFranchiseId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();

        List<PublicScreenFranchiseDTO> franchiseDTOs = new ArrayList<>();
        for (UUID franchiseId : franchiseIds) {
            Optional<ScreenFranchise> franchiseOpt = franchiseRepositoryPort.findById(franchiseId);
            if (franchiseOpt.isEmpty()) {
                continue;
            }

            ScreenFranchise franchise = franchiseOpt.get();
            List<ScreenTitle> siblingTitles = titleRepositoryPort.findPublishedByFranchiseId(franchiseId);

            List<PublicScreenFranchiseInstallmentDTO> installments = siblingTitles.stream()
                    .map(s -> new PublicScreenFranchiseInstallmentDTO(
                            s.getId(),
                            s.getSlug(),
                            s.getTitle(),
                            s.getStructure(),
                            s.getPresentation(),
                            s.getId().equals(currentTitle.getId())
                    ))
                    .toList();

            if (!installments.isEmpty()) {
                franchiseDTOs.add(new PublicScreenFranchiseDTO(
                        franchise.getId(),
                        franchise.getName(),
                        franchise.getSlug(),
                        installments
                ));
            }
        }

        return List.copyOf(franchiseDTOs);
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

    /**
     * Deterministic total ordering comparator for public entry listing:
     * 1. Entry type grouping: EPISODE/FEATURE (1) -> SPECIAL (2) -> TRAILER (3);
     * 2. Number ascending (nulls last);
     * 3. ID ascending tie-breaker.
     */
    static final Comparator<ScreenEntry> ENTRY_ORDER_COMPARATOR = Comparator
            .comparingInt(GetPublicScreenTitleDetailUseCase::typeOrder)
            .thenComparing(ScreenEntry::getNumber, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(ScreenEntry::getId);

    private static int typeOrder(ScreenEntry entry) {
        if (entry.getType() == null) {
            return 99;
        }
        return switch (entry.getType()) {
            case EPISODE, FEATURE -> 1;
            case SPECIAL -> 2;
            case TRAILER -> 3;
        };
    }
}
