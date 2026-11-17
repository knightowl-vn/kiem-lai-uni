package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaPublicImageBatchDeliveryQueryPort.MediaPublicImageBatchDeliveryRow;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaType;
import com.universe.media.domain.MediaVisibility;
import com.universe.media.domain.StorageProviderId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MediaPublicImageBatchDeliveryQueryPersistenceAdapter Unit Tests")
class MediaPublicImageBatchDeliveryQueryPersistenceAdapterTest {

    @Mock
    private SpringDataMediaPublicImageBatchDeliveryQueryRepository repository;

    private MediaPublicImageBatchDeliveryQueryPersistenceAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new MediaPublicImageBatchDeliveryQueryPersistenceAdapter(repository);
    }

    private MediaPublicImageBatchDeliveryProjection createProjection(
            String assetId,
            String versionId,
            String variantId,
            String versionUrl,
            String variantUrl
    ) {
        MediaPublicImageBatchDeliveryProjection proj = mock(MediaPublicImageBatchDeliveryProjection.class);
        when(proj.getAssetId()).thenReturn(assetId);
        when(proj.getStatus()).thenReturn("ACTIVE");
        when(proj.getVisibility()).thenReturn("PUBLIC");
        when(proj.getMediaType()).thenReturn("IMAGE");
        when(proj.getCurrentVersionNumber()).thenReturn(1);
        when(proj.getVersionId()).thenReturn(versionId);
        when(proj.getVersionNumber()).thenReturn(1);
        when(proj.getStorageProviderId()).thenReturn("cloudinary");
        when(proj.getVersionPublicUrl()).thenReturn(versionUrl);
        when(proj.getVariantId()).thenReturn(variantId);
        when(proj.getVariantPublicUrl()).thenReturn(variantUrl);
        return proj;
    }

    @Test
    @DisplayName("Empty or null input returns empty list without calling repository")
    void shouldReturnEmptyListForEmptyOrNullInput() {
        assertThat(adapter.findEligibleBatchDeliveryRows(null, "w300")).isEmpty();
        assertThat(adapter.findEligibleBatchDeliveryRows(Collections.emptyList(), "w300")).isEmpty();
        assertThat(adapter.findEligibleBatchDeliveryRows(Arrays.asList(null, null), "w300")).isEmpty();

        verify(repository, never()).findBatchDeliveryProjections(any(), any());
    }

    @Test
    @DisplayName("Normalizes variantKey and defaults null to empty string")
    void shouldNormalizeVariantKey() {
        UUID assetId = UUID.randomUUID();
        when(repository.findBatchDeliveryProjections(any(), eq("w600")))
                .thenReturn(Collections.emptyList());

        adapter.findEligibleBatchDeliveryRows(List.of(assetId), "  W600  ");
        verify(repository).findBatchDeliveryProjections(eq(List.of(assetId.toString())), eq("w600"));

        when(repository.findBatchDeliveryProjections(any(), eq("")))
                .thenReturn(Collections.emptyList());

        adapter.findEligibleBatchDeliveryRows(List.of(assetId), null);
        verify(repository).findBatchDeliveryProjections(eq(List.of(assetId.toString())), eq(""));
    }

    @Test
    @DisplayName("Maps projection fields correctly to MediaPublicImageBatchDeliveryRow")
    void shouldMapProjectionFieldsToRow() {
        UUID assetId = UUID.randomUUID();
        UUID versionId = UUID.randomUUID();
        UUID variantId = UUID.randomUUID();
        String versionUrl = "https://res.cloudinary.com/kiemlai/image/upload/v1/source.jpg";
        String variantUrl = "https://res.cloudinary.com/kiemlai/image/upload/c_scale,w_300/thumb.webp";

        MediaPublicImageBatchDeliveryProjection proj = createProjection(
                assetId.toString(), versionId.toString(), variantId.toString(), versionUrl, variantUrl
        );

        when(repository.findBatchDeliveryProjections(any(), anyString()))
                .thenReturn(List.of(proj));

        List<MediaPublicImageBatchDeliveryRow> rows =
                adapter.findEligibleBatchDeliveryRows(List.of(assetId), "w300");

        assertThat(rows).hasSize(1);
        MediaPublicImageBatchDeliveryRow row = rows.get(0);
        assertThat(row.assetId()).isEqualTo(assetId);
        assertThat(row.status()).isEqualTo(MediaAssetStatus.ACTIVE);
        assertThat(row.visibility()).isEqualTo(MediaVisibility.PUBLIC);
        assertThat(row.mediaType()).isEqualTo(MediaType.IMAGE);
        assertThat(row.currentVersionNumber()).isEqualTo(1);
        assertThat(row.versionId()).isEqualTo(versionId);
        assertThat(row.versionNumber()).isEqualTo(1);
        assertThat(row.storageProviderId()).isEqualTo(StorageProviderId.of("cloudinary"));
        assertThat(row.versionPublicUrl()).isEqualTo(versionUrl);
        assertThat(row.variantId()).isEqualTo(variantId);
        assertThat(row.variantPublicUrl()).isEqualTo(variantUrl);
        assertThat(row.isVariantPresent()).isTrue();
    }

    @Test
    @DisplayName("Single query execution for batches up to 500 items (1, 12, 100)")
    void shouldExecuteExactlyOneQueryForBatchesWithinChunkLimit() {
        List<UUID> ids100 = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ids100.add(UUID.randomUUID());
        }

        when(repository.findBatchDeliveryProjections(any(), anyString()))
                .thenReturn(Collections.emptyList());

        adapter.findEligibleBatchDeliveryRows(ids100, "w300");

        verify(repository, times(1)).findBatchDeliveryProjections(any(), anyString());
    }

    @Test
    @DisplayName("Partitions inputs exceeding 500 items into chunks without dropping IDs")
    void shouldPartitionInputsExceedingChunkLimit() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 1050; i++) {
            ids.add(UUID.randomUUID());
        }

        when(repository.findBatchDeliveryProjections(any(), anyString()))
                .thenReturn(Collections.emptyList());

        adapter.findEligibleBatchDeliveryRows(ids, "w300");

        // 1050 items should be split into 3 chunks: 500, 500, 50
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(repository, times(3)).findBatchDeliveryProjections(captor.capture(), eq("w300"));

        List<Collection<String>> allChunks = captor.getAllValues();
        assertThat(allChunks.get(0)).hasSize(500);
        assertThat(allChunks.get(1)).hasSize(500);
        assertThat(allChunks.get(2)).hasSize(50);
    }
}
