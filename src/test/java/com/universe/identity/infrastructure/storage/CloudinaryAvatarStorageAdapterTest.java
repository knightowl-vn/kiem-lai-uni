package com.universe.identity.infrastructure.storage;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CloudinaryAvatarStorageAdapterTest {

    private static final UUID USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final String EXPECTED_PUBLIC_ID = "kiemlai/avatars/" + USER_ID;
    private static final String CONFIGURED_CLOUD_NAME = "kiemlai";

    @Mock
    private Uploader uploader;

    private Cloudinary cloudinary;
    private CloudinaryAvatarStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        cloudinary = Mockito.spy(new Cloudinary(Map.of("cloud_name", CONFIGURED_CLOUD_NAME)));
        adapter = new CloudinaryAvatarStorageAdapter(cloudinary);
    }

    @Test
    @DisplayName("valid legacy Cloudinary avatar URL with configured cloud name is recognized")
    void shouldRecognizeValidLegacyCloudinaryUrlWithConfiguredCloud() {
        String url1 = "https://res.cloudinary.com/" + CONFIGURED_CLOUD_NAME + "/image/upload/v12345/kiemlai/avatars/" + USER_ID + ".jpg";
        String url2 = "https://" + CONFIGURED_CLOUD_NAME + ".res.cloudinary.com/image/upload/kiemlai/avatars/avatar.png";

        assertThat(adapter.isLegacyAvatarUrl(url1)).isTrue();
        assertThat(adapter.isLegacyAvatarUrl(url2)).isTrue();
    }

    @Test
    @DisplayName("same Cloudinary host but different cloud/account is rejected")
    void shouldRejectSameHostWithDifferentCloudName() {
        String otherCloudUrl1 = "https://res.cloudinary.com/other-cloud/image/upload/v1/kiemlai/avatars/x.jpg";
        String otherCloudUrl2 = "https://other-cloud.res.cloudinary.com/image/upload/kiemlai/avatars/x.jpg";

        assertThat(adapter.isLegacyAvatarUrl(otherCloudUrl1)).isFalse();
        assertThat(adapter.isLegacyAvatarUrl(otherCloudUrl2)).isFalse();
    }

    @Test
    @DisplayName("Google/external URL is rejected")
    void shouldRejectGoogleExternalUrl() {
        String googleUrl = "https://lh3.googleusercontent.com/a/ACg8ocI-sample-avatar.png";

        assertThat(adapter.isLegacyAvatarUrl(googleUrl)).isFalse();
    }

    @Test
    @DisplayName("unrelated Cloudinary path is rejected")
    void shouldRejectUnrelatedCloudinaryPath() {
        String novelCoverUrl = "https://res.cloudinary.com/" + CONFIGURED_CLOUD_NAME + "/image/upload/v12345/kiemlai/novel/covers/novel-1.jpg";
        String wikiImageUrl = "https://res.cloudinary.com/" + CONFIGURED_CLOUD_NAME + "/image/upload/v12345/kiemlai/wiki/article-1.png";

        assertThat(adapter.isLegacyAvatarUrl(novelCoverUrl)).isFalse();
        assertThat(adapter.isLegacyAvatarUrl(wikiImageUrl)).isFalse();
    }

    @Test
    @DisplayName("deceptive Cloudinary-looking hosts are rejected")
    void shouldRejectDeceptiveCloudinaryHosts() {
        String attackerSuffix = "https://res.cloudinary.com.attacker.example/" + CONFIGURED_CLOUD_NAME + "/kiemlai/avatars/avatar.jpg";
        String attackerPrefix = "https://attacker-res.cloudinary.com/" + CONFIGURED_CLOUD_NAME + "/kiemlai/avatars/avatar.jpg";

        assertThat(adapter.isLegacyAvatarUrl(attackerSuffix)).isFalse();
        assertThat(adapter.isLegacyAvatarUrl(attackerPrefix)).isFalse();
    }

    @Test
    @DisplayName("null, blank, or malformed URLs are rejected")
    void shouldRejectNullBlankOrMalformedUrls() {
        assertThat(adapter.isLegacyAvatarUrl(null)).isFalse();
        assertThat(adapter.isLegacyAvatarUrl("   ")).isFalse();
        assertThat(adapter.isLegacyAvatarUrl("://invalid-uri")).isFalse();
        assertThat(adapter.isLegacyAvatarUrl("/local/relative/path")).isFalse();
    }

    @Test
    @DisplayName("when configured cloud name is missing or blank, returns false")
    void shouldReturnFalseWhenConfiguredCloudNameIsMissing() {
        Cloudinary unconfiguredCloudinary = new Cloudinary(Map.of());
        CloudinaryAvatarStorageAdapter unconfiguredAdapter = new CloudinaryAvatarStorageAdapter(unconfiguredCloudinary);

        String url = "https://res.cloudinary.com/" + CONFIGURED_CLOUD_NAME + "/image/upload/v12345/kiemlai/avatars/" + USER_ID + ".jpg";

        assertThat(unconfiguredAdapter.isLegacyAvatarUrl(url)).isFalse();
    }

    @Test
    @DisplayName("deleteAvatar with 'ok' result completes successfully")
    void shouldDeleteAvatarSuccessfullyWithOkResult() throws IOException {
        doReturn(uploader).when(cloudinary).uploader();
        when(uploader.destroy(eq(EXPECTED_PUBLIC_ID), anyMap())).thenReturn(Map.of("result", "ok"));

        adapter.deleteAvatar(USER_ID);

        verify(uploader).destroy(eq(EXPECTED_PUBLIC_ID), anyMap());
    }

    @Test
    @DisplayName("deleteAvatar with 'not found' result completes successfully (retry-safe)")
    void shouldDeleteAvatarSuccessfullyWithNotFoundResult() throws IOException {
        doReturn(uploader).when(cloudinary).uploader();
        when(uploader.destroy(eq(EXPECTED_PUBLIC_ID), anyMap())).thenReturn(Map.of("result", "not found"));

        adapter.deleteAvatar(USER_ID);

        verify(uploader).destroy(eq(EXPECTED_PUBLIC_ID), anyMap());
    }

    @Test
    @DisplayName("deleteAvatar with null result map throws IllegalStateException without NPE")
    void shouldThrowWhenDeleteResultIsNull() throws IOException {
        doReturn(uploader).when(cloudinary).uploader();
        when(uploader.destroy(eq(EXPECTED_PUBLIC_ID), anyMap())).thenReturn(null);

        assertThatThrownBy(() -> adapter.deleteAvatar(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cloudinary trả về kết quả rỗng khi xóa ảnh đại diện.");
    }

    @Test
    @DisplayName("deleteAvatar with unexpected result status throws IllegalStateException")
    void shouldThrowWhenDeleteResultIsUnexpected() throws IOException {
        doReturn(uploader).when(cloudinary).uploader();
        when(uploader.destroy(eq(EXPECTED_PUBLIC_ID), anyMap())).thenReturn(Map.of("result", "error"));

        assertThatThrownBy(() -> adapter.deleteAvatar(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cloudinary xóa avatar thất bại: error");
    }

    @Test
    @DisplayName("deleteAvatar throwing IOException is wrapped in IllegalStateException")
    void shouldThrowWhenDeleteThrowsIoException() throws IOException {
        doReturn(uploader).when(cloudinary).uploader();
        when(uploader.destroy(eq(EXPECTED_PUBLIC_ID), anyMap())).thenThrow(new IOException("Network timeout"));

        assertThatThrownBy(() -> adapter.deleteAvatar(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Không thể kết nối Cloudinary để xóa ảnh đại diện.");
    }

    @Test
    @DisplayName("deleteAvatar requires non-null userId")
    void shouldThrowWhenUserIdIsNull() {
        assertThatThrownBy(() -> adapter.deleteAvatar(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("User ID không được để trống.");
    }
}
