package com.universe.interaction.entry.shared;

import com.universe.identity.contracts.dto.UserPublicProfileDTO;
import com.universe.interaction.application.query.CommentReadItem;
import com.universe.interaction.application.query.CommentThreadView;
import com.universe.interaction.domain.Comment;
import com.universe.interaction.domain.CommentTarget;
import com.universe.interaction.entry.dto.CommentAuthorDTO;
import com.universe.interaction.entry.dto.CommentOriginDTO;
import com.universe.interaction.entry.dto.CommentThreadResponseDTO;
import com.universe.interaction.entry.dto.ReactionSummaryResponseDTO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CommentThreadDtoAssembler Unit Tests")
class CommentThreadDtoAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
    private static final UUID TARGET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ROOT_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID REPLY_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID TOMBSTONE_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ENTRY_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID AUTHOR_1_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID AUTHOR_2_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    @Test
    @DisplayName("Assembles thread DTOs with author fallback, tombstone redaction, reactions, and custom origin")
    void shouldAssembleThreadDtosAccurately() {
        CommentTarget target = CommentTarget.screenTitle(TARGET_ID);
        Comment root = Comment.createRoot(ROOT_ID, target, AUTHOR_1_ID, "Root text", NOW, ENTRY_ID);
        Comment reply = Comment.createReply(REPLY_ID, root, AUTHOR_2_ID, "Reply text", NOW.plusSeconds(1));
        Comment tombstone = Comment.createReply(TOMBSTONE_ID, root, AUTHOR_2_ID, "Deleted text", NOW.plusSeconds(2), ENTRY_ID);
        tombstone.delete(NOW.plusSeconds(3));

        CommentThreadView view = new CommentThreadView(
                CommentReadItem.fromRoot(root),
                List.of(
                        CommentReadItem.fromTombstoneReply(tombstone, AUTHOR_1_ID),
                        CommentReadItem.fromActiveReply(reply, AUTHOR_1_ID)
                )
        );

        Map<UUID, UserPublicProfileDTO> authors = Map.of(
                AUTHOR_1_ID, new UserPublicProfileDTO(AUTHOR_1_ID, "Author One", "/a1.png", "author_1")
        );
        Map<UUID, ReactionSummaryResponseDTO> reactions = Map.of(
                ROOT_ID, new ReactionSummaryResponseDTO("COMMENT", ROOT_ID, Map.of("LIKE", 3L), 3L, "LIKE")
        );

        List<CommentThreadResponseDTO> threads = CommentThreadDtoAssembler.toThreadDtos(
                List.of(ROOT_ID),
                Map.of(ROOT_ID, view),
                authors,
                AUTHOR_1_ID,
                reactions,
                item -> item.originScreenEntryId() != null
                        ? new CommentOriginDTO(item.originScreenEntryId(), "Tập 1", "tap-1")
                        : null
        );

        assertThat(threads).hasSize(1);
        CommentThreadResponseDTO dto = threads.get(0);
        assertThat(dto.root().author().displayName()).isEqualTo("Author One");
        assertThat(dto.root().canEdit()).isTrue();
        assertThat(dto.root().reactionSummary().totalCount()).isEqualTo(3L);
        assertThat(dto.root().origin().badgeText()).isEqualTo("Tập 1");

        // Tombstone reply stays redacted
        assertThat(dto.replies().get(0).tombstone()).isTrue();
        assertThat(dto.replies().get(0).author()).isNull();
        assertThat(dto.replies().get(0).origin()).isNull();
        assertThat(dto.replies().get(0).reactionSummary()).isNull();

        // Missing profile falls back cleanly
        assertThat(dto.replies().get(1).tombstone()).isFalse();
        assertThat(dto.replies().get(1).author().displayName()).isEqualTo(CommentAuthorDTO.DEFAULT_DISPLAY_NAME);
        assertThat(dto.replies().get(1).canEdit()).isFalse();
    }
}
