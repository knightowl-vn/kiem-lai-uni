package com.universe.screen.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

/**
 * Thrown when a public visitor attempts to access a Screen title that is not found,
 * not published, or has been archived.
 *
 * <p>Preserves privacy invariants: does not reveal whether an unpublished title exists.
 */
public class PublishedScreenTitleNotFoundException extends BaseApplicationException {

    public PublishedScreenTitleNotFoundException(String slug) {
        super("PUBLISHED_SCREEN_TITLE_NOT_FOUND", "Không tìm thấy tác phẩm xuất bản với slug: " + slug);
    }
}
