package com.universe.interaction.entry.novel;

import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.application.query.GetCommentThreadsByRootIdsUseCase;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.ChapterBlockDiscussionResponseDTO;
import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.identity.contracts.interfaces.UserIdentityContract;
import com.universe.novel.application.anchor.ChapterBlockDiscussionAnchorView;
import com.universe.novel.application.anchor.GetChapterBlockDiscussionAnchorsUseCase;
import com.universe.novel.application.exceptions.ReaderBlockNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("NovelBlockDiscussionQueryCoordinator Unit Tests")
class NovelBlockDiscussionQueryCoordinatorTest {

    @Mock
    private GetChapterBlockDiscussionAnchorsUseCase getChapterBlockDiscussionAnchorsUseCase;

    @Mock
    private GetCommentThreadsByRootIdsUseCase getCommentThreadsByRootIdsUseCase;

    @Mock
    private UserIdentityContract userIdentityContract;

    private NovelBlockDiscussionQueryCoordinator coordinator;

    private static final UUID CHAPTER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String BLOCK_KEY = "blk-test";
    private static final String CANONICAL_TEXT = "Paragraph text content.";
    private static final long CONTENT_VERSION = 2L;

    @BeforeEach
    void setUp() {
        coordinator = new NovelBlockDiscussionQueryCoordinator(
                getChapterBlockDiscussionAnchorsUseCase,
                getCommentThreadsByRootIdsUseCase,
                userIdentityContract
        );
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when chapterId is null")
    void shouldThrowWhenChapterIdIsNull() {
        assertThatThrownBy(() -> coordinator.getBlockDiscussion((UUID) null, BLOCK_KEY))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("chapterId cannot be null");

        verify(getChapterBlockDiscussionAnchorsUseCase, never()).execute(any(), any());
        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Should throw IllegalArgumentException when blockKey is null or blank")
    void shouldThrowWhenBlockKeyIsBlank() {
        assertThatThrownBy(() -> coordinator.getBlockDiscussion(CHAPTER_ID, (String) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockKey cannot be blank");

        assertThatThrownBy(() -> coordinator.getBlockDiscussion(CHAPTER_ID, "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blockKey cannot be blank");

        verify(getChapterBlockDiscussionAnchorsUseCase, never()).execute(any(), any());
        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Should return 0 threadCount and empty threads list without calling Interaction when no anchors resolved")
    void shouldReturnEmptyDiscussionWithoutCallingInteractionWhenNoAnchors() {
        ChapterBlockDiscussionAnchorView anchorView = new ChapterBlockDiscussionAnchorView(
                CHAPTER_ID,
                CONTENT_VERSION,
                BLOCK_KEY,
                CANONICAL_TEXT,
                Collections.emptyList()
        );

        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY))
                .thenReturn(anchorView);

        ChapterBlockDiscussionResponseDTO response = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY);

        assertThat(response).isNotNull();
        assertThat(response.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(response.contentVersion()).isEqualTo(CONTENT_VERSION);
        assertThat(response.blockKey()).isEqualTo(BLOCK_KEY);
        assertThat(response.canonicalText()).isEqualTo(CANONICAL_TEXT);
        assertThat(response.threadCount()).isZero();
        assertThat(response.commentCount()).isZero();
        assertThat(response.threads()).isEmpty();

        verify(getChapterBlockDiscussionAnchorsUseCase).execute(CHAPTER_ID, BLOCK_KEY);
        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Should coordinate Novel anchor view and Interaction threads when anchors exist")
    void shouldCoordinateNovelAndInteractionWhenAnchorsExist() {
        UUID rootId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID authorId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        ChapterBlockDiscussionAnchorView anchorView = new ChapterBlockDiscussionAnchorView(
                CHAPTER_ID,
                CONTENT_VERSION,
                BLOCK_KEY,
                CANONICAL_TEXT,
                List.of(rootId)
        );

        Comment rootComment = Comment.createRoot(rootId, CommentTarget.novelChapter(CHAPTER_ID), authorId, "Thread root", Instant.now());
        CommentThreadView threadView = new CommentThreadView(CommentReadItem.fromRoot(rootComment), Collections.emptyList());

        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY))
                .thenReturn(anchorView);
        when(getCommentThreadsByRootIdsUseCase.execute(CommentTarget.novelChapter(CHAPTER_ID), List.of(rootId)))
                .thenReturn(List.of(threadView));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(authorId)))
                .thenReturn(Map.of(authorId, new UserPublicProfileDTO(authorId, "Tác Giả", "https://img.com/avatar.png")));

        ChapterBlockDiscussionResponseDTO response = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY);

        assertThat(response).isNotNull();
        assertThat(response.chapterId()).isEqualTo(CHAPTER_ID);
        assertThat(response.contentVersion()).isEqualTo(CONTENT_VERSION);
        assertThat(response.blockKey()).isEqualTo(BLOCK_KEY);
        assertThat(response.canonicalText()).isEqualTo(CANONICAL_TEXT);
        assertThat(response.threadCount()).isEqualTo(1);
        assertThat(response.threads()).hasSize(1);
        assertThat(response.threads().get(0).root().id()).isEqualTo(rootId);
        assertThat(response.threads().get(0).root().author()).isNotNull();
        assertThat(response.threads().get(0).root().author().userId()).isEqualTo(authorId);
        assertThat(response.threads().get(0).root().author().displayName()).isEqualTo("Tác Giả");
        assertThat(response.threads().get(0).root().author().avatarUrl()).isEqualTo("https://img.com/avatar.png");

        verify(getChapterBlockDiscussionAnchorsUseCase).execute(CHAPTER_ID, BLOCK_KEY);
        verify(getCommentThreadsByRootIdsUseCase).execute(CommentTarget.novelChapter(CHAPTER_ID), List.of(rootId));
        verify(userIdentityContract).findPublicProfilesByIds(Set.of(authorId));
    }

