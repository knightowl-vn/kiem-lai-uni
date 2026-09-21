package com.universe.interaction.entry.admin;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.exceptions.InteractionReportNotFoundException;
import com.universe.interaction.application.query.GetInteractionReportDetailUseCase;
import com.universe.interaction.application.query.InteractionReportDetailResult;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.entry.admin.dto.AdminCommentReportDetailDTO;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCommentReportDetailCoordinatorTest {

    @Mock
    private GetInteractionReportDetailUseCase getReportDetailUseCase;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private ChapterListQueryPort chapterListQueryPort;

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

    @Captor
    private ArgumentCaptor<Set<UUID>> userIdsCaptor;

    private AdminCommentReportDetailCoordinator coordinator;

    private final UUID reportId = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final UUID commentId = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private final UUID reporterId = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private final UUID authorId = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private final UUID resolverId = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private final UUID chapterId = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private final UUID articleId = UUID.fromString("77777777-7777-7777-7777-777777777777");
    private final Instant baseTime = Instant.parse("2026-09-21T10:00:00Z");

    @BeforeEach
    void setUp() {
        coordinator = new AdminCommentReportDetailCoordinator(
                getReportDetailUseCase,
                userIdentityContract,
                chapterListQueryPort,
                wikiArticleQueryPort
        );
    }

    @Test
    @DisplayName("Case A: ACTIVE Novel report - reporter and author resolved, Novel target resolved, zero Wiki calls")
    void shouldComposeActiveNovelReportDetail() {
        InteractionReportDetailResult raw = new InteractionReportDetailResult(
                reportId,
                commentId,
                reporterId,
                ReportReason.SPAM,
                "Báo cáo spam chương",
                "Nội dung quảng cáo vi phạm",
                ReportStatus.PENDING,
                baseTime,
                null,
                null,
                true,
                authorId,
                CommentStatus.ACTIVE,
                "Nội dung quảng cáo vi phạm (live)",
                CommentTargetType.NOVEL_CHAPTER,
                chapterId,
                null,
                baseTime.minusSeconds(600),
                baseTime.minusSeconds(600),
                null
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);

        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of(
                reporterId, new UserPublicProfileDTO(reporterId, "Alice Reporter", "https://img/alice.png"),
                authorId, new UserPublicProfileDTO(authorId, "Bob Author", "https://img/bob.png")
        ));

        ChapterListItemDTO chapterDTO = new ChapterListItemDTO(
                chapterId,
                100,
                "Chương 100: Đại Đạo Triều Thiên",
                "tap-1/chuong-100",
                "PUBLISHED",
                Instant.now()
        );
        when(chapterListQueryPort.findListItemsByIds(Set.of(chapterId))).thenReturn(Map.of(chapterId, chapterDTO));

        AdminCommentReportDetailDTO detail = coordinator.getDetail(reportId);

        assertThat(detail).isNotNull();
        assertThat(detail.reportId()).isEqualTo(reportId);
        assertThat(detail.commentId()).isEqualTo(commentId);
        assertThat(detail.reportedBodySnapshot()).isEqualTo("Nội dung quảng cáo vi phạm");
        assertThat(detail.currentCommentBody()).isEqualTo("Nội dung quảng cáo vi phạm (live)");

        // Reporter
        assertThat(detail.reporter()).isNotNull();
        assertThat(detail.reporter().userId()).isEqualTo(reporterId);
        assertThat(detail.reporter().displayName()).isEqualTo("Alice Reporter");
        assertThat(detail.reporter().avatarUrl()).isEqualTo("https://img/alice.png");
        assertThat(detail.reporter().resolved()).isTrue();

        // Author
        assertThat(detail.commentAuthor()).isNotNull();
        assertThat(detail.commentAuthor().userId()).isEqualTo(authorId);
        assertThat(detail.commentAuthor().displayName()).isEqualTo("Bob Author");
        assertThat(detail.commentAuthor().resolved()).isTrue();

        // Resolver is null for PENDING
        assertThat(detail.resolver()).isNull();
        assertThat(detail.resolvedByUserId()).isNull();
        assertThat(detail.resolvedAt()).isNull();

        // Target: Novel chapter
        assertThat(detail.target()).isNotNull();
        assertThat(detail.target().targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
        assertThat(detail.target().targetId()).isEqualTo(chapterId);
        assertThat(detail.target().title()).isEqualTo("Chương 100: Đại Đạo Triều Thiên");
        assertThat(detail.target().chapterNumber()).isEqualTo(100);
        assertThat(detail.target().resolved()).isTrue();

        // Query Budget Verification
        verify(userIdentityContract, times(1)).findPublicProfilesByIds(userIdsCaptor.capture());
        assertThat(userIdsCaptor.getValue()).containsExactlyInAnyOrder(reporterId, authorId);
        verify(chapterListQueryPort, times(1)).findListItemsByIds(Set.of(chapterId));
        verifyNoInteractions(wikiArticleQueryPort);
    }

    @Test
    @DisplayName("Case B: ACTIVE Wiki report - Wiki target resolved, zero Novel calls")
    void shouldComposeActiveWikiReportDetail() {
        InteractionReportDetailResult raw = new InteractionReportDetailResult(
                reportId,
                commentId,
                reporterId,
                ReportReason.HARASSMENT,
                null,
                "Bình luận công kích nhân vật",
                ReportStatus.PENDING,
                baseTime,
                null,
                null,
                true,
                authorId,
                CommentStatus.ACTIVE,
                "Bình luận công kích nhân vật",
                CommentTargetType.WIKI_ARTICLE,
                articleId,
                null,
                baseTime.minusSeconds(300),
                baseTime.minusSeconds(300),
                null
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);

        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of(
                reporterId, new UserPublicProfileDTO(reporterId, "Charlie Reporter", null),
                authorId, new UserPublicProfileDTO(authorId, "Dave Author", null)
        ));

        WikiArticleListItemDTO articleDTO = new WikiArticleListItemDTO(
                articleId,
                "Trần Bình An",
                "tran-binh-an",
                "CHARACTER",
                "PUBLISHED",
                UUID.randomUUID(),
                Instant.now(),
                Instant.now(),
                1L
        );
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(articleId))).thenReturn(Map.of(articleId, articleDTO));

        AdminCommentReportDetailDTO detail = coordinator.getDetail(reportId);

        assertThat(detail).isNotNull();
        assertThat(detail.target()).isNotNull();
        assertThat(detail.target().targetType()).isEqualTo(CommentTargetType.WIKI_ARTICLE);
        assertThat(detail.target().targetId()).isEqualTo(articleId);
        assertThat(detail.target().title()).isEqualTo("Trần Bình An");
        assertThat(detail.target().articleType()).isEqualTo("CHARACTER");
        assertThat(detail.target().resolved()).isTrue();

        verify(wikiArticleQueryPort, times(1)).findListItemsByIds(Set.of(articleId));
        verifyNoInteractions(chapterListQueryPort);
    }

    @Test
    @DisplayName("Case C: RESOLVED report - reporter, author, resolver all batched together, resolver composed")
    void shouldComposeResolvedReportDetailWithResolver() {
        Instant resolvedAt = baseTime.plusSeconds(3600);
        InteractionReportDetailResult raw = new InteractionReportDetailResult(
                reportId,
                commentId,
                reporterId,
                ReportReason.SPOILER,
                "Tiết lộ tình tiết cuối truyện",
                "Snapshot spoiler",
                ReportStatus.RESOLVED_ACTION_TAKEN,
                baseTime,
                resolverId,
                resolvedAt,
                true,
                authorId,
                CommentStatus.DELETED,
                null,
                CommentTargetType.NOVEL_CHAPTER,
                chapterId,
                null,
                baseTime.minusSeconds(1000),
                baseTime.plusSeconds(1800),
                baseTime.plusSeconds(1800)
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);

        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of(
                reporterId, new UserPublicProfileDTO(reporterId, "Reporter R", null),
                authorId, new UserPublicProfileDTO(authorId, "Author A", null),
                resolverId, new UserPublicProfileDTO(resolverId, "Admin Mod", "https://img/mod.png")
        ));

        when(chapterListQueryPort.findListItemsByIds(Set.of(chapterId))).thenReturn(Map.of());

        AdminCommentReportDetailDTO detail = coordinator.getDetail(reportId);

        assertThat(detail).isNotNull();
        assertThat(detail.status()).isEqualTo(ReportStatus.RESOLVED_ACTION_TAKEN);
        assertThat(detail.resolvedByUserId()).isEqualTo(resolverId);
        assertThat(detail.resolvedAt()).isEqualTo(resolvedAt);

        // Resolver composed correctly
        assertThat(detail.resolver()).isNotNull();
        assertThat(detail.resolver().userId()).isEqualTo(resolverId);
        assertThat(detail.resolver().displayName()).isEqualTo("Admin Mod");
        assertThat(detail.resolver().avatarUrl()).isEqualTo("https://img/mod.png");
        assertThat(detail.resolver().resolved()).isTrue();

        // Exactly one batch query containing all 3 unique IDs
        verify(userIdentityContract, times(1)).findPublicProfilesByIds(userIdsCaptor.capture());
        assertThat(userIdsCaptor.getValue()).containsExactlyInAnyOrder(reporterId, authorId, resolverId);
    }

    @Test
    @DisplayName("Case D: Missing Identity profiles - safe unresolved fallbacks preserving scalar user IDs")
    void shouldHandleMissingIdentityProfilesGracefully() {
        InteractionReportDetailResult raw = new InteractionReportDetailResult(
                reportId,
                commentId,
                reporterId,
                ReportReason.OTHER,
                "Lý do khác",
                "Snapshot evidence",
                ReportStatus.RESOLVED_NO_ACTION,
                baseTime,
                resolverId,
                baseTime.plusSeconds(60),
                true,
                authorId,
                CommentStatus.ACTIVE,
                "Live body",
                CommentTargetType.WIKI_ARTICLE,
                articleId,
                null,
                baseTime.minusSeconds(100),
                baseTime.minusSeconds(100),
                null
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);
        // Identity returns empty map (users not found/deleted)
        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of());
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(articleId))).thenReturn(Map.of());

        AdminCommentReportDetailDTO detail = coordinator.getDetail(reportId);

        assertThat(detail).isNotNull();
        // Reporter unresolved fallback
        assertThat(detail.reporter()).isNotNull();
        assertThat(detail.reporter().userId()).isEqualTo(reporterId);
        assertThat(detail.reporter().displayName()).isNull();
        assertThat(detail.reporter().resolved()).isFalse();

        // Author unresolved fallback
        assertThat(detail.commentAuthor()).isNotNull();
        assertThat(detail.commentAuthor().userId()).isEqualTo(authorId);
        assertThat(detail.commentAuthor().displayName()).isNull();
        assertThat(detail.commentAuthor().resolved()).isFalse();

        // Resolver unresolved fallback
        assertThat(detail.resolver()).isNotNull();
        assertThat(detail.resolver().userId()).isEqualTo(resolverId);
        assertThat(detail.resolver().displayName()).isNull();
        assertThat(detail.resolver().resolved()).isFalse();
    }

    @Test
    @DisplayName("Case E: Missing target metadata - unresolved target preserving targetType and targetId")
    void shouldHandleMissingTargetMetadataGracefully() {
        InteractionReportDetailResult raw = new InteractionReportDetailResult(
                reportId,
                commentId,
                reporterId,
                ReportReason.SPAM,
                null,
                "Snapshot",
                ReportStatus.PENDING,
                baseTime,
                null,
                null,
                true,
                authorId,
                CommentStatus.ACTIVE,
                "Live body",
                CommentTargetType.NOVEL_CHAPTER,
                chapterId,
                null,
                baseTime.minusSeconds(60),
                baseTime.minusSeconds(60),
                null
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);
        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of());
        // Target not found in chapter query
        when(chapterListQueryPort.findListItemsByIds(Set.of(chapterId))).thenReturn(Map.of());

        AdminCommentReportDetailDTO detail = coordinator.getDetail(reportId);

        assertThat(detail).isNotNull();
        assertThat(detail.target()).isNotNull();
        assertThat(detail.target().targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
        assertThat(detail.target().targetId()).isEqualTo(chapterId);
        assertThat(detail.target().resolved()).isFalse();
        assertThat(detail.target().title()).isNull();
    }

    @Test
    @DisplayName("Case F: Missing current comment - report returned, author and target null, zero target queries")
    void shouldHandleMissingCurrentCommentGracefully() {
        InteractionReportDetailResult raw = new InteractionReportDetailResult(
                reportId,
                commentId,
                reporterId,
                ReportReason.SPAM,
                "Spam description",
                "Snapshot historical evidence",
                ReportStatus.PENDING,
                baseTime,
                null,
                null,
                false, // Missing comment
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
        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of(
                reporterId, new UserPublicProfileDTO(reporterId, "Alice Reporter", null)
        ));

        AdminCommentReportDetailDTO detail = coordinator.getDetail(reportId);

        assertThat(detail).isNotNull();
        assertThat(detail.reportId()).isEqualTo(reportId);
        assertThat(detail.reportedBodySnapshot()).isEqualTo("Snapshot historical evidence");
        assertThat(detail.currentCommentAvailable()).isFalse();
        assertThat(detail.commentAuthor()).isNull();
        assertThat(detail.target()).isNull();

        // Identity query only requests reporter ID
        verify(userIdentityContract, times(1)).findPublicProfilesByIds(userIdsCaptor.capture());
        assertThat(userIdsCaptor.getValue()).containsExactly(reporterId);

        // Neither Novel nor Wiki query is called
        verifyNoInteractions(chapterListQueryPort);
        verifyNoInteractions(wikiArticleQueryPort);
    }

    @Test
    @DisplayName("Case G: DELETED comment tombstone - current body null, author and target composed from tombstone")
    void shouldComposeDetailForDeletedCommentTombstone() {
        Instant deletedAt = baseTime.plusSeconds(200);
        InteractionReportDetailResult raw = new InteractionReportDetailResult(
                reportId,
                commentId,
                reporterId,
                ReportReason.HARASSMENT,
                "Quấy rối",
                "Snapshot evidence captured before deletion",
                ReportStatus.PENDING,
                baseTime,
                null,
                null,
                true,
                authorId,
                CommentStatus.DELETED,
                null, // null body for DELETED tombstone
                CommentTargetType.WIKI_ARTICLE,
                articleId,
                null,
                baseTime.minusSeconds(500),
                deletedAt,
                deletedAt
        );
        when(getReportDetailUseCase.execute(reportId)).thenReturn(raw);
        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of(
                reporterId, new UserPublicProfileDTO(reporterId, "Reporter User", null),
                authorId, new UserPublicProfileDTO(authorId, "Deleted Comment Author", null)
        ));
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(articleId))).thenReturn(Map.of(
                articleId, new WikiArticleListItemDTO(articleId, "Tiêu Đề Bài", "tieu-de-bai", "LORE", "PUBLISHED", UUID.randomUUID(), Instant.now(), Instant.now(), 1L)
        ));

        AdminCommentReportDetailDTO detail = coordinator.getDetail(reportId);

        assertThat(detail).isNotNull();
        assertThat(detail.currentCommentAvailable()).isTrue();
        assertThat(detail.currentCommentStatus()).isEqualTo(CommentStatus.DELETED);
        assertThat(detail.currentCommentBody()).isNull();
        assertThat(detail.commentDeletedAt()).isEqualTo(deletedAt);
        assertThat(detail.reportedBodySnapshot()).isEqualTo("Snapshot evidence captured before deletion");

        assertThat(detail.commentAuthor()).isNotNull();
        assertThat(detail.commentAuthor().displayName()).isEqualTo("Deleted Comment Author");

        assertThat(detail.target()).isNotNull();
        assertThat(detail.target().title()).isEqualTo("Tiêu Đề Bài");
    }

    @Test
    @DisplayName("Case H: Raw use case throws InteractionReportNotFoundException - propagates without cross-context calls")
    void shouldPropagateNotFoundExceptionWithoutCrossContextCalls() {
        when(getReportDetailUseCase.execute(reportId))
                .thenThrow(new InteractionReportNotFoundException(reportId));

        assertThrows(InteractionReportNotFoundException.class, () -> coordinator.getDetail(reportId));

        verifyNoInteractions(userIdentityContract);
        verifyNoInteractions(chapterListQueryPort);
        verifyNoInteractions(wikiArticleQueryPort);
    }
}
