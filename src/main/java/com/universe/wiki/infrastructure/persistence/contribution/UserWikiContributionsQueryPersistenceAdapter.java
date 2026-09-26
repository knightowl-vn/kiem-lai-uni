package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.wiki.application.ports.UserWikiContributionsQueryPort;
import com.universe.wiki.contracts.dto.contribution.UserWikiContributionItemDTO;
import com.universe.wiki.contracts.dto.contribution.UserWikiContributionPageDTO;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Adapter persistence thực thi UserWikiContributionsQueryPort cho các truy vấn đọc phân trang.
 *
 * Đảm bảo:
 * 1. Phân trang và sắp xếp mới nhất lên đầu: createdAt DESC, id DESC;
 * 2. Đọc an toàn từ snapshot dữ liệu đóng góp đã lưu;
 * 3. Trả về DTO thuần túy, không để rò rỉ JPA entity ra application/web.
 */
@Component
@Transactional(readOnly = true)
public class UserWikiContributionsQueryPersistenceAdapter implements UserWikiContributionsQueryPort {

    private final SpringDataWikiContributionJpaRepository repository;

    public UserWikiContributionsQueryPersistenceAdapter(
            SpringDataWikiContributionJpaRepository repository
    ) {
        this.repository = Objects.requireNonNull(
                repository,
                "SpringDataWikiContributionJpaRepository không được để trống."
        );
    }

    @Override
    public UserWikiContributionPageDTO findByUserId(UUID userId, int page, int size) {
        if (userId == null) {
            return new UserWikiContributionPageDTO(
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
        Page<WikiContributionJpaEntity> resultPage =
                repository.findBySubmittedByUserId(userId.toString(), pageable);

        List<UserWikiContributionItemDTO> items = resultPage.getContent().stream()
                .map(this::toDTO)
                .toList();

        return new UserWikiContributionPageDTO(
                items,
                resultPage.getNumber(),
                resultPage.getSize(),
                resultPage.getTotalElements(),
                resultPage.getTotalPages(),
                resultPage.isFirst(),
                resultPage.isLast()
        );
    }

    private UserWikiContributionItemDTO toDTO(WikiContributionJpaEntity entity) {
        UUID id = UUID.fromString(entity.getId());
        UUID articleId = entity.getArticleId() != null ? UUID.fromString(entity.getArticleId()) : null;

        return new UserWikiContributionItemDTO(
                id,
                articleId,
                entity.getArticleTitleSnapshot(),
                entity.getArticleSlugSnapshot(),
                entity.getArticleTypeSnapshot(),
                entity.getContextType(),
                entity.getContributionType(),
                entity.getMessage(),
                entity.getStatus(),
                entity.getCreatedAt(),
                entity.getResolutionNote(),
                entity.getResolvedAt()
        );
    }
}
