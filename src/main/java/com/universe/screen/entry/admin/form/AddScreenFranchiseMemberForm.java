package com.universe.screen.entry.admin.form;

import java.util.UUID;

public class AddScreenFranchiseMemberForm {

    private UUID titleId;
    private Integer displayOrder;

    public AddScreenFranchiseMemberForm() {
    }

    public AddScreenFranchiseMemberForm(UUID titleId, Integer displayOrder) {
        this.titleId = titleId;
        this.displayOrder = displayOrder;
    }

    public UUID getTitleId() {
        return titleId;
    }

    public void setTitleId(UUID titleId) {
        this.titleId = titleId;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }
}
