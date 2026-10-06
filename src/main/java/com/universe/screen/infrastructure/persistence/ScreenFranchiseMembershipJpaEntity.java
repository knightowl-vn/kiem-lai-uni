package com.universe.screen.infrastructure.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

@Entity
@Table(
        name = "screen_franchise_memberships",
        uniqueConstraints = {
                @UniqueConstraint(
                        name = "uq_screen_franchise_memberships_franchise_title",
                        columnNames = {
                                "franchise_id",
                                "title_id"
                        }
                )
        },
        indexes = {
                @Index(
                        name = "idx_screen_franchise_memberships_title",
                        columnList = "title_id"
                )
        }
)
public class ScreenFranchiseMembershipJpaEntity {

    @Id
    @Column(
            name = "id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String id;

    @Column(
            name = "franchise_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String franchiseId;

    @Column(
            name = "title_id",
            nullable = false,
            length = 36,
            columnDefinition = "CHAR(36)"
    )
    private String titleId;

    @Column(
            name = "display_order",
            nullable = false
    )
    private int displayOrder;

    public ScreenFranchiseMembershipJpaEntity() {
    }

    public ScreenFranchiseMembershipJpaEntity(
            String id,
            String franchiseId,
            String titleId,
            int displayOrder
    ) {
        this.id = id;
        this.franchiseId = franchiseId;
        this.titleId = titleId;
        this.displayOrder = displayOrder;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getFranchiseId() {
        return franchiseId;
    }

    public void setFranchiseId(String franchiseId) {
        this.franchiseId = franchiseId;
    }

    public String getTitleId() {
        return titleId;
    }

    public void setTitleId(String titleId) {
        this.titleId = titleId;
    }

    public int getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(int displayOrder) {
        this.displayOrder = displayOrder;
    }
}
