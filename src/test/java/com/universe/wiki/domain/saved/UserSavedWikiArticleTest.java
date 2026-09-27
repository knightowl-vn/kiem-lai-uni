package com.universe.wiki.domain.saved;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("UserSavedWikiArticle Domain Aggregate Tests")
class UserSavedWikiArticleTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID ARTICLE_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-09-16T12:00:00Z");

    @Test
    @DisplayName("Tạo mới UserSavedWikiArticle hợp lệ")
    void shouldCreateValidInstance() {
        UserSavedWikiArticle savedArticle = UserSavedWikiArticle.create(
                ID,
                USER_ID,
                ARTICLE_ID,
                NOW
        );

        assertThat(savedArticle).isNotNull();
        assertThat(savedArticle.getId()).isEqualTo(ID);
        assertThat(savedArticle.getUserId()).isEqualTo(USER_ID);
        assertThat(savedArticle.getArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(savedArticle.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Khôi phục (rehydrate) UserSavedWikiArticle từ tầng lưu trữ")
    void shouldRehydrateInstance() {
        UserSavedWikiArticle rehydrated = UserSavedWikiArticle.rehydrate(
                ID,
                USER_ID,
                ARTICLE_ID,
                NOW
        );

        assertThat(rehydrated).isNotNull();
        assertThat(rehydrated.getId()).isEqualTo(ID);
        assertThat(rehydrated.getUserId()).isEqualTo(USER_ID);
        assertThat(rehydrated.getArticleId()).isEqualTo(ARTICLE_ID);
        assertThat(rehydrated.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("Từ chối khởi tạo khi có trường null")
    void shouldRejectNullFields() {
        assertThatThrownBy(() -> UserSavedWikiArticle.create(null, USER_ID, ARTICLE_ID, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết Wiki đã lưu không được để trống.");

        assertThatThrownBy(() -> UserSavedWikiArticle.create(ID, null, ARTICLE_ID, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID người dùng không được để trống.");

        assertThatThrownBy(() -> UserSavedWikiArticle.create(ID, USER_ID, null, NOW))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ID bài viết không được để trống.");

        assertThatThrownBy(() -> UserSavedWikiArticle.create(ID, USER_ID, ARTICLE_ID, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("Thời gian lưu bài viết không được để trống.");
    }

    @Test
    @DisplayName("Kiểm tra equals và hashCode dựa trên id")
    void shouldImplementEqualsAndHashCodeBasedOnId() {
        UserSavedWikiArticle article1 = UserSavedWikiArticle.create(ID, USER_ID, ARTICLE_ID, NOW);
        UserSavedWikiArticle article2 = UserSavedWikiArticle.rehydrate(ID, USER_ID, ARTICLE_ID, NOW.plusSeconds(60));
        UserSavedWikiArticle article3 = UserSavedWikiArticle.create(UUID.randomUUID(), USER_ID, ARTICLE_ID, NOW);

        assertThat(article1).isEqualTo(article2);
        assertThat(article1.hashCode()).isEqualTo(article2.hashCode());
        assertThat(article1).isNotEqualTo(article3);
    }
}
