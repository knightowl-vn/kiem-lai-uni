package com.universe.wiki.domain.revision;

import com.universe.wiki.domain.article.ArticleStatus;
import com.universe.wiki.domain.article.ArticleType;
import com.universe.wiki.domain.article.Slug;
import com.universe.wiki.domain.article.WikiArticle;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WikiArticleRevisionTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID REVISION_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final Instant NOW =
            Instant.parse(
                    "2026-08-06T07:00:00Z"
            );

    @Test
    @DisplayName(
            "Tạo revision đầu tiên từ bài viết mới"
    )
    void shouldCreateInitialRevisionSnapshot() {
        WikiArticle article =
                createDraftArticle();

        WikiArticleRevision revision =
                WikiArticleRevision.createSnapshot(
                        REVISION_ID,
                        article,
                        RevisionChangeType.CREATE_DRAFT,
                        "Tạo bản nháp đầu tiên"
                );

        assertThat(revision.id())
                .isEqualTo(REVISION_ID);

        assertThat(revision.articleId())
                .isEqualTo(ARTICLE_ID);

        assertThat(revision.revisionNumber())
                .isEqualTo(1L);
        
        assertThat(
                revision.contentVersion()
        ).isEqualTo(
                1L
        );

        assertThat(revision.title())
                .isEqualTo("Trần Bình An");

        assertThat(revision.slug().value())
                .isEqualTo("tran-binh-an");

        assertThat(revision.articleType())
                .isEqualTo(
                        ArticleType.CHARACTER
                );

        assertThat(revision.status())
                .isEqualTo(
                        ArticleStatus.DRAFT
                );

        assertThat(revision.changeType())
                .isEqualTo(
                        RevisionChangeType.CREATE_DRAFT
                );

        assertThat(revision.editSummary())
                .isEqualTo(
                        "Tạo bản nháp đầu tiên"
                );

        assertThat(revision.editedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(revision.createdAt())
                .isEqualTo(NOW);
    }

    @Test
    @DisplayName(
            "Revision number sử dụng aggregate version hiện tại"
    )
    void shouldUseCurrentAggregateVersionAsRevisionNumber() {
        WikiArticle article =
                createDraftArticle();

        article.updateDraft(
                "Trần Bình An",
                new Slug(
                        "tran-binh-an"
                ),
                ArticleType.CHARACTER,
                "Nhân vật chính.",
                "Nội dung bài viết.",
                ADMIN_ID,
                NOW.plusSeconds(60)
        );

        WikiArticleRevision revision =
                WikiArticleRevision.createSnapshot(
                        REVISION_ID,
                        article,
                        RevisionChangeType.UPDATE_DRAFT,
                        "Bổ sung nội dung"
                );

        assertThat(article.getAggregateVersion())
                .isEqualTo(2L);
        
        assertThat(
                article.getContentVersion()
        ).isEqualTo(
                2L
        );

        assertThat(revision.revisionNumber())
                .isEqualTo(2L);
        
        assertThat(
                revision.contentVersion()
        ).isEqualTo(
                2L
        );

        assertThat(revision.summary())
                .isEqualTo(
                        "Nhân vật chính."
                );

        assertThat(revision.content())
                .isEqualTo(
                        "Nội dung bài viết."
                );
    }

    @Test
    @DisplayName(
            "Chuẩn hóa mô tả chỉnh sửa rỗng thành null"
    )
    void shouldNormalizeBlankEditSummaryToNull() {
        WikiArticleRevision revision =
                WikiArticleRevision.createSnapshot(
                        REVISION_ID,
                        createDraftArticle(),
                        RevisionChangeType.CREATE_DRAFT,
                        "   "
                );

        assertThat(revision.editSummary())
                .isNull();
    }

    @Test
    @DisplayName(
            "Từ chối mô tả chỉnh sửa dài hơn 500 ký tự"
    )
    void shouldRejectTooLongEditSummary() {
        String tooLongEditSummary =
                "a".repeat(501);

        assertThatThrownBy(() ->
                WikiArticleRevision.createSnapshot(
                        REVISION_ID,
                        createDraftArticle(),
                        RevisionChangeType.CREATE_DRAFT,
                        tooLongEditSummary
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Mô tả chỉnh sửa không được vượt quá 500 ký tự."
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
                ADMIN_ID,
                NOW
        );
    }
    
    @Test
    @DisplayName(
            "Revision lifecycle tăng revision number nhưng giữ content version"
    )
    void shouldKeepContentVersionForLifecycleRevision() {
        WikiArticle article =
                WikiArticle.createDraft(
                        ARTICLE_ID,
                        "Trần Bình An",
                        new Slug(
                                "tran-binh-an"
                        ),
                        ArticleType.CHARACTER,
                        "",
                        "Nội dung đầy đủ.",
                        ADMIN_ID,
                        NOW
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

        article.publish(
                ADMIN_ID,
                NOW.plusSeconds(60)
        );

        WikiArticleRevision revision =
                WikiArticleRevision.createSnapshot(
                        REVISION_ID,
                        article,
                        RevisionChangeType.PUBLISH,
                        "Xuất bản"
                );

        /*
         * Publish là mutation thứ 2.
         */
        assertThat(
                revision.revisionNumber()
        ).isEqualTo(
                2L
        );

        /*
         * Nhưng nội dung vẫn là phiên bản đầu tiên.
         */
        assertThat(
                revision.contentVersion()
        ).isEqualTo(
                1L
        );
    }
}