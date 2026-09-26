package com.universe.wiki.infrastructure.persistence.saved;

import com.universe.wiki.contracts.dto.saved.SavedWikiArticleItemDTO;
import com.universe.wiki.contracts.dto.saved.SavedWikiArticlePageDTO;
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
@DisplayName("WikiSavedArticlesQueryPersistenceAdapter Unit Tests")
class WikiSavedArticlesQueryPersistenceAdapterTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID SAVED_1_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID SAVED_2_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final UUID ARTICLE_1_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
    private static final UUID ARTICLE_2_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
    private static final UUID COVER_MEDIA_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @Mock
    private SpringDataWikiSavedArticleJpaRepository repository;

    private WikiSavedArticlesQueryPersistenceAdapter queryAdapter;

    @BeforeEach
    void setUp() {
        queryAdapter = new WikiSavedArticlesQueryPersistenceAdapter(repository);
    }

    @Test
    @DisplayName("Ánh xạ chính xác bài viết AVAILABLE và UNAVAILABLE mà không rò rỉ metadata")
    void shouldMapAvailableAndUnavailableArticlesCorrectlyWithoutMetadataLeakage() {
        WikiSavedArticleItemProjection proj1 = mock(WikiSavedArticleItemProjection.class);
        when(proj1.getSavedId()).thenReturn(SAVED_1_ID.toString());
        when(proj1.getArticleId()).thenReturn(ARTICLE_1_ID.toString());
        when(proj1.getSavedAt()).thenReturn(NOW);
        when(proj1.getArticleStatus()).thenReturn("PUBLISHED");
        when(proj1.getTitle()).thenReturn("Trần Bình An");
        when(proj1.getSlug()).thenReturn("tran-binh-an");
        when(proj1.getArticleType()).thenReturn("CHARACTER");
        when(proj1.getSummary()).thenReturn("Nhân vật chính");
        when(proj1.getCoverMediaAssetId()).thenReturn(COVER_MEDIA_ID.toString());
        when(proj1.getCoverPositionX()).thenReturn(50);
        when(proj1.getCoverPositionY()).thenReturn(30);

        WikiSavedArticleItemProjection proj2 = mock(WikiSavedArticleItemProjection.class);
        when(proj2.getSavedId()).thenReturn(SAVED_2_ID.toString());
        when(proj2.getArticleId()).thenReturn(ARTICLE_2_ID.toString());
        when(proj2.getSavedAt()).thenReturn(NOW.minusSeconds(3600));
        when(proj2.getArticleStatus()).thenReturn("DRAFT");

        PageImpl<WikiSavedArticleItemProjection> page = new PageImpl<>(
                List.of(proj1, proj2),
                PageRequest.of(0, 20),
                2
        );

        when(repository.findSavedArticlesByUserId(eq(USER_ID.toString()), any(Pageable.class)))
                .thenReturn(page);

        SavedWikiArticlePageDTO result = queryAdapter.findSavedArticles(USER_ID, 0, 20);

        assertThat(result.items()).hasSize(2);

        // 1. Article 1: AVAILABLE
        SavedWikiArticleItemDTO item1 = result.items().get(0);
        assertThat(item1.savedId()).isEqualTo(SAVED_1_ID);
        assertThat(item1.articleId()).isEqualTo(ARTICLE_1_ID);
        assertThat(item1.available()).isTrue();
        assertThat(item1.title()).isEqualTo("Trần Bình An");
        assertThat(item1.slug()).isEqualTo("tran-binh-an");
        assertThat(item1.articleType()).isEqualTo("CHARACTER");
        assertThat(item1.summary()).isEqualTo("Nhân vật chính");
        assertThat(item1.coverMediaAssetId()).isEqualTo(COVER_MEDIA_ID);
        assertThat(item1.coverPositionX()).isEqualTo(50);
        assertThat(item1.coverPositionY()).isEqualTo(30);

        // 2. Article 2: UNAVAILABLE - tuyệt đối không rò rỉ tiêu đề, tóm tắt hay cover
        SavedWikiArticleItemDTO item2 = result.items().get(1);
        assertThat(item2.savedId()).isEqualTo(SAVED_2_ID);
        assertThat(item2.articleId()).isEqualTo(ARTICLE_2_ID);
        assertThat(item2.available()).isFalse();
        assertThat(item2.title()).isNull();
        assertThat(item2.slug()).isNull();
        assertThat(item2.articleType()).isNull();
        assertThat(item2.summary()).isNull();
        assertThat(item2.coverMediaAssetId()).isNull();
        assertThat(item2.coverPositionX()).isEqualTo(50);
        assertThat(item2.coverPositionY()).isEqualTo(50);
    }

    @Test
    @DisplayName("Trả về trang rỗng khi userId là null")
    void shouldReturnEmptyPageWhenUserIdIsNull() {
        SavedWikiArticlePageDTO result = queryAdapter.findSavedArticles(null, 0, 20);

        assertThat(result.items()).isEmpty();
        assertThat(result.totalElements()).isEqualTo(0);
    }
}
