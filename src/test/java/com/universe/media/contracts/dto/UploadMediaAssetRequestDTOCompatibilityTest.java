package com.universe.media.contracts.dto;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class UploadMediaAssetRequestDTOCompatibilityTest {

    @Test
    @DisplayName("6-argument constructor preserves backward compatibility defaulting clientTag to null")
    void shouldDefaultClientTagToNullInLegacyConstructor() {
        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        UploadMediaAssetRequestDTO dto = new UploadMediaAssetRequestDTO(
                stream,
                3L,
                "image/png",
                MediaTypeDTO.IMAGE,
                MediaVisibilityDTO.PUBLIC,
                "test.png"
        );

        assertThat(dto.content()).isSameAs(stream);
        assertThat(dto.sizeBytes()).isEqualTo(3L);
        assertThat(dto.mimeType()).isEqualTo("image/png");
        assertThat(dto.mediaType()).isEqualTo(MediaTypeDTO.IMAGE);
        assertThat(dto.visibility()).isEqualTo(MediaVisibilityDTO.PUBLIC);
        assertThat(dto.originalFilename()).isEqualTo("test.png");
        assertThat(dto.clientTag()).isNull();
    }

    @Test
    @DisplayName("7-argument constructor stores provided clientTag")
    void shouldStoreProvidedClientTag() {
        InputStream stream = new ByteArrayInputStream(new byte[]{1, 2, 3});
        UploadMediaAssetRequestDTO dto = new UploadMediaAssetRequestDTO(
                stream,
                3L,
                "image/png",
                MediaTypeDTO.IMAGE,
                MediaVisibilityDTO.PUBLIC,
                "test.png",
                "wiki.article.cover"
        );

        assertThat(dto.clientTag()).isEqualTo("wiki.article.cover");
    }
}
