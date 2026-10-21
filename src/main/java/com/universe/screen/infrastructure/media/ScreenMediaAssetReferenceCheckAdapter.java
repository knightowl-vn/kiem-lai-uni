package com.universe.screen.infrastructure.media;

import com.universe.media.contracts.dto.MediaTypeDTO;
import com.universe.media.contracts.exceptions.MediaAssetReferencedException;
import com.universe.media.contracts.interfaces.MediaAssetReferenceCheckPort;
import com.universe.screen.application.ports.ScreenEntryRepositoryPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Screen bounded-context adapter implementing {@link MediaAssetReferenceCheckPort}.
 *
 * <p>Protects Screen video assets from generic Media archive, delete, or physical purge
 * when an existing {@link com.universe.screen.domain.ScreenEntry} references the asset.
 *
 * <p><strong>Concurrency &amp; Locking:</strong>
 * When called inside an active ambient transaction, uses {@link ScreenEntryRepositoryPort#findEntryIdsByVideoMediaAssetIdForUpdate(UUID)}
 * to acquire exclusive row locks across all referencing ScreenEntry rows. This enforces a consistent lock acquisition hierarchy
 * (MediaAsset row lock held first by caller, ScreenEntry row/index locks acquired second), reducing deadlock risk against
 * concurrent attachment workflows and preventing concurrent attachment races.
 *
 * <p>When called outside an active transaction (e.g. non-transactional physical purge pre-checks),
 * falls back to the non-locking {@link ScreenEntryRepositoryPort#findEntryIdsByVideoMediaAssetId(UUID)} query.
 */
@Component
public class ScreenMediaAssetReferenceCheckAdapter implements MediaAssetReferenceCheckPort {

    private final ScreenEntryRepositoryPort entryRepository;

    public ScreenMediaAssetReferenceCheckAdapter(ScreenEntryRepositoryPort entryRepository) {
        this.entryRepository = Objects.requireNonNull(entryRepository, "ScreenEntryRepositoryPort cannot be null.");
    }

    @Override
    public boolean supports(MediaTypeDTO mediaType) {
        return mediaType == MediaTypeDTO.VIDEO;
    }

    @Override
    public void ensureNotReferenced(UUID mediaAssetId) throws MediaAssetReferencedException {
        if (mediaAssetId == null) {
            return;
        }

        List<UUID> referencingEntryIds;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            referencingEntryIds = entryRepository.findEntryIdsByVideoMediaAssetIdForUpdate(mediaAssetId);
        } else {
            referencingEntryIds = entryRepository.findEntryIdsByVideoMediaAssetId(mediaAssetId);
        }

        if (!referencingEntryIds.isEmpty()) {
            throw new MediaAssetReferencedException(
                    mediaAssetId,
                    "Media asset [" + mediaAssetId + "] cannot be archived, deleted, or purged because it is currently referenced by ScreenEntry "
                            + referencingEntryIds + "."
            );
        }
    }
}
