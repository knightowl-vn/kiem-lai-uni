package com.universe.wiki.application.article.update.draft;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import com.universe.wiki.application.exceptions.ArticleSlugAlreadyExistsException;
import com.universe.wiki.application.ports.SlugGeneratorPort;
import com.universe.wiki.application.ports.WikiArticleRepositoryPort;
import com.universe.wiki.application.ports.WikiArticleRevisionRepositoryPort;

import com.universe.wiki.contracts.dto.WikiArticleDTO;

import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;

import com.universe.wiki.domain.revision.RevisionChangeType;
import com.universe.wiki.domain.revision.WikiArticleRevision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateDraftAndPublishWikiArticleUseCaseTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID OTHER_ARTICLE_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID REVISION_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-14T02:00:00Z"
            );

    private static final Instant PUBLISHED_AT =
            Instant.parse(
                    "2026-08-14T03:00:00Z"
            );


    @Mock
    private WikiArticleRepositoryPort
            articleRepositoryPort;

    @Mock
    private WikiArticleRevisionRepositoryPort
            revisionRepositoryPort;

    @Mock
    private SlugGeneratorPort
            slugGeneratorPort;

    @Mock
    private IdGeneratorPort
            idGeneratorPort;

    @Mock
    private ClockPort
            clockPort;


    private UpdateDraftAndPublishWikiArticleUseCase
            useCase;


    @BeforeEach
    void setUp() {

        useCase =
                new UpdateDraftAndPublishWikiArticleUseCase(
                        articleRepositoryPort,
                        revisionRepositoryPort,
                        slugGeneratorPort,
                        idGeneratorPort,
                        clockPort
                );
    }


    @Test
    @DisplayName(
            "Cập nhật nội dung DRAFT rồi xuất bản bằng một revision UPDATE_AND_PUBLISH"
    )
    void shouldUpdateDraftAndPublishWithSingleRevision() {

        WikiArticle article =
                createDraftArticle();


        Slug newSlug =
                new Slug(
                        "tran-binh-an-hoan-thien"
                );


        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(
                        article
                )
        );


        when(
                slugGeneratorPort.generate(
                        "Trần Bình An hoàn thiện"
                )
        ).thenReturn(
                newSlug
        );


        when(
                articleRepositoryPort.findByArticleTypeAndSlug(
                        ArticleType.CHARACTER,
                        newSlug
                )
        ).thenReturn(
                Optional.empty()
        );


        when(
                clockPort.now()
        ).thenReturn(
                PUBLISHED_AT
        );


        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );


        WikiArticleDTO result =
                useCase.execute(
                        new UpdateDraftAndPublishWikiArticleCommand(
                                ARTICLE_ID,
                                "Trần Bình An hoàn thiện",
                                ArticleType.CHARACTER,
                                "Tóm tắt hoàn thiện",
                                "Nội dung hoàn thiện để xuất bản",
                                "  Hoàn thiện và xuất bản  ",
                                ADMIN_ID
                        )
                );


        assertThat(
                article.getStatus()
        ).isEqualTo(
                ArticleStatus.PUBLISHED
        );


        assertThat(
                article.getTitle()
        ).isEqualTo(
                "Trần Bình An hoàn thiện"
        );


        assertThat(
                article.getSlug().value()
        ).isEqualTo(
                "tran-binh-an-hoan-thien"
        );


        assertThat(
                article.getSummary()
        ).isEqualTo(
                "Tóm tắt hoàn thiện"
        );


        assertThat(
                article.getContent()
        ).isEqualTo(
                "Nội dung hoàn thiện để xuất bản"
        );


        assertThat(
                article.getPublishedBy()
        ).isEqualTo(
                ADMIN_ID
        );


        assertThat(
                article.getPublishedAt()
        ).isEqualTo(
                PUBLISHED_AT
        );


        /*
         * create draft = aggregate 1 / content 1
         *
         * update + publish = một business action:
         * aggregate +1, content +1 vì dữ liệu đổi.
         */
        assertThat(
                article.getAggregateVersion()
        ).isEqualTo(
                2L
        );


        assertThat(
                article.getContentVersion()
        ).isEqualTo(
                2L
        );


        assertThat(
                result.status()
        ).isEqualTo(
                "PUBLISHED"
        );


        verify(
                articleRepositoryPort
        ).save(
                article
        );


        ArgumentCaptor<WikiArticleRevision>
                revisionCaptor =
                ArgumentCaptor.forClass(
                        WikiArticleRevision.class
                );


        verify(
                revisionRepositoryPort
        ).save(
                revisionCaptor.capture()
        );


        WikiArticleRevision revision =
                revisionCaptor.getValue();


        assertThat(
                revision.id()
        ).isEqualTo(
                REVISION_ID
        );


        assertThat(
                revision.articleId()
        ).isEqualTo(
                ARTICLE_ID
        );


        assertThat(
                revision.revisionNumber()
        ).isEqualTo(
                2L
        );


        assertThat(
                revision.status()
        ).isEqualTo(
                ArticleStatus.PUBLISHED
        );


        assertThat(
                revision.changeType()
        ).isEqualTo(
                RevisionChangeType.UPDATE_AND_PUBLISH
        );


        assertThat(
                revision.editSummary()
        ).isEqualTo(
                "Hoàn thiện và xuất bản"
        );


        assertThat(
                revision.editedBy()
        ).isEqualTo(
                ADMIN_ID
        );


        assertThat(
                revision.createdAt()
        ).isEqualTo(
                PUBLISHED_AT
        );
    }


    @Test
    @DisplayName(
            "DRAFT không đổi nội dung thì chỉ tạo revision PUBLISH và không tăng contentVersion"
    )
    void shouldPublishWithoutContentChange() {

        WikiArticle article =
                createDraftArticle();


        Slug sameSlug =
                new Slug(
                        "tran-binh-an"
                );


        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(
                        article
                )
        );


        when(
                slugGeneratorPort.generate(
                        "Trần Bình An"
                )
        ).thenReturn(
                sameSlug
        );


        when(
                articleRepositoryPort.findByArticleTypeAndSlug(
                        ArticleType.CHARACTER,
                        sameSlug
                )
        ).thenReturn(
                Optional.of(
                        article
                )
        );


        when(
                clockPort.now()
        ).thenReturn(
                PUBLISHED_AT
        );


        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );


        WikiArticleDTO result =
                useCase.execute(
                        new UpdateDraftAndPublishWikiArticleCommand(
                                ARTICLE_ID,
                                "Trần Bình An",
                                ArticleType.CHARACTER,
                                "Nhân vật chính của Kiếm Lai.",
                                "Nội dung ban đầu của bài viết.",
                                "   ",
                                ADMIN_ID
                        )
                );


        assertThat(
                article.getStatus()
        ).isEqualTo(
                ArticleStatus.PUBLISHED
        );


        assertThat(
                article.getAggregateVersion()
        ).isEqualTo(
                2L
        );


        assertThat(
                article.getContentVersion()
        ).isEqualTo(
                1L
        );


        assertThat(
                result.status()
        ).isEqualTo(
                "PUBLISHED"
        );


        ArgumentCaptor<WikiArticleRevision>
                revisionCaptor =
                ArgumentCaptor.forClass(
                        WikiArticleRevision.class
                );


        verify(
                revisionRepositoryPort
        ).save(
                revisionCaptor.capture()
        );


        WikiArticleRevision revision =
                revisionCaptor.getValue();


        assertThat(
                revision.changeType()
        ).isEqualTo(
                RevisionChangeType.PUBLISH
        );


        assertThat(
                revision.editSummary()
        ).isEqualTo(
                "Xuất bản bài viết"
        );


        assertThat(
                revision.revisionNumber()
        ).isEqualTo(
                2L
        );
    }


    @Test
    @DisplayName(
            "Từ chối Lưu & xuất bản khi content rỗng và không mutate Aggregate"
    )
    void shouldRejectBlankContentBeforeMutation() {

        WikiArticle article =
                createDraftArticle();


        Slug newSlug =
                new Slug(
                        "ten-moi-khong-duoc-luu"
                );


        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(
                        article
                )
        );


        when(
                slugGeneratorPort.generate(
                        "Tên mới không được lưu"
                )
        ).thenReturn(
                newSlug
        );


        when(
                articleRepositoryPort.findByArticleTypeAndSlug(
                        ArticleType.CHARACTER,
                        newSlug
                )
        ).thenReturn(
                Optional.empty()
        );


        when(
                clockPort.now()
        ).thenReturn(
                PUBLISHED_AT
        );


        assertThatThrownBy(() ->
                useCase.execute(
                        new UpdateDraftAndPublishWikiArticleCommand(
                                ARTICLE_ID,
                                "Tên mới không được lưu",
                                ArticleType.CHARACTER,
                                "Tóm tắt mới",
                                "   ",
                                "Xuất bản",
                                ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Bài viết phải có nội dung trước khi xuất bản."
                );


        /*
         * Không được update dở dang trước khi publish fail.
         */
        assertThat(
                article.getTitle()
        ).isEqualTo(
                "Trần Bình An"
        );


        assertThat(
                article.getSlug().value()
        ).isEqualTo(
                "tran-binh-an"
        );


        assertThat(
                article.getSummary()
        ).isEqualTo(
                "Nhân vật chính của Kiếm Lai."
        );


        assertThat(
                article.getContent()
        ).isEqualTo(
                "Nội dung ban đầu của bài viết."
        );


        assertThat(
                article.getStatus()
        ).isEqualTo(
                ArticleStatus.DRAFT
        );


        assertThat(
                article.getAggregateVersion()
        ).isEqualTo(
                1L
        );


        assertThat(
                article.getContentVersion()
        ).isEqualTo(
                1L
        );


        verify(
                articleRepositoryPort,
                never()
        ).save(
                article
        );


        verify(
                revisionRepositoryPort,
                never()
        ).save(
                org.mockito.ArgumentMatchers
                        .any(
                                WikiArticleRevision.class
                        )
        );
    }


    @Test
    @DisplayName(
            "Từ chối Lưu & xuất bản khi slug mới thuộc về bài Wiki khác"
    )
    void shouldRejectDuplicateSlug() {

        WikiArticle article =
                createDraftArticle();


        WikiArticle anotherArticle =
                WikiArticle.createDraft(
                        OTHER_ARTICLE_ID,
                        "Nhân vật khác",
                        new Slug(
                                "tran-binh-an-hoan-thien"
                        ),
                        ArticleType.CHARACTER,
                        "Tóm tắt khác",
                        "Nội dung khác",
                        ADMIN_ID,
                        CREATED_AT
                );


        Slug duplicateSlug =
                new Slug(
                        "tran-binh-an-hoan-thien"
                );


        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(
                        article
                )
        );


        when(
                slugGeneratorPort.generate(
                        "Trần Bình An hoàn thiện"
                )
        ).thenReturn(
                duplicateSlug
        );


        when(
                articleRepositoryPort.findByArticleTypeAndSlug(
                        ArticleType.CHARACTER,
                        duplicateSlug
                )
        ).thenReturn(
                Optional.of(
                        anotherArticle
                )
        );


        assertThatThrownBy(() ->
                useCase.execute(
                        new UpdateDraftAndPublishWikiArticleCommand(
                                ARTICLE_ID,
                                "Trần Bình An hoàn thiện",
                                ArticleType.CHARACTER,
                                "Tóm tắt",
                                "Nội dung",
                                null,
                                ADMIN_ID
                        )
                )
        )
                .isInstanceOf(
                        ArticleSlugAlreadyExistsException.class
                )
                .hasMessageContaining(
                        "Slug bài viết đã tồn tại"
                );


        assertThat(
                article.getStatus()
        ).isEqualTo(
                ArticleStatus.DRAFT
        );


        assertThat(
                article.getAggregateVersion()
        ).isEqualTo(
                1L
        );


        verify(
                articleRepositoryPort,
                never()
        ).save(
                article
        );


        verify(
                revisionRepositoryPort,
                never()
        ).save(
                org.mockito.ArgumentMatchers
                        .any(
                                WikiArticleRevision.class
                        )
        );
    }


    private WikiArticle createDraftArticle() {

        return WikiArticle.createDraft(
                ARTICLE_ID,
                "Trần Bình An",
                new Slug(
                        "tran-binh-an"
                ),
                ArticleType.CHARACTER,
                "Nhân vật chính của Kiếm Lai.",
                "Nội dung ban đầu của bài viết.",
                ADMIN_ID,
                CREATED_AT
        );
    }
}