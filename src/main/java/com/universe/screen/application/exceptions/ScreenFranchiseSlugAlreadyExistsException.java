package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class ScreenFranchiseSlugAlreadyExistsException extends BaseApplicationException {

    public ScreenFranchiseSlugAlreadyExistsException(String slug) {
        super("SCREEN_FRANCHISE_SLUG_EXISTS", "Slug franchise \"" + slug + "\" đã tồn tại trên hệ thống.");
    }

    public ScreenFranchiseSlugAlreadyExistsException(String slug, Throwable cause) {
        super("SCREEN_FRANCHISE_SLUG_EXISTS", "Slug franchise \"" + slug + "\" đã tồn tại trên hệ thống.", cause);
    }
}
