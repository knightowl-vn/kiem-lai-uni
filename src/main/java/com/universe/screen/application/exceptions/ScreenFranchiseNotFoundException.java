package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class ScreenFranchiseNotFoundException extends BaseApplicationException {

    public ScreenFranchiseNotFoundException(UUID id) {
        super("SCREEN_FRANCHISE_NOT_FOUND", "Không tìm thấy Screen franchise với ID: " + id);
    }

    public ScreenFranchiseNotFoundException(String message) {
        super("SCREEN_FRANCHISE_NOT_FOUND", message);
    }
}
