package com.universe.interaction.entry.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("CommentAuthorDTO Unit Tests")
class CommentAuthorDTOTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Test
    @DisplayName("Should construct with full valid fields")
    void shouldConstructWithFullValidFields() {
        CommentAuthorDTO dto = new CommentAuthorDTO(
                USER_ID,
                "Quettye",
                "https://example.com/avatar.png",
                "quettye"
        );

        assertThat(dto.userId()).isEqualTo(USER_ID);
        assertThat(dto.displayName()).isEqualTo("Quettye");
        assertThat(dto.avatarUrl()).isEqualTo("https://example.com/avatar.png");
        assertThat(dto.publicHandle()).isEqualTo("quettye");
    }

    @Test
    @DisplayName("Should throw NullPointerException when userId is null")
    void shouldThrowWhenUserIdIsNull() {
        assertThatThrownBy(() -> new CommentAuthorDTO(null, "Quettye", null, "quettye"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Author userId cannot be null");
    }

    @Test
    @DisplayName("Should fallback displayName to default when null, empty, or whitespace")
    void shouldFallbackDisplayNameWhenBlank() {
        CommentAuthorDTO dtoNull = new CommentAuthorDTO(USER_ID, null, null, "quettye");
        assertThat(dtoNull.displayName()).isEqualTo(CommentAuthorDTO.DEFAULT_DISPLAY_NAME);

        CommentAuthorDTO dtoEmpty = new CommentAuthorDTO(USER_ID, "", null, "quettye");
        assertThat(dtoEmpty.displayName()).isEqualTo(CommentAuthorDTO.DEFAULT_DISPLAY_NAME);

        CommentAuthorDTO dtoBlank = new CommentAuthorDTO(USER_ID, "   ", null, "quettye");
        assertThat(dtoBlank.displayName()).isEqualTo(CommentAuthorDTO.DEFAULT_DISPLAY_NAME);
    }

    @Test
    @DisplayName("Should trim displayName when populated")
    void shouldTrimDisplayName() {
        CommentAuthorDTO dto = new CommentAuthorDTO(USER_ID, "  Quettye  ", null, "quettye");
        assertThat(dto.displayName()).isEqualTo("Quettye");
    }

    @Test
    @DisplayName("Should normalize publicHandle to null when null, empty, or blank")
    void shouldNormalizePublicHandleWhenBlank() {
        CommentAuthorDTO dtoNull = new CommentAuthorDTO(USER_ID, "Quettye", null, null);
        assertThat(dtoNull.publicHandle()).isNull();

        CommentAuthorDTO dtoEmpty = new CommentAuthorDTO(USER_ID, "Quettye", null, "");
        assertThat(dtoEmpty.publicHandle()).isNull();

        CommentAuthorDTO dtoBlank = new CommentAuthorDTO(USER_ID, "Quettye", null, "   ");
        assertThat(dtoBlank.publicHandle()).isNull();
    }

    @Test
    @DisplayName("Should trim publicHandle when populated")
    void shouldTrimPublicHandle() {
        CommentAuthorDTO dto = new CommentAuthorDTO(USER_ID, "Quettye", null, "  quettye  ");
        assertThat(dto.publicHandle()).isEqualTo("quettye");
    }

    @Test
    @DisplayName("Should preserve publicHandle byte-for-byte without case transformation or character substitution")
    void shouldPreservePublicHandleByteForByte() {
        CommentAuthorDTO dto = new CommentAuthorDTO(USER_ID, "Quettye", null, "QuyenNe");
        assertThat(dto.publicHandle()).isEqualTo("QuyenNe");
    }

    @Test
    @DisplayName("fallback method should produce expected default record")
    void shouldProduceExpectedFallbackRecord() {
        CommentAuthorDTO dto = CommentAuthorDTO.fallback(USER_ID);

        assertThat(dto.userId()).isEqualTo(USER_ID);
        assertThat(dto.displayName()).isEqualTo(CommentAuthorDTO.DEFAULT_DISPLAY_NAME);
        assertThat(dto.avatarUrl()).isNull();
        assertThat(dto.publicHandle()).isNull();
    }
}
