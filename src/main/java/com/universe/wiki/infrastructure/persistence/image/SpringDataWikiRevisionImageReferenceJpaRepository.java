package com.universe.wiki.infrastructure.persistence.image;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface
        SpringDataWikiRevisionImageReferenceJpaRepository
        extends JpaRepository<
                WikiRevisionImageReferenceJpaEntity,
                String
        > {

    List<WikiRevisionImageReferenceJpaEntity>
            findByRevisionId(
                    String revisionId
            );
}