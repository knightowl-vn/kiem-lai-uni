package com.universe.wiki.application.ports;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.universe.wiki.application.image.UploadWikiImageUseCase;
import com.universe.wiki.application.image.WikiImageUploadResult;

import static org.mockito.ArgumentMatchers.any;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.universe.wiki.application.image
.WikiImageAsset;

import java.util.Optional;

@ExtendWith(MockitoExtension.class)
class UploadWikiImageUseCaseTest {

    @Mock
    private WikiImageStoragePort
            imageStoragePort;
    
    @Mock
    private WikiImageRepositoryPort
            imageRepositoryPort;

    private UploadWikiImageUseCase
            useCase;
    
    
    @BeforeEach
    void setUp() {
        useCase =
                new UploadWikiImageUseCase(
                        imageStoragePort,
                        imageRepositoryPort
                );
    }

    @Test
    @DisplayName(
            "Upload ảnh Wiki hợp lệ"
    )
    void shouldUploadValidWikiImage() {
        byte[] content =
                new byte[]{
                        1, 2, 3
                };

        WikiImageUploadResult expected =
                new WikiImageUploadResult(
                        "https://example.com/wiki/test.webp",
                        "kiemlai/wiki/test"
                );

        when(
                imageRepositoryPort
                        .findByContentHash(
                                any()
                        )
        ).thenReturn(
                Optional.empty()
        );

        when(
                imageStoragePort.upload(
                        "test.webp",
                        "image/webp",
                        content
                )
        ).thenReturn(
                expected
        );

        WikiImageUploadResult result =
                useCase.execute(
                        "test.webp",
                        "image/webp",
                        content
                );

        assertThat(result)
                .isEqualTo(expected);

        verify(
                imageStoragePort
        ).upload(
                "test.webp",
                "image/webp",
                content
        );

        verify(
                imageRepositoryPort
        ).save(
                any(
                        WikiImageAsset.class
                )
        );
    }

    @Test
    @DisplayName(
            "Từ chối file ảnh rỗng"
    )
    void shouldRejectEmptyImage() {
        assertThatThrownBy(() ->
                useCase.execute(
                        "test.png",
                        "image/png",
                        new byte[0]
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Vui lòng chọn một ảnh Wiki."
                );

        verify(
                imageStoragePort,
                never()
        ).upload(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any()
        );
    }

    @Test
    @DisplayName(
            "Từ chối loại file không hỗ trợ"
    )
    void shouldRejectUnsupportedImageType() {
        assertThatThrownBy(() ->
                useCase.execute(
                        "test.svg",
                        "image/svg+xml",
                        new byte[]{1}
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP."
                );
    }

    @Test
    @DisplayName(
            "Từ chối ảnh lớn hơn 5 MB"
    )
    void shouldRejectImageLargerThanFiveMegabytes() {
        byte[] content =
                new byte[
                        5 * 1024 * 1024 + 1
                ];

        assertThatThrownBy(() ->
                useCase.execute(
                        "large.png",
                        "image/png",
                        content
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Ảnh Wiki không được vượt quá 5 MB."
                );
    }
    
    @Test
    @DisplayName(
            "Từ chối ảnh HEIC"
    )
    void shouldRejectHeicImage() {
        assertThatThrownBy(() ->
                useCase.execute(
                        "photo.heic",
                        "image/heic",
                        new byte[]{
                                1, 2, 3
                        }
                )
        )
                .isInstanceOf(
                        IllegalArgumentException.class
                )
                .hasMessage(
                        "Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP."
                );

        verify(
                imageStoragePort,
                never()
        ).upload(
                any(),
                any(),
                any()
        );
    }
}