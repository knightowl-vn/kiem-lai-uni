package com.universe.wiki.infrastructure.persistence.saved;

import com.universe.wiki.application.ports.WikiSavedArticlesQueryPort;
import com.universe.wiki.contracts.dto.saved.SavedWikiArticleItemDTO;
import com.universe.wiki.contracts.dto.saved.SavedWikiArticlePageDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter persistence thực thi WikiSavedArticlesQueryPort cho các truy vấn đọc phân trang.
 *
 * Đảm bảo:
 * 1. Phân trang và sắp xếp mới nhất lên đầu: createdAt DESC, id DESC;
 * 2. Phân biệt rõ AVAILABLE (bài viết đang PUBLISHED) và UNAVAILABLE (bài viết đã gỡ/lưu trữ);
 * 3. Tuyệt đối không để lộ thông tin nhạy cảm (metadata/tiêu đề/ảnh) của bài viết UNAVAILABLE.
 */
@Component
@Transactional(readOnly = true)
public class WikiSavedArticlesQueryPersistenceAdapter implements WikiSavedArticlesQueryPort {

    private final SpringDataWikiSavedArticleJpaRepository repository;

    public WikiSavedArticlesQueryPersistenceAdapter(
            SpringDataWikiSavedArticleJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "SpringDataWikiSavedArticleJpaRepository không được để trống."
        );
    }

    @Override
    public SavedWikiArticlePageDTO findSavedArticles(UUID userId, int page, int size) {
        if (userId == null) {
            return new SavedWikiArticlePageDTO(
                    List.of(),
                    page,
                    size,
                    0,
                    0,
                    true,
                    true
            );
        }

        Pageable pageable = PageRequest.of(Math.max(0, page), Math.max(1, size));
        Page<WikiSavedArticleItemProjection> resultPage =
                repository.findSavedArticlesByUserId(userId.toString(), pageable);

        List<SavedWikiArticleItemDTO> items = resultPage.getContent().stream()
                .map(this::toDTO)
                .toList();

        return new SavedWikiArticlePageDTO(
                items,
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages(),
                resultPage.isFirst(),
                resultPage.isLast()
        );
    }

    private SavedWikiArticleItemDTO toDTO(WikiSavedArticleItemProjection projection) {
        UUID savedId = UUID.fromString(projection.getSavedId());
        UUID articleId = UUID.fromString(projection.getArticleId());
        boolean available = "PUBLISHED".equalsIgnoreCase(projection.getArticleStatus());

        if (available) {
            UUID coverMediaAssetId = projection.getCoverMediaAssetId() != null
                    ? UUID.fromString(projection.getCoverMediaAssetId())
                    : null;
            int coverPositionX = projection.getCoverPositionX() != null ? projection.getCoverPositionX() : 50;
            int coverPositionY = projection.getCoverPositionY() != null ? projection.getCoverPositionY() : 50;

            return SavedWikiArticleItemDTO.available(
                    savedId,
                    articleId,
                    projection.getSavedAt(),
                    projection.getTitle(),
                    projection.getSlug(),
                    projection.getArticleType(),
                    projection.getSummary(),
                    coverMediaAssetId,
                    coverPositionX,
                    coverPositionY
            );
        }

        return SavedWikiArticleItemDTO.unavailable(
                savedId,
                articleId,
                projection.getSavedAt()
        );
    }
}
