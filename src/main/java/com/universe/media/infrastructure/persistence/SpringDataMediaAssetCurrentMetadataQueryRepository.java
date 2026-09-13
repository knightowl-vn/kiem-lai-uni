package com.universe.media.infrastructure.persistence;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SpringDataMediaAssetCurrentMetadataQueryRepository
        extends Repository<MediaAssetJpaEntity, String> {

    @Query(value = """
            select
                a.id as assetId,
                a.status as status,
                a.visibility as visibility,
                a.current_version_number as currentVersionNumber,
                v.asset_id as declaredCurrentVersionAssetId,
                v.version_number as declaredCurrentVersionNumber
            from media_assets a
            left join media_asset_versions v
                on v.asset_id = a.id
                and v.version_number = a.current_version_number
            where a.id = :assetId
            """, nativeQuery = true)
    Optional<MediaAssetCurrentMetadataProjection> findCurrentMetadata(
            @Param("assetId") String assetId
    );
}
