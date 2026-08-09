package com.universe.wiki.application.article.create;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;

import com.universe.wiki.application.exceptions
        .ArticleSlugAlreadyExistsException;
import com.universe.wiki.application.ports
        .SlugGeneratorPort;
import com.universe.wiki.application.ports
        .WikiArticleRepositoryPort;
import com.universe.wiki.application.ports
        .WikiArticleRevisionRepositoryPort;

import com.universe.wiki.contracts.dto
        .WikiArticleDTO;

import com.universe.wiki.domain.article
        .ArticleStatus;
import com.universe.wiki.domain.article
        .ArticleType;
import com.universe.wiki.domain.article
        .Slug;
import com.universe.wiki.domain.article
        .WikiArticle;

import com.universe.wiki.domain.revision
        .RevisionChangeType;
import com.universe.wiki.domain.revision
        .WikiArticleRevision;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CreateWikiArticleUseCaseTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID REVISION_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-06T03:00:00Z"
            );

    private static final String TITLE =
            "Trần Bình An";

    private static final String SUMMARY =
            "Nhân vật chính của Kiếm Lai.";

    private static final String CONTENT =
            "Nội dung ban đầu của bài viết.";

    private static final String EDIT_SUMMARY =
            "Khởi tạo bài Trần Bình An";

    private static final Slug ARTICLE_SLUG =
            new Slug(
                    "tran-binh-an"
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

    private CreateWikiArticleUseCase
            createWikiArticleUseCase;

    @BeforeEach
    void setUp() {
        createWikiArticleUseCase =
                new CreateWikiArticleUseCase(
                        articleRepositoryPort,
                        revisionRepositoryPort,
                        slugGeneratorPort,
                        idGeneratorPort,
                        clockPort
                );
    }

    /*
     * =====================================================
     * CREATE DRAFT SUCCESS
     * =====================================================
     */

    @Test
    @DisplayName(
            "Tạo bài Wiki DRAFT cùng nội dung và revision đầu tiên"
    )
    void shouldCreateWikiArticleAsDraft() {
        prepareSuccessfulCreation();

        WikiArticleDTO result =
                createWikiArticleUseCase.execute(
                        createCommand()
                );

        verifySavedArticle();

        verifySavedInitialRevision();

        assertThat(
                result.id()
        ).isEqualTo(
                ARTICLE_ID
        );

        assertThat(
                result.title()
        ).isEqualTo(
                TITLE
        );

        assertThat(
                result.slug()
        ).isEqualTo(
                "tran-binh-an"
        );

        assertThat(
                result.articleType()
        ).isEqualTo(
                "CHARACTER"
        );

        assertThat(
                result.status()
        ).isEqualTo(
                "DRAFT"
        );

        /*
         * Behavior mới:
         * Draft được tạo cùng summary/content mà Admin nhập.
         */
        assertThat(
                result.summary()
        ).isEqualTo(
                SUMMARY
        );

        assertThat(
                result.content()
        ).isEqualTo(
                CONTENT
        );

        assertThat(
                result.createdBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                result.updatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                result.createdAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                result.updatedAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                1L
        );
    }

    /*
     * =====================================================
     * DUPLICATE SLUG
     * =====================================================
     */

    @Test
    @DisplayName(
            "Từ chối tạo bài khi slug đã tồn tại trong cùng loại"
    )
    void shouldRejectDuplicateSlug() {
        CreateWikiArticleCommand command =
                createCommand();

        when(
                slugGeneratorPort.generate(
                        TITLE
                )
        ).thenReturn(
                ARTICLE_SLUG
        );

        when(
                articleRepositoryPort
                        .existsByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                ARTICLE_SLUG
                        )
        ).thenReturn(
                true
        );

        assertThatThrownBy(() ->
                createWikiArticleUseCase.execute(
                        command
                )
        )
                .isInstanceOf(
                        ArticleSlugAlreadyExistsException.class
                )
                .hasMessage(
                        "Slug bài viết đã tồn tại trong loại "
                                + "CHARACTER: tran-binh-an"
                );

        verify(
                idGeneratorPort,
                never()
        ).generate();

        verify(
                clockPort,
                never()
        ).now();

        verify(
                articleRepositoryPort,
                never()
        ).save(
                any(WikiArticle.class)
        );

        verify(
                revisionRepositoryPort,
                never()
        ).save(
                any(WikiArticleRevision.class)
        );
    }

    /*
     * =====================================================
     * NULL COMMAND
     * =====================================================
     */

    @Test
    @DisplayName(
            "Từ chối command null"
    )
    void shouldRejectNullCommand() {
        assertThatThrownBy(() ->
                createWikiArticleUseCase.execute(
                        null
                )
        )
                .isInstanceOf(
                        NullPointerException.class
                )
                .hasMessage(
                        "Create wiki article command không được để trống."
                );

        verify(
                slugGeneratorPort,
                never()
        ).generate(
                any()
        );

        verify(
                idGeneratorPort,
                never()
        ).generate();

        verify(
                clockPort,
                never()
        ).now();

        verify(
                articleRepositoryPort,
                never()
        ).save(
                any(WikiArticle.class)
        );

        verify(
                revisionRepositoryPort,
                never()
        ).save(
                any(WikiArticleRevision.class)
        );
    }

    /*
     * =====================================================
     * REVISION SAVE FAILURE
     * =====================================================
     */

    @Test
    @DisplayName(
            "Lỗi lưu revision làm use case tạo bài thất bại"
    )
    void shouldFailWhenSavingInitialRevisionFails() {
        prepareSuccessfulCreation();

        doThrow(
                new IllegalStateException(
                        "Không thể lưu revision."
                )
        )
                .when(
                        revisionRepositoryPort
                )
                .save(
                        any(WikiArticleRevision.class)
                );

        assertThatThrownBy(() ->
                createWikiArticleUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessage(
                        "Không thể lưu revision."
                );

        verify(
                articleRepositoryPort
        ).save(
                any(WikiArticle.class)
        );

        verify(
                revisionRepositoryPort
        ).save(
                any(WikiArticleRevision.class)
        );
    }

    /*
     * =====================================================
     * INITIAL CONTENT
     * =====================================================
     */

    @Test
    @DisplayName(
            "Tạo Draft với summary và content ngay từ lần đầu"
    )
    void shouldCreateDraftWithInitialContent() {
        prepareSuccessfulCreation();

        WikiArticleDTO result =
                createWikiArticleUseCase.execute(
                        createCommand()
                );

        assertThat(
                result.status()
        ).isEqualTo(
                "DRAFT"
        );

        assertThat(
                result.summary()
        ).isEqualTo(
                SUMMARY
        );

        assertThat(
                result.content()
        ).isEqualTo(
                CONTENT
        );

        assertThat(
                result.aggregateVersion()
        ).isEqualTo(
                1L
        );

        verify(
                articleRepositoryPort
        ).save(
                any(WikiArticle.class)
        );

        verify(
                revisionRepositoryPort
        ).save(
                any(WikiArticleRevision.class)
        );
    }

    /*
     * =====================================================
     * HELPERS
     * =====================================================
     */

    private CreateWikiArticleCommand createCommand() {
        return new CreateWikiArticleCommand(
                TITLE,
                ArticleType.CHARACTER,
                SUMMARY,
                CONTENT,
                EDIT_SUMMARY,
                ADMIN_ID
        );
    }

    private void prepareSuccessfulCreation() {
        when(
                slugGeneratorPort.generate(
                        TITLE
                )
        ).thenReturn(
                ARTICLE_SLUG
        );

        when(
                articleRepositoryPort
                        .existsByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                ARTICLE_SLUG
                        )
        ).thenReturn(
                false
        );

        /*
         * ID thứ nhất cho Article.
         * ID thứ hai cho Revision #1.
         */
        when(
                idGeneratorPort.generate()
        ).thenReturn(
                ARTICLE_ID,
                REVISION_ID
        );

        when(
                clockPort.now()
        ).thenReturn(
                NOW
        );
    }

    private void verifySavedArticle() {
        ArgumentCaptor<WikiArticle> articleCaptor =
                ArgumentCaptor.forClass(
                        WikiArticle.class
                );

        verify(
                articleRepositoryPort
        ).save(
                articleCaptor.capture()
        );

        WikiArticle savedArticle =
                articleCaptor.getValue();

        assertThat(
                savedArticle.getId()
        ).isEqualTo(
                ARTICLE_ID
        );

        assertThat(
                savedArticle.getTitle()
        ).isEqualTo(
                TITLE
        );

        assertThat(
                savedArticle.getSlug()
        ).isEqualTo(
                ARTICLE_SLUG
        );

        assertThat(
                savedArticle.getArticleType()
        ).isEqualTo(
                ArticleType.CHARACTER
        );

        assertThat(
                savedArticle.getStatus()
        ).isEqualTo(
                ArticleStatus.DRAFT
        );

        /*
         * Đây chính là hai assertion cũ trước đây
         * đang dùng isEmpty().
         */
        assertThat(
                savedArticle.getSummary()
        ).isEqualTo(
                SUMMARY
        );

        assertThat(
                savedArticle.getContent()
        ).isEqualTo(
                CONTENT
        );

        assertThat(
                savedArticle.getCreatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                savedArticle.getUpdatedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                savedArticle.getCreatedAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                savedArticle.getUpdatedAt()
        ).isEqualTo(
                NOW
        );

        assertThat(
                savedArticle.getAggregateVersion()
        ).isEqualTo(
                1L
        );
    }

    private void verifySavedInitialRevision() {
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

        WikiArticleRevision savedRevision =
                revisionCaptor.getValue();

        assertThat(
                savedRevision.id()
        ).isEqualTo(
                REVISION_ID
        );

        assertThat(
                savedRevision.articleId()
        ).isEqualTo(
                ARTICLE_ID
        );

        assertThat(
                savedRevision.revisionNumber()
        ).isEqualTo(
                1L
        );

        assertThat(
                savedRevision.title()
        ).isEqualTo(
                TITLE
        );

        assertThat(
                savedRevision.slug().value()
        ).isEqualTo(
                "tran-binh-an"
        );

        assertThat(
                savedRevision.articleType()
        ).isEqualTo(
                ArticleType.CHARACTER
        );

        /*
         * Revision #1 phải snapshot luôn nội dung
         * mà Admin nhập trong lần tạo đầu tiên.
         */
        assertThat(
                savedRevision.summary()
        ).isEqualTo(
                SUMMARY
        );

        assertThat(
                savedRevision.content()
        ).isEqualTo(
                CONTENT
        );

        assertThat(
                savedRevision.status()
        ).isEqualTo(
                ArticleStatus.DRAFT
        );

        assertThat(
                savedRevision.changeType()
        ).isEqualTo(
                RevisionChangeType.CREATE_DRAFT
        );

        /*
         * Command đã truyền EDIT_SUMMARY,
         * nên không còn mong mặc định
         * "Tạo bản nháp đầu tiên".
         */
        assertThat(
                savedRevision.editSummary()
        ).isEqualTo(
                EDIT_SUMMARY
        );

        assertThat(
                savedRevision.editedBy()
        ).isEqualTo(
                ADMIN_ID
        );

        assertThat(
                savedRevision.createdAt()
        ).isEqualTo(
                NOW
        );
    }
}