package com.universe.community.domain;

import com.universe.community.domain.exception.CommunityPostValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CommunityPostRevisionTest {

    private static final UUID REV_ID = UUID.fromString("00000000-0000-0000-0000-000000000010");
    private static final UUID POST_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID EDITOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant EDITED_AT = Instant.parse("2026-09-29T10:05:00Z");

    @Test
    @DisplayName("Should create valid CommunityPostRevision snapshot")
    void shouldCreateValidRevision() {
        CommunityPostRevision revision = new CommunityPostRevision(
                REV_ID,
                POST_ID,
                1,
                EDITOR_ID,
                "  Prior caption  ",
                "  Updated caption  ",
                EDITED_AT
        );

        assertThat(revision.getId()).isEqualTo(REV_ID);
        assertThat(revision.getPostId()).isEqualTo(POST_ID);
        assertThat(revision.getRevisionNumber()).isEqualTo(1);
        assertThat(revision.getEditorUserId()).isEqualTo(EDITOR_ID);
        assertThat(revision.getPreviousCaption()).isEqualTo("Prior caption");
        assertThat(revision.getCaption()).isEqualTo("Updated caption");
        assertThat(revision.getEditedAt()).isEqualTo(EDITED_AT);
    }

    @Test
    @DisplayName("Should reject revisionNumber < 1")
    void shouldRejectInvalidRevisionNumber() {
        assertThatThrownBy(() -> new CommunityPostRevision(REV_ID, POST_ID, 0, EDITOR_ID, "prev", "curr", EDITED_AT))
                .isInstanceOf(CommunityPostValidationException.class)
                .hasMessageContaining("Revision number must be greater than or equal to 1");

        assertThatThrownBy(() -> new CommunityPostRevision(REV_ID, POST_ID, -1, EDITOR_ID, "prev", "curr", EDITED_AT))
                .isInstanceOf(CommunityPostValidationException.class);
    }

    @Test
    @DisplayName("Should reject null mandatory fields")
    void shouldRejectNullMandatoryFields() {
        assertThatThrownBy(() -> new CommunityPostRevision(null, POST_ID, 1, EDITOR_ID, "prev", "curr", EDITED_AT))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CommunityPostRevision(REV_ID, null, 1, EDITOR_ID, "prev", "curr", EDITED_AT))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CommunityPostRevision(REV_ID, POST_ID, 1, null, "prev", "curr", EDITED_AT))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> new CommunityPostRevision(REV_ID, POST_ID, 1, EDITOR_ID, "prev", "curr", null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("Should reject blank or excessive previousCaption and caption")
    void shouldRejectInvalidCaptions() {
        assertThatThrownBy(() -> new CommunityPostRevision(REV_ID, POST_ID, 1, EDITOR_ID, null, "curr", EDITED_AT))
                .isInstanceOf(CommunityPostValidationException.class);

        assertThatThrownBy(() -> new CommunityPostRevision(REV_ID, POST_ID, 1, EDITOR_ID, "   ", "curr", EDITED_AT))
                .isInstanceOf(CommunityPostValidationException.class);

        assertThatThrownBy(() -> new CommunityPostRevision(REV_ID, POST_ID, 1, EDITOR_ID, "prev", null, EDITED_AT))
                .isInstanceOf(CommunityPostValidationException.class);

        assertThatThrownBy(() -> new CommunityPostRevision(REV_ID, POST_ID, 1, EDITOR_ID, "prev", "", EDITED_AT))
                .isInstanceOf(CommunityPostValidationException.class);

        assertThatThrownBy(() -> new CommunityPostRevision(REV_ID, POST_ID, 1, EDITOR_ID, "a".repeat(2001), "curr", EDITED_AT))
                .isInstanceOf(CommunityPostValidationException.class);

        assertThatThrownBy(() -> new CommunityPostRevision(REV_ID, POST_ID, 1, EDITOR_ID, "prev", "a".repeat(2001), EDITED_AT))
                .isInstanceOf(CommunityPostValidationException.class);
    }
}
