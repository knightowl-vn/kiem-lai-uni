package com.universe.wiki.infrastructure.persistence.contribution;

import com.universe.wiki.contracts.dto.contribution.UserWikiContributionItemDTO;
import com.universe.wiki.contracts.dto.contribution.UserWikiContributionPageDTO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("UserWikiContributionsQueryPersistenceAdapter Unit Tests")
class UserWikiContributionsQueryPersistenceAdapterTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID CONTRIBUTION_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ARTICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-26T12:00:00Z");

    @Mock
    private SpringDataWikiContributionJpaRepository repository;

    private UserWikiContributionsQueryPersistenceAdapter queryAdapter;

    @BeforeEach
    void setUp() {
        queryAdapter = new UserWikiContributionsQueryPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("Ánh xạ chính xác đóng góp từ entity sang DTO")
    void shouldMapContributionEntityToDtoCorrectly() {
        WikiContributionJpaEntity entity = mock(WikiContributionJpaEntity.class);
        when(entity.getId()).thenReturn(CONTRIBUTION_ID.toString());
        when(entity.getArticleId()).thenReturn(ARTICLE_ID.toString());
        when(entity.getArticleTitleSnapshot()).thenReturn("Trần Bình An");
        when(entity.getArticleSlugSnapshot()).thenReturn("tran-binh-an");
        when(entity.getArticleTypeSnapshot()).thenReturn("CHARACTER");
        when(entity.getContextType()).thenReturn("FULL_ARTICLE");
        when(entity.getContributionType()).thenReturn("INCORRECT_INFORMATION");
        when(entity.getMessage()).thenReturn("Cần sửa lỗi");
        when(entity.getStatus()).thenReturn("RESOLVED");
        when(entity.getCreatedAt()).thenReturn(NOW);
        when(entity.getResolutionNote()).thenReturn("Đã sửa");
        when(entity.getResolvedAt()).thenReturn(NOW.plusSeconds(1800));

        PageImpl<WikiContributionJpaEntity> page = new PageImpl<>(
                List.of(entity),
                PageRequest.of(0, 20),
                1
        );

        when(repository.findBySubmittedByUserId(eq(USER_ID.toString()), any(Pageable.class)))
                .thenReturn(page);

        UserWikiContributionPageDTO result = queryAdapter.findByUserId(USER_ID, 0, 20);

        assertThat(result.items()).hasSize(1);
        UserWikiContributionItemDTO dto = result.items().get(0);
        assertThat(dto.id()).isEqualTo(CONTRIBUTION_ID);
        assertThat(dto.articleId()).isEqualTo(ARTICLE_ID);
        assertThat(dto.articleTitleSnapshot()).isEqualTo("Trần Bình An");
        assertThat(dto.articleSlugSnapshot()).isEqualTo("tran-binh-an");
        assertThat(dto.articleTypeSnapshot()).isEqualTo("CHARACTER");
        assertThat(dto.contextType()).isEqualTo("FULL_ARTICLE");
        assertThat(dto.contributionType()).isEqualTo("INCORRECT_INFORMATION");
        assertThat(dto.message()).isEqualTo("Cần sửa lỗi");
        assertThat(dto.status()).isEqualTo("RESOLVED");
        assertThat(dto.createdAt()).isEqualTo(NOW);
        assertThat(dto.resolutionNote()).isEqualTo("Đã sửa");
        assertThat(dto.resolvedAt()).isEqualTo(NOW.plusSeconds(1800));
    }

    @Test
    @DisplayName("Trả về trang rỗng khi userId là null")
    void shouldReturnEmptyPageWhenUserIdIsNull() {
        UserWikiContributionPageDTO result = queryAdapter.findByUserId(null, 0, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(0);
    }
}
