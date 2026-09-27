package com.universe.wiki.application.exceptions;

import com.universe.shared.exceptions.BaseApplicationException;

import java.util.UUID;

public class WikiCoverStaleMutationException extends BaseApplicationException {

    private static final long serialVersionUID = 1L;

    public WikiCoverStaleMutationException(String message) {
        super("WIKI_COVER_STALE_MUTATION", message);
    }

    public WikiCoverStaleMutationException(UUID articleId) {
        super(
                "WIKI_COVER_STALE_MUTATION",
                "Ảnh bìa của bài viết đã bị thay đổi đồng thời trong lúc thao tác: " + articleId
        );
    }
}
