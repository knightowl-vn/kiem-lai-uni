package com.universe.novel.application.profile;

import com.universe.novel.application.ports.NovelProfileRepositoryPort;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GetNovelProfileUseCaseTest {

    private static final UUID PROFILE_ID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final Instant CREATED_AT =
            Instant.parse("2026-08-22T11:30:00Z");

    private static final Instant UPDATED_AT =
            Instant.parse("2026-08-22T11:30:00Z");

    @Mock
    private NovelProfileRepositoryPort
            novelProfileRepositoryPort;

    private GetNovelProfileUseCase
            useCase;

    @BeforeEach
    void setUp() {
        useCase = new GetNovelProfileUseCase(
                novelProfileRepositoryPort
        );
    }

    @Test
    @DisplayName("Lấy hồ sơ Novel mặc định 'kiem-lai' thành công")
    void shouldReturnNovelProfileSuccessfully() {
        NovelProfileDTO expectedProfile = new NovelProfileDTO(
                PROFILE_ID,
                "Kiếm Lai",
                "kiem-lai",
                "Phong Hỏa Hí Chư Hầu",
                "Mô tả tiểu thuyết Kiếm Lai.",
                "https://example.com/cover.jpg",
                "ONGOING",
                CREATED_AT,
                UPDATED_AT
        );

        when(novelProfileRepositoryPort.findBySlug("kiem-lai"))
                .thenReturn(Optional.of(expectedProfile));

        NovelProfileDTO actual = useCase.execute();

        assertThat(actual).isEqualTo(expectedProfile);
        verify(novelProfileRepositoryPort).findBySlug("kiem-lai");
    }

    @Test
    @DisplayName("Ném IllegalStateException khi không tìm thấy hồ sơ Novel mặc định")
    void shouldThrowExceptionWhenProfileNotFound() {
        when(novelProfileRepositoryPort.findBySlug("kiem-lai"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> useCase.execute())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Không tìm thấy hồ sơ tiểu thuyết mặc định");

        verify(novelProfileRepositoryPort).findBySlug("kiem-lai");
    }
}
