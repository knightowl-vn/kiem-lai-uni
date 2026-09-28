package com.universe.media.infrastructure.storage.cloudinary;

import com.cloudinary.Cloudinary;
import com.cloudinary.Url;
import com.cloudinary.Uploader;
import com.universe.media.application.exceptions.StorageException;
import com.universe.media.application.exceptions.StorageObjectAlreadyExistsException;
import com.universe.media.application.ports.storage.StoredBinaryObject;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CloudinaryStorageAdapterTest {

    private Cloudinary cloudinary;
    private Uploader uploader;
    private CloudinaryStorageAdapter adapter;

    @BeforeEach
    void setUp() {
        cloudinary = mock(Cloudinary.class);
        uploader = mock(Uploader.class);
        when(cloudinary.uploader()).thenReturn(uploader);
        adapter = new CloudinaryStorageAdapter(cloudinary);
    }

    @Test
    @DisplayName("providerId returns 'cloudinary'")
    void shouldReturnCloudinaryProviderId() {
        assertThat(adapter.providerId()).isEqualTo(StorageProviderId.of("cloudinary"));
    }

    @Test
    @DisplayName("store uploads with overwrite=false and returns StoredBinaryObject with secure_url")
    @SuppressWarnings("unchecked")
    void shouldStoreSuccessfullyAndReturnStoredBinaryObject() throws Exception {
        StorageKey key = StorageKey.of("kiemlai/wiki/covers/cover1");
        byte[] payload = "image data".getBytes(StandardCharsets.UTF_8);
        MimeType mimeType = MimeType.of("image/webp");

        Map<String, Object> uploadResult = Map.of(
                "public_id", "kiemlai/wiki/covers/cover1",
                "secure_url", "https://res.cloudinary.com/test-cloud/image/upload/v1/kiemlai/wiki/covers/cover1.webp"
        );
        when(uploader.upload(any(File.class), anyMap())).thenReturn(uploadResult);

        StoredBinaryObject stored = adapter.store(
                key,
                new ByteArrayInputStream(payload),
                payload.length,
                mimeType
        );

        assertThat(stored.location().providerId()).isEqualTo(StorageProviderId.of("cloudinary"));
        assertThat(stored.location().key().value()).isEqualTo("kiemlai/wiki/covers/cover1");
        assertThat(stored.publicUrl()).isEqualTo("https://res.cloudinary.com/test-cloud/image/upload/v1/kiemlai/wiki/covers/cover1.webp");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(any(File.class), paramsCaptor.capture());
        Map<String, Object> params = paramsCaptor.getValue();
        assertThat(params.get("public_id")).isEqualTo("kiemlai/wiki/covers/cover1");
        assertThat(params.get("asset_folder")).isEqualTo("kiemlai/wiki/covers");
        assertThat(params.get("overwrite")).isEqualTo(false);
        assertThat(params.get("resource_type")).isEqualTo("image");
        assertThat(params.get("unique_filename")).isEqualTo(false);
        assertThat(params).doesNotContainKey("use_asset_folder_as_public_id_prefix");
    }

    @Test
    @DisplayName("store sets asset_folder for avatar storage key")
    @SuppressWarnings("unchecked")
    void shouldSetAssetFolderForAvatarKey() throws Exception {
        StorageKey key = StorageKey.of("kiemlai/avatars/user123");
        byte[] payload = "avatar data".getBytes(StandardCharsets.UTF_8);
        MimeType mimeType = MimeType.of("image/png");

        Map<String, Object> uploadResult = Map.of(
                "public_id", "kiemlai/avatars/user123",
                "secure_url", "https://res.cloudinary.com/test-cloud/image/upload/v1/kiemlai/avatars/user123.png"
        );
        when(uploader.upload(any(File.class), anyMap())).thenReturn(uploadResult);

        StoredBinaryObject stored = adapter.store(
                key,
                new ByteArrayInputStream(payload),
                payload.length,
                mimeType
        );

        assertThat(stored.location().key().value()).isEqualTo("kiemlai/avatars/user123");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(any(File.class), paramsCaptor.capture());
        Map<String, Object> params = paramsCaptor.getValue();
        assertThat(params.get("public_id")).isEqualTo("kiemlai/avatars/user123");
        assertThat(params.get("asset_folder")).isEqualTo("kiemlai/avatars");
        assertThat(params.get("overwrite")).isEqualTo(false);
    }

    @Test
    @DisplayName("store sets asset_folder for novel storage key")
    @SuppressWarnings("unchecked")
    void shouldSetAssetFolderForNovelKey() throws Exception {
        StorageKey key = StorageKey.of("kiemlai/novel/cover123");
        byte[] payload = "novel data".getBytes(StandardCharsets.UTF_8);
        MimeType mimeType = MimeType.of("image/jpeg");

        Map<String, Object> uploadResult = Map.of(
                "public_id", "kiemlai/novel/cover123",
                "secure_url", "https://res.cloudinary.com/test-cloud/image/upload/v1/kiemlai/novel/cover123.jpg"
        );
        when(uploader.upload(any(File.class), anyMap())).thenReturn(uploadResult);

        StoredBinaryObject stored = adapter.store(
                key,
                new ByteArrayInputStream(payload),
                payload.length,
                mimeType
        );

        assertThat(stored.location().key().value()).isEqualTo("kiemlai/novel/cover123");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(any(File.class), paramsCaptor.capture());
        Map<String, Object> params = paramsCaptor.getValue();
        assertThat(params.get("public_id")).isEqualTo("kiemlai/novel/cover123");
        assertThat(params.get("asset_folder")).isEqualTo("kiemlai/novel");
        assertThat(params.get("overwrite")).isEqualTo(false);
    }

    @Test
    @DisplayName("store omits asset_folder for no-parent storage key")
    @SuppressWarnings("unchecked")
    void shouldOmitAssetFolderForNoParentKey() throws Exception {
        StorageKey key = StorageKey.of("abc123");
        byte[] payload = "data".getBytes(StandardCharsets.UTF_8);
        MimeType mimeType = MimeType.of("image/jpeg");

        Map<String, Object> uploadResult = Map.of(
                "public_id", "abc123",
                "secure_url", "https://res.cloudinary.com/test-cloud/image/upload/v1/abc123.jpg"
        );
        when(uploader.upload(any(File.class), anyMap())).thenReturn(uploadResult);

        StoredBinaryObject stored = adapter.store(
                key,
                new ByteArrayInputStream(payload),
                payload.length,
                mimeType
        );

        assertThat(stored.location().key().value()).isEqualTo("abc123");

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(uploader).upload(any(File.class), paramsCaptor.capture());
        Map<String, Object> params = paramsCaptor.getValue();
        assertThat(params.get("public_id")).isEqualTo("abc123");
        assertThat(params).doesNotContainKey("asset_folder");
        assertThat(params.get("overwrite")).isEqualTo(false);
        assertThat(params.get("resource_type")).isEqualTo("image");
        assertThat(params.get("unique_filename")).isEqualTo(false);
    }

    @Test
    @DisplayName("store maps already exists / duplicate error to StorageObjectAlreadyExistsException")
    @SuppressWarnings("unchecked")
    void shouldMapDuplicateToStorageObjectAlreadyExistsException() throws Exception {
        StorageKey key = StorageKey.of("kiemlai/wiki/covers/cover1");
        byte[] payload = "image data".getBytes(StandardCharsets.UTF_8);

        when(uploader.upload(any(File.class), anyMap()))
                .thenThrow(new IOException("Resource already exists with this public_id"));

        assertThatThrownBy(() -> adapter.store(key, new ByteArrayInputStream(payload), payload.length, MimeType.of("image/webp")))
                .isInstanceOf(StorageObjectAlreadyExistsException.class);
    }

    @Test
    @DisplayName("open throws StorageException informing that streaming is not supported")
    void shouldThrowWhenOpenCalled() {
        StorageKey key = StorageKey.of("kiemlai/wiki/covers/cover1");
        assertThatThrownBy(() -> adapter.open(key))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Direct byte streaming is not supported for Cloudinary");
    }

    @Test
    @DisplayName("openRange throws StorageException informing that streaming is not supported")
    void shouldThrowWhenOpenRangeCalled() {
        StorageKey key = StorageKey.of("kiemlai/wiki/covers/cover1");
        assertThatThrownBy(() -> adapter.openRange(key, 0, 100))
                .isInstanceOf(StorageException.class)
                .hasMessageContaining("Direct byte range streaming is not supported for Cloudinary");
    }

    @Test
    @DisplayName("delete invokes Cloudinary destroy with invalidate=true")
    @SuppressWarnings("unchecked")
    void shouldDeleteWithInvalidation() throws Exception {
        StorageKey key = StorageKey.of("kiemlai/wiki/covers/cover1");

        when(uploader.destroy(eq("kiemlai/wiki/covers/cover1"), anyMap()))
                .thenReturn(Map.of("result", "ok"));

        adapter.delete(key);

        ArgumentCaptor<Map<String, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(uploader).destroy(eq("kiemlai/wiki/covers/cover1"), paramsCaptor.capture());
        assertThat(paramsCaptor.getValue().get("invalidate")).isEqualTo(true);
    }

    @Test
    @DisplayName("supports returns true for cloudinary and false for others")
    void shouldSupportOnlyCloudinaryProvider() {
        assertThat(adapter.supports(StorageProviderId.of("cloudinary"))).isTrue();
        assertThat(adapter.supports(StorageProviderId.of("local"))).isFalse();
        assertThat(adapter.supports(StorageProviderId.of("r2"))).isFalse();
    }

    @Test
    @DisplayName("generateVariantUrl creates transformation URL with target width")
    void shouldGenerateVariantUrl() {
        Cloudinary realCloudinary = new Cloudinary("cloudinary://123456789012345:abcdefghijklmnopqrstuvwxyza@test-cloud");
        when(cloudinary.url()).thenReturn(realCloudinary.url());

        String variantUrl = adapter.generateVariantUrl("kiemlai/wiki/covers/cover1", 300);

        assertThat(variantUrl).contains("c_scale,w_300").contains("kiemlai/wiki/covers/cover1");
    }

    @Test
    @DisplayName("generateVariantUrl via ImageVariantPublicUrlPort generates validated URI")
    void shouldGenerateVariantUriFromLocationAndSpec() {
        Cloudinary realCloudinary = new Cloudinary("cloudinary://123456789012345:abcdefghijklmnopqrstuvwxyza@test-cloud");
        when(cloudinary.url()).thenReturn(realCloudinary.url());

        java.net.URI uri = adapter.generateVariantUrl(
                com.universe.media.domain.StorageLocation.of("cloudinary", "kiemlai/wiki/covers/cover1"),
                com.universe.media.domain.ImageVariantSpec.of(400)
        );

        assertThat(uri).isNotNull();
        assertThat(uri.getScheme()).isEqualTo("https");
        assertThat(uri.toString()).contains("c_scale,w_400").contains("kiemlai/wiki/covers/cover1");
    }
}
