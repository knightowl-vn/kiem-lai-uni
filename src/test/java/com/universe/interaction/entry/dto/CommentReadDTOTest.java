package com.universe.interaction.entry.dto;

import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommentReadDTO Unit Tests (MS-05E5G4C1)")
class CommentReadDTOTest {

    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REPLY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TOMBSTONE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final UUID OWNER_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID OTHER_USER_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final Instant NOW = Instant.parse("2026-09-17T12:00:00Z");

    @Test
    @DisplayName("A. Authenticated owner on active root comment receives canEdit=true and canDelete=true")
    void shouldAllowEditAndDeleteForActiveRootOwner() {
        Comment root = Comment.createRoot(ROOT_ID, CommentTarget.novelChapter(TARGET_ID), OWNER_ID, "Root body", NOW);
        CommentReadItem item = CommentReadItem.fromRoot(root);

        CommentReadDTO dto = CommentReadDTO.from(item, OWNER_ID);

        assertThat(dto.canEdit()).isTrue();
        assertThat(dto.canDelete()).isTrue();
        assertThat(dto.id()).isEqualTo(ROOT_ID);
        assertThat(dto.authorUserId()).isEqualTo(OWNER_ID);
        assertThat(dto.parentCommentId()).isNull();
        assertThat(dto.replyToAuthorUserId()).isNull();
        assertThat(dto.body()).isEqualTo("Root body");
        assertThat(dto.tombstone()).isFalse();
    }

    @Test
    @DisplayName("B. Authenticated owner on active reply receives canEdit=true and canDelete=true")
    void shouldAllowEditAndDeleteForActiveReplyOwner() {
        Comment root = Comment.createRoot(ROOT_ID, CommentTarget.novelChapter(TARGET_ID), OTHER_USER_ID, "Root body", NOW);
        Comment reply = Comment.createReply(REPLY_ID, root, OWNER_ID, "Reply body", NOW.plusSeconds(30));
        CommentReadItem item = CommentReadItem.fromActiveReply(reply, OTHER_USER_ID);

        CommentReadDTO dto = CommentReadDTO.from(item, OWNER_ID);

        assertThat(dto.canEdit()).isTrue();
        assertThat(dto.canDelete()).isTrue();
        assertThat(dto.id()).isEqualTo(REPLY_ID);
        assertThat(dto.authorUserId()).isEqualTo(OWNER_ID);
        assertThat(dto.parentCommentId()).isEqualTo(ROOT_ID);
        assertThat(dto.replyToAuthorUserId()).isEqualTo(OTHER_USER_ID);
        assertThat(dto.body()).isEqualTo("Reply body");
        assertThat(dto.tombstone()).isFalse();
    }

    @Test
    @DisplayName("C. Authenticated non-owner receives canEdit=false and canDelete=false")
    void shouldDisallowEditAndDeleteForNonOwner() {
        Comment root = Comment.createRoot(ROOT_ID, CommentTarget.novelChapter(TARGET_ID), OWNER_ID, "Root body", NOW);
        CommentReadItem item = CommentReadItem.fromRoot(root);

        CommentReadDTO dto = CommentReadDTO.from(item, OTHER_USER_ID);

        assertThat(dto.canEdit()).isFalse();
        assertThat(dto.canDelete()).isFalse();
        assertThat(dto.authorUserId()).isEqualTo(OWNER_ID);
    }

    @Test
    @DisplayName("D. Guest viewer (null viewerUserId) receives canEdit=false and canDelete=false")
    void shouldDisallowEditAndDeleteForGuest() {
        Comment root = Comment.createRoot(ROOT_ID, CommentTarget.novelChapter(TARGET_ID), OWNER_ID, "Root body", NOW);
        CommentReadItem item = CommentReadItem.fromRoot(root);

        CommentReadDTO dto = CommentReadDTO.from(item, (UUID) null);

        assertThat(dto.canEdit()).isFalse();
        assertThat(dto.canDelete()).isFalse();
        assertThat(dto.authorUserId()).isEqualTo(OWNER_ID);
    }

    @Test
    @DisplayName("E & F. Tombstone reply sanitizes authorUserId, replyToAuthorUserId, author, canEdit, canDelete while preserving id and parentCommentId")
    void shouldSanitizeTombstonePrivacyWhilePreservingStructuralAncestry() {
        Comment root = Comment.createRoot(ROOT_ID, CommentTarget.novelChapter(TARGET_ID), OWNER_ID, "Root body", NOW);
        Comment tombstone = Comment.createReply(TOMBSTONE_ID, root, OWNER_ID, "Will be deleted", NOW.plusSeconds(30));
        tombstone.delete(NOW.plusSeconds(60));
        CommentReadItem item = CommentReadItem.fromTombstoneReply(tombstone, OWNER_ID);

        // Even when viewer is the original author, capabilities are false and identities are nulled
        CommentReadDTO dto = CommentReadDTO.from(item, OWNER_ID);

        // E: Privacy hardening assertions
        assertThat(dto.tombstone()).isTrue();
        assertThat(dto.authorUserId()).isNull();
        assertThat(dto.replyToAuthorUserId()).isNull();
        assertThat(dto.author()).isNull();
        assertThat(dto.canEdit()).isFalse();
        assertThat(dto.canDelete()).isFalse();

        // F: Structural ancestry preservation
        assertThat(dto.id()).isEqualTo(TOMBSTONE_ID);
        assertThat(dto.parentCommentId()).isEqualTo(ROOT_ID);
        assertThat(dto.body()).isNull();
        assertThat(dto.createdAt()).isEqualTo(NOW.plusSeconds(30));
        assertThat(dto.updatedAt()).isEqualTo(NOW.plusSeconds(60));
    }

    @Test
    @DisplayName("Constructor sanitization ensures tombstone privacy even if fields are directly supplied")
    void shouldSanitizeTombstoneEvenWhenDirectConstructorIsInvoked() {
        CommentAuthorDTO mockAuthor = new CommentAuthorDTO(OWNER_ID, "Leaked Name", null);
        CommentReadDTO directDto = new CommentReadDTO(
                TOMBSTONE_ID,
                OWNER_ID,
                ROOT_ID,
                OTHER_USER_ID,
                null,
                true, // tombstone
                NOW,
                NOW,
                mockAuthor,
                true, // attempted canEdit
                true  // attempted canDelete
        );

        assertThat(directDto.tombstone()).isTrue();
        assertThat(directDto.authorUserId()).isNull();
        assertThat(directDto.replyToAuthorUserId()).isNull();
        assertThat(directDto.author()).isNull();
        assertThat(directDto.canEdit()).isFalse();
        assertThat(directDto.canDelete()).isFalse();
        assertThat(directDto.id()).isEqualTo(TOMBSTONE_ID);
        assertThat(directDto.parentCommentId()).isEqualTo(ROOT_ID);
    }
}
