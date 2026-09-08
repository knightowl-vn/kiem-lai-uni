package com.universe.novel.infrastructure.persistence.narration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SpringDataChapterNarrationAudioJpaRepository
        extends JpaRepository<ChapterNarrationAudioJpaEntity, String> {

    Optional<ChapterNarrationAudioJpaEntity> findBySegmentIdAndManagedVoiceId(String segmentId, String managedVoiceId);

    List<ChapterNarrationAudioJpaEntity> findBySegmentIdInAndManagedVoiceId(Collection<String> segmentIds, String managedVoiceId);

    List<ChapterNarrationAudioJpaEntity> findBySegmentIdIn(Collection<String> segmentIds);

    List<ChapterNarrationAudioJpaEntity> findBySegmentId(String segmentId);

    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN TRUE ELSE FALSE END FROM ChapterNarrationAudioJpaEntity a WHERE a.mediaAssetId = :mediaAssetId AND a.id <> :excludingId")
    boolean existsByMediaAssetIdAndIdNot(@Param("mediaAssetId") String mediaAssetId, @Param("excludingId") String excludingId);

    @Query("SELECT CASE WHEN COUNT(a) > 0 THEN TRUE ELSE FALSE END FROM ChapterNarrationAudioJpaEntity a WHERE a.mediaAssetId = :mediaAssetId")
    boolean existsByMediaAssetId(@Param("mediaAssetId") String mediaAssetId);
}
