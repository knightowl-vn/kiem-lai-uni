package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaAssetContentDeliveryQueryPort.MediaAssetContentDeliverySnapshot;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetContentDeliveryQueryPersistenceAdapterTest {

    private static final UUID ASSET_ID = UUID.fromString("c2100000-0000-0000-0000-000000000001");
    private static final UUID VERSION_ID = UUID.fromString("c2100000-0000-0000-0000-000000000002");
    private static final String HASH =
            "a591a6d40bf420404a011733cfb7b190d62c65bf0bcda32b57b277d9ad9f146e";

    @Mock
    private SpringDataMediaAssetContentDeliveryQueryRepository repository;

    @Mock
    private MediaAssetContentDeliveryProjection projection;

    @Test
    void mapsCompleteInternalDeliverySnapshot() {
        when(repository.findContentDelivery(ASSET_ID.toString())).thenReturn(Optional.of(projection));
        when(projection.getAssetId()).thenReturn(ASSET_ID.toString());
        when(projection.getStatus()).thenReturn("ACTIVE");
        when(projection.getVisibility()).thenReturn("PUBLIC");
        when(projection.getCurrentVersionNumber()).thenReturn(3);
        when(projection.getVersionId()).thenReturn(VERSION_ID.toString());
        when(projection.getVersionAssetId()).thenReturn(ASSET_ID.toString());
        when(projection.getVersionNumber()).thenReturn(3);
        when(projection.getStorageProviderId()).thenReturn("local");
        when(projection.getStorageKey()).thenReturn("objects/chapter.mp3");
        when(projection.getContentHash()).thenReturn(HASH);
        when(projection.getMimeType()).thenReturn("audio/mpeg");
        when(projection.getSizeBytes()).thenReturn(1234L);

        MediaAssetContentDeliverySnapshot result = adapter().findByAssetId(ASSET_ID).orElseThrow();

        assertThat(result).isEqualTo(new MediaAssetContentDeliverySnapshot(
                ASSET_ID, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 3,
                VERSION_ID, ASSET_ID, 3, "local", "objects/chapter.mp3",
                HASH, "audio/mpeg", 1234L
        ));
        verify(repository).findContentDelivery(ASSET_ID.toString());
    }

    @Test
    void preservesNullableVersionFieldsForBrokenCurrentVersionReference() {
        when(repository.findContentDelivery(ASSET_ID.toString())).thenReturn(Optional.of(projection));
        when(projection.getAssetId()).thenReturn(ASSET_ID.toString());
        when(projection.getStatus()).thenReturn("ACTIVE");
        when(projection.getVisibility()).thenReturn("PUBLIC");
        when(projection.getCurrentVersionNumber()).thenReturn(2);
        when(projection.getVersionNumber()).thenReturn(null);
        when(projection.getSizeBytes()).thenReturn(null);

        MediaAssetContentDeliverySnapshot result = adapter().findByAssetId(ASSET_ID).orElseThrow();

        assertThat(result.versionId()).isNull();
        assertThat(result.versionAssetId()).isNull();
        assertThat(result.versionNumber()).isNull();
        assertThat(result.storageProviderId()).isNull();
        assertThat(result.storageKey()).isNull();
        assertThat(result.contentHash()).isNull();
        assertThat(result.mimeType()).isNull();
        assertThat(result.sizeBytes()).isNull();
    }

    @Test
    void queryIsAssetDrivenOneQueryLeftJoinForDeclaredCurrentVersion() throws Exception {
        Method method = SpringDataMediaAssetContentDeliveryQueryRepository.class.getMethod(
                "findContentDelivery", String.class
        );
        String query = method.getAnnotation(Query.class).value();

        assertThat(query).contains(
                "from media_assets a",
                "left join media_asset_versions v",
                "v.asset_id = a.id",
                "v.version_number = a.current_version_number",
                "where a.id = :assetId",
                "v.storage_provider_id as storageProviderId",
                "v.storage_key as storageKey",
                "v.content_hash as contentHash",
                "v.mime_type as mimeType",
                "v.size_bytes as sizeBytes"
        );
    }

    private MediaAssetContentDeliveryQueryPersistenceAdapter adapter() {
        return new MediaAssetContentDeliveryQueryPersistenceAdapter(repository);
    }
}
