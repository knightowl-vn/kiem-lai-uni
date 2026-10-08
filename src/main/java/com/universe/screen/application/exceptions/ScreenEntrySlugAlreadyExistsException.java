package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class ScreenEntrySlugAlreadyExistsException extends BaseApplicationException {

    public ScreenEntrySlugAlreadyExistsException(String slug) {
        super("SCREEN_ENTRY_SLUG_EXISTS", "Slug screen entry \"" + slug + "\" đã tồn tại cho Screen title này.");
    }

    public ScreenEntrySlugAlreadyExistsException(String slug, Throwable cause) {
        super("SCREEN_ENTRY_SLUG_EXISTS", "Slug screen entry \"" + slug + "\" đã tồn tại cho Screen title này.", cause);
    }
}
