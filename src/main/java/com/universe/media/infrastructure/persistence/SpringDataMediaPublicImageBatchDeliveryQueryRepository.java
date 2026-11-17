package com.universe.media.infrastructure.persistence;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface SpringDataMediaPublicImageBatchDeliveryQueryRepository
        extends Repository<MediaAssetJpaEntity, String> {

    @Query(value = """
            select
                a.id as assetId,
                a.status as status,
                a.visibility as visibility,
                a.media_type as mediaType,
                a.current_version_number as currentVersionNumber,
                v.id as versionId,
                v.version_number as versionNumber,
                v.storage_provider_id as storageProviderId,
                v.public_url as versionPublicUrl,
                iv.id as variantId,
                iv.public_url as variantPublicUrl
            from media_assets a
            join media_asset_versions v
                on v.asset_id = a.id
                and v.version_number = a.current_version_number
            left join media_image_variants iv
                on iv.version_id = v.id
                and iv.variant_key = :variantKey
            where a.id in (:assetIds)
              and a.media_type = 'IMAGE'
              and a.status = 'ACTIVE'
              and a.visibility = 'PUBLIC'
              and v.storage_provider_id = 'cloudinary'
            """, nativeQuery = true)
    List<MediaPublicImageBatchDeliveryProjection> findBatchDeliveryProjections(
            @Param("assetIds") Collection<String> assetIds,
            @Param("variantKey") String variantKey
    );
}
