package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

public class ScreenTitleSlugAlreadyExistsException extends BaseApplicationException {

    public ScreenTitleSlugAlreadyExistsException(String slug) {
        super("SCREEN_TITLE_SLUG_EXISTS", "Slug screen title \"" + slug + "\" đã tồn tại trên hệ thống.");
    }

    public ScreenTitleSlugAlreadyExistsException(String slug, Throwable cause) {
        super("SCREEN_TITLE_SLUG_EXISTS", "Slug screen title \"" + slug + "\" đã tồn tại trên hệ thống.", cause);
    }
}
