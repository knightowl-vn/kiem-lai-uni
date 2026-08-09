package com.universe.wiki.application.ports;

import com.universe.wiki.contracts.dto.WikiArticleRevisionDetailDTO;
import com.universe.wiki.contracts.dto.WikiArticleRevisionPageDTO;

import java.util.Optional;
import java.util.UUID;

/**
 * Read port phục vụ truy vấn lịch sử WikiArticle.
 */
public interface WikiArticleRevisionQueryPort {

    WikiArticleRevisionPageDTO findPageByArticleId(
            UUID articleId,
            int page,
            int size
    );

    Optional<WikiArticleRevisionDetailDTO>
            findDetailByArticleIdAndRevisionNumber(
                    UUID articleId,
                    long revisionNumber
            );
}