package com.universe.media.application.image;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.domain.ContentHash;
import com.universe.media.domain.ContentHashStatus;
import com.universe.media.domain.ImageVariantSpec;
import com.universe.media.domain.MediaAsset;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaAssetVersion;
import com.universe.media.domain.MediaImageVariant;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.MimeType;
import com.universe.media.domain.StorageKey;
import com.universe.media.domain.StorageLocation;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResolveMediaPublicImageDeliveryUrlUseCase Unit Tests")
class ResolveMediaPublicImageDeliveryUrlUseCaseTest {

    private static final UUID ASSET_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID VERSION_V1_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID VERSION_V2_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final StorageProviderId CLOUDINARY_PROVIDER = StorageProviderId.of("cloudinary");
    private static final StorageLocation CLOUDINARY_LOCATION = StorageLocation.of(CLOUDINARY_PROVIDER, StorageKey.of("images/backdrop.jpg"));
    private static final StorageLocation LOCAL_LOCATION = StorageLocation.of(StorageProviderId.of("local"), StorageKey.of("images/backdrop.jpg"));
    private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
    private static final String CLOUDINARY_ORIGINAL_URL = "https://res.cloudinary.com/kiemlai/image/upload/v12345/screen/backdrop/abc.jpg";
    private static final String CLOUDINARY_VARIANT_W600_URL = "https://res.cloudinary.com/kiemlai/image/upload/c_scale,w_600/screen/thumbnail/def.jpg";

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Mock
    private MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort;

