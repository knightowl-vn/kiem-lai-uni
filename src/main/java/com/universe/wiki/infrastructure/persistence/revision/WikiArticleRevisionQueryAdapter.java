package com.universe.wiki.infrastructure.persistence.revision;

import com.universe.wiki.application.ports.WikiArticleRevisionQueryPort;
import com.universe.wiki.contracts.dto.WikiArticleRevisionDetailDTO;
import com.universe.wiki.contracts.dto.WikiArticleRevisionListItemDTO;
import com.universe.wiki.contracts.dto.WikiArticleRevisionPageDTO;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class WikiArticleRevisionQueryAdapter
        implements WikiArticleRevisionQueryPort {

    private final SpringDataWikiArticleRevisionJpaRepository
            repository;

    public WikiArticleRevisionQueryAdapter(
            SpringDataWikiArticleRevisionJpaRepository repository
    ) {
        this.repository =
                repository;
    }

    /**
     * Lấy danh sách revision của một bài viết,
     * sắp xếp revision mới nhất đứng trước.
     */
    @Override
    public WikiArticleRevisionPageDTO
            findPageByArticleId(
                    UUID articleId,
                    int page,
                    int size
            ) {

        if (articleId == null) {
            return new WikiArticleRevisionPageDTO(
                    List.of(),
                    page,
                    size,
                    0L,
                    0,
                    true,
                    true
            );
        }

        Pageable pageable =
                PageRequest.of(
                        page,
                        size,
                        Sort.by(
                                Sort.Direction.DESC,
                                "revisionNumber"
                        )
                );

        Page<WikiArticleRevisionJpaEntity>
                entityPage =
                repository.findByArticleId(
                        articleId.toString(),
                        pageable
                );

        List<WikiArticleRevisionListItemDTO>
                items =
                entityPage
                        .getContent()
                        .stream()
                        .map(this::toListItemDTO)
                        .toList();

        return new WikiArticleRevisionPageDTO(
                items,
                entityPage.getNumber(),
                entityPage.getSize(),
                entityPage.getTotalElements(),
                entityPage.getTotalPages(),
                entityPage.isFirst(),
                entityPage.isLast()
        );
    }

    /**
     * Lấy đầy đủ snapshot của một revision cụ thể.
     */
    @Override
    public Optional<WikiArticleRevisionDetailDTO>
            findDetailByArticleIdAndRevisionNumber(
                    UUID articleId,
                    long revisionNumber
            ) {

        if (articleId == null
                || revisionNumber < 1L) {

            return Optional.empty();
        }

        return repository
                .findByArticleIdAndRevisionNumber(
                        articleId.toString(),
                        revisionNumber
                )
                .map(this::toDetailDTO);
    }

    /**
     * Ánh xạ entity thành DTO rút gọn
     * dùng cho màn hình danh sách revision.
     */
    private WikiArticleRevisionListItemDTO
            toListItemDTO(
                    WikiArticleRevisionJpaEntity entity
            ) {

        return new WikiArticleRevisionListItemDTO(
                UUID.fromString(
                        entity.getId()
                ),
                UUID.fromString(
                        entity.getArticleId()
                ),
                entity.getRevisionNumber(),
                entity.getStatus(),
                entity.getChangeType(),
                entity.getEditSummary(),
                UUID.fromString(
                        entity.getEditedBy()
                ),
                entity.getCreatedAt()
        );
    }

    /**
     * Ánh xạ entity thành DTO đầy đủ
     * dùng cho màn hình chi tiết revision.
     */
    private WikiArticleRevisionDetailDTO
            toDetailDTO(
                    WikiArticleRevisionJpaEntity entity
            ) {

        return new WikiArticleRevisionDetailDTO(
                UUID.fromString(
                        entity.getId()
                ),
                UUID.fromString(
                        entity.getArticleId()
                ),
                entity.getRevisionNumber(),
                entity.getTitle(),
                entity.getSlug(),
                entity.getArticleType(),
                entity.getSummary(),
                entity.getContent(),
                entity.getStatus(),
                entity.getChangeType(),
                entity.getEditSummary(),
                UUID.fromString(
                        entity.getEditedBy()
                ),
                entity.getCreatedAt()
        );
    }
}