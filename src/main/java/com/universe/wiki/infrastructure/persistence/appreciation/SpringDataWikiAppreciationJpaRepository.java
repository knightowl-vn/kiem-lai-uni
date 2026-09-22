package com.universe.wiki.infrastructure.persistence.appreciation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SpringDataWikiAppreciationJpaRepository
        extends JpaRepository<WikiAppreciationJpaEntity, String> {

    Optional<WikiAppreciationJpaEntity> findByWikiArticleIdAndUserId(
            String wikiArticleId,
            String userId
    );

    boolean existsByWikiArticleIdAndUserId(
            String wikiArticleId,
            String userId
    );
}
