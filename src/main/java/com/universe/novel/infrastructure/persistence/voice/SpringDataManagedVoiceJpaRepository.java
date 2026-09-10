package com.universe.novel.infrastructure.persistence.voice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataManagedVoiceJpaRepository extends JpaRepository<ManagedVoiceJpaEntity, String> {

    Optional<ManagedVoiceJpaEntity> findByVoiceKey(String voiceKey);

    Optional<ManagedVoiceJpaEntity> findByDefaultVoiceTrue();

    List<ManagedVoiceJpaEntity> findAllByOrderByDisplayOrderAscCreatedAtAsc();

    List<ManagedVoiceJpaEntity> findAllByStatusOrderByDisplayOrderAscCreatedAtAsc(String status);

    @Query("""
            SELECT v.voiceKey AS voiceKey,
                   v.displayName AS displayName,
                   v.defaultVoice AS defaultVoice
            FROM ManagedVoiceJpaEntity v
            WHERE v.status = :status
            ORDER BY v.displayOrder ASC, v.createdAt ASC, v.id ASC
            """)
    List<PublicManagedVoiceCatalogProjection> findPublicCatalogByStatus(@Param("status") String status);

    boolean existsByVoiceKey(String voiceKey);

    @Query("SELECT COALESCE(MAX(v.displayOrder), 0) FROM ManagedVoiceJpaEntity v")
    int findMaxDisplayOrder();
}
