package com.universe.media.infrastructure.persistence;

import com.universe.media.application.ports.MediaAssetCurrentMetadataQueryPort.MediaAssetCurrentMetadataSnapshot;
import com.universe.media.contracts.dto.MediaAssetCurrentMetadataDTO;
import com.universe.media.domain.MediaAssetStatus;
import com.universe.media.domain.MediaVisibility;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MediaAssetCurrentMetadataQueryPersistenceAdapterTest {

    private static final UUID ASSET_ID = UUID.fromString("a2200000-0000-0000-0000-000000000001");

    @Mock
    private SpringDataMediaAssetCurrentMetadataQueryRepository repository;

    @Mock
    private MediaAssetCurrentMetadataProjection projection;

    @Test
    void mapsOnlyTheAssetAndDeclaredCurrentVersionProof() {
        when(repository.findCurrentMetadata(ASSET_ID.toString())).thenReturn(Optional.of(projection));
        when(projection.getAssetId()).thenReturn(ASSET_ID.toString());
        when(projection.getStatus()).thenReturn("ACTIVE");
        when(projection.getVisibility()).thenReturn("PUBLIC");
        when(projection.getCurrentVersionNumber()).thenReturn(4);
        when(projection.getDeclaredCurrentVersionAssetId()).thenReturn(ASSET_ID.toString());
        when(projection.getDeclaredCurrentVersionNumber()).thenReturn(4);

        MediaAssetCurrentMetadataSnapshot result = adapter().findByAssetId(ASSET_ID).orElseThrow();

        assertThat(result).isEqualTo(new MediaAssetCurrentMetadataSnapshot(
                ASSET_ID, MediaAssetStatus.ACTIVE, MediaVisibility.PUBLIC, 4, ASSET_ID, 4
        ));
        verify(repository).findCurrentMetadata(ASSET_ID.toString());
    }

    @Test
    void preservesNullableVersionFieldsForABrokenCurrentVersionReference() {
        when(repository.findCurrentMetadata(ASSET_ID.toString())).thenReturn(Optional.of(projection));
        when(projection.getAssetId()).thenReturn(ASSET_ID.toString());
        when(projection.getStatus()).thenReturn("ARCHIVED");
        when(projection.getVisibility()).thenReturn("RESTRICTED");
        when(projection.getCurrentVersionNumber()).thenReturn(3);
        when(projection.getDeclaredCurrentVersionAssetId()).thenReturn(null);
        when(projection.getDeclaredCurrentVersionNumber()).thenReturn(null);

        MediaAssetCurrentMetadataSnapshot result = adapter().findByAssetId(ASSET_ID).orElseThrow();

        assertThat(result.declaredCurrentVersionAssetId()).isNull();
        assertThat(result.declaredCurrentVersionNumber()).isNull();
        assertThat(result.status()).isEqualTo(MediaAssetStatus.ARCHIVED);
        assertThat(result.visibility()).isEqualTo(MediaVisibility.RESTRICTED);
    }

    @Test
    void queryIsOneAssetDrivenLeftJoinAgainstTheDeclaredCurrentVersion() throws Exception {
        Method method = SpringDataMediaAssetCurrentMetadataQueryRepository.class.getMethod(
                "findCurrentMetadata", String.class
        );
        String query = method.getAnnotation(Query.class).value();

        assertThat(query).contains(
                "from media_assets a",
                "left join media_asset_versions v",
                "v.asset_id = a.id",
                "v.version_number = a.current_version_number",
                "where a.id = :assetId",
                "a.status as status",
                "a.visibility as visibility",
                "a.current_version_number as currentVersionNumber"
        );
        assertThat(query).doesNotContain(
                "storage_provider_id", "storage_key", "content_hash", "original_filename", "public_url"
        );
    }

    @Test
    void publicDtoContainsNoStorageOrProviderMetadata() {
        assertThat(MediaAssetCurrentMetadataDTO.class.getRecordComponents())
                .extracting(RecordComponent::getName)
                .containsExactly("id", "status", "visibility", "currentVersionNumber");
    }

    private MediaAssetCurrentMetadataQueryPersistenceAdapter adapter() {
        return new MediaAssetCurrentMetadataQueryPersistenceAdapter(repository);
    }
}
