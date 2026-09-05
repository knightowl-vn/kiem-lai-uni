package com.universe.novel.infrastructure.persistence.voice;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface SpringDataManagedVoiceJpaRepository extends JpaRepository<ManagedVoiceJpaEntity, String> {

    Optional<ManagedVoiceJpaEntity> findByVoiceKey(String voiceKey);

    Optional<ManagedVoiceJpaEntity> findByDefaultVoiceTrue();

    List<ManagedVoiceJpaEntity> findAllByOrderByDisplayOrderAscCreatedAtAsc();

    List<ManagedVoiceJpaEntity> findAllByStatusOrderByDisplayOrderAscCreatedAtAsc(String status);

    boolean existsByVoiceKey(String voiceKey);
}