    private ResolveMediaPublicImageDeliveryUrlUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ResolveMediaPublicImageDeliveryUrlUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                mediaImageVariantRepositoryPort
        );
    }

    private MediaAsset createAsset(MediaType type, MediaAssetStatus status, MediaVisibility visibility, int currentVersion) {
        MediaAsset asset = MediaAsset.registerInitial(ASSET_ID, type, visibility, NOW);
        if (currentVersion > 1) {
            for (int i = 2; i <= currentVersion; i++) {
                asset.registerNextVersion(NOW);
            }
        }
        if (status == MediaAssetStatus.ARCHIVED) {
            asset.archive(NOW);
        } else if (status == MediaAssetStatus.DELETED) {
            asset.markDeleted(NOW);
        }
        return asset;
    }

    private MediaAssetVersion createVersion(UUID versionId, int versionNumber, StorageLocation location, String publicUrl) {
        return MediaAssetVersion.rehydrate(
                versionId,
                ASSET_ID,
                versionNumber,
                location,
                publicUrl,
                ContentHash.of("a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e"),
                ContentHashStatus.SERVER_COMPUTED,
                MimeType.of("image/jpeg"),
                102400L,
                "backdrop.jpg",
                NOW
        );
    }

    @Test
    @DisplayName("Eligible source image: ACTIVE + PUBLIC + IMAGE + Cloudinary returns direct public URL")
    void shouldResolveEligibleSourceImagePublicUrl() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        MediaAssetVersion version = createVersion(VERSION_V1_ID, 1, CLOUDINARY_LOCATION, CLOUDINARY_ORIGINAL_URL);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));

        Optional<String> result = useCase.execute(ASSET_ID, (String) null);

        assertThat(result).isPresent().contains(CLOUDINARY_ORIGINAL_URL);
    }

    @Test
    @DisplayName("Eligible image variant: ACTIVE + PUBLIC + IMAGE + current version variant returns direct variant URL")
    void shouldResolveEligibleImageVariantPublicUrl() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        MediaAssetVersion version = createVersion(VERSION_V1_ID, 1, CLOUDINARY_LOCATION, CLOUDINARY_ORIGINAL_URL);
        MediaImageVariant variant = MediaImageVariant.createExternal(
                UUID.randomUUID(),
                VERSION_V1_ID,
                ImageVariantSpec.of(600),
                CLOUDINARY_VARIANT_W600_URL,
                MimeType.of("image/jpeg"),
                NOW
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_V1_ID, "w600")).thenReturn(Optional.of(variant));

        Optional<String> result = useCase.execute(ASSET_ID, "w600");

        assertThat(result).isPresent().contains(CLOUDINARY_VARIANT_W600_URL);
    }

    @Test
    @DisplayName("Integer targetWidth overload correctly maps to canonical variantKey w600")
    void shouldResolveWithIntegerTargetWidth() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        MediaAssetVersion version = createVersion(VERSION_V1_ID, 1, CLOUDINARY_LOCATION, CLOUDINARY_ORIGINAL_URL);
        MediaImageVariant variant = MediaImageVariant.createExternal(
                UUID.randomUUID(),
                VERSION_V1_ID,
                ImageVariantSpec.of(600),
                CLOUDINARY_VARIANT_W600_URL,
                MimeType.of("image/jpeg"),
                NOW
        );

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_V1_ID, "w600")).thenReturn(Optional.of(variant));

        Optional<String> result = useCase.execute(ASSET_ID, 600);

        assertThat(result).isPresent().contains(CLOUDINARY_VARIANT_W600_URL);
    }

    @Test
    @DisplayName("Null asset ID returns empty")
    void shouldReturnEmptyForNullAssetId() {
        Optional<String> result = useCase.execute(null, (String) null);
        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Unknown asset returns empty")
    void shouldReturnEmptyForUnknownAsset() {
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.empty());

        Optional<String> result = useCase.execute(ASSET_ID, (String) null);

        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = MediaVisibility.class, names = {"PRIVATE", "RESTRICTED"})
    @DisplayName("Non-PUBLIC visibility fails closed and returns empty")
    void shouldReturnEmptyForNonPublicVisibility(MediaVisibility visibility) {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, visibility, 1);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        Optional<String> result = useCase.execute(ASSET_ID, (String) null);

        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = MediaAssetStatus.class, names = {"ARCHIVED", "DELETED"})
    @DisplayName("Non-ACTIVE status fails closed and returns empty")
    void shouldReturnEmptyForNonActiveStatus(MediaAssetStatus status) {
        MediaAsset asset = createAsset(MediaType.IMAGE, status, MediaVisibility.PUBLIC, 1);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        Optional<String> result = useCase.execute(ASSET_ID, (String) null);

        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = MediaType.class, names = {"VIDEO", "AUDIO", "DOCUMENT"})
    @DisplayName("Non-IMAGE media type fails closed and returns empty")
    void shouldReturnEmptyForNonImageMediaType(MediaType mediaType) {
        MediaAsset asset = createAsset(mediaType, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));

        Optional<String> result = useCase.execute(ASSET_ID, (String) null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Storage provider not Cloudinary (e.g. local or b2/r2) fails closed and returns empty")
    void shouldReturnEmptyWhenStorageProviderNotCloudinary() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        MediaAssetVersion version = createVersion(VERSION_V1_ID, 1, LOCAL_LOCATION, "https://local-domain.com/image.jpg");

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));

        Optional<String> result = useCase.execute(ASSET_ID, (String) null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Missing variant under current version returns empty (preserves canonical fallback)")
    void shouldReturnEmptyWhenVariantNotFound() {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        MediaAssetVersion version = createVersion(VERSION_V1_ID, 1, CLOUDINARY_LOCATION, CLOUDINARY_ORIGINAL_URL);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_V1_ID, "w600")).thenReturn(Optional.empty());

        Optional<String> result = useCase.execute(ASSET_ID, "w600");

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Stale variant from older version is not selected when asset has newer version")
    void shouldNotSelectStaleVariantFromOlderVersion() {
        // Asset is at version 2
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 2);
        MediaAssetVersion currentVersionV2 = createVersion(VERSION_V2_ID, 2, CLOUDINARY_LOCATION, CLOUDINARY_ORIGINAL_URL);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 2)).thenReturn(Optional.of(currentVersionV2));
        // Variant w600 was not generated for version 2 yet, so findByVersionIdAndVariantKey on V2 returns empty
        when(mediaImageVariantRepositoryPort.findByVersionIdAndVariantKey(VERSION_V2_ID, "w600")).thenReturn(Optional.empty());

        Optional<String> result = useCase.execute(ASSET_ID, "w600");

        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://res.cloudinary.com/kiemlai/image/upload/sample.jpg",   // insecure HTTP scheme
            "https://attacker.example.com/malicious.jpg",                   // non-Cloudinary host
            "https://res.cloudinary.com.attacker.com/sample.jpg",           // deceptive host prefix
            "not-a-valid-uri-string:::",                                   // malformed URI
            ""                                                              // empty string
    })
    @DisplayName("Untrusted or non-HTTPS URLs fail closed and return empty")
    void shouldRejectUntrustedOrNonHttpsUrls(String untrustedUrl) {
        MediaAsset asset = createAsset(MediaType.IMAGE, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 1);
        MediaAssetVersion version = createVersion(VERSION_V1_ID, 1, CLOUDINARY_LOCATION, untrustedUrl);

        when(mediaAssetRepositoryPort.findById(ASSET_ID)).thenReturn(Optional.of(asset));
        when(mediaAssetVersionRepositoryPort.findByAssetIdAndVersionNumber(ASSET_ID, 1)).thenReturn(Optional.of(version));

        Optional<String> result = useCase.execute(ASSET_ID, (String) null);

        assertThat(result).isEmpty();
    }
}
