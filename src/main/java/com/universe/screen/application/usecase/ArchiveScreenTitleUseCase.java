package com.universe.screen.application.usecase;

import com.universe.screen.application.dto.ScreenTitleDTO;
import com.universe.screen.application.exceptions.ScreenTitleNotFoundException;
import com.universe.screen.application.exceptions.ScreenTitleVideoConflictException;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import com.universe.screen.application.ports.ScreenMediaVisibilityPort;
import com.universe.screen.application.ports.ScreenTitleRepositoryPort;
import com.universe.screen.domain.ScreenEntry;
import com.universe.screen.domain.ScreenTitle;
import com.universe.shared.time.ClockPort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class ArchiveScreenTitleUseCase {

    private static final Logger LOGGER = LoggerFactory.getLogger(ArchiveScreenTitleUseCase.class);
    private static final int MAX_RETRIES = 3;

    private final ScreenTitleRepositoryPort titleRepository;
    private final ScreenEntryRepositoryPort entryRepository;
    private final ScreenMediaVisibilityPort mediaVisibilityPort;
    private final ClockPort clockPort;
    private final TransactionTemplate transactionTemplate;

    public ArchiveScreenTitleUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository,
            ScreenMediaVisibilityPort mediaVisibilityPort,
            ClockPort clockPort
    ) {
        this(titleRepository, entryRepository, mediaVisibilityPort, clockPort, (TransactionTemplate) null);
    }

    @Autowired
    public ArchiveScreenTitleUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository,
            ScreenMediaVisibilityPort mediaVisibilityPort,
            ClockPort clockPort,
            @Autowired(required = false) PlatformTransactionManager transactionManager
    ) {
        this(titleRepository, entryRepository, mediaVisibilityPort, clockPort,
                transactionManager != null ? new TransactionTemplate(transactionManager) : null);
    }

    public ArchiveScreenTitleUseCase(
            ScreenTitleRepositoryPort titleRepository,
            ScreenEntryRepositoryPort entryRepository,
            ScreenMediaVisibilityPort mediaVisibilityPort,
            ClockPort clockPort,
            TransactionTemplate transactionTemplate
    ) {
        this.titleRepository = Objects.requireNonNull(titleRepository, "ScreenTitleRepositoryPort cannot be null.");
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
        this.mediaVisibilityPort = Objects.requireNonNull(mediaVisibilityPort, "ScreenMediaVisibilityPort cannot be null.");
        this.clockPort = Objects.requireNonNull(clockPort, "ClockPort cannot be null.");
        this.transactionTemplate = transactionTemplate;
        if (this.transactionTemplate != null) {
            this.transactionTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
    }

    public ScreenTitleDTO execute(UUID titleId) {
        Objects.requireNonNull(titleId, "Title ID cannot be null.");

        if (transactionTemplate == null) {
            return executeInTransaction(titleId);
        }

        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                return transactionTemplate.execute(status -> executeInTransaction(titleId));
            } catch (ScreenTitleVideoConflictException ex) {
                if (attempt == MAX_RETRIES) {
                    throw ex;
                }
                LOGGER.info("Dynamic asset set conflict archiving ScreenTitle [{}]. Retrying (attempt {}/{})",
                        titleId, attempt, MAX_RETRIES);
            }
        }
        throw new IllegalStateException("Max retries exceeded for title archival: " + titleId);
    }

    private ScreenTitleDTO executeInTransaction(UUID titleId) {
        // Preliminary collect candidate video bindings across all child entries
        List<ScreenEntry> preliminaryEntries = entryRepository.findByTitleId(titleId);
        List<ScreenEntryRepositoryPort.EntryVideoBinding> preliminaryBindings = preliminaryEntries.stream()
                .sorted(java.util.Comparator.comparing(ScreenEntry::getId))
                .map(entry -> new ScreenEntryRepositoryPort.EntryVideoBinding(entry.getId(), entry.getVideoMediaAssetId()))
                .toList();
        List<UUID> preliminaryVideoAssetIds = preliminaryBindings.stream()
                .map(ScreenEntryRepositoryPort.EntryVideoBinding::videoMediaAssetId)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();

        // STEP 1: Global Lock Protocol - MediaAssets locked FIRST in ascending UUID order
        for (UUID videoAssetId : preliminaryVideoAssetIds) {
            mediaVisibilityPort.lockAndInspectVideoMediaAsset(videoAssetId);
        }

        // STEP 2: Load ScreenTitle with lock
        ScreenTitle title = titleRepository.findByIdForUpdate(titleId)
                .orElseThrow(() -> new ScreenTitleNotFoundException(titleId));

        // STEP 3: Post-lock Authoritative Consistency Check (fresh scalar current-read bypassing Hibernate L1 cache)
        List<ScreenEntryRepositoryPort.EntryVideoBinding> authoritativeBindings =
                entryRepository.findEntryVideoBindingsByTitleIdCurrentRead(titleId).stream()
                        .sorted(java.util.Comparator.comparing(ScreenEntryRepositoryPort.EntryVideoBinding::entryId))
                        .toList();

        if (!preliminaryBindings.equals(authoritativeBindings)) {
            throw new ScreenTitleVideoConflictException(
                    titleId,
                    preliminaryBindings,
                    authoritativeBindings
            );
        }

        Instant archivedAt = clockPort.now();
        title.archive(archivedAt);

        ScreenTitle saved = titleRepository.save(title);

        // STEP 4: P0-A Reconcile videos of affected child entries: demote ONLY IF unreferenced
        for (ScreenEntryRepositoryPort.EntryVideoBinding binding : authoritativeBindings) {
            UUID videoAssetId = binding.videoMediaAssetId();
            if (videoAssetId != null) {
                boolean hasOther = entryRepository.hasOtherPublishedReference(videoAssetId, null);
                if (!hasOther) {
                    mediaVisibilityPort.makeVideoPrivate(videoAssetId, binding.entryId());
                }
            }
        }

        return toDTO(saved);
    }

    private static ScreenTitleDTO toDTO(ScreenTitle title) {
        return ScreenTitleDTO.from(title);
    }
}
