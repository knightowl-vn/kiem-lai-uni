package com.universe.interaction.entry.admin;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.interaction.application.ports.InteractionReportQueueQueryPort;
import com.universe.interaction.application.query.InteractionReportQueueFilter;
import com.universe.interaction.application.query.InteractionReportQueueItem;
import com.universe.interaction.application.query.InteractionReportQueuePage;
import com.universe.interaction.application.query.InteractionReportQueueSort;
import com.universe.interaction.application.query.ReportQueueLifecycleScope;
import com.universe.interaction.domain.CommentStatus;
import com.universe.interaction.domain.CommentTargetType;
import com.universe.interaction.domain.report.ReportModerationAction;
import com.universe.interaction.domain.report.ReportReason;
import com.universe.interaction.domain.report.ReportStatus;
import com.universe.interaction.entry.admin.dto.AdminCommentReportQueueItemDTO;
import com.universe.interaction.entry.admin.dto.AdminCommentReportQueuePageDTO;
import com.universe.novel.application.ports.ChapterListQueryPort;
import com.universe.novel.contracts.dto.ChapterListItemDTO;
import com.universe.wiki.application.ports.WikiArticleQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleListItemDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminCommentReportQueueCoordinatorTest {

    @Mock
    private InteractionReportQueueQueryPort queueQueryPort;

    @Mock
    private UserIdentityContract userIdentityContract;

    @Mock
    private ChapterListQueryPort chapterListQueryPort;

    @Mock
    private WikiArticleQueryPort wikiArticleQueryPort;

    private AdminCommentReportQueueCoordinator coordinator;

    private final InteractionReportQueueFilter defaultFilter = new InteractionReportQueueFilter(
            ReportQueueLifecycleScope.PENDING,
            null,
            null,
            InteractionReportQueueSort.OLDEST,
            0,
            20
    );

    @BeforeEach
    void setUp() {
        coordinator = new AdminCommentReportQueueCoordinator(
                queueQueryPort,
                userIdentityContract,
                chapterListQueryPort,
                wikiArticleQueryPort
        );
    }

    @Test
    @DisplayName("Constructor enforces non-null dependencies")
    void constructorEnforcesDependencies() {
        assertThatThrownBy(() -> new AdminCommentReportQueueCoordinator(null, userIdentityContract, chapterListQueryPort, wikiArticleQueryPort))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminCommentReportQueueCoordinator(queueQueryPort, null, chapterListQueryPort, wikiArticleQueryPort))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminCommentReportQueueCoordinator(queueQueryPort, userIdentityContract, null, wikiArticleQueryPort))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new AdminCommentReportQueueCoordinator(queueQueryPort, userIdentityContract, chapterListQueryPort, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("getReportQueue throws when filter is null")
    void shouldThrowWhenFilterIsNull() {
        assertThatThrownBy(() -> coordinator.getReportQueue(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("InteractionReportQueueFilter cannot be null");
    }

    @Test
    @DisplayName("Case A: Mixed queue correctly enriches both Novel and Wiki targets, and batches unique Identity lookups")
    void shouldEnrichMixedQueueWithNovelWikiAndIdentity() {
        UUID reporter1 = UUID.randomUUID();
        UUID reporter2 = UUID.randomUUID();
        UUID author1 = UUID.randomUUID();
        UUID author2 = reporter1; // Same as reporter1 to test deduplication
        UUID chapterId = UUID.randomUUID();
        UUID articleId = UUID.randomUUID();

        InteractionReportQueueItem item1 = createRawItem(
                UUID.randomUUID(), UUID.randomUUID(), reporter1, author1,
                CommentTargetType.NOVEL_CHAPTER, chapterId,
                ReportReason.SPAM, ReportStatus.PENDING, CommentStatus.ACTIVE
        );
        InteractionReportQueueItem item2 = createRawItem(
                UUID.randomUUID(), UUID.randomUUID(), reporter2, author2,
                CommentTargetType.WIKI_ARTICLE, articleId,
                ReportReason.HARASSMENT, ReportStatus.PENDING, CommentStatus.ACTIVE
        );

        when(queueQueryPort.findQueueReports(defaultFilter))
                .thenReturn(new InteractionReportQueuePage(List.of(item1, item2), 0, 20, 2));

        // Deduplicated identity lookup: reporter1, reporter2, author1
        Set<UUID> expectedUserIds = Set.of(reporter1, reporter2, author1);
        when(userIdentityContract.findPublicProfilesByIds(expectedUserIds)).thenReturn(Map.of(
                reporter1, new UserPublicProfileDTO(reporter1, "Alice", "https://img/alice.png", "alice"),
                reporter2, new UserPublicProfileDTO(reporter2, "Bob", "https://img/bob.png", "bob"),
                author1, new UserPublicProfileDTO(author1, "Charlie", null, "charlie")
        ));

        when(chapterListQueryPort.findListItemsByIds(Set.of(chapterId))).thenReturn(Map.of(
                chapterId, new ChapterListItemDTO(chapterId, 12, "Hồi 12", "tap-1/chuong-12", "PUBLISHED", Instant.now())
        ));

        when(wikiArticleQueryPort.findListItemsByIds(Set.of(articleId))).thenReturn(Map.of(
                articleId, new WikiArticleListItemDTO(articleId, "Hàn Lập", "han-lap", "CHARACTER", "DRAFT", UUID.randomUUID(), Instant.now(), Instant.now(), 1L)
        ));

        AdminCommentReportQueuePageDTO result = coordinator.getReportQueue(defaultFilter);

        assertThat(result.items()).hasSize(2);

        // Item 1 verification (Novel chapter comment report)
        AdminCommentReportQueueItemDTO result1 = result.items().get(0);
        assertThat(result1.reportId()).isEqualTo(item1.reportId());
        assertThat(result1.reporter().resolved()).isTrue();
        assertThat(result1.reporter().displayName()).isEqualTo("Alice");
        assertThat(result1.reporter().avatarUrl()).isEqualTo("https://img/alice.png");
        assertThat(result1.commentAuthor().resolved()).isTrue();
        assertThat(result1.commentAuthor().displayName()).isEqualTo("Charlie");
        assertThat(result1.target().targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
        assertThat(result1.target().targetId()).isEqualTo(chapterId);
        assertThat(result1.target().resolved()).isTrue();
        assertThat(result1.target().title()).isEqualTo("Hồi 12");
        assertThat(result1.target().slug()).isEqualTo("tap-1/chuong-12");
        assertThat(result1.target().chapterNumber()).isEqualTo(12);
        assertThat(result1.target().currentStatus()).isEqualTo("PUBLISHED");
        assertThat(result1.target().articleType()).isNull();

        // Item 2 verification (Wiki article comment report)
        AdminCommentReportQueueItemDTO result2 = result.items().get(1);
        assertThat(result2.reportId()).isEqualTo(item2.reportId());
        assertThat(result2.reporter().resolved()).isTrue();
        assertThat(result2.reporter().displayName()).isEqualTo("Bob");
        assertThat(result2.commentAuthor().resolved()).isTrue();
        assertThat(result2.commentAuthor().displayName()).isEqualTo("Alice"); // Reused profile
        assertThat(result2.target().targetType()).isEqualTo(CommentTargetType.WIKI_ARTICLE);
        assertThat(result2.target().targetId()).isEqualTo(articleId);
        assertThat(result2.target().resolved()).isTrue();
        assertThat(result2.target().title()).isEqualTo("Hàn Lập");
        assertThat(result2.target().slug()).isEqualTo("han-lap");
        assertThat(result2.target().currentStatus()).isEqualTo("DRAFT");
        assertThat(result2.target().articleType()).isEqualTo("CHARACTER");
        assertThat(result2.target().chapterNumber()).isNull();

        verify(chapterListQueryPort).findListItemsByIds(Set.of(chapterId));
        verify(wikiArticleQueryPort).findListItemsByIds(Set.of(articleId));
        verify(userIdentityContract).findPublicProfilesByIds(expectedUserIds);
    }

    @Test
    @DisplayName("Case B: Pure Novel queue never calls WikiArticleQueryPort")
    void shouldAvoidWikiCallWhenQueueHasOnlyNovelReports() {
        UUID chapterId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        InteractionReportQueueItem item = createRawItem(
                UUID.randomUUID(), UUID.randomUUID(), reporterId, authorId,
                CommentTargetType.NOVEL_CHAPTER, chapterId,
                ReportReason.SPAM, ReportStatus.PENDING, CommentStatus.ACTIVE
        );

        when(queueQueryPort.findQueueReports(defaultFilter))
                .thenReturn(new InteractionReportQueuePage(List.of(item), 0, 20, 1));
        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of());
        when(chapterListQueryPort.findListItemsByIds(Set.of(chapterId))).thenReturn(Map.of());

        coordinator.getReportQueue(defaultFilter);

        verify(chapterListQueryPort).findListItemsByIds(Set.of(chapterId));
        verify(wikiArticleQueryPort, never()).findListItemsByIds(any());
    }

    @Test
    @DisplayName("Case C: Pure Wiki queue never calls ChapterListQueryPort")
    void shouldAvoidNovelCallWhenQueueHasOnlyWikiReports() {
        UUID articleId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        InteractionReportQueueItem item = createRawItem(
                UUID.randomUUID(), UUID.randomUUID(), reporterId, authorId,
                CommentTargetType.WIKI_ARTICLE, articleId,
                ReportReason.SPOILER, ReportStatus.PENDING, CommentStatus.ACTIVE
        );

        when(queueQueryPort.findQueueReports(defaultFilter))
                .thenReturn(new InteractionReportQueuePage(List.of(item), 0, 20, 1));
        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of());
        when(wikiArticleQueryPort.findListItemsByIds(Set.of(articleId))).thenReturn(Map.of());

        coordinator.getReportQueue(defaultFilter);

        verify(wikiArticleQueryPort).findListItemsByIds(Set.of(articleId));
        verify(chapterListQueryPort, never()).findListItemsByIds(any());
    }

    @Test
    @DisplayName("Case D: Missing Identity profiles are handled gracefully with resolved = false")
    void shouldHandleMissingIdentityProfilesGracefully() {
        UUID chapterId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        InteractionReportQueueItem item = createRawItem(
                UUID.randomUUID(), UUID.randomUUID(), reporterId, authorId,
                CommentTargetType.NOVEL_CHAPTER, chapterId,
                ReportReason.HATE_SPEECH, ReportStatus.PENDING, CommentStatus.ACTIVE
        );

        when(queueQueryPort.findQueueReports(defaultFilter))
                .thenReturn(new InteractionReportQueuePage(List.of(item), 0, 20, 1));
        // Identity returns empty map (user not found/deleted)
        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of());
        when(chapterListQueryPort.findListItemsByIds(any())).thenReturn(Map.of(
                chapterId, new ChapterListItemDTO(chapterId, 1, "Chương 1", "tap-1/chuong-1", "PUBLISHED", Instant.now())
        ));

        AdminCommentReportQueuePageDTO result = coordinator.getReportQueue(defaultFilter);

        assertThat(result.items()).hasSize(1);
        AdminCommentReportQueueItemDTO dto = result.items().get(0);

        assertThat(dto.reporter().userId()).isEqualTo(reporterId);
        assertThat(dto.reporter().resolved()).isFalse();
        assertThat(dto.reporter().displayName()).isNull();
        assertThat(dto.reporter().avatarUrl()).isNull();

        assertThat(dto.commentAuthor().userId()).isEqualTo(authorId);
        assertThat(dto.commentAuthor().resolved()).isFalse();
        assertThat(dto.commentAuthor().displayName()).isNull();
        assertThat(dto.commentAuthor().avatarUrl()).isNull();

        // Target remains resolved
        assertThat(dto.target().resolved()).isTrue();
        assertThat(dto.target().title()).isEqualTo("Chương 1");
    }

    @Test
    @DisplayName("Case E: Missing Target metadata is handled gracefully with resolved = false")
    void shouldHandleMissingTargetMetadataGracefully() {
        UUID missingChapterId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();

        InteractionReportQueueItem item = createRawItem(
                UUID.randomUUID(), UUID.randomUUID(), reporterId, authorId,
                CommentTargetType.NOVEL_CHAPTER, missingChapterId,
                ReportReason.SEXUAL_OR_OBSCENE, ReportStatus.PENDING, CommentStatus.DELETED
        );

        when(queueQueryPort.findQueueReports(defaultFilter))
                .thenReturn(new InteractionReportQueuePage(List.of(item), 0, 20, 1));
        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of(
                reporterId, new UserPublicProfileDTO(reporterId, "User A", null, "user_a"),
                authorId, new UserPublicProfileDTO(authorId, "User B", null, "user_b")
        ));
        // Novel returns empty map (chapter deleted/missing)
        when(chapterListQueryPort.findListItemsByIds(Set.of(missingChapterId))).thenReturn(Map.of());

        AdminCommentReportQueuePageDTO result = coordinator.getReportQueue(defaultFilter);

        assertThat(result.items()).hasSize(1);
        AdminCommentReportQueueItemDTO dto = result.items().get(0);

        assertThat(dto.target().targetType()).isEqualTo(CommentTargetType.NOVEL_CHAPTER);
        assertThat(dto.target().targetId()).isEqualTo(missingChapterId);
        assertThat(dto.target().resolved()).isFalse();
        assertThat(dto.target().title()).isNull();
        assertThat(dto.target().slug()).isNull();
        assertThat(dto.target().currentStatus()).isNull();
        assertThat(dto.target().chapterNumber()).isNull();
        assertThat(dto.target().articleType()).isNull();

        // Preserves comment status
        assertThat(dto.commentStatus()).isEqualTo(CommentStatus.DELETED);
    }

    @Test
    @DisplayName("Case F: Empty raw queue short-circuits with ZERO calls to foreign ports")
    void shouldReturnEmptyPageWithoutCallingForeignPortsWhenRawQueueIsEmpty() {
        when(queueQueryPort.findQueueReports(defaultFilter))
                .thenReturn(new InteractionReportQueuePage(List.of(), 2, 10, 0));

        AdminCommentReportQueuePageDTO result = coordinator.getReportQueue(defaultFilter);

        assertThat(result.items()).isEmpty();
        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(0L);
        assertThat(result.totalPages()).isEqualTo(0);
        assertThat(result.hasNext()).isFalse();
        assertThat(result.hasPrevious()).isFalse();

        verifyNoInteractions(userIdentityContract, chapterListQueryPort, wikiArticleQueryPort);
    }

    @Test
    @DisplayName("Case G: Preserves pagination metadata exactly matching raw page")
    void shouldPreservePaginationMetadataMatchingRawPage() {
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();

        InteractionReportQueueItem item = createRawItem(
                UUID.randomUUID(), UUID.randomUUID(), reporterId, authorId,
                CommentTargetType.NOVEL_CHAPTER, chapterId,
                ReportReason.OTHER, ReportStatus.RESOLVED_ACTION_TAKEN, CommentStatus.ACTIVE
        );

        // Page 2 of size 10 with 45 total elements -> totalPages = 5, hasNext = true, hasPrevious = true
        when(queueQueryPort.findQueueReports(defaultFilter))
                .thenReturn(new InteractionReportQueuePage(List.of(item), 2, 10, 45));
        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of());
        when(chapterListQueryPort.findListItemsByIds(any())).thenReturn(Map.of());

        AdminCommentReportQueuePageDTO result = coordinator.getReportQueue(defaultFilter);

        assertThat(result.page()).isEqualTo(2);
        assertThat(result.size()).isEqualTo(10);
        assertThat(result.totalElements()).isEqualTo(45L);
        assertThat(result.totalPages()).isEqualTo(5);
        assertThat(result.hasNext()).isTrue();
        assertThat(result.hasPrevious()).isTrue();
    }

    @Test
    @DisplayName("Case H: Preserves exact queue ordering as returned by raw queue")
    void shouldPreserveExactQueueOrdering() {
        UUID repId1 = UUID.randomUUID();
        UUID repId2 = UUID.randomUUID();
        UUID repId3 = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID user = UUID.randomUUID();

        InteractionReportQueueItem item1 = createRawItem(
                repId1, UUID.randomUUID(), user, user,
                CommentTargetType.NOVEL_CHAPTER, chapterId,
                ReportReason.SPAM, ReportStatus.PENDING, CommentStatus.ACTIVE
        );
        InteractionReportQueueItem item2 = createRawItem(
                repId2, UUID.randomUUID(), user, user,
                CommentTargetType.NOVEL_CHAPTER, chapterId,
                ReportReason.HARASSMENT, ReportStatus.PENDING, CommentStatus.ACTIVE
        );
        InteractionReportQueueItem item3 = createRawItem(
                repId3, UUID.randomUUID(), user, user,
                CommentTargetType.NOVEL_CHAPTER, chapterId,
                ReportReason.OTHER, ReportStatus.RESOLVED_ACTION_TAKEN, CommentStatus.ACTIVE
        );

        when(queueQueryPort.findQueueReports(defaultFilter))
                .thenReturn(new InteractionReportQueuePage(List.of(item1, item2, item3), 0, 10, 3));
        when(userIdentityContract.findPublicProfilesByIds(any())).thenReturn(Map.of());
        when(chapterListQueryPort.findListItemsByIds(any())).thenReturn(Map.of());

        AdminCommentReportQueuePageDTO result = coordinator.getReportQueue(defaultFilter);

        assertThat(result.items()).hasSize(3);
        assertThat(result.items().get(0).reportId()).isEqualTo(repId1);
        assertThat(result.items().get(1).reportId()).isEqualTo(repId2);
        assertThat(result.items().get(2).reportId()).isEqualTo(repId3);
    }

    @Test
    @DisplayName("Case I: Processed queue items include resolver in batch Identity lookup and map resolver DTO")
    void shouldBatchLookupResolverIdentityForProcessedQueueItems() {
        UUID reportId = UUID.randomUUID();
        UUID commentId = UUID.randomUUID();
        UUID reporterId = UUID.randomUUID();
        UUID authorId = UUID.randomUUID();
        UUID resolverId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        Instant resolvedAt = Instant.parse("2026-09-20T11:00:00Z");

        InteractionReportQueueItem processedItem = new InteractionReportQueueItem(
                reportId,
                commentId,
                reporterId,
                ReportReason.SPAM,
                "Spam report",
                "Spam comment body",
                ReportStatus.RESOLVED_ACTION_TAKEN,
                Instant.parse("2026-09-20T10:00:00Z"),
                authorId,
                CommentTargetType.NOVEL_CHAPTER,
                chapterId,
                CommentStatus.DELETED,
                ReportModerationAction.DELETE_COMMENT,
                resolverId,
                resolvedAt
        );

        InteractionReportQueueFilter processedFilter = new InteractionReportQueueFilter(
                ReportQueueLifecycleScope.PROCESSED,
                null,
                null,
                InteractionReportQueueSort.OLDEST,
                0,
                20
        );

        when(queueQueryPort.findQueueReports(processedFilter))
                .thenReturn(new InteractionReportQueuePage(List.of(processedItem), 0, 20, 1));

        Set<UUID> expectedUserIds = Set.of(reporterId, authorId, resolverId);
        when(userIdentityContract.findPublicProfilesByIds(expectedUserIds)).thenReturn(Map.of(
                reporterId, new UserPublicProfileDTO(reporterId, "Reporter", null, "reporter"),
                authorId, new UserPublicProfileDTO(authorId, "Author", null, "author"),
                resolverId, new UserPublicProfileDTO(resolverId, "Moderator Bob", "https://img/bob.png", "moderator_bob")
        ));
        when(chapterListQueryPort.findListItemsByIds(Set.of(chapterId))).thenReturn(Map.of(
                chapterId, new ChapterListItemDTO(chapterId, 1, "Chương 1", "tap-1/chuong-1", "PUBLISHED", Instant.now())
        ));

        AdminCommentReportQueuePageDTO result = coordinator.getReportQueue(processedFilter);

        assertThat(result.items()).hasSize(1);
        AdminCommentReportQueueItemDTO dto = result.items().get(0);

        assertThat(dto.moderationAction()).isEqualTo(ReportModerationAction.DELETE_COMMENT);
        assertThat(dto.resolvedAt()).isEqualTo(resolvedAt);
        assertThat(dto.resolver()).isNotNull();
        assertThat(dto.resolver().userId()).isEqualTo(resolverId);
        assertThat(dto.resolver().displayName()).isEqualTo("Moderator Bob");
        assertThat(dto.resolver().avatarUrl()).isEqualTo("https://img/bob.png");
        assertThat(dto.resolver().resolved()).isTrue();

        verify(userIdentityContract).findPublicProfilesByIds(expectedUserIds);
    }

    private InteractionReportQueueItem createRawItem(
            UUID reportId,
            UUID commentId,
            UUID reporterId,
            UUID authorId,
            CommentTargetType targetType,
            UUID targetId,
            ReportReason reason,
            ReportStatus reportStatus,
            CommentStatus commentStatus
    ) {
        ReportModerationAction action = null;
        UUID resolverId = null;
        Instant resolvedAt = null;
        if (reportStatus == ReportStatus.RESOLVED_ACTION_TAKEN) {
            action = ReportModerationAction.DELETE_COMMENT;
            resolverId = UUID.randomUUID();
            resolvedAt = Instant.parse("2026-09-20T11:00:00Z");
        } else if (reportStatus == ReportStatus.RESOLVED_NO_ACTION) {
            action = ReportModerationAction.NO_ACTION;
            resolverId = UUID.randomUUID();
            resolvedAt = Instant.parse("2026-09-20T11:00:00Z");
        }

        return new InteractionReportQueueItem(
                reportId,
                commentId,
                reporterId,
                reason,
                "Report description for " + reportId,
                "Reported comment body snapshot",
                reportStatus,
                Instant.parse("2026-09-20T10:00:00Z"),
                authorId,
                targetType,
                targetId,
                commentStatus,
                action,
                resolverId,
                resolvedAt
        );
    }
}
