package com.universe.wiki.application.revision.query.detail;

import com.universe.wiki.application.exceptions
        .WikiArticleRevisionNotFoundException;
import com.universe.wiki.application.ports
        .WikiArticleRevisionQueryPort;
import com.universe.wiki.contracts.dto
        .WikiArticleRevisionDetailDTO;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
public class GetWikiArticleRevisionDetailUseCase {

    private final WikiArticleRevisionQueryPort
            revisionQueryPort;

    public GetWikiArticleRevisionDetailUseCase(
            WikiArticleRevisionQueryPort revisionQueryPort
    ) {
        this.revisionQueryPort =
                revisionQueryPort;
    }

    @Transactional(readOnly = true)
    public WikiArticleRevisionDetailDTO execute(
            GetWikiArticleRevisionDetailQuery query
    ) {
        Objects.requireNonNull(
                query,
                "Get wiki article revision detail query "
                        + "không được để trống."
        );

        UUID articleId =
                Objects.requireNonNull(
                        query.articleId(),
                        "Article ID không được để trống."
                );

        long revisionNumber =
                query.revisionNumber();

        if (revisionNumber < 1L) {
            throw new IllegalArgumentException(
                    "Revision number phải lớn hơn hoặc bằng 1."
            );
        }

        return revisionQueryPort
                .findDetailByArticleIdAndRevisionNumber(
                        articleId,
                        revisionNumber
                )
                .orElseThrow(() ->
                        new WikiArticleRevisionNotFoundException(
                                articleId,
                                revisionNumber
                        )
                );
    }
}