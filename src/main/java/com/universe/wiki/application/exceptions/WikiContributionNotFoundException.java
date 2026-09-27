package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

/**
 * Ngoại lệ ném ra khi không tìm thấy đóng góp bài viết Wiki theo định danh ID.
 */
public class WikiContributionNotFoundException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public WikiContributionNotFoundException(UUID contributionId) {
        super(
                "WIKI_CONTRIBUTION_NOT_FOUND",
                "Không tìm thấy đóng góp bài viết Wiki: " + contributionId
        );
    }
}
