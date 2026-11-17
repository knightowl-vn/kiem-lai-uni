package com.universe.media.application.image;

import com.universe.media.application.ports.MediaAssetRepositoryPort;
import com.universe.media.application.ports.MediaAssetVersionRepositoryPort;
import com.universe.media.application.ports.MediaImageVariantRepositoryPort;
import com.universe.media.application.ports.MediaPublicImageBatchDeliveryQueryPort;
import com.universe.media.application.ports.MediaPublicImageBatchDeliveryQueryPort.MediaPublicImageBatchDeliveryRow;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ResolveMediaPublicImageDeliveryUrlUseCase Batch Unit Tests")
class ResolveMediaPublicImageDeliveryUrlUseCaseBatchTest {

    private static final StorageProviderId CLOUDINARY_PROVIDER = StorageProviderId.of("cloudinary");
    private static final String CLOUDINARY_CANONICAL_SOURCE_URL =
            "https://res.cloudinary.com/kiemlai/image/upload/v12345/screen/poster/abc.jpg";
    private static final String CLOUDINARY_CANONICAL_W300_URL =
            "https://res.cloudinary.com/kiemlai/image/upload/c_scale,w_300/screen/poster/abc.webp";

    @Mock
    private MediaAssetRepositoryPort mediaAssetRepositoryPort;

    @Mock
    private MediaAssetVersionRepositoryPort mediaAssetVersionRepositoryPort;

    @Mock
    private MediaImageVariantRepositoryPort mediaImageVariantRepositoryPort;

    @Mock
    private MediaPublicImageBatchDeliveryQueryPort queryPort;

    private ResolveMediaPublicImageDeliveryUrlUseCase useCase;

    @BeforeEach
    void setUp() {
        useCase = new ResolveMediaPublicImageDeliveryUrlUseCase(
                mediaAssetRepositoryPort,
                mediaAssetVersionRepositoryPort,
                mediaImageVariantRepositoryPort,
                queryPort
        );
    }

    private MediaPublicImageBatchDeliveryRow createEligibleRow(
            UUID assetId,
            UUID versionId,
            UUID variantId,
            String versionUrl,
            String variantUrl
    ) {
        return new MediaPublicImageBatchDeliveryRow(
                assetId,
                MediaAssetStatus.ACTIVE,
                MediaVisibility.PUBLIC,
                MediaType.IMAGE,
                1,
                versionId,
                1,
                CLOUDINARY_PROVIDER,
                versionUrl,
                variantId,
                variantUrl
        );
    }

    // =========================================================================
    // 6.1 Eligibility Tests
    // =========================================================================

