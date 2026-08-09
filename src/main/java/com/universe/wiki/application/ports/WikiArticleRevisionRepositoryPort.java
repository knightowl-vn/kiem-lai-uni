package com.universe.wiki.application.ports;

import com.universe.wiki.domain.revision.WikiArticleRevision;

import java.util.Optional;
import java.util.UUID;

/**
 * Port persistence cho lịch sử WikiArticle.
 *
 * Revision là dữ liệu append-only:
 * chỉ thêm mới, không cập nhật revision đã tồn tại.
 */
public interface WikiArticleRevisionRepositoryPort {

    void save(
            WikiArticleRevision revision
    );

    Optional<WikiArticleRevision>
            findByArticleIdAndRevisionNumber(
                    UUID articleId,
                    long revisionNumber
            );
}