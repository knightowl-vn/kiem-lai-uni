package com.universe.wiki.application.revision.query.list;

import com.universe.wiki.application.ports.WikiArticleRevisionQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleRevisionPageDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class ListWikiArticleRevisionsUseCase {

    private static final int MAX_PAGE_SIZE =
            100;

    private final WikiArticleRevisionQueryPort
            revisionQueryPort;

    public ListWikiArticleRevisionsUseCase(
            WikiArticleRevisionQueryPort revisionQueryPort
    ) {
        this.revisionQueryPort =
                revisionQueryPort;
    }

    @Transactional(readOnly = true)
    public WikiArticleRevisionPageDTO execute(
            ListWikiArticleRevisionsQuery query
    ) {
        Objects.requireNonNull(
                query,
                "List wiki article revisions query "
                        + "không được để trống."
        );

        UUID articleId =
                Objects.requireNonNull(
                        query.articleId(),
                        "Article ID không được để trống."
                );

        validatePagination(
                query.page(),
                query.size()
        );

        return revisionQueryPort
                .findPageByArticleId(
                        articleId,
                        query.page(),
                        query.size()
                );
    }

    private void validatePagination(
            int page,
            int size
    ) {
        if (page < 0) {
            throw new IllegalArgumentException(
                    "Page không được nhỏ hơn 0."
            );
        }

        if (size < 1) {
            throw new IllegalArgumentException(
                    "Page size phải lớn hơn hoặc bằng 1."
            );
        }

        if (size > MAX_PAGE_SIZE) {
            throw new IllegalArgumentException(
                    "Page size không được vượt quá "
                            + MAX_PAGE_SIZE
                            + "."
            );
        }
    }
}