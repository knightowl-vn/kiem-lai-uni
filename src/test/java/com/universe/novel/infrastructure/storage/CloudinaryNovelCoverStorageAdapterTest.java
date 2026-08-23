package com.universe.novel.infrastructure.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.universe.novel.application.profile.NovelCoverUpload;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudinaryNovelCoverStorageAdapterTest {

    private static final byte[] VALID_IMAGE_BYTES =
            new byte[]{1, 2, 3, 4, 5};

    private static final String SECURE_URL =
            "https://res.cloudinary.com/demo/image/upload/v12345/kiemlai/novel/covers/kiem-lai/test.webp";

    @Mock
    private Cloudinary
            cloudinary;

    @Mock
    private Uploader
            uploader;

    private CloudinaryNovelCoverStorageAdapter
            adapter;

    @BeforeEach
    void setUp() {
        adapter = new CloudinaryNovelCoverStorageAdapter(
                cloudinary
        );
    }

    @Test
    @DisplayName("Upload ảnh bìa thành công với public_id duy nhất và đúng asset_folder")
    void shouldUploadCoverSuccessfullyWithUniquePublicId() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenReturn(Map.of("secure_url", SECURE_URL));

        NovelCoverUpload coverUpload = new NovelCoverUpload(
                "cover-art.png",
                "image/png",
                VALID_IMAGE_BYTES
        );

        String resultUrl = adapter.upload("kiem-lai", coverUpload);

        assertThat(resultUrl).isEqualTo(SECURE_URL);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> optionsCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(any(byte[].class), optionsCaptor.capture());

        Map<String, Object> options = optionsCaptor.getValue();
        assertThat(options.get("asset_folder")).isEqualTo("kiemlai/novel/covers/kiem-lai");
        assertThat(options.get("overwrite")).isEqualTo(false);
        assertThat(options.get("resource_type")).isEqualTo("image");
        assertThat(options.get("format")).isEqualTo("webp");

        String publicId = (String) options.get("public_id");
        assertThat(publicId).startsWith("kiemlai/novel/covers/kiem-lai/");
        String uuidPart = publicId.substring("kiemlai/novel/covers/kiem-lai/".length());
        assertThat(UUID.fromString(uuidPart)).isNotNull();
    }

    @Test
    @DisplayName("Mỗi lần upload tạo ra một public_id duy nhất không trùng lặp")
    void shouldGenerateDifferentPublicIdForEveryUpload() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenReturn(Map.of("secure_url", SECURE_URL));

        NovelCoverUpload coverUpload = new NovelCoverUpload(
                "cover.jpg",
                "image/jpeg",
                VALID_IMAGE_BYTES
        );

        adapter.upload("kiem-lai", coverUpload);
        adapter.upload("kiem-lai", coverUpload);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> optionsCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(uploader, times(2)).upload(any(byte[].class), optionsCaptor.capture());

        var capturedList = optionsCaptor.getAllValues();
        String publicId1 = (String) capturedList.get(0).get("public_id");
        String publicId2 = (String) capturedList.get(1).get("public_id");

        assertThat(publicId1).isNotEqualTo(publicId2);
    }

    @Test
    @DisplayName("Ném IllegalArgumentException khi MIME type không được hỗ trợ")
    void shouldThrowExceptionWhenMimeTypeIsInvalid() {
        NovelCoverUpload invalidMime = new NovelCoverUpload(
                "cover.gif",
                "image/gif",
                VALID_IMAGE_BYTES
        );

        assertThatThrownBy(() -> adapter.upload("kiem-lai", invalidMime))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chỉ chấp nhận ảnh định dạng JPG, PNG hoặc WEBP.");

        verify(cloudinary, never()).uploader();
    }

    @Test
    @DisplayName("Ném IllegalArgumentException khi đuôi mở rộng file không hợp lệ")
    void shouldThrowExceptionWhenExtensionIsInvalid() {
        NovelCoverUpload invalidExt = new NovelCoverUpload(
                "cover.bmp",
                "image/png",
                VALID_IMAGE_BYTES
        );

        assertThatThrownBy(() -> adapter.upload("kiem-lai", invalidExt))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Chỉ chấp nhận ảnh định dạng JPG, PNG hoặc WEBP.");

        verify(cloudinary, never()).uploader();
    }

    @Test
    @DisplayName("Ném IllegalArgumentException khi kích thước file vượt quá 5 MB")
    void shouldThrowExceptionWhenFileSizeExceeds5MB() {
        byte[] oversizedBytes = new byte[5 * 1024 * 1024 + 1];
        NovelCoverUpload oversizedUpload = new NovelCoverUpload(
                "huge-cover.jpg",
                "image/jpeg",
                oversizedBytes
        );

        assertThatThrownBy(() -> adapter.upload("kiem-lai", oversizedUpload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Ảnh bìa không được vượt quá 5 MB.");

        verify(cloudinary, never()).uploader();
    }

    @Test
    @DisplayName("Ném IllegalArgumentException khi dữ liệu file rỗng hoặc null")
    void shouldThrowExceptionWhenContentIsEmptyOrNull() {
        NovelCoverUpload emptyUpload = new NovelCoverUpload(
                "empty.jpg",
                "image/jpeg",
                new byte[0]
        );

        assertThatThrownBy(() -> adapter.upload("kiem-lai", emptyUpload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Vui lòng chọn file ảnh bìa hợp lệ.");

        assertThatThrownBy(() -> adapter.upload("kiem-lai", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Dữ liệu ảnh bìa không được để trống.");

        verify(cloudinary, never()).uploader();
    }

    @Test
    @DisplayName("Ném IllegalArgumentException khi novel slug rỗng")
    void shouldThrowExceptionWhenSlugIsBlank() {
        NovelCoverUpload validUpload = new NovelCoverUpload(
                "cover.webp",
                "image/webp",
                VALID_IMAGE_BYTES
        );

        assertThatThrownBy(() -> adapter.upload("   ", validUpload))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Slug của Novel không được để trống.");

        verify(cloudinary, never()).uploader();
    }

    @Test
    @DisplayName("Ném IllegalStateException khi Cloudinary ném IOException")
    void shouldThrowExceptionWhenCloudinaryFailsWithIOException() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenThrow(new IOException("Connection reset by peer"));

        NovelCoverUpload validUpload = new NovelCoverUpload(
                "cover.png",
                "image/png",
                VALID_IMAGE_BYTES
        );

        assertThatThrownBy(() -> adapter.upload("kiem-lai", validUpload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Không thể tải ảnh bìa Novel lên Cloudinary.");
    }

    @Test
    @DisplayName("Ném IllegalStateException khi Cloudinary không trả về secure_url")
    void shouldThrowExceptionWhenCloudinaryReturnsMissingSecureUrl() throws Exception {
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(byte[].class), anyMap()))
                .thenReturn(Map.of("public_id", "some-public-id"));

        NovelCoverUpload validUpload = new NovelCoverUpload(
                "cover.png",
                "image/png",
                VALID_IMAGE_BYTES
        );

        assertThatThrownBy(() -> adapter.upload("kiem-lai", validUpload))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cloudinary không trả về secure_url");
    }
}
