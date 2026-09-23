package com.universe.wiki.application.article.update.draft;

import com.universe.shared.id.IdGeneratorPort;
import com.universe.shared.time.ClockPort;
import com.universe.wiki.application.exceptions.ArticleSlugAlreadyExistsException;
import com.universe.wiki.application.exceptions.WikiArticleNotFoundException;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UpdateDraftWikiArticleUseCaseTest {

    private static final UUID ARTICLE_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111"
            );

    private static final UUID OTHER_ARTICLE_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222"
            );

    private static final UUID ADMIN_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333"
            );

    private static final UUID REVISION_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444"
            );

    private static final Instant CREATED_AT =
            Instant.parse(
                    "2026-08-06T07:00:00Z"
            );

    private static final Instant UPDATED_AT =
            Instant.parse(
                    "2026-08-06T08:00:00Z"
            );

    private static final Slug UPDATED_SLUG =
            new Slug(
                    "tran-binh-an-cap-nhat"
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

    private UpdateDraftWikiArticleUseCase
            updateDraftUseCase;

    @BeforeEach
    void setUp() {
        updateDraftUseCase =
                new UpdateDraftWikiArticleUseCase(
                        articleRepositoryPort,
                        revisionRepositoryPort,
                        slugGeneratorPort,
                        idGeneratorPort,
                        clockPort
                );
    }

    @Test
    @DisplayName(
            "Cập nhật bản nháp và lưu revision UPDATE_DRAFT"
    )
    void shouldUpdateDraftAndSaveRevision() {
        WikiArticle article =
                createDraftArticle(
                        ARTICLE_ID,
                        "Trần Bình An",
                        "tran-binh-an"
                );

        UpdateDraftWikiArticleCommand command =
                createCommand();

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(article)
        );

        when(
                slugGeneratorPort.generate(
                        "Trần Bình An cập nhật"
                )
        ).thenReturn(
                UPDATED_SLUG
        );

        when(
                articleRepositoryPort
                        .findByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                UPDATED_SLUG
                        )
        ).thenReturn(
                Optional.empty()
        );

        when(
                clockPort.now()
        ).thenReturn(
                UPDATED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        WikiArticleDTO result =
                updateDraftUseCase.execute(
                        command
                );

        assertThat(article.getTitle())
                .isEqualTo(
                        "Trần Bình An cập nhật"
                );

        assertThat(article.getSlug())
                .isEqualTo(
                        UPDATED_SLUG
                );

        assertThat(article.getSummary())
                .isEqualTo(
                        "Tóm tắt mới."
                );

        assertThat(article.getContent())
                .isEqualTo(
                        "Nội dung mới."
                );

        assertThat(article.getStatus())
                .isEqualTo(
                        ArticleStatus.DRAFT
                );

        assertThat(article.getUpdatedBy())
                .isEqualTo(
                        ADMIN_ID
                );

        assertThat(article.getUpdatedAt())
                .isEqualTo(
                        UPDATED_AT
                );

        assertThat(article.getAggregateVersion())
                .isEqualTo(2L);

        verify(articleRepositoryPort)
                .save(article);

        ArgumentCaptor<WikiArticleRevision>
                revisionCaptor =
                ArgumentCaptor.forClass(
                        WikiArticleRevision.class
                );

        verify(revisionRepositoryPort)
                .save(
                        revisionCaptor.capture()
                );

        WikiArticleRevision revision =
                revisionCaptor.getValue();

        assertThat(revision.id())
                .isEqualTo(REVISION_ID);

        assertThat(revision.articleId())
                .isEqualTo(ARTICLE_ID);

        assertThat(revision.revisionNumber())
                .isEqualTo(2L);

        assertThat(revision.changeType())
                .isEqualTo(
                        RevisionChangeType.UPDATE_DRAFT
                );

        assertThat(revision.editSummary())
                .isEqualTo(
                        "Bổ sung nội dung bản nháp"
                );

        assertThat(revision.editedBy())
                .isEqualTo(ADMIN_ID);

        assertThat(revision.createdAt())
                .isEqualTo(UPDATED_AT);

        assertThat(result.aggregateVersion())
                .isEqualTo(2L);

        assertThat(result.status())
                .isEqualTo("DRAFT");
    }

    @Test
    @DisplayName(
            "Từ chối cập nhật khi không tìm thấy bài viết"
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
                updateDraftUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        WikiArticleNotFoundException.class
                )
                .hasMessage(
                        "Không tìm thấy bài viết Wiki: "
                                + ARTICLE_ID
                );

        verify(
                articleRepositoryPort,
                never()
        ).save(any());

        verify(
                revisionRepositoryPort,
                never()
        ).save(any());
    }

    @Test
    @DisplayName(
            "Từ chối slug đang thuộc bài viết khác"
    )
    void shouldRejectSlugOwnedByAnotherArticle() {
        WikiArticle currentArticle =
                createDraftArticle(
                        ARTICLE_ID,
                        "Trần Bình An",
                        "tran-binh-an"
                );

        WikiArticle anotherArticle =
                createDraftArticle(
                        OTHER_ARTICLE_ID,
                        "Bài khác",
                        "tran-binh-an-cap-nhat"
                );

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(currentArticle)
        );

        when(
                slugGeneratorPort.generate(
                        "Trần Bình An cập nhật"
                )
        ).thenReturn(
                UPDATED_SLUG
        );

        when(
                articleRepositoryPort
                        .findByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                UPDATED_SLUG
                        )
        ).thenReturn(
                Optional.of(anotherArticle)
        );

        assertThatThrownBy(() ->
                updateDraftUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        ArticleSlugAlreadyExistsException.class
                )
                .hasMessage(
                        "Slug bài viết đã tồn tại trong loại "
                                + "CHARACTER: "
                                + "tran-binh-an-cap-nhat"
                );

        verify(
                articleRepositoryPort,
                never()
        ).save(any());

        verify(
                revisionRepositoryPort,
                never()
        ).save(any());
    }

    @Test
    @DisplayName(
            "Cho phép giữ slug đang thuộc chính bài hiện tại"
    )
    void shouldAllowSlugOwnedByCurrentArticle() {
        WikiArticle article =
                createDraftArticle(
                        ARTICLE_ID,
                        "Trần Bình An",
                        "tran-binh-an-cap-nhat"
                );

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(article)
        );

        when(
                slugGeneratorPort.generate(
                        "Trần Bình An cập nhật"
                )
        ).thenReturn(
                UPDATED_SLUG
        );

        when(
                articleRepositoryPort
                        .findByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                UPDATED_SLUG
                        )
        ).thenReturn(
                Optional.of(article)
        );

        when(
                clockPort.now()
        ).thenReturn(
                UPDATED_AT
        );

        when(
                idGeneratorPort.generate()
        ).thenReturn(
                REVISION_ID
        );

        updateDraftUseCase.execute(
                createCommand()
        );

        verify(articleRepositoryPort)
                .save(article);

        verify(revisionRepositoryPort)
                .save(
                        any(WikiArticleRevision.class)
                );
    }

    @Test
    @DisplayName(
            "Không cho updateDraft đối với bài đã xuất bản"
    )
    void shouldRejectUpdatingPublishedArticleAsDraft() {
        WikiArticle article =
                createPublishedArticle();

        when(
                articleRepositoryPort.findById(
                        ARTICLE_ID
                )
        ).thenReturn(
                Optional.of(article)
        );

        when(
                slugGeneratorPort.generate(
                        "Trần Bình An cập nhật"
                )
        ).thenReturn(
                UPDATED_SLUG
        );

        when(
                articleRepositoryPort
                        .findByArticleTypeAndSlug(
                                ArticleType.CHARACTER,
                                UPDATED_SLUG
                        )
        ).thenReturn(
                Optional.empty()
        );

        when(
                clockPort.now()
        ).thenReturn(
                UPDATED_AT
        );

        assertThatThrownBy(() ->
                updateDraftUseCase.execute(
                        createCommand()
                )
        )
                .isInstanceOf(
                        IllegalStateException.class
                )
                .hasMessageContaining(
                        "bản nháp"
                );

        verify(
                revisionRepositoryPort,
                never()
        ).save(any());
    }

    /*
     * =====================================================
     * COVER TRI-STATE SEMANTICS
     * =====================================================
     */

    @Test
    @DisplayName("Tri-state: Legacy update command (without cover info) preserves existing cover")
    void shouldPreserveExistingCoverWhenLegacyCommandUsed() {
        UUID existingCoverId = UUID.randomUUID();
        WikiArticle article = WikiArticle.createDraft(
                ARTICLE_ID, "Trần Bình An", new Slug("tran-binh-an"),
                ArticleType.CHARACTER, ADMIN_ID, CREATED_AT, existingCoverId
        );
        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(slugGeneratorPort.generate("Trần Bình An")).thenReturn(new Slug("tran-binh-an"));
        when(clockPort.now()).thenReturn(UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        // Legacy 7-arg constructor (updateCover = false)
        UpdateDraftWikiArticleCommand legacyCommand = new UpdateDraftWikiArticleCommand(
                ARTICLE_ID, "Trần Bình An", ArticleType.CHARACTER,
                "Tóm tắt mới", "Nội dung mới", "Cập nhật không chỉnh bìa", ADMIN_ID
        );

        WikiArticleDTO result = updateDraftUseCase.execute(legacyCommand);

        assertThat(result.coverMediaAssetId()).isEqualTo(existingCoverId);
        assertThat(article.getCoverMediaAssetId()).isEqualTo(existingCoverId);
    }

    @Test
    @DisplayName("Tri-state: Explicit same UUID preserves cover without fake cover mutation")
    void shouldPreserveCoverWithoutMutationWhenSameUuidProvided() {
        UUID existingCoverId = UUID.randomUUID();
        WikiArticle article = WikiArticle.createDraft(
                ARTICLE_ID, "Trần Bình An", new Slug("tran-binh-an"),
                ArticleType.CHARACTER, ADMIN_ID, CREATED_AT, existingCoverId
        );
        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(slugGeneratorPort.generate("Trần Bình An")).thenReturn(new Slug("tran-binh-an"));
        when(clockPort.now()).thenReturn(UPDATED_AT);

        long aggBefore = article.getAggregateVersion();
        long contentBefore = article.getContentVersion();

        // 9-arg constructor with same UUID and updateCover = true, and identical text
        UpdateDraftWikiArticleCommand sameCommand = new UpdateDraftWikiArticleCommand(
                ARTICLE_ID, "Trần Bình An", ArticleType.CHARACTER,
                "", "", "Không thay đổi gì", ADMIN_ID, existingCoverId, true
        );

        WikiArticleDTO result = updateDraftUseCase.execute(sameCommand);

        assertThat(result.coverMediaAssetId()).isEqualTo(existingCoverId);
        assertThat(article.getAggregateVersion()).isEqualTo(aggBefore);
        assertThat(article.getContentVersion()).isEqualTo(contentBefore);
        verify(articleRepositoryPort, never()).save(any());
    }

    @Test
    @DisplayName("Tri-state: Explicit new UUID changes cover, increments aggregateVersion, keeps contentVersion")
    void shouldChangeCoverWhenNewUuidProvided() {
        UUID oldCoverId = UUID.randomUUID();
        UUID newCoverId = UUID.randomUUID();
        WikiArticle article = WikiArticle.createDraft(
                ARTICLE_ID, "Trần Bình An", new Slug("tran-binh-an"),
                ArticleType.CHARACTER, ADMIN_ID, CREATED_AT, oldCoverId
        );
        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(slugGeneratorPort.generate("Trần Bình An")).thenReturn(new Slug("tran-binh-an"));
        when(clockPort.now()).thenReturn(UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        long aggBefore = article.getAggregateVersion();
        long contentBefore = article.getContentVersion();

        // Identical content, but new cover UUID with updateCover = true
        UpdateDraftWikiArticleCommand updateCoverCmd = new UpdateDraftWikiArticleCommand(
                ARTICLE_ID, "Trần Bình An", ArticleType.CHARACTER,
                "", "", "Đổi ảnh bìa", ADMIN_ID, newCoverId, true
        );

        WikiArticleDTO result = updateDraftUseCase.execute(updateCoverCmd);

        assertThat(result.coverMediaAssetId()).isEqualTo(newCoverId);
        assertThat(article.getCoverMediaAssetId()).isEqualTo(newCoverId);
        assertThat(article.getAggregateVersion()).isEqualTo(aggBefore + 1);
        assertThat(article.getContentVersion()).isEqualTo(contentBefore);
        verify(articleRepositoryPort).save(article);
    }

    @Test
    @DisplayName("Tri-state: Explicit null removes cover, increments aggregateVersion, keeps contentVersion")
    void shouldRemoveCoverWhenNullProvidedWithUpdateFlagTrue() {
        UUID oldCoverId = UUID.randomUUID();
        WikiArticle article = WikiArticle.createDraft(
                ARTICLE_ID, "Trần Bình An", new Slug("tran-binh-an"),
                ArticleType.CHARACTER, ADMIN_ID, CREATED_AT, oldCoverId
        );
        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(slugGeneratorPort.generate("Trần Bình An")).thenReturn(new Slug("tran-binh-an"));
        when(clockPort.now()).thenReturn(UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        long aggBefore = article.getAggregateVersion();
        long contentBefore = article.getContentVersion();

        // Identical content, explicit null with updateCover = true
        UpdateDraftWikiArticleCommand removeCoverCmd = new UpdateDraftWikiArticleCommand(
                ARTICLE_ID, "Trần Bình An", ArticleType.CHARACTER,
                "", "", "Gỡ ảnh bìa", ADMIN_ID, null, true
        );

        WikiArticleDTO result = updateDraftUseCase.execute(removeCoverCmd);

        assertThat(result.coverMediaAssetId()).isNull();
        assertThat(article.getCoverMediaAssetId()).isNull();
        assertThat(article.getAggregateVersion()).isEqualTo(aggBefore + 1);
        assertThat(article.getContentVersion()).isEqualTo(contentBefore);
        verify(articleRepositoryPort).save(article);
    }

    @Test
    @DisplayName("Tri-state for UpdateDraftAndPublish: legacy preserves, new changes, null removes")
    void shouldVerifyTriStateForUpdateDraftAndPublishUseCase() {
        UpdateDraftAndPublishWikiArticleUseCase updateDraftAndPublishUseCase =
                new UpdateDraftAndPublishWikiArticleUseCase(
                        articleRepositoryPort,
                        revisionRepositoryPort,
                        slugGeneratorPort,
                        idGeneratorPort,
                        clockPort
                );

        UUID existingCoverId = UUID.randomUUID();
        WikiArticle article = WikiArticle.createDraft(
                ARTICLE_ID, "Trần Bình An", new Slug("tran-binh-an"),
                ArticleType.CHARACTER, "Tóm tắt", "Nội dung hoàn chỉnh", existingCoverId, ADMIN_ID, CREATED_AT
        );

        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(article));
        when(slugGeneratorPort.generate("Trần Bình An")).thenReturn(new Slug("tran-binh-an"));
        when(clockPort.now()).thenReturn(UPDATED_AT);
        when(idGeneratorPort.generate()).thenReturn(REVISION_ID);

        // 1. Legacy command preserves
        UpdateDraftAndPublishWikiArticleCommand legacyCmd = new UpdateDraftAndPublishWikiArticleCommand(
                ARTICLE_ID, "Trần Bình An", ArticleType.CHARACTER,
                "Tóm tắt", "Nội dung hoàn chỉnh", "Chỉ xuất bản", ADMIN_ID
        );
        WikiArticleDTO dto1 = updateDraftAndPublishUseCase.execute(legacyCmd);
        assertThat(dto1.coverMediaAssetId()).isEqualTo(existingCoverId);

        // 2. Explicit new UUID changes cover
        UUID newCoverId = UUID.randomUUID();
        WikiArticle draftArticle2 = WikiArticle.createDraft(
                ARTICLE_ID, "Trần Bình An", new Slug("tran-binh-an"),
                ArticleType.CHARACTER, "Tóm tắt", "Nội dung hoàn chỉnh", existingCoverId, ADMIN_ID, CREATED_AT
        );
        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(draftArticle2));
        UpdateDraftAndPublishWikiArticleCommand changeCmd = new UpdateDraftAndPublishWikiArticleCommand(
                ARTICLE_ID, "Trần Bình An", ArticleType.CHARACTER,
                "Tóm tắt", "Nội dung hoàn chỉnh", "Đổi bìa và xuất bản", ADMIN_ID, newCoverId, true
        );
        WikiArticleDTO dto2 = updateDraftAndPublishUseCase.execute(changeCmd);
        assertThat(dto2.coverMediaAssetId()).isEqualTo(newCoverId);

        // 3. Explicit null removes cover
        WikiArticle draftArticle3 = WikiArticle.createDraft(
                ARTICLE_ID, "Trần Bình An", new Slug("tran-binh-an"),
                ArticleType.CHARACTER, "Tóm tắt", "Nội dung hoàn chỉnh", existingCoverId, ADMIN_ID, CREATED_AT
        );
        when(articleRepositoryPort.findById(ARTICLE_ID)).thenReturn(Optional.of(draftArticle3));
        UpdateDraftAndPublishWikiArticleCommand removeCmd = new UpdateDraftAndPublishWikiArticleCommand(
                ARTICLE_ID, "Trần Bình An", ArticleType.CHARACTER,
                "Tóm tắt", "Nội dung hoàn chỉnh", "Gỡ bìa và xuất bản", ADMIN_ID, null, true
        );
        WikiArticleDTO dto3 = updateDraftAndPublishUseCase.execute(removeCmd);
        assertThat(dto3.coverMediaAssetId()).isNull();
    }

    private UpdateDraftWikiArticleCommand
            createCommand() {

        return new UpdateDraftWikiArticleCommand(
                ARTICLE_ID,
                "Trần Bình An cập nhật",
                ArticleType.CHARACTER,
                "Tóm tắt mới.",
                "Nội dung mới.",
                "Bổ sung nội dung bản nháp",
                ADMIN_ID
        );
    }

    private WikiArticle createDraftArticle(
            UUID id,
            String title,
            String slug
    ) {
        return WikiArticle.createDraft(
                id,
                title,
                new Slug(slug),
                ArticleType.CHARACTER,
                ADMIN_ID,
                CREATED_AT
        );
    }

    private WikiArticle createPublishedArticle() {
        WikiArticle article =
                createDraftArticle(
                        ARTICLE_ID,
                        "Trần Bình An",
                        "tran-binh-an"
                );

        article.updateDraft(
                "Trần Bình An",
                new Slug("tran-binh-an"),
                ArticleType.CHARACTER,
                "Nhân vật chính.",
                "Nội dung hoàn chỉnh.",
                ADMIN_ID,
                CREATED_AT.plusSeconds(60)
        );

        article.publish(
                ADMIN_ID,
                CREATED_AT.plusSeconds(120)
        );

        return article;
    }
}