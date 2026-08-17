package com.universe.wiki.infrastructure.persistence.image;

import com.universe.wiki.application.ports
        .WikiMarkdownImageExtractor;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
@Transactional
public class WikiImageReferenceSynchronizer {

    private final WikiMarkdownImageExtractor
            imageExtractor;

    private final SpringDataWikiImageJpaRepository
            imageRepository;

    private final
    SpringDataWikiArticleImageReferenceJpaRepository
            articleReferenceRepository;

    private final
    SpringDataWikiRevisionImageReferenceJpaRepository
            revisionReferenceRepository;


    public WikiImageReferenceSynchronizer(
            WikiMarkdownImageExtractor imageExtractor,
            SpringDataWikiImageJpaRepository imageRepository,
            SpringDataWikiArticleImageReferenceJpaRepository
                    articleReferenceRepository,
            SpringDataWikiRevisionImageReferenceJpaRepository
                    revisionReferenceRepository
    ) {
        this.imageExtractor =
                imageExtractor;

        this.imageRepository =
                imageRepository;

        this.articleReferenceRepository =
                articleReferenceRepository;

        this.revisionReferenceRepository =
                revisionReferenceRepository;
    }


    /*
     * =====================================================
     * ARTICLE REFERENCES
     * =====================================================
     */

    public void syncArticleReferences(
            UUID articleId,
            String markdown
    ) {
        if (articleId == null) {
            return;
        }

        Set<String> desiredImageIds =
                resolveImageIds(
                        markdown
                );

        String articleIdValue =
                articleId.toString();

        List<WikiArticleImageReferenceJpaEntity>
                existingReferences =
                articleReferenceRepository
                        .findByArticleId(
                                articleIdValue
                        );

        Set<String> existingImageIds =
                existingReferences
                        .stream()
                        .map(
                                WikiArticleImageReferenceJpaEntity
                                        ::getImageId
                        )
                        .collect(
                                Collectors.toSet()
                        );


        /*
         * Xóa reference không còn xuất hiện
         * trong content hiện tại.
         */
        List<WikiArticleImageReferenceJpaEntity>
                referencesToDelete =
                existingReferences
                        .stream()
                        .filter(
                                reference ->
                                        !desiredImageIds.contains(
                                                reference.getImageId()
                                        )
                        )
                        .toList();

        if (!referencesToDelete.isEmpty()) {
            articleReferenceRepository
                    .deleteAll(
                            referencesToDelete
                    );
        }


        /*
         * Thêm reference mới.
         */
        for (
                String imageId :
                desiredImageIds
        ) {
            if (
                    existingImageIds.contains(
                            imageId
                    )
            ) {
                continue;
            }

            WikiArticleImageReferenceJpaEntity
                    reference =
                    new WikiArticleImageReferenceJpaEntity();

            reference.setId(
                    UUID.randomUUID()
                            .toString()
            );

            reference.setArticleId(
                    articleIdValue
            );

            reference.setImageId(
                    imageId
            );

            articleReferenceRepository
                    .save(
                            reference
                    );
        }
    }


    /*
     * =====================================================
     * REVISION REFERENCES
     * =====================================================
     */

    public void syncRevisionReferences(
            UUID revisionId,
            String markdown
    ) {
        if (revisionId == null) {
            return;
        }

        Set<String> desiredImageIds =
                resolveImageIds(
                        markdown
                );

        String revisionIdValue =
                revisionId.toString();

        List<WikiRevisionImageReferenceJpaEntity>
                existingReferences =
                revisionReferenceRepository
                        .findByRevisionId(
                                revisionIdValue
                        );

        Set<String> existingImageIds =
                existingReferences
                        .stream()
                        .map(
                                WikiRevisionImageReferenceJpaEntity
                                        ::getImageId
                        )
                        .collect(
                                Collectors.toSet()
                        );


        List<WikiRevisionImageReferenceJpaEntity>
                referencesToDelete =
                existingReferences
                        .stream()
                        .filter(
                                reference ->
                                        !desiredImageIds.contains(
                                                reference.getImageId()
                                        )
                        )
                        .toList();

        if (!referencesToDelete.isEmpty()) {
            revisionReferenceRepository
                    .deleteAll(
                            referencesToDelete
                    );
        }


        for (
                String imageId :
                desiredImageIds
        ) {
            if (
                    existingImageIds.contains(
                            imageId
                    )
            ) {
                continue;
            }

            WikiRevisionImageReferenceJpaEntity
                    reference =
                    new WikiRevisionImageReferenceJpaEntity();

            reference.setId(
                    UUID.randomUUID()
                            .toString()
            );

            reference.setRevisionId(
                    revisionIdValue
            );

            reference.setImageId(
                    imageId
            );

            revisionReferenceRepository
                    .save(
                            reference
                    );
        }
    }


    /*
     * =====================================================
     * MARKDOWN URL → WIKI IMAGE ID
     * =====================================================
     */

    private Set<String> resolveImageIds(
            String markdown
    ) {
        Set<String> imageUrls =
                imageExtractor
                        .extractImageUrls(
                                markdown
                        );

        if (imageUrls.isEmpty()) {
            return Set.of();
        }

        return imageRepository
                .findByUrlIn(
                        imageUrls
                )
                .stream()
                .map(
                        WikiImageJpaEntity::getId
                )
                .collect(
                        Collectors.toCollection(
                                HashSet::new
                        )
                );
    }
}