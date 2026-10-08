package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ScreenFranchiseMembershipNotFoundException extends BaseApplicationException {

    public ScreenFranchiseMembershipNotFoundException(UUID membershipId) {
        super("SCREEN_FRANCHISE_MEMBERSHIP_NOT_FOUND",
                "Không tìm thấy thành viên franchise với ID: " + membershipId);
    }

    public ScreenFranchiseMembershipNotFoundException(String message) {
        super("SCREEN_FRANCHISE_MEMBERSHIP_NOT_FOUND", message);
    }
}
