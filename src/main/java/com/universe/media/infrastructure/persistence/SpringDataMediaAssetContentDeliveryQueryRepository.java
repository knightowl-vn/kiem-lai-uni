package com.universe.media.infrastructure.persistence;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataMediaAssetContentDeliveryQueryRepository
        extends Repository<MediaAssetJpaEntity, String> {

    @Query(value = """
            select
                a.id as assetId,
                a.status as status,
                a.visibility as visibility,
                a.current_version_number as currentVersionNumber,
                v.id as versionId,
                v.asset_id as versionAssetId,
                v.version_number as versionNumber,
                v.storage_provider_id as storageProviderId,
                v.storage_key as storageKey,
                v.content_hash as contentHash,
                v.mime_type as mimeType,
                v.size_bytes as sizeBytes
            from media_assets a
            left join media_asset_versions v
                on v.asset_id = a.id
                and v.version_number = a.current_version_number
            where a.id = :assetId
            """, nativeQuery = true)
    Optional<MediaAssetContentDeliveryProjection> findContentDelivery(
            @Param("assetId") String assetId
    );
}