    @Test
    @DisplayName("Should enrich roots and active replies while suppressing tombstone author and querying Identity in a single batch")
    void shouldEnrichThreadsAndActiveRepliesAndExcludeTombstonesFromIdentityLookup() {
        UUID rootId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID userRoot = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID userActiveReply = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID userTombstone = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        UUID activeReplyId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID tombstoneReplyId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        Comment rootComment = Comment.createRoot(rootId, CommentTarget.novelChapter(CHAPTER_ID), userRoot, "Root text", Instant.now());
        Comment activeReplyComment = Comment.createReply(activeReplyId, rootComment, userActiveReply, "Reply text", Instant.now());
        Comment tombstoneReplyComment = Comment.createReply(tombstoneReplyId, rootComment, userTombstone, "Deleted text", Instant.now());
        tombstoneReplyComment.delete(Instant.now());

        CommentReadItem rootItem = CommentReadItem.fromRoot(rootComment);
        CommentReadItem activeReplyItem = CommentReadItem.fromActiveReply(activeReplyComment, userRoot);
        CommentReadItem tombstoneReplyItem = CommentReadItem.fromTombstoneReply(tombstoneReplyComment, userRoot);

        CommentThreadView threadView = new CommentThreadView(rootItem, List.of(activeReplyItem, tombstoneReplyItem));

        ChapterBlockDiscussionAnchorView anchorView = new ChapterBlockDiscussionAnchorView(
                CHAPTER_ID, CONTENT_VERSION, BLOCK_KEY, CANONICAL_TEXT, List.of(rootId)
        );

        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY)).thenReturn(anchorView);
        when(getCommentThreadsByRootIdsUseCase.execute(CommentTarget.novelChapter(CHAPTER_ID), List.of(rootId)))
                .thenReturn(List.of(threadView));

        // Note: userTombstone must NOT be in the lookup query!
        when(userIdentityContract.findPublicProfilesByIds(Set.of(userRoot, userActiveReply)))
                .thenReturn(Map.of(
                        userRoot, new UserPublicProfileDTO(userRoot, "Root User", "https://img.com/root.jpg"),
                        userActiveReply, new UserPublicProfileDTO(userActiveReply, "Reply User", "https://img.com/reply.jpg")
                ));

        ChapterBlockDiscussionResponseDTO response = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY);

        assertThat(response.threads()).hasSize(1);
        assertThat(response.threadCount()).isEqualTo(1);
        assertThat(response.commentCount()).isEqualTo(2);
        var thread = response.threads().get(0);

        // Root author verified
        assertThat(thread.root().author()).isNotNull();
        assertThat(thread.root().author().displayName()).isEqualTo("Root User");
        assertThat(thread.root().author().avatarUrl()).isEqualTo("https://img.com/root.jpg");

        // Active reply author verified
        assertThat(thread.replies()).hasSize(2);
        var activeReplyDto = thread.replies().get(0);
        assertThat(activeReplyDto.author()).isNotNull();
        assertThat(activeReplyDto.author().displayName()).isEqualTo("Reply User");
        assertThat(activeReplyDto.author().avatarUrl()).isEqualTo("https://img.com/reply.jpg");

        // Tombstone reply author MUST be null to prevent identity re-introduction
        var tombstoneReplyDto = thread.replies().get(1);
        assertThat(tombstoneReplyDto.tombstone()).isTrue();
        assertThat(tombstoneReplyDto.author()).isNull();
        assertThat(tombstoneReplyDto.authorUserId()).isNull();
        assertThat(tombstoneReplyDto.replyToAuthorUserId()).isNull();
        assertThat(tombstoneReplyDto.canEdit()).isFalse();
        assertThat(tombstoneReplyDto.canDelete()).isFalse();
        assertThat(tombstoneReplyDto.id()).isEqualTo(tombstoneReplyId);
        assertThat(tombstoneReplyDto.parentCommentId()).isEqualTo(rootId);

        // Exactly one batch query to Identity, with only visible active user IDs
        verify(userIdentityContract).findPublicProfilesByIds(Set.of(userRoot, userActiveReply));
    }

    @Test
    @DisplayName("Should fallback to default author when Identity profile is missing (Case A)")
    void shouldFallbackToDefaultAuthorWhenIdentityProfileMissing() {
        UUID rootId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID authorId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        ChapterBlockDiscussionAnchorView anchorView = new ChapterBlockDiscussionAnchorView(
                CHAPTER_ID, CONTENT_VERSION, BLOCK_KEY, CANONICAL_TEXT, List.of(rootId)
        );

        Comment rootComment = Comment.createRoot(rootId, CommentTarget.novelChapter(CHAPTER_ID), authorId, "Thread root", Instant.now());
        CommentThreadView threadView = new CommentThreadView(CommentReadItem.fromRoot(rootComment), Collections.emptyList());

        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY)).thenReturn(anchorView);
        when(getCommentThreadsByRootIdsUseCase.execute(CommentTarget.novelChapter(CHAPTER_ID), List.of(rootId)))
                .thenReturn(List.of(threadView));
        // Case A: Identity profile not found
        when(userIdentityContract.findPublicProfilesByIds(Set.of(authorId))).thenReturn(Map.of());

        ChapterBlockDiscussionResponseDTO response = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY);

        assertThat(response.threads().get(0).root().author()).isNotNull();
        assertThat(response.threads().get(0).root().author().userId()).isEqualTo(authorId);
        assertThat(response.threads().get(0).root().author().displayName()).isEqualTo("Người dùng");
        assertThat(response.threads().get(0).root().author().avatarUrl()).isNull();
    }

    @Test
    @DisplayName("Should fallback to 'Người dùng' when Identity profile exists but displayName is blank or null (Case B)")
    void shouldFallbackToDefaultDisplayNameWhenIdentityProfileHasBlankOrNullDisplayName() {
        UUID rootId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID authorId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        ChapterBlockDiscussionAnchorView anchorView = new ChapterBlockDiscussionAnchorView(
                CHAPTER_ID, CONTENT_VERSION, BLOCK_KEY, CANONICAL_TEXT, List.of(rootId)
        );

        Comment rootComment = Comment.createRoot(rootId, CommentTarget.novelChapter(CHAPTER_ID), authorId, "Thread root", Instant.now());
        CommentThreadView threadView = new CommentThreadView(CommentReadItem.fromRoot(rootComment), Collections.emptyList());

        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY)).thenReturn(anchorView);
        when(getCommentThreadsByRootIdsUseCase.execute(CommentTarget.novelChapter(CHAPTER_ID), List.of(rootId)))
                .thenReturn(List.of(threadView));
        // Case B: Identity profile exists with blank displayName (raw from Identity)
        when(userIdentityContract.findPublicProfilesByIds(Set.of(authorId)))
                .thenReturn(Map.of(authorId, new UserPublicProfileDTO(authorId, "   ", "https://cdn.example.com/avatar.jpg")));

        ChapterBlockDiscussionResponseDTO response = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY);

        assertThat(response.threads().get(0).root().author()).isNotNull();
        assertThat(response.threads().get(0).root().author().userId()).isEqualTo(authorId);
        assertThat(response.threads().get(0).root().author().displayName()).isEqualTo("Người dùng");
        assertThat(response.threads().get(0).root().author().avatarUrl()).isEqualTo("https://cdn.example.com/avatar.jpg");
    }

    @Test
    @DisplayName("Should propagate ReaderBlockNotFoundException when Novel use case throws it")
    void shouldPropagateReaderBlockNotFoundException() {
        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY))
                .thenThrow(new ReaderBlockNotFoundException(CHAPTER_ID, BLOCK_KEY));

        assertThatThrownBy(() -> coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY))
                .isInstanceOf(ReaderBlockNotFoundException.class);

        verify(getCommentThreadsByRootIdsUseCase, never()).execute(any(), any());
    }

    @Test
    @DisplayName("Should compute canEdit capability accurately for owner root, owner reply, non-owner, guest, and tombstone")
    void shouldComputeCanEditCapabilityAccurately() {
        UUID rootId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID reply1Id = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID reply2Id = UUID.fromString("44444444-4444-4444-4444-444444444444");
        UUID replyTombstoneId = UUID.fromString("55555555-5555-5555-5555-555555555555");

        UUID ownerRootId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID ownerReply1Id = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID otherUserId = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        ChapterBlockDiscussionAnchorView anchorView = new ChapterBlockDiscussionAnchorView(
                CHAPTER_ID, CONTENT_VERSION, BLOCK_KEY, CANONICAL_TEXT, List.of(rootId)
        );

        Instant now = Instant.now();
        Comment rootComment = Comment.createRoot(rootId, CommentTarget.novelChapter(CHAPTER_ID), ownerRootId, "Root comment", now);
        Comment reply1Comment = Comment.createReply(reply1Id, rootComment, ownerReply1Id, "Reply 1", now);
        Comment reply2Comment = Comment.createReply(reply2Id, rootComment, otherUserId, "Reply 2", now);
        Comment tombstoneComment = Comment.createReply(replyTombstoneId, rootComment, otherUserId, "Temp", now);
        tombstoneComment.delete(now);

        CommentThreadView threadView = new CommentThreadView(
                CommentReadItem.fromRoot(rootComment),
                List.of(
                        CommentReadItem.fromActiveReply(reply1Comment, ownerRootId),
                        CommentReadItem.fromActiveReply(reply2Comment, ownerRootId),
                        CommentReadItem.fromTombstoneReply(tombstoneComment, ownerRootId)
                )
        );

        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY)).thenReturn(anchorView);
        when(getCommentThreadsByRootIdsUseCase.execute(CommentTarget.novelChapter(CHAPTER_ID), List.of(rootId)))
                .thenReturn(List.of(threadView));
        when(userIdentityContract.findPublicProfilesByIds(any()))
                .thenReturn(Map.of(
                        ownerRootId, new UserPublicProfileDTO(ownerRootId, "Owner Root", null),
                        ownerReply1Id, new UserPublicProfileDTO(ownerReply1Id, "Owner Reply", null),
                        otherUserId, new UserPublicProfileDTO(otherUserId, "Other User", null)
                ));

        // 1. Authenticated as Root Owner: root canEdit=true, canDelete=true; others false
        ChapterBlockDiscussionResponseDTO resRootOwner = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY, ownerRootId);
        var threadRootOwner = resRootOwner.threads().get(0);
        assertThat(threadRootOwner.root().canEdit()).isTrue(); // A: owner root => canEdit true
        assertThat(threadRootOwner.root().canDelete()).isTrue(); // A: owner root => canDelete true
        assertThat(threadRootOwner.replies().get(0).canEdit()).isFalse(); // C: non-owner reply => canEdit false
        assertThat(threadRootOwner.replies().get(0).canDelete()).isFalse(); // C: non-owner reply => canDelete false
        assertThat(threadRootOwner.replies().get(1).canEdit()).isFalse(); // C: non-owner reply => canEdit false
        assertThat(threadRootOwner.replies().get(1).canDelete()).isFalse(); // C: non-owner reply => canDelete false
        assertThat(threadRootOwner.replies().get(2).canEdit()).isFalse(); // E: tombstone => canEdit false
        assertThat(threadRootOwner.replies().get(2).canDelete()).isFalse(); // E: tombstone => canDelete false

        // 2. Authenticated as Reply1 Owner: root false, reply1 canEdit=true, canDelete=true; others false
        ChapterBlockDiscussionResponseDTO resReplyOwner = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY, ownerReply1Id);
        var threadReplyOwner = resReplyOwner.threads().get(0);
        assertThat(threadReplyOwner.root().canEdit()).isFalse(); // C: non-owner root => canEdit false
        assertThat(threadReplyOwner.root().canDelete()).isFalse(); // C: non-owner root => canDelete false
        assertThat(threadReplyOwner.replies().get(0).canEdit()).isTrue(); // B: owner reply => canEdit true
        assertThat(threadReplyOwner.replies().get(0).canDelete()).isTrue(); // B: owner reply => canDelete true
        assertThat(threadReplyOwner.replies().get(1).canEdit()).isFalse(); // C: non-owner reply => canEdit false
        assertThat(threadReplyOwner.replies().get(1).canDelete()).isFalse(); // C: non-owner reply => canDelete false
        assertThat(threadReplyOwner.replies().get(2).canEdit()).isFalse(); // E: tombstone => canEdit false
        assertThat(threadReplyOwner.replies().get(2).canDelete()).isFalse(); // E: tombstone => canDelete false

        // 3. Guest (null viewerUserId): all canEdit=false, canDelete=false
        ChapterBlockDiscussionResponseDTO resGuest = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY, null);
        var threadGuest = resGuest.threads().get(0);
        assertThat(threadGuest.root().canEdit()).isFalse(); // D: guest => canEdit false
        assertThat(threadGuest.root().canDelete()).isFalse(); // D: guest => canDelete false
        assertThat(threadGuest.replies().get(0).canEdit()).isFalse(); // D: guest => canEdit false
        assertThat(threadGuest.replies().get(0).canDelete()).isFalse(); // D: guest => canDelete false
        assertThat(threadGuest.replies().get(1).canEdit()).isFalse(); // D: guest => canEdit false
        assertThat(threadGuest.replies().get(1).canDelete()).isFalse(); // D: guest => canDelete false
        assertThat(threadGuest.replies().get(2).canEdit()).isFalse(); // E: tombstone => canEdit false
        assertThat(threadGuest.replies().get(2).canDelete()).isFalse(); // E: tombstone => canDelete false
    }

    @Test
    @DisplayName("Should preserve canEdit and canDelete capability even when Identity public profile lookup is missing")
    void shouldPreserveCanEditEvenWhenIdentityProfileIsMissing() {
        UUID rootId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID ownerId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        ChapterBlockDiscussionAnchorView anchorView = new ChapterBlockDiscussionAnchorView(
                CHAPTER_ID, CONTENT_VERSION, BLOCK_KEY, CANONICAL_TEXT, List.of(rootId)
        );

        Comment rootComment = Comment.createRoot(rootId, CommentTarget.novelChapter(CHAPTER_ID), ownerId, "Thread root", Instant.now());
        CommentThreadView threadView = new CommentThreadView(CommentReadItem.fromRoot(rootComment), Collections.emptyList());

        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY)).thenReturn(anchorView);
        when(getCommentThreadsByRootIdsUseCase.execute(CommentTarget.novelChapter(CHAPTER_ID), List.of(rootId)))
                .thenReturn(List.of(threadView));
        when(userIdentityContract.findPublicProfilesByIds(Set.of(ownerId))).thenReturn(Map.of());

        ChapterBlockDiscussionResponseDTO response = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY, ownerId);

        // F: Missing Identity profile does not affect canEdit or canDelete
        assertThat(response.threads().get(0).root().canEdit()).isTrue();
        assertThat(response.threads().get(0).root().canDelete()).isTrue();
        assertThat(response.threads().get(0).root().author().displayName()).isEqualTo("Người dùng");

        // G: Exactly one batch query to Identity, no extra query for capabilities
        verify(userIdentityContract).findPublicProfilesByIds(Set.of(ownerId));
    }

    @Test
    @DisplayName("Public DTO regression: active child of deleted parent has replyToAuthorUserId=null while preserving parentCommentId")
    void shouldSuppressDeletedParentAttributionOnActiveChildInPublicDto() {
        UUID rootId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        UUID userRoot = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
        UUID userB = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        UUID userC = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc");

        UUID bId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID cId = UUID.fromString("44444444-4444-4444-4444-444444444444");

        Instant now = Instant.now();
        Comment rootComment = Comment.createRoot(rootId, CommentTarget.novelChapter(CHAPTER_ID), userRoot, "Root text", now);
        Comment bComment = Comment.createReply(bId, rootComment, userB, "Deleted B text", now);
        Comment cComment = Comment.createReply(cId, bComment, userC, "Active child text", now.plusSeconds(60));
        bComment.delete(now.plusSeconds(90));

        CommentReadItem rootItem = CommentReadItem.fromRoot(rootComment);
        CommentReadItem bItem = CommentReadItem.fromTombstoneReply(bComment, userRoot);
        CommentReadItem cItem = CommentReadItem.fromActiveReply(cComment, null);

        CommentThreadView threadView = new CommentThreadView(rootItem, List.of(bItem, cItem));

        ChapterBlockDiscussionAnchorView anchorView = new ChapterBlockDiscussionAnchorView(
                CHAPTER_ID, CONTENT_VERSION, BLOCK_KEY, CANONICAL_TEXT, List.of(rootId)
        );

        when(getChapterBlockDiscussionAnchorsUseCase.execute(CHAPTER_ID, BLOCK_KEY)).thenReturn(anchorView);
        when(getCommentThreadsByRootIdsUseCase.execute(CommentTarget.novelChapter(CHAPTER_ID), List.of(rootId)))
                .thenReturn(List.of(threadView));

        // Deleted B author MUST NOT be queried in Identity
        when(userIdentityContract.findPublicProfilesByIds(Set.of(userRoot, userC)))
                .thenReturn(Map.of(
                        userRoot, new UserPublicProfileDTO(userRoot, "Root User", null),
                        userC, new UserPublicProfileDTO(userC, "Child C User", null)
                ));

        ChapterBlockDiscussionResponseDTO response = coordinator.getBlockDiscussion(CHAPTER_ID, BLOCK_KEY, null);

        var thread = response.threads().get(0);
        assertThat(thread.replies()).hasSize(2);

        // Tombstone B verification
        var bDto = thread.replies().get(0);
        assertThat(bDto.id()).isEqualTo(bId);
        assertThat(bDto.tombstone()).isTrue();
        assertThat(bDto.authorUserId()).isNull();
        assertThat(bDto.replyToAuthorUserId()).isNull();
        assertThat(bDto.author()).isNull();
        assertThat(bDto.canEdit()).isFalse();
        assertThat(bDto.canDelete()).isFalse();

        // Active Child C verification
        var cDto = thread.replies().get(1);
        assertThat(cDto.id()).isEqualTo(cId);
        assertThat(cDto.tombstone()).isFalse();
        assertThat(cDto.parentCommentId()).isEqualTo(bId); // Preserved structural ancestry
        assertThat(cDto.replyToAuthorUserId()).isNull();   // Deleted B author UUID suppressed!
        assertThat(cDto.authorUserId()).isEqualTo(userC);

        verify(userIdentityContract).findPublicProfilesByIds(Set.of(userRoot, userC));
    }
}
