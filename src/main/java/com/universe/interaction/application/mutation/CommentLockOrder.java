package com.universe.interaction.application.mutation;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Utility providing canonical total ordering for pessimistic lock acquisition across multiple comments.
 *
 * <p>To prevent deadlocks and lock-order inversions between multi-comment mutation operations
 * (e.g. {@link ReplyCommentUseCase}) and bulk lock operations (e.g. {@link CleanupCommunityPostInteractionsUseCase}),
 * all multi-comment writers must acquire locks in the exact same total order:
 * lexicographical ascending order of the persisted comment ID string (matching {@code ORDER BY c.id ASC} in MySQL).
 */
public final class CommentLockOrder {

    public static final Comparator<UUID> UUID_STRING_COMPARATOR = Comparator.comparing(
            uuid -> Objects.requireNonNull(uuid, "Comment UUID cannot be null.").toString()
    );

    private CommentLockOrder() {
        // utility class
    }

    /**
     * Orders two comment IDs in canonical lock acquisition order (ID ASC).
     *
     * @param id1 first comment ID
     * @param id2 second comment ID
     * @return immutable list containing the IDs ordered canonically
     */
    public static List<UUID> inLockOrder(UUID id1, UUID id2) {
        Objects.requireNonNull(id1, "Comment ID 1 cannot be null.");
        Objects.requireNonNull(id2, "Comment ID 2 cannot be null.");
        if (id1.equals(id2)) {
            return List.of(id1);
        }
        return UUID_STRING_COMPARATOR.compare(id1, id2) <= 0
                ? List.of(id1, id2)
                : List.of(id2, id1);
    }
}
