package com.universe.wiki.entry.admin;

import com.universe.wiki.application.image.UploadWikiImageUseCase;
import com.universe.wiki.application.image.WikiImageUploadResult;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminWikiImageControllerTest {

    @Mock
    private UploadWikiImageUseCase uploadWikiImageUseCase;

    private AdminWikiImageController controller;

    @BeforeEach
    void setUp() {
        controller = new AdminWikiImageController(uploadWikiImageUseCase);
    }

    @Test
    @DisplayName("upload thành công trả về 200 OK với URL và publicId null an toàn cho Media-backed image")
    void shouldUploadSuccessfullyAndReturnNullPublicIdForMediaBackedImage() {
        byte[] content = "test-image-content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "photo.png",
                "image/png",
                content
        );

        when(uploadWikiImageUseCase.execute(
                any(InputStream.class),
                eq((long) content.length),
                eq("image/png"),
                eq("photo.png")
        )).thenReturn(new WikiImageUploadResult(
                "/media/assets/11111111-1111-1111-1111-111111111111/content",
                null
        ));

        ResponseEntity<?> response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("url")).isEqualTo("/media/assets/11111111-1111-1111-1111-111111111111/content");
        assertThat(body.containsKey("publicId")).isTrue();
        assertThat(body.get("publicId")).isNull();
    }

    @Test
    @DisplayName("upload thành công khi deduplicate legacy trả về 200 OK với URL và publicId legacy")
    void shouldUploadSuccessfullyAndReturnLegacyPublicIdForLegacyImage() {
        byte[] content = "legacy-content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "legacy.webp",
                "image/webp",
                content
        );

        when(uploadWikiImageUseCase.execute(
                any(InputStream.class),
                eq((long) content.length),
                eq("image/webp"),
                eq("legacy.webp")
        )).thenReturn(new WikiImageUploadResult(
                "https://res.cloudinary.com/legacy.webp",
                "kiemlai/wiki/legacy-123"
        ));

        ResponseEntity<?> response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("url")).isEqualTo("https://res.cloudinary.com/legacy.webp");
        assertThat(body.get("publicId")).isEqualTo("kiemlai/wiki/legacy-123");
    }

    @Test
    @DisplayName("upload trả về 400 Bad Request khi file rỗng")
    void shouldReturnBadRequestWhenFileIsEmpty() {
        MockMultipartFile emptyFile = new MockMultipartFile(
                "file",
                "empty.png",
                "image/png",
                new byte[0]
        );

        ResponseEntity<?> response = controller.upload(emptyFile);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("message", "Vui lòng chọn một ảnh Wiki.");
    }

    @Test
    @DisplayName("upload trả về 400 Bad Request khi use case ném IllegalArgumentException")
    void shouldReturnBadRequestWhenUseCaseThrowsIllegalArgumentException() {
        byte[] content = "invalid".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "invalid.txt",
                "text/plain",
                content
        );

        when(uploadWikiImageUseCase.execute(any(), anyLong(), any(), any()))
                .thenThrow(new IllegalArgumentException("Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP."));

        ResponseEntity<?> response = controller.upload(file);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).containsEntry("message", "Chỉ chấp nhận ảnh JPG, PNG hoặc WEBP.");
    }
}
