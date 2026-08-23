package com.universe.novel.infrastructure.persistence.profile;

import com.universe.novel.contracts.dto.profile.NovelProfileDTO;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class NovelProfilePersistenceAdapterTest {

    private static final String PROFILE_ID_STR =
            "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-22T11:30:00Z");

    private static final Instant UPDATED_AT =
            Instant.parse("2026-08-22T17:30:00Z");

    @Mock
    private SpringDataNovelProfileJpaRepository
            novelProfileRepository;

    private NovelProfilePersistenceAdapter
            adapter;

    @BeforeEach
    void setUp() {
        adapter = new NovelProfilePersistenceAdapter(
                novelProfileRepository
        );
    }

    @Test
    @DisplayName("findBySlug ánh xạ NovelProfileJpaEntity sang NovelProfileDTO chính xác")
    void shouldFindAndMapEntityToDTO() {
        NovelProfileJpaEntity entity = createEntity(
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả Kiếm Lai",
                "https://example.com/cover.jpg",
                "ONGOING"
        );

        when(novelProfileRepository.findBySlug("kiem-lai"))
                .thenReturn(Optional.of(entity));

        Optional<NovelProfileDTO> result =
                adapter.findBySlug("kiem-lai");

        assertThat(result).isPresent();
        NovelProfileDTO dto = result.get();
        assertThat(dto.id()).isEqualTo(UUID.fromString(PROFILE_ID_STR));
        assertThat(dto.title()).isEqualTo("Kiếm Lai");
        assertThat(dto.slug()).isEqualTo("kiem-lai");
        assertThat(dto.author()).isEqualTo("Phong Hỏa Hí Chư Hầu");
        assertThat(dto.description()).isEqualTo("Mô tả Kiếm Lai");
        assertThat(dto.coverImageUrl()).isEqualTo("https://example.com/cover.jpg");
        assertThat(dto.status()).isEqualTo("ONGOING");
        assertThat(dto.createdAt()).isEqualTo(CREATED_AT);
        assertThat(dto.updatedAt()).isEqualTo(CREATED_AT);

        verify(novelProfileRepository).findBySlug("kiem-lai");
    }

    @Test
    @DisplayName("update cập nhật các trường được phép và lưu lại NovelProfileJpaEntity")
    void shouldUpdateEntityAndSave() {
        NovelProfileJpaEntity entity = createEntity(
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa",
                "Mô tả cũ",
                null,
                "ONGOING"
        );

        when(novelProfileRepository.findBySlug("kiem-lai"))
                .thenReturn(Optional.of(entity));
        when(novelProfileRepository.save(any(NovelProfileJpaEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        NovelProfileDTO updatedDTO = adapter.update(
                "kiem-lai",
                "Kiếm Lai Mới",
                "Tác giả Mới",
                "Mô tả Mới",
                "https://example.com/cover2.jpg",
                "COMPLETED",
                UPDATED_AT
        );

        assertThat(updatedDTO.title()).isEqualTo("Kiếm Lai Mới");
        assertThat(updatedDTO.author()).isEqualTo("Tác giả Mới");
        assertThat(updatedDTO.description()).isEqualTo("Mô tả Mới");
        assertThat(updatedDTO.coverImageUrl()).isEqualTo("https://example.com/cover2.jpg");
        assertThat(updatedDTO.status()).isEqualTo("COMPLETED");
        assertThat(updatedDTO.updatedAt()).isEqualTo(UPDATED_AT);

        // Đảm bảo ID, slug và createdAt không bị thay đổi
        assertThat(updatedDTO.id()).isEqualTo(UUID.fromString(PROFILE_ID_STR));
        assertThat(updatedDTO.slug()).isEqualTo("kiem-lai");
        assertThat(updatedDTO.createdAt()).isEqualTo(CREATED_AT);

        verify(novelProfileRepository).findBySlug("kiem-lai");
        verify(novelProfileRepository).save(entity);
    }

    @Test
    @DisplayName("update ném IllegalStateException khi không tìm thấy entity theo slug")
    void shouldThrowExceptionWhenEntityNotFound() {
        when(novelProfileRepository.findBySlug("nonexistent"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> adapter.update(
                "nonexistent",
                "Tiêu đề",
                "Tác giả",
                "Mô tả",
                null,
                "ONGOING",
                UPDATED_AT
        )).isInstanceOf(IllegalStateException.class);
    }

    private NovelProfileJpaEntity createEntity(
            String title,
            String slug,
            String author,
            String description,
            String coverImageUrl,
            String status
    ) {
        NovelProfileJpaEntity entity = new NovelProfileJpaEntity();
        // Set fields via reflection or helper update
        entity.update(title, author, description, coverImageUrl, status, CREATED_AT);
        try {
            var idField = NovelProfileJpaEntity.class.getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(entity, PROFILE_ID_STR);

            var slugField = NovelProfileJpaEntity.class.getDeclaredField("slug");
            slugField.setAccessible(true);
            slugField.set(entity, slug);

            var createdField = NovelProfileJpaEntity.class.getDeclaredField("createdAt");
            createdField.setAccessible(true);
            createdField.set(entity, CREATED_AT);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
        return entity;
    }
}
