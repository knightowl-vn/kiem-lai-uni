package com.universe.screen.domain;

import java.util.Objects;
import java.util.UUID;

/**
 * Ordered association between a {@link ScreenFranchise} and a member {@link ScreenTitle}.
 *
 * <p>V1 ordering invariant:
 * <ul>
 *   <li>Immutable scalar UUID identity ({@code id});</li>
 *   <li>Scalar reference to parent franchise ({@code franchiseId});</li>
 *   <li>Scalar reference to member catalog title ({@code titleId});</li>
 *   <li>0-based display order index ({@code displayOrder &gt;= 0});</li>
 *   <li>Zero previous/next title pointer chains.</li>
 * </ul>
 */
public final class ScreenFranchiseMembership {

    private final UUID id;
    private final UUID franchiseId;
    private final UUID titleId;
    private int displayOrder;

    /**
     * Constructs a ScreenFranchiseMembership with validated scalar identities and ordering.
     *
     * @param id non-null membership UUID
     * @param franchiseId non-null parent franchise UUID
     * @param titleId non-null member title UUID
     * @param displayOrder non-negative display order index
     */
    public ScreenFranchiseMembership(UUID id, UUID franchiseId, UUID titleId, int displayOrder) {
        this.id = Objects.requireNonNull(id, "Membership ID cannot be null.");
        this.franchiseId = Objects.requireNonNull(franchiseId, "Franchise ID cannot be null.");
        this.titleId = Objects.requireNonNull(titleId, "Title ID cannot be null.");
        this.displayOrder = validateDisplayOrder(displayOrder);
    }

    /**
     * Factory method to create a new ScreenFranchiseMembership.
     */
    public static ScreenFranchiseMembership create(UUID id, UUID franchiseId, UUID titleId, int displayOrder) {
        return new ScreenFranchiseMembership(id, franchiseId, titleId, displayOrder);
    }

    /**
     * Updates the franchise display order index.
     *
     * @param newDisplayOrder non-negative display order index
     */
    public void updateDisplayOrder(int newDisplayOrder) {
        this.displayOrder = validateDisplayOrder(newDisplayOrder);
    }

    private static int validateDisplayOrder(int order) {
        if (order < 0) {
            throw new IllegalArgumentException("Display order cannot be negative: " + order);
        }
        return order;
    }

    public UUID getId() {
        return id;
    }

    public UUID getFranchiseId() {
        return franchiseId;
    }

    public UUID getTitleId() {
        return titleId;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ScreenFranchiseMembership that = (ScreenFranchiseMembership) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ScreenFranchiseMembership{" +
                "id=" + id +
                ", franchiseId=" + franchiseId +
                ", titleId=" + titleId +
                ", displayOrder=" + displayOrder +
                '}';
    }
}