    @Test
    @DisplayName("ACTIVE + PUBLIC + IMAGE with Cloudinary resolves source URL when no variant requested")
    void shouldResolveEligibleBatchSourceImages() {
        UUID assetId1 = UUID.randomUUID();
        UUID assetId2 = UUID.randomUUID();

        MediaPublicImageBatchDeliveryRow row1 = createEligibleRow(
                assetId1, UUID.randomUUID(), null, CLOUDINARY_CANONICAL_SOURCE_URL, null
        );
        MediaPublicImageBatchDeliveryRow row2 = createEligibleRow(
                assetId2, UUID.randomUUID(), null, CLOUDINARY_CANONICAL_SOURCE_URL, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq(null)))
                .thenReturn(List.of(row1, row2));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId1, assetId2), (String) null, false);

        assertThat(result)
                .hasSize(2)
                .containsEntry(assetId1, CLOUDINARY_CANONICAL_SOURCE_URL)
                .containsEntry(assetId2, CLOUDINARY_CANONICAL_SOURCE_URL);
    }

    @Test
    @DisplayName("ACTIVE + PUBLIC + IMAGE with Cloudinary resolves variant URL when variant is present")
    void shouldResolveEligibleBatchVariantImages() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        MediaPublicImageBatchDeliveryRow row = createEligibleRow(
                assetId, versionId, variantId, CLOUDINARY_CANONICAL_SOURCE_URL, CLOUDINARY_CANONICAL_W300_URL
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq("w300")))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), 300, false);

        assertThat(result)
                .hasSize(1)
                .containsEntry(assetId, CLOUDINARY_CANONICAL_W300_URL);
    }

    @ParameterizedTest
    @EnumSource(value = MediaVisibility.class, names = {"PRIVATE", "RESTRICTED"})
    @DisplayName("Non-PUBLIC visibility fails closed and is excluded from batch result")
    void shouldExcludeNonPublicVisibility(MediaVisibility visibility) {
        UUID assetId = UUID.randomUUID();
        MediaPublicImageBatchDeliveryRow row = new MediaPublicImageBatchDeliveryRow(
                assetId, MediaAssetStatus.ACTIVE, visibility, MediaType.IMAGE,
                1, UUID.randomUUID(), 1, CLOUDINARY_PROVIDER, CLOUDINARY_CANONICAL_SOURCE_URL, null, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq(null)))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), (Integer) null);

        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = MediaAssetStatus.class, names = {"ARCHIVED", "DELETED"})
    @DisplayName("Non-ACTIVE status fails closed and is excluded from batch result")
    void shouldExcludeNonActiveStatus(MediaAssetStatus status) {
        UUID assetId = UUID.randomUUID();
        MediaPublicImageBatchDeliveryRow row = new MediaPublicImageBatchDeliveryRow(
                assetId, status, MediaVisibility.PUBLIC, MediaType.IMAGE,
                1, UUID.randomUUID(), 1, CLOUDINARY_PROVIDER, CLOUDINARY_CANONICAL_SOURCE_URL, null, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq(null)))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), (Integer) null);

        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = MediaType.class, names = {"VIDEO", "AUDIO", "DOCUMENT"})
    @DisplayName("Non-IMAGE media type fails closed and is excluded from batch result")
    void shouldExcludeNonImageMediaType(MediaType mediaType) {
        UUID assetId = UUID.randomUUID();
        MediaPublicImageBatchDeliveryRow row = new MediaPublicImageBatchDeliveryRow(
                assetId, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, mediaType,
                1, UUID.randomUUID(), 1, CLOUDINARY_PROVIDER, CLOUDINARY_CANONICAL_SOURCE_URL, null, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq(null)))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), (Integer) null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Outdated version (versionNumber != currentVersionNumber) fails closed")
    void shouldExcludeOutdatedVersion() {
        UUID assetId = UUID.randomUUID();
        MediaPublicImageBatchDeliveryRow row = new MediaPublicImageBatchDeliveryRow(
                assetId, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, MediaType.IMAGE,
                2, UUID.randomUUID(), 1, CLOUDINARY_PROVIDER, CLOUDINARY_CANONICAL_SOURCE_URL, null, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq(null)))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), (Integer) null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Storage provider not Cloudinary (e.g. b2 or local) fails closed")
    void shouldExcludeNonCloudinaryProvider() {
        UUID assetId = UUID.randomUUID();
        MediaPublicImageBatchDeliveryRow row = new MediaPublicImageBatchDeliveryRow(
                assetId, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, MediaType.IMAGE,
                1, UUID.randomUUID(), 1, StorageProviderId.of("b2"),
                "https://f000.backblazeb2.com/file/kiemlai/img.jpg", null, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq(null)))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), (Integer) null);

        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://res.cloudinary.com/kiemlai/image/upload/sample.jpg",   // insecure HTTP
            "https://attacker.example.com/malicious.jpg",                   // untrusted host
            "https://res.cloudinary.com.attacker.com/sample.jpg",           // deceptive prefix
            "not-a-valid-uri:::",                                          // invalid URI
            ""                                                              // blank
    })
    @DisplayName("Untrusted or non-HTTPS URLs fail closed and are excluded from result")
    void shouldExcludeUntrustedOrNonHttpsUrls(String untrustedUrl) {
        UUID assetId = UUID.randomUUID();
        MediaPublicImageBatchDeliveryRow row = createEligibleRow(
                assetId, UUID.randomUUID(), null, untrustedUrl, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq(null)))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), (Integer) null);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Missing preferred variant with fallbackToSource = false produces no URL")
    void shouldExcludeWhenVariantMissingAndFallbackDisabled() {
        UUID assetId = UUID.randomUUID();
        MediaPublicImageBatchDeliveryRow row = createEligibleRow(
                assetId, UUID.randomUUID(), null, CLOUDINARY_CANONICAL_SOURCE_URL, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq("w600")))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), 600, false);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Missing preferred variant with fallbackToSource = true falls back to source URL")
    void shouldFallbackToSourceWhenVariantMissingAndFallbackEnabled() {
        UUID assetId = UUID.randomUUID();
        MediaPublicImageBatchDeliveryRow row = createEligibleRow(
                assetId, UUID.randomUUID(), null, CLOUDINARY_CANONICAL_SOURCE_URL, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq("w600")))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), 600, true);

        assertThat(result)
                .hasSize(1)
                .containsEntry(assetId, CLOUDINARY_CANONICAL_SOURCE_URL);
    }

    @Test
    @DisplayName("Variant exists with null public URL (physical variant) falls back to source URL when fallbackToSource is true")
    void shouldFallbackToSourceWhenExistingPhysicalVariantHasNullPublicUrlAndFallbackEnabled() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        // Physical variant row exists (variantId != null) but publicUrl is null
        MediaPublicImageBatchDeliveryRow row = createEligibleRow(
                assetId, versionId, variantId, CLOUDINARY_CANONICAL_SOURCE_URL, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq("w300")))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), 300, true);

        assertThat(result)
                .hasSize(1)
                .containsEntry(assetId, CLOUDINARY_CANONICAL_SOURCE_URL);
    }

    @Test
    @DisplayName("Variant exists with null public URL (physical variant) produces no URL when fallbackToSource is false")
    void shouldExcludeWhenExistingPhysicalVariantHasNullPublicUrlAndFallbackDisabled() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        // Physical variant row exists (variantId != null) but publicUrl is null
        MediaPublicImageBatchDeliveryRow row = createEligibleRow(
                assetId, versionId, variantId, CLOUDINARY_CANONICAL_SOURCE_URL, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq("w300")))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), 300, false);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Variant exists with blank public URL falls back to source URL when fallbackToSource is true")
    void shouldFallbackToSourceWhenExistingVariantHasBlankPublicUrlAndFallbackEnabled() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        MediaPublicImageBatchDeliveryRow row = createEligibleRow(
                assetId, versionId, variantId, CLOUDINARY_CANONICAL_SOURCE_URL, "   "
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq("w300")))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), 300, true);

        assertThat(result)
                .hasSize(1)
                .containsEntry(assetId, CLOUDINARY_CANONICAL_SOURCE_URL);
    }

    @Test
    @DisplayName("Variant exists but has invalid nonblank URL fails closed and does NOT fall back to source URL")
    void shouldFailClosedWhenExistingVariantHasInvalidUrlEvenIfFallbackEnabled() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        // Variant row exists (variantId != null) but has an untrusted/invalid nonblank URL
        MediaPublicImageBatchDeliveryRow row = createEligibleRow(
                assetId, versionId, variantId, CLOUDINARY_CANONICAL_SOURCE_URL, "https://evil.com/variant.jpg"
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq("w300")))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), 300, true);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("Variant exists with invalid nonblank URL produces no URL when fallbackToSource is false")
    void shouldFailClosedWhenExistingVariantHasInvalidUrlAndFallbackDisabled() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();

        MediaPublicImageBatchDeliveryRow row = createEligibleRow(
                assetId, versionId, variantId, CLOUDINARY_CANONICAL_SOURCE_URL, "https://evil.com/variant.jpg"
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq("w300")))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(assetId), 300, false);

        assertThat(result).isEmpty();
    }

    // =========================================================================
    // 6.2 Batch Input Tests
    // =========================================================================

    @Test
    @DisplayName("Empty collection returns empty map without invoking query port")
    void shouldReturnEmptyMapForEmptyInputWithoutQuery() {
        Map<UUID, String> result = useCase.executeBatch(Collections.emptyList(), 300);

        assertThat(result).isEmpty();
        verify(queryPort, never()).findEligibleBatchDeliveryRows(any(), any());
    }

    @Test
    @DisplayName("Null collection returns empty map without invoking query port")
    void shouldReturnEmptyMapForNullInputWithoutQuery() {
        Map<UUID, String> result = useCase.executeBatch((Collection<UUID>) null, 300);

        assertThat(result).isEmpty();
        verify(queryPort, never()).findEligibleBatchDeliveryRows(any(), any());
    }

    @Test
    @DisplayName("Collection containing only nulls returns empty map without invoking query port")
    void shouldReturnEmptyMapForCollectionOfNullsWithoutQuery() {
        List<UUID> listWithNulls = Arrays.asList(null, null);

        Map<UUID, String> result = useCase.executeBatch(listWithNulls, 300);

        assertThat(result).isEmpty();
        verify(queryPort, never()).findEligibleBatchDeliveryRows(any(), any());
    }

    @Test
    @DisplayName("Duplicate IDs in input are deduplicated before querying")
    void shouldDeduplicateIdsBeforeQuery() {
        UUID assetId = UUID.randomUUID();
        List<UUID> duplicates = List.of(assetId, assetId, assetId);

        MediaPublicImageBatchDeliveryRow row = createEligibleRow(
                assetId, UUID.randomUUID(), null, CLOUDINARY_CANONICAL_SOURCE_URL, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq(null)))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(duplicates, (Integer) null);

        assertThat(result).hasSize(1).containsEntry(assetId, CLOUDINARY_CANONICAL_SOURCE_URL);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<UUID>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(queryPort, times(1)).findEligibleBatchDeliveryRows(captor.capture(), eq(null));
        assertThat(captor.getValue()).containsExactly(assetId);
    }

    @Test
    @DisplayName("Mixed eligible and ineligible images: only eligible assets are returned")
    void shouldFilterMixedEligibleAndIneligibleImages() {
        UUID eligibleId = UUID.randomUUID();
        UUID ineligibleId = UUID.randomUUID();

        MediaPublicImageBatchDeliveryRow eligibleRow = createEligibleRow(
                eligibleId, UUID.randomUUID(), null, CLOUDINARY_CANONICAL_SOURCE_URL, null
        );
        MediaPublicImageBatchDeliveryRow privateRow = new MediaPublicImageBatchDeliveryRow(
                ineligibleId, MediaAssetStatus.ACTIVE, MediaVisibility.PRIVATE, MediaType.IMAGE,
                1, UUID.randomUUID(), 1, CLOUDINARY_PROVIDER, CLOUDINARY_CANONICAL_SOURCE_URL, null, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq(null)))
                .thenReturn(List.of(eligibleRow, privateRow));

        Map<UUID, String> result = useCase.executeBatch(List.of(eligibleId, ineligibleId), (Integer) null);

        assertThat(result)
                .hasSize(1)
                .containsKey(eligibleId)
                .doesNotContainKey(ineligibleId);
    }

    @Test
    @DisplayName("Deterministic mapping: returned URLs correctly map to their corresponding asset IDs")
    void shouldDeterministicallyMapUrlsToAssetIds() {
        UUID id1 = UUID.randomUUID();
        UUID id2 = UUID.randomUUID();
        String url1 = "https://res.cloudinary.com/kiemlai/image/upload/v1/poster1.webp";
        String url2 = "https://res.cloudinary.com/kiemlai/image/upload/v1/poster2.webp";

        MediaPublicImageBatchDeliveryRow row1 = createEligibleRow(id1, UUID.randomUUID(), null, url1, null);
        MediaPublicImageBatchDeliveryRow row2 = createEligibleRow(id2, UUID.randomUUID(), null, url2, null);

        when(queryPort.findEligibleBatchDeliveryRows(any(), eq(null)))
                .thenReturn(List.of(row1, row2));

        Map<UUID, String> result = useCase.executeBatch(List.of(id1, id2), (Integer) null);

        assertThat(result.get(id1)).isEqualTo(url1);
        assertThat(result.get(id2)).isEqualTo(url2);
    }

    // =========================================================================
    // 6.3 Query Efficiency Tests (1, 12, 100 assets)
    // =========================================================================

    @Test
    @DisplayName("Query bounded: 1 asset invokes query port exactly once")
    void shouldInvokeQueryPortExactlyOnceFor1Asset() {
        UUID id = UUID.randomUUID();
        MediaPublicImageBatchDeliveryRow row = createEligibleRow(
                id, UUID.randomUUID(), null, CLOUDINARY_CANONICAL_SOURCE_URL, null
        );

        when(queryPort.findEligibleBatchDeliveryRows(any(), anyString()))
                .thenReturn(List.of(row));

        Map<UUID, String> result = useCase.executeBatch(List.of(id), "w300", true);

        assertThat(result).hasSize(1);
        verify(queryPort, times(1)).findEligibleBatchDeliveryRows(any(), eq("w300"));
    }

    @Test
    @DisplayName("Query bounded: 12 assets invoke query port exactly once (no N+1 loop)")
    void shouldInvokeQueryPortExactlyOnceFor12Assets() {
        List<UUID> ids = new ArrayList<>();
        List<MediaPublicImageBatchDeliveryRow> rows = new ArrayList<>();

        for (int i = 0; i < 12; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            rows.add(createEligibleRow(id, UUID.randomUUID(), null, CLOUDINARY_CANONICAL_SOURCE_URL, null));
        }

        when(queryPort.findEligibleBatchDeliveryRows(any(), anyString()))
                .thenReturn(rows);

        Map<UUID, String> result = useCase.executeBatch(ids, "w300", true);

        assertThat(result).hasSize(12);
        verify(queryPort, times(1)).findEligibleBatchDeliveryRows(any(), eq("w300"));
    }

    @Test
    @DisplayName("Query bounded: 100 assets invoke query port exactly once (no N+1 loop)")
    void shouldInvokeQueryPortExactlyOnceFor100Assets() {
        List<UUID> ids = new ArrayList<>();
        List<MediaPublicImageBatchDeliveryRow> rows = new ArrayList<>();

        for (int i = 0; i < 100; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            rows.add(createEligibleRow(id, UUID.randomUUID(), null, CLOUDINARY_CANONICAL_SOURCE_URL, null));
        }

        when(queryPort.findEligibleBatchDeliveryRows(any(), anyString()))
                .thenReturn(rows);

        Map<UUID, String> result = useCase.executeBatch(ids, "w300", true);

        assertThat(result).hasSize(100);
        verify(queryPort, times(1)).findEligibleBatchDeliveryRows(any(), eq("w300"));
    }
}
