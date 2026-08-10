package com.universe.wiki.application.article.delete;

import com.universe.wiki.application.exceptions
        .WikiArticleNotFoundException;

import com.universe.wiki.application.ports
        .WikiArticleRepositoryPort;

import com.universe.wiki.application.ports
        .WikiArticleRevisionRepositoryPort;

import com.universe.wiki.domain.article
        .ArticleType;

import com.universe.wiki.domain.article
        .Slug;

import com.universe.wiki.domain.article
        .WikiArticle;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeleteWikiArticleUseCaseTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-09T01:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-09T02:00:00Z"
            );

    @Mock
    private WikiArticleRepositoryPort
            articleRepositoryPort;

    @Mock
    private WikiArticleRevisionRepositoryPort
            revisionRepositoryPort;

    private DeleteWikiArticleUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase =
                new DeleteWikiArticleUseCase(
                        articleRepositoryPort,
                        revisionRepositoryPort
                );
    }


    @Test
    @DisplayName(
            "Xóa DRAFT bằng cách xóa revisions trước rồi xóa article"
    )
    void shouldDeleteDraftArticle() {
        WikiArticle article =
                createDraft();

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(article)
        );

        useCase.execute(
                new DeleteWikiArticleCommand(
                        ARTICLE_ID
                )
        );

        InOrder inOrder =
                inOrder(
                        revisionRepositoryPort,
                        articleRepositoryPort
                );

        inOrder.verify(
                revisionRepositoryPort
        ).deleteAllByArticleId(
                ARTICLE_ID
        );

        inOrder.verify(
                articleRepositoryPort
        ).deleteById(
                ARTICLE_ID
        );
    }


    @Test
    @DisplayName(
            "Không cho xóa trực tiếp bài PUBLISHED"
    )
    void shouldRejectDeletingPublishedArticle() {
        WikiArticle article =
                createCompleteDraft();

        article.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(article)
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new DeleteWikiArticleCommand(
                                ARTICLE_ID
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Không thể xóa bài viết đang PUBLISHED. "
                        + "Hãy gỡ xuất bản bài viết trước khi xóa."
                );

        verify(
                revisionRepositoryPort,
                never()
        ).deleteAllByArticleId(
                ARTICLE_ID
        );

        verify(
                articleRepositoryPort,
                never()
        ).deleteById(
                ARTICLE_ID
        );
    }


    @Test
    @DisplayName(
            "Cho phép xóa bài ARCHIVED"
    )
    void shouldDeleteArchivedArticle() {
        WikiArticle article =
                createCompleteDraft();

        article.publish(
                ADMIN_ID,
                UPDATED_AT
        );

        article.archive(
                ADMIN_ID,
                UPDATED_AT.plusSeconds(60)
        );

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(article)
        );

        useCase.execute(
                new DeleteWikiArticleCommand(
                        ARTICLE_ID
                )
        );

        verify(
                revisionRepositoryPort
        ).deleteAllByArticleId(
                ARTICLE_ID
        );

        verify(
                articleRepositoryPort
        ).deleteById(
                ARTICLE_ID
        );
    }


    @Test
    @DisplayName(
            "Từ chối xóa khi không tìm thấy bài"
    )
    void shouldRejectWhenArticleDoesNotExist() {
        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.empty()
        );

        assertThatThrownBy(() ->
                useCase.execute(
                        new DeleteWikiArticleCommand(
                                ARTICLE_ID
                        )
                )
        )
                .isInstanceOf(
                        WikiArticleNotFoundException.class
                );

        verify(
                revisionRepositoryPort,
                never()
        ).deleteAllByArticleId(
                ARTICLE_ID
        );

        verify(
                articleRepositoryPort,
                never()
        ).deleteById(
                ARTICLE_ID
        );
    }


    private WikiArticle createDraft() {
        return WikiArticle.createDraft(
                ARTICLE_ID,
                "Trần Bình An",
                new Slug(
                        "tran-binh-an"
                ),
                ArticleType.CHARACTER,
                ADMIN_ID,
                CREATED_AT
        );
    }


    private WikiArticle createCompleteDraft() {
        WikiArticle article =
                createDraft();

        article.updateDraft(
                "Trần Bình An",
                new Slug(
                        "tran-binh-an"
                ),
                ArticleType.CHARACTER,
                "",
                "Nội dung chi tiết.",
                ADMIN_ID,
                UPDATED_AT
        );

        return article;
    }
}