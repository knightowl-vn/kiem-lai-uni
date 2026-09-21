package com.universe.interaction.entry.admin;

import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.query.GetInteractionReportDetailUseCase;
import com.universe.interaction.application.query.InteractionReportDetailResult;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.entry.admin.dto.AdminCommentReportContextNavigationDTO;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCommentReportContextNavigationCoordinatorTest {

    @Mock
    private GetInteractionReportDetailUseCase getReportDetailUseCase;

    @Mock
    private ChapterListQueryPort chapterListQueryPort;

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

    private AdminCommentReportContextNavigationCoordinator coordinator;

    private final UUID reportId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID rootCommentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID replyCommentId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID reporterId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private final UUID authorId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final UUID targetId = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private final Instant baseTime = Instant.parse("2026-09-21T10:00:00Z");

    @BeforeEach
    void setUp() {
        coordinator = new AdminCommentReportContextNavigationCoordinator(
                getReportDetailUseCase,
                chapterListQueryPort,
                wikiArticleQueryPort
        );
    }

    @Nested
    @DisplayName("Constructor and Validation Tests")
    class ConstructorValidationTests {

        @Test
        @DisplayName("Enforces non-null dependencies")
        void shouldEnforceNonNullDependencies() {
            assertThatThrownBy(() -> new AdminCommentReportContextNavigationCoordinator(null, chapterListQueryPort, wikiArticleQueryPort))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("GetInteractionReportDetailUseCase cannot be null");

            assertThatThrownBy(() -> new AdminCommentReportContextNavigationCoordinator(getReportDetailUseCase, null, wikiArticleQueryPort))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("ChapterListQueryPort cannot be null");

            assertThatThrownBy(() -> new AdminCommentReportContextNavigationCoordinator(getReportDetailUseCase, chapterListQueryPort, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("WikiArticleQueryPort cannot be null");
        }

        @Test
        @DisplayName("Enforces non-null reportId in resolveNavigation")
        void shouldEnforceNonNullReportId() {
            assertThatThrownBy(() -> coordinator.resolveNavigation(null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("reportId cannot be null");
        }

        @Test
        @DisplayName("Propagates InteractionReportNotFoundException directly when report does not exist")
        void shouldPropagateNotFoundException() {
            when(getReportDetailUseCase.execute(reportId)).thenThrow(new InteractionReportNotFoundException(reportId));

            assertThatThrownBy(() -> coordinator.resolveNavigation(reportId))
                    .isInstanceOf(InteractionReportNotFoundException.class)
                    .hasMessageContaining(reportId.toString());

            verify(chapterListQueryPort, never()).findListItemsByIds(any());
            verify(wikiArticleQueryPort, never()).findListItemsByIds(any());
        }
    }

    @Test
    @DisplayName("Case A: Novel root comment - threadId equals commentId, available = true")
    void caseA_shouldResolveNovelRootCommentNavigation() {
        InteractionReportDetailResult raw = createRawResult(
                rootCommentId,
                null, // root comment: threadRootCommentId is null
                CommentTargetType.NOVEL_CHAPTER,
                targetId,
                true,
                CommentStatus.ACTIVE
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);

        ChapterListItemDTO chapter = new ChapterListItemDTO(
                targetId,
                1,
                "Chương 1: Khởi đầu",
                "quyen-1-chuong-1",
                "PUBLISHED",
                baseTime
        );
        when(chapterListQueryPort.findListItemsByIds(Set.of(targetId))).thenReturn(Map.of(targetId, chapter));

        AdminCommentReportContextNavigationDTO nav = coordinator.resolveNavigation(reportId);

        assertThat(nav.available()).isTrue();
        assertThat(nav.reportId()).isEqualTo(reportId);
        assertThat(nav.commentId()).isEqualTo(rootCommentId);
        assertThat(nav.threadId()).isEqualTo(rootCommentId);
        assertThat(nav.targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
        assertThat(nav.slug()).isEqualTo("quyen-1-chuong-1");
        assertThat(nav.articleType()).isNull();

        verify(chapterListQueryPort, times(1)).findListItemsByIds(Set.of(targetId));
        verify(wikiArticleQueryPort, never()).findListItemsByIds(any());
    }

    @Test
    @DisplayName("Case B: Novel reply comment - threadId is root UUID, available = true")
    void caseB_shouldResolveNovelReplyCommentNavigation() {
        InteractionReportDetailResult raw = createRawResult(
                replyCommentId,
                rootCommentId, // reply comment: threadRootCommentId is rootCommentId
                CommentTargetType.NOVEL_CHAPTER,
                targetId,
                true,
                CommentStatus.ACTIVE
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);

        ChapterListItemDTO chapter = new ChapterListItemDTO(
                targetId,
                5,
                "Chương 5: Bước ngoặt",
                "quyen-1-chuong-5",
                "PUBLISHED",
                baseTime
        );
        when(chapterListQueryPort.findListItemsByIds(Set.of(targetId))).thenReturn(Map.of(targetId, chapter));

        AdminCommentReportContextNavigationDTO nav = coordinator.resolveNavigation(reportId);

        assertThat(nav.available()).isTrue();
        assertThat(nav.reportId()).isEqualTo(reportId);
        assertThat(nav.commentId()).isEqualTo(replyCommentId);
        assertThat(nav.threadId()).isEqualTo(rootCommentId);
        assertThat(nav.targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
        assertThat(nav.slug()).isEqualTo("quyen-1-chuong-5");
        assertThat(nav.articleType()).isNull();
    }

    @Test
    @DisplayName("Case C: Wiki root comment - threadId equals commentId, raw structural articleType returned, available = true")
    void caseC_shouldResolveWikiRootCommentNavigation() {
        InteractionReportDetailResult raw = createRawResult(
                rootCommentId,
                null,
                CommentTargetType.WIKI_ARTICLE,
                targetId,
                true,
                CommentStatus.ACTIVE
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);

        WikiArticleListItemDTO article = new WikiArticleListItemDTO(
                targetId,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "PUBLISHED",
                authorId,
                baseTime,
                baseTime,
                1L
        );
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(targetId))).thenReturn(Map.of(targetId, article));

        AdminCommentReportContextNavigationDTO nav = coordinator.resolveNavigation(reportId);

        assertThat(nav.available()).isTrue();
        assertThat(nav.reportId()).isEqualTo(reportId);
        assertThat(nav.commentId()).isEqualTo(rootCommentId);
        assertThat(nav.threadId()).isEqualTo(rootCommentId);
        assertThat(nav.targetType()).isEqualTo(CommentTargetType.WIKI_ARTICLE);
        assertThat(nav.slug()).isEqualTo("tran-binh-an");
        assertThat(nav.articleType()).isEqualTo("CHARACTER"); // raw structural type

        verify(wikiArticleQueryPort, times(1)).findListItemsByIds(Set.of(targetId));
        verify(chapterListQueryPort, never()).findListItemsByIds(any());
    }

    @Test
    @DisplayName("Case D: Wiki reply comment - threadId is root UUID, raw structural articleType returned, available = true")
    void caseD_shouldResolveWikiReplyCommentNavigation() {
        InteractionReportDetailResult raw = createRawResult(
                replyCommentId,
                rootCommentId,
                CommentTargetType.WIKI_ARTICLE,
                targetId,
                true,
                CommentStatus.ACTIVE
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);

        WikiArticleListItemDTO article = new WikiArticleListItemDTO(
                targetId,
                "Ly Châu Thành",
                "ly-chau-thanh",
                "LOCATION",
                "PUBLISHED",
                authorId,
                baseTime,
                baseTime,
                1L
        );
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(targetId))).thenReturn(Map.of(targetId, article));

        AdminCommentReportContextNavigationDTO nav = coordinator.resolveNavigation(reportId);

        assertThat(nav.available()).isTrue();
        assertThat(nav.reportId()).isEqualTo(reportId);
        assertThat(nav.commentId()).isEqualTo(replyCommentId);
        assertThat(nav.threadId()).isEqualTo(rootCommentId);
        assertThat(nav.targetType()).isEqualTo(CommentTargetType.WIKI_ARTICLE);
        assertThat(nav.slug()).isEqualTo("ly-chau-thanh");
        assertThat(nav.articleType()).isEqualTo("LOCATION"); // raw structural type
    }

    @Test
    @DisplayName("Case E: Missing comment - available = false, zero target port queries")
    void caseE_shouldReturnUnavailableWhenCommentMissing() {
        InteractionReportDetailResult raw = new InteractionReportDetailResult(
                reportId,
                rootCommentId,
                reporterId,
                ReportReason.SPAM,
                "Spam report",
                "Snapshot",
                ReportStatus.PENDING,
                baseTime,
                null,
                null,
                false, // comment is unavailable / deleted from store
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);

        AdminCommentReportContextNavigationDTO nav = coordinator.resolveNavigation(reportId);

        assertThat(nav.available()).isFalse();
        assertThat(nav.reportId()).isEqualTo(reportId);
        assertThat(nav.commentId()).isNull();

        verify(chapterListQueryPort, never()).findListItemsByIds(any());
        verify(wikiArticleQueryPort, never()).findListItemsByIds(any());
    }

    @Test
    @DisplayName("Case F: Missing target - query port returns empty map, available = false")
    void caseF_shouldReturnUnavailableWhenTargetNotFound() {
        InteractionReportDetailResult raw = createRawResult(
                rootCommentId,
                null,
                CommentTargetType.NOVEL_CHAPTER,
                targetId,
                true,
                CommentStatus.ACTIVE
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);
        when(chapterListQueryPort.findListItemsByIds(Set.of(targetId))).thenReturn(Map.of());

        AdminCommentReportContextNavigationDTO nav = coordinator.resolveNavigation(reportId);

        assertThat(nav.available()).isFalse();
        assertThat(nav.reportId()).isEqualTo(reportId);
    }

    @Test
    @DisplayName("Case G: Unpublished target - status DRAFT or ARCHIVED, available = false")
    void caseG_shouldReturnUnavailableWhenTargetNotPublished() {
        // Novel DRAFT
        InteractionReportDetailResult rawNovel = createRawResult(
                rootCommentId,
                null,
                CommentTargetType.NOVEL_CHAPTER,
                targetId,
                true,
                CommentStatus.ACTIVE
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(rawNovel);

        ChapterListItemDTO draftChapter = new ChapterListItemDTO(
                targetId,
                1,
                "Draft Chapter",
                "draft-chapter",
                "DRAFT",
                baseTime
        );
        when(chapterListQueryPort.findListItemsByIds(Set.of(targetId))).thenReturn(Map.of(targetId, draftChapter));

        AdminCommentReportContextNavigationDTO navNovel = coordinator.resolveNavigation(reportId);
        assertThat(navNovel.available()).isFalse();

        // Wiki ARCHIVED
        InteractionReportDetailResult rawWiki = createRawResult(
                rootCommentId,
                null,
                CommentTargetType.WIKI_ARTICLE,
                targetId,
                true,
                CommentStatus.ACTIVE
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(rawWiki);

        WikiArticleListItemDTO archivedArticle = new WikiArticleListItemDTO(
                targetId,
                "Archived Article",
                "archived-article",
                "CHARACTER",
                "ARCHIVED",
                authorId,
                baseTime,
                baseTime,
                1L
        );
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(targetId))).thenReturn(Map.of(targetId, archivedArticle));

        AdminCommentReportContextNavigationDTO navWiki = coordinator.resolveNavigation(reportId);
        assertThat(navWiki.available()).isFalse();
    }

    @Test
    @DisplayName("Case H: Deleted comment - soft-deleted tombstone remains navigable when target is published")
    void caseH_shouldRemainNavigableWhenCommentStatusIsDeleted() {
        InteractionReportDetailResult raw = createRawResult(
                rootCommentId,
                null,
                CommentTargetType.NOVEL_CHAPTER,
                targetId,
                true,
                CommentStatus.DELETED // soft-deleted tombstone
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);

        ChapterListItemDTO chapter = new ChapterListItemDTO(
                targetId,
                1,
                "Chương 1",
                "quyen-1-chuong-1",
                "PUBLISHED",
                baseTime
        );
        when(chapterListQueryPort.findListItemsByIds(Set.of(targetId))).thenReturn(Map.of(targetId, chapter));

        AdminCommentReportContextNavigationDTO nav = coordinator.resolveNavigation(reportId);

        assertThat(nav.available()).isTrue();
        assertThat(nav.reportId()).isEqualTo(reportId);
        assertThat(nav.commentId()).isEqualTo(rootCommentId);
        assertThat(nav.slug()).isEqualTo("quyen-1-chuong-1");
    }

    @Test
    @DisplayName("Case I: Unsupported target, missing articleType, or blank slug - available = false")
    void caseI_shouldReturnUnavailableForUnsupportedOrInvalidMetadata() {
        // Sub-case 1: Target type is null
        InteractionReportDetailResult rawNullTarget = createRawResult(
                rootCommentId,
                null,
                null,
                targetId,
                true,
                CommentStatus.ACTIVE
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(rawNullTarget);

        assertThat(coordinator.resolveNavigation(reportId).available()).isFalse();

        // Sub-case 2: Null articleType for Wiki
        InteractionReportDetailResult rawWikiNullType = createRawResult(
                rootCommentId,
                null,
                CommentTargetType.WIKI_ARTICLE,
                targetId,
                true,
                CommentStatus.ACTIVE
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(rawWikiNullType);

        WikiArticleListItemDTO nullTypeArticle = new WikiArticleListItemDTO(
                targetId,
                "No Type",
                "no-type",
                null,
                "PUBLISHED",
                authorId,
                baseTime,
                baseTime,
                1L
        );
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(targetId))).thenReturn(Map.of(targetId, nullTypeArticle));

        assertThat(coordinator.resolveNavigation(reportId).available()).isFalse();

        // Sub-case 3: Blank articleType for Wiki
        WikiArticleListItemDTO blankTypeArticle = new WikiArticleListItemDTO(
                targetId,
                "Blank Type",
                "blank-type",
                "   ",
                "PUBLISHED",
                authorId,
                baseTime,
                baseTime,
                1L
        );
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(targetId))).thenReturn(Map.of(targetId, blankTypeArticle));

        assertThat(coordinator.resolveNavigation(reportId).available()).isFalse();

        // Sub-case 4: Blank slug
        ChapterListItemDTO blankSlugChapter = new ChapterListItemDTO(
                targetId,
                1,
                "Blank Slug",
                "   ",
                "PUBLISHED",
                baseTime
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(createRawResult(
                rootCommentId,
                null,
                CommentTargetType.NOVEL_CHAPTER,
                targetId,
                true,
                CommentStatus.ACTIVE
        ));
        when(chapterListQueryPort.findListItemsByIds(Set.of(targetId))).thenReturn(Map.of(targetId, blankSlugChapter));

        assertThat(coordinator.resolveNavigation(reportId).available()).isFalse();
    }

    private InteractionReportDetailResult createRawResult(
            UUID commentId,
            UUID rootThreadCommentId,
            CommentTargetType targetType,
            UUID targetId,
            boolean commentAvailable,
            CommentStatus commentStatus
    ) {
        return new InteractionReportDetailResult(
                reportId,
                commentId,
                reporterId,
                ReportReason.SPAM,
                "Test description",
                "Reported evidence snapshot",
                ReportStatus.PENDING,
                baseTime,
                null,
                null,
                commentAvailable,
                authorId,
                commentStatus,
                "Live comment body text",
                targetType,
                targetId,
                rootThreadCommentId,
                baseTime.minusSeconds(100),
                baseTime.minusSeconds(50),
                null
        );
    }

    @SuppressWarnings("unchecked")
    private static <T> Set<T> any() {
        return org.mockito.ArgumentMatchers.any();
    }
}
