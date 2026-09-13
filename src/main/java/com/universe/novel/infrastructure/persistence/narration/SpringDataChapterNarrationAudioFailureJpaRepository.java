package com.universe.novel.infrastructure.persistence.narration;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataChapterNarrationAudioFailureJpaRepository
        extends JpaRepository<ChapterNarrationAudioFailureJpaEntity, String> {

    Optional<ChapterNarrationAudioFailureJpaEntity> findBySegmentIdAndManagedVoiceId(String segmentId, String managedVoiceId);

    List<ChapterNarrationAudioFailureJpaEntity> findBySegmentIdInAndManagedVoiceId(Collection<String> segmentIds, String managedVoiceId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("DELETE FROM ChapterNarrationAudioFailureJpaEntity f " +
           "WHERE f.segmentId = :segmentId " +
           "  AND f.managedVoiceId = :managedVoiceId " +
           "  AND f.attemptedSynthesisRevision <= :successfulRevision")
    int deleteSupersededBySuccessfulRevision(
            @Param("segmentId") String segmentId,
            @Param("managedVoiceId") String managedVoiceId,
            @Param("successfulRevision") long successfulRevision
    );
}
