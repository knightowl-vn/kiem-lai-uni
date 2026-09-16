package com.universe.novel.application.ports;

import com.universe.novel.domain.anchor.ChapterCommentAnchor;

import java.util.Optional;
import java.util.UUID;

/**
 * Novel application persistence port for immutable ChapterCommentAnchor storage.
 *
 * <p>Preserves clean architecture contracts:
 * <ul>
 *   <li>Framework-free interface;</li>
 *   <li>Append-only write contract (no mutation/update operations);</li>
 *   <li>One-to-one retrieval by {@code rootCommentId}.</li>
 * </ul>
 */
public interface ChapterCommentAnchorRepositoryPort {

    /**
     * Persists a new immutable ChapterCommentAnchor.
     *
     * @param anchor the anchor domain model to persist, must not be null
     * @return the persisted anchor domain model
     */
    ChapterCommentAnchor save(ChapterCommentAnchor anchor);

    /**
     * Finds an existing anchor by its root comment ID.
     *
     * @param rootCommentId the scalar ID of the root discussion comment
     * @return an Optional containing the anchor if found, or empty if not found
     */
    Optional<ChapterCommentAnchor> findByRootCommentId(UUID rootCommentId);
}
