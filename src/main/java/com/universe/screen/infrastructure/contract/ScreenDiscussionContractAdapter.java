package com.universe.screen.infrastructure.contract;

import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.contracts.dto.ScreenEntryOriginDTO;
import com.universe.screen.contracts.dto.ScreenTitleNavigationDTO;
import com.universe.screen.contracts.dto.ScreenTitleReportTargetDTO;
import com.universe.screen.contracts.interfaces.ScreenDiscussionContract;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenEntryType;
import com.universe.screen.domain.ScreenTitle;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Screen infrastructure adapter implementing the public {@link ScreenDiscussionContract}.
 *
 * <p>Encapsulates Screen-owned publication rules and entry origin badge formatting so that
 * foreign modules (e.g. Interaction) never depend directly on Screen internal domain,
 * application, or infrastructure packages.
 */
@Service
public class ScreenDiscussionContractAdapter implements ScreenDiscussionContract {

    private final ScreenTitleRepositoryPort screenTitleRepositoryPort;
    private final ScreenEntryRepositoryPort screenEntryRepositoryPort;

    public ScreenDiscussionContractAdapter(
            ScreenTitleRepositoryPort screenTitleRepositoryPort,
            ScreenEntryRepositoryPort screenEntryRepositoryPort
    ) {
        this.screenTitleRepositoryPort = Objects.requireNonNull(
                screenTitleRepositoryPort,
                "ScreenTitleRepositoryPort cannot be null."
        );
        this.screenEntryRepositoryPort = Objects.requireNonNull(
                screenEntryRepositoryPort,
                "ScreenEntryRepositoryPort cannot be null."
        );
    }

    @Override
    public boolean isPublishedTitle(UUID titleId) {
        if (titleId == null) {
            return false;
        }
        return screenTitleRepositoryPort.findPublishedById(titleId).isPresent();
    }

    @Override
    public boolean isValidPublishedOrigin(UUID titleId, UUID entryId) {
        if (titleId == null || entryId == null || !isPublishedTitle(titleId)) {
            return false;
        }
        return screenEntryRepositoryPort.findPublishedById(entryId)
                .map(entry -> titleId.equals(entry.getTitleId()))
                .orElse(false);
    }

    @Override
    public Map<UUID, ScreenEntryOriginDTO> findPublishedEntryOriginsByTitleId(UUID titleId) {
        if (titleId == null) {
            return Map.of();
        }
        List<ScreenEntry> publishedEntries = screenEntryRepositoryPort.findPublishedByTitleId(titleId);
        if (publishedEntries == null || publishedEntries.isEmpty()) {
            return Map.of();
        }
        return publishedEntries.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableMap(
                        ScreenEntry::getId,
                        entry -> new ScreenEntryOriginDTO(
                                entry.getId(),
                                entry.getSlug(),
                                formatEntryBadge(entry)
                        ),
                        (existing, replacement) -> existing
                ));
    }

    @Override
    public Map<UUID, ScreenEntryOriginDTO> findPublishedEntryOriginsByTitleIdAndEntryIds(
            UUID titleId,
            Set<UUID> entryIds
    ) {
        if (titleId == null || entryIds == null || entryIds.isEmpty()) {
            return Map.of();
        }
        Set<UUID> cleanIds = new LinkedHashSet<>();
        for (UUID entryId : entryIds) {
            if (entryId != null) {
                cleanIds.add(entryId);
            }
        }
        if (cleanIds.isEmpty()) {
            return Map.of();
        }
        List<ScreenEntryRepositoryPort.PublishedEntryOrigin> publishedOrigins =
                screenEntryRepositoryPort.findPublishedEntryOriginsByTitleIdAndEntryIds(titleId, cleanIds);
        if (publishedOrigins == null || publishedOrigins.isEmpty()) {
            return Map.of();
        }
        return publishedOrigins.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.toUnmodifiableMap(
                        ScreenEntryRepositoryPort.PublishedEntryOrigin::id,
                        origin -> new ScreenEntryOriginDTO(
                                origin.id(),
                                origin.slug(),
                                formatEntryBadge(origin.type(), origin.number())
                        ),
                        (existing, replacement) -> existing
                ));
    }

    @Override
    public Map<UUID, ScreenTitleReportTargetDTO> findReportTargetTitlesByIds(Set<UUID> titleIds) {
        if (titleIds == null || titleIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ScreenTitle> titles = screenTitleRepositoryPort.findTitlesByIds(titleIds);
        if (titles == null || titles.isEmpty()) {
            return Map.of();
        }
        Map<UUID, ScreenTitleReportTargetDTO> result = new HashMap<>();
        titles.forEach((id, title) -> {
            if (id != null && title != null) {
                result.put(id, new ScreenTitleReportTargetDTO(
                        title.getId(),
                        title.getTitle(),
                        title.getSlug(),
                        title.getStatus() != null ? title.getStatus().name() : null,
                        title.isPublished()
                ));
            }
        });
        return Collections.unmodifiableMap(result);
    }

    @Override
    public Optional<ScreenTitleReportTargetDTO> findReportTargetTitleById(UUID titleId) {
        if (titleId == null) {
            return Optional.empty();
        }
        return screenTitleRepositoryPort.findById(titleId)
                .map(title -> new ScreenTitleReportTargetDTO(
                        title.getId(),
                        title.getTitle(),
                        title.getSlug(),
                        title.getStatus() != null ? title.getStatus().name() : null,
                        title.isPublished()
                ));
    }

    @Override
    public Optional<String> findEntryBadgeForReport(UUID titleId, UUID entryId) {
        if (titleId == null || entryId == null) {
            return Optional.empty();
        }
        return screenEntryRepositoryPort.findById(entryId)
                .filter(entry -> titleId.equals(entry.getTitleId()))
                .map(ScreenDiscussionContractAdapter::formatEntryBadge);
    }

    @Override
    public Optional<ScreenTitleNavigationDTO> findPublishedNavigationByTitleId(UUID titleId) {
        if (titleId == null) {
            return Optional.empty();
        }
        return screenTitleRepositoryPort.findPublishedById(titleId)
                .filter(title -> title.getSlug() != null && !title.getSlug().isBlank())
                .map(title -> new ScreenTitleNavigationDTO(
                        title.getId(),
                        title.getSlug().trim()
                ));
    }

    public static String formatEntryBadge(ScreenEntry entry) {
        if (entry == null) {
            return "";
        }
        return formatEntryBadge(entry.getType(), entry.getNumber());
    }

    public static String formatEntryBadge(ScreenEntryType type, Integer number) {
        if (type == null) {
            return "";
        }
        return switch (type) {
            case EPISODE -> "Tập " + (number != null ? number : "");
            case FEATURE -> "Bản chiếu chính";
            case SPECIAL -> number != null ? "Ngoại truyện " + number : "Ngoại truyện";
            case TRAILER -> number != null ? "Trailer " + number : "Trailer";
        };
    }
}
