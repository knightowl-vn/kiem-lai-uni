package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class DuplicateScreenFranchiseMembershipException extends BaseApplicationException {

    public DuplicateScreenFranchiseMembershipException(UUID franchiseId, UUID titleId) {
        super("SCREEN_FRANCHISE_MEMBERSHIP_DUPLICATE",
                "Tiêu đề (" + titleId + ") đã thuộc franchise (" + franchiseId + ").");
    }

    public DuplicateScreenFranchiseMembershipException(UUID franchiseId, UUID titleId, Throwable cause) {
        super("SCREEN_FRANCHISE_MEMBERSHIP_DUPLICATE",
                "Tiêu đề (" + titleId + ") đã thuộc franchise (" + franchiseId + ").", cause);
    }

    public DuplicateScreenFranchiseMembershipException(String message) {
        super("SCREEN_FRANCHISE_MEMBERSHIP_DUPLICATE", message);
    }
}
